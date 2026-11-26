package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Optional compat with the Limb Damage mod (modid limbdamage), by reflection only - no compile dependency, so the loadout mod runs the
 *  same with or without it.
 *
 *  Detects the mod, copies its LIVE config into Rules (global zone multipliers, the per-weapon-category table, headshotOverride, gun-type
 *  overrides) and re-sends the registry to every client whenever any of it changes - so the Advanced stats of every player show what THIS
 *  server's Limb Damage actually does. Detection / read status is part of the synced rules, so the UI can say "detected", "config unreadable"
 *  or "not detected". Re-checked every few seconds, so editing limbdamage-common.toml while the server runs is picked up. */
public final class LimbCompat {

    private static final String PKG = "dev.szo9k4.limbdamage.";
    private static final String[] PARTS = {"HEAD", "BODY", "ABDOMEN", "ARMS", "LEGS", "FEET"};
    private static boolean warned;

    private LimbCompat() {
    }

    public static boolean loaded() {
        return ModList.get() != null && ModList.get().isLoaded("limbdamage");
    }

    /** Server tick hook: cheap when the mod is absent, otherwise re-reads its config and pushes a changed result to everyone. */
    public static void poll(MinecraftServer server) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        if (syncInto(registry.rules())) {
            registry.save();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Net.syncRegistryTo(player);
            }
            MWLoadoutMod.LOGGER.info("[MWLoadout] Limb Damage config changed - synced to {} player(s)", server.getPlayerList().getPlayerCount());
        }
    }

    private static String snapshot(Rules r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.limbDetected).append(r.limbSynced).append(r.limbHeadMul).append(r.limbBodyMul).append(r.limbAbdomenMul)
                .append(r.limbArmsMul).append(r.limbLegsMul).append(r.limbFeetMul).append(r.limbCategoriesEnabled)
                .append(r.limbHeadshotOverride).append(r.limbGunTypeOverrides);
        new java.util.ArrayList<>(r.limbCategories.entrySet()).forEach(e -> sb.append(e.getKey()).append(java.util.Arrays.toString(e.getValue())));
        return sb.toString();
    }

    /** @return true when anything the clients need changed, so the caller persists + re-syncs. */
    public static boolean syncInto(Rules rules) {
        String before = snapshot(rules);
        boolean detected = loaded();
        rules.limbDetected = detected;
        if (!detected) {
            rules.limbSynced = false;
        } else if (rules.limbAutoSync) {
            try {
                read(rules);
                rules.limbSynced = true;
            } catch (Throwable t) {
                rules.limbSynced = false; // installed, but its config could not be read (not loaded yet / a field was renamed)
                if (!warned) {
                    warned = true;
                    MWLoadoutMod.LOGGER.warn("[MWLoadout] Limb Damage is installed but its config could not be read - using the last known values", t);
                }
            }
        }
        return !before.equals(snapshot(rules));
    }

    private static void read(Rules rules) throws Exception {
        Class<?> config = Class.forName(PKG + "config.LimbConfig");
        rules.limbHeadMul = number(config, "HEAD_MULTIPLIER");
        rules.limbBodyMul = number(config, "BODY_MULTIPLIER");
        rules.limbAbdomenMul = number(config, "ABDOMEN_MULTIPLIER");
        rules.limbArmsMul = number(config, "ARMS_MULTIPLIER");
        rules.limbLegsMul = number(config, "LEGS_MULTIPLIER");
        rules.limbFeetMul = number(config, "FEET_MULTIPLIER");

        // per-weapon-category table (absent in older builds -> categories simply stay off)
        try {
            Class<?> categories = Class.forName(PKG + "config.CategoryConfig");
            rules.limbCategoriesEnabled = Boolean.TRUE.equals(value(categories, "ENABLED"));
            rules.limbHeadshotOverride = Boolean.TRUE.equals(value(categories, "HEADSHOT_OVERRIDE"));
            Object overrides = value(categories, "OVERRIDES");
            rules.limbGunTypeOverrides = new java.util.ArrayList<>();
            if (overrides instanceof List<?> list) {
                for (Object entry : list) {
                    rules.limbGunTypeOverrides.add(String.valueOf(entry));
                }
            }
            Class<?> bodyPart = Class.forName(PKG + "BodyPart");
            Method get = categories.getMethod("get", String.class, bodyPart);
            Object[] constants = bodyPart.getEnumConstants();
            // Build in a local map and publish it with ONE assignment: in single player the client renders from this same Rules object,
            // and filling rules.limbCategories in place let the render thread iterate it mid-put (ConcurrentModificationException crash).
            java.util.LinkedHashMap<String, double[]> built = new java.util.LinkedHashMap<>();
            Object names = categories.getField("CATEGORIES").get(null);
            if (names instanceof List<?> categoryList) {
                for (Object name : categoryList) {
                    double[] values = new double[PARTS.length];
                    for (int i = 0; i < PARTS.length; i++) {
                        values[i] = -1.0;
                        for (Object constant : constants) {
                            if (constant.toString().equals(PARTS[i])) {
                                values[i] = ((Number) get.invoke(null, String.valueOf(name), constant)).doubleValue();
                            }
                        }
                    }
                    built.put(String.valueOf(name), values);
                }
            }
            rules.limbCategories = built;
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException ignored) {
            rules.limbCategoriesEnabled = false;
            rules.limbCategories = new java.util.LinkedHashMap<>();
        }
    }

    private static Object value(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getField(fieldName);
        Object holder = field.get(null);
        return holder.getClass().getMethod("get").invoke(holder);
    }

    private static double number(Class<?> owner, String fieldName) throws Exception {
        return ((Number) value(owner, fieldName)).doubleValue();
    }
}
