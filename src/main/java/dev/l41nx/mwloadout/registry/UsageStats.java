package dev.l41nx.mwloadout.registry;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate usage + combat counters across every player, for the admin Stats tab: which weapons
 * and perks actually get picked and how often (the "top tier" / pick-rate chart), plus real
 * combat performance per weapon (kills, headshot ratio, average time-to-kill, hits landed). Lives
 * in config/mwloadout/stats.json, same directory/load/save shape as {@link LoadoutRegistry}, and
 * syncs to the client the same way too (toSyncJson/fromSyncJson, singleton reused on both sides).
 *
 * Pick counts are taken at EQUIP time (LoadoutApplier.apply(), which covers both a manual equip
 * from the menu AND every respawn) rather than at menu-selection time: a weapon that spends more
 * actual playtime in players' hands is the more meaningful "what does the server actually fight
 * with" signal for balance than a one-off menu click. The trade-off is real and deliberate: a
 * player who dies often with a given loadout inflates that loadout's numbers a bit faster than
 * one who plays carefully. This is a pick-RATE metric, not a "how many distinct players tried
 * this at least once" metric.
 *
 * Combat stats are keyed by TACZ gameId (e.g. "tacz:g36k"), NOT catalog entry id: durable across a
 * catalog entry being renamed or deleted, and a Blueprint child gets its own row for free since it
 * has its own distinct gameId from its platform — exactly what "count blueprints separately" needs.
 * Sourced from TACZ's public event API (EntityKillByGunEvent for kills/headshots, standard Forge
 * LivingHurtEvent + IGun for hits/TTK). TACZ's public "common" event package doesn't expose a
 * shots-fired event, so true accuracy (hits/shots) isn't reliably trackable — "hits landed" is
 * tracked instead as the closest honest substitute, alongside headshot ratio for precision.
 */
public final class UsageStats {

    private static final UsageStats INSTANCE = new UsageStats();

    public static UsageStats get() {
        return INSTANCE;
    }

    private UsageStats() {
    }

    private Path dataDir;
    private final Map<String, Integer> weaponPicks = new java.util.concurrent.ConcurrentHashMap<>();     // catalog entry id -> count
    private final Map<String, Integer> perkPicks = new java.util.concurrent.ConcurrentHashMap<>();       // perk id -> count
    private final Map<String, Integer> categoryPicks = new java.util.concurrent.ConcurrentHashMap<>();   // weapon category -> count
    private final Map<UUID, Integer> playerPicks = new java.util.concurrent.ConcurrentHashMap<>();       // player -> total equip events
    private final Map<UUID, String> playerNames = new java.util.concurrent.ConcurrentHashMap<>();        // last-seen name, display only
    private volatile int totalPicks = 0;
    private volatile long trackingSince = 0;

    private final Map<String, Integer> weaponKills = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> weaponHeadshotKills = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> weaponHits = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> weaponTtkSumMillis = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> weaponTtkSamples = new java.util.concurrent.ConcurrentHashMap<>();
    // TTK engagement tracking: "attackerUUID:victimUUID" -> timestamp of the FIRST hit in the
    // current unresolved exchange. Cleared on kill (resolveEngagement) or swept if stale (a fight
    // that never ends in a kill — target escaped, healed, disconnected — would otherwise leak).
    private final Map<String, Long> engagementStart = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ENGAGEMENT_STALE_MILLIS = 5 * 60 * 1000L;
    private int eventsSinceSave = 0;

    // ============================================================= persistence (disk + network)

