package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.client.gui.MWStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * Killstreak/specialist/system toast cards: single active slot (a new toast replaces whatever's
 * showing rather than queuing), wall-clock timed, position-only animation (no alpha fade). This
 * keeps two independent clocks from ever fighting each other, which is what caused the flicker in
 * an earlier queued/tick-interpolated version.
 *
 * <p>Position is one of {@link ScreenAnchor}'s 9 points ({@link ClientCfg#toastAnchor()}),
 * default bottom_center. Side anchors slide in horizontally; center-column anchors slide
 * vertically from the nearer edge.
 *
 * <p>Renders as its own overlay layer — never draws into Killfeed's banner, and vice versa. Both
 * can land in the same screen corner if configured that way; the default offset here clears
 * Killfeed's own default bottom-center banner in the common case, and either position is
 * independently reconfigurable.
 */
public final class ToastHud {

    private static final long SLIDE_MILLIS = MWStyle.ANIM_SCREEN_OPEN;
    private static long holdMillis() {
        return ClientCfg.TOAST_HOLD_MS.get();
    }

    private static long totalMillis() {
        return SLIDE_MILLIS * 2 + holdMillis();
    }
    private static final int MIN_WIDTH = 150;
    private static final int MAX_WIDTH = 320;
    private static final int PADDING_X = 20; // 10px each side
    private static final int MIN_HEIGHT = 22;

    private static final class Toast {
        final String title;
        final String subtitle;
        final int accent;
        final int width;
        final int height;
        final java.util.List<net.minecraft.util.FormattedCharSequence> subLines;
        final long startedAt = System.currentTimeMillis();
        int kind = -1; // 0 streak, 1 level, 2 equip, 3 notice: picks the position override

        Toast(String title, String subtitle, int accent, int width, int height,
              java.util.List<net.minecraft.util.FormattedCharSequence> subLines) {
            this.title = title;
            this.subtitle = subtitle;
            this.accent = accent;
            this.width = width;
            this.height = height;
            this.subLines = subLines;
        }
    }

    /** The one active toast, or null — overwritten outright by push(), exactly like
     *  BannerState.currentBanner/currentStreak. No queue. */
    private static Toast current;

    private ToastHud() {
    }

    /** @param title short, ALL-CAPS-styled headline (e.g. "KILLSTREAK READY").
     *  @param subtitle detail line under it (e.g. the reward's name) — pass "" for none.
     *  @param accentColor 0xRRGGBB used for the left accent bar and the title color. */
    public static void push(String title, String subtitle, int accentColor) {
        push(-1, title, subtitle, accentColor);
    }

    /** @param kind 0 streak, 1 level, 2 equip, 3 notice (-1 = none): each kind can have its own screen position in the client config. */
    public static void push(int kind, String title, String subtitle, int accentColor) {
        Minecraft mc = Minecraft.getInstance();
        int titleW = mc.font.width(title.toUpperCase(Locale.ROOT));
        int subW = subtitle == null || subtitle.isEmpty() ? 0 : mc.font.width(subtitle);
        int width = Mth.clamp(Math.max(titleW, subW) + PADDING_X, MIN_WIDTH, MAX_WIDTH);
        // Long details WRAP onto up to two lines instead of being cut off; the card grows to fit.
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = subW == 0 ? java.util.List.of()
                : mc.font.split(net.minecraft.network.chat.Component.literal(subtitle), width - PADDING_X);
        if (lines.size() > 2) {
            lines = lines.subList(0, 2);
        }
        int height = lines.isEmpty() ? MIN_HEIGHT : 14 + 10 * (1 + lines.size());
        current = new Toast(title, subtitle, accentColor, width, height, lines);
        current.kind = kind;
    }

    public static void clear() {
        current = null;
    }

