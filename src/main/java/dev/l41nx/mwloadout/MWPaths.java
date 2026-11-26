package dev.l41nx.mwloadout;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Where MW Loadout keeps its data. Everything lives in ONE folder OUTSIDE config/ - the game folder of the client, the server folder of a
 * dedicated server (override with -Dmwloadout.dir=/some/path, e.g. for hosts that mount data separately):
 *
 * <pre>
 * mwloadout/
 *   data/      slots, catalog, perks, rules, usage stats   (server side: the whole game setup, copy it to move the setup)
 *   players/   one file per player: classes, gun builds    (server side, also the singleplayer world's profile)
 *   cloud/     this device's copy of the player's classes   (client side: &lt;account uuid&gt;.json + backups/)
 *   client/    client-only data (advanced-stats target settings)
 * </pre>
 *
 * config/ is for settings (the toml files); this is data that players and admins back up, copy between machines and sync. The first start
 * after an update COPIES whatever the old config/mwloadout/ folder held into the new layout and leaves the old files untouched as a backup.
 * Pure java + FMLPaths: safe to touch from both distributions.
 */
public final class MWPaths {
    private static boolean migrated;

    private MWPaths() {
    }

    public static Path root() {
        String override = System.getProperty("mwloadout.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        return FMLPaths.GAMEDIR.get().resolve("mwloadout");
    }

    public static Path dataDir() {
        return root().resolve("data");
    }

    public static Path playersDir() {
        return root().resolve("players");
    }

    public static Path cloudDir() {
        return root().resolve("cloud");
    }

    public static Path clientDir() {
        return root().resolve("client");
    }

    private static Path legacyDir() {
        return FMLPaths.CONFIGDIR.get().resolve("mwloadout");
    }

    /** Idempotent. Copies (never moves, never overwrites) the pre-update layout into the new folders. Call once, as early as possible. */
    public static synchronized void migrate() {
        if (migrated) {
            return;
        }
        migrated = true;
        try {
            Files.createDirectories(root());
            Path readme = root().resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, "MW Loadout data folder.\n"
                        + "data/     slots, catalog, perks, rules, stats (the whole game setup)\n"
                        + "players/  one json per player (classes and gun builds)\n"
                        + "cloud/    this device's copy of your own classes (client)\n"
                        + "client/   client-only data\n"
                        + "Back this folder up before editing it by hand. Settings (toml) stay in config/.\n");
            }
            Path legacy = legacyDir();
            int copied = 0;
            if (Files.isDirectory(legacy)) {
                copied += copyFiles(legacy, dataDir());
                copied += copyFiles(legacy.resolve("players"), playersDir());
                copied += copyFiles(legacy.resolve("cloud"), cloudDir());
            }
            Path adv = FMLPaths.CONFIGDIR.get().resolve("mwloadout-advstats.json");
            Path advNew = clientDir().resolve("advstats.json");
            if (Files.isRegularFile(adv) && !Files.exists(advNew)) {
                Files.createDirectories(advNew.getParent());
                Files.copy(adv, advNew);
                copied++;
            }
            if (copied > 0) {
                MWLoadoutMod.LOGGER.info("[MWLoadout] Copied {} data file(s) from config/mwloadout to {} (the old files were left in place as a backup)", copied, root());
                try {
                    Files.createDirectories(legacy);
                    Files.writeString(legacy.resolve("MOVED.txt"), "MW Loadout data now lives in " + root() + "\n"
                            + "These files are an old copy and are no longer read; delete them once you have checked the new folder.\n");
                } catch (IOException ignored) {
                    // the note is a courtesy
                }
            }
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Data folder migration failed - continuing with whatever is in {}", root(), e);
        }
    }

    /** Copies the regular files directly inside src to dst when dst does not have them yet. Returns how many were copied. */
    private static int copyFiles(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) {
            return 0;
        }
        int copied = 0;
        try (Stream<Path> files = Files.list(src)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || name.endsWith(".tmp") || name.equals("MOVED.txt")) {
                    continue;
                }
                Path target = dst.resolve(name);
                if (Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(dst);
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
        }
        return copied;
    }
}
