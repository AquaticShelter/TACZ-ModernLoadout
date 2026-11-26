package dev.l41nx.mwloadout.registry;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.SlotDefinition;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The creative-tab scan — what actually populates the catalog, replacing raw index scanning as
 * the primary source. Why tabs and not indices:
 *
 *  - TACZ only puts NON-hidden guns in its tabs, so packs' hidden=true guns (dev/base variants)
 *    never leak into pickers — the exact complaint with index scanning.
 *  - Tab stacks are FULLY FORMED: correct base item + correct NBT + resolved display name. For
 *    LRTactical melee/consumable/throwable "virtual items" that's everything the guessed-id
 *    seeding got wrong (the black-magenta icons were stacks pointing at ids the packs never
 *    registered) — scanning captures whatever the pack actually ships, whatever its namespace.
 *
 * Healing: a scanned LRT stack whose NBT id (MeleeWeaponId/ConsumableId/ThrowableId) matches an
 * existing entry's NBT id OVERWRITES that entry's gameId + nbt + displayName in place, keeping
 * the admin's category/count/enabled edits — so the shipped seed entries self-repair on first
 * scan instead of duplicating. Auto-added guns that vanish from tabs (pack removed / gun now
 * hidden) get disabled, not deleted — player presets referencing them degrade gracefully.
 */
public final class CreativeTabScanner {

    private CreativeTabScanner() {
    }

    /** Consecutive-miss counter per TACZ gameId, kept across scans for the server's lifetime.
     *  A single scan finding a gun absent from creative tabs is NOT reliable proof it's gone —
     *  addon gunpacks can register slightly after ServerStartedEvent fires (their own async load,
     *  a slow datapack reload racing the tab rebuild), so the automatic boot-time scan could catch
     *  a gun mid-registration and wrongly conclude it was removed. Requiring the SAME gameId to be
     *  missing on two scans in a row before disabling absorbs exactly that one-off startup hiccup
     *  — the class this protects the most is Blueprint children, since losing one silently degrades
     *  a switch into "slot went empty, refilled with something unrelated". */
    private static final java.util.Map<String, Integer> missStreak = new java.util.HashMap<>();
    private static final int DISABLE_AFTER_MISSES = 2;

    /** Summary of one scan pass, for the Admin Panel's SCANNER section (req. #12-16). Counts are
     *  catalog-wide (not just what changed this pass) so the UI can always show a fresh
     *  Total/Active/Stale readout without a separate query. */
    public record ScanResult(int added, int healed, int newlyStale, int recovered,
                              int totalEntries, int activeEntries, int staleEntries,
                              java.util.List<CatalogEntry> staleList) {
    }

    /** Backward-compatible wrapper — existing callers just want "how many entries changed".
     *  New code (Admin Panel SCANNER section) should call reconcile() directly for the full
     *  Total/Active/Stale breakdown. */
    public static int scan(MinecraftServer server, LoadoutRegistry registry) {
        ScanResult result = reconcile(server, registry);
        return result.added() + result.healed() + result.newlyStale() + result.recovered();
    }

    /** Full reconciliation pass (req. #12-16): rebuilds creative tabs, classifies every visible
     *  stack, flags entries missing two scans running as stale (see missStreak doc above),
     *  recovers any previously-stale entry whose gun reappeared, then reports a fresh
     *  catalog-wide Total/Active/Stale count so the admin UI never needs a separate query. */
    public static ScanResult reconcile(MinecraftServer server, LoadoutRegistry registry) {
        int added = 0;
        int healed = 0;
        try {
            CreativeModeTabs.tryRebuildTabContents(server.getWorldData().enabledFeatures(), true, server.registryAccess());
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Creative tab rebuild failed, falling back to index scan", e);
            WeaponCatalog.autoSeed(registry);
            return summarize(registry, 0, 0, 0, 0);
        }

        Set<String> gunsSeenInTabs = new HashSet<>();
        // v4.3.6 fix (bug #9): the ghost-cleanup pass below only ever looked at GUN entries, so an
        // LRTactical virtual item (kind ITEM, id "lrt_*") whose item disappeared from the tabs
        // (pack updated/removed the item) never got flagged stale or disabled — it just sat in
        // the catalog forever, still selectable, and kept surfacing as a "missing item" that
        // never cleared the way a ghost TACZ gun does. Tracked the same way guns are.
        Set<String> lrtItemsSeenInTabs = new HashSet<>();
        int recovered = 0;
        try {
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                for (ItemStack stack : tab.getDisplayItems()) {
                    int delta = classify(registry, stack, gunsSeenInTabs, lrtItemsSeenInTabs);
                    if (delta > 0) {
                        healed += delta; // classify() returning >0 covers both "new" and "healed" cases
                    }
                }
            }
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Creative tab scan failed", e);
            return summarize(registry, added, healed, 0, 0);
        }

        // Attachments are a separate index (not creative-tab display items), scanned up front so
        // the recovery/newly-stale passes below can reconcile them the same pass as guns/LRT
        // items — previously an uninstalled attachment pack's entries just sat enabled forever
        // (nothing ever disabled them), showing as untranslated "item.tacz.attachment" ghosts.
        Set<String> attachmentsSeenInTabs = new HashSet<>();
        healed += scanAttachments(registry, attachmentsSeenInTabs);

