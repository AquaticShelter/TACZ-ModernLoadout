package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.client.gui.MWStyle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Quaternionf;

/**
 * High Alert's directional warning, radial style (technique mirrored from RadialAggroIndicator's
 * HudHandler, the proven implementation the design references): each reported watcher paints a
 * pulsing chevron on a ring around the crosshair — positioned at sin/cos(angle) * radius and
 * rotated to point outward, so "someone is aiming at you from your 4 o'clock" reads instantly
 * without hunting the screen edges.
 *
 * A watcher HOLDS steady (no re-flashing) for as long as the server keeps reporting the same
 * bearing (every 10 ticks / ~500ms) — only the very first sighting gets a quick fade-in — and
 * only starts a slow fade-out once the reports stop, i.e. once you're no longer being watched.
 * Yaw is re-anchored against the player's CURRENT facing each frame, so turning your head sweeps
 * the band around the ring instead of leaving it stale where it spawned.
 */
public final class HighAlertOverlay {

    /** How long a bearing is allowed to go unrefreshed before it's treated as "still watched".
     *  Kept comfortably above the server's ~500ms report cadence so continuous watching never
     *  dips back into the rise phase and reads as flicker. */
    private static final long REFRESH_GRACE_MILLIS = 650;
    /** Slow fade-out once reports stop arriving — this is the ONLY time the band should be seen
     *  animating; while watched it just holds. */
    private static final long FADE_MILLIS = 1600;
    /** Quick fade-in the first time a bearing is seen; never repeats while it keeps refreshing. */
    private static final long RISE_MILLIS = 140;
    private static final long LIFE_MILLIS = REFRESH_GRACE_MILLIS + FADE_MILLIS;
    private static final int MAX_PULSES = 8;
    /** World-space bearings (holder yaw at report time + relative yaw), re-anchored per frame. */
    private static final float[] WORLD_YAWS = new float[MAX_PULSES];
    /** Last time this slot's bearing was refreshed by the server — drives hold/fade-out timing. */
    private static final long[] STARTED = new long[MAX_PULSES];
    /** When this slot was first sighted — drives the one-time rise-in only. */
    private static final long[] FIRST_SEEN = new long[MAX_PULSES];

    private HighAlertOverlay() {
    }