    public void load(Path worldDataDir) {
        this.dataDir = worldDataDir;
        try {
            Files.createDirectories(worldDataDir);
        } catch (IOException e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Cannot create data directory {}", worldDataDir, e);
        }
        clearAll();
        Path file = dataDir.resolve("stats.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            applyJson(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed reading stats.json", e);
        }
    }

    /** Same atomic-write discipline as ProfileManager: temp file then rename, so a crash mid-save
     *  can never leave a half-written stats.json that reads back as corrupt. */
    public void save() {
        if (dataDir == null) {
            return;
        }
        try {
            Path target = dataDir.resolve("stats.json");
            Path tmp = dataDir.resolve("stats.json.tmp");
            Files.writeString(tmp, Json.GSON.toJson(buildJson()));
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed writing stats.json", e);
        }
    }

    /** Server-side: full snapshot for the S2CSyncStats packet. */
    public String toSyncJson() {
        return Json.GSON.toJson(buildJson());
    }

    /** Client-side: replace the local read-only mirror from a sync blob (same singleton-reuse
     *  pattern LoadoutRegistry already uses for its own sync). */
    public void fromSyncJson(String data) {
        clearAll();
        try {
            applyJson(JsonParser.parseString(data).getAsJsonObject());
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed parsing synced stats", e);
        }
    }

    private void clearAll() {
        weaponPicks.clear();
        perkPicks.clear();
        categoryPicks.clear();
        playerPicks.clear();
        playerNames.clear();
        weaponKills.clear();
        weaponHeadshotKills.clear();
        weaponHits.clear();
        weaponTtkSumMillis.clear();
        weaponTtkSamples.clear();
        engagementStart.clear();
        totalPicks = 0;
        trackingSince = System.currentTimeMillis();
    }

    private JsonObject buildJson() {
        JsonObject json = new JsonObject();
        json.add("weapons", toJsonCounts(weaponPicks));
        json.add("perks", toJsonCounts(perkPicks));
        json.add("categories", toJsonCounts(categoryPicks));
        json.add("weaponKills", toJsonCounts(weaponKills));
        json.add("weaponHeadshotKills", toJsonCounts(weaponHeadshotKills));
        json.add("weaponHits", toJsonCounts(weaponHits));
        JsonObject ttk = new JsonObject();
        weaponTtkSumMillis.forEach((gameId, sum) -> {
            JsonObject pair = new JsonObject();
            pair.addProperty("sumMillis", sum);
            pair.addProperty("samples", weaponTtkSamples.getOrDefault(gameId, 0));
            ttk.add(gameId, pair);
        });
        json.add("weaponTtk", ttk);
        JsonObject players = new JsonObject();
        playerPicks.forEach((uuid, count) -> players.addProperty(uuid.toString(), count));
        json.add("players", players);
        JsonObject names = new JsonObject();
        playerNames.forEach((uuid, name) -> names.addProperty(uuid.toString(), name));
        json.add("playerNames", names);
        json.addProperty("totalPicks", totalPicks);
        json.addProperty("trackingSince", trackingSince);
        return json;
    }

    private void applyJson(JsonObject json) {
        readCounts(json, "weapons", weaponPicks);
        readCounts(json, "perks", perkPicks);
        readCounts(json, "categories", categoryPicks);
        readCounts(json, "weaponKills", weaponKills);
        readCounts(json, "weaponHeadshotKills", weaponHeadshotKills);
        readCounts(json, "weaponHits", weaponHits);
        if (json.has("weaponTtk")) {
            for (var jsonEntry : json.getAsJsonObject("weaponTtk").entrySet()) {
                var pair = jsonEntry.getValue().getAsJsonObject();
                weaponTtkSumMillis.put(jsonEntry.getKey(), pair.get("sumMillis").getAsLong());
                weaponTtkSamples.put(jsonEntry.getKey(), pair.get("samples").getAsInt());
            }
        }
        if (json.has("players")) {
            for (var jsonEntry : json.getAsJsonObject("players").entrySet()) {
                try {
                    playerPicks.put(UUID.fromString(jsonEntry.getKey()), jsonEntry.getValue().getAsInt());
                } catch (Exception ignored) {
                    // malformed uuid key from hand-editing - skip that one entry, keep the rest
                }
            }
        }
        if (json.has("playerNames")) {
            for (var jsonEntry : json.getAsJsonObject("playerNames").entrySet()) {
                try {
                    playerNames.put(UUID.fromString(jsonEntry.getKey()), jsonEntry.getValue().getAsString());
                } catch (Exception ignored) {
                    dev.l41nx.mwloadout.MWLog.swallowed("registry/UsageStats:200", ignored);
                }
            }
        }
        totalPicks = Json.intValue(json, "totalPicks", 0);
        trackingSince = (long) Json.doubleValue(json, "trackingSince", trackingSince);
    }

    private static void readCounts(JsonObject json, String key, Map<String, Integer> into) {
        if (!json.has(key)) {
            return;
        }
        for (var jsonEntry : json.getAsJsonObject(key).entrySet()) {
            into.put(jsonEntry.getKey(), jsonEntry.getValue().getAsInt());
        }
    }

    private static JsonObject toJsonCounts(Map<String, Integer> counts) {
        JsonObject json = new JsonObject();
        counts.forEach(json::addProperty);
        return json;
    }

    // ==================================================================== recording (server-side)

    /** Called once per LoadoutApplier.apply() — bumps every counter this equip touches. */
    public void recordPick(UUID playerId, String playerName, Iterable<String> weaponEntryIds,
                            Iterable<String> weaponCategories, Iterable<String> perkIds) {
        totalPicks++;
        playerPicks.merge(playerId, 1, Integer::sum);
        playerNames.put(playerId, playerName);
        for (String id : weaponEntryIds) {
            weaponPicks.merge(id, 1, Integer::sum);
        }
        for (String category : weaponCategories) {
            categoryPicks.merge(category, 1, Integer::sum);
        }
        for (String perkId : perkIds) {
            perkPicks.merge(perkId, 1, Integer::sum);
        }
    }

    /** A gun-damage hit landed on someone. Also arms TTK tracking if the victim is a player and
     *  this is the first hit of a fresh exchange (see engagementStart doc above). */
    public void recordHit(String gameId, UUID attacker, UUID victim) {
        weaponHits.merge(gameId, 1, Integer::sum);
        if (victim != null) {
            engagementStart.putIfAbsent(attacker + ":" + victim, System.currentTimeMillis());
            if (engagementStart.size() > 200) {
                sweepStaleEngagements();
            }
        }
    }

    private void sweepStaleEngagements() {
        long cutoff = System.currentTimeMillis() - ENGAGEMENT_STALE_MILLIS;
        engagementStart.values().removeIf(started -> started < cutoff);
    }

    /** A gun kill landed. ttkMillis is null when the victim wasn't a tracked player-vs-player
     *  exchange (no engagement start recorded) — those kills still count, just without a TTK
     *  sample, rather than being dropped or guessed at. */
    public void recordKill(String gameId, boolean headshot, Long ttkMillis) {
        weaponKills.merge(gameId, 1, Integer::sum);
        if (headshot) {
            weaponHeadshotKills.merge(gameId, 1, Integer::sum);
        }
        // Sanity cap: an "engagement" longer than 5 minutes is almost certainly two unrelated
        // fights that happen to share the same two players, not one continuous exchange — skip it
        // rather than let one outlier badly skew the average.
        if (ttkMillis != null && ttkMillis > 0 && ttkMillis < ENGAGEMENT_STALE_MILLIS) {
            weaponTtkSumMillis.merge(gameId, ttkMillis, Long::sum);
            weaponTtkSamples.merge(gameId, 1, Integer::sum);
        }
    }

    /** Resolves (and clears) the tracked engagement between this attacker/victim pair, returning
     *  the elapsed milliseconds since the first hit — or null if no hit was tracked for this pair
     *  (e.g. a one-shot kill with no prior LivingHurtEvent, or PvE). */
    public Long resolveEngagement(UUID attacker, UUID victim) {
        return engagementStart.remove(attacker + ":" + victim);
    }

    /** Flushes to disk every 20th recorded event instead of every single one — respawns and kills
     *  can be frequent in a firefight, and this is aggregate analytics, not anything that needs to
     *  survive a crash mid-fight. Still saved on server stop regardless. */
    public boolean shouldAutoSave() {
        eventsSinceSave++;
        if (eventsSinceSave >= 20) {
            eventsSinceSave = 0;
            return true;
        }
        return false;
    }

    // ========================================================================= queries (both sides)

    public int totalPicks() {
        return totalPicks;
    }

    public long trackingSince() {
        return trackingSince;
    }

    public int pickCount(String catalogId) {
        return weaponPicks.getOrDefault(catalogId, 0);
    }

    public int killsFor(String gameId) {
        return weaponKills.getOrDefault(gameId, 0);
    }

    public int hitsFor(String gameId) {
        return weaponHits.getOrDefault(gameId, 0);
    }

    /** -1 when there are no kills yet for this weapon (nothing to divide by). */
    public float headshotRatio(String gameId) {
        int kills = killsFor(gameId);
        if (kills == 0) {
            return -1f;
        }
        return weaponHeadshotKills.getOrDefault(gameId, 0) / (float) kills;
    }

    /** Average time-to-kill in seconds, or -1 when there's no PvP TTK sample yet for this weapon. */
    public float averageTtkSeconds(String gameId) {
        Integer samples = weaponTtkSamples.get(gameId);
        if (samples == null || samples == 0) {
            return -1f;
        }
        return weaponTtkSumMillis.getOrDefault(gameId, 0L) / 1000f / samples;
    }

    public List<Map.Entry<String, Integer>> topWeapons(int limit) {
        return top(weaponPicks, limit);
    }

    public List<Map.Entry<String, Integer>> topPerks(int limit) {
        return top(perkPicks, limit);
    }

    public List<Map.Entry<String, Integer>> topCategories(int limit) {
        return top(categoryPicks, limit);
    }

    public List<Map.Entry<UUID, Integer>> topPlayers(int limit) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(playerPicks.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public String playerName(UUID playerId) {
        return playerNames.getOrDefault(playerId, playerId.toString().substring(0, 8));
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /** Admin reset — wipes every counter but keeps tracking (stats.json overwritten on next
     *  save). Used by the Stats tab's Reset button. */
    public void resetAll() {
        clearAll();
        save();
    }
}