        // Recovery pass: any entry the SCANNER previously flagged stale whose gun/item/attachment
        // is visible again this pass gets un-flagged and re-enabled — a returned pack shouldn't
        // need an admin to notice and manually re-enable it.
        for (CatalogEntry entry : registry.catalogList()) {
            String lrtKey = lrtSeenKey(entry);
            boolean isLrtItem = lrtKey != null;
            boolean isAttachment = entry.kind == CatalogEntry.Kind.ATTACHMENT && entry.id.startsWith("att_");
            String seenKey = isLrtItem ? lrtKey : isAttachment ? entry.id : entry.gameId;
            Set<String> seenSet = isLrtItem ? lrtItemsSeenInTabs : isAttachment ? attachmentsSeenInTabs : gunsSeenInTabs;
            if (entry.stale && (entry.kind == CatalogEntry.Kind.GUN || isLrtItem || isAttachment) && seenSet.contains(seenKey)) {
                entry.stale = false;
                entry.staleSinceMillis = 0;
                entry.enabled = true;
                missStreak.remove(seenKey);
                recovered++;
                MWLoadoutMod.LOGGER.info("[MWLoadout] Recovered previously-stale {} {} — visible again",
                        isAttachment ? "attachment" : isLrtItem ? "item" : "gun", seenKey);
            }
        }