    public static void pulse(float[] relativeYaws) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        float playerYaw = minecraft.player.getYRot();
        long now = System.currentTimeMillis();
        outer:
        for (float relative : relativeYaws) {
            float world = Mth.wrapDegrees(playerYaw + relative);
            for (int i = 0; i < MAX_PULSES; i++) {
                if (now - STARTED[i] < LIFE_MILLIS && Math.abs(Mth.wrapDegrees(WORLD_YAWS[i] - world)) < 20) {
                    // Same watcher still tracking us — refresh the hold timer only. FIRST_SEEN is
                    // untouched so the rise-in animation never replays while it keeps refreshing.
                    WORLD_YAWS[i] = world;
                    STARTED[i] = now;
                    continue outer;
                }
            }
            int slot = 0;
            for (int i = 1; i < MAX_PULSES; i++) {
                if (STARTED[i] < STARTED[slot]) {
                    slot = i;
                }
            }
            WORLD_YAWS[slot] = world;
            STARTED[slot] = now;
            FIRST_SEEN[slot] = now;
        }
    }

    /** Horizontal edge band: a solid, dense core of `solidCore` px right at the screen edge
     *  (reads as a bold, unmistakable line instead of a faint haze), then an eased gradient tail
     *  for the rest of `depth` so it still dissolves smoothly toward the center. left=true
     *  anchors the solid core at x0 (the left screen edge); false anchors it at x0+depth-1. */
    private static void fadeHorizontal(GuiGraphics graphics, int x0, int depth, int solidCore, int screenHeight,
                                       int baseColor, float peakAlpha, boolean left) {
        int tail = Math.max(1, depth - solidCore);
        for (int i = 0; i < depth; i++) {
            int edgeDist = left ? i : (depth - 1 - i);   // 0 = right at the screen edge
            float a;
            if (edgeDist < solidCore) {
                a = peakAlpha;
            } else {
                float t = (edgeDist - solidCore) / (float) tail;
                a = peakAlpha * (float) Math.pow(1f - t, 1.35);
            }
            if (a <= 0.004f) continue;
            int col = x0 + i;
            graphics.fill(col, 0, col + 1, screenHeight, MWStyle.withAlpha(baseColor, a));
        }
    }

    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        float playerYaw = minecraft.player.getYRot();

        // Accumulate a single directional weight per edge across all active pulses, so multiple
        // threats never stack into a blinding full-screen border. Each watcher HOLDS steady while
        // still being reported and only animates once — a slow fade-out — when it stops. No
        // repeated flashing while continuously watched.
        float topW = 0, bottomW = 0, leftW = 0, rightW = 0;
        for (int i = 0; i < MAX_PULSES; i++) {
            long sinceRefresh = now - STARTED[i];
            if (STARTED[i] <= 0 || sinceRefresh < 0 || sinceRefresh >= LIFE_MILLIS) {
                continue;
            }
            float envelope;
            if (sinceRefresh <= REFRESH_GRACE_MILLIS) {
                // Still being actively reported: one quick rise-in on first sight, then hold flat.
                long sinceFirstSeen = now - FIRST_SEEN[i];
                envelope = sinceFirstSeen < RISE_MILLIS ? (sinceFirstSeen / (float) RISE_MILLIS) : 1f;
            } else {
                // Reports stopped — slow, single fade-out, no bounce.
                float fadeT = (sinceRefresh - REFRESH_GRACE_MILLIS) / (float) FADE_MILLIS;
                envelope = 1f - Mth.clamp(fadeT, 0f, 1f);
            }
            envelope = Math.max(0f, envelope);

            double angle = Math.toRadians(Mth.wrapDegrees(WORLD_YAWS[i] - playerYaw));
            double dirX = Math.sin(angle);
            double dirY = -Math.cos(angle);
            rightW = Math.max(rightW, (float) Math.max(0, dirX) * envelope);
            leftW = Math.max(leftW, (float) Math.max(0, -dirX) * envelope);
            bottomW = Math.max(bottomW, (float) Math.max(0, dirY) * envelope);
            topW = Math.max(topW, (float) Math.max(0, -dirY) * envelope);
        }
        if (topW <= 0.01f && bottomW <= 0.01f && leftW <= 0.01f && rightW <= 0.01f) {
            return;
        }

        // Tighter total coverage than before (was 40px), but with a solid, dense core near the
        // edge so it reads as bold rather than thin — less screen real estate, more presence.
        int depth = 26;
        int solidCore = 12;
        int base = 0xFF000000 | (dev.l41nx.mwloadout.client.ClientCfg.highAlertColor() & 0xFFFFFF);
        // Config-driven intensity (0..1), boosted further and given a higher floor so the default
        // reads clearly and darkly without squinting.
        float peak = Math.max(0.16f, Math.min(0.97f, dev.l41nx.mwloadout.client.ClientCfg.highAlertIntensity() * 1.15f));

        if (topW > 0.01f) {
            graphics.fill(0, 0, screenWidth, solidCore, MWStyle.withAlpha(base, peak * topW));
            graphics.fillGradient(0, solidCore, screenWidth, depth,
                    MWStyle.withAlpha(base, peak * topW), MWStyle.withAlpha(base, 0f));
        }
        if (bottomW > 0.01f) {
            graphics.fill(0, screenHeight - solidCore, screenWidth, screenHeight, MWStyle.withAlpha(base, peak * bottomW));
            graphics.fillGradient(0, screenHeight - depth, screenWidth, screenHeight - solidCore,
                    MWStyle.withAlpha(base, 0f), MWStyle.withAlpha(base, peak * bottomW));
        }
        if (leftW > 0.01f) {
            fadeHorizontal(graphics, 0, depth, solidCore, screenHeight, base, peak * leftW, true);
        }
        if (rightW > 0.01f) {
            fadeHorizontal(graphics, screenWidth - depth, depth, solidCore, screenHeight, base, peak * rightW, false);
        }
    }
}