    /** Render is called with a frame's partialTick by the overlay registration, but this class
     *  deliberately never reads it — see class javadoc. */
    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        Toast t = current;
        if (t == null || mc.options.hideGui || mc.player == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - t.startedAt;
        if (elapsed < 0 || elapsed >= totalMillis()) {
            current = null;
            return;
        }

        // size + opacity (settings): the card is laid out in a scaled coordinate space
        float uiScale = ClientCfg.TOAST_SCALE.get() / 100f;
        float opacity = ClientCfg.TOAST_OPACITY.get() / 100f;
        screenWidth = Math.round(screenWidth / uiScale);
        screenHeight = Math.round(screenHeight / uiScale);
        String anchor = ClientCfg.toastAnchorFor(t.kind);
        boolean sideAnchor = !ScreenAnchor.isCenterColumn(anchor);
        // Offsets push INWARD from whichever edge(s) the anchor sits on, not blindly in the
        // positive direction — a positive Y offset on a BOTTOM anchor must move the toast UP
        // (away from the bottom edge), the mirror image of what a TOP anchor needs.
        int xOff = ClientCfg.toastXOffsetFor(t.kind) * (ScreenAnchor.isRight(anchor) ? -1 : 1);
        int yOff = ClientCfg.toastYOffsetFor(t.kind) * (ScreenAnchor.isBottom(anchor) ? -1 : 1);
        // v5.1.0: best-effort compat nudge — if Killfeed is installed AND its own kill/death
        // banner is configured to the exact same corner we are, push ours further out so the two
        // don't render on top of each other. Silent no-op if Killfeed is absent or its position
        // can't be read (see KillFeedCompat.bannerAnchor) — this only ever ADDS margin,
        // never something either mod depends on to look right on its own.
        if (anchor.equals(dev.l41nx.mwloadout.compat.KillFeedCompat.bannerAnchor())) {
            int extra = ScreenAnchor.isCenterColumn(anchor) ? 56 : 20;
            yOff += extra * (ScreenAnchor.isBottom(anchor) ? -1 : 1);
        }
        float settledX = anchorX(anchor, screenWidth, t.width) + xOff;
        float settledY = anchorY(anchor, screenHeight, t.height) + yOff;
        // other HUD mods (ModernHud): step out of their element boxes (avoidance works in real GUI units)
        settledY += dev.l41nx.mwloadout.compat.HudAvoid.dy(settledX * uiScale, settledY * uiScale, t.width * uiScale, t.height * uiScale,
                !ScreenAnchor.isTop(anchor)) / uiScale;

        float slide = computeSlideProgress(elapsed);
        float x = settledX;
        float y = settledY;
        if (sideAnchor) {
            float startX = ScreenAnchor.isLeft(anchor) ? -t.width : screenWidth;
            x = startX + (settledX - startX) * slide;
        } else {
            float startY = ScreenAnchor.isTop(anchor) ? -t.height : screenHeight;
            y = startY + (settledY - startY) * slide;
        }

        int ix = Math.round(x);
        int iy = Math.round(y);
        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1f);
        if (MWStyle.angular()) {
            drawTag(graphics, mc, t, ix, iy, opacity, elapsed);
            graphics.pose().popPose();
            return;
        }
        graphics.fill(ix, iy, ix + t.width, iy + t.height, MWStyle.mulAlpha(MWStyle.PANEL, opacity));
        MWStyle.hairline(graphics, ix, iy, t.width, t.height, MWStyle.mulAlpha(MWStyle.border(), opacity));
        graphics.fill(ix, iy + 1, ix + 3, iy + t.height - 1, MWStyle.mulAlpha(t.accent, opacity));
        if (opacity > 0.08f) {
            graphics.drawString(mc.font, MWStyle.ellipsize(mc.font, t.title.toUpperCase(Locale.ROOT), t.width - PADDING_X),
                    ix + 10, iy + 7, MWStyle.mulAlpha(t.accent, opacity), false);
            for (int i = 0; i < t.subLines.size(); i++) {
                graphics.drawString(mc.font, t.subLines.get(i), ix + 10, iy + 19 + i * 10, MWStyle.mulAlpha(MWStyle.TEXT, opacity), false);
            }
        }
        graphics.pose().popPose();
    }

    /** Dispatch tag: a cut-corner plate with a hard shadow, a solid accent block carrying a one-letter code (not a thin side stripe), the
     *  title in caps and a stepped timer rail that drains along the bottom edge while the toast holds. Drawn with raw fills (HUD context:
     *  the screen fade of MWStyle must not touch it). */
    private static void drawTag(GuiGraphics g, Minecraft mc, Toast t, int x, int y, float opacity, long elapsed) {
        int c = MWStyle.cut(t.width, t.height, 5);
        int block = 16; // accent block width
        int accent = MWStyle.mulAlpha(t.accent | 0xFF000000, opacity);
        MWStyle.plateShadow(g, false, x, y, t.width, t.height, c, 2, MWStyle.mulAlpha(0xAA000000, opacity));
        MWStyle.plateFill(g, false, x, y, t.width, t.height, c, MWStyle.mulAlpha(MWStyle.PANEL | 0xFF000000, opacity));
        // accent block: the left end of the plate, with the same cut at its bottom-left corner
        MWStyle.poly(g, accent, x, y, x + block, y, x + block, y + t.height, x + c, y + t.height, x, y + t.height - c);
        MWStyle.plateEdge(g, false, x, y, t.width, t.height, c, MWStyle.mulAlpha(MWStyle.border() | 0xFF000000, opacity));
        if (opacity > 0.08f) {
            String code = t.title.isEmpty() ? "!" : t.title.substring(0, 1).toUpperCase(Locale.ROOT);
            g.drawString(mc.font, code, x + (block - mc.font.width(code)) / 2 + 1, y + 6, MWStyle.mulAlpha(0xFF0B0C0E, opacity), false);
            g.drawString(mc.font, MWStyle.ellipsize(mc.font, t.title.toUpperCase(Locale.ROOT), t.width - block - 12),
                    x + block + 6, y + 6, MWStyle.mulAlpha(MWStyle.TEXT | 0xFF000000, opacity), false);
            for (int i = 0; i < t.subLines.size(); i++) {
                g.drawString(mc.font, t.subLines.get(i), x + block + 6, y + 18 + i * 10, MWStyle.mulAlpha(MWStyle.TEXT_DIM | 0xFF000000, opacity), false);
            }
        }
        // timer rail: a thin continuous line that drains during the hold
        long holdStart = SLIDE_MILLIS, holdEnd = SLIDE_MILLIS + holdMillis();
        float left = elapsed <= holdStart ? 1f : elapsed >= holdEnd ? 0f : 1f - (elapsed - holdStart) / (float) (holdEnd - holdStart);
        int railX = x + block + 6, railW = t.width - block - c - 10;
        g.fill(railX, y + t.height - 4, railX + railW, y + t.height - 3, MWStyle.mulAlpha(0x30FFFFFF, opacity));
        g.fill(railX, y + t.height - 4, railX + Math.round(railW * left), y + t.height - 3, accent);
    }

    /** 0 = fully off-screen at the starting edge, 1 = fully settled — pure function of elapsed
     *  wall-clock time, same shape as Killfeed's BannerOverlay#computeSlideProgress. */
    private static float computeSlideProgress(long elapsed) {
        if (elapsed < SLIDE_MILLIS) {
            return MWStyle.easeOutCubic(elapsed / (float) SLIDE_MILLIS);
        }
        long slideOutStart = SLIDE_MILLIS + holdMillis();
        if (elapsed <= slideOutStart) {
            return 1f;
        }
        float p = Math.min(1f, (elapsed - slideOutStart) / (float) SLIDE_MILLIS);
        return 1f - easeInCubic(p);
    }

    private static float easeInCubic(float v) {
        return v * v * v;
    }

    private static float anchorX(String anchor, int screenWidth, int toastWidth) {
        float nx = ScreenAnchor.normX(anchor);
        return nx * screenWidth - nx * toastWidth; // 0 at left edge, centered at 0.5, flush right at 1
    }

    private static float anchorY(String anchor, int screenHeight, int toastHeight) {
        float ny = ScreenAnchor.normY(anchor);
        return ny * screenHeight - ny * toastHeight;
    }
}
