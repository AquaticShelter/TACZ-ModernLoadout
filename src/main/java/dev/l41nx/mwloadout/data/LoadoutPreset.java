package dev.l41nx.mwloadout.data;

import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One saved class: a name, one Selection per slot id, and one perk id per perk-slot id.
 * Selections reference catalog ids (never raw ItemStacks) — the server rebuilds real stacks from
 * the catalog on every apply, so a stale/hacked preset can never smuggle arbitrary NBT into the
 * inventory; it can only name things the admin actually allowed.
 */
public final class LoadoutPreset {

    /** One slot's choice: a catalog entry plus (guns only) attachment ids by TACZ slot type. */
    public static final class Selection {
        public String entryId = "";
        /** Attachment TYPE name (SCOPE/MUZZLE/GRIP/STOCK/LASER/EXTENDED_MAG) -> attachment id. */
        public Map<String, String> attachments = new HashMap<>();

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("entryId", entryId);
            JsonObject attachmentsJson = new JsonObject();
            attachments.forEach(attachmentsJson::addProperty);
            json.add("attachments", attachmentsJson);
            return json;
        }

        public static Selection fromJson(JsonObject json) {
            Selection selection = new Selection();
            selection.entryId = Json.str(json, "entryId", "");
            JsonElement attachmentsElement = json.get("attachments");
            if (attachmentsElement != null && attachmentsElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : attachmentsElement.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        selection.attachments.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            return selection;
        }
    }

    public String name = "Class";
    /** SlotDefinition.id -> selection. Absent key = slot left empty. */
    public Map<String, Selection> slots = new HashMap<>();
    /** Rules.perkSlots id -> PerkDefinition.id. Absent = no perk in that column. */
    public Map<String, String> perks = new HashMap<>();
    /** Killstreak rewards this class brings (Rules.streakRewards item ids): at most 3, distinct kill counts.
     *  {@code null} = never customised -> the default selection (see StreakPool). */
    public List<String> streaks = null;

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        JsonObject slotsJson = new JsonObject();
        slots.forEach((slotId, selection) -> slotsJson.add(slotId, selection.toJson()));
        json.add("slots", slotsJson);
        JsonObject perksJson = new JsonObject();
        perks.forEach(perksJson::addProperty);
        json.add("perks", perksJson);
        if (streaks != null) {
            JsonArray streaksJson = new JsonArray();
            streaks.forEach(streaksJson::add);
            json.add("streaks", streaksJson);
        }
        return json;
    }

    public static LoadoutPreset fromJson(JsonObject json) {
        LoadoutPreset preset = new LoadoutPreset();
        preset.name = Json.str(json, "name", "Class");
        JsonElement slotsElement = json.get("slots");
        if (slotsElement != null && slotsElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : slotsElement.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    preset.slots.put(entry.getKey(), Selection.fromJson(entry.getValue().getAsJsonObject()));
                }
            }
        }
        JsonElement perksElement = json.get("perks");
        if (perksElement != null && perksElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : perksElement.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    preset.perks.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        JsonElement streaksElement = json.get("streaks");
        if (streaksElement != null && streaksElement.isJsonArray()) {
            preset.streaks = new ArrayList<>();
            streaksElement.getAsJsonArray().forEach(element -> {
                if (element.isJsonPrimitive()) {
                    preset.streaks.add(element.getAsString());
                }
            });
        }
        return preset;
    }
}
