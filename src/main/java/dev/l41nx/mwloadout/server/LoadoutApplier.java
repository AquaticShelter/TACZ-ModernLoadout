package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validation and application, both strictly server-side. The client's preset is treated as a
 * CLAIM: every slot selection is re-checked against the live registry (categories, white/black
 * lists, enablement, overkill rewiring, perk column membership, attachment existence) before a
 * single stack is built. Anything invalid is DROPPED from the sanitized copy rather than failing
 * the whole apply — a stale selection after an admin removes an item shouldn't brick the class,
 * it should just come up empty so the player re-picks.
 *
 * Apply sequence (deterministic, dupe-free): clear hotbar+main+offhand (armor deliberately left
 * alone — out of scope by spec) -> hotbar stacks per slot definition order -> reserve ammo into
 * main inventory -> perks -> one containerMenu broadcast.
 */
public final class LoadoutApplier {

    private LoadoutApplier() {
    }

    /** Sanitizes IN PLACE and reports what was dropped (for the deny toast). Player-context
     *  overload additionally enforces progression unlock levels when that system is enabled. */
    public static List<String> sanitize(LoadoutPreset preset) {
        return sanitize(preset, Integer.MAX_VALUE, false);
    }

    public static List<String> sanitize(LoadoutPreset preset, int playerLevel) {
        return sanitize(preset, playerLevel, false);
    }

    public static List<String> sanitize(LoadoutPreset preset, int playerLevel, boolean isOp) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        Rules rules = registry.rules();
        List<String> dropped = new ArrayList<>();

        boolean overkill = hasSpecialPerk(preset, "overkill");
        boolean underkill = !overkill && hasSpecialPerk(preset, "underkill"); // Underkill: primary slot takes the secondary categories
        Set<String> usedGunIds = new HashSet<>();

        for (SlotDefinition slot : registry.slotList()) {
            LoadoutPreset.Selection selection = preset.slots.get(slot.id);
            if (!slot.enabled || slot.locked) {
                // Locked slots always resolve to the admin default, never the player's claim.
                preset.slots.remove(slot.id);
                continue;
            }
            if (selection == null || selection.entryId.isEmpty()) {
                continue;
            }
            CatalogEntry entry = registry.entry(selection.entryId);
            String reason = allowedIn(slot, entry, overkill, underkill, isOp);
            if (reason == null && LoadoutRegistry.get().rules().progressionEnabled) {
                CatalogEntry gatingEntry = entry;
                CatalogEntry platform = registry.blueprintOwnerByGameId(entry.gameId);
                if (platform != null) {
                    gatingEntry = platform; // Blueprint variant: gate on the platform, not itself
                }
                if (gatingEntry.unlockLevel > playerLevel) {
                    reason = "locked (LV " + gatingEntry.unlockLevel + ")";
                }
            }
            if (reason != null) {
                // Blueprint-aware recovery: a Blueprint form that's individually unavailable right
                // now (disabled by a weaponscan re-run, category re-tagged, briefly missing from a
                // creative tab during a slow world load, etc.) shouldn't dump the slot into
                // autoFillEmptySlots' fully-random pool — that's what turned "picked a Blueprint
                // variant" into "primary slot went empty, then got refilled with an unrelated
                // random gun". Fall back to the platform gun (or whichever the platform itself
                // currently resolves through here — recursion isn't needed since the platform
                // entry is never itself a Blueprint child) so the player keeps a gun from the SAME
                // family at worst, never a random one.
                CatalogEntry recovered = blueprintFallback(registry, entry, slot, overkill, underkill, isOp);
                if (recovered != null) {
                    dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.info(
                            "[MWLoadout][sanitize] {}: '{}' failed ({}) -> recovered via Blueprint fallback to platform '{}'",
                            slot.id, selection.entryId, reason, recovered.id);
                    selection.entryId = recovered.id;
                    entry = recovered;
                    reason = null;
                }
            }
            if (reason != null) {
                dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.warn(
                        "[MWLoadout][sanitize] {}: DROPPED '{}' (gameId={}) - {}", slot.id, selection.entryId,
                        entry != null ? entry.gameId : "?", reason);
                dropped.add(slot.displayName + ": " + reason);
                preset.slots.remove(slot.id);
                continue;
            }
            if (entry.kind == CatalogEntry.Kind.GUN) {
                // Overkill: no duplicate gun across weapon slots.
                if (!usedGunIds.add(entry.gameId)) {
                    dropped.add(slot.displayName + ": duplicate weapon");
                    preset.slots.remove(slot.id);
                    continue;
                }
                sanitizeAttachments(selection);
            } else {
                selection.attachments.clear();
            }
        }

