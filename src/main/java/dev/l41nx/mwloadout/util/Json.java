package dev.l41nx.mwloadout.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Tiny helpers so registry/data classes read as schema, not as Gson boilerplate. */
public final class Json {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Same serializer, no pretty-printing — for building a value to show in a single-line admin
     *  EditBox. GSON's indented output has real newlines in it, which a single-line EditBox can't
     *  render as line breaks — it shows the raw control character as a glyph instead (looks like
     *  literal "LF" text stamped into the field). Never use this for anything actually written to
     *  disk; the pretty-printed GSON above stays the on-disk format. */
    public static final Gson GSON_COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static String str(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    public static int intValue(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    public static double doubleValue(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsDouble() : fallback;
    }

    public static long longValue(JsonObject json, String key, long fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
    }

    public static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    public static List<String> strList(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        JsonElement element = json.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    public static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
