package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientCfg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * MW2019's actual menu language, second pass: charcoal panels, thin GRAY hairlines (near-white
 * when selected — gold is gone from the defaults, though every color is a client-config knob),
 * ALL-CAPS labels, and the signature hover treatment: a faint light-blue fill with a fine grid
 * mesh inside the hovered tile, exactly the effect the MW19 loadout cards use.
 *
 * All animation remains stateless-per-frame (hover floats eased by frame delta, ~180ms screen
 * open fade) and position-static — hit tests never lie. Hover ticks are globally rate-limited
 * (90ms) so sweeping the cursor across a grid clicks once or twice instead of machine-gunning.
 */
public final class MWStyle {

    public static final int BACKDROP = 0xE6070709;
    public static final int PANEL = 0xF0121316;
    public static final int PANEL_RAISED = 0xF01A1C20;
    public static final int PANEL_HOVER = 0xF022242A;
    public static final int TEXT = 0xFFE8EAEC;
    public static final int TEXT_DIM = 0xFF8E939A;
    public static final int LOCKED = 0xFF5A5E66;
    public static final int DANGER = 0xFFC0392B;
    public static final int POSITIVE = 0xFF6FBF4A;
    /** Attachment-dot gold (installed / available markers on weapon cards). */
    /** The gold of the UI: header bars, tab underlines, selection outlines, progress fills, attachment dots, EQUIPPED. Reserved for the LINES and
     *  marks that carry the eye (unlike accent(), the general highlight). Configurable: client config "signalColor", default #E3B341. */
    public static int signal() {
        return ClientCfg.color(ClientCfg.SIGNAL_COLOR, 0xFFE3B341);
    }
    public static final int NEGATIVE = 0xFFD05C4A;

    // Shared animation durations (ms) — one small set instead of magic numbers scattered per file.
    public static final long ANIM_FAST = 110;
    public static final long ANIM_NORMAL = 180;
    public static final long ANIM_SCREEN_OPEN = 320;
    public static final long ANIM_SCREEN_CLOSE = 200;

    private static long lastHoverSound;

    private MWStyle() {
    }

    // Config-driven theme colors (cheap reads; ForgeConfig caches values).
    public static int border() {
        return ClientCfg.color(ClientCfg.BORDER_COLOR, 0xFF3A3E44);
    }

    public static int borderActive() {
        return ClientCfg.color(ClientCfg.BORDER_ACTIVE_COLOR, 0xFFC8CDD2);
    }

    public static int accent() {
        return ClientCfg.color(ClientCfg.ACCENT_COLOR, 0xFFC8CDD2);
    }

    public static int hoverTint() {
        return ClientCfg.color(ClientCfg.HOVER_COLOR, 0xFF6FB7E8);
    }

    private static final java.util.Map<String, String> TR_CACHE = new java.util.HashMap<>();

    /** Cached Component.translatable(key).getString() for text drawn EVERY frame (labels, headers): the language lookup and
     *  string build used to run per frame per label. Client thread only; flushed whenever a loadout screen opens, which also
     *  covers a language switch or a resource reload. */
    public static String tr(String key) {
        String cached = TR_CACHE.get(key);
        if (cached == null) {
            cached = Component.translatable(key).getString();
            TR_CACHE.put(key, cached);
        }
        return cached;
    }

