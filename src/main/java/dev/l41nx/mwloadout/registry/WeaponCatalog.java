package dev.l41nx.mwloadout.registry;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import com.tacz.guns.api.TimelessAPI;

/**
 * Auto-populates GUN catalog entries from every gun in every loaded TACZ gunpack, so new packs
 * appear in pickers with zero admin work. Runs at server start AFTER the registry JSON load:
 * only ADDS ids that don't already exist, so admin edits (renames, recategorizations, disables)
 * always survive a restart — the auto-scan never overwrites a human decision.
 *
 * Category comes from the pack's own type string (TACZ gun indices carry "ar"/"smg"/"lmg"/
 * "sniper"/"shotgun"/"pistol"/"rpg"/"mg"...), normalized onto the category vocabulary the
 * default slots use. Unknown types pass through lowercase as-is — an admin can still allow them
 * on a slot by typing that same string.
 */
public final class WeaponCatalog {

    private WeaponCatalog() {
    }

    public static void autoSeed(LoadoutRegistry registry) {
        int[] added = {0};
        try {
            TimelessAPI.getAllCommonGunIndex().forEach(pair -> {
                String gunId = pair.getKey().toString();
                String catalogId = "gun_" + gunId.replace(':', '_').replace('/', '_');
                if (registry.entry(catalogId) != null) {
                    return;
                }
                String type = "";
                String packName = null;
                try {
                    type = pair.getValue().getType();
                    packName = pair.getValue().getPojo().getName();
                } catch (Exception ignored) {
                    dev.l41nx.mwloadout.MWLog.swallowed("registry/WeaponCatalog:37", ignored);
                }
                String display = packName != null && !packName.isEmpty() && !packName.startsWith("tacz.")
                        ? packName : prettyName(gunId);
                CatalogEntry entry = CatalogEntry.gun(catalogId, display, normalizeCategory(type), gunId);
                registry.upsertEntry(entry);
                added[0]++;
            });
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] TACZ gun scan failed", e);
        }
        if (added[0] > 0) {
            MWLoadoutMod.LOGGER.info("[MWLoadout] Auto-added {} guns from loaded gunpacks", added[0]);
            registry.save();
        }
    }

    private static String normalizeCategory(String taczType) {
        if (taczType == null) {
            return "ar";
        }
        return switch (taczType.toLowerCase()) {
            case "ar", "rifle" -> "ar";
            case "smg" -> "smg";
            case "lmg", "mg" -> "lmg";
            case "sniper" -> "sr";
            case "dmr", "marksman" -> "dmr";
            case "shotgun" -> "sg";
            case "pistol" -> "pistol";
            case "rpg", "launcher" -> "launcher";
            default -> taczType.toLowerCase();
        };
    }

    private static String prettyName(String gunId) {
        String path = gunId.contains(":") ? gunId.substring(gunId.indexOf(':') + 1) : gunId;
        String[] words = path.replace('_', ' ').trim().split(" ");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.length() <= 3
                    ? word.toUpperCase()
                    : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.length() == 0 ? gunId : out.toString();
    }
}
