package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player profile store: <game or server folder>/mwloadout/players/<uuid>.json (MWPaths), one file per player
 * (CodMatchmaking's PlayerLoadoutDataIO pattern). Loaded lazily on first access instead of
 * all-at-once at server start — a long-lived server accumulates thousands of profile files and
 * only a handful of those players are ever online at once. Dropped from memory on logout.
 *
 * Saves are atomic (write to a .tmp file, then rename over the real one) so a crash or power loss
 * mid-write can never leave a half-written file that reads back as "corrupt" and wipes the
 * player's data — the previous version wrote straight to the target file, which had exactly that
 * failure mode. A save is also skipped entirely when the serialized profile is byte-identical to
 * what was last written this session, so a burst of mutations with no net change doesn't hit disk
 * repeatedly. If a load DOES find a genuinely corrupt file (pre-existing damage, hand-editing,
 * etc.), it's backed up alongside the original (.json.corrupt-<timestamp>) before falling back to
 * a fresh profile, instead of the corruption evidence being silently discarded.
 */
public final class ProfileManager {

    private static final ProfileManager INSTANCE = new ProfileManager();

    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private MinecraftServer server;

    private ProfileManager() {
    }

    public static ProfileManager get() {
        return INSTANCE;
    }

    public void attach(MinecraftServer server) {
        this.server = server;
        profiles.clear();
        lastWrittenJson.clear();
    }

    /** Collapse repeated numeric suffixes left by the old auto-save dedupe bug ("Sigma 2 2 2"
     *  -> "Sigma"), then re-dedupe cleanly so any genuine duplicates get a single tidy number.
     *  Runs once per load; a healthy profile is unchanged. */
    private static void repairNames(PlayerProfile profile) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LoadoutPreset preset : profile.presets) {
            String name = preset.name == null ? "" : preset.name.trim();
            // Collapse a run of trailing " N N N" down to the base token.
            String collapsed = name.replaceAll("(\\s+\\d+)+$", "").trim();
            if (collapsed.isEmpty()) {
                collapsed = name;
            }
            String candidate = collapsed;
            int suffix = 2;
            while (!seen.add(candidate.toLowerCase(java.util.Locale.ROOT))) {
                candidate = collapsed + " " + suffix++;
            }
            preset.name = candidate;
        }
    }

    public PlayerProfile profile(UUID playerId) {
        return profiles.computeIfAbsent(playerId, this::loadOrCreate);
    }

    // Last JSON actually written per player this session — lets save() skip a genuinely
    // redundant write (nothing changed since last time) instead of hitting disk every call.
    private final Map<UUID, String> lastWrittenJson = new ConcurrentHashMap<>();

    public void save(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null || server == null) {
            return;
        }
        String json = Json.GSON.toJson(profile.toJson());
        if (json.equals(lastWrittenJson.get(playerId))) {
            return; // identical to what's already on disk - skip the write entirely
        }
        try {
            Path dir = playersDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(playerId + ".json");
            // Atomic write: temp file first, then rename over the target. A crash/power-loss
            // mid-write leaves the temp file damaged and the REAL file untouched — never a
            // half-written profile that reads as "corrupt" and gets wiped on next load. Falls
            // back to a plain (still-safer-than-before) write if the filesystem doesn't support
            // atomic move across these two paths (some network mounts don't).
            Path tmp = dir.resolve(playerId + ".json.tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            lastWrittenJson.put(playerId, json);
        } catch (IOException e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed saving profile {}", playerId, e);
        }
    }

    public void unload(UUID playerId) {
        save(playerId);
        profiles.remove(playerId);
        lastWrittenJson.remove(playerId);
    }

    private PlayerProfile loadOrCreate(UUID playerId) {
        if (server != null) {
            Path file = playersDir().resolve(playerId + ".json");
            if (Files.exists(file)) {
                try {
                    PlayerProfile loaded = PlayerProfile.fromJson(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
                    // The starter deck is seeded ONCE (fresh profile -> populate()). It is never topped up again: padding every load
                    // to ten re-created the classes a player had deleted and bloated imported / cloud profiles back to ten.
                    // Only a file with no classes at all (empty / hand-trimmed) gets a deck, so equip never has nothing to use.
                    if (loaded.presets.isEmpty()) {
                        DefaultLoadouts.populate(loaded, playerId);
                    }
                    repairNames(loaded); // one-time cleanup of runaway "Sigma 2 2 2" suffixes
                    return loaded;
                } catch (Exception e) {
                    MWLoadoutMod.LOGGER.error("[MWLoadout] Corrupt profile {} - backing up and starting fresh",
                            playerId, e);
                    try {
                        Path backup = file.resolveSibling(playerId + ".json.corrupt-" + System.currentTimeMillis());
                        Files.move(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        MWLoadoutMod.LOGGER.error("[MWLoadout] Backed up corrupt profile to {}", backup);
                    } catch (IOException backupFailed) {
                        MWLoadoutMod.LOGGER.error("[MWLoadout] Could not even back up the corrupt profile {}",
                                playerId, backupFailed);
                    }
                }
            }
        }
        PlayerProfile fresh = new PlayerProfile(playerId);
        // Never deploy empty-handed: a brand-new profile ships with ten pre-built classes.
        // No save() here — we're inside computeIfAbsent (the map entry doesn't exist yet);
        // the deck persists on the player's first save/equip, and reseeds identically (UUID
        // -seeded) until then, so nothing is ever lost either way.
        DefaultLoadouts.populate(fresh, playerId);
        return fresh;
    }

    /** mwloadout/players/ (see MWPaths) - outside config/ and outside world data, so player classes survive world resets and
     *  map swaps (saveandloadinventories-style persistence). */
    private Path playersDir() {
        return dev.l41nx.mwloadout.MWPaths.playersDir();
    }
}
