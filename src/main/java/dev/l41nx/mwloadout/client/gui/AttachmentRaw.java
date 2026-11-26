package dev.l41nx.mwloadout.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/** The attachment's RAW data json (the file the pack author wrote), for the parts TACZ's AttachmentData does not expose to us:
 *  addon extensions such as {@code gunsmithlib_extension.potion_effects} (debuffs the round inflicts). Looked up once per
 *  attachment in the gun packs under {@code <game>/tacz} (zips + folders), then in the owning mod's jar; cached, never throws. */
public final class AttachmentRaw {

    public record PotionEffect(String effectId, int durationTicks, int level, double chance) {
        /** "SLOWNESS" - the registry's display name when the effect exists, otherwise the prettified id path. */
        public String displayName() {
            try {
                var effect = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation(effectId));
                if (effect != null) {
                    return effect.getDisplayName().getString().toUpperCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:36", ignored);
            }
            String path = effectId.contains(":") ? effectId.substring(effectId.indexOf(':') + 1) : effectId;
            return path.replace('_', ' ').toUpperCase(Locale.ROOT);
        }
    }

    private record Src(Path path, boolean zip) {
    }

    private static final JsonObject NONE = new JsonObject();
    private static final Map<String, JsonObject> CACHE = new ConcurrentHashMap<>();
    private static Map<String, Src> index;

    private AttachmentRaw() {
    }

    public static void clear() {
        CACHE.clear();
        synchronized (AttachmentRaw.class) {
            index = null;
        }
    }

    /** Potion effects the attachment's rounds apply to whatever they hit (empty when none / not found). */
    public static List<PotionEffect> potionEffects(String attachmentId) {
        List<PotionEffect> out = new ArrayList<>();
        try {
            JsonObject ext = get(attachmentId).has("gunsmithlib_extension") && get(attachmentId).get("gunsmithlib_extension").isJsonObject()
                    ? get(attachmentId).getAsJsonObject("gunsmithlib_extension") : null;
            if (ext == null || !ext.has("potion_effects") || !ext.get("potion_effects").isJsonArray()) {
                return out;
            }
            JsonArray array = ext.getAsJsonArray("potion_effects");
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject o = element.getAsJsonObject();
                if (!o.has("effect_id")) {
                    continue;
                }
                out.add(new PotionEffect(o.get("effect_id").getAsString(),
                        o.has("duration") ? o.get("duration").getAsInt() : 0,
                        o.has("level") ? o.get("level").getAsInt() : 1,
                        o.has("chance") ? o.get("chance").getAsDouble() : 1.0));
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:83", ignored);
        }
        return out;
    }

    public static JsonObject get(String attachmentId) {
        if (attachmentId == null || !attachmentId.contains(":")) {
            return NONE;
        }
        JsonObject cached = CACHE.get(attachmentId);
        if (cached != null) {
            return cached;
        }
        JsonObject found = NONE;
        try {
            String ns = attachmentId.substring(0, attachmentId.indexOf(':'));
            String name = attachmentId.substring(attachmentId.indexOf(':') + 1);
            List<String> candidates = new ArrayList<>();
            // the index file names the data file ("data": "ns:xxx_data"); try it first
            String indexText = read(ns, "data/" + ns + "/index/attachments/" + name + ".json");
            if (indexText != null) {
                JsonObject indexJson = JsonParser.parseString(indexText).getAsJsonObject();
                if (indexJson.has("data") && indexJson.get("data").isJsonPrimitive()) {
                    String ref = indexJson.get("data").getAsString();
                    String refNs = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : ns;
                    String refName = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
                    candidates.add("data/" + refNs + "/data/" + refName + ".json");
                    candidates.add("data/" + refNs + "/data/attachments/" + refName + ".json");
                }
            }
            candidates.add("data/" + ns + "/data/" + name + "_data.json");
            candidates.add("data/" + ns + "/data/" + name + ".json");
            candidates.add("data/" + ns + "/data/attachments/" + name + "_data.json");
            candidates.add("data/" + ns + "/data/attachments/" + name + ".json");
            for (String rel : candidates) {
                String text = read(ns, rel);
                if (text != null) {
                    JsonElement parsed = JsonParser.parseString(text);
                    if (parsed.isJsonObject()) {
                        found = parsed.getAsJsonObject();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:127", ignored);
        }
        CACHE.put(attachmentId, found);
        return found;
    }

    private static String read(String ns, String rel) {
        try {
            Src src = index().get(rel);
            if (src != null) {
                if (src.zip()) {
                    try (ZipFile zip = new ZipFile(src.path().toFile())) {
                        var entry = zip.getEntry(rel);
                        if (entry != null) {
                            try (InputStream in = zip.getInputStream(entry)) {
                                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            }
                        }
                    }
                } else {
                    return Files.readString(src.path(), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:150", ignored);
        }
        try { // addon packs that ship inside their own mod jar
            var info = net.minecraftforge.fml.ModList.get().getModFileById(ns);
            if (info != null) {
                Path path = info.getFile().findResource(rel.split("/"));
                if (path != null && Files.isRegularFile(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:160", ignored);
        }
        return null;
    }

    /** One scan of every pack under <game>/tacz: relative path -> where to read it from. */
    private static synchronized Map<String, Src> index() {
        if (index != null) {
            return index;
        }
        Map<String, Src> map = new HashMap<>();
        try {
            Path root = FMLPaths.GAMEDIR.get().resolve("tacz");
            if (Files.isDirectory(root)) {
                List<Path> children = new ArrayList<>();
                try (var stream = Files.list(root)) {
                    stream.forEach(children::add);
                }
                for (Path child : children) {
                    if (Files.isDirectory(child)) {
                        Path dataDir = child.resolve("data");
                        if (Files.isDirectory(dataDir)) {
                            try (var walk = Files.walk(dataDir)) {
                                walk.filter(p -> p.toString().endsWith(".json")).forEach(p ->
                                        map.putIfAbsent(child.relativize(p).toString().replace('\\', '/'), new Src(p, false)));
                            }
                        }
                    } else if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        try (ZipFile zip = new ZipFile(child.toFile())) {
                            zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith("data/") && e.getName().endsWith(".json"))
                                    .forEach(e -> map.putIfAbsent(e.getName(), new Src(child, true)));
                        } catch (Exception ignored) {
                            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:191", ignored);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentRaw:196", ignored);
        }
        index = map;
        return map;
    }
}
