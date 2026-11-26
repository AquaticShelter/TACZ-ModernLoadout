package dev.l41nx.mwloadout.client.gui;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.modifier.JsonProperty;
import com.tacz.guns.resource.pojo.data.attachment.AttachmentData;
import com.tacz.guns.resource.pojo.data.attachment.Modifier;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AllowAttachmentTagMatcher;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reads gun/attachment numbers out of TACZ indices into ready-to-draw (text, color) lines, and
 * answers per-gun attachment compatibility. Every API call here is lifted verbatim from
 * CodMatchmaking's TaczItemCache (decompiled) — the exact getters its working stat tooltips used:
 * GunData.getBulletData/getRoundsPerMinute/getAmmoAmount/getFireModeSet/getAllowAttachments,
 * BulletData.getDamageAmount/getExtraDamage/getBulletAmount, ExtraDamage.getHeadShotMultiplier/
 * getArmorIgnore, AttachmentData.getModifier/getWeight/getExtendedMagLevel, Modifier.getAddend/
 * getPercent/getMultiplier, and AllowAttachmentTagMatcher.match for compat.
 *
 * "Inverted" stats (recoil / inaccuracy / weight / knockback) flip the good/bad coloring — less
 * recoil is green even though the number is negative, same convention the original used.
 */
public final class TaczStats {

    public record Line(String text, int color) {
    }

    private static final Map<String, String> AMMO_CACHE = new ConcurrentHashMap<>();

    private TaczStats() {
    }

    /** The gun's ammo id ("tacz:556x45" ...), cached — powers ammo-based search filtering and
     *  the RESERVE line without touching the index per keystroke. */
    public static String ammoId(String gunId) {
        return AMMO_CACHE.computeIfAbsent(gunId, id -> {
            try {
                var index = TimelessAPI.getCommonGunIndex(new ResourceLocation(id));
                if (index.isPresent()) {
                    ResourceLocation ammo = index.get().getGunData().getAmmoId();
                    return ammo == null ? "" : ammo.toString();
                }
            } catch (Exception ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:56", ignored);
            }
            return "";
        });
    }

