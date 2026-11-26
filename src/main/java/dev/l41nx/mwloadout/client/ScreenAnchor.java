package dev.l41nx.mwloadout.client;

import java.util.List;
import java.util.Map;

/**
 * v5.0.0: a simple 9-point screen anchor system (top/middle/bottom x left/center/right) so HUD
 * elements — currently just {@link ToastHud} — can be positioned via a plain config string
 * ("top_left", "bottom_center", ...) instead of a hardcoded corner. Independently written for
 * MWLoadout's own config shape; not a copy of any other mod's implementation.
 */
public final class ScreenAnchor {

    public static final String DEFAULT = "bottom_center";

    /** anchor id -> normalized (0..1, 0..1) screen position, (0,0)=top-left, (1,1)=bottom-right. */
    private static final Map<String, float[]> POINTS = Map.of(
            "top_left", new float[]{0f, 0f},
            "top_center", new float[]{0.5f, 0f},
            "top_right", new float[]{1f, 0f},
            "middle_left", new float[]{0f, 0.5f},
            "center", new float[]{0.5f, 0.5f},
            "middle_right", new float[]{1f, 0.5f},
            "bottom_left", new float[]{0f, 1f},
            "bottom_center", new float[]{0.5f, 1f},
            "bottom_right", new float[]{1f, 1f}
    );

    public static final List<String> IDS = List.of(
            "top_left", "top_center", "top_right",
            "middle_left", "center", "middle_right",
            "bottom_left", "bottom_center", "bottom_right"
    );

    private ScreenAnchor() {
    }

    public static boolean isValid(String id) {
        return id != null && POINTS.containsKey(id);
    }

    public static String orDefault(String id) {
        return isValid(id) ? id : DEFAULT;
    }

    /** 0 = left edge, 0.5 = horizontal center, 1 = right edge. */
    public static float normX(String anchor) {
        return POINTS.get(orDefault(anchor))[0];
    }

    /** 0 = top edge, 0.5 = vertical center, 1 = bottom edge. */
    public static float normY(String anchor) {
        return POINTS.get(orDefault(anchor))[1];
    }

    public static boolean isTop(String anchor) {
        return normY(anchor) == 0f;
    }

    public static boolean isBottom(String anchor) {
        return normY(anchor) == 1f;
    }

    public static boolean isLeft(String anchor) {
        return normX(anchor) == 0f;
    }

    public static boolean isRight(String anchor) {
        return normX(anchor) == 1f;
    }

    /** true for the 3 anchors whose horizontal position is the screen's center column
     *  (top_center, center, bottom_center) — these slide in vertically; the other 6 (pinned to
     *  a left/right edge) slide in horizontally. Matches Killfeed's own BannerOverlay#applySlide
     *  convention exactly. */
    public static boolean isCenterColumn(String anchor) {
        return normX(anchor) == 0.5f;
    }
}
