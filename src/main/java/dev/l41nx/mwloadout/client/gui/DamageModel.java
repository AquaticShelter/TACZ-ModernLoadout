package dev.l41nx.mwloadout.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The damage a TACZ bullet really does on THIS server, for the Advanced stats box and the stat tooltips:
 *
 *    TACZ damage at distance (the figure TACZ itself shows)  ->  x TACZ headshot multiplier (head only)
 *      ->  x Limb Damage zone multiplier (synced from the server's Limb Damage config)  ->  vanilla armor (only the part the bullet does
 *      not ignore: armor_ignore)  ->  x (1 - server-wide damage reduction).
 *
 *  The player's own what-if numbers (reduction %, armor points, toughness, target HP) are kept here and persisted. */
public final class DamageModel {

    public enum Zone {
        HEAD("Head", 0xFFFF5A4F), CHEST("Chest", 0xFFFFB23F), ABDOMEN("Abdomen", 0xFFE8D44D), ARMS("Arms", 0xFF4FC3F7), LEGS("Legs", 0xFF9B87FF), FEET("Feet", 0xFFFF8FB8);

        public final String label;
        public final int color;

        Zone(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** -1 = follow the server's configured reduction (Rules.damageReductionPercent). */
    public static double reductionPercent = -1;
    public static double armorPoints = 0;
    public static double toughness = 0;
    public static double targetHp = 20;
    /** Fragile target: Limb Damage then treats ARMS and LEGS as x1.0. */
    public static boolean fragile = false;
    public static int tab = 0;
    /** Series hidden in the chart (bit per Zone ordinal, bit 5 = the raw TACZ line). */
    public static int hiddenMask = 0;

    private static boolean loaded;

    private DamageModel() {
    }

    private static Path file() {
        return dev.l41nx.mwloadout.MWPaths.clientDir().resolve("advstats.json");
    }

    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                reductionPercent = json.has("reductionPercent") ? json.get("reductionPercent").getAsDouble() : -1;
                armorPoints = json.has("armorPoints") ? json.get("armorPoints").getAsDouble() : 0;
                toughness = json.has("toughness") ? json.get("toughness").getAsDouble() : 0;
                targetHp = json.has("targetHp") ? json.get("targetHp").getAsDouble() : 20;
                fragile = json.has("fragile") && json.get("fragile").getAsBoolean();
                tab = json.has("tab") ? json.get("tab").getAsInt() : 0;
                hiddenMask = json.has("hiddenMask") ? json.get("hiddenMask").getAsInt() : 0;
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/DamageModel:71", ignored);
        }
    }

