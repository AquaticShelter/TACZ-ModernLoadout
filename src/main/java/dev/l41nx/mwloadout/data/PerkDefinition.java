package dev.l41nx.mwloadout.data;

import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One selectable perk. Which perk COLUMN it appears in is data ("perkSlot" matches a
 * Rules.perkSlots id), and what it does is data too — a list of effect objects the server-side
 * PerkEffects engine interprets:
 *
 *   {"type":"attribute", "attribute":"minecraft:generic.movement_speed", "amount":0.011}
 *       → an ADDITION AttributeModifier with a stable per-perk UUID. Deliberately a modifier and
 *         NOT a base-value overwrite (even though the reference /attribute commands set base):
 *         modifiers stack correctly with other mods and are removable without having to remember
 *         and restore the original base value. Amounts are pre-translated so the END RESULT
 *         matches the reference commands (e.g. base 0.1 + 0.011 = the requested 0.111).
 *   {"type":"mob_effect", "effect":"minecraft:regeneration", "amplifier":0}
 *       → infinite, ambient, icon-hidden potion effect while the perk is worn.
 *   {"type":"special", "key":"overkill" | "scavenger"}
 *       → behavior implemented in code (overkill rewires slot-2 validation; scavenger restocks).
 *
 * Unknown types/keys are ignored, so future effect engines can add vocabulary without breaking
 * old saves.
 */
public final class PerkDefinition {

    public String id = "";
    public String displayName = "";
    public String description = "";
    /** Texture path under assets/mwloadout/ (e.g. "textures/perks/overkill.png"). */
    public String icon = "";
    /** Which perk column this perk belongs to (matches Rules.perkSlots ids). */
    public String perkSlot = "";
    public boolean enabled = true;
    public List<JsonObject> effects = new ArrayList<>();

    /** 1..3 for a perk that exists ONLY in that Specialist column ("specialist3" = the third Specialist stage); 0 for an ordinary perk.
     *  Such a perk never shows in the regular Perk 1/2/3 pickers and is not applied from a class's perk columns. */
    public int specialistColumn() {
        if (perkSlot == null || !perkSlot.startsWith("specialist")) {
            return 0;
        }
        try {
            return Integer.parseInt(perkSlot.substring("specialist".length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static PerkDefinition of(String id, String name, String description, String icon, String perkSlot, List<JsonObject> effects) {
        PerkDefinition perk = new PerkDefinition();
        perk.id = id;
        perk.displayName = name;
        perk.description = description;
        perk.icon = icon;
        perk.perkSlot = perkSlot;
        // a COPY: callers pass List.of(...), which is immutable - and the heal passes (healLegacyAmmoBonus, the Battle Hardened
        // reduces/immune entry) and the admin editor all mutate this list. Storing the argument directly crashed the server at start.
        perk.effects = new java.util.ArrayList<>(effects);
        return perk;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("displayName", displayName);
        json.addProperty("description", description);
        json.addProperty("icon", icon);
        json.addProperty("perkSlot", perkSlot);
        json.addProperty("enabled", enabled);
        JsonArray array = new JsonArray();
        effects.forEach(array::add);
        json.add("effects", array);
        return json;
    }

    public static PerkDefinition fromJson(JsonObject json) {
        PerkDefinition perk = new PerkDefinition();
        perk.id = Json.str(json, "id", "");
        perk.displayName = Json.str(json, "displayName", perk.id);
        perk.description = Json.str(json, "description", "");
        perk.icon = Json.str(json, "icon", "");
        perk.perkSlot = Json.str(json, "perkSlot", "");
        perk.enabled = Json.bool(json, "enabled", true);
        JsonElement effectsElement = json.get("effects");
        if (effectsElement != null && effectsElement.isJsonArray()) {
            for (JsonElement element : effectsElement.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    perk.effects.add(element.getAsJsonObject());
                }
            }
        }
        return perk;
    }
}
