package dev.l41nx.mwloadout.compat;

import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Keeps MW Loadout's own HUD pieces (killstreak tracker, toasts) clear of other HUD mods instead of drawing on top
 * of them. Reads ModernHud's public layout API ({@code cc.xypp.modernhud.api.HudLayout.rects()}: live element boxes in
 * GUI units) by reflection - no dependency, and without ModernHud (or with an older one) nothing moves.
 */
public final class HudAvoid {
    private static final float MARGIN = 4f;
    private static int state;          // 0 unknown, 1 bound, -1 absent
    private static MethodHandle rects;
    private static final float[][] NONE = new float[0][];
    private static float[][] cache = NONE;
    private static long cacheAt;

    private HudAvoid() {
    }

    private static float[][] boxes() {
        if (state == 0) {
            state = -1;
            try {
                if (ModList.get().isLoaded("modernhud")) {
                    // bound once as a MethodHandle: no reflective access checks or boxing on the per-frame path
                    rects = MethodHandles.publicLookup().findStatic(Class.forName("cc.xypp.modernhud.api.HudLayout"), "rects",
                            MethodType.methodType(float[][].class));
                    state = 1;
                }
            } catch (Throwable ignored) {
                // ModernHud without the layout API
            }
        }
        if (state < 0) {
            return cache;
        }
        long now = System.currentTimeMillis();
        if (now - cacheAt > 50L) {                 // one reflective call per few frames is plenty
            cacheAt = now;
            try {
                cache = (float[][]) rects.invokeExact();
            } catch (Throwable t) {
                state = -1;
                cache = NONE;
            }
        }
        return cache;
    }

    private static boolean hit(float x, float y, float w, float h, float[] r) {
        return x < r[0] + r[2] + MARGIN && x + w + MARGIN > r[0] && y < r[1] + r[3] + MARGIN && y + h + MARGIN > r[1];
    }

    /**
     * How far (GUI units, negative = up) a box at x/y/w/h has to move vertically to stop overlapping other HUD mods'
     * elements. up = push upward (bottom-anchored pieces), otherwise downward. 0 when nothing is in the way.
     */
    public static float dy(float x, float y, float w, float h, boolean up) {
        float[][] b = boxes();
        if (b.length == 0) {
            return 0f;
        }
        float off = 0f;
        for (int pass = 0; pass < 4; pass++) {         // pushing past one box can land on the next one
            boolean moved = false;
            for (float[] r : b) {
                if (hit(x, y + off, w, h, r)) {
                    off = up ? r[1] - MARGIN - (y + h) : r[1] + r[3] + MARGIN - y;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return off;
    }
}
