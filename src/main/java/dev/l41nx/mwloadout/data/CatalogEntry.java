package dev.l41nx.mwloadout.data;

import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonObject;

/**
 * One selectable thing in the loadout system — either a TACZ gun or a plain item (optionally with
 * NBT, which is how LRTactical melee/consumable/throwable "virtual items" work: base item id +
 * MeleeWeaponId/ConsumableId/ThrowableId in NBT).
 *
 * Unified on purpose: slots don't care whether their contents are guns or items, they just
 * reference catalog ids and categories. The GUN/ITEM split only matters to the stack factory
 * (guns get attachments, a filled magazine and reserve ammo; items get count + NBT) and to the
 * GUI (guns get the attachment editor).
 *
 * Gun entries are auto-seeded from every loaded TACZ gunpack at server start (category taken from
 * the pack's own type string), so new gunpacks appear in the picker with zero admin work; admins
 * can then recategorize, disable, or blacklist per-slot. Item entries are admin-created.
 */
public final class CatalogEntry {

    // ATTACHMENT is its own kind, deliberately never referenced by any
    // SlotDefinition.allowedCategories — attachments are gunsmith parts, not equippable loadout
    // items, so they must never leak into a slot picker's candidate list the way a GUN/ITEM does.
    // Every `== Kind.GUN` / `!= Kind.ITEM` check elsewhere in the codebase already treats "not a
    // gun" as "an equippable item" for slot/loadout purposes; ATTACHMENT entries are filtered out
    // upstream (by category, in the attachment editor and the slot picker) before any of that
    // code ever sees one, so those existing two-way checks needed no changes.
    public enum Kind { GUN, ITEM, ATTACHMENT }

    public String id = "";
    public Kind kind = Kind.ITEM;
    public String displayName = "";
    /** Free-form, matched against SlotDefinition.allowedCategories ("ar", "smg", "sr", "sg",
     *  "lmg", "pistol", "launcher", "melee", "consumable", "lethal", "tactical", or anything an
     *  admin invents). */
    public String category = "";
    /** GUN: the TACZ gun id (e.g. "tacz:ak47"). ITEM: the registry item id. */
    public String gameId = "";
    /** ITEM only: SNBT merged into the stack (e.g. {ThrowableId:"mw19:frag_gn"}). */
    public String nbt = "";
    /** ITEM only: stack count handed out (slot maxCount caps it). */
    public int count = 1;
    public boolean enabled = true;
    /** Progression gate: required player level (0 = always unlocked). Only enforced when
     *  Rules.progressionEnabled. */
    public int unlockLevel = 0;
    /** GUN only: this weapon's OWN ammo reserve multiplier (0 or less = not set, falls through to
     *  Rules.categoryMultipliers then Rules.dynamicAmmoTiers — see Rules.effectiveAmmoMultiplierFor).
     *  Wins over every other source, including a category multiplier set for this same gun's
     *  category — the single most specific knob in the ammo precedence chain. Only meaningful
     *  when Rules.dynamicAmmoEnabled is on; ignored entirely otherwise. */
    public double ammoMultiplier = 0;
    /** GUN only: marks this entry as a Blueprint PLATFORM (MW2022-style). When true, the entries
     *  listed in blueprintIds are hidden from normal slot pickers and instead become switchable
     *  "forms" of THIS gun, reachable via its Gunsmith's Blueprints button. */
    public boolean hasBlueprint = false;
    /** Other CatalogEntry ids (same TACZ platform, different model/stats) offered as Blueprint
     *  switches on this entry. Ignored unless hasBlueprint is true. */
    public java.util.List<String> blueprintIds = new java.util.ArrayList<>();
    /** True only when CreativeTabScanner itself disabled this entry because its gun/item vanished
     *  from every creative tab (pack removed, gun re-hidden) — a "ghost ID". Distinct from an
     *  admin manually unchecking "enabled" for their own reasons: the reconciliation/cleanup UI
     *  only ever offers to touch entries flagged stale=true, never an admin's own disable. Cleared
     *  automatically the moment the scanner sees the gun/item again. */
    public boolean stale = false;
    /** Wall-clock ms when this entry was first flagged stale — 0 if never. Purely informational
     *  (admin cleanup UI can show "stale for 6 days"), never used for any automatic decision. */
    public long staleSinceMillis = 0;
    /** "Exclusive test" weapon (GUN only): completely invisible to non-OP players — never sent
     *  to their client at all (not just hidden client-side), never a valid selection to save or
     *  apply for them, and never a candidate for autoFillEmptySlots' random pool. An OP player
     *  sees and uses it exactly like any other catalog entry. */
    public boolean adminOnly = false;
    /** Recharge in seconds when this item sits in a FIELD UPGRADE slot. 0 = use Rules.fieldUpgradeCooldownSeconds. */
    public int cooldownSeconds = 0;
    /** FIELD UPGRADE items that are NOT consumed when used (reusable tools): start the recharge on a right-click instead
     *  of waiting for the stack to shrink. Off by default - clicking alone does not deploy anything. */
    public boolean cooldownOnClick = false;

