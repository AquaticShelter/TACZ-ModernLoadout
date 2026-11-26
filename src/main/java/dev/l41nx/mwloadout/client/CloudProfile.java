package dev.l41nx.mwloadout.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.util.Json;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Local "cloud": a copy of the player's own classes and gun builds kept on THIS device (mwloadout/cloud/&lt;account uuid&gt;.json, outside config/).
 *
 *  - Whenever the integrated server sends a customised profile (the player saved something in single player), the copy is refreshed.
 *    Dedicated servers only refresh it when "cloudWriteFromServers" is on.
 *  - When the player joins a server (or a new world) where their profile is still untouched, the copy is offered to the server once; the server
 *    re-validates every class with its own rules and drops what it does not have, so a different catalog / unlock level never breaks anything.
 *
 *  Never leaves the machine except through that one packet, never carries XP, and can be switched off with the client option "cloudSync". */
public final class CloudProfile {

    private static boolean importOffered;
    private static String lastWritten = "";
    private static PlayerProfile lastProfile;

    private CloudProfile() {
    }

    public static void resetSession() {
        importOffered = false;
    }

    private static Path file() {
        var id = Minecraft.getInstance().getUser().getProfileId();
        return dev.l41nx.mwloadout.MWPaths.cloudDir().resolve(id + ".json");
    }

    /** Called every time the server sends the player's profile. */
    public static void onProfile(PlayerProfile profile) {
        lastProfile = profile; // what the server last told us: the source of "save to this device"
        try {
            if (!ClientCfg.CLOUD_SYNC.get()) {
                return;
            }
            if (profile.customized) {
                // The copy is fed from single player (full catalog, nothing missing). A dedicated server only shows the player what THAT server
                // allows, so by default it never overwrites the copy - otherwise one limited server would shrink your single-player build.
                if (Minecraft.getInstance().hasSingleplayerServer() || ClientCfg.CLOUD_WRITE_FROM_SERVERS.get()) {
                    write(profile);
                }
            } else if (!importOffered) {
                importOffered = true;
                offerImport();
            }
        } catch (Throwable ignored) {
            // a cloud problem must never get in the way of the menu itself
        }
    }

    /** True when this device has a cloud copy that could be sent (the CLOUD button only shows then). */
    public static boolean hasLocalCopy() {
        try {
            return ClientCfg.CLOUD_SYNC.get() && Files.isRegularFile(file());
        } catch (Throwable t) {
            return false;
        }
    }

    /** The CLOUD button: send the copy again on purpose - replaces the classes on this server, which is why the screen asks twice.
     *  @return false when there is nothing usable to send (the caller shows that to the player) */
    public static boolean manualImport() {
        try {
            Path file = file();
            if (!Files.isRegularFile(file)) {
                return false;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            if (!json.has("presets") || json.getAsJsonArray("presets").isEmpty()) {
                return false;
            }
            Net.C2SImportCloud.sendAll(text, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void offerImport() throws Exception {
        if (!LoadoutRegistry.get().rules().allowCloudImport) {
            return;
        }
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        if (!json.has("presets") || json.getAsJsonArray("presets").isEmpty()) {
            return;
        }
        Net.C2SImportCloud.sendAll(text, false); // chunked: one packet is capped at 32767 bytes
    }

    /** True while connected to a remote (dedicated / LAN-host) server with a profile in hand: "save to this device" makes sense there. In
     *  single player the copy is kept up to date automatically. */
    public static boolean canSaveFromServer() {
        try {
            return lastProfile != null && ClientCfg.CLOUD_SYNC.get() && !Minecraft.getInstance().hasSingleplayerServer();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The reverse of the import: writes the classes and gun builds the server holds for you into this device's copy, so single player (and
     *  any other server) can use them. The previous copy is backed up first (last 5 kept). @return classes saved, or -1 when it failed. */
    public static int saveFromServer() {
        PlayerProfile profile = lastProfile;
        if (profile == null) {
            return -1;
        }
        try {
            Path file = file();
            if (Files.isRegularFile(file)) {
                Path backups = file.getParent().resolve("backups");
                Files.createDirectories(backups);
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                Files.copy(file, backups.resolve(file.getFileName().toString().replace(".json", "") + "-" + stamp + ".json"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try (var list = Files.list(backups)) {
                    var old = list.filter(p -> p.getFileName().toString().startsWith(file.getFileName().toString().replace(".json", "")))
                            .sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).skip(5).toList();
                    for (Path p : old) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            write(profile, true);
            return profile.presets.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void write(PlayerProfile profile) throws Exception {
        write(profile, false);
    }

    private static void write(PlayerProfile profile, boolean force) throws Exception {
        JsonObject json = new JsonObject();
        JsonArray presets = new JsonArray();
        for (LoadoutPreset preset : profile.presets) {
            presets.add(preset.toJson());
        }
        json.add("presets", presets);
        json.addProperty("activeIndex", profile.activeIndex);
        JsonObject builds = new JsonObject();
        for (Map.Entry<String, PlayerProfile.GunBuilds> entry : profile.gunBuilds.entrySet()) {
            builds.add(entry.getKey(), entry.getValue().toJson());
        }
        json.add("gunBuilds", builds);
        json.addProperty("killCard", profile.killCard);
        json.addProperty("killEmblem", profile.killEmblem);
        String content = Json.GSON.toJson(json);
        if (!force && content.equals(lastWritten)) {
            return; // nothing changed since the last write
        }
        lastWritten = content;
        json.addProperty("savedAt", System.currentTimeMillis());
        Path file = file();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