        // Perks: must reference an enabled perk that belongs to that exact column. An invalid
        // reference (admin renamed/deleted/re-columned a perk after the player picked it) gets a
        // random valid perk from the SAME column instead of just going blank — matches the
        // Blueprint-platform-fallback philosophy used for weapons above: degrade to something
        // still useful, don't just drop the player's slot to empty over an admin-side edit they
        // had nothing to do with.
        java.util.Random perkRandom = new java.util.Random();
        for (Rules.PerkSlot perkSlot : rules.perkSlots) {
            String perkId = preset.perks.get(perkSlot.id);
            if (perkId == null || perkId.isEmpty()) {
                continue;
            }
            PerkDefinition perk = registry.perk(perkId);
            if (perk == null || !perk.enabled || !perkSlot.id.equals(perk.perkSlot)) {
                java.util.List<PerkDefinition> pool = new java.util.ArrayList<>();
                for (PerkDefinition candidate : registry.perkList()) {
                    if (candidate.enabled && perkSlot.id.equals(candidate.perkSlot)) {
                        pool.add(candidate);
                    }
                }
                if (!pool.isEmpty()) {
                    PerkDefinition replacement = pool.get(perkRandom.nextInt(pool.size()));
                    preset.perks.put(perkSlot.id, replacement.id);
                    dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.info(
                            "[MWLoadout][sanitize] {}: invalid perk '{}' -> randomly replaced with '{}'",
                            perkSlot.id, perkId, replacement.id);
                } else {
                    dropped.add(perkSlot.displayName + ": invalid perk");
                    preset.perks.remove(perkSlot.id);
                }
            }
        }
        preset.perks.keySet().removeIf(slotId -> rules.perkSlots.stream().noneMatch(perkSlot -> perkSlot.id.equals(slotId)));

        // Killstreaks: max 3, distinct kill counts, only things still in the admin's pool.
        preset.streaks = dev.l41nx.mwloadout.data.StreakPool.sanitize(preset.streaks, rules);

