package dev.l41nx.mwloadout.registry;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for slots / catalog / perks / rules. Server loads it from
 * world/data/mwloadout/*.json (seeding MW19-spec defaults on first run), admins mutate it through
 * the admin packets (which call save()), and the whole thing serializes to one JSON blob for
 * client sync — clients keep a read-only copy for GUI rendering, never authority.
 */
public final class LoadoutRegistry {

    private static final LoadoutRegistry INSTANCE = new LoadoutRegistry();

    private final Map<String, SlotDefinition> slots = new LinkedHashMap<>();
    private final Map<String, CatalogEntry> catalog = new LinkedHashMap<>();
    private final Map<String, PerkDefinition> perks = new LinkedHashMap<>();
    private Rules rules = new Rules();
    private Path dataDir;

    private LoadoutRegistry() {
    }

    public static LoadoutRegistry get() {
        return INSTANCE;
    }

    public List<SlotDefinition> slotList() {
        return new ArrayList<>(slots.values());
    }

    public SlotDefinition slot(String id) {
        return slots.get(id);
    }

    public List<CatalogEntry> catalogList() {
        return new ArrayList<>(catalog.values());
    }

    public CatalogEntry entry(String id) {
        return catalog.get(id);
    }

    /** Catalog entry whose TACZ gameId matches — used to resolve a compare-pin (stored by
     *  gameId) back to a display name, AND to resolve Blueprint child gameIds to their catalog
     *  entry. Guns generally map 1:1, but two entries CAN legitimately share a gameId (the
     *  auto-seeded "gun_..." entry plus an admin's hand-captured/quick-add entry for the same
     *  TACZ gun) — and the auto-seeded one can independently flip enabled/disabled on every
     *  weaponscan (pack removed, or transiently missing from a creative tab during a slow
     *  world/datapack load). Blindly returning "the first match" could hand back a disabled
     *  duplicate even while a perfectly usable enabled one exists right next to it in the map —
     *  the exact failure mode behind Blueprint switches silently landing on an unusable entry.
     *  So: prefer an ENABLED match, and only fall back to a disabled one if that's all there is. */
    public CatalogEntry entryByGameId(String gameId) {
        if (gameId == null || gameId.isEmpty()) {
            return null;
        }
        CatalogEntry match = null;
        CatalogEntry disabledFallback = null;
        int totalMatches = 0;
        for (CatalogEntry entry : catalog.values()) {
            if (gameId.equals(entry.gameId)) {
                totalMatches++;
                if (entry.enabled && match == null) {
                    match = entry;
                }
                if (!entry.enabled && disabledFallback == null) {
                    disabledFallback = entry;
                }
            }
        }
        if (totalMatches > 1) {
            MWLoadoutMod.LOGGER.warn(
                    "[MWLoadout][registry] gameId '{}' matches {} catalog entries (duplicate ids) - "
                            + "resolved to '{}'. Check the admin catalog for a leftover auto-seeded entry "
                            + "next to a hand-added one.",
                    gameId, totalMatches, match != null ? match.id : (disabledFallback != null ? disabledFallback.id : "none"));
        }
        return match != null ? match : disabledFallback;
    }