        // Guns/LRT items/attachments previously auto-added but no longer reported: hidden or
        // removed -> flag stale, but only once the SAME id has missed two scans in a row (see
        // missStreak doc above). Guns get a further authoritative purge check against TACZ's own
        // registry; LRT items and attachments stop at "disabled" — there's no equivalent
        // authoritative "definitely gone forever" signal for them, so an admin still makes the
        // final delete call for those.
        int newlyStale = 0;
        int purged = 0;
        if (!gunsSeenInTabs.isEmpty() || !lrtItemsSeenInTabs.isEmpty() || !attachmentsSeenInTabs.isEmpty()) {
            java.util.List<String> purgeIds = new java.util.ArrayList<>();
            for (CatalogEntry entry : registry.catalogList()) {
                boolean isGun = entry.kind == CatalogEntry.Kind.GUN && entry.id.startsWith("gun_");
                String lrtKey = lrtSeenKey(entry);
                boolean isLrtItem = lrtKey != null;
                boolean isAttachment = entry.kind == CatalogEntry.Kind.ATTACHMENT && entry.id.startsWith("att_");
                if (!isGun && !isLrtItem && !isAttachment) {
                    continue;
                }
                String seenKey = isGun ? entry.gameId : isLrtItem ? lrtKey : entry.id;
                Set<String> seenSet = isGun ? gunsSeenInTabs : isLrtItem ? lrtItemsSeenInTabs : attachmentsSeenInTabs;
                if (seenSet.contains(seenKey)) {
                    missStreak.remove(seenKey);
                    continue;
                }
                // Already flagged stale from an earlier pass and STILL missing this pass: for guns,
                // check TACZ's own gun registry directly — if it confirms the gameId doesn't exist
                // at all anymore (the pack was actually uninstalled — the strongest signal there
                // is, much stronger than "not in a creative tab this scan"), purge it for real
                // instead of leaving it flagged forever waiting for an admin to remember to click
                // Clean Stale. This is the actual gap behind "reports ghosts but never cleans them
                // up". LRT items and attachments have no such authoritative index to check, so they
                // stay flagged stale/disabled (still "cleared" out of pickers/applies) rather than
                // auto-purged.
                if (entry.stale) {
                    if (isGun) {
                        boolean genuinelyGone;
                        try {
                            genuinelyGone = TimelessAPI.getCommonGunIndex(new ResourceLocation(entry.gameId)).isEmpty();
                        } catch (Exception e) {
                            genuinelyGone = false; // malformed id / API hiccup - don't risk purging on a fluke
                        }
                        if (genuinelyGone) {
                            purgeIds.add(entry.id);
                            MWLoadoutMod.LOGGER.info(
                                    "[MWLoadout] Auto-purging ghost entry '{}' (gameId {}) - TACZ no longer has this "
                                            + "gun registered at all (pack uninstalled)", entry.id, entry.gameId);
                        }
                    }
                    continue;
                }
                int misses = missStreak.merge(seenKey, 1, Integer::sum);
                if (misses >= DISABLE_AFTER_MISSES) {
                    entry.enabled = false;
                    entry.stale = true;
                    entry.staleSinceMillis = System.currentTimeMillis();
                    newlyStale++;
                    missStreak.remove(seenKey);
                    MWLoadoutMod.LOGGER.info("[MWLoadout] Flagged stale (ghost ID) {} {} (missing {} scans in a row)",
                            isGun ? "gun" : isAttachment ? "attachment" : "item", seenKey, misses);
                } else {
                    MWLoadoutMod.LOGGER.info("[MWLoadout] {} {} missing from creative tabs this scan ({}/{} before "
                            + "flagging stale) - not acting yet, could be a slow pack load",
                            isGun ? "Gun" : isAttachment ? "Attachment" : "Item", seenKey, misses, DISABLE_AFTER_MISSES);
                }
            }
            if (!purgeIds.isEmpty()) {
                purged = registry.removeEntries(purgeIds);
            }
        }
        healed += seedCompanionItems(registry);
        healed += healSlotCaps(registry);
        int totalChanged = added + healed + newlyStale + recovered + purged;
        if (totalChanged > 0) {
            registry.save();
        }
        MWLoadoutMod.LOGGER.info("[MWLoadout] Reconcile: {} added, {} healed, {} newly stale, {} recovered, {} "
                + "auto-purged, {} guns visible", added, healed, newlyStale, recovered, purged, gunsSeenInTabs.size());
        return summarize(registry, added, healed, newlyStale, recovered);
    }

    private static ScanResult summarize(LoadoutRegistry registry, int added, int healed, int newlyStale, int recovered) {
        int total = 0;
        int active = 0;
        java.util.List<CatalogEntry> staleList = new java.util.ArrayList<>();
        for (CatalogEntry entry : registry.catalogList()) {
            total++;
            if (entry.stale) {
                staleList.add(entry);
            } else if (entry.enabled) {
                active++;
            }
        }
        return new ScanResult(added, healed, newlyStale, recovered, total, active, staleList.size(), staleList);
    }

    /** Existing configs carry the old maxCount=1 on equipment slots — the reason "stim x2"
     *  still arrived as one stim: buildItem caps at slot.maxCount. Raise floors in place. */
    private static int healSlotCaps(LoadoutRegistry registry) {
        int changed = 0;
        changed += raiseCap(registry, "consumable", 4);
        changed += raiseCap(registry, "lethal", 4);
        changed += raiseCap(registry, "tactical", 4);
        changed += raiseCap(registry, "extra2", 2); // headroom for Shrapnel's +1 deployable
        changed += healPresetCeiling(registry);
        changed += healShrapnel(registry);
        changed += healBattleHardenedReduces(registry);
        changed += dev.l41nx.mwloadout.server.LimbCompat.syncInto(registry.rules()) ? 1 : 0;
        changed += healPerkTuning(registry);
        changed += healPerkBalance(registry);
        changed += healStreakDefaults(registry);
        changed += healFieldUpgradeNames(registry);
        changed += healExtra3Optional(registry);
        changed += healPufferfish(registry);
        changed += healSleightReloadAttribute(registry);
        changed += healMwAttributes(registry);
        changed += healDynamicAmmoTiers(registry);
        changed += healLegacyAmmoBonus(registry);
        return changed;
    }

    /** Old configs persisted maxPresets=10 (our former default, not an admin decision) —
     *  raise to the new 20-class ceiling in place. Admin values ABOVE 10 are left alone. */
    private static int healPresetCeiling(LoadoutRegistry registry) {
        if (registry.rules().presetCeilingMigrated) {
            return 0;
        }
        registry.rules().presetCeilingMigrated = true;
        if (registry.rules().maxPresets == 10) {
            registry.rules().maxPresets = 20;
        }
        return 1;
    }

    /** Seed the default Extra 3 pufferfish into existing configs (once) if absent. */
    private static int healPufferfish(LoadoutRegistry registry) {
        if (registry.rules().pufferfishMigrated) {
            return 0;
        }
        registry.rules().pufferfishMigrated = true;
        if (registry.entry("pufferfish") == null) {
            registry.upsertEntry(dev.l41nx.mwloadout.data.CatalogEntry.item(
                    "pufferfish", "Pufferfish", "extra3", "minecraft:pufferfish", "", 1));
        }
        return 1;
    }

    /** Extra 3 becomes the opt-in novelty slot (never random-filled) on existing configs too. */
    private static int healExtra3Optional(LoadoutRegistry registry) {
        if (registry.rules().extra3OptionalMigrated) {
            return 0;
        }
        registry.rules().extra3OptionalMigrated = true;
        var slot = registry.slot("extra3");
        if (slot != null) {
            slot.optional = true;
        }
        return 1;
    }

    /** One-shot per config: re-seed the retuned perks (Double Time sneak, Quick Fix supplies,
     *  Sleight draw/reload via tacz_attributes, Scavenger-all) when the stored perkRevision is
     *  older. Only the four touched perks are overwritten; admin-created perks are untouched. */
    /** Ensures every shipped-default perk/column exists at least once — additive only, NEVER
     *  touches an existing perk (see LoadoutRegistry.ensureDefaultPerksSeeded doc for why this
     *  must be the additive version, not reseedPerks()). Still gated by perkRevision so this
     *  doesn't re-scan the whole perk list on every single server start once a config is current. */
    private static int healPerkTuning(LoadoutRegistry registry) {
        if (registry.rules().perkRevision >= 6) {
            return 0;
        }
        registry.ensureDefaultPerksSeeded();
        registry.rules().perkRevision = 6;
        return 1;
    }

    /** Configs created before the killstreak system have empty streakRewards/specialistPerkPool.
     *  Seed the MW defaults ONCE (streakRevision gate) without clobbering an admin who already set
     *  their own ladder — we only fill when the list is actually empty. */
    private static int healStreakDefaults(LoadoutRegistry registry) {
        var rules = registry.rules();
        if (rules.streakRevision >= 2) {
            return 0;
        }
        int changed = 0;
        if (rules.streakRewards.isEmpty()) {
            rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(3, "nmw2:bomb_drone"));
            rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(5, "nmw2:spotter_scope"));
            rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(7, "nmw2:laser_pointer"));
            rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(9, "ts:fl10"));
            changed = 1;
        } else {
            // Migrate configs seeded by revision 1: fix the laser-pointer id typo, and add the new
            // 9-kill ts:fl10 tier if a still-default 3-tier ladder is present (admin edits kept).
            for (var r : rules.streakRewards) {
                if ("nmw2:laserpointer".equals(r.itemId)) {
                    r.itemId = "nmw2:laser_pointer";
                    changed = 1;
                }
            }
            boolean has9 = rules.streakRewards.stream().anyMatch(r -> r.kills >= 9);
            if (rules.streakRewards.size() == 3 && !has9) {
                rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(9, "ts:fl10"));
                changed = 1;
            }
        }
        if (rules.specialistPerkPool.isEmpty()) {
            for (String p : new String[]{"battle_hardened", "sleight_of_hands", "overkill",
                    "fully_loaded", "scavenger", "high_alert", "shield_up", "shrapnel"}) {
                rules.specialistPerkPool.add(p);
            }
            changed = 1;
        }
        rules.streakRevision = 2;
        return changed;
    }

    /** Rename the three "EXTRA n" slots to "FIELD UPGRADE n" in configs seeded before the rename.
     *  Only touches slots still carrying the old display name; admin-renamed slots are left alone. */
    private static int healFieldUpgradeNames(LoadoutRegistry registry) {
        int changed = 0;
        for (int i = 1; i <= 3; i++) {
            SlotDefinition slot = registry.slot("extra" + i);
            if (slot != null && slot.displayName != null && slot.displayName.equalsIgnoreCase("EXTRA " + i)) {
                slot.displayName = "FIELD UPGRADE " + i;
                changed = 1;
            }
        }
        return changed;
    }

    private record PerkMove(String id, String oldSlot, String newSlot, String oldName, String newName, String[] oldDesc, String newDesc) {
    }

    /** One-shot (Rules.perkBalanceRevision): brings a saved config to the Modern Warfare 4 style layout - perks move to their new column, the
     *  renamed ones (Double Time -> Blood Rush, Talk To Hand -> Fast Hands) get their names, descriptions are rewritten, Fragiles trades its
     *  Wither for a 2 s regeneration block, Battle Hardened's armor and Fully Loaded's multiplier come down. Everything is only changed while it
     *  still holds the OLD DEFAULT, so an admin's own slot / name / text / number is never overwritten. */
    private static int healPerkBalance(LoadoutRegistry registry) {
        var rules = registry.rules();
        // Ghost now also hides from UAV sweeps and gunshot pings (UAV Killstreaks): refresh its text while it is still an old default
        int ghostText = 0;
        var ghost = registry.perk("ghost");
        if (ghost != null && ("Undetectable by enemy Tracker and High Alert.".equals(ghost.description)
                || "Immune to Tracker and High Alert.".equals(ghost.description))) {
            ghost.description = "Undetectable by enemy UAV sweeps, Tracker and High Alert. Firing does not ping you on enemy minimaps.";
            ghostText = 1;
        }
        if (rules.perkBalanceRevision >= 1) {
            return ghostText;
        }
        rules.perkBalanceRevision = 1;
        PerkMove[] moves = {
                new PerkMove("quick_fix", "perk3", "perk1", null, null, new String[]{"Regeneration from kills"}, "Kills trigger health regeneration."),
                new PerkMove("overkill", "perk2", "perk1", null, null, new String[]{}, null),
                new PerkMove("high_alert", "perk2", "perk1", null, null, new String[]{"Your vision pulses when enemies outside your view look at you."},
                        "Your vision pulses when an enemy outside your view looks at you."),
                new PerkMove("ghost", "perk3", "perk1", null, null, new String[]{"Immune to Tracker and High Alert."}, "Undetectable by enemy Tracker and High Alert."),
                new PerkMove("tune_up", "perk1", "perk1", null, null, new String[]{"Field upgrades recharge 40% faster."}, "Field Upgrades recharge 40% faster."),
                new PerkMove("killchain", "perk1", "perk1", null, null,
                        new String[]{"Your first Killstreak progress carries over death. Resets upon a second consecutive death."},
                        "Your killstreak progress survives your first death. A second death in a row resets it."),
                new PerkMove("hardline", "perk2", "perk2", null, null, new String[]{"Killstreak rewards cost one less kill."}, "Killstreaks cost one less kill."),
                new PerkMove("double_time", "perk1", "perk2", "Double Time", "Blood Rush", new String[]{"Increases movement speed."},
                        "Move faster, including while crouched."),
                new PerkMove("sleight_of_hands", "perk1", "perk2", "Talk To Hand", "Fast Hands", new String[]{"Faster reload & swap under pressure."},
                        "Reload and swap weapons faster."),
                new PerkMove("ninja", "perk2", "perk2", null, null, new String[]{
                        "Your movement sounds - footsteps, swimming, landing - are 90% quieter. Works with Presence Footsteps.",
                        "Your movement sounds - footsteps, swimming, landing - are 90% quieter. Works with Presence Footsteps and Tactical Footsteps."},
                        "Your footsteps and movement sounds are 90% quieter."),
                new PerkMove("shrapnel", "perk3", "perk2", null, null, new String[]{"Resistant to explosive damage, carry extra lethal and tactical equipment."},
                        "Take much less explosive damage. Carry extra Lethal and Tactical equipment."),
                new PerkMove("battle_hardened", "perk3", "perk3", null, null, new String[]{"Take less damage; negative effects last 90% shorter."},
                        "Reduces the effect of enemy Tacticals and status effects. Immune to Glowing, except from Tracker. Slightly tougher."),
                new PerkMove("scavenger", "perk2", "perk3", null, null, new String[]{"Resupply ammo from kills. Lethal and tactical equipment recharges over time."},
                        "Kills resupply your ammo. Lethal and Tactical equipment recharge over time."),
                new PerkMove("tracker", "perk1", "perk3", null, null, new String[]{"Dealing damage reveals enemies."}, "Damaging an enemy marks them for a short time."),
                new PerkMove("fragiles", "perk2", "perk3", null, null, new String[]{"Increases melee damage. Your hits briefly delay enemy health regeneration."},
                        "Increases melee damage. Hits you land stop enemy players from regenerating health for 2 seconds."),
                new PerkMove("fully_loaded", "perk1", "perk3", null, null, new String[]{"Deploy with maximum reserve ammo (x4 reserve, every weapon category)."},
                        "Deploy with double reserve ammo for every weapon."),
                new PerkMove("shield_up", "perk3", "specialist3", null, null, new String[]{"Deploy with extra armor plates."}, "Deploy with extra armor plates. Specialist only."),
        };
        for (PerkMove move : moves) {
            var perk = registry.perk(move.id());
            if (perk == null) {
                continue;
            }
            if (move.oldSlot().equals(perk.perkSlot)) {
                perk.perkSlot = move.newSlot();
            }
            if (move.oldName() != null && move.oldName().equals(perk.displayName)) {
                perk.displayName = move.newName();
            }
            if (move.newDesc() != null) {
                for (String old : move.oldDesc()) {
                    if (old.equals(perk.description)) {
                        perk.description = move.newDesc();
                        break;
                    }
                }
            }
        }
        var battleHardened = registry.perk("battle_hardened");
        if (battleHardened != null) {
            for (var effect : battleHardened.effects) {
                if ("attribute".equals(effect.has("type") ? effect.get("type").getAsString() : "")
                        && "minecraft:generic.armor".equals(effect.has("attribute") ? effect.get("attribute").getAsString() : "")
                        && effect.has("amount") && effect.get("amount").getAsDouble() == 10.0) {
                    effect.addProperty("amount", 4.0); // +10 armor is a 40 % cut on its own; this perk is about status effects
                }
            }
        }
        var fragiles = registry.perk("fragiles");
        if (fragiles != null) {
            for (int i = 0; i < fragiles.effects.size(); i++) {
                var effect = fragiles.effects.get(i);
                if (effect.has("key") && "fragiles_wither".equals(effect.get("key").getAsString())) {
                    fragiles.effects.set(i, Defaults.fragilesRegenBlock());
                }
            }
        }
        if (rules.fullyLoadedMultiplier == 4.0) {
            rules.fullyLoadedMultiplier = 2.0;
        }
        return 1;
    }

    /** Saved configs carry Battle Hardened with armor only - give it the data-driven "reduces_effects" entry once.
     *  A perk the admin already tuned (it has such an entry) or removed on purpose afterwards is left alone. */
    private static int healBattleHardenedReduces(LoadoutRegistry registry) {
        if (registry.rules().battleHardenedEffectsMigrated) {
            return 0;
        }
        registry.rules().battleHardenedEffectsMigrated = true;
        var perk = registry.perk("battle_hardened");
        if (perk == null) {
            return 1;
        }
        for (var effect : perk.effects) {
            if (effect.has("reduces_effects")) {
                // already has the duration entry (first v34 build): just add the glow immunity next to it
                if (!effect.has("immune_effects")) {
                    com.google.gson.JsonArray immune = new com.google.gson.JsonArray();
                    immune.add("minecraft:glowing");
                    effect.add("immune_effects", immune);
                }
                return 1;
            }
        }
        perk.effects.add(Defaults.reducesEffects());
        return 1;
    }

    /** Configs seeded before the Shrapnel rework carry the old effect JSON (no "shrapnel"
     *  special key) — the explosion-resist handler can't see those. Replace in place. */
    private static int healShrapnel(LoadoutRegistry registry) {
        if (registry.rules().shrapnelMigrated) {
            return 0;
        }
        registry.rules().shrapnelMigrated = true;
        var perk = registry.perk("shrapnel");
        if (perk == null) {
            return 1;
        }
        boolean hasSpecial = false;
        for (var effect : perk.effects) {
            if ("special".equals(dev.l41nx.mwloadout.util.Json.str(effect, "type", ""))
                    && "shrapnel".equals(dev.l41nx.mwloadout.util.Json.str(effect, "key", ""))) {
                hasSpecial = true;
                break;
            }
        }
        if (hasSpecial && perk.description.startsWith("Resistant")) {
            return 1;
        }
        perk.description = "Resistant to explosive damage. Carry extra lethal, tactical and deployable equipment.";
        perk.effects.clear();
        com.google.gson.JsonObject special = new com.google.gson.JsonObject();
        special.addProperty("type", "special");
        special.addProperty("key", "shrapnel");
        perk.effects.add(special);
        return 1;
    }

    /** Deploy count for a genuinely NEW auto-seeded throwable entry is set once, at creation, in
     *  classify() (melee=1, everything else=2) — never re-applied here on every later scan. A
     *  separate "heal count back to 2 on every restart" pass used to live here, but its only real
     *  effect was silently overwriting an admin's deliberate count edit on a "lrt_"/"x_" entry
     *  every single server restart (the entry already existed, so classify()'s creation-time
     *  default never re-ran for it — this function was the ONLY thing still touching it, and had
     *  no way to tell "still the untouched default" apart from "admin set this to 1 on purpose").
     */

    /** One-shot: every perk effect that used an attribute library's speed attribute now uses the built-in mw: one, so the mod no longer needs
     *  ApothicAttributes / tacz_attributes / gunsmithlib. Fast Hands' old default amount (+0.3) becomes the new default (+0.35, 1.35x); an
     *  admin's own amount is kept. */
    private static int healMwAttributes(LoadoutRegistry registry) {
        if (registry.rules().mwAttributesMigrated) {
            return 0;
        }
        registry.rules().mwAttributesMigrated = true;
        java.util.Map<String, String> map = java.util.Map.of(
                "gunsmithlib:reload_speed", "mw:reload_speed",
                "tacz_attributes:reload_speed", "mw:reload_speed",
                "apothic_attributes:reload_speed", "mw:reload_speed",
                "tacz_attributes:draw_speed", "mw:draw_speed",
                "gunsmithlib:draw_speed", "mw:draw_speed",
                "tacz_attributes:ads_speed", "mw:ads_speed",
                "gunsmithlib:ads_speed", "mw:ads_speed");
        for (var perk : registry.perkList()) {
            for (var effect : perk.effects) {
                if (!"attribute".equals(dev.l41nx.mwloadout.util.Json.str(effect, "type", ""))) {
                    continue;
                }
                String target = map.get(dev.l41nx.mwloadout.util.Json.str(effect, "attribute", ""));
                if (target == null) {
                    continue;
                }
                effect.addProperty("attribute", target);
                if ("sleight_of_hands".equals(perk.id) && effect.has("amount")
                        && Math.abs(effect.get("amount").getAsDouble() - 0.3) < 1e-6) {
                    effect.addProperty("amount", 0.35);
                }
            }
        }
        var tracker = registry.perk("tracker");
        if (tracker != null && "Damaging an enemy marks them for a short time.".equals(tracker.description)) {
            tracker.description = "Damaging an enemy marks them for a short time. Your scope stays steady: no sway, no need to hold your breath.";
        }
        return 1;
    }

    /** Sleight of Hand's reload_speed attribute was seeded under a nonexistent namespace
     *  (tacz_attributes:reload_speed) — the real reload-speed attribute is provided by
     *  gunsmithlib; draw_speed was always correct under tacz_attributes and is left alone. Only
     *  the id string is patched in place, so an admin's own retuned amount (or a deliberately
     *  different attribute id) survives untouched. */
    private static int healSleightReloadAttribute(LoadoutRegistry registry) {
        if (registry.rules().sleightReloadAttrMigrated) {
            return 0;
        }
        registry.rules().sleightReloadAttrMigrated = true;
        var perk = registry.perk("sleight_of_hands");
        if (perk == null) {
            return 1;
        }
        int changed = 0;
        for (var effect : perk.effects) {
            if ("attribute".equals(dev.l41nx.mwloadout.util.Json.str(effect, "type", ""))
                    && "tacz_attributes:reload_speed".equals(dev.l41nx.mwloadout.util.Json.str(effect, "attribute", ""))) {
                effect.addProperty("attribute", "gunsmithlib:reload_speed");
                changed = 1;
            }
        }
        return changed;
    }

    /** One-shot: the ammo-system rework replaced the old floor-only tier shape (no category or
     *  ceiling support, couldn't express a shotgun exception) with the new one and flipped
     *  dynamicAmmoEnabled on by default. An existing config's tier LIST is fully replaced with
     *  the new default set exactly once — old-shape tier data doesn't carry any admin intent the
     *  new shape can preserve (it's the same 3 numbers every fresh install shipped with), so
     *  there's nothing worth trying to merge. Never touches dynamicAmmoTiers again after this
     *  runs once; an admin's edits made after the rework are safe. Also force-enables dynamic
     *  ammo (an admin who'd explicitly turned it OFF still gets flipped back on here, once —
     *  this is a one-time "you're on the new system now" move, not a standing override; flipping
     *  it off again afterward sticks). */
    private static int healDynamicAmmoTiers(LoadoutRegistry registry) {
        if (registry.rules().dynamicAmmoTiersRebalanced) {
            return 0;
        }
        registry.rules().dynamicAmmoTiersRebalanced = true;
        registry.rules().dynamicAmmoTiers.clear();
        registry.rules().dynamicAmmoTiers.add(new dev.l41nx.mwloadout.data.Rules.AmmoTier(0, 9, "sg", 8.0));
        registry.rules().dynamicAmmoTiers.add(new dev.l41nx.mwloadout.data.Rules.AmmoTier(0, 4, "", 5.0));
        registry.rules().dynamicAmmoTiers.add(new dev.l41nx.mwloadout.data.Rules.AmmoTier(5, 49, "", 3.0));
        registry.rules().dynamicAmmoTiers.add(new dev.l41nx.mwloadout.data.Rules.AmmoTier(50, -1, "", 2.0));
        registry.rules().dynamicAmmoEnabled = true;
        MWLoadoutMod.LOGGER.info("[MWLoadout] Rebalanced dynamicAmmoTiers to the shotgun-aware default set and enabled dynamic ammo");
        return 1;
    }

    /** One-shot: strips any leftover "special":"ammo_bonus" effect from every perk's effects
     *  list — the mechanism it belonged to (Fully Loaded's old flat-add-per-category bonus) is
     *  retired; Fully Loaded's actual bonus now lives entirely in Rules.fullyLoadedMultiplier
     *  (stacked onto the dynamic ammo tier, see LoadoutApplier), read by perk ID, not by effect.
     *  An existing server's perks.json still had this JSON sitting there looking functional when
     *  it was actually inert — this clears it out instead of leaving dead config in the PERKS tab. */
    private static int healLegacyAmmoBonus(LoadoutRegistry registry) {
        if (registry.rules().legacyAmmoBonusStripped) {
            return 0;
        }
        registry.rules().legacyAmmoBonusStripped = true;
        int changed = 0;
        for (var perk : registry.perkList()) {
            int before = perk.effects.size();
            perk.effects.removeIf(effect ->
                    "special".equals(dev.l41nx.mwloadout.util.Json.str(effect, "type", ""))
                            && "ammo_bonus".equals(dev.l41nx.mwloadout.util.Json.str(effect, "key", "")));
            if (perk.effects.size() != before) {
                changed++;
                MWLoadoutMod.LOGGER.info("[MWLoadout] Stripped dead ammo_bonus effect from perk '{}'", perk.id);
            }
        }
        return changed;
    }

    private static int raiseCap(LoadoutRegistry registry, String slotId, int floor) {
        var slot = registry.slot(slotId);
        if (slot != null && slot.maxCount < floor) {
            slot.maxCount = floor;
            return 1;
        }
        return 0;
    }

    /** Curated companion loadout gear, auto-stocked ONLY when the providing mod is actually
     *  installed (registry lookup per item — no hard dependency): SuperbWarfare utility gear
     *  fills EXTRA 1, its deployables fill EXTRA 2, and the slots enable themselves on first
     *  find. EXTRA 3 stays off — that one's the admin's sandbox. Runs inside every scan but
     *  only ever adds what's missing. */
    private static int seedCompanionItems(LoadoutRegistry registry) {
        int changed = 0;
        // Repair tool is no longer wanted in Field Upgrade — strip it if an older seed left it.
        boolean hasRepair = registry.catalogList().stream()
                .anyMatch(e -> "superbwarfare:repair_tool".equals(e.id));
        if (hasRepair) {
            registry.removeEntry("superbwarfare:repair_tool");
            changed = 1;
        }
        changed += seedInto(registry, "extra1", new String[]{
                "dragonrise_reforge:shield",
                "superbwarfare:ptkm_1r", "superbwarfare:edd"});
        changed += seedInto(registry, "extra2", new String[]{
                "superbwarfare:c4_bomb", "superbwarfare:claymore_mine", "superbwarfare:tm_62"});
        changed += seedInto(registry, "melee", new String[]{"lrtactical:flash_shield"});
        return changed;
    }

    private static int seedInto(LoadoutRegistry registry, String slotId, String[] itemIds) {
        int changed = 0;
        for (String itemId : itemIds) {
            var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
            if (item == null) {
                continue; // providing mod absent — skip silently, zero hard deps
            }
            String catalogId = "x_" + itemId.replace(':', '_');
            if (registry.entry(catalogId) != null) {
                continue; // already seeded from an earlier pass — leave the admin's edits alone
            }
            CatalogEntry entry = CatalogEntry.item(catalogId,
                    new net.minecraft.world.item.ItemStack(item).getHoverName().getString(),
                    slotId, itemId, "", 1);
            registry.upsertEntry(entry);
            changed++;
        }
        // Auto-enable ONLY the first time this pass actually seeds something NEW into the slot —
        // NOT just because a companion item still exists in-game (that was true on every restart
        // forever, which is what silently flipped an admin's manual "disable" back on every time).
        // A genuinely fresh addition (new companion item pack installed, or first server boot) is
        // the only case that should ever touch enabled/hidden again after that.
        if (changed > 0) {
            var slot = registry.slot(slotId);
            if (slot != null && (!slot.enabled || !slot.allowedCategories.contains(slotId))) {
                slot.enabled = true;
                slot.hidden = false;
                if (!slot.allowedCategories.contains(slotId)) {
                    slot.allowedCategories.add(slotId);
                }
                changed++;
            }
        }
        return changed;
    }

    /** Scans every attachment TACZ currently knows about into its own ATTACHMENT-kind
     *  catalog branch, so an admin can enable/disable individual attachments from the Admin Panel
     *  exactly like a gun or item — without attachments ever being reachable as slot-fillable
     *  loadout content (see CatalogEntry.Kind javadoc: category is always "attachment", never an
     *  allowedCategories match for any real slot). Adds newly-seen attachments and populates
     *  `seenInTabs` with every id TACZ reports this pass, so the caller can run the same
     *  recovery/newly-stale reconciliation guns and LRT items already get — an attachment pack
     *  being uninstalled used to leave its entries enabled forever (nothing here ever disabled
     *  one on its own), which is exactly the untranslated "item.tacz.attachment" ghost rows an
     *  admin would otherwise have to notice and clear by hand. Real name resolution happens
     *  entirely client-side (DisplayNames.ofAttachment), same as guns; the stored displayName
     *  here is only ever a fallback for contexts with no client to resolve it. */
    private static int scanAttachments(LoadoutRegistry registry, Set<String> seenInTabs) {
        int[] added = {0};
        try {
            TimelessAPI.getAllCommonAttachmentIndex().forEach(pair -> {
                ResourceLocation rl = pair.getKey();
                if (rl == null) {
                    return;
                }
                String attachmentId = rl.toString();
                String catalogId = "att_" + attachmentId.replace(':', '_').replace('/', '_');
                seenInTabs.add(catalogId);
                if (registry.entry(catalogId) != null) {
                    return;
                }
                registry.upsertEntry(CatalogEntry.attachment(catalogId, attachmentId, attachmentId));
                added[0]++;
            });
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Attachment scan failed", e);
        }
        return added[0];
    }

    private static final java.util.regex.Pattern LRT_NBT_ID_PATTERN =
            java.util.regex.Pattern.compile("(MeleeWeaponId|ConsumableId|ThrowableId):\"([^\"]+)\"");

    /** The comparable "seen in tabs" key for any LRTactical virtual-item catalog entry — same
     *  transform classify() uses when it builds lrtItemsSeenInTabs, so a hand-seeded default
     *  entry (arbitrary id, e.g. "melee_knife", nbt guessed at seed time and possibly wrong/dead
     *  on this pack) and a scanner-created one (id IS already this key) both compare correctly
     *  against the same scanned set — hand-seeded entries used to be invisible to stale-detection
     *  entirely (only "lrt_"-prefixed ids were checked), so a guessed id that never matched
     *  anything real just sat enabled forever, showing as a broken/missing item indefinitely.
     *  Returns null for anything that isn't an LRT virtual item (no Melee/Consumable/ThrowableId
     *  with a non-empty value in its NBT). */
    private static String lrtSeenKey(CatalogEntry entry) {
        if (entry.kind != CatalogEntry.Kind.ITEM || entry.nbt == null) {
            return null;
        }
        java.util.regex.Matcher m = LRT_NBT_ID_PATTERN.matcher(entry.nbt);
        if (!m.find()) {
            return null;
        }
        return "lrt_" + m.group(2).replace(':', '_').replace('/', '_');
    }

    private static int classify(LoadoutRegistry registry, ItemStack stack, Set<String> gunsSeen, Set<String> lrtItemsSeen) {
        try {
            IGun igun = IGun.getIGunOrNull(stack);
            if (igun != null) {
                ResourceLocation gunRL = igun.getGunId(stack);
                if (gunRL == null) {
                    return 0;
                }
                String gunId = gunRL.toString();
                gunsSeen.add(gunId);
                String catalogId = "gun_" + gunId.replace(':', '_').replace('/', '_');
                if (registry.entry(catalogId) != null) {
                    return 0;
                }
                String type = "";
                try {
                    var index = TimelessAPI.getCommonGunIndex(gunRL);
                    if (index.isPresent()) {
                        type = index.get().getType();
                    }
                } catch (Exception ignored) {
                    dev.l41nx.mwloadout.MWLog.swallowed("registry/CreativeTabScanner:739", ignored);
                }
                registry.upsertEntry(CatalogEntry.gun(catalogId, stack.getHoverName().getString(),
                        normalizeCategory(type), gunId));
                return 1;
            }

            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return 0;
            }
            // v4.3.6 fix (bug #10): LRTactical's "virtual item" NBT template can carry ALL THREE
            // of MeleeWeaponId/ConsumableId/ThrowableId keys on one stack (only the relevant one
            // actually populated, the others present-but-empty) — the old `tag.contains(key)`
            // check matched on key PRESENCE alone, so whichever key was checked first won
            // regardless of whether it actually held a value, silently mis-sorting (or dropping
            // entirely, once nbtId turned out empty) every item whose real id lived in a
            // later-checked key. Requiring a non-empty value fixes both the miscategorization and
            // the silent drop.
            String nbtId = null;
            String nbtKey = null;
            String kindCategory = null;
            if (tag.contains("MeleeWeaponId") && !tag.getString("MeleeWeaponId").isEmpty()) {
                nbtId = tag.getString("MeleeWeaponId");
                nbtKey = "MeleeWeaponId";
                kindCategory = "melee";
            } else if (tag.contains("ConsumableId") && !tag.getString("ConsumableId").isEmpty()) {
                nbtId = tag.getString("ConsumableId");
                nbtKey = "ConsumableId";
                kindCategory = "consumable";
            } else if (tag.contains("ThrowableId") && !tag.getString("ThrowableId").isEmpty()) {
                nbtId = tag.getString("ThrowableId");
                nbtKey = "ThrowableId";
                kindCategory = throwableCategory(nbtId);
            }
            if (nbtId == null || nbtId.isEmpty()) {
                return 0;
            }

            ResourceLocation itemRL = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemRL == null) {
                return 0;
            }
            String fullNbt = tag.toString();
            String name = stack.getHoverName().getString();
            // Mark seen BEFORE any early return below (healed, unchanged, or newly-added all
            // count as "still present this scan") — same unconditional-add-up-front pattern the
            // gun branch above already uses for gunsSeen, now applied to LRT items too so the
            // ghost-cleanup pass (bug #9) actually has something to check against.
            String catalogId = "lrt_" + nbtId.replace(':', '_').replace('/', '_');
            lrtItemsSeen.add(catalogId);

            // Heal any existing entry referencing this same NBT id (fixes the broken seeds).
            // Anchored on "key":"id" (not just "id" anywhere in the NBT string) — a bare substring
            // match could false-positive whenever one item's id string happens to be a substring
            // of another's (e.g. "smoke" inside "smoke_grenade"), silently merging two different
            // items into one catalog entry.
            String anchor = nbtKey + ":\"" + nbtId + "\"";
            for (CatalogEntry existing : registry.catalogList()) {
                if (existing.kind == CatalogEntry.Kind.ITEM && existing.nbt != null && existing.nbt.contains(anchor)) {
                    boolean broken = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(existing.gameId)) == null
                            || !existing.gameId.equals(itemRL.toString());
                    if (broken) {
                        existing.gameId = itemRL.toString();
                        existing.nbt = fullNbt;
                        existing.displayName = name;
                        return 1;
                    }
                    return 0;
                }
            }

            if (registry.entry(catalogId) != null) {
                return 0;
            }
            CatalogEntry entry = CatalogEntry.item(catalogId, name, kindCategory, itemRL.toString(), fullNbt,
                    kindCategory.equals("melee") ? 1 : 2);
            registry.upsertEntry(entry);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Lethal-vs-tactical is gameplay intent no index can tell us; keyword heuristic covers the
     *  standard MW vocabulary and admins recategorize the rest in one click. */
    static String throwableCategory(String throwableId) {
        String lower = throwableId.toLowerCase(Locale.ROOT);
        if (lower.contains("frag") || lower.contains("semtex") || lower.contains("c4")
                || lower.contains("claymore") || lower.contains("mine") || lower.contains("molotov")
                || lower.contains("thermite") || lower.contains("incendiary") || lower.contains("toxic")
                || lower.contains("knife") || lower.contains("impact")) {
            return "lethal";
        }
        return "tactical";
    }

    static String normalizeCategory(String taczType) {
        if (taczType == null) {
            return "ar";
        }
        return switch (taczType.toLowerCase(Locale.ROOT)) {
            case "ar", "rifle" -> "ar";
            case "smg" -> "smg";
            case "lmg", "mg" -> "lmg";
            case "sniper" -> "sr";
            case "dmr", "marksman" -> "dmr";
            case "shotgun" -> "sg";
            case "pistol" -> "pistol";
            case "rpg", "launcher" -> "launcher";
            default -> taczType.toLowerCase(Locale.ROOT);
        };
    }
}