    public static void save() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("reductionPercent", reductionPercent);
            json.addProperty("armorPoints", armorPoints);
            json.addProperty("toughness", toughness);
            json.addProperty("targetHp", targetHp);
            json.addProperty("fragile", fragile);
            json.addProperty("tab", tab);
            json.addProperty("hiddenMask", hiddenMask);
            Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/DamageModel:86", ignored);
        }
    }

    private static Rules rules() {
        return LoadoutRegistry.get().rules();
    }

    /** The server-wide cut in percent that is in effect: the player's slider, or the server's own number until they touch it. */
    public static double reduction() {
        return reductionPercent >= 0 ? reductionPercent : rules().damageReductionPercent;
    }

    public static double serverReduction() {
        return rules().damageReductionPercent;
    }

    /** Which Limb Damage weapon category a gun falls in: the server's gunTypeOverrides ("tacz:ak47=rifle") first, else TACZ's own type. */
    public static String category(String gunId, String taczType) {
        try {
            for (String entry : rules().limbGunTypeOverrides) {
                int eq = entry.indexOf('=');
                if (eq > 0 && entry.substring(0, eq).trim().equalsIgnoreCase(gunId)) {
                    return entry.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/DamageModel:112", ignored);
        }
        return taczType == null || taczType.isEmpty() ? null : taczType;
    }

    private static int partIndex(Zone zone) {
        return switch (zone) {
            case HEAD -> 0;
            case CHEST -> 1;
            case ABDOMEN -> 2;
            case ARMS -> 3;
            case LEGS -> 4;
            case FEET -> 5;
        };
    }

    /** Limb Damage multiplier of a zone for a weapon category, exactly as the server's config resolves it: the category's own value when
     *  per-category multipliers are on and it is set (>= 0), else the global one. */
    public static double limb(Zone zone, String category) {
        if (!limbActive()) {
            return 1.0; // no Limb Damage on the server: it applies no zone multiplier at all (only TACZ's own headshot multiplier exists)
        }
        Rules r = rules();
        double value = switch (zone) {
            case HEAD -> r.limbHeadMul;
            case CHEST -> r.limbBodyMul;
            case ABDOMEN -> r.limbAbdomenMul;
            case ARMS -> r.limbArmsMul;
            case LEGS -> r.limbLegsMul;
            case FEET -> r.limbFeetMul;
        };
        if (r.limbCategoriesEnabled && category != null) {
            double[] own = r.limbCategories.get(category);
            if (own != null && own[partIndex(zone)] >= 0) {
                value = own[partIndex(zone)];
            }
        }
        if (fragile && (zone == Zone.ARMS || zone == Zone.LEGS || zone == Zone.FEET)) {
            return 1.0;
        }
        return value;
    }

    /** True when the server runs the Limb Damage mod (modid limbdamage). Everything about per-part multipliers is shown only then. */
    public static boolean limbActive() {
        return rules().limbDetected;
    }

    private static final Zone[] BASIC_ZONES = {Zone.HEAD, Zone.CHEST};

    /** The zones the UI lists: all six with Limb Damage, otherwise just Head (TACZ's headshot multiplier) and Chest (the plain bullet). */
    public static Zone[] activeZones() {
        return limbActive() ? Zone.values() : BASIC_ZONES;
    }

    public static boolean zoneShown(Zone zone) {
        return limbActive() || zone == Zone.HEAD || zone == Zone.CHEST;
    }

    /** Total multiplier a zone applies to the TACZ number - what the table shows as MULT. TACZ's headshot multiplier counts for the head
     *  unless the server's headshotOverride cancels it (then only the limb head multiplier applies). */
    public static double zoneMultiplier(TaczStats.AdvancedData d, Zone zone) {
        double tacz = zone == Zone.HEAD && !rules().limbHeadshotOverride ? d.headMul() : 1.0;
        return tacz * limb(zone, d.category());
    }

    public static boolean headshotOverride() {
        return rules().limbHeadshotOverride;
    }

    /** Everything the server's Limb Damage config contributes, as one string: the Advanced stats box compares it frame to frame and flashes
     *  "config changed" the moment a resync brings a different value (multipliers, category table, headshotOverride, gun-type overrides). */
    public static String limbSignature() {
        Rules r = rules();
        StringBuilder sb = new StringBuilder();
        sb.append(r.limbDetected).append(r.limbSynced).append(r.limbHeadMul).append(r.limbBodyMul).append(r.limbAbdomenMul)
                .append(r.limbArmsMul).append(r.limbLegsMul).append(r.limbFeetMul).append(r.limbCategoriesEnabled)
                .append(r.limbHeadshotOverride).append(r.limbGunTypeOverrides).append(r.damageReductionPercent);
        // The map is replaced / refilled from the network thread when the server resyncs: iterate a private copy and never let a race
        // reach the render thread (this was the AttachmentEditorScreen crash: ConcurrentModificationException in LinkedHashMap.forEach).
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                for (java.util.Map.Entry<String, double[]> e : new java.util.ArrayList<>(r.limbCategories.entrySet())) {
                    sb.append(e.getKey()).append(java.util.Arrays.toString(e.getValue()));
                }
                break;
            } catch (java.util.ConcurrentModificationException race) {
                sb.setLength(0); // retry from scratch with a fresh copy
                sb.append(r.limbDetected).append(r.limbSynced);
            }
        }
        return sb.toString();
    }

    /** One-line status of the Limb Damage link, for the Advanced stats header. {text, colour}. */
    public static Object[] limbStatus() {
        Rules r = rules();
        if (!r.limbDetected) {
            return new Object[]{"Limb Damage isn't installed on this server \u2014 only Head (TACZ headshot) and Body are listed.", 0xFF626872};
        }
        if (!r.limbSynced) {
            return new Object[]{"Limb Damage is installed but its config couldn't be read \u2014 using the last known values.", 0xFFFFB23F};
        }
        boolean clientHas = net.minecraftforge.fml.ModList.get() != null && net.minecraftforge.fml.ModList.get().isLoaded("limbdamage");
        return new Object[]{"Zone multipliers come from the server's Limb Damage config" + (clientHas ? "." : " (the mod isn't installed on this client)."), 0xFF626872};
    }

    /** vanilla CombatRules.getDamageAfterAbsorb. */
    public static double absorb(double damage, double armor, double toughness) {
        double f = 2.0 + toughness / 4.0;
        double g = Math.max(armor * 0.2, Math.min(20.0, armor - damage / f));
        return damage * (1.0 - g / 25.0);
    }

    /** What ONE pellet/bullet does to the target after everything. {@code tacz} = the TACZ figure at that distance (per pellet). */
    public static double perHit(TaczStats.AdvancedData d, double tacz, Zone zone) {
        double raw = tacz * zoneMultiplier(d, zone);
        double ignore = Math.max(0.0, Math.min(1.0, d.armorIgnore()));
        double armored = raw * (1.0 - ignore);
        if (armorPoints > 0) {
            armored = absorb(armored, armorPoints, toughness);
        }
        return (armored + raw * ignore) * (1.0 - Math.max(0.0, Math.min(99.0, reduction())) / 100.0);
    }

    /** Player-sized target the shotgun model aims at: hitbox width x height in metres, aimed at its centre. */
    public static final double TARGET_W = 0.6, TARGET_H = 1.8;

    public static boolean isShotgun(TaczStats.AdvancedData d) {
        return d.pellets() > 1;
    }

    /** Height of the aim point above the target's feet (chest), metres. */
    public static final double AIM_HEIGHT = 1.2;

    /** CDF of a triangular offset on [-s, s] - what vanilla-style projectile spread rolls on each axis (random.triangle(0, dev)). */
    private static double triCdf(double t, double s) {
        if (t <= -s) {
            return 0.0;
        }
        if (t >= s) {
            return 1.0;
        }
        double u = t / s;
        return u < 0 ? 0.5 * (1 + u) * (1 + u) : 1.0 - 0.5 * (1 - u) * (1 - u);
    }

    /** Chance that ONE pellet lands on a standing player aimed at the chest: the spread rolls an independent triangular offset sideways and up/down
     *  (like vanilla projectiles), the hitbox is 0.6 wide x 1.8 tall. An estimate - TACZ's own RNG is not replayed. */
    public static double hitFraction(double distance, double degrees) {
        double s = radius(distance, degrees);
        if (s <= 1e-6) {
            return 1.0;
        }
        double sideways = triCdf(TARGET_W / 2.0, s) - triCdf(-TARGET_W / 2.0, s);
        double vertical = triCdf(TARGET_H - AIM_HEIGHT, s) - triCdf(-AIM_HEIGHT, s);
        return Math.max(0.0, Math.min(1.0, sideways * vertical));
    }

    /** Pellets that land on the target on average, per trigger pull. */
    public static double expectedPellets(TaczStats.AdvancedData d, double distance, double degrees) {
        return d.pellets() * hitFraction(distance, degrees);
    }

    /** One trigger pull as it is shown everywhere: a single bullet -> its hit; a shotgun -> the damage of the pellets that actually connect at
     *  that distance (hip spread) - the per-pellet figure x expected pellets on target. {@code allHit} = the every-pellet-lands ceiling. */
    public static double shotValue(TaczStats.AdvancedData d, double distance, Zone zone, boolean allHit) {
        double perPellet = perHit(d, taczAt(d, distance), zone);
        if (!isShotgun(d)) {
            return perPellet;
        }
        return perPellet * (allHit ? d.pellets() : expectedPellets(d, distance, d.stanceSpread()[0]));
    }

    /** What one trigger pull is worth on average to a chest-aimed player-sized target at a distance. */
    public static double expectedPerShot(TaczStats.AdvancedData d, double distance, double degrees) {
        return perHit(d, taczAt(d, distance), Zone.CHEST) * expectedPellets(d, distance, degrees);
    }

    /** Per trigger pull (all pellets). */
    public static double perShot(TaczStats.AdvancedData d, double tacz, Zone zone) {
        return perHit(d, tacz, zone) * d.pellets();
    }

    public static double shotsToKill(double perShot) {
        return perShot <= 0 ? 99 : Math.max(1, Math.ceil(targetHp / perShot - 1e-6));
    }

    public static double ttkMs(TaczStats.AdvancedData d, double perShot) {
        return d.rpm() <= 0 ? 0 : (shotsToKill(perShot) - 1) * 60000.0 / d.rpm();
    }

    /** Damage of ONE bullet entity at a distance. The falloff steps in a gun's data are what a whole trigger pull is worth when every bullet
     *  lands (TACZ splits that figure over bullet_amount), so a shotgun pellet is step / bullet_amount; a single-bullet gun is unchanged.
     *  Armor, headshots and the server cut are then applied PER PELLET (see perHit) - they are not linear in the damage. */
    public static double taczAt(TaczStats.AdvancedData d, double distance) {
        return listedAt(d, distance) / Math.max(1, d.pellets());
    }

    /** The figure written in the gun data at a distance (all bullets of one trigger pull). */
    public static double listedAt(TaczStats.AdvancedData d, double distance) {
        for (float[] step : d.falloff()) {
            if (distance <= step[0]) {
                return step[1];
            }
        }
        return d.falloff()[d.falloff().length - 1][1];
    }

    /** Group radius in metres at a distance for a spread angle in degrees. */
    public static double radius(double distance, double degrees) {
        return distance * Math.tan(Math.toRadians(degrees));
    }
}