    public static void playClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
    }

    /** UI open chime — a low-then-high two-note swell built from the UI click sound (guaranteed
     *  to exist in 1.20.1), so no risk of a missing-sound crash. */
    public static void playOpen() {
        var sm = Minecraft.getInstance().getSoundManager();
        sm.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f, 0.5f));
        sm.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.35f));
    }

    /** Hover tick, globally rate-limited so grid sweeps don't machine-gun. */
    public static void playHover() {
        long now = System.currentTimeMillis();
        if (now - lastHoverSound < 90) {
            return;
        }
        lastHoverSound = now;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.7f, 0.25f));
    }

    public static void playTab() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.3f, 0.6f));
    }

    /** 0..1 over ANIM_SCREEN_CLOSE - the closing side of screen transitions. Every screen (and LoadoutScreen's own
     *  retract) uses this same duration so chrome, lines, buttons and cards all finish fading together. */
    public static float closeProgress(long closingAtMillis) {
        if (closingAtMillis <= 0) {
            return 0f;
        }
        float t = Mth.clamp((System.currentTimeMillis() - closingAtMillis) / (float) closeMillis(), 0f, 1f);
        // Ease-OUT, same family as the cards' retract: lines, labels and buttons start fading immediately and finish with
        // the cards. The old ease-in (t*t) kept them at ~full brightness for half the close, so they were the LAST things
        // left on screen after the cards had already gone.
        return easeOutCubic(t);
    }

    /** One full-screen veil draw: the whole in/out animation system. Opening fades it 1->0 over
     *  the open ramp, closing fades 0->1, tab switches flash it briefly over the content region —
     *  a single fill masks every pop-in, no per-element alpha plumbing, hit-tests untouched. */
    public static void veil(GuiGraphics graphics, int x, int y, int width, int height, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        // Full-screen transition veils (x=0,y=0, spanning the whole viewport) used to wash a dark
        // fade over the ENTIRE UI on every screen open/close/switch — background AND buttons alike
        // — which read as an unwanted flash rather than a clean transition. Disabled outright:
        // screens still switch on the same timer (transitionRender's own close/open logic is
        // untouched), there's just no dark veil drawn while it happens. Localized flashes (e.g. a
        // list refreshing after an edit) always pass a sub-region rect, so they're unaffected.
        Minecraft mc = Minecraft.getInstance();
        if (x == 0 && y == 0 && width == mc.getWindow().getGuiScaledWidth() && height == mc.getWindow().getGuiScaledHeight()) {
            return;
        }
        String style;
        try {
            style = dev.l41nx.mwloadout.client.ClientCfg.TRANSITION_STYLE.get();
        } catch (Exception e) {
            style = "fade"; // config not loaded yet (early screens) — never crash over polish
        }
        // Always a soft fade now — the old instant/wipe styles were jarring. A gentle eased
        // alpha reads as a comfortable dissolve rather than a hard sweep.
        float eased = alpha * alpha * (3f - 2f * alpha); // smoothstep
        MWStyle.fillF(graphics, x, y, x + width, y + height, withAlpha(0xFF08080A, Math.min(1f, eased)));
    }

    /** Shared open progress of the current screen (0..1), updated each screenChrome call so
     *  widgets can fade/slide in together with the backdrop. */
    public static float chromeOpen = 1f;

    public static float openProgress(long openedAtMillis) {
        float t = Mth.clamp((System.currentTimeMillis() - openedAtMillis) / (float) ANIM_SCREEN_OPEN, 0f, 1f);
        return easeOutCubic(t);
    }

    /**
     * A layered "frosted" backdrop, a comfortable stand-in for a true gaussian blur (which needs a
     * post-process shader not present in 1.20.1 by default). Three cheap passes: a base dim, a soft
     * top→bottom gradient for depth, and a vignette that keeps the middle — where the panels sit —
     * a touch clearer than the edges. Reads as a calm, dimmed-and-softened scene rather than a flat
     * black wall.
     */
    public static void frostedBackdrop(GuiGraphics graphics, int width, int height, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        // Base dim.
        MWStyle.fillF(graphics, 0, 0, width, height, withAlpha(BACKDROP, alpha * 0.85f));
        // Subtle top→bottom depth gradient (kept — it reads as a clean darkened scene).
        MWStyle.gradF(graphics, 0, 0, width, height,
                withAlpha(0xFF05070A, alpha * 0.30f), withAlpha(0xFF0A0D12, alpha * 0.10f));
    }

    public static void screenChrome(GuiGraphics graphics, int width, String title, long openedAtMillis) {
        beginFrame();
        long nowMs = System.currentTimeMillis();
        if (closeStart > 0 && nowMs - closeStart > ANIM_SCREEN_CLOSE + 300) {
            closeStart = 0; // stale (closed to the world long ago) — never leave a screen stuck invisible
        }
        float open = openProgress(openedAtMillis);
        boolean continuing = nowMs - handoffAt < ANIM_SCREEN_OPEN + 80;
        if (continuing) {
            open = 0.6f + 0.4f * open; // arrives at 60 %, not 0: the chrome never blinks out between two loadout screens
        }
        // Closing: widgets/title fade out together instead of vanishing on the swap frame.
        // Towards another loadout screen they only dim to 60 % (the next screen takes over from there); towards the game they fade fully.
        float closeMul = closeStart > 0 ? 1f - closeProgress(closeStart) * (closeToMw ? 0.4f : 1f) : 1f;
        chromeOpen = open * closeMul;
        localAlpha = 1f; // a group that threw mid-draw must never leave the next frame faded
        chromeStamp = System.currentTimeMillis();
        open = chromeOpen;
        // Backdrop honours the player's opacity setting. FLICKER FIX: previously every screen
        // faded its backdrop 0->1 on open, so closing one loadout screen into another dropped the
        // dim to nothing for a frame and brought it back (the once-per-close flash). Now the
        // backdrop is continuous across a hand-off (no fade-in, no fade-out) and only fades
        // in from / out to the world when entering from / leaving to the actual game.
        boolean handedOff = continuing;
        float backdropIn = handedOff ? 1f : openProgress(openedAtMillis);
        // the dim/blur itself keeps a plain linear fade out (only the UI elements use the eased curve)
        float closeLinear = closeStart > 0 ? 1f - Mth.clamp((nowMs - closeStart) / (float) closeMillis(), 0f, 1f) : 1f;
        float backdropOut = (closeStart > 0 && !closeToMw) ? closeLinear : 1f;
        float backdropAlpha = dev.l41nx.mwloadout.client.ClientCfg.backdropOpacity() * backdropIn * backdropOut;
        // Real gaussian blur of the world behind the menu (guarded; falls back to the dim if the
        // shader can't load). Then the frosted dim layers on top for contrast.
        dev.l41nx.mwloadout.client.BlurHandler.apply(net.minecraft.client.Minecraft.getInstance().getFrameTime());
        frostedBackdrop(graphics, width, graphics.guiHeight(), backdropAlpha);
        MWStyle.strF(graphics, Minecraft.getInstance().font, title.toUpperCase(), 10, 14, withAlpha(TEXT, open), false);
        MWStyle.fillF(graphics, 10, 26, width - 10, 27, border());
        MWStyle.fillF(graphics, 10, 26, 10 + 40, 27, mulAlpha(signal(), open));
    }

    // ------------------------------------------------------------------ angular plate kit
    // One silhouette for the whole mod: a rectangle with two OPPOSITE corners (top-right, bottom-left) cut at 45 degrees, like a stamped
    // data plate. No blue washes: depth is a hard offset shadow, selection is ONE treatment (gradient halo + 1 px edge + a strip on the cut silhouette).

    /** Tab / section switch flashes and the staggered row reveal. Off by default: they read as lag, not polish. */
    public static boolean sectionAnim() {
        return dev.l41nx.mwloadout.client.ClientCfg.sectionAnim();
    }

    private static long angularStamp;
    private static boolean angularValue = true;

    /** Read from the config at most every 250 ms: it is asked for dozens of times per frame (every panel, tile, button, wash). */
    public static boolean angular() {
        long now = System.currentTimeMillis();
        if (now - angularStamp > 250L) {
            angularStamp = now;
            angularValue = dev.l41nx.mwloadout.client.ClientCfg.angularUi();
        }
        return angularValue;
    }

    /** Cut size that never eats a small element: at most a quarter of the short side. */
    public static int cut(int width, int height, int wanted) {
        return Math.max(0, Math.min(wanted, Math.min(width, height) / 4));
    }

    private static void rect(GuiGraphics g, boolean fade, int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        if (fade) {
            fillF(g, x1, y1, x2, y2, color);
        } else {
            g.fill(x1, y1, x2, y2, color);
        }
    }

    // ---- low-level emitters: everything goes into vanilla's own gui buffer (same RenderType as GuiGraphics.fill), so draw order, scissor and
    // ---- pose scale behave exactly like normal fills. A high-level call emits ALL its geometry, then flushes once.

    private static void vtx(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, float x, float y, int argb) {
        vc.vertex(m, x, y, 0f).color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f).endVertex();
    }

    /** One triangle as a degenerate quad, wound the way vanilla's fill() winds its quads (face culling stays valid). */
    private static void tri(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m,
                            float ax, float ay, int ca, float bx, float by, int cb, float cx, float cy, int cc) {
        float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (cross == 0f || Float.isNaN(cross)) {
            return;
        }
        if (cross > 0f) { // wrong way round: swap b and c
            float tx = bx, ty = by;
            int tc = cb;
            bx = cx; by = cy; cb = cc;
            cx = tx; cy = ty; cc = tc;
        }
        vtx(vc, m, ax, ay, ca);
        vtx(vc, m, bx, by, cb);
        vtx(vc, m, cx, cy, cc);
        vtx(vc, m, cx, cy, cc);
    }

    private static void quadRect(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, float x1, float y1, float x2, float y2, int col) {
        if (x2 <= x1 || y2 <= y1 || (col >>> 24) == 0) {
            return;
        }
        tri(vc, m, x1, y1, col, x1, y2, col, x2, y2, col);
        tri(vc, m, x1, y1, col, x2, y2, col, x2, y1, col);
    }

    private static void fan(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, float[] p, int col) {
        if ((col >>> 24) == 0) {
            return;
        }
        for (int i = 1; i < p.length / 2 - 1; i++) {
            tri(vc, m, p[0], p[1], col, p[2 * i], p[2 * i + 1], col, p[2 * i + 2], p[2 * i + 3], col);
        }
    }

    /** The plate outline, clockwise from the top-left: the top-right and bottom-left corners are cut by c. */
    private static float[] hex(float x, float y, float w, float h, float c) {
        return new float[]{x, y, x + w - c, y, x + w, y + c, x + w, y + h, x + c, y + h, x, y + h - c};
    }

    private static int col(boolean fade, int color) {
        return fade ? fa(color) : color;
    }

    private static void fillI(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, int x, int y, int w, int h, int c, int color) {
        if (c <= 0) {
            quadRect(vc, m, x, y, x + w, y + h, color);
        } else {
            fan(vc, m, hex(x, y, w, h, c), color);
        }
    }

    private static void edgeI(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, int x, int y, int w, int h, int c, int color) {
        if (c <= 0) {
            quadRect(vc, m, x, y, x + w, y + 1, color);
            quadRect(vc, m, x, y + h - 1, x + w, y + h, color);
            quadRect(vc, m, x, y + 1, x + 1, y + h - 1, color);
            quadRect(vc, m, x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        quadRect(vc, m, x, y, x + w - c, y + 1, color);                 // top
        quadRect(vc, m, x + c, y + h - 1, x + w, y + h, color);         // bottom
        quadRect(vc, m, x, y + 1, x + 1, y + h - c, color);             // left
        quadRect(vc, m, x + w - 1, y + c, x + w, y + h - 1, color);     // right
        float k = 0.70710678f; // 1 px measured across a 45 degree line, towards the inside
        float[] d1 = {x + w - c, y, x + w, y + c, x + w - k, y + c + k, x + w - c - k, y + k};
        float[] d2 = {x + c, y + h, x, y + h - c, x + k, y + h - c - k, x + c + k, y + h - k};
        for (float[] d : new float[][]{d1, d2}) {
            tri(vc, m, d[0], d[1], color, d[2], d[3], color, d[4], d[5], color);
            tri(vc, m, d[0], d[1], color, d[4], d[5], color, d[6], d[7], color);
        }
    }

    /** Left accent strip of width sw that follows the plate: its bottom end rides the cut corner exactly instead of poking out of it. */
    private static void stripI(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, int x, int y, int h, int c, int sw, int color) {
        if (c <= 0) {
            quadRect(vc, m, x, y, x + sw, y + h, color);
            return;
        }
        float[] p = {x, y, x + sw, y, x + sw, y + h - c + sw, x, y + h - c};
        fan(vc, m, p, color);
    }

    /** Soft halo: a ring around the plate whose alpha fades from {@code a} at the edge to 0 over r px (per-vertex colours = a real gradient,
     *  not stacked outlines). Corner offsets use mitre joins so the cut corners keep their shape. */
    private static void glowI(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, int x, int y, int w, int h, int c, int rgb, float a, float r) {
        if (a <= 0.01f) {
            return;
        }
        float[] p = hex(x, y, w, h, c);
        int n = p.length / 2;
        float[] q = new float[p.length];
        for (int i = 0; i < n; i++) {
            int pv = (i + n - 1) % n, nx = (i + 1) % n;
            float d1x = p[2 * i] - p[2 * pv], d1y = p[2 * i + 1] - p[2 * pv + 1];
            float d2x = p[2 * nx] - p[2 * i], d2y = p[2 * nx + 1] - p[2 * i + 1];
            float l1 = (float) Math.sqrt(d1x * d1x + d1y * d1y), l2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
            if (l1 == 0f || l2 == 0f) {
                q[2 * i] = p[2 * i];
                q[2 * i + 1] = p[2 * i + 1];
                continue;
            }
            float n1x = d1y / l1, n1y = -d1x / l1, n2x = d2y / l2, n2y = -d2x / l2; // outward normals (clockwise polygon, y down)
            float k = 1f + n1x * n2x + n1y * n2y;
            q[2 * i] = p[2 * i] + (n1x + n2x) * r / k;
            q[2 * i + 1] = p[2 * i + 1] + (n1y + n2y) * r / k;
        }
        int inner = withAlpha(rgb, Mth.clamp(a, 0f, 1f)), outer = withAlpha(rgb, 0f);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            tri(vc, m, p[2 * i], p[2 * i + 1], inner, p[2 * j], p[2 * j + 1], inner, q[2 * j], q[2 * j + 1], outer);
            tri(vc, m, p[2 * i], p[2 * i + 1], inner, q[2 * j], q[2 * j + 1], outer, q[2 * i], q[2 * i + 1], outer);
        }
    }

    // ---- public plate API (one buffer acquisition and one flush per call)

    /** Filled plate with the top-right and bottom-left corners cut by {@code c} px. */
    public static void plateFill(GuiGraphics g, boolean fade, int x, int y, int w, int h, int c, int color) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        fillI(vc, g.pose().last().pose(), x, y, w, h, c, col(fade, color));
        g.flush();
    }

    /** 1 px outline that follows the cut corners. */
    public static void plateEdge(GuiGraphics g, boolean fade, int x, int y, int w, int h, int c, int color) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        edgeI(vc, g.pose().last().pose(), x, y, w, h, c, col(fade, color));
        g.flush();
    }

    /** Hard offset shadow (solid, no blur). */
    public static void plateShadow(GuiGraphics g, boolean fade, int x, int y, int w, int h, int c, int offset, int color) {
        plateFill(g, fade, x + offset, y + offset, w, h, c, color);
    }

    /** Polygon in any colour, same buffer rules (used for the toast block and the arrows). */
    public static void poly(GuiGraphics g, int argb, float... pts) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        fan(vc, g.pose().last().pose(), pts, argb);
        g.flush();
    }

    /** Right-pointing pointer (replaces the bitmap glyph, which looked chunky): a clean triangle centred on cy, s = half height. */
    public static void arrowRight(GuiGraphics g, float x, float cy, float s, int color) {
        poly(g, fa(color), x, cy - s, x + s * 1.15f, cy, x, cy + s);
    }

    /** The ONE selection treatment: halo (gradient), a single 1 px edge and the accent strip, all on the same cut silhouette. */
    public static void selection(GuiGraphics g, int x, int y, int w, int h, int c, int rgb, float strength) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        selectionI(vc, g.pose().last().pose(), x, y, w, h, c, rgb, strength);
        g.flush();
    }

    private static void selectionI(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, int x, int y, int w, int h, int c, int rgb, float strength) {
        glowI(vc, m, x, y, w, h, c, rgb & 0xFFFFFF, 0.34f * strength * uiAlpha(), 3.5f);
        edgeI(vc, m, x, y, w, h, c, fa(withAlpha(rgb, 0.92f)));
        stripI(vc, m, x, y, h, c, 3, fa(rgb | 0xFF000000));
    }

    /** Hover affordance on a plate: a thin accent strip on the left edge, faded in with the hover amount. */
    public static void hoverStrip(GuiGraphics g, int x, int y, int h, int c, int rgb, float amount) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        stripI(vc, g.pose().last().pose(), x, y, h, c, 2, fa(withAlpha(rgb, Mth.clamp(amount, 0f, 1f))));
        g.flush();
    }

    /** Translucent wash that fits the plate shape (angular) or the plain rectangle (classic): use instead of fillF over a tile / row / button. */
    public static void wash(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (angular()) {
            plateFill(g, true, x, y, w, h, cut(w, h, 4), color);
        } else {
            fillF(g, x, y, x + w, y + h, color);
        }
    }

    /** Plate with ONE cut corner: the outer top one (top-right when {@code topRight}, else top-left). Raw ARGB, no screen fade (HUD use). */
    public static void cutFill(GuiGraphics g, int x, int y, int w, int h, int c, boolean topRight, int color) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        float[] p = topRight
                ? new float[]{x, y, x + w - c, y, x + w, y + c, x + w, y + h, x, y + h}
                : new float[]{x + c, y, x + w, y, x + w, y + h, x, y + h, x, y + c};
        fan(vc, g.pose().last().pose(), p, color);
        g.flush();
    }

    /** 1 px outline of the one-corner plate above. */
    public static void cutEdge(GuiGraphics g, int x, int y, int w, int h, int c, boolean topRight, int color) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        org.joml.Matrix4f m = g.pose().last().pose();
        float k = 0.70710678f;
        quadRect(vc, m, x, y + h - 1, x + w, y + h, color); // bottom
        float[] d;
        if (topRight) {
            quadRect(vc, m, x, y, x + w - c, y + 1, color);
            quadRect(vc, m, x, y + 1, x + 1, y + h - 1, color);
            quadRect(vc, m, x + w - 1, y + c, x + w, y + h - 1, color);
            d = new float[]{x + w - c, y, x + w, y + c, x + w - k, y + c + k, x + w - c - k, y + k};
        } else {
            quadRect(vc, m, x + c, y, x + w, y + 1, color);
            quadRect(vc, m, x, y + c, x + 1, y + h - 1, color);
            quadRect(vc, m, x + w - 1, y + 1, x + w, y + h - 1, color);
            d = new float[]{x + c, y, x, y + c, x + k, y + c + k, x + c + k, y + k};
        }
        tri(vc, m, d[0], d[1], color, d[2], d[3], color, d[4], d[5], color);
        tri(vc, m, d[0], d[1], color, d[4], d[5], color, d[6], d[7], color);
        g.flush();
    }

    /** Horizontal two-colour gradient (per-vertex colours). Raw ARGB. */
    public static void gradientH(GuiGraphics g, float x, float y, float w, float h, int cLeft, int cRight) {
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        org.joml.Matrix4f m = g.pose().last().pose();
        tri(vc, m, x, y, cLeft, x, y + h, cLeft, x + w, y + h, cRight);
        tri(vc, m, x, y, cLeft, x + w, y + h, cRight, x + w, y, cRight);
        g.flush();
    }

    /** A ring segment (donut slice) between two radii and two angles in degrees, clockwise from the top. One buffer, one flush; raw ARGB. */
    public static void ringSector(GuiGraphics g, float cx, float cy, float rIn, float rOut, float a0, float a1, int argb) {
        if ((argb >>> 24) == 0 || a1 <= a0) {
            return;
        }
        com.mojang.blaze3d.vertex.VertexConsumer vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        org.joml.Matrix4f m = g.pose().last().pose();
        int steps = Math.max(1, (int) Math.ceil((a1 - a0) / 6f));
        for (int i = 0; i < steps; i++) {
            double t0 = Math.toRadians(a0 + (a1 - a0) * i / steps), t1 = Math.toRadians(a0 + (a1 - a0) * (i + 1) / steps);
            float ix0 = cx + rIn * (float) Math.sin(t0), iy0 = cy - rIn * (float) Math.cos(t0);
            float ox0 = cx + rOut * (float) Math.sin(t0), oy0 = cy - rOut * (float) Math.cos(t0);
            float ix1 = cx + rIn * (float) Math.sin(t1), iy1 = cy - rIn * (float) Math.cos(t1);
            float ox1 = cx + rOut * (float) Math.sin(t1), oy1 = cy - rOut * (float) Math.cos(t1);
            tri(vc, m, ix0, iy0, argb, ox0, oy0, argb, ox1, oy1, argb);
            tri(vc, m, ix0, iy0, argb, ox1, oy1, argb, ix1, iy1, argb);
        }
        g.flush();
    }

    /** Slow 0..1 sine used for ambient glow (about a 3 s period, eased - it breathes, it never blinks). */
    public static float softPulse() {
        return 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 480.0);
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        if (angular()) {
            int c = cut(width, height, 6);
            // shadow + fill + edge: one buffer acquisition, one flush (it used to be three)
            com.mojang.blaze3d.vertex.VertexConsumer vc = graphics.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
            org.joml.Matrix4f m = graphics.pose().last().pose();
            fillI(vc, m, x + 2, y + 2, width, height, c, fa(0x66000000));
            fillI(vc, m, x, y, width, height, c, fa(PANEL));
            edgeI(vc, m, x, y, width, height, c, fa(border()));
            graphics.flush();
            return;
        }
        MWStyle.fillF(graphics, x, y, x + width, y + height, PANEL);
        hairline(graphics, x, y, width, height, border());
    }

    /** MW19 card: charcoal fill; hovering paints the light-blue mesh; selection swaps the border
     *  to near-white with a left accent bar. */
    public static void tile(GuiGraphics graphics, int x, int y, int width, int height, float hover, boolean selected) {
        if (angular()) {
            int c = cut(width, height, 4);
            com.mojang.blaze3d.vertex.VertexConsumer vc = graphics.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
            org.joml.Matrix4f m = graphics.pose().last().pose();
            fillI(vc, m, x, y, width, height, c, fa(blend(PANEL_RAISED, PANEL_HOVER, hover)));
            if (selected) {
                selectionI(vc, m, x, y, width, height, c, signal(), 0.55f + 0.45f * softPulse()); // halo + one edge + strip, one silhouette
            } else {
                edgeI(vc, m, x, y, width, height, c, fa(blend(border(), borderActive(), hover * 0.5f)));
                if (hover > 0.02f) {
                    stripI(vc, m, x, y, height, c, 2, fa(withAlpha(signal(), Mth.clamp(hover, 0f, 1f))));
                }
            }
            graphics.flush(); // one flush for the whole tile
            return;
        }
        MWStyle.fillF(graphics, x, y, x + width, y + height, blend(PANEL_RAISED, PANEL_HOVER, hover));
        if (hover > 0.02f) {
            // Only a soft blue wash now (no grid mesh) — a clean dissolve-style hover.
            MWStyle.fillF(graphics, x + 1, y + 1, x + width - 1, y + height - 1, withAlpha(hoverTint(), 0.12f * hover));
        }
        hairline(graphics, x, y, width, height, selected ? borderActive() : blend(border(), borderActive(), hover * 0.45f));
        if (selected) {
            MWStyle.fillF(graphics, x, y, x + 2, y + height, fa(signal()));
        }
    }

    public static void hairline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        MWStyle.fillF(graphics, x, y, x + width, y + 1, color);
        MWStyle.fillF(graphics, x, y + height - 1, x + width, y + height, color);
        MWStyle.fillF(graphics, x, y, x + 1, y + height, color);
        MWStyle.fillF(graphics, x + width - 1, y, x + width, y + height, color);
    }

    /** ONE clean selection highlight (use instead of stacking several outlines): a soft halo that fades out over 3 px, then a single crisp
     *  1 px edge in the colour. {@code strength} 0..1 drives the halo (pass the pulse for a breathing glow). */
    public static void glowOutline(GuiGraphics graphics, int x, int y, int width, int height, int color, float strength) {
        if (angular()) {
            selection(graphics, x, y, width, height, cut(width, height, 4), color, strength);
            return;
        }
        for (int k = 3; k >= 1; k--) {
            int ring = withAlpha(color, 0.22f * strength * (4 - k) / 3f);
            fillF(graphics, x - k, y - k, x + width + k, y - k + 1, ring);
            fillF(graphics, x - k, y + height + k - 1, x + width + k, y + height + k, ring);
            fillF(graphics, x - k, y - k + 1, x - k + 1, y + height + k - 1, ring);
            fillF(graphics, x + width + k - 1, y - k + 1, x + width + k, y + height + k - 1, ring);
        }
        hairline(graphics, x, y, width, height, withAlpha(color, 0.78f + 0.22f * strength));
    }

    public static void header(GuiGraphics graphics, int x, int y, String text) {
        header(graphics, x, y, 120, text);
    }

    /** Section header: the label in bold, a short gold-orange bar under it, and a faint rule carrying on to the right edge of the block. */
    public static void header(GuiGraphics graphics, int x, int y, int width, String text) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        graphics.drawString(font, net.minecraft.network.chat.Component.literal(text.toUpperCase(java.util.Locale.ROOT))
                .withStyle(net.minecraft.ChatFormatting.BOLD), x, y, fa(TEXT), false);
        // one 1 px rule, the accent segment sits ON it: the old 2 px bar + 1 px rule made a visible pixel step at the join
        MWStyle.fillF(graphics, x, y + 11, x + width, y + 12, border());
        MWStyle.fillF(graphics, x, y + 11, x + 24, y + 12, signal());
    }

    /** A bar-and-rule divider for a block whose heading is drawn by hand. */
    public static void sectionRule(GuiGraphics graphics, int x, int y, int width) {
        MWStyle.fillF(graphics, x, y + 1, x + width, y + 2, border());
        MWStyle.fillF(graphics, x, y + 1, x + 24, y + 2, signal());
    }

    /** Multiplies a colour's alpha by the current screen fade (chromeOpen: 0 -> 1 on open, 1 -> 0 on close), so static
     *  lines / labels / bars animate in and out together with the rest of the chrome instead of popping. */
    public static int fa(int color) {
        return mulAlpha(color, uiAlpha());
    }

    /** Alpha multiply that KEEPS the colour's own alpha (withAlpha replaces it). */
    public static int mulAlpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * Mth.clamp(factor, 0f, 1f));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** Fading text. Vanilla draws text whose alpha is below ~4 fully OPAQUE, so near-invisible text is skipped. */
    public static void text(GuiGraphics g, net.minecraft.client.gui.Font font, String s, int x, int y, int color) {
        strF(g, font, s, x, y, color, false);
    }

    public static void textCentered(GuiGraphics g, net.minecraft.client.gui.Font font, String s, int cx, int y, int color) {
        centerF(g, font, s, cx, y, color);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Global UI fade. Every fill / gradient / text call in the GUI package goes through these, so ANY element - not just
    // the ones somebody remembered to special-case - fades with the screen open/close (uiAlpha()). When the screen is
    // fully open (alpha ~1) they are straight pass-throughs.
    // ---------------------------------------------------------------------------------------------------------------

    private static long chromeStamp = 0L;

    /** Current screen fade, 0..1. Falls back to 1 when no screen has refreshed it recently (HUD, stale state). */
    public static float uiAlpha() {
        float base = System.currentTimeMillis() - chromeStamp > 250L ? 1f : chromeOpen;
        return base * localAlpha;
    }

    private static float localAlpha = 1f;

    public static float uiAlphaLocal() {
        return localAlpha;
    }

    /** Extra alpha multiplier for ONE group of elements (e.g. the gunsmith hub cards while the select view fades in).
     *  Always pair with localAlpha(1f) afterwards. */
    public static void localAlpha(float a) {
        localAlpha = Mth.clamp(a, 0f, 1f);
    }

    private static int ua(int color) {
        return mulAlpha(color, uiAlpha());
    }

    public static void fillF(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, uiAlpha() >= 0.999f ? color : ua(color));
    }

    public static void fillF(GuiGraphics g, int x1, int y1, int x2, int y2, int z, int color) {
        g.fill(x1, y1, x2, y2, z, uiAlpha() >= 0.999f ? color : ua(color));
    }

    public static void gradF(GuiGraphics g, int x1, int y1, int x2, int y2, int c1, int c2) {
        if (uiAlpha() >= 0.999f) {
            g.fillGradient(x1, y1, x2, y2, c1, c2);
        } else {
            g.fillGradient(x1, y1, x2, y2, ua(c1), ua(c2));
        }
    }

    public static void gradF(GuiGraphics g, int x1, int y1, int x2, int y2, int z, int c1, int c2) {
        if (uiAlpha() >= 0.999f) {
            g.fillGradient(x1, y1, x2, y2, z, c1, c2);
        } else {
            g.fillGradient(x1, y1, x2, y2, z, ua(c1), ua(c2));
        }
    }

    /** Text colour after the fade; -1 means "too transparent, skip" (vanilla would draw alpha < 4 fully opaque). */
    private static int textColor(int color) {
        float a = uiAlpha();
        if (a >= 0.999f) {
            return color;
        }
        if ((color >>> 24) == 0) {
            color |= 0xFF000000; // vanilla treats a missing alpha as opaque - do the same before scaling it
        }
        int c = mulAlpha(color, a);
        return (c >>> 24) < 6 ? -1 : c;
    }

    public static int strF(GuiGraphics g, net.minecraft.client.gui.Font f, String t, int x, int y, int color, boolean shadow) {
        int c = textColor(color);
        return c == -1 ? x : g.drawString(f, t, x, y, c, shadow);
    }

    public static int strF(GuiGraphics g, net.minecraft.client.gui.Font f, String t, int x, int y, int color) {
        return strF(g, f, t, x, y, color, true);
    }

    public static int strF(GuiGraphics g, net.minecraft.client.gui.Font f, net.minecraft.network.chat.Component t, int x, int y, int color, boolean shadow) {
        int c = textColor(color);
        return c == -1 ? x : g.drawString(f, t, x, y, c, shadow);
    }

    public static int strF(GuiGraphics g, net.minecraft.client.gui.Font f, net.minecraft.network.chat.Component t, int x, int y, int color) {
        return strF(g, f, t, x, y, color, true);
    }

    public static int strF(GuiGraphics g, net.minecraft.client.gui.Font f, net.minecraft.util.FormattedCharSequence t, int x, int y, int color, boolean shadow) {
        int c = textColor(color);
        return c == -1 ? x : g.drawString(f, t, x, y, c, shadow);
    }

    public static int strF(GuiGraphics g, net.minecraft.client.gui.Font f, net.minecraft.util.FormattedCharSequence t, int x, int y, int color) {
        return strF(g, f, t, x, y, color, true);
    }

    public static void centerF(GuiGraphics g, net.minecraft.client.gui.Font f, String t, int cx, int y, int color) {
        int c = textColor(color);
        if (c != -1) {
            g.drawCenteredString(f, t, cx, y, c);
        }
    }

    public static void centerF(GuiGraphics g, net.minecraft.client.gui.Font f, net.minecraft.network.chat.Component t, int cx, int y, int color) {
        int c = textColor(color);
        if (c != -1) {
            g.drawCenteredString(f, t, cx, y, c);
        }
    }

    public static void centerF(GuiGraphics g, net.minecraft.client.gui.Font f, net.minecraft.util.FormattedCharSequence t, int cx, int y, int color) {
        int c = textColor(color);
        if (c != -1) {
            g.drawCenteredString(f, t, cx, y, c);
        }
    }

    /** Same, for the "draw width/height + region" blit overload. */
    public static void blitF(GuiGraphics g, net.minecraft.resources.ResourceLocation tex, int x, int y, int w, int h,
                             float u, float v, int uw, int vh, int texW, int texH) {
        float a = uiAlpha();
        if (a < 0.999f) {
            g.setColor(1f, 1f, 1f, a);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        }
        g.blit(tex, x, y, w, h, u, v, uw, vh, texW, texH);
        if (a < 0.999f) {
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    /** Textured quad (icons) tinted with the screen fade. */
    public static void blitF(GuiGraphics g, net.minecraft.resources.ResourceLocation tex, int x, int y, float u, float v,
                             int w, int h, int texW, int texH) {
        float a = uiAlpha();
        if (a < 0.999f) {
            g.setColor(1f, 1f, 1f, a);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        }
        g.blit(tex, x, y, u, v, w, h, texW, texH);
        if (a < 0.999f) {
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    // -------------------------------------------------------------- animation toolkit
    /** Frame-rate-independent smoothing: approaches target by `speed` per 50ms tick regardless
     *  of FPS. dtTicks is partialTick-derived time. The pow() keeps a 240fps client and a 30fps
     *  client animating at the same wall-clock rate — the trick lifted from a well-animated ref. */
    public static float approach(float current, float target, float speed, float dtTicks) {
        float factor = 1f - (float) Math.pow(1f - Mth.clamp(speed, 0f, 1f), Math.max(0.0001f, dtTicks));
        return current + (target - current) * factor;
    }

    public static float easeOutCubic(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float u = 1f - t; // u*u*u instead of Math.pow: called dozens of times per frame by the cascades
        return 1f - u * u * u;
    }

    public static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.2f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }

    public static float easeInOutCubic(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    public static float easeHover(float current, boolean hovered, float partialTick) {
        // Driven by REAL wall-clock frame time. (partialTick is only the fraction through the
        // current 50ms game tick — it snaps back to ~0 every tick, so easing keyed off it
        // stuttered at uneven speed; frameDt() is the actual time since the previous frame.)
        return approach(current, hovered ? 1f : 0f, 0.30f, frameDt());
    }

    // ---- per-frame clock + screen hand-off (smooth, flicker-free open/close) ----
    private static long lastFrameNanos;
    private static float frameDtTicks = 1f;
    private static long closeStart;     // when the CURRENT screen began its close fade (0 = not closing)
    private static boolean closeToMw;   // closing INTO another loadout screen (vs back to the world)
    private static long handoffAt;      // when a loadout screen last handed over to another one

    /** For screens that draw their own chrome and never call screenChrome() (admin panel, item
     *  browser): tick the frame clock AND pin chromeOpen to 1. Without this the buttons keep whatever
     *  value the PREVIOUS screen left behind - after a fade-out that is ~0, i.e. buttons drawn almost
     *  invisible yet still hoverable and clickable. */
    public static void plainChrome() {
        beginFrame();
        chromeOpen = 1f;
        localAlpha = 1f;
        chromeStamp = System.currentTimeMillis();
    }

    /** Called once per frame by screenChrome(): measures real elapsed time in 50ms game ticks. */
    public static void beginFrame() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            frameDtTicks = Mth.clamp((now - lastFrameNanos) / 50_000_000f, 0.02f, 2.5f);
        }
        lastFrameNanos = now;
    }

    /** Thin MW-style tile: translucent fill, 1px frame that DRAWS ON as `intro` goes 0 -> 1 (top/left grow from one
     *  corner, bottom/right from the opposite), and on hover accent corner brackets plus an underline that grows
     *  left to right. `selected` adds a 1px accent edge. Much lighter than tile() - no heavy slab. */
    public static void thinTile(net.minecraft.client.gui.GuiGraphics g, int x, int y, int w, int h,
                                float hover, boolean selected, float intro) {
        if (intro <= 0.01f) {
            return;
        }
        float a = Mth.clamp(intro * 1.3f, 0f, 1f);
        float lift = Mth.clamp(hover, 0f, 1f);
        MWStyle.fillF(g, x, y, x + w, y + h, withAlpha(blend(PANEL, PANEL_RAISED, lift * 0.7f), (0.50f + 0.16f * lift) * a));
        int frame = withAlpha(blend(border(), borderActive(), lift * 0.7f), a);
        int tw = Math.round(w * intro);
        int th = Math.round(h * intro);
        MWStyle.fillF(g, x, y, x + tw, y + 1, frame);
        MWStyle.fillF(g, x + w - tw, y + h - 1, x + w, y + h, frame);
        MWStyle.fillF(g, x, y, x + 1, y + th, frame);
        MWStyle.fillF(g, x + w - 1, y + h - th, x + w, y + h, frame);
        if (intro < 0.99f) {
            return;
        }
        if (lift > 0.02f) {
            int accent = withAlpha(accent(), lift);
            int len = 4;
            MWStyle.fillF(g, x, y, x + len, y + 1, accent);
            MWStyle.fillF(g, x, y, x + 1, y + len, accent);
            MWStyle.fillF(g, x + w - len, y, x + w, y + 1, accent);
            MWStyle.fillF(g, x + w - 1, y, x + w, y + len, accent);
            MWStyle.fillF(g, x, y + h - 1, x + len, y + h, accent);
            MWStyle.fillF(g, x, y + h - len, x + 1, y + h, accent);
            MWStyle.fillF(g, x + w - len, y + h - 1, x + w, y + h, accent);
            MWStyle.fillF(g, x + w - 1, y + h - len, x + w, y + h, accent);
            MWStyle.fillF(g, x, y + h - 1, x + Math.round(w * lift), y + h, accent);
        }
        if (selected) {
            MWStyle.fillF(g, x, y, x + 1, y + h, accent());
        }
    }

    /** Selection feedback: a soft accent fill sweeps left to right across the entry and fades, with a bright
     *  leading edge. Replaces the bouncy "pop" - no scaling, nothing jumps. */
    public static void sweepFill(net.minecraft.client.gui.GuiGraphics g, int x, int y, int w, int h,
                                 long startMs, int durationMs, int color) {
        if (startMs <= 0) {
            return;
        }
        float t = Mth.clamp((System.currentTimeMillis() - startMs) / (float) durationMs, 0f, 1f);
        if (t >= 1f) {
            return;
        }
        float e = easeOutCubic(t);
        int fillW = Math.round(w * e);
        MWStyle.fillF(g, x, y, x + fillW, y + h, withAlpha(color, 0.30f * (1f - t * t)));
        MWStyle.fillF(g, Math.max(x, x + fillW - 1), y, x + fillW, y + h, withAlpha(0xFFFFFFFF, 0.5f * (1f - t)));
    }

    /** Selection "pop": 1.0 -> ~1.2 -> 1.0 over `durationMs` after `startMs` (0 = never popped). Used when a perk /
     *  killstreak gets picked so the change reads instantly instead of the icon just swapping. */
    public static float pop(long startMs, int durationMs) {
        if (startMs <= 0) {
            return 1f;
        }
        float t = Mth.clamp((System.currentTimeMillis() - startMs) / (float) durationMs, 0f, 1f);
        return 1f + 0.22f * (float) Math.sin(t * Math.PI) * (1f - 0.35f * t);
    }

    /** 1 -> 0 fade over `durationMs` after `startMs` (a bright flash behind a freshly picked entry). */
    public static float flash(long startMs, int durationMs) {
        if (startMs <= 0) {
            return 0f;
        }
        float t = Mth.clamp((System.currentTimeMillis() - startMs) / (float) durationMs, 0f, 1f);
        return (1f - t) * (1f - t);
    }

    /** Truncate with an ellipsis (never a hard cut) so clipped names read as "longer than shown". */
    public static String ellipsize(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int ellipsisWidth = font.width("\u2026");
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsisWidth)) + "\u2026";
    }

    /** Text that fits is drawn as-is. Text that doesn't is ellipsized while idle and, when `active`
     *  (hovered / selected row), slides sideways inside its box: pause, glide to the end, pause, loop.
     *  Clipped with a scissor so it never spills into neighbouring columns. */
    public static void scrollText(net.minecraft.client.gui.GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                  String text, int x, int y, int maxWidth, int color, boolean active) {
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            MWStyle.strF(graphics, font, text, x, y, color, false);
            return;
        }
        if (!active) {
            MWStyle.strF(graphics, font, ellipsize(font, text, maxWidth), x, y, color, false);
            return;
        }
        int overflow = textWidth - maxWidth + 4;
        long pause = 650;
        long slide = (long) (overflow / 34f * 1000f); // ~34 px/s: readable, not frantic
        long period = pause + slide + pause;
        long t = System.currentTimeMillis() % period;
        float offset = t < pause ? 0f : t < pause + slide ? (t - pause) / (float) slide * overflow : overflow;
        graphics.enableScissor(x, y - 1, x + maxWidth, y + 10);
        MWStyle.strF(graphics, font, text, x - Math.round(offset), y, color, false);
        graphics.disableScissor();
    }

    public static float frameDt() {
        return frameDtTicks;
    }

    /** How long a closing screen lingers before the next one is set: short when the next one is another loadout screen. */
    public static long closeMillis() {
        return closeToMw ? 90L : ANIM_SCREEN_CLOSE;
    }

    /** A screen starts its close fade. toMwScreen = the next screen is another loadout screen. */
    public static void beginClose(boolean toMwScreen) {
        closeStart = System.currentTimeMillis();
        closeToMw = toMwScreen;
    }

    /** Right before setScreen(nextLoadoutScreen): the backdrop must carry over unbroken. */
    public static void handoff() {
        handoffAt = System.currentTimeMillis();
        closeStart = 0;
    }

    /** Every loadout screen calls this from init(). A screen that opens while another loadout screen drew a frame a moment ago is a HAND-OFF
     *  (tab switch inside the gunsmith, back to the parent, settings sub-page...) whichever way it was reached: detected here instead of
     *  relying on every setScreen call site remembering handoff(), which is what made the background drop out and fade back in. */
    public static void screenOpened() {
        long now = System.currentTimeMillis();
        if (now - chromeStamp < 400L) {
            handoffAt = now;
        }
        closeStart = 0;
        TR_CACHE.clear(); // language switch / resource reload shows up on the next screen open
    }

    public static int blend(int colorA, int colorB, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int a = (int) Mth.lerp(t, (colorA >>> 24) & 0xFF, (colorB >>> 24) & 0xFF);
        int r = (int) Mth.lerp(t, (colorA >>> 16) & 0xFF, (colorB >>> 16) & 0xFF);
        int g = (int) Mth.lerp(t, (colorA >>> 8) & 0xFF, (colorB >>> 8) & 0xFF);
        int b = (int) Mth.lerp(t, colorA & 0xFF, colorB & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, float alpha) {
        int a = (int) (Mth.clamp(alpha, 0f, 1f) * 255) << 24;
        return a | (color & 0xFFFFFF);
    }

    public static Button flatButton(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new FlatButton(x, y, width, height, label, onPress);
    }

    /** Small key plate ("ENTER", "ESC", "G") drawn at (x, y) = top-left of the text line. Returns its width. */
    public static int keyChip(GuiGraphics g, net.minecraft.client.gui.Font font, String key, int x, int y, int color) {
        int w = font.width(key) + 6;
        fillF(g, x, y - 2, x + w, y + 10, withAlpha(color, 0.16f)); // a quiet plate: a 1 px frame hugged the pixel font
        strF(g, font, key, x + 3, y, color, false);
        return w;
    }

    /** Locked / disabled surface: faint vertical hatching over the face (cheap: one 1 px column every 4 px). */
    public static void hatch(GuiGraphics g, int x, int y, int w, int h, int color) {
        for (int dx = 0; dx < w; dx += 4) {
            fillF(g, x + dx, y, x + dx + 1, y + h, color);
        }
    }

    public static final class FlatButton extends Button {
        private static final int DARK_TEXT = 0xFF101114;
        private static final int HOVER_FILL = 0xF0E8EAEC; // light slab: hover = invert, no glow
        private float hover;
        private boolean accentStyle;
        private boolean primaryStyle;
        private String keyHint = "";
        private long flashAt;

        private FlatButton(int x, int y, int width, int height, Component label, OnPress onPress) {
            super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        }

        public FlatButton accent() {
            this.accentStyle = true;
            return this;
        }

        /** The one main action of a screen (EQUIP): signal-colour fill, dark text. */
        public FlatButton primary() {
            this.primaryStyle = true;
            return this;
        }

        /** Key-cap shown before the label ("ENTER", "ESC"). Purely a hint: the key handling stays in the screen. */
        public FlatButton key(String hint) {
            this.keyHint = hint == null ? "" : hint;
            return this;
        }

        @Override
        public void onPress() {
            flashAt = System.currentTimeMillis(); // soft accent flash on click
            super.onPress();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            hover = easeHover(hover, isHoveredOrFocused() && active, partialTick);
            int slide = (int) ((1f - chromeOpen) * 8); // starts 8px low, settles with the screen open
            int bx = getX();
            int by = getY() + slide;

            int fill;
            int textColor;
            int borderColor;
            if (!active) {
                fill = PANEL;
                textColor = LOCKED;
                borderColor = LOCKED;
            } else if (primaryStyle) {
                fill = blend(signal(), 0xFFFFFFFF, hover * 0.18f);
                textColor = DARK_TEXT;
                borderColor = signal();
            } else {
                fill = blend(PANEL_RAISED, HOVER_FILL, hover * 0.92f);
                textColor = blend(TEXT_DIM, DARK_TEXT, hover);
                borderColor = accentStyle ? borderActive() : blend(border(), borderActive(), hover * 0.6f);
            }
            if (angular()) {
                int c = cut(width, height, 3);
                com.mojang.blaze3d.vertex.VertexConsumer vc = graphics.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
                org.joml.Matrix4f m = graphics.pose().last().pose();
                fillI(vc, m, bx, by, width, height, c, fa(fill));
                edgeI(vc, m, bx, by, width, height, c, fa(borderColor));
                if (accentStyle && active && !primaryStyle && hover < 0.5f) {
                    stripI(vc, m, bx, by, height, c, 2, fa(signal()));
                }
                graphics.flush();
            } else {
                MWStyle.fillF(graphics, bx, by, bx + width, by + height, fill);
                hairline(graphics, bx, by, width, height, borderColor);
            }
            if (!active) {
                hatch(graphics, bx + 1, by + 1, width - 2, height - 2, 0x1CFFFFFF);
            }
            if (flashAt > 0) {
                float ft = (System.currentTimeMillis() - flashAt) / 280f;
                if (ft >= 1f) {
                    flashAt = 0;
                } else {
                    float k = (1f - ft) * (1f - ft);
                    MWStyle.wash(graphics, bx, by, width, height, withAlpha(MWStyle.accent(), 0.42f * k));
                }
            }
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String label = getMessage().getString().toUpperCase();
            int labelW = font.width(label);
            boolean chip = !keyHint.isEmpty() && width >= labelW + font.width(keyHint) + 22;
            int chipW = chip ? font.width(keyHint) + 6 + 5 : 0;
            int startX = bx + (width - labelW - chipW) / 2;
            int ty = by + (height - 8) / 2;
            if (chip) {
                keyChip(graphics, font, keyHint, startX, ty, withAlpha(textColor, 0.8f));
            }
            MWStyle.strF(graphics, font, label, startX + chipW, ty, textColor, false);
        }
    }
    /** A draggable horizontal slider styled to match FlatButton — used for the backdrop opacity
     *  and any future 0..max setting. Reports its integer value through onChange as you drag. */
    public static final class Slider extends net.minecraft.client.gui.components.AbstractSliderButton {
        private final int min;
        private final int max;
        private final java.util.function.Consumer<Integer> onChange;
        private final String label;
        private float hoverAnim;

        public Slider(int x, int y, int width, int height, String label, int min, int max,
                      int value, java.util.function.Consumer<Integer> onChange) {
            super(x, y, width, height, Component.empty(),
                    (double) (value - min) / Math.max(1, max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.onChange = onChange;
            updateMessage();
        }

        public int intValue() {
            return (int) Math.round(min + value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + intValue() + "%"));
        }

        @Override
        protected void applyValue() {
            if (onChange != null) {
                onChange.accept(intValue());
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            hoverAnim = approach(hoverAnim, isHoveredOrFocused() ? 1f : 0f, 0.3f, frameDt());
            MWStyle.fillF(graphics, getX(), getY(), getX() + width, getY() + height, blend(PANEL_RAISED, PANEL_HOVER, hoverAnim));
            // Filled portion up to the handle.
            int handleX = getX() + (int) (value * (width - 8));
            MWStyle.fillF(graphics, getX(), getY(), handleX + 4, getY() + height, withAlpha(accent(), 0.30f + 0.20f * hoverAnim));
            hairline(graphics, getX(), getY(), width, height, blend(border(), borderActive(), hoverAnim * 0.7f));
            // Handle.
            MWStyle.fillF(graphics, handleX, getY(), handleX + 8, getY() + height, borderActive());
            MWStyle.centerF(graphics, Minecraft.getInstance().font, getMessage().getString(),
                    getX() + width / 2, getY() + (height - 8) / 2, TEXT);
        }
    }
}
