package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * First-join seeding: a brand-new profile gets all ten classes pre-built — one gun per class,
 * category coverage across the deck (AR/SMG/LMG/SR/DMR/SG/BR then wrap), a random pistol
 * secondary, knife, stim, MW19 frag + flash, the curated extras (DragonRise shield, SuperbWarfare
 * claymore) when those mods are around, and the Quick Fix / Blood Rush / Scavenger perk trio (one per column).
 * No attachments — the gunsmith is the player's job.
 *
 * Guns (and melee/consumable/equipment, when there's more than one candidate) are picked with a
 * UUID-seeded Random: stable per player (relog doesn't reshuffle their deck) but different
 * between players, which reads as personality rather than chaos. Runs ONLY when the profile has
 * zero presets — one manual edit and this never touches them again.
 */
public final class DefaultLoadouts {

    private static final String[] CATEGORY_DECK = {"ar", "smg", "lmg", "sr", "dmr", "sg", "br", "ar", "smg", "lmg"};

    private DefaultLoadouts() {
    }

    /** Appends default classes until the profile has target presets — the padding path for
     *  pre-existing profiles. Deck position continues from the current count so categories
     *  still rotate. */
    public static void pad(PlayerProfile profile, UUID playerId, int target) {
        int start = profile.presets.size();
        buildInto(profile, playerId, start, target);
    }

    public static void populate(PlayerProfile profile, UUID playerId) {
        buildInto(profile, playerId, 0, Math.min(10, LoadoutRegistry.get().rules().maxPresets));
        profile.activeIndex = 0;
    }

    private static void buildInto(PlayerProfile profile, UUID playerId, int startIndex, int endExclusive) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        Rules rules = registry.rules();
        Random random = new Random(playerId.getMostSignificantBits() ^ playerId.getLeastSignificantBits());

        String meleeId = randomOfCategory(registry, "melee", random);
        String consumableId = randomOfCategory(registry, "consumable", random);
        String lethalId = preferNbt(registry, "lethal", "mw19:frag_gn", random);
        String tacticalId = preferNbt(registry, "tactical", "mw19:flash", random);
        String extra1Id = preferGameId(registry, "extra1", "dragonrise_reforge:shield", random);
        String extra2Id = preferGameId(registry, "extra2", "superbwarfare:claymore_mine", random);

        for (int i = startIndex; i < endExclusive; i++) {
            LoadoutPreset preset = new LoadoutPreset();
            preset.name = "CLASS " + (i + 1);
            select(preset, "primary", randomGun(registry, CATEGORY_DECK[i % CATEGORY_DECK.length], random));
            select(preset, "secondary", randomGun(registry, "pistol", random));
            select(preset, "melee", meleeId);
            select(preset, "consumable", consumableId);
            select(preset, "lethal", lethalId);
            select(preset, "tactical", tacticalId);
            select(preset, "extra1", extra1Id);
            select(preset, "extra2", extra2Id);
            preset.perks.put("perk1", "quick_fix");
            preset.perks.put("perk2", "double_time"); // Blood Rush
            preset.perks.put("perk3", "scavenger");
            profile.presets.add(preset);
        }
    }

    private static void select(LoadoutPreset preset, String slotId, String entryId) {
        if (entryId == null) {
            return;
        }
        LoadoutPreset.Selection selection = new LoadoutPreset.Selection();
        selection.entryId = entryId;
        preset.slots.put(slotId, selection);
    }

    private static String randomGun(LoadoutRegistry registry, String category, Random random) {
        List<CatalogEntry> pool = new ArrayList<>();
        for (CatalogEntry entry : registry.catalogList()) {
            if (entry.enabled && entry.kind == CatalogEntry.Kind.GUN && entry.category.equals(category)
                    && entry.unlockLevel <= 1) {
                pool.add(entry);
            }
        }
        if (pool.isEmpty()) {
            // Category absent from the installed packs: any gun beats an empty hand.
            for (CatalogEntry entry : registry.catalogList()) {
                if (entry.enabled && entry.kind == CatalogEntry.Kind.GUN) {
                    pool.add(entry);
                }
            }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size())).id;
    }

    private static String randomOfCategory(LoadoutRegistry registry, String category, Random random) {
        List<CatalogEntry> pool = new ArrayList<>();
        for (CatalogEntry entry : registry.catalogList()) {
            if (entry.enabled && entry.category.equals(category)) {
                pool.add(entry);
            }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size())).id;
    }

    private static String preferNbt(LoadoutRegistry registry, String category, String nbtId, Random random) {
        for (CatalogEntry entry : registry.catalogList()) {
            if (entry.enabled && entry.nbt != null && entry.nbt.contains(nbtId)) {
                return entry.id;
            }
        }
        return randomOfCategory(registry, category, random);
    }

    private static String preferGameId(LoadoutRegistry registry, String category, String gameId, Random random) {
        for (CatalogEntry entry : registry.catalogList()) {
            if (entry.enabled && gameId.equals(entry.gameId)) {
                return entry.id;
            }
        }
        return randomOfCategory(registry, category, random);
    }
}
