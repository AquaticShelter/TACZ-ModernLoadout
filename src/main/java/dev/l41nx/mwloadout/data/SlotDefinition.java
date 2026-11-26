package dev.l41nx.mwloadout.data;

import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One admin-configurable loadout slot bound to a hotbar index. Everything an admin can express
 * about a slot lives here — the validator, the player GUI and the admin GUI all read the same
 * definition, so a rule change in one place is instantly the rule everywhere.
 *
 * Category strings are free-form (matched against {@link CatalogEntry#category}); admins invent
 * new categories just by typing the same string in both places — no code, no enum to extend.
 */
public final class SlotDefinition {

    public String id = "";
    public int hotbarIndex;
    public String displayName = "";
    public String description = "";
    /** Item id rendered as the slot's icon in GUIs when the slot is empty. */
    public String iconItem = "minecraft:item_frame";
    public List<String> allowedCategories = new ArrayList<>();
    /** Catalog ids always allowed regardless of category. */
    public List<String> whitelist = new ArrayList<>();
    /** Catalog ids never allowed, wins over everything. */
    public List<String> blacklist = new ArrayList<>();
    /** Stack count given for non-gun entries (guns are always 1). Entry-level count wins if set. */
    public int maxCount = 1;
    /** Catalog id auto-selected for new loadouts / invalidated selections; empty = start empty. */
    public String defaultEntry = "";
    /** Required: loadout can't be saved with this slot empty. */
    public boolean required;
    /** Locked: players can't change it — always receives defaultEntry (admin-forced content). */
    public boolean locked;
    /** Hidden: not shown in the player GUI at all (still applied if locked+default set). */
    public boolean hidden;
    /** Disabled: slot doesn't exist for gameplay purposes (nothing applied, nothing shown). */
    public boolean enabled = true;
    /** When true this slot is NEVER random-filled — it stays empty until the player deliberately
     *  picks something (e.g. an Extra 3 stocked with a novelty item like a pufferfish). */
    public boolean optional;

    public static SlotDefinition of(String id, int hotbarIndex, String displayName, String description,
                                     String iconItem, List<String> categories, String defaultEntry, boolean required) {
        SlotDefinition slot = new SlotDefinition();
        slot.id = id;
        slot.hotbarIndex = hotbarIndex;
        slot.displayName = displayName;
        slot.description = description;
        slot.iconItem = iconItem;
        slot.allowedCategories = new ArrayList<>(categories);
        slot.defaultEntry = defaultEntry;
        slot.required = required;
        return slot;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("hotbarIndex", hotbarIndex);
        json.addProperty("displayName", displayName);
        json.addProperty("description", description);
        json.addProperty("iconItem", iconItem);
        json.add("allowedCategories", Json.toArray(allowedCategories));
        json.add("whitelist", Json.toArray(whitelist));
        json.add("blacklist", Json.toArray(blacklist));
        json.addProperty("maxCount", maxCount);
        json.addProperty("defaultEntry", defaultEntry);
        json.addProperty("required", required);
        json.addProperty("locked", locked);
        json.addProperty("hidden", hidden);
        json.addProperty("enabled", enabled);
        json.addProperty("optional", optional);
        return json;
    }

    public static SlotDefinition fromJson(JsonObject json) {
        SlotDefinition slot = new SlotDefinition();
        slot.id = Json.str(json, "id", "");
        slot.hotbarIndex = Json.intValue(json, "hotbarIndex", 0);
        slot.displayName = Json.str(json, "displayName", slot.id);
        slot.description = Json.str(json, "description", "");
        slot.iconItem = Json.str(json, "iconItem", "minecraft:item_frame");
        slot.allowedCategories = Json.strList(json, "allowedCategories");
        slot.whitelist = Json.strList(json, "whitelist");
        slot.blacklist = Json.strList(json, "blacklist");
        slot.maxCount = Json.intValue(json, "maxCount", 1);
        slot.defaultEntry = Json.str(json, "defaultEntry", "");
        slot.required = Json.bool(json, "required", false);
        slot.locked = Json.bool(json, "locked", false);
        slot.hidden = Json.bool(json, "hidden", false);
        slot.enabled = Json.bool(json, "enabled", true);
        slot.optional = Json.bool(json, "optional", false);
        return slot;
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(Json.GSON.toJson(toJson()), 32767);
    }

    public static SlotDefinition fromBytes(FriendlyByteBuf buf) {
        return fromJson(Json.GSON.fromJson(buf.readUtf(32767), JsonObject.class));
    }
}
