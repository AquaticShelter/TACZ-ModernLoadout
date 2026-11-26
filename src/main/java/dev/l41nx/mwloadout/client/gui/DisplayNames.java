package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.data.CatalogEntry;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Human names for catalog entries. The server-side scanner can only store what it sees, and a
 * dedicated/integrated server has no client lang files — so gun names arrive as raw translation
 * keys ("item.tacz.modern_kinetic_gun"). The CLIENT is where language lives, so resolution
 * happens here at draw time:
 *
 *   guns -> the pack's own name via ClientGunIndex#getName (what TACZ itself shows), which may
 *           itself be a lang key -> I18n; anything else -> I18n if the string looks like a key
 *           and the key exists; otherwise the stored text as-is.
 *
 * Cached per entry; invalidated with the other icon caches on registry resync (covers language
 * switching mid-session too, since a resync also lands on dimension change/login).
 */
public final class DisplayNames {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private DisplayNames() {
    }

    public static void clear() {
        CACHE.clear();
    }

    public static String of(CatalogEntry entry) {
        if (entry == null) {
            return "";
        }
        return CACHE.computeIfAbsent(entry.id + "|" + entry.displayName, key -> resolve(entry));
    }

    /**
     * attachment ids have no CatalogEntry and no gun-style
     * ClientGunIndex#getName() shortcut to read a lang name from — the old callers just
     * upper-cased the raw resource-location path (id "speed_mag" -> "SPEED MAG") instead of the
     * item's real lang name ("APH Upgrade"). The only reliable source of that name is the actual
     * ItemStack TACZ builds for the id (same one the gunsmith installs) via its normal
     * {@code getHoverName()} — exactly what the player already sees on the item elsewhere in
     * TACZ's own UI. Falls back to a formatted id only if the stack can't be built at all (pack
     * removed), never shown as the primary case.
     */
    public static String ofAttachment(String attachmentId) {
        if (attachmentId == null || attachmentId.isEmpty()) {
            return "";
        }
        return CACHE.computeIfAbsent("attachment|" + attachmentId, key -> resolveAttachment(attachmentId));
    }

    private static String resolveAttachment(String attachmentId) {
        try {
            net.minecraft.world.item.ItemStack stack =
                    dev.l41nx.mwloadout.server.TaczStackFactory.buildAttachment(attachmentId);
            if (!stack.isEmpty()) {
                String name = stack.getHoverName().getString();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/DisplayNames:69", ignored);
        }
        return prettyFallback(attachmentId);
    }

    /** Last-resort formatting when the real item name can't be resolved (e.g. the attachment's
     *  pack was removed) — a formatted id is still better than a hard crash or a blank label,
     *  but this must never be the path taken for a normally-installed attachment. */
    private static String prettyFallback(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] words = path.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.length() == 0 ? id : out.toString();
    }

    /** KillstreakWeaponScreen had its own separate, ad-hoc raw-id formatter
     *  for streak reward guns instead of going through the same real-name resolution guns get
     *  everywhere else (the gunsmith, the loadout screen, etc.) — same class of bug as #7, just
     *  for a bare gun id instead of a CatalogEntry. */
    public static String ofGun(String gunId) {
        if (gunId == null || gunId.isEmpty()) {
            return "";
        }
        return CACHE.computeIfAbsent("gun|" + gunId, key -> resolveGun(gunId));
    }

    /** Name for ANY killstreak reward id (catalog id, gun id, item id +SNBT, lang key): the same
     *  resolution the server uses to grant it, so the label matches what the player receives. */
    public static String ofAny(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return CACHE.computeIfAbsent("any|" + id, key -> resolveAny(id));
    }

    private static String resolveAny(String id) {
        try {
            CatalogEntry entry = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().entry(id.trim());
            if (entry != null) {
                return resolve(entry);
            }
            net.minecraft.world.item.ItemStack stack = dev.l41nx.mwloadout.server.StreakItemFactory.build(id);
            if (!stack.isEmpty()) {
                com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
                if (gun != null && gun.getGunId(stack) != null) {
                    return resolveGun(gun.getGunId(stack).toString());
                }
                String name = stack.getHoverName().getString();
                if (name != null && !name.isEmpty()) {
                    return looksLikeKey(name) && I18n.exists(name) ? I18n.get(name) : name;
                }
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/DisplayNames:130", ignored);
        }
        String bare = id.indexOf('{') > 0 ? id.substring(0, id.indexOf('{')) : id;
        return looksLikeKey(bare) && I18n.exists(bare) ? I18n.get(bare) : prettyFallback(bare);
    }

    private static String resolveGun(String gunId) {
        try {
            var index = TimelessAPI.getClientGunIndex(new ResourceLocation(gunId));
            if (index.isPresent()) {
                String packName = index.get().getName();
                if (packName != null && !packName.isEmpty()) {
                    return looksLikeKey(packName) && I18n.exists(packName) ? I18n.get(packName) : packName;
                }
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/DisplayNames:145", ignored);
        }
        return prettyFallback(gunId);
    }

    private static String resolve(CatalogEntry entry) {
        if (entry.kind == CatalogEntry.Kind.GUN) {
            try {
                var index = TimelessAPI.getClientGunIndex(new ResourceLocation(entry.gameId));
                if (index.isPresent()) {
                    String packName = index.get().getName();
                    if (packName != null && !packName.isEmpty()) {
                        return looksLikeKey(packName) && I18n.exists(packName) ? I18n.get(packName) : packName;
                    }
                }
            } catch (Exception ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/gui/DisplayNames:160", ignored);
            }
        }
        // the Admin Panel's CATALOG list shows the real
        // attachment name (via the same resolver AttachmentEditorScreen uses) instead of the raw
        // scanned id. IMPORTANT: call resolveAttachment() directly, NOT ofAttachment() — this
        // method only ever runs from inside CACHE.computeIfAbsent() (via of(), above), and
        // ConcurrentHashMap explicitly forbids calling computeIfAbsent again on the SAME map from
        // within another computeIfAbsent's mapping function — even under a different key, it
        // throws "IllegalStateException: Recursive update" every time, which is exactly what was
        // crashing the game the instant the Catalog tab tried to render an attachment row.
        // resolveAttachment() does the same lookup without touching CACHE a second time; of()'s
        // own computeIfAbsent already caches whatever it returns under the entry's key.
        if (entry.kind == CatalogEntry.Kind.ATTACHMENT) {
            return resolveAttachment(entry.gameId);
        }
        String raw = entry.displayName;
        if (raw != null && looksLikeKey(raw) && I18n.exists(raw)) {
            return I18n.get(raw);
        }
        return raw == null ? "" : raw;
    }

    private static boolean looksLikeKey(String text) {
        return text.indexOf('.') >= 0 && text.indexOf(' ') < 0;
    }
}