        if (preset.name == null || preset.name.isBlank()) {
            preset.name = "CLASS";
        }
        if (preset.name.length() > 24) {
            preset.name = preset.name.substring(0, 24);
        }
        return dropped;
    }

    /** Public wrapper around allowedIn() for external diagnostics (the /mwloadout diagnose
     *  command) — reuses the exact same validation sanitize() itself runs, so a diagnostic report
     *  can never disagree with what actually happens at save/apply time. Always evaluated as if
     *  OP (isOp=true): an admin-only "exclusive test" entry is intentionally restricted, not a
     *  bug, so diagnostics shouldn't flag it as broken. */
    public static String checkSelection(SlotDefinition slot, CatalogEntry entry, boolean overkill) {
        return allowedIn(slot, entry, overkill, false, true);
    }

    public static String checkSelection(SlotDefinition slot, CatalogEntry entry, boolean overkill, boolean underkill) {
        return allowedIn(slot, entry, overkill, underkill, true);
    }

    private static String allowedIn(SlotDefinition slot, CatalogEntry entry, boolean overkill, boolean underkill, boolean isOp) {
        if (entry == null || !entry.enabled) {
            return "unknown item";
        }
        if (entry.adminOnly && !isOp) {
            return "admin-only";
        }
        if (slot.blacklist.contains(entry.id)) {
            return "blacklisted";
        }
        if (slot.whitelist.contains(entry.id)) {
            return null;
        }
        List<String> categories = effectiveCategories(slot, overkill, underkill);
        if (categories == null) {
            return "slot unavailable";
        }
        return categories.contains(entry.category) ? null : "category not allowed";
    }

    /** When a selection fails validation, check whether it's a Blueprint form (platform or
     *  child) and, if so, whether the PLATFORM gun would itself be valid in this slot. Returns
     *  the platform to fall back to, or null if this selection has nothing to do with Blueprints
     *  (or the platform isn't valid here either) — in which case the caller drops it as usual. */
    private static CatalogEntry blueprintFallback(LoadoutRegistry registry, CatalogEntry entry, SlotDefinition slot,
                                                    boolean overkill, boolean underkill, boolean isOp) {
        if (entry == null) {
            return null;
        }
        CatalogEntry platform = entry.hasBlueprint ? entry : registry.blueprintOwnerByGameId(entry.gameId);
        if (platform == null || platform.id.equals(entry.id)) {
            return null;
        }
        return allowedIn(slot, platform, overkill, underkill, isOp) == null ? platform : null;
    }

    /**
     * Overkill rewiring, kept in ONE place so validator and GUI agree: the "secondary" slot
     * borrows the "primary" slot's categories (second primary), and its own pistol/launcher
     * vocabulary becomes unavailable — matching MW19, where Overkill replaces the sidearm slot
     * outright instead of adding to it.
     */
    public static List<String> effectiveCategories(SlotDefinition slot, boolean overkill) {
        return effectiveCategories(slot, overkill, false);
    }

    /** Underkill (Black Ops 4 style) is Overkill mirrored: the PRIMARY slot borrows the SECONDARY slot's categories, so the loadout carries two
     *  secondaries. Overkill wins when a preset somehow has both, so the two perks can never swap the slots into each other. */
    public static List<String> effectiveCategories(SlotDefinition slot, boolean overkill, boolean underkill) {
        if (underkill && !overkill && "primary".equals(slot.id)) {
            SlotDefinition secondary = LoadoutRegistry.get().slot("secondary");
            return secondary != null ? secondary.allowedCategories : slot.allowedCategories;
        }
        if (overkill && "secondary".equals(slot.id)) {
            SlotDefinition primary = LoadoutRegistry.get().slot("primary");
            return primary != null ? primary.allowedCategories : slot.allowedCategories;
        }
        return slot.allowedCategories;
    }

    public static boolean hasSpecialPerk(LoadoutPreset preset, String key) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (String perkId : preset.perks.values()) {
            PerkDefinition perk = registry.perk(perkId);
            if (perk != null && PerkEffects.hasSpecial(perk, key)) {
                return true;
            }
        }
        return false;
    }

    /** Also reused by the gun-build save path — one definition of "attachment exists". The fire
     *  mode choice (see TaczStackFactory.FIRE_MODE_KEY doc) rides in this SAME map purely for
     *  storage convenience, but it isn't a real attachment: it must never count toward the
     *  points-limit removal below, and its value ("SEMI"/"BURST"/"AUTO") is a FireMode enum name,
     *  NOT a TACZ item id, so it must never go through the ResourceLocation validity check either
     *  — that check previously ran it through TimelessAPI.getCommonAttachmentIndex(), which threw
     *  (uppercase isn't valid in a ResourceLocation), got caught, and silently deleted the entry
     *  on every single save. That was the actual bug behind "fire mode doesn't register". */
    public static void sanitizeAttachmentMap(java.util.Map<String, String> attachments) {
        String savedFireMode = attachments.remove(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY);
        String savedLaserColor = attachments.remove(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY);
        java.util.Map<String, String> savedRisers = extractRisers(attachments);
        int limit = LoadoutRegistry.get().rules().maxAttachments();
        if (attachments.size() > limit) {
            var iterator = attachments.entrySet().iterator();
            int kept = 0;
            while (iterator.hasNext()) {
                iterator.next();
                if (++kept > limit) {
                    iterator.remove();
                }
            }
        }
        attachments.entrySet().removeIf(attachment -> {
            String id = attachment.getValue();
            if (id == null || id.isEmpty()) {
                return true;
            }
            try {
                return TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(id)).isEmpty();
            } catch (Exception e) {
                return true;
            }
        });
        if (savedLaserColor != null && savedLaserColor.matches("#[0-9A-Fa-f]{6}") && attachments.containsKey("LASER")) {
            attachments.put(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY, savedLaserColor.toUpperCase());
        }
        restoreRisers(attachments, savedRisers);
        if (savedFireMode != null) {
            // Validate it's still a real FireMode name before putting it back — hand-edited data
            // or a future enum change shouldn't leave garbage sitting in the map.
            try {
                com.tacz.guns.api.item.gun.FireMode.valueOf(savedFireMode);
                attachments.put(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY, savedFireMode);
            } catch (IllegalArgumentException ignored) {
                dev.l41nx.mwloadout.MWLog.swallowed("server/LoadoutApplier:291", ignored);
            }
        }
    }


    /** Takes the TACZ 1.2.0 riser entries out of an attachments map (they are mounts, not attachments: they must not be counted against
     *  the limit or checked as attachment ids). Hand them to {@link #restoreRisers} afterwards. */
    private static java.util.Map<String, String> extractRisers(java.util.Map<String, String> attachments) {
        java.util.Map<String, String> risers = new java.util.LinkedHashMap<>();
        var iterator = attachments.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().startsWith(dev.l41nx.mwloadout.server.TaczStackFactory.RISER_KEY_PREFIX)) {
                risers.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        return risers;
    }

    /** Puts well-formed risers back - and only when this TACZ has them; on older TACZ they are dropped (the feature is simply absent). */
    private static void restoreRisers(java.util.Map<String, String> attachments, java.util.Map<String, String> risers) {
        if (risers.isEmpty() || !dev.l41nx.mwloadout.server.TaczCompat.enabled(dev.l41nx.mwloadout.server.TaczCompat.Feature.RISER)) {
            return;
        }
        for (var entry : risers.entrySet()) {
            String slot = entry.getKey().substring(dev.l41nx.mwloadout.server.TaczStackFactory.RISER_KEY_PREFIX.length());
            try {
                com.tacz.guns.api.item.attachment.AttachmentType.valueOf(slot);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (ResourceLocation.tryParse(entry.getValue() == null ? "" : entry.getValue()) != null) {
                attachments.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void sanitizeAttachments(LoadoutPreset.Selection selection) {
        String savedFireMode = selection.attachments.remove(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY);
        String savedLaserColor = selection.attachments.remove(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY);
        java.util.Map<String, String> savedRisers = extractRisers(selection.attachments);
        selection.attachments.entrySet().removeIf(attachment -> {
            String id = attachment.getValue();
            if (id == null || id.isEmpty()) {
                return true;
            }
            try {
                return TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(id)).isEmpty();
            } catch (Exception e) {
                return true;
            }
        });
        if (savedLaserColor != null && savedLaserColor.matches("#[0-9A-Fa-f]{6}") && selection.attachments.containsKey("LASER")) {
            selection.attachments.put(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY, savedLaserColor.toUpperCase());
        }
        restoreRisers(selection.attachments, savedRisers);
        if (savedFireMode != null) {
            try {
                com.tacz.guns.api.item.gun.FireMode.valueOf(savedFireMode);
                selection.attachments.put(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY, savedFireMode);
            } catch (IllegalArgumentException ignored) {
                dev.l41nx.mwloadout.MWLog.swallowed("server/LoadoutApplier:317", ignored);
            }
        }
    }

    // ---------------------------------------------------------------- apply

    /** Every enabled, visible slot deploys with SOMETHING: unpicked slots get a random valid
     *  entry written INTO the preset at equip time — the player sees exactly what the roulette
     *  handed them next time they open the screen, and can replace it. Validation goes through
     *  the same allowedIn() the picker uses, so a fill can never be an illegal selection. */
    /** Public roulette: fill every empty slot with random legal picks (context-menu RANDOM). */
    public static void randomFill(LoadoutPreset preset, boolean isOp) {
        autoFillEmptySlots(preset, isOp);
    }

    private static void autoFillEmptySlots(LoadoutPreset preset, boolean isOp) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        boolean overkill = hasSpecialPerk(preset, "overkill");
        boolean underkill = !overkill && hasSpecialPerk(preset, "underkill"); // Underkill: primary slot takes the secondary categories
        java.util.Random random = new java.util.Random();
        for (SlotDefinition slot : registry.slotList()) {
            if (!slot.enabled || slot.hidden || slot.optional) {
                continue; // optional slots (e.g. Extra 3 novelty) are never auto-stocked
            }
            LoadoutPreset.Selection selection = preset.slots.get(slot.id);
            if (selection != null && !selection.entryId.isEmpty()
                    && allowedIn(slot, registry.entry(selection.entryId), overkill, underkill, isOp) == null) {
                continue;
            }
            String previous = selection != null ? selection.entryId : "(empty)";
            List<CatalogEntry> pool = new ArrayList<>();
            for (CatalogEntry entry : registry.catalogList()) {
                if (allowedIn(slot, entry, overkill, underkill, isOp) == null) {
                    pool.add(entry);
                }
            }
            if (!pool.isEmpty()) {
                LoadoutPreset.Selection filled = new LoadoutPreset.Selection();
                filled.entryId = pool.get(random.nextInt(pool.size())).id;
                preset.slots.put(slot.id, filled);
                dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.warn(
                        "[MWLoadout][autoFill] {}: was '{}', RANDOM-FILLED with '{}' (pool size {})",
                        slot.id, previous, filled.entryId, pool.size());
            }
        }
    }

    /** Grant a single perk on top of the current loadout (Specialist streak reward). */
    public static void applySinglePerk(ServerPlayer player, String perkId) {
        PerkEffects.applySinglePerk(player, perkId);
        if ("fully_loaded".equals(perkId)) {
            topUpFullyLoaded(player); // its ammo is NOT an attribute: it only existed while building the class, so grant it now
        }
    }

    /** Gunsmith live apply: the player's loadout gun for {@code entry} gets {@code attachments} NOW, instead of at the next class apply. */
    public static void refreshManagedGun(ServerPlayer player, CatalogEntry entry, java.util.Map<String, String> attachments) {
        if (entry == null || entry.kind != CatalogEntry.Kind.GUN) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < 37; i++) { // 0-35 inventory + 36 = offhand in this indexing; getItem returns EMPTY beyond the container
            ItemStack stack = i < 36 ? player.getInventory().getItem(i) : player.getOffhandItem();
            var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            if (igun == null || !entry.gameId.equals(igun.getGunId(stack).toString()) || !stack.hasTag()
                    || !stack.getTag().getBoolean("mwloadout_managed")) {
                continue;
            }
            int oldCapacity = TaczStackFactory.currentMagazineCapacity(stack, entry.gameId);
            int loadedBefore = igun.getCurrentAmmoCount(stack);
            if (TaczStackFactory.refreshAttachments(stack, entry.gameId, attachments)) {
                // Extended Mag on/off changes the magazine size: the magazine and the reserve have to follow (see syncCapacity).
                syncCapacity(player, stack, entry, oldCapacity, TaczStackFactory.currentMagazineCapacity(stack, entry.gameId), loadedBefore);
                if (i < 36) {
                    player.getInventory().setItem(i, stack.copy()); // a fresh instance so TACZ re-reads the held gun's properties
                } else {
                    player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, stack.copy());
                }
                changed = true;
            }
            break;
        }
        if (!changed) {
            dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.info("[MWLoadout] gunsmith live apply: no managed {} found in {}'s inventory (nothing refreshed)", entry.gameId, player.getGameProfile().getName());
        }
        if (changed) {
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
        }
    }

    public static final String CAP_TAG = "mwloadout_cap";

    /** The magazine size changed (Extended Mag fitted / removed, by the gunsmith OR by TACZ's own refit screen): keep the gun consistent with
     *  what a freshly issued one would have. A magazine that was full is topped up to the new size, a smaller magazine cuts the rounds down,
     *  and the reserve moves by the difference of the two full reserves. A half-empty magazine keeps its rounds - nothing is refunded. */
    private static void syncCapacity(ServerPlayer player, ItemStack gun, CatalogEntry entry, int oldCapacity, int newCapacity, int loadedBefore) {
        if (newCapacity <= 0) {
            return;
        }
        var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(gun);
        dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.info("[MWLoadout] {} magazine {} -> {} (loaded before: {}, reserve target {} -> {})", entry.gameId, oldCapacity, newCapacity,
                loadedBefore, reserveTargetFor(player, entry, oldCapacity), reserveTargetFor(player, entry, newCapacity));
        if (igun != null && oldCapacity > 0 && oldCapacity != newCapacity) {
            if (newCapacity > oldCapacity && loadedBefore >= oldCapacity) {
                igun.setCurrentAmmoCount(gun, newCapacity);
            } else if (igun.getCurrentAmmoCount(gun) > newCapacity) {
                igun.setCurrentAmmoCount(gun, newCapacity);
            }
            adjustReserveForCapacity(player, gun, entry, oldCapacity, newCapacity);
        }
        gun.getOrCreateTag().putInt(CAP_TAG, newCapacity);
    }

    /** Server-side watcher (every 10 ticks): the capacity a managed gun was last known to have is stored on the stack, so ANY change of the
     *  magazine size is noticed - including attachments swapped in TACZ's own refit screen, which never goes through the gunsmith packet. */
    public static void tickAmmoCapacity(ServerPlayer player) {
        if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        for (int i = 0; i < 37; i++) {
            ItemStack stack = i < 36 ? player.getInventory().getItem(i) : player.getOffhandItem();
            if (stack.isEmpty() || !stack.hasTag() || !stack.getTag().getBoolean(MANAGED_TAG)) {
                continue;
            }
            var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            if (igun == null) {
                continue;
            }
            String gunId = igun.getGunId(stack).toString();
            int now = TaczStackFactory.currentMagazineCapacity(stack, gunId);
            if (now <= 0) {
                continue;
            }
            int known = stack.getTag().contains(CAP_TAG) ? stack.getTag().getInt(CAP_TAG) : -1;
            if (known == now) {
                continue;
            }
            CatalogEntry entry = LoadoutRegistry.get().entryByGameId(gunId);
            if (known <= 0 || entry == null) {
                stack.getOrCreateTag().putInt(CAP_TAG, now); // first sighting: just remember it
                continue;
            }
            syncCapacity(player, stack, entry, known, now, igun.getCurrentAmmoCount(stack));
        }
    }

    /** Reserve rounds a gun with this magazine capacity starts with - the exact formula apply() uses (dynamic ammo x category/tier multiplier x Fully Loaded). */
    private static int reserveTargetFor(ServerPlayer player, CatalogEntry entry, int capacity) {
        Rules rules = LoadoutRegistry.get().rules();
        if (!rules.dynamicAmmoEnabled || capacity <= 0) {
            return -1;
        }
        double multiplier = rules.effectiveAmmoMultiplierFor(capacity, entry.category, entry.ammoMultiplier);
        LoadoutPreset active = ProfileManager.get().profile(player.getUUID()).activePreset();
        if ((active != null && PerkEffects.hasFullyLoaded(active)) || specialistHasFullyLoaded(player)) {
            multiplier *= rules.fullyLoadedMultiplier;
        }
        return Math.max(1, (int) Math.round(capacity * multiplier));
    }

    /** Gunsmith live apply: after the magazine size changed, move the reserve by the same amount the class would have been given (what the
     *  player already fired stays fired - only the difference between the old and the new full reserve is added or taken away). */
    private static void adjustReserveForCapacity(ServerPlayer player, ItemStack gun, CatalogEntry entry, int oldCapacity, int newCapacity) {
        if (oldCapacity <= 0 || newCapacity <= 0 || oldCapacity == newCapacity) {
            return;
        }
        int oldTarget = reserveTargetFor(player, entry, oldCapacity);
        int newTarget = reserveTargetFor(player, entry, newCapacity);
        if (oldTarget < 0 || newTarget < 0) {
            return;
        }
        int delta = newTarget - oldTarget;
        if (delta == 0) {
            return;
        }
        var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(gun);
        if (igun == null) {
            return;
        }
        if (igun.useDummyAmmo(gun)) {
            igun.setDummyAmmoAmount(gun, Math.max(0, igun.getDummyAmmoAmount(gun) + delta));
            return;
        }
        if (delta > 0) {
            for (ItemStack ammo : TaczStackFactory.buildReserveAmmo(entry.gameId, delta)) {
                tagManaged(ammo);
                if (!player.getInventory().add(ammo)) {
                    player.drop(ammo, false);
                }
            }
            return;
        }
        // smaller magazine: take the surplus loose ammo of this gun back out of the inventory
        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(new ResourceLocation(entry.gameId))
                .map(index -> index.getGunData().getAmmoId()).orElse(null);
        int toRemove = -delta;
        for (int slot = 0; slot < 36 && toRemove > 0; slot++) {
            ItemStack inv = player.getInventory().getItem(slot);
            var iammo = com.tacz.guns.api.item.IAmmo.getIAmmoOrNull(inv);
            if (iammo == null || ammoId == null || !ammoId.equals(iammo.getAmmoId(inv))) {
                continue;
            }
            int take = Math.min(toRemove, inv.getCount());
            inv.shrink(take);
            toRemove -= take;
        }
    }

    private static boolean specialistHasFullyLoaded(ServerPlayer player) {
        return ProfileManager.get().profile(player.getUUID()).activeSpecialistPerks.contains("fully_loaded");
    }

    /** Fully Loaded granted mid-life (Specialist): the x-multiplier used to be applied only when the class was built, so a
     *  Specialist grant changed nothing. Raise every loadout gun's reserve to what a Fully Loaded class would have started with. */
    public static void topUpFullyLoaded(ServerPlayer player) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        Rules rules = registry.rules();
        LoadoutPreset preset = ProfileManager.get().profile(player.getUUID()).activePreset();
        PerkDefinition perk = registry.perk("fully_loaded");
        if (preset == null || perk == null || !perk.enabled || !rules.dynamicAmmoEnabled) {
            return;
        }
        for (SlotDefinition slot : registry.slotList()) {
            CatalogEntry entry = slot.enabled ? resolveEntry(registry, preset, slot) : null;
            if (entry == null || entry.kind != CatalogEntry.Kind.GUN) {
                continue;
            }
            LoadoutPreset.Selection selection = preset.slots.get(slot.id);
            java.util.Map<String, String> attachments = selection != null ? selection.attachments : java.util.Map.of();
            int capacity = TaczStackFactory.getMagazineCapacity(entry.gameId, attachments);
            if (capacity <= 0) {
                continue;
            }
            double multiplier = rules.effectiveAmmoMultiplierFor(capacity, entry.category, entry.ammoMultiplier) * rules.fullyLoadedMultiplier;
            int target = Math.max(1, (int) Math.round(capacity * multiplier));
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
                if (igun == null || !entry.gameId.equals(igun.getGunId(stack).toString()) || !stack.hasTag()
                        || !stack.getTag().getBoolean("mwloadout_managed")) {
                    continue;
                }
                if (igun.useDummyAmmo(stack)) {
                    int missing = target - igun.getDummyAmmoAmount(stack);
                    if (missing > 0) {
                        igun.addDummyAmmoAmount(stack, missing);
                    }
                } else {
                    for (ItemStack ammo : TaczStackFactory.buildReserveAmmo(entry.gameId, target)) { // reserve lives in the inventory
                        tagManaged(ammo);
                        if (!player.getInventory().add(ammo)) {
                            player.drop(ammo, false);
                        }
                    }
                }
                break;
            }
        }
        player.containerMenu.broadcastChanges();
    }

    /** Re-apply just the active loadout's perks (used to strip specialist-granted perks on death). */
    public static void reapplyActiveLoadoutPerks(ServerPlayer player) {
        LoadoutPreset active = ProfileManager.get().profile(player.getUUID()).activePreset();
        if (active != null) {
            PerkEffects.applyPerks(player, active);
        }
    }

    public static void apply(ServerPlayer player, LoadoutPreset preset) {
        EditRuleEngine.resetUseTracking(player); // fresh gear = fresh baseline for use detection
        KillstreakItems.suppressConsumption(player, 20); // rebuilding the inventory must not look like a killstreak use
        boolean isOp = player.hasPermissions(2);
        java.util.Map<String, String> before = new java.util.LinkedHashMap<>();
        for (var slotEntry : preset.slots.entrySet()) {
            before.put(slotEntry.getKey(), slotEntry.getValue().entryId);
        }
        sanitize(preset, Integer.MAX_VALUE, isOp);
        autoFillEmptySlots(preset, isOp);
        // Empty killstreak slots get random legal picks written INTO the class, like empty weapon slots and
        // perk columns do (distinct kill costs, max 3).
        {
            Rules streakRules = LoadoutRegistry.get().rules();
            if (dev.l41nx.mwloadout.data.StreakPool.needsFill(streakRules, preset)) {
                List<String> keep = preset.streaks == null ? List.of() : preset.streaks;
                preset.streaks = dev.l41nx.mwloadout.data.StreakPool.sanitize(
                        dev.l41nx.mwloadout.data.StreakPool.fillRandom(streakRules, keep, new java.util.Random()), streakRules);
            }
        }
        boolean changed = false;
        for (var slotEntry : preset.slots.entrySet()) {
            if (!slotEntry.getValue().entryId.equals(before.get(slotEntry.getKey()))) {
                changed = true;
                break;
            }
        }
        if (changed || before.size() != preset.slots.size()) {
            dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.info(
                    "[MWLoadout][apply] {} ({}): preset '{}' was modified during apply - before={} after={}",
                    player.getGameProfile().getName(), player.getUUID(), preset.name, before,
                    preset.slots.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            java.util.Map.Entry::getKey, e -> e.getValue().entryId)));
        }
        LoadoutRegistry registry = LoadoutRegistry.get();
        Rules rules = registry.rules();

        // Usage tracking for the admin Stats tab (see UsageStats doc for why this is counted at
        // equip time, not menu-selection time). Uses the FINAL sanitized preset, so a selection
        // that got dropped or fallback-recovered above is tracked as what actually gets equipped.
        List<String> trackedWeaponIds = new ArrayList<>();
        List<String> trackedCategories = new ArrayList<>();
        for (LoadoutPreset.Selection selection : preset.slots.values()) {
            CatalogEntry entry = registry.entry(selection.entryId);
            if (entry != null && entry.kind == CatalogEntry.Kind.GUN) {
                trackedWeaponIds.add(entry.id);
                trackedCategories.add(entry.category);
            }
        }
        dev.l41nx.mwloadout.registry.UsageStats stats = dev.l41nx.mwloadout.registry.UsageStats.get();
        stats.recordPick(player.getUUID(), player.getGameProfile().getName(), trackedWeaponIds, trackedCategories,
                preset.perks.values());
        if (stats.shouldAutoSave()) {
            stats.save();
        }

        // Earned killstreak items must survive a class swap no matter what: remember them now, put back any
        // that the placement below overwrote or displaced.
        List<ItemStack> carriedStreakItems = collectStreakItems(player);
        clearManagedInventory(player);
        relocateStreakItemsOutOfHotbarSlots(player, registry);

        List<ItemStack> reserveAmmo = new ArrayList<>();
        for (SlotDefinition slot : registry.slotList()) {
            if (!slot.enabled || slot.hotbarIndex < 0 || slot.hotbarIndex > 8) {
                continue;
            }
            CatalogEntry entry = resolveEntry(registry, preset, slot);
            if (entry == null) {
                continue;
            }
            ItemStack stack;
            if (entry.kind == CatalogEntry.Kind.GUN) {
                LoadoutPreset.Selection selection = preset.slots.get(slot.id);
                java.util.Map<String, String> gunAttachments =
                        selection != null ? selection.attachments : java.util.Map.of();
                // Reserve = magazine capacity x effective multiplier, full stop. Magazine
                // capacity read fresh off THIS build's attachments, so an Extended Mag raises
                // reserve right along with magazine size. Multiplier precedence (most to least
                // specific): this weapon's own ammoMultiplier > its category's multiplier
                // (Rules.categoryMultipliers) > the magazine-capacity tier table
                // (Rules.dynamicAmmoTiers) — see Rules.effectiveAmmoMultiplierFor. Fully Loaded
                // STACKS fullyLoadedMultiplier onto whichever of those three answered — it must
                // never replace it outright, or a high-multiplier gun would see its reserve DROP
                // once Fully Loaded came in on top of a flat override.
                int rounds;
                if (rules.dynamicAmmoEnabled) {
                    int magazineCapacity = TaczStackFactory.getMagazineCapacity(entry.gameId, gunAttachments);
                    if (magazineCapacity > 0) {
                        double multiplier = rules.effectiveAmmoMultiplierFor(magazineCapacity, entry.category, entry.ammoMultiplier);
                        if (PerkEffects.hasFullyLoaded(preset) || specialistHasFullyLoaded(player)) {
                            multiplier *= rules.fullyLoadedMultiplier;
                        }
                        rounds = Math.max(1, (int) Math.round(magazineCapacity * multiplier));
                    } else {
                        rounds = rules.reserveAmmoFor(entry.gameId, entry.category); // TACZ lookup failed - static fallback
                    }
                } else {
                    rounds = rules.reserveAmmoFor(entry.gameId, entry.category);
                }
                if (rules.ammoInGunNbt) {
                    // Reserve rides in the gun itself (DummyAmmo NBT) — nothing to spill into
                    // the inventory, nothing for anyone to drop-farm.
                    stack = TaczStackFactory.buildGun(entry.gameId, gunAttachments, rounds);
                } else {
                    stack = TaczStackFactory.buildGun(entry.gameId, gunAttachments);
                    reserveAmmo.addAll(TaczStackFactory.buildReserveAmmo(entry.gameId, rounds));
                }
            } else {
                int equipmentBonus = PerkEffects.equipmentBonus(preset, slot);
                stack = TaczStackFactory.buildItem(entry, slot.maxCount, equipmentBonus);
                // Items whose max stack size is below the configured count (LRTactical throwables
                // are maxStackSize 1) previously got silently clamped by the container sync — the
                // "2 in the loadout, 1 in hand" bug. The hotbar takes what fits; the remainder
                // spills into the main inventory as extra stacks.
                if (!stack.isEmpty() && stack.getCount() > stack.getMaxStackSize()) {
                    int remainder = stack.getCount() - stack.getMaxStackSize();
                    stack.setCount(stack.getMaxStackSize());
                    while (remainder > 0) {
                        ItemStack extra = stack.copy();
                        extra.setCount(Math.min(remainder, stack.getMaxStackSize()));
                        remainder -= extra.getCount();
                        tagManaged(extra);
                        reserveAmmo.add(extra);
                    }
                }
            }
            if (!stack.isEmpty()) {
                tagManaged(stack);
                player.getInventory().setItem(slot.hotbarIndex, stack);
                if (FieldUpgrades.isFieldUpgradeSlot(slot) && entry.kind == CatalogEntry.Kind.ITEM) {
                    FieldUpgrades.onEquipped(player, slot, entry, stack); // recharge rules (see FieldUpgrades)
                } else if (FieldUpgrades.isFieldUpgradeSlot(slot)) {
                    FieldUpgrades.onUnequipped(player, slot);
                }
            } else if (FieldUpgrades.isFieldUpgradeSlot(slot)) {
                FieldUpgrades.onUnequipped(player, slot);
            }
        }

        // Reserve ammo (and spilled equipment) goes into main inventory rows (9..35), never hotbar.
        int inventorySlot = 9;
        for (ItemStack ammo : reserveAmmo) {
            tagManaged(ammo);
            while (inventorySlot < 36 && !player.getInventory().getItem(inventorySlot).isEmpty()) {
                inventorySlot++;
            }
            if (inventorySlot >= 36) {
                break;
            }
            player.getInventory().setItem(inventorySlot++, ammo);
        }

        restoreMissingStreakItems(player, carriedStreakItems);
        KillstreakManager.fillSpecialistPerks(player); // a class swap can clash with staged Specialist perks
        PerkEffects.applyPerks(player, preset);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static List<ItemStack> collectStreakItems(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (KillstreakItems.isStreakItem(stack)) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /** Re-adds any remembered streak item that is no longer carried (placement overwrote its slot, or the
     *  relocation had no free slot). Last resort is dropping it at the player's feet - never deleting it. */
    private static void restoreMissingStreakItems(ServerPlayer player, List<ItemStack> remembered) {
        var inventory = player.getInventory();
        for (ItemStack wanted : remembered) {
            int have = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (KillstreakItems.isStreakItem(stack) && ItemStack.isSameItemSameTags(stack, wanted)) {
                    have += stack.getCount();
                }
            }
            if (have >= wanted.getCount()) {
                continue;
            }
            ItemStack missing = wanted.copy();
            missing.setCount(wanted.getCount() - have);
            dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.warn("[MWLoadout][apply] restoring a killstreak item that the class swap displaced: {}",
                    missing);
            if (!inventory.add(missing)) {
                player.drop(missing, false);
            }
        }
    }

    /** Locked slots force the admin default; otherwise player selection, else slot default. */
    private static CatalogEntry resolveEntry(LoadoutRegistry registry, LoadoutPreset preset, SlotDefinition slot) {
        if (slot.locked) {
            return slot.defaultEntry.isEmpty() ? null : registry.entry(slot.defaultEntry);
        }
        LoadoutPreset.Selection selection = preset.slots.get(slot.id);
        if (selection != null && !selection.entryId.isEmpty()) {
            CatalogEntry entry = registry.entry(selection.entryId);
            if (entry != null && entry.enabled) {
                return entry;
            }
        }
        return slot.defaultEntry.isEmpty() ? null : registry.entry(slot.defaultEntry);
    }

    public static final String MANAGED_TAG = "mwloadout_managed";

    /** Every stack this system hands out carries a marker tag: the drop guard and death cleanup
     *  in ServerEvents key off it, so ONLY loadout items are protected/culled — anything the
     *  player obtained legitimately elsewhere is never touched. */
    static void tagManaged(ItemStack stack) {
        if (!stack.isEmpty()) {
            stack.getOrCreateTag().putBoolean(MANAGED_TAG, true);
        }
    }

    public static boolean isManaged(ItemStack stack) {
        return !stack.isEmpty() && stack.getTag() != null && stack.getTag().getBoolean(MANAGED_TAG);
    }

    /** Hotbar + main inventory + offhand. Armor and curios untouched by design. Killstreak
     *  weapons are also preserved (whitelist): a queued/carried streak gun with ammo survives a
     *  loadout swap and only leaves when fired dry — per the README. */
    /** clearManagedInventory() preserves a queued/carried streak weapon wherever it happens to be
     *  sitting — but Killstreak.giveStreakItem() places it via vanilla "first empty slot" logic,
     *  with no awareness of which hotbar indices this system is about to write loadout weapons
     *  into. If the streak item landed in one of THOSE slots, the placement loop below would
     *  silently overwrite it a moment later — the streak reward vanishing on a class swap, purely
     *  by chance of which slot it happened to occupy. Move it out of the way first instead.
     */
    private static void relocateStreakItemsOutOfHotbarSlots(ServerPlayer player, LoadoutRegistry registry) {
        Set<Integer> reserved = new HashSet<>();
        for (SlotDefinition slot : registry.slotList()) {
            if (slot.enabled && slot.hotbarIndex >= 0 && slot.hotbarIndex <= 8) {
                reserved.add(slot.hotbarIndex);
            }
        }
        for (int hotbarIndex : reserved) {
            ItemStack existing = player.getInventory().getItem(hotbarIndex);
            if (!KillstreakItems.isStreakItem(existing)) {
                continue;
            }
            int freeSlot = -1;
            for (int candidate = 0; candidate < 36; candidate++) {
                if (reserved.contains(candidate)) {
                    continue; // don't just shuffle it into another slot we're about to overwrite too
                }
                if (player.getInventory().getItem(candidate).isEmpty()) {
                    freeSlot = candidate;
                    break;
                }
            }
            if (freeSlot >= 0) {
                player.getInventory().setItem(freeSlot, existing);
                player.getInventory().setItem(hotbarIndex, ItemStack.EMPTY);
            }
            // No free slot anywhere: leave it be. The placement loop overwriting it here is the
            // same outcome vanilla would give a full inventory anyway (nowhere left to put it).
        }
    }

    public static void clearManagedInventory(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (KillstreakItems.isStreakItem(existing)) {
                continue; // keep the streak weapon across loadout changes
            }
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!KillstreakItems.isStreakItem(off)) {
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }
}