    public static CatalogEntry gun(String id, String displayName, String category, String gunId) {
        CatalogEntry entry = new CatalogEntry();
        entry.id = id;
        entry.kind = Kind.GUN;
        entry.displayName = displayName;
        entry.category = category;
        entry.gameId = gunId;
        return entry;
    }

    public static CatalogEntry item(String id, String displayName, String category, String itemId, String nbt, int count) {
        CatalogEntry entry = new CatalogEntry();
        entry.id = id;
        entry.kind = Kind.ITEM;
        entry.displayName = displayName;
        entry.category = category;
        entry.gameId = itemId;
        entry.nbt = nbt;
        entry.count = count;
        return entry;
    }

    /** a scanned TACZ attachment, catalog-tracked so an admin can enable/disable
     *  individual attachments like any other item — but ALWAYS category "attachment", its own
     *  dedicated branch so it can never be matched into a gun/throwable/melee slot by accident. */
    public static CatalogEntry attachment(String id, String displayName, String attachmentId) {
        CatalogEntry entry = new CatalogEntry();
        entry.id = id;
        entry.kind = Kind.ATTACHMENT;
        entry.displayName = displayName;
        entry.category = "attachment";
        entry.gameId = attachmentId;
        return entry;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("kind", kind.name());
        json.addProperty("displayName", displayName);
        json.addProperty("category", category);
        json.addProperty("gameId", gameId);
        json.addProperty("nbt", nbt);
        json.addProperty("count", count);
        json.addProperty("enabled", enabled);
        json.addProperty("unlockLevel", unlockLevel);
        json.addProperty("ammoMultiplier", ammoMultiplier);
        json.addProperty("hasBlueprint", hasBlueprint);
        com.google.gson.JsonArray blueprintArray = new com.google.gson.JsonArray();
        blueprintIds.forEach(blueprintArray::add);
        json.add("blueprintIds", blueprintArray);
        json.addProperty("stale", stale);
        json.addProperty("staleSinceMillis", staleSinceMillis);
        json.addProperty("adminOnly", adminOnly);
        json.addProperty("cooldownSeconds", cooldownSeconds);
        json.addProperty("cooldownOnClick", cooldownOnClick);
        return json;
    }

    public static CatalogEntry fromJson(JsonObject json) {
        CatalogEntry entry = new CatalogEntry();
        entry.id = Json.str(json, "id", "");
        entry.kind = switch (Json.str(json, "kind", "ITEM").toUpperCase(java.util.Locale.ROOT)) {
            case "GUN" -> Kind.GUN;
            case "ATTACHMENT" -> Kind.ATTACHMENT;
            default -> Kind.ITEM;
        };
        entry.displayName = Json.str(json, "displayName", entry.id);
        entry.category = Json.str(json, "category", "");
        entry.gameId = Json.str(json, "gameId", "");
        entry.nbt = Json.str(json, "nbt", "");
        entry.count = Math.max(1, Json.intValue(json, "count", 1));
        entry.enabled = Json.bool(json, "enabled", true);
        entry.unlockLevel = Math.max(0, Json.intValue(json, "unlockLevel", 0));
        entry.ammoMultiplier = Json.doubleValue(json, "ammoMultiplier", 0);
        entry.hasBlueprint = Json.bool(json, "hasBlueprint", false);
        com.google.gson.JsonElement blueprintElement = json.get("blueprintIds");
        if (blueprintElement != null && blueprintElement.isJsonArray()) {
            for (com.google.gson.JsonElement element : blueprintElement.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    String id2 = element.getAsString();
                    if (id2 != null && !id2.isEmpty()) {
                        entry.blueprintIds.add(id2);
                    }
                }
            }
        }
        entry.stale = Json.bool(json, "stale", false);
        entry.staleSinceMillis = Json.longValue(json, "staleSinceMillis", 0L);
        entry.adminOnly = Json.bool(json, "adminOnly", false);
        entry.cooldownSeconds = Math.max(0, Json.intValue(json, "cooldownSeconds", 0));
        entry.cooldownOnClick = Json.bool(json, "cooldownOnClick", false);
        return entry;
    }
}