    /** The base-platform entry that claims `gameId` as one of its Blueprints (hasBlueprint=true
     *  and gameId listed in its blueprintIds), or null if `gameId` isn't a blueprint child of
     *  anything. blueprintIds holds raw TACZ gameIds (e.g. "tacz:g36k"), not catalog ids — any
     *  installed addon's gun works, since guns auto-seed their own catalog entry at server start.
     *  A blueprint child is hidden from normal slot pickers — it's only reachable via its owner's
     *  Gunsmith "Blueprints" switcher. */
    public CatalogEntry blueprintOwnerByGameId(String gameId) {
        if (gameId == null || gameId.isEmpty()) {
            return null;
        }
        for (CatalogEntry candidate : catalog.values()) {
            if (candidate.hasBlueprint && candidate.blueprintIds.contains(gameId)) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isBlueprintChildGameId(String gameId) {
        return blueprintOwnerByGameId(gameId) != null;
    }

    public List<PerkDefinition> perkList() {
        return new ArrayList<>(perks.values());
    }

    public PerkDefinition perk(String id) {
        return perks.get(id);
    }

    /** The perks Specialist can pick from: every enabled perk (default) or the admin's curated specialistPerkPool. */
    public java.util.List<String> specialistPool() {
        java.util.List<String> all = new java.util.ArrayList<>();
        if (!rules.specialistPoolAll) {
            for (String id : rules.specialistPerkPool) {
                if (!Rules.SPECIALIST_EXCLUDED.contains(id)) {
                    all.add(id);
                }
            }
            return all;
        }
        for (dev.l41nx.mwloadout.data.PerkDefinition perk : perkList()) {
            if (perk.enabled && !Rules.SPECIALIST_EXCLUDED.contains(perk.id)) {
                all.add(perk.id);
            }
        }
        return all;
    }

    public Rules rules() {
        return rules;
    }

    public void upsertSlot(SlotDefinition slot) {
        slots.put(slot.id, slot);
    }

    public void removeSlot(String id) {
        slots.remove(id);
    }

    public void upsertEntry(CatalogEntry entry) {
        catalog.put(entry.id, entry);
    }

    public void removeEntry(String id) {
        catalog.remove(id);
    }

    /** Batch delete (Admin Panel multi-select). Returns how many actually existed and got removed
     *  — callers use this for the "X entries deleted" confirmation, not just ids.size(). */
    public int removeEntries(java.util.Collection<String> ids) {
        int removed = 0;
        for (String id : ids) {
            if (catalog.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    /** Batch enable/disable (Admin Panel multi-select). Skips ids that don't resolve to an entry
     *  rather than throwing — a stale selection (entry deleted by someone else mid-batch) just
     *  gets silently skipped. Returns how many were actually changed. */
    public int setEntriesEnabled(java.util.Collection<String> ids, boolean enabled) {
        int changed = 0;
        for (String id : ids) {
            CatalogEntry entry = catalog.get(id);
            if (entry != null && entry.enabled != enabled) {
                entry.enabled = enabled;
                changed++;
            }
        }
        return changed;
    }

    /** Batch recategorize (Admin Panel multi-select "move category"). */
    public int setEntriesCategory(java.util.Collection<String> ids, String category) {
        int changed = 0;
        for (String id : ids) {
            CatalogEntry entry = catalog.get(id);
            if (entry != null && !entry.category.equals(category)) {
                entry.category = category;
                changed++;
            }
        }
        return changed;
    }

    public void upsertPerk(PerkDefinition perk) {
        perks.put(perk.id, perk);
    }

    /** Seeding-safe insert: does nothing if a perk with this id already exists. Used for every
     *  shipped-default perk seed so an admin's customization to that perk (description, effects,
     *  even which column it's in) — or a same-id perk they authored entirely themselves — can
     *  never be silently overwritten by a later scan/heal pass. Genuinely new perks (id not
     *  present yet) still get added normally. */
    public void upsertPerkIfAbsent(PerkDefinition perk) {
        perks.putIfAbsent(perk.id, perk);
    }

    public void removePerk(String id) {
        perks.remove(id);
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    /** /mwloadout resetperks: wipe perks + perk columns and re-seed current defaults, keeping
     *  slots/catalog/player data — the upgrade path when a mod update reshapes the perk layout
     *  (old worlds keep their saved perks.json forever otherwise). */
    /** TRUE wipe-and-reset to shipped defaults — for the explicit admin command
     *  (/mwloadout admin resetperks) ONLY. An admin who runs that command wants a hard reset, so
     *  this deliberately clears first. Never call this from an automatic pass (server start,
     *  scan/reconcile) — see ensureDefaultPerksSeeded for the safe, additive-only equivalent used
     *  there; that distinction is exactly what was missing before (this method used to be the
     *  ONLY option, so the automatic heal pass had no choice but to call something destructive). */
    public void reseedPerks() {
        perks.clear();
        rules.perkSlots.clear();
        Defaults.seedPerksAndColumns(this);
    }

    /** Adds any of the shipped default perks/columns that are missing, WITHOUT touching anything
     *  that already exists — every insert goes through upsertPerkIfAbsent / addPerkSlotIfAbsent,
     *  so an admin's customization to a shipped-id perk (or a perk they authored entirely
     *  themselves) is never overwritten or deleted. This is what the automatic scan/reconcile
     *  pass calls (see CreativeTabScanner.healPerkTuning) — reseedPerks() above is the destructive
     *  version, reserved for the explicit admin command that means to hard-reset on purpose. */
    public void ensureDefaultPerksSeeded() {
        Defaults.seedPerksAndColumns(this);
    }

    // ---------------------------------------------------------------- persistence

    /** Points persistence at a new directory WITHOUT touching in-memory data — the migration
     *  primitive (load old location, rebind, save). load() by contrast clears and re-seeds. */
    public void rebindStorage(Path directory) {
        this.dataDir = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Cannot create data directory {}", directory, e);
        }
    }

    public void load(Path worldDataDir) {
        this.dataDir = worldDataDir;
        try {
            Files.createDirectories(worldDataDir);
        } catch (IOException e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Cannot create data directory {}", worldDataDir, e);
        }

        slots.clear();
        catalog.clear();
        perks.clear();
        rules = new Rules();

        JsonObject slotsJson = readFile("slots.json");
        JsonObject catalogJson = readFile("catalog.json");
        JsonObject perksJson = readFile("perks.json");
        JsonObject rulesJson = readFile("rules.json");

        if (slotsJson == null || rulesJson == null) {
            MWLoadoutMod.LOGGER.info("[MWLoadout] First run - seeding default MW19 configuration");
            Defaults.seed(this);
            save();
            // Merge any partially-present files over the defaults (e.g. only catalog.json existed).
        }
        if (slotsJson != null) {
            readList(slotsJson, "slots").forEach(json -> upsertSlot(SlotDefinition.fromJson(json)));
        }
        if (catalogJson != null) {
            readList(catalogJson, "entries").forEach(json -> upsertEntry(CatalogEntry.fromJson(json)));
        }
        if (perksJson != null) {
            readList(perksJson, "perks").forEach(json -> upsertPerk(PerkDefinition.fromJson(json)));
        }
        if (rulesJson != null) {
            rules = Rules.fromJson(rulesJson);
        }
        MWLoadoutMod.LOGGER.info("[MWLoadout] Registry loaded: {} slots, {} catalog entries, {} perks",
                slots.size(), catalog.size(), perks.size());
    }

    public void save() {
        if (dataDir == null) {
            return;
        }
        JsonObject slotsJson = new JsonObject();
        JsonArray slotArray = new JsonArray();
        slots.values().forEach(slot -> slotArray.add(slot.toJson()));
        slotsJson.add("slots", slotArray);
        writeFile("slots.json", slotsJson);

        JsonObject catalogJson = new JsonObject();
        JsonArray entryArray = new JsonArray();
        catalog.values().forEach(entry -> entryArray.add(entry.toJson()));
        catalogJson.add("entries", entryArray);
        writeFile("catalog.json", catalogJson);

        JsonObject perksJson = new JsonObject();
        JsonArray perkArray = new JsonArray();
        perks.values().forEach(perk -> perkArray.add(perk.toJson()));
        perksJson.add("perks", perkArray);
        writeFile("perks.json", perksJson);

        writeFile("rules.json", rules.toJson());
    }

    private JsonObject readFile(String name) {
        Path file = dataDir.resolve(name);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed reading {}", file, e);
            return null;
        }
    }

    private void writeFile(String name, JsonObject json) {
        try {
            Files.writeString(dataDir.resolve(name), Json.GSON.toJson(json));
        } catch (IOException e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed writing {}", name, e);
        }
    }

    private static List<JsonObject> readList(JsonObject json, String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement element = json.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    out.add(item.getAsJsonObject());
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- network snapshot

    /** The whole registry as one JSON blob — simple, versioned, and the client never needs to
     *  parse anything the server didn't already validate. */
    public String toSyncJson() {
        return toSyncJson(true);
    }

    /** @param isOp false strips every adminOnly ("exclusive test") entry out of the payload
     *  entirely — a non-OP client never receives them, so there's nothing for a modified client
     *  to reveal or select even by crafting a raw packet. */
    public String toSyncJson(boolean isOp) {
        JsonObject root = new JsonObject();
        JsonArray slotArray = new JsonArray();
        slots.values().forEach(slot -> slotArray.add(slot.toJson()));
        root.add("slots", slotArray);
        JsonArray entryArray = new JsonArray();
        catalog.values().stream()
                .filter(entry -> isOp || !entry.adminOnly)
                .forEach(entry -> entryArray.add(entry.toJson()));
        root.add("entries", entryArray);
        JsonArray perkArray = new JsonArray();
        perks.values().forEach(perk -> perkArray.add(perk.toJson()));
        root.add("perks", perkArray);
        root.add("rules", rules.toJson());
        return Json.GSON.toJson(root);
    }

    /** Client-side: replace local snapshot from a sync blob. */
    public void fromSyncJson(String data) {
        slots.clear();
        catalog.clear();
        perks.clear();
        JsonObject root = JsonParser.parseString(data).getAsJsonObject();
        readList(root, "slots").forEach(json -> upsertSlot(SlotDefinition.fromJson(json)));
        readList(root, "entries").forEach(json -> upsertEntry(CatalogEntry.fromJson(json)));
        readList(root, "perks").forEach(json -> upsertPerk(PerkDefinition.fromJson(json)));
        JsonElement rulesElement = root.get("rules");
        if (rulesElement != null && rulesElement.isJsonObject()) {
            rules = Rules.fromJson(rulesElement.getAsJsonObject());
        }
    }
}