    /** Build-aware gun stats — the numbers the refit system would show: damage, headshot,
     *  pierce and MAGAZINE all run through AttachmentDataUtils against a stack carrying the
     *  actual attachments (the same utils TACZ's own HUD/refit use), so an extended mag or a
     *  damage muzzle changes the readout instantly. */
    public static List<Line> gunStatsFor(String gunId, java.util.Map<String, String> attachments) {
        List<Line> lines = new ArrayList<>();
        try {
            ItemStack built = dev.l41nx.mwloadout.server.TaczStackFactory.buildGun(gunId, attachments);
            TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId)).ifPresent(index -> {
                GunData data = index.getGunData();
                float damage = (float) AttachmentDataUtils.getDamageWithAttachment(built, data);
                lines.add(new Line("DMG " + trim(damage), MWStyle.TEXT));
                float headshot = (float) AttachmentDataUtils.getHeadshotMultiplier(built, data);
                if (headshot > 0) {
                    lines.add(new Line("HS x" + trim(headshot) + " (" + trim(damage * headshot) + ")", MWStyle.accent()));
                }
                float pierce = (float) AttachmentDataUtils.getArmorIgnoreWithAttachment(built, data);
                if (pierce > 0) {
                    lines.add(new Line("BULLET PENETRATION " + Math.round(pierce * 100) + "%", MWStyle.TEXT_DIM));
                }
                lines.add(new Line("RPM " + rpmWithBuild(data, attachments), MWStyle.TEXT));
                lines.add(new Line("MAG " + AttachmentDataUtils.getAmmoCountWithAttachment(built, data), MWStyle.TEXT));
                if (buildHasMag(attachments, true)) {
                    lines.add(new Line("EXPLOSIVE ROUNDS", MWStyle.accent()));
                }
                if (buildHasMag(attachments, false)) {
                    lines.add(new Line("INCENDIARY ROUNDS", MWStyle.accent()));
                }
                for (String attachmentId : attachments.values()) {
                    for (AttachmentRaw.PotionEffect effect : AttachmentRaw.potionEffects(attachmentId)) {
                        lines.add(new Line("INFLICTS " + effect.displayName(), MWStyle.accent()));
                    }
                }
                var fireModes = data.getFireModeSet();
                if (fireModes != null && !fireModes.isEmpty()) {
                    lines.add(new Line(fireModes.stream().map(Enum::name).collect(Collectors.joining(" / ")), MWStyle.TEXT_DIM));
                }
            });
        } catch (Exception e) {
            return gunStats(gunId);
        }
        return lines.isEmpty() ? gunStats(gunId) : lines;
    }

    public static List<Line> gunStats(String gunId) {
        List<Line> lines = new ArrayList<>();
        try {
            TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId)).ifPresent(index -> {
                GunData data = index.getGunData();
                BulletData bullet = data.getBulletData();
                if (bullet != null) {
                    float damage = bullet.getDamageAmount();
                    ExtraDamage firstStepSource = bullet.getExtraDamage();
                    if (firstStepSource != null && firstStepSource.getDamageAdjust() != null && !firstStepSource.getDamageAdjust().isEmpty()) {
                        damage = (float) number(firstStepSource.getDamageAdjust().get(0), "getDamage"); // distance-0 damage = the number TACZ shows
                    }
                    lines.add(new Line("DMG " + trim(damage), MWStyle.TEXT));
                    ExtraDamage extra = bullet.getExtraDamage();
                    if (extra != null) {
                        float headshot = extra.getHeadShotMultiplier();
                        if (headshot > 0) {
                            lines.add(new Line("HS x" + trim(headshot) + " (" + trim(damage * headshot) + ")", MWStyle.accent()));
                        }
                        float armorIgnore = extra.getArmorIgnore();
                        if (armorIgnore > 0) {
                            lines.add(new Line("BULLET PENETRATION " + Math.round(armorIgnore * 100) + "%", MWStyle.TEXT_DIM));
                        }
                    }
                    if (bullet.getBulletAmount() > 1) {
                        lines.add(new Line("PELLETS " + bullet.getBulletAmount(), MWStyle.TEXT_DIM));
                    }
                }
                lines.add(new Line("RPM " + data.getRoundsPerMinute(), MWStyle.TEXT));
                lines.add(new Line("MAG " + data.getAmmoAmount(), MWStyle.TEXT));
                var fireModes = data.getFireModeSet();
                if (fireModes != null && !fireModes.isEmpty()) {
                    lines.add(new Line(fireModes.stream().map(Enum::name).collect(Collectors.joining(" / ")), MWStyle.TEXT_DIM));
                }
            });
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:141", ignored);
        }
        return lines;
    }

    public static List<Line> attachmentStats(String attachmentId) {
        List<Line> lines = new ArrayList<>();
        try {
            TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId)).ifPresent(index -> {
                AttachmentData data = index.getData();
                if (data == null) {
                    return;
                }
                final boolean magazine = isMagazine(index, data);
                final boolean sight = "SCOPE".equals(attachmentTypeName(index)); // sights never move sprint-to-fire
                Map<String, ?> modifiers = data.getModifier();
                double[] penetration = new double[2]; // [sum of effects, count]
                if (modifiers != null) {
                    for (Map.Entry<String, ?> entry : modifiers.entrySet()) {
                        if (entry.getValue() instanceof JsonProperty<?> property
                                && property.getValue() instanceof Modifier modifier) {
                            if (isPenetrationKey(entry.getKey())) {
                                penetration[0] += effectOf(modifier);
                                penetration[1] += 1;
                                continue;
                            }
                            Line line = formatModifier(entry.getKey(), modifier, magazine);
                            if (line != null) {
                                lines.add(line);
                                if (isAdsKey(entry.getKey()) && !sight) {
                                    Line stf = sprintToFireLine(modifier);
                                    if (stf != null) {
                                        lines.add(stf);
                                    }
                                }
                            }
                        }
                    }
                }
                if ("SCOPE".equals(attachmentTypeName(index))) {
                    float[] zooms = scopeZooms(attachmentId);
                    if (zooms != null) {
                        lines.add(new Line("ZOOM " + zoomText(zooms), MWStyle.TEXT));
                    }
                }
                if (penetration[1] > 0 && penetration[0] != 0) {
                    // armor_ignore + pierce read as ONE stat: the average of the two effects
                    double avg = penetration[0] / penetration[1];
                    lines.add(new Line("BULLET PENETRATION " + (avg > 0 ? "+" : "") + Math.round(avg * 100) + "%",
                            avg > 0 ? MWStyle.POSITIVE : MWStyle.NEGATIVE));
                }
                for (AttachmentRaw.PotionEffect effect : AttachmentRaw.potionEffects(attachmentId)) {
                    lines.add(new Line("INFLICTS " + effect.displayName() + " LV." + effect.level() + " "
                            + trim(effect.durationTicks() / 20f) + "s" + (effect.chance() < 1 ? " (" + Math.round(effect.chance() * 100) + "%)" : ""),
                            MWStyle.accent()));
                }
                float weight = data.getWeight();
                if (weight != 0) {
                    lines.add(new Line("WEIGHT " + (weight > 0 ? "+" : "") + trim(weight),
                            weight > 0 ? MWStyle.NEGATIVE : MWStyle.POSITIVE));
                }
                int extendedMag = data.getExtendedMagLevel();
                if (extendedMag > 0) {
                    lines.add(new Line("EXT MAG LV." + extendedMag, MWStyle.accent()));
                }
            });
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:200", ignored);
        }
        return lines;
    }

    /** MW19 attachment card style: PROS (+, green) and CONS (−, red) as humanized stat names
     *  with NO numbers — "how it feels", not a spreadsheet. Inversion applies (less recoil is a
     *  PRO); zero-effect modifiers are skipped. */
    public static List<Line> attachmentProsCons(String attachmentId) {
        // called every frame by the attachment list / tooltip, and it now reads the client index by reflection (zoom): compute once per attachment
        List<Line> cached = PROS_CONS_CACHE.get(attachmentId);
        if (cached == null) {
            cached = java.util.Collections.unmodifiableList(computeProsCons(attachmentId));
            PROS_CONS_CACHE.put(attachmentId, cached);
        }
        return cached;
    }

    private static final Map<String, List<Line>> PROS_CONS_CACHE = new java.util.HashMap<>();

    /** the build without its sight / scope (TACZ-ModernTweak: sights never change sprint-to-fire) */
    static java.util.Map<String, String> withoutSights(java.util.Map<String, String> attachments) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : attachments.entrySet()) {
            String id = e.getValue();
            boolean sight = false;
            try {
                var index = id == null || id.isEmpty() ? java.util.Optional.<Object>empty()
                        : TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(id)).map(i -> (Object) i);
                sight = index.isPresent() && "SCOPE".equals(attachmentTypeName(index.get()));
            } catch (Throwable ignored) {
                // unknown id: keep it
            }
            if (!sight) {
                out.put(e.getKey(), id);
            }
        }
        return out;
    }

    private static String attachmentTypeName(Object index) {
        Object type = call(index, "getType");
        return type == null ? "" : type.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** Zoom levels of a scope / sight (client display data): {1.0} for a red dot, {3, 6} for a variable scope. null when it has none. */
    private static float[] scopeZooms(String attachmentId) {
        try {
            var client = TimelessAPI.getClientAttachmentIndex(new ResourceLocation(attachmentId));
            if (client.isPresent()) {
                Object zoom = call(client.get(), "getZoom");
                if (zoom instanceof float[] levels && levels.length > 0) {
                    return levels;
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:235", ignored);
        }
        return null;
    }

    /** COD-style scope read-out: more than one zoom level = VARIABLE ZOOM; a single level under 4x = CLEAN SIGHT PICTURE; 4x and up = HIGH MAGNIFICATION. */
    private static Line scopeLine(String attachmentId, Object index) {
        if (!"SCOPE".equals(attachmentTypeName(index))) {
            return null;
        }
        float[] zooms = scopeZooms(attachmentId);
        if (zooms == null) {
            return null;
        }
        if (zooms.length > 1) {
            return new Line("+ VARIABLE ZOOM", MWStyle.POSITIVE);
        }
        return new Line(zooms[0] < 4f ? "+ CLEAN SIGHT PICTURE" : "+ HIGH MAGNIFICATION", MWStyle.POSITIVE);
    }

    private static String zoomText(float[] zooms) {
        if (zooms.length == 1) {
            return trim(zooms[0]) + "x";
        }
        float min = zooms[0], max = zooms[0];
        for (float z : zooms) {
            min = Math.min(min, z);
            max = Math.max(max, z);
        }
        return trim(min) + "x-" + trim(max) + "x";
    }

    private static List<Line> computeProsCons(String attachmentId) {
        List<Line> pros = new ArrayList<>();
        List<Line> cons = new ArrayList<>();
        final double[] penetration = new double[1]; // armor_ignore + pierce summed into ONE "bullet penetration" stat
        try {
            TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId)).ifPresent(index -> {
                AttachmentData data = index.getData();
                if (data == null) {
                    return;
                }
                final boolean magazine = isMagazine(index, data);
                final boolean laser = "LASER".equals(attachmentTypeName(index));
                final boolean sight = "SCOPE".equals(attachmentTypeName(index)); // sights never move sprint-to-fire
                Map<String, ?> modifiers = data.getModifier();
                if (modifiers != null) {
                    for (Map.Entry<String, ?> entry : modifiers.entrySet()) {
                        if (!(entry.getValue() instanceof JsonProperty<?> property)) {
                            continue;
                        }
                        Object value = property.getValue();
                        // TACZ custom modifiers (recoil among them) store a fastutil
                        // Pair<Modifier, Modifier> (pitch, yaw) instead of a single Modifier.
                        // Readout rules: BOTH axes touched and unequal -> one combined stat named
                        // for the stronger axis (pitch stronger = RECOIL STEADINESS, yaw stronger
                        // = RECOIL STABILIZATION); equal, or only one axis touched -> the axes are
                        // listed separately as VERTICAL / HORIZONTAL RECOIL CONTROL.
                        if (value instanceof it.unimi.dsi.fastutil.Pair<?, ?> pair
                                && pair.left() instanceof Modifier pitch
                                && pair.right() instanceof Modifier yaw) {
                            addRecoilLines(entry.getKey(), effectOf(pitch), effectOf(yaw), laser, pros, cons);
                            continue;
                        }
                        if (value instanceof java.util.Map<?, ?> typeMap) {
                            // Inaccuracy-style modifiers: Map<InaccuracyType, Modifier>. Sum the
                            // signed effects; LESS inaccuracy is a PRO ("+ ACCURACY").
                            double total = 0;
                            for (Object sub : typeMap.values()) {
                                if (sub instanceof Modifier subMod) {
                                    total += signedEffect(subMod);
                                }
                            }
                            if (total != 0) {
                                if (total < 0) {
                                    pros.add(new Line("+ ACCURACY", MWStyle.POSITIVE));
                                } else {
                                    cons.add(new Line("- ACCURACY", MWStyle.NEGATIVE));
                                }
                            }
                            continue;
                        }
                        if (!(value instanceof Modifier modifier)) {
                            continue;
                        }
                        if (entry.getKey().contains("weight")) {
                            // Already represented below as MOBILITY, computed from data.getWeight()
                            // (the gun's actual aggregate weight, not this one attachment's raw
                            // modifier value) — showing a separate "WEIGHT/WEIGHT MODIFIER" line
                            // here duplicated that same underlying concept under a second name.
                            continue;
                        }
                        // effectOf also reads "function" (lua) modifiers, which have no addend/percent/multiplier at all
                        double signedValue = effectOf(modifier);
                        if (signedValue == 0) {
                            continue;
                        }
                        if (isPenetrationKey(entry.getKey())) {
                            penetration[0] += signedValue;
                            continue;
                        }
                        boolean positive = signedValue > 0;
                        boolean inverted = entry.getKey().contains("recoil") || entry.getKey().contains("inaccuracy")
                                || entry.getKey().contains("weight") || entry.getKey().contains("knockback")
                                || entry.getKey().contains("ads");
                        boolean good = inverted != positive;
                        String label = statLabel(entry.getKey(), magazine);
                        if (good) {
                            pros.add(new Line("+ " + label, MWStyle.POSITIVE));
                        } else {
                            cons.add(new Line("- " + label, MWStyle.NEGATIVE));
                        }
                        // TaCZ Modern Tweaks: sprint-to-fire follows the ADS time, so the same attachment moves it too
                        if (isAdsKey(entry.getKey()) && !sight && dev.l41nx.mwloadout.compat.ModernTweakBridge.sprintToFirePerAdsSecond() > 0f) {
                            if (good) {
                                pros.add(new Line("+ SPRINT TO FIRE", MWStyle.POSITIVE));
                            } else {
                                cons.add(new Line("- SPRINT TO FIRE", MWStyle.NEGATIVE));
                            }
                        }
                    }
                }
                if (penetration[0] > 0) {
                    pros.add(new Line("+ BULLET PENETRATION", MWStyle.POSITIVE));
                } else if (penetration[0] < 0) {
                    cons.add(new Line("- BULLET PENETRATION", MWStyle.NEGATIVE));
                }
                for (AttachmentRaw.PotionEffect effect : AttachmentRaw.potionEffects(attachmentId)) {
                    pros.add(new Line("+ INFLICTS " + effect.displayName(), MWStyle.POSITIVE));
                }
                Line scope = scopeLine(attachmentId, index);
                if (scope != null) {
                    pros.add(0, scope); // what the sight IS comes first
                }
                int extendedMag = data.getExtendedMagLevel();
                if (extendedMag > 0) {
                    pros.add(new Line("+ MAGAZINE CAPACITY", MWStyle.POSITIVE));
                }
                if (hasMagFlag(data, true)) {
                    pros.add(new Line("+ EXPLOSIVE ROUNDS", MWStyle.POSITIVE));
                }
                if (hasMagFlag(data, false)) {
                    pros.add(new Line("+ INCENDIARY ROUNDS", MWStyle.POSITIVE));
                }
                float weight = data.getWeight();
                if (weight > 0) {
                    cons.add(new Line("- MOBILITY", MWStyle.NEGATIVE));
                } else if (weight < 0) {
                    pros.add(new Line("+ MOBILITY", MWStyle.POSITIVE));
                }
            });
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:377", ignored);
        }
        pros.addAll(cons);
        return pros;
    }


    /** Humanized attachment stat names. ammo_speed and effective_range are SEPARATE stats;
     *  explosion / incendiary damage get their own proper names instead of the raw key. */
    /** Is this attachment an (extended) magazine? Mags describe the ROUND (bullet velocity); every
     *  other attachment — barrels, muzzles — and the gun's own HUD describe MUZZLE velocity. */
    private static boolean isMagazine(Object attachmentIndex, AttachmentData data) {
        try {
            if (data != null && data.getExtendedMagLevel() > 0) {
                return true;
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:393", ignored);
        }
        Object type = call(attachmentIndex, "getType");
        return type != null && type.toString().toUpperCase(java.util.Locale.ROOT).contains("MAG");
    }

    private static String statLabel(String key) {
        return statLabel(key, false);
    }

    private static String statLabel(String key, boolean magazine) {
        return switch (key) {
            case "rpm" -> "FIRE RATE";
            case "ads" -> "ADS SPEED";
            case "ammo_speed", "speed" -> magazine ? "BULLET VELOCITY" : "MUZZLE VELOCITY";
            case "effective_range" -> "EFFECTIVE RANGE";
            case "head_shot" -> "HEADSHOT DAMAGE";
            case "armor_ignore", "pierce" -> "BULLET PENETRATION";
            default -> isExplosiveDamageKey(key) ? "EXPLOSIVE DAMAGE"
                    : isIncendiaryDamageKey(key) ? "INCENDIARY DAMAGE"
                    : pretty(key);
        };
    }

    private static void addRecoilLines(String key, double pitchEffect, double yawEffect, boolean laser, List<Line> pros, List<Line> cons) {
        double pitchMag = Math.abs(pitchEffect), yawMag = Math.abs(yawEffect);
        boolean bothTouched = pitchMag > 1e-9 && yawMag > 1e-9;
        boolean sameDirection = Math.signum(pitchEffect) == Math.signum(yawEffect);
        if (laser && bothTouched && sameDirection && Math.abs(pitchMag - yawMag) <= 1e-6) {
            // a laser that calms vertical and horizontal kick by the SAME amount reads as one thing: how smooth the recoil is
            boolean reduces = pitchEffect < 0;
            (reduces ? pros : cons).add(new Line((reduces ? "+ " : "- ") + "RECOIL SMOOTHNESS", reduces ? MWStyle.POSITIVE : MWStyle.NEGATIVE));
            return;
        }
        if (bothTouched && sameDirection && Math.abs(pitchMag - yawMag) > 1e-6) {
            boolean reduces = pitchEffect < 0; // less recoil = pro
            String label = pitchMag > yawMag ? "RECOIL STEADINESS" : "RECOIL STABILIZATION";
            (reduces ? pros : cons).add(new Line((reduces ? "+ " : "- ") + label, reduces ? MWStyle.POSITIVE : MWStyle.NEGATIVE));
            return;
        }
        if (pitchMag > 1e-9) {
            boolean reduces = pitchEffect < 0;
            (reduces ? pros : cons).add(new Line((reduces ? "+ " : "- ") + "VERTICAL RECOIL CONTROL", reduces ? MWStyle.POSITIVE : MWStyle.NEGATIVE));
        }
        if (yawMag > 1e-9) {
            boolean reduces = yawEffect < 0;
            (reduces ? pros : cons).add(new Line((reduces ? "+ " : "- ") + "HORIZONTAL RECOIL CONTROL", reduces ? MWStyle.POSITIVE : MWStyle.NEGATIVE));
        }
    }

    /** The attachment's ADS modifier key ("ads", or TACZ's ADS_TIME property id). */
    private static boolean isAdsKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.equals("ads") || k.equals("ads_time") || k.equals("ads_addend");
    }

    /** "SPRINT TO FIRE -0.03s / -8%" for an ADS modifier, when TaCZ Modern Tweaks drives sprint-to-fire from ADS; null otherwise. */
    private static Line sprintToFireLine(Modifier modifier) {
        float per = dev.l41nx.mwloadout.compat.ModernTweakBridge.sprintToFirePerAdsSecond();
        if (per <= 0f) {
            return null;
        }
        double addend = modifier.getAddend() * per;
        double percent = modifier.getPercent();
        double multiplier = modifier.getMultiplier();
        StringBuilder text = new StringBuilder("SPRINT TO FIRE ");
        boolean hasValue = false;
        double sign = 0;
        if (Math.abs(addend) > 1e-4) {
            text.append(addend > 0 ? "+" : "").append(String.format(java.util.Locale.ROOT, "%.2f", addend)).append('s');
            hasValue = true;
            sign += addend;
        }
        if (percent != 0) {
            if (hasValue) {
                text.append(' ');
            }
            text.append(percent > 0 ? "+" : "").append(Math.round(percent * 100)).append('%');
            hasValue = true;
            sign += percent;
        }
        if (multiplier != 0 && multiplier != 1) {
            if (hasValue) {
                text.append(' ');
            }
            text.append('x').append(String.format(java.util.Locale.ROOT, "%.2f", multiplier));
            hasValue = true;
            sign += multiplier - 1;
        }
        if (!hasValue || sign == 0) {
            return null;
        }
        return new Line(text.toString(), sign > 0 ? MWStyle.NEGATIVE : MWStyle.POSITIVE);
    }

    private static Line formatModifier(String key, Modifier modifier, boolean magazine) {
        double addend = modifier.getAddend();
        double percent = modifier.getPercent();
        double multiplier = modifier.getMultiplier();
        StringBuilder text = new StringBuilder(statLabel(key, magazine)).append(' ');
        boolean hasValue = false;
        if (addend != 0) {
            text.append(addend > 0 ? "+" : "").append(trim((float) addend));
            hasValue = true;
        }
        if (percent != 0) {
            if (hasValue) {
                text.append(' ');
            }
            text.append(percent > 0 ? "+" : "").append(Math.round(percent * 100)).append('%');
            hasValue = true;
        }
        if (multiplier != 0 && multiplier != 1) {
            if (hasValue) {
                text.append(' ');
            }
            text.append('x').append(String.format("%.2f", multiplier));
            hasValue = true;
        }
        double fnEffect = 0;
        if (!hasValue) {
            // "function" (lua) modifiers carry no addend/percent/multiplier: show what the function does to a unit value
            fnEffect = effectOf(modifier);
            if (fnEffect == 0) {
                return null;
            }
            text.append(fnEffect > 0 ? "+" : "").append(Math.round(fnEffect * 100)).append('%');
        }
        boolean positive = addend > 0 || percent > 0 || multiplier > 1 || fnEffect > 0;
        boolean negative = addend < 0 || percent < 0 || (multiplier > 0 && multiplier < 1) || fnEffect < 0;
        boolean inverted = key.contains("recoil") || key.contains("inaccuracy")
                || key.contains("weight") || key.contains("knockback");
        if (inverted) {
            boolean swap = positive;
            positive = negative;
            negative = swap;
        }
        return new Line(text.toString(), positive ? MWStyle.POSITIVE : negative ? MWStyle.NEGATIVE : MWStyle.TEXT_DIM);
    }

    private static final java.util.Map<String, Boolean> ATTACHMENT_SUPPORT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Does this gun accept ANY attachment? Guns whose pack declares no attachment slots get no GUNSMITH button
     *  (it used to open an empty gunsmith still showing all six stat rows). Cached per gun id. */
    public static boolean hasAttachmentSupport(String gunId) {
        if (gunId == null || gunId.isEmpty()) {
            return false;
        }
        return ATTACHMENT_SUPPORT.computeIfAbsent(gunId, id -> !allowedTypes(id).isEmpty());
    }

    public static void clearAttachmentSupportCache() {
        ATTACHMENT_SUPPORT.clear();
        SLOT_COUNT.clear();
        ALLOWED_NAMES.clear();
        AttachmentRaw.clear();
        PROS_CONS_CACHE.clear();
    }

    private static final java.util.Map<String, Set<String>> ALLOWED_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Map<String, Integer> SLOT_COUNT = new java.util.concurrent.ConcurrentHashMap<>();

    /** TACZ's own description key for a gun (the "tooltip" field of its index) - empty when the pack has none. */
    public static String gunTooltipKey(String gunId) {
        try {
            return TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId))
                    .map(index -> index.getPojo().getTooltip()).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** Same for an attachment. */
    public static String attachmentTooltipKey(String attachmentId) {
        try {
            return TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId))
                    .map(index -> index.getPojo().getTooltip()).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** How many attachment slots this gun actually allows (its pack's allow_attachments). Falls back to 5 when the pack
     *  declares nothing. Cached - the weapon cards ask every frame. */
    public static int attachmentSlotCount(String gunId) {
        return SLOT_COUNT.computeIfAbsent(gunId, id -> {
            int n = allowedTypes(id).size();
            return n > 0 ? n : 5;
        });
    }

    /** Attachments really installed on a build: the stored fire-mode marker and blank / disallowed-type entries do not count. */
    public static int installedAttachments(String gunId, java.util.Map<String, String> build) {
        if (build == null || build.isEmpty()) {
            return 0;
        }
        // cached per gun: this runs for every weapon card EVERY frame (it used to rebuild the set + hit TACZ each time)
        Set<String> allowed = ALLOWED_NAMES.computeIfAbsent(gunId, id -> {
            Set<String> names = new java.util.HashSet<>();
            for (AttachmentType type : allowedTypes(id)) {
                names.add(type.name());
            }
            return names;
        });
        int count = 0;
        for (java.util.Map.Entry<String, String> e : build.entrySet()) {
            if (dev.l41nx.mwloadout.server.TaczStackFactory.isMeta(e.getKey())) {
                continue; // fire mode rides in the same map - it was being counted as a 6th mod
            }
            String id = e.getValue();
            if (id == null || id.isEmpty() || (!allowed.isEmpty() && !allowed.contains(e.getKey()))) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static Boolean ammoSlotHookOk;

    /** The AMMO TYPE slot is offered only when the mixin really took effect: the gun must accept the injected type. Checked once per session
     *  on the first gun asked, so a partly applied mixin (or a TACZ build it does not match) simply leaves the old single slot instead of
     *  showing a slot that cannot be filled. */
    private static boolean ammoSlotWorks(String gunId) {
        if (!dev.l41nx.mwloadout.MWSlots.active()) {
            return false;
        }
        if (ammoSlotHookOk == null) {
            try {
                net.minecraft.world.item.ItemStack gun = dev.l41nx.mwloadout.server.TaczStackFactory.buildGun(gunId, java.util.Map.of());
                var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(gun);
                ammoSlotHookOk = igun != null && igun.allowAttachmentType(gun, dev.l41nx.mwloadout.MWSlots.AMMO);
            } catch (Throwable t) {
                ammoSlotHookOk = false;
            }
            if (!ammoSlotHookOk) {
                dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.warn("[MWLoadout] AMMO TYPE slot: the gun-side mixin did not apply, keeping one shared slot");
            }
        }
        return ammoSlotHookOk;
    }

    /** Attachment types this gun's own pack declares as installable — the type rail only shows
     *  these. Empty/exception falls back to "show everything" rather than hiding the editor. */
    public static Set<AttachmentType> allowedTypes(String gunId) {
        Set<AttachmentType> allowed = new LinkedHashSet<>();
        try {
            TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId)).ifPresent(index -> {
                List<AttachmentType> types = index.getGunData().getAllowAttachments();
                if (types != null) {
                    for (AttachmentType type : types) {
                        if (type != null && type != AttachmentType.NONE) {
                            allowed.add(type);
                            if (type == AttachmentType.EXTENDED_MAG && ammoSlotWorks(gunId)) {
                                allowed.add(dev.l41nx.mwloadout.MWSlots.AMMO); // MAGAZINE then AMMO TYPE
                            }
                        }
                    }
                }
            });
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:582", ignored);
        }
        return allowed;
    }

    /** TACZ's own gun<->attachment tag matching; permissive on failure (same as the original). */
    public static boolean compatible(String gunId, String attachmentId) {
        try {
            return AllowAttachmentTagMatcher.match(new ResourceLocation(gunId), new ResourceLocation(attachmentId));
        } catch (Exception e) {
            return true;
        }
    }


    /** Gunsmith bar slots. Indices 0-5 are the classic MW19 six (kept stable — SlotPickerScreen
     *  draws just those); 6-8 are the extended stats: MUZZLE VELOCITY is its own stat (no longer
     *  folded into RANGE, which is purely the effective-range falloff distance), and the
     *  EXPLOSIVE / INCENDIARY damage bars only appear for guns/attachments that have them. */
    public static final String[] BAR_LABELS = {"ACCURACY", "DAMAGE", "RANGE", "FIRE RATE", "MOBILITY", "CONTROL",
            "MUZZLE VELOCITY", "EXPLOSIVE DMG", "INCENDIARY DMG"};
    public static final int BAR_COUNT = BAR_LABELS.length;
    public static final int IDX_RPM = 3;
    /** Top-to-bottom order the gunsmith draws the bars in (bullet speed sits under range). */
    /** Explosive / incendiary damage have no bar in the gun info any more (they still show as attachment lines). */
    public static final int[] BAR_ORDER = {0, 1, 2, 6, 3, 4, 5};
    /** Raw-value direction per slot: true = a LOWER raw number is the better one (inaccuracy,
     *  weight, recoil) — drives good/bad colouring of percentage deltas. */
    public static final boolean[] LOWER_IS_BETTER = {true, false, false, false, true, true, false, false, false};
    /** Display units matched to rawStats() order — "" for a bare number. */
    public static final String[] BAR_UNITS = {"\u00b0 spread", "", "blocks", "RPM", "weight", "\u00b0 kick", "m/s", "", ""};
    /** What the NUMBER next to each bar is. Three bars are drawn inverted (a longer bar = better) while their number is a "lower is better" quantity:
     *  ACCURACY shows the standing spread, MOBILITY the build's weight, CONTROL the recoil kick per shot - so a high bar always means good, and the
     *  number is TACZ's own figure. */
    public static final String[] BAR_MEANING = {"Standing spread in degrees (a tighter cone is more accurate)", "Damage of one hit",
            "Distance up to which a hit does full damage", "Rounds per minute", "Weight of the whole build (a lighter gun is more mobile)",
            "Recoil kick per shot in degrees, vertical + horizontal (less kick is easier to control)", "Speed of the bullet leaving the barrel",
            "", ""};

    /** Should this slot be drawn at all? The first seven always are; explosive/incendiary only
     *  when any of the supplied raw vectors (current / stock / hovered) has a non-zero value. */
    public static boolean barRelevant(int index, float[]... raws) {
        if (index < 6 || index == 6) {
            return true;
        }
        for (float[] raw : raws) {
            if (raw != null && index < raw.length && raw[index] > 0.0001f) {
                return true;
            }
        }
        return false;
    }

    /** Normalized 0..1 bars from a raw vector — scales chosen against the default pack's spread
     *  so mid-tier guns sit mid-bar. */
    public static float[] barsFromRaw(float[] raw) {
        float[] bars = new float[BAR_COUNT];
        if (raw == null || raw.length < BAR_COUNT) {
            java.util.Arrays.fill(bars, 0.5f);
            return bars;
        }
        bars[0] = 1f - Math.min(1f, raw[0] / 8f);
        // sqrt curve against a 100-damage ceiling (was 40, which made every 40+ gun read as a full bar): the NUMBER is always exact, this is only the bar.
        bars[1] = Math.min(1f, (float) Math.sqrt(Math.max(0f, raw[1]) / 100.0));
        bars[2] = Math.min(1f, raw[2] / 120f);
        bars[3] = Math.min(1f, raw[3] / 1100f);
        bars[4] = 1f - Math.min(1f, raw[4] / 12f);
        bars[5] = 1f - Math.min(1f, raw[5] / 4f);
        bars[6] = Math.min(1f, raw[6] / 1000f);
        bars[7] = Math.min(1f, (float) Math.sqrt(Math.max(0f, raw[7]) / 40.0));
        bars[8] = Math.min(1f, (float) Math.sqrt(Math.max(0f, raw[8]) / 20.0));
        return bars;
    }

    /** The gunsmith bars, each normalized 0..1, computed for a specific BUILD (see rawStats). */
    public static float[] barStats(String gunId, java.util.Map<String, String> attachments) {
        return barsFromRaw(rawStats(gunId, attachments));
    }

    /** The raw, pre-normalization number behind each bar slot, straight from TACZ, evaluated
     *  through the same attachment pipeline the fire path uses. Accuracy/control are TACZ's own
     *  inaccuracy/recoil magnitude (lower = better, shown as-is); damage is per-hit; range is the
     *  effective range in blocks; bullet speed is m/s; fire rate RPM; mobility is weight. */
    public static float[] rawStats(String gunId, java.util.Map<String, String> attachments) {
        float[] raw = new float[BAR_COUNT];
        try {
            ItemStack built = dev.l41nx.mwloadout.server.TaczStackFactory.buildGun(gunId, attachments);
            var indexOpt = TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId));
            if (indexOpt.isEmpty()) {
                return raw;
            }
            GunData data = indexOpt.get().getGunData();
            raw[0] = inaccuracyWithBuild(data, attachments);
            raw[1] = (float) AttachmentDataUtils.getDamageWithAttachment(built, data);
            raw[2] = effectiveRangeWithBuild(data, attachments);
            raw[3] = rpmWithBuild(data, attachments);
            raw[4] = (float) AttachmentDataUtils.getWightWithAttachment(built, data);
            raw[5] = recoilWithBuild(data, attachments);
            raw[6] = bulletSpeedWithBuild(data, attachments);
            raw[7] = extraDamageWithBuild(data, attachments, true);
            raw[8] = extraDamageWithBuild(data, attachments, false);
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:676", ignored);
        }
        return raw;
    }

    /** Everything the Advanced Stats box needs for one build. {@code falloff} = ascending {distance, damage} steps (TACZ's damage_adjust:
     *  the damage that applies up to that distance), already scaled by the build's damage / effective-range modifiers; the last step's
     *  distance is Float.MAX_VALUE. */
    public record AdvancedData(float damage, int pellets, float headMul, float armorIgnore, float pierce, float speed, int rpm, int mag,
                               float aimTime, float weight, float inaccuracy, float recoil, float range, float[][] falloff,
                               float[] stanceSpread, float lifeSeconds, String gunId, String taczType, float recoilPitch, float recoilYaw,
                               float stfAimTime, float stfWeight) {
        /** Limb Damage weapon category, resolved LIVE (the server's gunTypeOverrides can change while the box is open). */
        public String category() {
            return DamageModel.category(gunId, taczType);
        }
    }

    /** Raw numbers behind a build, straight from TACZ through the same attachment pipeline as {@link #rawStats}; null when the gun is unknown. */
    public static AdvancedData advancedData(String gunId, java.util.Map<String, String> attachments) {
        try {
            var indexOpt = TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId));
            if (indexOpt.isEmpty()) {
                return null;
            }
            GunData data = indexOpt.get().getGunData();
            float[] raw = rawStats(gunId, attachments);
            float[] stock = rawStats(gunId, java.util.Map.of());
            java.util.Map<String, String> noSights = withoutSights(attachments); // sprint-to-fire ignores sights / scopes
            ItemStack built = dev.l41nx.mwloadout.server.TaczStackFactory.buildGun(gunId, attachments);
            BulletData bullet = data.getBulletData();
            float baseDamage = bullet == null ? 0f : bullet.getDamageAmount();
            float firstStep = baseDamage;
            ExtraDamage firstExtra = bullet == null ? null : bullet.getExtraDamage();
            if (firstExtra != null && firstExtra.getDamageAdjust() != null && !firstExtra.getDamageAdjust().isEmpty()) {
                firstStep = (float) number(firstExtra.getDamageAdjust().get(0), "getDamage"); // distance-0 damage: the figure TACZ displays
            }
            // Attachment effect on damage = this build / the stock gun, through the same TACZ pipeline. It used to divide by damage_adjust[0], which is only
            // right when "damage" equals the first falloff step - shotguns (damage 30, steps 50/40/20) had EVERY step scaled down wrongly.
            float damageRatio = stock[1] > 0f ? raw[1] / stock[1] : 1f;
            float rangeRatio = stock[2] > 0f ? raw[2] / stock[2] : 1f;
            float headMul = 1f;
            java.util.List<float[]> steps = new java.util.ArrayList<>();
            ExtraDamage extra = bullet == null ? null : bullet.getExtraDamage();
            if (extra != null) {
                headMul = extra.getHeadShotMultiplier();
                if (extra.getDamageAdjust() != null) {
                    for (Object pair : extra.getDamageAdjust()) {
                        double distance = number(pair, "getDistance");
                        double damage = number(pair, "getDamage");
                        steps.add(new float[]{distance >= 1.0e6 ? Float.MAX_VALUE : (float) (distance * rangeRatio), (float) (damage * damageRatio)});
                    }
                }
            }
            if (steps.isEmpty()) {
                steps.add(new float[]{Float.MAX_VALUE, raw[1]});
            }
            // A slug ammo mod turns the pellet cloud into ONE projectile (TACZ does it for the MAGAZINE slot, the mod's mixin for AMMO TYPE)
            int pellets = bullet == null || dev.l41nx.mwloadout.MWSlots.slugFitted(built, true) ? 1 : Math.max(1, bullet.getBulletAmount());
            return new AdvancedData(raw[1], pellets, headMul,
                    (float) AttachmentDataUtils.getArmorIgnoreWithAttachment(built, data),
                    bullet == null ? 0f : (float) number(bullet, "getPierce"), raw[6], (int) raw[3],
                    // TACZ's own magazine figure for THIS stack: includes the Extended Mag level (the old figure ignored it)
                    AttachmentDataUtils.getAmmoCountWithAttachment(built, data),
                    adsTimeWithBuild(data, attachments), raw[4], raw[0], raw[5], raw[2], steps.toArray(new float[0][]),
                    stanceSpread(data, attachments), bullet == null ? 0f : (float) number(bullet, "getLifeSecond"),
                    gunId, gunType(indexOpt.get()), (float) recoilPair(data, attachments)[0], (float) recoilPair(data, attachments)[1],
                    adsTimeWithBuild(data, noSights), rawStats(gunId, noSights)[4]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Muzzle speed (m/s) after the build: BulletData speed, then every attachment's
     *  "ammo_speed" modifier. Deliberately NOT part of effective range any more. */
    private static float bulletSpeedWithBuild(GunData data, java.util.Map<String, String> attachments) {
        double speed = 0;
        try {
            speed = number(data.getBulletData(), "getSpeed");
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:750", ignored);
        }
        for (String attachmentId : attachments.values()) {
            for (Modifier modifier : modifiersMatching(attachmentId, key -> key.equals("ammo_speed") || key.equals("speed"))) {
                speed = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(modifier, speed);
            }
        }
        return (float) speed;
    }

    /** Explosive (explosive=true) or incendiary damage after the build. The gun side is read
     *  reflectively from BulletData's explosion / ignite blocks (those getters moved between TACZ
     *  versions); the attachment side evaluates every modifier whose key names the effect. */
    private static float extraDamageWithBuild(GunData data, java.util.Map<String, String> attachments, boolean explosive) {
        double value = 0;
        try {
            BulletData bullet = data.getBulletData();
            if (bullet != null) {
                Object block = call(bullet, explosive ? "getExplosionData" : "getIgnite");
                if (block != null) {
                    value = number(block, "getDamage", "getIgniteDamage", "getDamageAmount", "getEntityDamage");
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:773", ignored);
        }
        for (String attachmentId : attachments.values()) {
            for (Modifier modifier : modifiersMatching(attachmentId,
                    key -> explosive ? isExplosiveDamageKey(key) : isIncendiaryDamageKey(key))) {
                value = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(modifier, value);
            }
        }
        return (float) Math.max(0, value);
    }


    /** TACZ ships two special magazine types: EXPLOSIVE and INCENDIARY. They're identified in the
     *  attachment data by boolean flags — `explosion: true` and `ignite_entity: true`. The flag can
     *  sit among the modifier entries (bare Boolean, or nested {"entity": true}) or be exposed as an
     *  AttachmentData getter depending on the TACZ version, so check every place. */
    private static boolean hasMagFlag(String attachmentId, boolean explosive) {
        try {
            var index = TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId));
            if (index.isEmpty() || index.get().getData() == null) {
                return false;
            }
            return hasMagFlag(index.get().getData(), explosive);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasMagFlag(AttachmentData data, boolean explosive) {
        try {
            Map<String, ?> modifiers = data.getModifier();
            if (modifiers != null) {
                for (Map.Entry<String, ?> entry : modifiers.entrySet()) {
                    String key = entry.getKey();
                    boolean keyMatch = explosive ? key.equals("explosion") || key.equals("explosive")
                            : key.equals("ignite_entity") || key.equals("ignite") || key.equals("incendiary");
                    if (keyMatch) {
                        Object value = entry.getValue() instanceof JsonProperty<?> property ? property.getValue() : entry.getValue();
                        if (truthy(value)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:817", ignored);
        }
        // Getter fallbacks (names differ across TACZ versions).
        String[] getters = explosive
                ? new String[]{"isExplosion", "getExplosion", "isExplosive", "isExplosionEnabled"}
                : new String[]{"isIgniteEntity", "getIgniteEntity", "isIgnite", "getIgnite", "isIncendiary"};
        for (String getter : getters) {
            if (truthy(call(data, getter))) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            return map.values().stream().anyMatch(TaczStats::truthy);
        }
        if (value == null) {
            return false;
        }
        // an object holding the flag (e.g. an Ignite block with isEntity()/isIgniteEntity())
        return truthy(call(value, "isEntity")) || truthy(call(value, "isIgniteEntity")) || truthy(call(value, "isExplode"));
    }

    /** Does any attachment in this build turn the ammo explosive / incendiary? */
    public static boolean buildHasMag(java.util.Map<String, String> attachments, boolean explosive) {
        for (String attachmentId : attachments.values()) {
            if (hasMagFlag(attachmentId, explosive)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplosiveDamageKey(String key) {
        return (key.contains("explo") || key.contains("blast"))
                && !key.contains("radius") && !key.contains("delay") && !key.contains("knockback") && !key.contains("block");
    }

    private static boolean isIncendiaryDamageKey(String key) {
        return (key.contains("ignite") || key.contains("incend") || key.contains("burn"))
                && !key.contains("block") && !key.contains("time") && !key.contains("duration");
    }

    /** All Modifier objects under keys on an attachment passing the filter. Values may be a bare
     *  Modifier, a (pitch,yaw) Pair, or a per-type Map — flatten them all. */
    private static List<Modifier> modifiersMatching(String attachmentId, java.util.function.Predicate<String> keyFilter) {
        List<Modifier> out = new ArrayList<>();
        try {
            var index = TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId));
            if (index.isEmpty() || index.get().getData() == null) {
                return out;
            }
            Map<String, ?> modifiers = index.get().getData().getModifier();
            if (modifiers == null) {
                return out;
            }
            for (Map.Entry<String, ?> entry : modifiers.entrySet()) {
                if (keyFilter.test(entry.getKey())) {
                    Object value = entry.getValue() instanceof JsonProperty<?> property ? property.getValue() : entry.getValue();
                    flattenModifiers(value, out);
                }
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:884", ignored);
        }
        return out;
    }

    private static void flattenModifiers(Object value, List<Modifier> out) {
        if (value instanceof Modifier modifier) {
            out.add(modifier);
        } else if (value instanceof it.unimi.dsi.fastutil.Pair<?, ?> pair) {
            flattenModifiers(pair.left(), out);
            flattenModifiers(pair.right(), out);
        } else if (value instanceof java.util.Map<?, ?> map) {
            map.values().forEach(v -> flattenModifiers(v, out));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(v -> flattenModifiers(v, out));
        }
    }

    private static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double number(Object target, String... methods) {
        for (String method : methods) {
            Object result = call(target, method);
            if (result instanceof Number n) {
                return n.doubleValue();
            }
        }
        return 0;
    }

    private static boolean isPenetrationKey(String key) {
        return key.equals("armor_ignore") || key.equals("pierce");
    }

    /** Signed effect of a modifier: addend + percent + (multiplier - 1); for function-only modifiers, TACZ's own evaluator is run on
     *  a unit value (x = 1) and the change is read off. Never throws. */
    private static double effectOf(Modifier modifier) {
        double effect = signedEffect(modifier);
        if (effect != 0) {
            return effect;
        }
        try {
            return com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(modifier, 1.0) - 1.0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double signedEffect(Modifier modifier) {
        return modifier.getAddend() + modifier.getPercent()
                + (modifier.getMultiplier() != 0 ? modifier.getMultiplier() - 1 : 0);
    }

    /** Recoil magnitude AFTER the build: base pitch+yaw keyframe magnitude, then every installed
     *  attachment's recoil pair applied through TACZ's own AttachmentPropertyManager.eval — the
     *  same math the fire path uses, so a recoil-cut muzzle moves the CONTROL bar for real. */
    private static float recoilWithBuild(GunData data, java.util.Map<String, String> attachments) {
        double[] pair = recoilPair(data, attachments);
        return (float) (pair[0] + pair[1]);
    }

    /** ADS time (s) after the build: the gun's own aim time plus every attachment's "ads" addend - a bigger magazine / heavier optic is
     *  slower to bring up, and the old readout only ever showed the bare gun's number. */
    private static float adsTimeWithBuild(GunData data, java.util.Map<String, String> attachments) {
        double time = number(data, "getAimTime");
        for (String attachmentId : attachments.values()) {
            try {
                var index = TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId));
                if (index.isPresent() && index.get().getData() != null) {
                    time += number(index.get().getData(), "getAdsAddendTime");
                }
            } catch (Exception ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:964", ignored);
            }
        }
        return (float) Math.max(0.0, time);
    }

    /** {vertical, horizontal} recoil kick per shot in degrees after the build's recoil modifiers. */
    private static double[] recoilPair(GunData data, java.util.Map<String, String> attachments) {
        var recoil = data.getRecoil();
        if (recoil == null) {
            return new double[]{0, 0};
        }
        double pitch = firstKeyframeMagnitude(recoil.getPitch());
        double yaw = firstKeyframeMagnitude(recoil.getYaw());
        for (String attachmentId : attachments.values()) {
            try {
                var index = TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId));
                if (index.isEmpty() || index.get().getData() == null) {
                    continue;
                }
                Object entry = index.get().getData().getModifier().get("recoil");
                if (entry instanceof JsonProperty<?> property
                        && property.getValue() instanceof it.unimi.dsi.fastutil.Pair<?, ?> pair
                        && pair.left() instanceof Modifier pitchMod
                        && pair.right() instanceof Modifier yawMod) {
                    pitch = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(pitchMod, pitch);
                    yaw = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(yawMod, yaw);
                }
            } catch (Exception ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:992", ignored);
            }
        }
        return new double[]{pitch, yaw};
    }

    /** TRUE effective range: the first damageAdjust distance in the gun's bullet data (the
     *  point where damage starts falling off) — TACZ's own default is 25m when a pack defines
     *  no falloff. The old speed*lifetime metric measured MAX TRAVEL, which is why SMGs with
     *  fast short-lived bullets pegged the bar. Attachment "effective_range" modifiers eval on
     *  top, same pipeline as rpm/inaccuracy/recoil. */
    private static float effectiveRangeWithBuild(GunData data, java.util.Map<String, String> attachments) {
        double range = 25.0; // TACZ fallback when no falloff curve is defined
        try {
            var bullet = data.getBulletData();
            if (bullet != null && bullet.getExtraDamage() != null
                    && !bullet.getExtraDamage().getDamageAdjust().isEmpty()) {
                range = bullet.getExtraDamage().getDamageAdjust().get(0).getDistance();
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TaczStats:1011", ignored);
        }
        for (String attachmentId : attachments.values()) {
            Object entry = modifierEntry(attachmentId, "effective_range");
            if (entry instanceof Modifier modifier) {
                range = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(modifier, range);
            }
        }
        return (float) range;
    }

    /** RPM after the build: every installed attachment's "rpm" Modifier evaluated in
     *  sequence — a rapid-fire barrel finally moves both the number and the bar. */
    private static int rpmWithBuild(GunData data, java.util.Map<String, String> attachments) {
        double rpm = data.getRoundsPerMinute();
        for (String attachmentId : attachments.values()) {
            Object entry = modifierEntry(attachmentId, "rpm");
            if (entry instanceof Modifier modifier) {
                rpm = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(modifier, rpm);
            }
        }
        return (int) Math.round(rpm);
    }

    /** Standing inaccuracy after the build: the "inaccuracy" custom modifier stores a
     *  Map<InaccuracyType, Modifier> — we evaluate the STAND entry, same pattern as recoil's
     *  Pair. This is the sync the ACCURACY bar was missing. */
    private static float inaccuracyWithBuild(GunData data, java.util.Map<String, String> attachments) {
        double inaccuracy = data.getInaccuracy(com.tacz.guns.resource.pojo.data.gun.InaccuracyType.STAND);
        for (String attachmentId : attachments.values()) {
            Object entry = modifierEntry(attachmentId, "inaccuracy");
            if (entry instanceof java.util.Map<?, ?> map) {
                Object modifier = map.get(com.tacz.guns.resource.pojo.data.gun.InaccuracyType.STAND);
                if (modifier instanceof Modifier standMod) {
                    inaccuracy = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(standMod, inaccuracy);
                }
            }
        }
        return (float) inaccuracy;
    }

    /** TACZ's own gun type ("rifle", "pistol", "sniper", "rpg", "smg", "shotgun", "mg") - the category Limb Damage groups guns by. */
    private static String gunType(Object index) {
        try {
            Object type = index.getClass().getMethod("getType").invoke(index);
            return type == null ? "" : type.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    /** A bar's number exactly as it should read: TACZ's own figure (64 stays 64, not 64.0 and never a 0-100 score). */
    public static String formatStat(int index, float value) {
        if (index == IDX_RPM || Math.abs(value - Math.rint(value)) < 0.005f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** Inaccuracy (degrees) per stance after the build: {STAND, MOVE, SNEAK, LIE, AIM}. Same evaluation as the single STAND figure. */
    private static float[] stanceSpread(GunData data, java.util.Map<String, String> attachments) {
        var types = com.tacz.guns.resource.pojo.data.gun.InaccuracyType.values();
        float[] out = new float[5];
        String[] wanted = {"STAND", "MOVE", "SNEAK", "LIE", "AIM"};
        for (int i = 0; i < wanted.length; i++) {
            for (var type : types) {
                if (!type.name().equals(wanted[i])) {
                    continue;
                }
                double value = data.getInaccuracy(type);
                for (String attachmentId : attachments.values()) {
                    Object entry = modifierEntry(attachmentId, "inaccuracy");
                    if (entry instanceof java.util.Map<?, ?> map && map.get(type) instanceof Modifier mod) {
                        value = com.tacz.guns.resource.modifier.AttachmentPropertyManager.eval(mod, value);
                    }
                }
                out[i] = (float) value;
            }
        }
        return out;
    }

    /** The raw value stored under a modifier key on an attachment (unwrapped from JsonProperty),
     *  or null — shared by the rpm/inaccuracy/recoil evaluators. */
    private static Object modifierEntry(String attachmentId, String key) {
        try {
            var index = TimelessAPI.getCommonAttachmentIndex(new ResourceLocation(attachmentId));
            if (index.isEmpty() || index.get().getData() == null) {
                return null;
            }
            Object entry = index.get().getData().getModifier().get(key);
            return entry instanceof JsonProperty<?> property ? property.getValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static float firstKeyframeMagnitude(com.tacz.guns.resource.pojo.data.gun.GunRecoilKeyFrame[] frames) {
        if (frames == null || frames.length == 0) {
            return 0f;
        }
        float[] value = frames[0].getValue();
        if (value == null || value.length == 0) {
            return 0f;
        }
        float sum = 0f;
        for (float component : value) {
            sum += Math.abs(component);
        }
        return sum / value.length;
    }

    private static String trim(float value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.1f", value);
    }

    private static String pretty(String key) {
        String[] words = key.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.toUpperCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }
}
