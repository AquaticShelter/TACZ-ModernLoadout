package dev.l41nx.mwloadout.compat;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import java.lang.reflect.Method;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/**
 * Soft bridge to TACZ-ModernTweak (modid tacz_moderntweak): no compile dependency,
 * everything by reflection on its public {@code TacticalTuning} API. With Modern Tweaks installed the gunsmith shows its
 * real handling numbers - sprint-to-fire (from ADS time + weight) and the quicker ADS time; without it every call
 * reports "not available" and the screens look exactly as before.
 */
public final class ModernTweakBridge {
    /** TACZ-ModernTweak 1.7+ */
    private static final String[][] TARGETS = {{"tacz_moderntweak", "dev.l41nx.moderntweak.TacticalTuning"}};
    private static boolean looked;
    private static Method stf, ads, perAds;

    private ModernTweakBridge() {
    }

    private static synchronized void look() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            Class<?> c = null;
            for (String[] t : TARGETS) {
                if (ModList.get().isLoaded(t[0])) {
                    c = Class.forName(t[1]);
                    break;
                }
            }
            if (c == null) {
                return;
            }
            stf = c.getMethod("sprintToFire", GunData.class, float.class, float.class);
            try {
                ads = c.getMethod("shownAdsTime", float.class);
            } catch (NoSuchMethodException ignored) {
                ads = null;
            }
            try {
                perAds = c.getMethod("sprintToFirePerAdsSecond");
            } catch (NoSuchMethodException ignored) {
                perAds = null;
            }
        } catch (Throwable t) {
            stf = null;
            dev.l41nx.mwloadout.MWLog.quiet("compat/ModernTweakBridge:look", t);
        }
    }

    /** Modern Tweaks is installed and new enough to report sprint-to-fire. */
    public static boolean available() {
        look();
        return stf != null;
    }

    /** Sprint-to-fire (s) for a build: TACZ ADS time and weight with the build's attachments. NaN when unavailable. */
    public static float sprintToFire(String gunId, float rawAdsTime, float weight) {
        if (!available() || gunId == null) {
            return Float.NaN;
        }
        try {
            var index = TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId));
            if (index.isEmpty()) {
                return Float.NaN;
            }
            return (Float) stf.invoke(null, index.get().getGunData(), rawAdsTime, weight);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** The ADS time Modern Tweaks actually uses in game for a TACZ ADS time (unchanged without it). */
    public static float shownAdsTime(float rawAdsTime) {
        if (!available() || ads == null) {
            return rawAdsTime;
        }
        try {
            return (Float) ads.invoke(null, rawAdsTime);
        } catch (Throwable t) {
            return rawAdsTime;
        }
    }

    /** Seconds of sprint-to-fire per second of ADS time (0 when sprint-to-fire does not follow ADS). */
    public static float sprintToFirePerAdsSecond() {
        if (!available() || perAds == null) {
            return 0f;
        }
        try {
            return (Float) perAds.invoke(null);
        } catch (Throwable t) {
            return 0f;
        }
    }
}
