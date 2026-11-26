package dev.l41nx.mwloadout.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/** "Advanced stats": the TACZ refit screen's full readout for the CURRENT build, plus what the numbers become on THIS server.
 *
 *  - left: every raw number vs the bare weapon (scrolls), and the DEFENSE controls - server damage-reduction slider with a typed decimal
 *    field, armor points / toughness / target HP typed in - that turn TACZ damage into damage actually taken;
 *  - right: DAMAGE / TIME TO KILL / SPREAD / PATTERN. Step and line charts that animate toward new values the moment a control or the build
 *    changes, hover read-out, click-to-pin distance; a per-zone table (Limb Damage zones, categories and overrides synced from the server);
 *    a Minecraft-style target with square spread rings; and a predicted spray pattern as firing dots on the player model.
 *
 *  Everything is laid out in a virtual space whose scale is chosen so one virtual unit is a WHOLE number of screen pixels (text stays crisp
 *  and the box fits at any GUI scale); charts are drawn straight to the GPU as feathered quads (analytic anti-aliasing). */
public final class AdvancedStatsPanel {

    private static final String[] TABS = {"Damage", "Time to kill", "Spread", "Pattern"};
    private static final String[] STANCE = {"Hip", "Moving", "Crouch", "Prone", "ADS"};
    private static final int[] STANCE_COLOR = {0xFFFFB23F, 0xFFFF5A4F, 0xFF4FC3F7, 0xFF9B87FF, 0xFF6CE08A};
    private static final int RAW_COLOR = 0xFFB9BEC7;
    private static final int N = 140;           // chart samples
    private static final float ANIM_SECONDS = 0.16f;
    private static final float MODEL_PX_M = 0.05625f; // one Minecraft model pixel in metres (32 px = 1.8 m)

    private boolean open;
    private float animT;
    private long lastNano;
    private int kDev = 1;       // screen pixels per virtual unit
    private float fit = 1f;     // virtual -> GUI units
    private int vw, vh;
    private String gunId = "";
    private String title = "";
    private Map<String, String> build = Map.of();
    private TaczStats.AdvancedData cur;
    private TaczStats.AdvancedData stock;
    private float reveal;
    private double pinned = -1;
    private float focusShown = 25f;
    private float hoverX = -1f;
    private float xMaxShown = 60f, yMaxShown = 1f;
    private boolean draggingSlider;
    private int patternStance = 4;
    private float leftScroll;
    private int leftAreaY, leftAreaH, leftContentH, leftAreaX, leftAreaW;
    private String limbSig = "";
    private long limbFlashUntil;
    private final Map<String, double[]> shown = new HashMap<>();
    private final Map<String, Float> nums = new HashMap<>();

    private EditBox hpBox, armorBox, toughBox, reductionBox;
    private int px, py, pw, ph;
    private int closeX, closeY;
    private int tabY;
    private final int[] tabX = new int[4], tabW = new int[4];
    private int chartX, chartY, chartW, chartH;
    private int sliderX, sliderY, sliderW, resetX, resetY, resetW, fragX, fragY, fragW;
    private final List<int[]> chips = new ArrayList<>(); // {x, y, w, h, index}
    private float lastShownReduction = -999f;

    /** The small square entry button (three bars = chart). Pops in shortly after its screen opens, grows on hover and shows its own label
     *  (vanilla's tooltip call rendered an empty box here). {@code shownAt} = when the screen opened. */
    public static void drawButton(GuiGraphics g, int x, int y, int size, int mx, int my, long shownAt) {
        float t = Math.min(1f, (System.currentTimeMillis() - shownAt - 220L) / 340f);
        if (t <= 0f) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean hover = mx >= x && mx < x + size && my >= y && my < y + size;
        float pop = Math.max(0f, MWStyle.easeOutBack(t)) * (hover ? 1.12f : 1f);
        g.pose().pushPose();
        g.pose().translate(x + size / 2f, y + size / 2f, 0);
        g.pose().scale(pop, pop, 1f);
        g.pose().translate(-size / 2f, -size / 2f, 0);
        MWStyle.fillF(g, 0, 0, size, size, hover ? 0xFF2C3139 : 0xFF1A1D22);
        int ink = hover ? MWStyle.TEXT : 0xFFA5ABB5;
        MWStyle.fillF(g, 3, 9, 5, 13, ink);
        MWStyle.fillF(g, 7, 6, 9, 13, ink);
        MWStyle.fillF(g, 11, 3, 13, 13, ink);
        g.pose().popPose();
        if (hover) {
            String label = "Advanced stats";
            int w = font.width(label) + 10;
            int lx = Math.max(2, x - w - 4), ly = y + (size - 13) / 2;
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            MWStyle.fillF(g, lx, ly, lx + w, ly + 13, 0xF2141719);
            MWStyle.strF(g, font, label, lx + 5, ly + 3, MWStyle.TEXT, false);
            g.pose().popPose();
        }
    }

    /** Tooltip for a stat bar (gunsmith and weapon selection): the exact TACZ figure first, then the comparison, then this stat's breakdown.
     *  For DAMAGE it also lists what each Limb Damage zone really takes on this server (reduction / armor from the Advanced stats controls). */
    public static List<Component> statTooltip(int index, float[] raw, float[] baseRaw, String baseLabel, TaczStats.AdvancedData d) {
        DamageModel.ensureLoaded();
        List<Component> lines = new ArrayList<>();
        String unit = TaczStats.BAR_UNITS[index];
        String suffix = unit.isEmpty() ? "" : " " + unit;
        lines.add(Component.literal(TaczStats.BAR_LABELS[index] + ": " + TaczStats.formatStat(index, raw[index]) + suffix)
                .withStyle(net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.BOLD));
        if (baseRaw != null && index < baseRaw.length) {
            float delta = raw[index] - baseRaw[index];
            String who = baseLabel == null ? "Stock" : baseLabel;
            if (Math.abs(delta) > 0.0001f) {
                boolean better = TaczStats.LOWER_IS_BETTER[index] ? delta < 0 : delta > 0;
                int pct = baseRaw[index] == 0 ? 0 : Math.round(delta / Math.abs(baseRaw[index]) * 100f);
                lines.add(Component.literal(who + " " + TaczStats.formatStat(index, baseRaw[index]) + suffix + "   " + (delta > 0 ? "+" : "")
                        + TaczStats.formatStat(index, delta) + (pct != 0 ? " (" + (pct > 0 ? "+" : "") + pct + "%)" : "")
                        + (better ? "   better" : "   worse"))
                        .withStyle(better ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED));
            } else {
                lines.add(Component.literal(who + " " + TaczStats.formatStat(index, baseRaw[index]) + suffix).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
        if (!TaczStats.BAR_MEANING[index].isEmpty()) {
            lines.add(Component.literal(TaczStats.BAR_MEANING[index]).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
        // The bar always reads "longer = better"; for ACCURACY / MOBILITY / CONTROL the number is the opposite way round (spread, weight, kick).
        lines.add(Component.literal(TaczStats.LOWER_IS_BETTER[index] ? "Longer bar = better   (the number: lower = better)"
                : "Longer bar = better   (the number: higher = better)").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        if (d != null) {
            switch (index) {
                case 1 -> {
                    lines.add(Component.literal((d.pellets() > 1 ? "Per pellet (TACZ), x " + d.pellets() + " pellets" : "TACZ damage") + "   Head x" + num(d.headMul()))
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                    double tacz = DamageModel.taczAt(d, 0);
                    double reduction = DamageModel.reduction();
                    lines.add(Component.literal("Taken on this server (0 m, " + num(reduction) + "% reduction"
                            + (DamageModel.armorPoints > 0 ? ", armor " + num(DamageModel.armorPoints) : "") + "):").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                    for (DamageModel.Zone z : DamageModel.activeZones()) {
                        double shot = DamageModel.perShot(d, tacz, z);
                        final int color = z.color & 0xFFFFFF;
                        String per = d.pellets() > 1 ? String.format(Locale.ROOT, "%.1f", DamageModel.perHit(d, tacz, z)) + "/pellet  =  " : "";
                        lines.add(Component.literal("  " + z.label + "  " + per + String.format(Locale.ROOT, "%.1f", shot) + "   " + (int) DamageModel.shotsToKill(shot) + " STK" + (d.pellets() > 1 ? " (all hit)" : ""))
                                .withStyle(st -> st.withColor(color)));
                    }
                    float[][] f = d.falloff();
                    StringBuilder sb = new StringBuilder("Falloff: ");
                    for (int i = 0; i < f.length && i < 4; i++) {
                        sb.append(f[i][0] >= 1.0e6f ? "beyond" : "to " + Math.round(f[i][0]) + " m").append(" ").append(num(f[i][1]));
                        if (i < f.length - 1 && i < 3) {
                            sb.append("  >  ");
                        }
                    }
                    lines.add(Component.literal(sb.toString()).withStyle(net.minecraft.ChatFormatting.GRAY));
                }
                case 2 -> lines.add(Component.literal("Full damage up to " + num(d.range()) + " m, then it falls off").withStyle(net.minecraft.ChatFormatting.GRAY));
                case 3 -> lines.add(Component.literal(num(raw[3] / 60f) + " shots/s   " + num(raw[3] > 0 ? 60000f / raw[3] : 0f) + " ms between shots").withStyle(net.minecraft.ChatFormatting.GRAY));
                case 6 -> lines.add(Component.literal("Travels " + num(raw[6] / 20f) + " blocks per tick").withStyle(net.minecraft.ChatFormatting.GRAY));
                default -> {
                }
            }
        }
        lines.add(Component.literal("Open Advanced stats for charts and the damage calculator").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return lines;
    }

    // ------------------------------------------------------------------ lifecycle
    public boolean isOpen() {
        return open || animT > 0f;
    }

    public void open(String gunId, String title, Map<String, String> build) {
        DamageModel.ensureLoaded();
        this.gunId = gunId;
        this.title = title;
        this.build = build == null ? Map.of() : Map.copyOf(build);
        this.cur = TaczStats.advancedData(gunId, this.build);
        this.stock = TaczStats.advancedData(gunId, Map.of());
        this.open = cur != null;
        this.reveal = 0f;
        this.pinned = -1;
        this.leftScroll = 0f;
        this.shown.clear();
        this.nums.clear();
        this.limbSig = DamageModel.limbSignature();
        Font font = Minecraft.getInstance().font;
        hpBox = box(font, DamageModel.targetHp, "\\d{0,4}(\\.\\d{0,2})?", v -> DamageModel.targetHp = Mth.clamp(v, 1, 2000));
        armorBox = box(font, DamageModel.armorPoints, "\\d{0,3}(\\.\\d{0,2})?", v -> DamageModel.armorPoints = Mth.clamp(v, 0, 60));
        toughBox = box(font, DamageModel.toughness, "\\d{0,2}(\\.\\d{0,2})?", v -> DamageModel.toughness = Mth.clamp(v, 0, 40));
        reductionBox = box(font, DamageModel.reduction(), "\\d{0,2}(\\.\\d{0,2})?", v -> {
            if (!syncingBox) {
                DamageModel.reductionPercent = Mth.clamp(v, 0, 99.9);
            }
        });
        lastShownReduction = -999f;
    }

    private boolean syncingBox;

    private EditBox box(Font font, double value, String regex, java.util.function.DoubleConsumer onChange) {
        EditBox box = new EditBox(font, 0, 0, 40, 12, Component.empty());
        box.setBordered(false);
        box.setMaxLength(7);
        box.setFilter(s -> s.matches(regex));
        syncingBox = true;
        box.setValue(fmtBox(value));
        syncingBox = false;
        box.setResponder(text -> {
            try {
                if (!text.isEmpty() && !text.equals(".")) {
                    onChange.accept(Double.parseDouble(text));
                }
            } catch (NumberFormatException ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/gui/AdvancedStatsPanel:219", ignored);
            }
        });
        return box;
    }

    private static String fmtBox(double value) {
        return Math.abs(value - Math.rint(value)) < 0.0005 ? String.valueOf((long) Math.rint(value))
                : java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private EditBox[] boxes() {
        return new EditBox[]{hpBox, armorBox, toughBox, reductionBox};
    }

    public void close() {
        if (open) {
            DamageModel.save();
        }
        open = false;
        for (EditBox box : boxes()) {
            if (box != null) {
                box.setFocused(false);
            }
        }
    }

    // ------------------------------------------------------------------ input (screen GUI coordinates in, virtual coordinates inside)
    private boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public boolean mouseClicked(double gx, double gy, int button) {
        if (!open) {
            return isOpen();
        }
        if (animT < 0.9f || button != 0) {
            return true;
        }
        double mx = gx / fit, my = gy / fit;
        for (EditBox box : boxes()) {
            boolean hit = box.mouseClicked(mx, my, button);
            box.setFocused(hit);
            if (hit) {
                return true;
            }
        }
        if (in(mx, my, closeX, closeY, 14, 14)) {
            close();
            return true;
        }
        for (int i = 0; i < TABS.length; i++) {
            if (in(mx, my, tabX[i], tabY, tabW[i], 14)) {
                if (DamageModel.tab != i) {
                    DamageModel.tab = i;
                    reveal = 0f;
                    shown.clear();
                    MWStyle.playClick();
                }
                return true;
            }
        }
        for (int[] chip : chips) {
            if (in(mx, my, chip[0], chip[1], chip[2], chip[3])) {
                if (DamageModel.tab == 3) {
                    patternStance = chip[4];
                } else if (DamageModel.tab < 2) {
                    DamageModel.hiddenMask ^= 1 << chip[4];
                }
                MWStyle.playClick();
                return true;
            }
        }
        if (in(mx, my, sliderX - 4, sliderY - 6, sliderW + 8, 16)) {
            draggingSlider = true;
            dragSlider(mx);
            return true;
        }
        if (in(mx, my, resetX, resetY, resetW, 12)) {
            DamageModel.reductionPercent = -1; // back to the server's own number
            MWStyle.playClick();
            return true;
        }
        if (in(mx, my, fragX, fragY, fragW, 12)) {
            DamageModel.fragile = !DamageModel.fragile;
            MWStyle.playClick();
            return true;
        }
        if (DamageModel.tab != 3 && in(mx, my, chartX, chartY, chartW, chartH)) {
            double distance = xMaxShown * (mx - chartX) / chartW;
            pinned = pinned >= 0 && Math.abs(pinned - distance) < xMaxShown * 0.02 ? -1 : distance; // click again to unpin
            MWStyle.playClick();
            return true;
        }
        if (!in(mx, my, px, py, pw, ph)) {
            close(); // click on the dim backdrop
        }
        return true;
    }

    private void dragSlider(double mx) {
        double t = Mth.clamp((mx - sliderX) / sliderW, 0.0, 1.0);
        DamageModel.reductionPercent = Math.round(t * 990.0) / 10.0; // 0.1 steps; the field next to it takes any decimal
    }

    public boolean mouseDragged(double gx, double gy) {
        if (draggingSlider) {
            dragSlider(gx / fit);
        }
        return isOpen();
    }

    public boolean mouseReleased() {
        if (draggingSlider) {
            draggingSlider = false;
            DamageModel.save();
        }
        return isOpen();
    }

    public boolean mouseScrolled(double gx, double gy, double delta) {
        double mx = gx / fit, my = gy / fit;
        if (in(mx, my, leftAreaX, leftAreaY, leftAreaW, leftAreaH)) {
            leftScroll = Mth.clamp(leftScroll - (float) delta * 22f, 0f, Math.max(0, leftContentH - leftAreaH));
        }
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) {
            return isOpen();
        }
        for (EditBox box : boxes()) {
            if (box.isFocused()) {
                if (keyCode == 256 || keyCode == 257 || keyCode == 335) { // ESC / Enter just leave the field
                    box.setFocused(false);
                    DamageModel.save();
                    return true;
                }
                return box.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        if (keyCode == 256) {
            close();
        }
        return true;
    }

    public boolean charTyped(char c, int modifiers) {
        if (!open) {
            return false;
        }
        for (EditBox box : boxes()) {
            if (box.isFocused()) {
                return box.charTyped(c, modifiers);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ GPU batch (feathered quads = analytic anti-aliasing)
    private final class Batch {
        private final BufferBuilder bb;
        private final Matrix4f matrix;
        private final float alpha;
        private final float f = 1f / kDev;   // one screen pixel in virtual units: the width of the soft edge
        private int count;

        Batch(GuiGraphics g) {
            g.flush();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            matrix = g.pose().last().pose();
            alpha = MWStyle.uiAlpha();
        }

        void v(float x, float y, int c, float a) {
            int al = Math.min(255, Math.max(0, Math.round(((c >>> 24) & 255) * alpha * a)));
            bb.vertex(matrix, x, y, 0f).color((c >> 16) & 255, (c >> 8) & 255, c & 255, al).endVertex();
            count++;
        }

        void grad(float x0, float y0, float x1, float y1, int top, int bottom) {
            v(x0, y0, top, 1f);
            v(x1, y0, top, 1f);
            v(x1, y1, bottom, 1f);
            v(x0, y1, bottom, 1f);
        }

        void quad(float x0, float y0, float x1, float y1, int c) {
            grad(x0, y0, x1, y1, c, c);
        }

        private void strip(float x1, float y1, float x2, float y2, float nx, float ny, float oa, float aa, float ob, float ab, int c) {
            v(x1 + nx * oa, y1 + ny * oa, c, aa);
            v(x2 + nx * oa, y2 + ny * oa, c, aa);
            v(x2 + nx * ob, y2 + ny * ob, c, ab);
            v(x1 + nx * ob, y1 + ny * ob, c, ab);
        }

        /** a line as three strips: a solid core and a one-pixel alpha ramp on each side (the soft edge IS the anti-aliasing). */
        void line(float x1, float y1, float x2, float y2, float width, int c) {
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.0001f) {
                return;
            }
            float ux = dx / len, uy = dy / len, nx = -uy, ny = ux;
            float hw = Math.max(width * 0.5f, f * 0.5f);
            float inner = Math.max(0f, hw - f * 0.5f), outer = hw + f * 0.5f;
            x1 -= ux * f * 0.5f;
            y1 -= uy * f * 0.5f;
            x2 += ux * f * 0.5f;
            y2 += uy * f * 0.5f;
            strip(x1, y1, x2, y2, nx, ny, -outer, 0f, -inner, 1f, c);
            strip(x1, y1, x2, y2, nx, ny, -inner, 1f, inner, 1f, c);
            strip(x1, y1, x2, y2, nx, ny, inner, 1f, outer, 0f, c);
        }

        private void band(float cx, float cy, float r0, float a0, float r1, float a1, int c, int seg) {
            for (int i = 0; i < seg; i++) {
                double t0 = Math.PI * 2 * i / seg, t1 = Math.PI * 2 * (i + 1) / seg;
                float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
                v(cx + c0 * r0, cy + s0 * r0, c, a0);
                v(cx + c0 * r1, cy + s0 * r1, c, a1);
                v(cx + c1 * r1, cy + s1 * r1, c, a1);
                v(cx + c1 * r0, cy + s1 * r0, c, a0);
            }
        }

        void disc(float cx, float cy, float r, int c) {
            int seg = Math.max(14, Math.min(56, (int) (r * kDev * 1.5f)));
            float inner = Math.max(0f, r - f * 0.5f);
            band(cx, cy, 0f, 1f, inner, 1f, c, seg);
            band(cx, cy, inner, 1f, r + f * 0.5f, 0f, c, seg);
        }

        void end() {
            var built = bb.end();
            if (count > 0) {
                BufferUploader.drawWithShader(built);
            } else {
                built.release();
            }
            RenderSystem.enableCull();
        }
    }

    private static int alphaOf(int color, float a) {
        return (Math.min(255, Math.max(0, Math.round(a * 255))) << 24) | (color & 0xFFFFFF);
    }

    private float snap(float v) {
        return Math.round(v * kDev) / (float) kDev;
    }

    private float an(String key, double target) {
        float now = nums.getOrDefault(key, (float) target);
        now = MWStyle.approach(now, (float) target, 0.2f, MWStyle.frameDt());
        nums.put(key, now);
        return now;
    }

    // ------------------------------------------------------------------ look: flat charcoal, one accent, hierarchy from size not decoration
    private static final int BG = 0xFF0F1115;
    private static final int RULE = 0xFF1D2026;
    private static final int GRID = 0xFF191C21;
    private static final int FAINT = 0xFF626872;

    /** text drawn at an integer scale (2x = a real "big number", still crisp because the layout scale is a whole number of pixels). */
    private void big(GuiGraphics g, Font font, String text, int x, int y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        MWStyle.strF(g, font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void heading(GuiGraphics g, Font font, String text, int x, int y, int w) {
        g.drawString(font, Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD), x, y, MWStyle.fa(MWStyle.TEXT), false);
        MWStyle.sectionRule(g, x, y + 10, w);
    }

    private static String num(double v) {
        return Math.abs(v - Math.rint(v)) < 0.005 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ render
    public void render(GuiGraphics g, int width, int height, int mouseX, int mouseY) {
        long nowN = System.nanoTime();
        float dtSec = lastNano == 0 ? 0f : Math.min(0.05f, (nowN - lastNano) / 1.0e9f);
        lastNano = nowN;
        // one linear clock, same speed in both directions; the same curve plays forwards on open and backwards on close
        animT = Mth.clamp(animT + (open ? 1f : -1f) * dtSec / ANIM_SECONDS, 0f, 1f);
        if (!open && animT <= 0f) {
            lastNano = 0;
            return;
        }
        if (cur == null) {
            return;
        }
        float dt = MWStyle.frameDt();
        reveal = MWStyle.approach(reveal, 1f, 0.07f, dt);
        Font font = Minecraft.getInstance().font;
        float e = animT * animT * (3f - 2f * animT);
        float rise = (1f - e) * 6f; // slides up 6 px while fading in

        // virtual layout space: 1 virtual unit = a whole number of screen pixels, as large as still fits the box
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        int kk = Math.max(1, (int) Math.floor(gs + 1e-6));
        while (kk > 1 && (height * gs / kk < 340 || width * gs / kk < 620)) {
            kk--;
        }
        kDev = kk;
        fit = (float) (kk / gs);
        vw = Math.round(width / fit);
        vh = Math.round(height / fit);
        pw = Math.min(vw - 16, 780);
        ph = Math.min(vh - 16, 430);
        px = (vw - pw) / 2;
        py = (vh - ph) / 2;
        int mx = Math.round(mouseX / fit), my = Math.round((mouseY / fit) - rise);

        String sig = DamageModel.limbSignature(); // the server's Limb Damage config changed while this box is open
        if (!sig.equals(limbSig)) {
            limbSig = sig;
            limbFlashUntil = System.currentTimeMillis() + 3500L;
        }

        MWStyle.localAlpha(e);
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, 400);
            MWStyle.fillF(g, 0, 0, width, height, 0xB8000000);
            g.pose().scale(fit, fit, 1f);
            g.pose().translate(0, rise, 0);

            MWStyle.fillF(g, px, py, px + pw, py + ph, BG);
            MWStyle.fillF(g, px, py, px + pw, py + 1, 0xFF2A2E36);
            MWStyle.fillF(g, px, py + ph - 1, px + pw, py + ph, 0xFF2A2E36);
            MWStyle.fillF(g, px, py, px + 1, py + ph, 0xFF2A2E36);
            MWStyle.fillF(g, px + pw - 1, py, px + pw, py + ph, 0xFF2A2E36);

            // header: the gun in large type, what this is underneath
            big(g, font, MWStyle.ellipsize(font, title, (pw - 90) / 2), px + 16, py + 9, MWStyle.TEXT, 2f);
            String cat = cur.category() != null ? " \u00b7 " + cur.category() : "";
            MWStyle.strF(g, font, "Advanced stats" + cat, px + 16, py + 28, MWStyle.TEXT_DIM, false);
            closeX = px + pw - 26;
            closeY = py + 8;
            boolean closeHover = in(mx, my, closeX, closeY, 16, 16);
            big(g, font, "\u00d7", closeX + 3, closeY, closeHover ? MWStyle.TEXT : FAINT, 2f);
            MWStyle.fillF(g, px, py + 42, px + pw, py + 43, RULE);
            MWStyle.fillF(g, px + 16, py + 42, px + 56, py + 44, MWStyle.signal()); // the one gold-orange mark in the header

            int leftW = Math.min(206, pw * 3 / 10);
            int lx = px + 16, top = py + 54;
            int defH = 118;
            int defY = py + ph - 30 - defH;
            renderReadout(g, font, lx, top, leftW, defY - 10 - top);
            renderDefense(g, font, lx, defY, leftW, mx, my);
            int rx = lx + leftW + 22;
            MWStyle.fillF(g, rx - 11, top, rx - 10, py + ph - 30, RULE);
            int rw = px + pw - 16 - rx;
            renderTabs(g, font, rx, top - 2, rw, mx, my);
            renderChartArea(g, font, rx, top + 22, rw, py + ph - 30 - (top + 22), mx, my);

            // footer: one plain sentence about where the zone numbers come from
            MWStyle.fillF(g, px, py + ph - 22, px + pw, py + ph - 21, RULE);
            Object[] limb = DamageModel.limbStatus();
            boolean flash = System.currentTimeMillis() < limbFlashUntil;
            String note = flash ? "Limb Damage config changed on the server \u2014 numbers updated." : (String) limb[0];
            MWStyle.strF(g, font, MWStyle.ellipsize(font, note, pw - 120), px + 16, py + ph - 14, flash ? MWStyle.accent() : (Integer) limb[1], false);
            String esc = "Esc to close";
            MWStyle.strF(g, font, esc, px + pw - 16 - font.width(esc), py + ph - 14, FAINT, false);

            for (EditBox box : boxes()) {
                box.render(g, mx, my, 0f);
            }
        } catch (Throwable t) {
            recoverFromRenderError(t);
        } finally {
            g.pose().popPose();
            MWStyle.localAlpha(1f);
        }
    }

    private boolean errorLogged;

    /** A chart/draw failure must never take the game down: put the shared buffer builder back into a usable state, close the box, log once. */
    private void recoverFromRenderError(Throwable t) {
        try {
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            if (bb.building()) {
                bb.end().release();
            }
            RenderSystem.enableCull();
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AdvancedStatsPanel:619", ignored);
        }
        if (!errorLogged) {
            errorLogged = true;
            dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.warn("[MWLoadout] Advanced stats draw failed - closing the box", t);
        }
        open = false;
        animT = 0f;
    }

    // ------------------------------------------------------------------ left: weapon numbers (scrolls) + incoming damage
    private void renderReadout(GuiGraphics g, Font font, int x, int y, int w, int space) {
        TaczStats.AdvancedData c = cur, s = stock == null ? cur : stock;
        int lh = 12;
        heading(g, font, "Weapon", x, y, w);
        List<Object[]> rows = new ArrayList<>();
        double dps = DamageModel.taczAt(c, 0) * c.pellets() * c.rpm() / 60.0;
        double dpsStock = DamageModel.taczAt(s, 0) * s.pellets() * s.rpm() / 60.0;
        boolean sg = DamageModel.isShotgun(c);
        if (sg) {
            // Shotgun: TACZ's figure is ONE pellet. Show it as such, then the pellet count, the all-pellets ceiling and what a real
            // trigger pull is worth at the distance the chart is focused on (the pellet cloud grows with range and misses the body).
            double near = DamageModel.taczAt(c, 0), nearStock = DamageModel.taczAt(s, 0);
            double spread = c.stanceSpread()[0], spreadStock = s.stanceSpread()[0];
            addCmp(rows, "Damage per pellet", near, nearStock, "", false);
            rows.add(new Object[]{"Pellets", "\u00d7" + c.pellets(), null, 0});
            addCmp(rows, "All pellets hit", near * c.pellets(), nearStock * s.pellets(), "", false);
            addCmp(rows, "Pellets on target @ " + num(focusShown) + " m",
                    DamageModel.expectedPellets(c, focusShown, spread), DamageModel.expectedPellets(s, focusShown, spreadStock), "", false);
        } else {
            addCmp(rows, "Damage", c.damage(), s.damage(), "", false);   // TACZ's own figure, untouched
        }
        rows.add(new Object[]{"Headshot", "\u00d7" + num(c.headMul()), null, 0});
        if (sg) {
            double real = DamageModel.expectedPerShot(c, focusShown, c.stanceSpread()[0]) * c.rpm() / 60.0;
            double realStock = DamageModel.expectedPerShot(s, focusShown, s.stanceSpread()[0]) * s.rpm() / 60.0;
            addCmp(rows, "DPS (all pellets hit)", dps, dpsStock, "", false);
            addCmp(rows, "DPS @ " + num(focusShown) + " m (expected)", real, realStock, "", false);
        } else {
            addCmp(rows, "Damage per second", dps, dpsStock, "", false);
        }
        addCmp(rows, "Fire rate", c.rpm(), s.rpm(), "rpm", false);
        addCmp(rows, "Magazine", c.mag(), s.mag(), "", false);
        addCmp(rows, "Effective range", c.range(), s.range(), "m", false);
        addCmp(rows, "Bullet speed", c.speed(), s.speed(), "m/s", false);
        addCmp(rows, "Armor ignore", c.armorIgnore() * 100, s.armorIgnore() * 100, "%", false);
        addCmp(rows, "Pierce", c.pierce(), s.pierce(), "", false);
        addCmp(rows, "Spread (standing)", c.inaccuracy(), s.inaccuracy(), "\u00b0", true);
        addCmp(rows, "Recoil", c.recoil(), s.recoil(), "", true);
        rows.add(new Object[]{"   up / sideways", num(c.recoilPitch()) + " / " + num(c.recoilYaw()) + "\u00b0", null, 0});
        // TaCZ Modern Tweaks installed: its real (quicker) ADS time, plus sprint-to-fire, which it derives from ADS time + weight
        addCmp(rows, "Aim time", dev.l41nx.mwloadout.compat.ModernTweakBridge.shownAdsTime(c.aimTime()),
                dev.l41nx.mwloadout.compat.ModernTweakBridge.shownAdsTime(s.aimTime()), "s", true);
        if (dev.l41nx.mwloadout.compat.ModernTweakBridge.available()) {
            // sights / scopes are left out: TACZ-ModernTweak never lets them change sprint-to-fire
            float stf = dev.l41nx.mwloadout.compat.ModernTweakBridge.sprintToFire(c.gunId(), c.stfAimTime(), c.stfWeight());
            float stfStock = dev.l41nx.mwloadout.compat.ModernTweakBridge.sprintToFire(s.gunId(), s.stfAimTime(), s.stfWeight());
            if (!Float.isNaN(stf) && !Float.isNaN(stfStock)) {
                addCmp(rows, "Sprint to fire", stf, stfStock, "s", true);
            }
        }
        addCmp(rows, "Weight", c.weight(), s.weight(), "", true);

        int top = y + 18;
        leftAreaX = x;
        leftAreaW = w;
        leftAreaY = top;
        leftAreaH = Math.max(lh, space - 18);
        leftContentH = rows.size() * lh;
        leftScroll = Mth.clamp(leftScroll, 0f, Math.max(0, leftContentH - leftAreaH));
        int textW = w - (leftContentH > leftAreaH ? 8 : 0);
        for (int i = 0; i < rows.size(); i++) {
            int yy = top + i * lh - Math.round(leftScroll);
            if (yy < top - 1 || yy + 9 > top + leftAreaH) {
                continue;
            }
            Object[] r = rows.get(i);
            row(g, font, x, yy, textW, (String) r[0], (String) r[1], (String) r[2], (Integer) r[3]);
        }
        if (leftContentH > leftAreaH) {
            int trackX = x + w - 2;
            MWStyle.fillF(g, trackX, top, trackX + 1, top + leftAreaH, RULE);
            int thumbH = Math.max(10, leftAreaH * leftAreaH / leftContentH);
            int thumbY = top + Math.round((leftAreaH - thumbH) * (leftScroll / Math.max(1, leftContentH - leftAreaH)));
            MWStyle.fillF(g, trackX - 1, thumbY, trackX + 1, thumbY + thumbH, FAINT);
        }
    }

    private void addCmp(List<Object[]> rows, String label, double now, double base, String unit, boolean lowerBetter) {
        String delta = null;
        int color = MWStyle.TEXT_DIM;
        if (Math.abs(now - base) > 0.0001) {
            double pct = base == 0 ? 0 : (now - base) / Math.abs(base) * 100.0;
            delta = (now > base ? "+" : "") + (base == 0 ? num(now - base) : Math.round(pct) + "%");
            color = (lowerBetter ? now < base : now > base) ? MWStyle.POSITIVE : MWStyle.NEGATIVE;
        }
        rows.add(new Object[]{label, num(now) + (unit.isEmpty() ? "" : " " + unit), delta, color});
    }

    private void row(GuiGraphics g, Font font, int x, int y, int w, String label, String value, String delta, int deltaColor) {
        MWStyle.strF(g, font, label, x, y, MWStyle.TEXT_DIM, false);
        int right = x + w;
        if (delta != null) {
            int dw = font.width(delta);
            MWStyle.strF(g, font, delta, right - dw, y, deltaColor, false);
            right -= dw + 6;
        }
        MWStyle.strF(g, font, value, right - font.width(value), y, MWStyle.TEXT, false);
    }

    private void renderDefense(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        MWStyle.fillF(g, x, y - 6, x + w, y - 5, RULE);
        heading(g, font, "Incoming damage", x, y, w);
        double reduction = DamageModel.reduction();
        boolean followsServer = DamageModel.reductionPercent < 0;
        int ry = y + 17;
        MWStyle.strF(g, font, "Server damage cut", x, ry + 1, MWStyle.TEXT_DIM, false);
        // typed value: click it and type any decimal, or drag the slider; both stay in step
        int bw = 44;
        int bx = x + w - bw - 9;
        boolean rf = reductionBox.isFocused();
        MWStyle.fillF(g, bx, ry - 2, bx + bw, ry + 11, rf ? 0xFF1A1F26 : 0xFF14171C);
        MWStyle.fillF(g, bx, ry + 10, bx + bw, ry + 11, rf ? MWStyle.accent() : 0xFF2A2E36);
        reductionBox.setX(bx + 4);
        reductionBox.setY(ry);
        reductionBox.setWidth(bw - 6);
        MWStyle.strF(g, font, "%", bx + bw + 2, ry + 1, MWStyle.TEXT_DIM, false);
        if (!reductionBox.isFocused() && Math.abs((float) reduction - lastShownReduction) > 0.0001f) {
            syncingBox = true;
            reductionBox.setValue(fmtBox(reduction));
            syncingBox = false;
            lastShownReduction = (float) reduction;
        }
        sliderX = x;
        sliderY = ry + 20;
        sliderW = w;
        MWStyle.fillF(g, sliderX, sliderY, sliderX + sliderW, sliderY + 2, 0xFF22262D);
        int fill = Math.round(sliderW * (float) (an("reductionPos", reduction) / 99.0));
        MWStyle.fillF(g, sliderX, sliderY, sliderX + fill, sliderY + 2, MWStyle.signal());
        int markX = sliderX + (int) Math.round(sliderW * Math.min(1.0, DamageModel.serverReduction() / 99.0));
        MWStyle.fillF(g, markX, sliderY - 2, markX + 1, sliderY + 4, FAINT);
        boolean knobHover = draggingSlider || in(mx, my, sliderX - 4, sliderY - 6, sliderW + 8, 16);
        int kx = sliderX + fill;
        MWStyle.fillF(g, kx - 2, sliderY - 3, kx + 2, sliderY + 5, knobHover ? 0xFFFFFFFF : MWStyle.TEXT);
        // reset link
        String link = followsServer ? "Matches the server (" + num(DamageModel.serverReduction()) + "%)" : "Use the server value (" + num(DamageModel.serverReduction()) + "%)";
        resetX = x;
        resetY = sliderY + 9;
        resetW = font.width(link);
        boolean linkHover = !followsServer && in(mx, my, resetX, resetY, resetW, 12);
        MWStyle.strF(g, font, link, resetX, resetY + 1, followsServer ? FAINT : (linkHover ? MWStyle.TEXT : MWStyle.accent()), false);
        if (!followsServer) {
            MWStyle.fillF(g, resetX, resetY + 10, resetX + resetW, resetY + 11, linkHover ? MWStyle.TEXT : MWStyle.accent());
        }
        int fy = resetY + 19;
        int fw = (w - 10) / 3;
        field(g, font, hpBox, "Target HP", x, fy, fw);
        field(g, font, armorBox, "Armor", x + fw + 5, fy, fw);
        field(g, font, toughBox, "Toughness", x + (fw + 5) * 2, fy, fw);
        // checkbox
        fragX = x;
        fragY = fy + 30;
        String frag = "Fragile target (arms and legs \u00d71)";
        fragW = 12 + font.width(frag);
        boolean fragHover = in(mx, my, fragX, fragY, fragW, 12);
        MWStyle.fillF(g, fragX, fragY + 1, fragX + 9, fragY + 10, fragHover ? 0xFF3A3F48 : 0xFF2A2E36);
        MWStyle.fillF(g, fragX + 1, fragY + 2, fragX + 8, fragY + 9, BG);
        if (DamageModel.fragile) {
            MWStyle.fillF(g, fragX + 2, fragY + 3, fragX + 7, fragY + 8, MWStyle.signal());
        }
        MWStyle.strF(g, font, frag, fragX + 13, fragY + 2, DamageModel.fragile ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
        double tacz = DamageModel.taczAt(cur, focusShown);
        double raw = tacz * DamageModel.zoneMultiplier(cur, DamageModel.Zone.CHEST);
        double taken = raw <= 0 ? 1.0 : DamageModel.perHit(cur, tacz, DamageModel.Zone.CHEST) / raw;
        String total = "A chest hit lands for " + num(an("cut", taken * 100)) + "% of the listed damage.";
        MWStyle.strF(g, font, MWStyle.ellipsize(font, total, w), x, fragY + 17, FAINT, false);
    }

    private void field(GuiGraphics g, Font font, EditBox box, String label, int x, int y, int w) {
        MWStyle.strF(g, font, label, x, y, MWStyle.TEXT_DIM, false);
        box.setX(x + 4);
        box.setY(y + 14);
        box.setWidth(w - 8);
        boolean focus = box.isFocused();
        MWStyle.fillF(g, x, y + 11, x + w, y + 25, focus ? 0xFF1A1F26 : 0xFF14171C);
        MWStyle.fillF(g, x, y + 24, x + w, y + 25, focus ? MWStyle.accent() : 0xFF2A2E36);
    }

    // ------------------------------------------------------------------ tabs: plain text, underline on the active one
    private void renderTabs(GuiGraphics g, Font font, int x, int y, int rw, int mx, int my) {
        tabY = y;
        int tx = x;
        for (int i = 0; i < TABS.length; i++) {
            tabW[i] = font.width(TABS[i]) + 14;
            tabX[i] = tx;
            boolean active = i == DamageModel.tab;
            boolean hover = in(mx, my, tx, y, tabW[i], 14);
            MWStyle.strF(g, font, TABS[i], tx, y + 3, active ? MWStyle.TEXT : (hover ? 0xFFB4BAC3 : MWStyle.TEXT_DIM), false);
            if (active) {
                MWStyle.fillF(g, tx, y + 14, tx + tabW[i] - 14, y + 16, MWStyle.signal());
            }
            tx += tabW[i] + 4;
        }
        MWStyle.fillF(g, x, y + 15, x + rw, y + 16, RULE);
    }

    // ------------------------------------------------------------------ series
    private record Ser(String key, String label, int color, boolean raw, DoubleUnaryOperator fn) {
    }

    private record Shot(float xPx, float yPx, int zone) {
    }

    private static final class Pattern {
        int shots;
        double[] climb, drift;
        final List<Shot> hits = new ArrayList<>();
        final int[] zoneHits = new int[6];
        int misses;
        int killShot = -1;
        int pelletsTotal; // shotguns: every pellet fired (shots x pellets); 0 = one bullet per shot
    }

    private Pattern pt;

    private static double interp(double[] a, double x) {
        double t = Mth.clamp(x, 0, a.length - 1);
        int i = (int) Math.floor(t), j = Math.min(a.length - 1, i + 1);
        return a[i] + (a[j] - a[i]) * (t - i);
    }

    /** Zone of a point on the Minecraft player model (units = model pixels, origin = ground, centre line x = 0), -1 = miss. */
    private static int zoneAt(float x, float y) {
        float ax = Math.abs(x);
        if (y < 0 || y > 32 || ax > 8) {
            return -1;
        }
        if (y >= 24) {
            return ax <= 4 ? DamageModel.Zone.HEAD.ordinal() : -1;
        }
        if (y >= 12) {
            if (ax <= 4) {
                return y >= 17 ? DamageModel.Zone.CHEST.ordinal() : DamageModel.Zone.ABDOMEN.ordinal();
            }
            return DamageModel.Zone.ARMS.ordinal();
        }
        if (ax > 4) {
            return -1;
        }
        return y < 2 ? DamageModel.Zone.FEET.ordinal() : DamageModel.Zone.LEGS.ordinal();
    }

    /** Predicted spray, no compensation: every shot lands inside the stance's spread cone around where the gun points, and the gun
     *  points higher by the accumulated recoil climb (each kick decays between shots, so slow guns barely climb). Deterministic - the
     *  same build always draws the same dots. An ESTIMATE from TACZ's recoil / inaccuracy numbers, not a replay of TACZ's own RNG. */
    private Pattern patCache;
    private int patKey;

    private Pattern pattern(double distance, int stance) {
        TaczStats.AdvancedData c = cur;
        Pattern p = new Pattern();
        int shots = Mth.clamp(c.mag() > 0 ? c.mag() : 30, 2, 30);
        p.shots = shots;
        p.climb = new double[shots + 1];
        p.drift = new double[shots + 1];
        double interval = c.rpm() > 0 ? 60000.0 / c.rpm() : 100.0;
        double keep = Math.exp(-interval / 400.0);
        Random rng = new Random(gunId.hashCode() * 31L + stance * 7919L + c.rpm());
        double climb = 0, drift = 0, tacz = DamageModel.taczAt(c, distance), total = 0;
        double radiusDeg = c.stanceSpread()[stance];
        for (int k = 0; k < shots; k++) {
            p.climb[k] = climb;
            p.drift[k] = Math.abs(drift);
            int pelletCount = DamageModel.isShotgun(c) ? c.pellets() : 1;
            for (int pellet = 0; pellet < pelletCount; pellet++) {
                double vDeg, hDeg;
                if (pelletCount > 1) {
                    // every pellet rolls its own triangular offset (vanilla-style spread), so a volley fans out and part of it misses
                    vDeg = climb + (rng.nextDouble() - rng.nextDouble()) * radiusDeg;
                    hDeg = drift + (rng.nextDouble() - rng.nextDouble()) * radiusDeg;
                } else {
                    double angle = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * radiusDeg;
                    vDeg = climb + Math.sin(angle) * r;
                    hDeg = drift + Math.cos(angle) * r;
                }
                float xPx = (float) (distance * Math.tan(Math.toRadians(hDeg)) / MODEL_PX_M);
                float yPx = (float) (20.5 + distance * Math.tan(Math.toRadians(vDeg)) / MODEL_PX_M);
                int zone = zoneAt(xPx, yPx);
                p.hits.add(new Shot(xPx, yPx, zone));
                if (zone >= 0) {
                    p.zoneHits[zone]++;
                    total += DamageModel.perHit(c, tacz, DamageModel.Zone.values()[zone]); // ONE pellet / bullet
                    if (p.killShot < 0 && total >= DamageModel.targetHp) {
                        p.killShot = k + 1;
                    }
                } else {
                    p.misses++;
                }
            }
            p.pelletsTotal += pelletCount > 1 ? pelletCount : 0;
            climb = (climb + c.recoilPitch() * (0.85 + 0.15 * rng.nextDouble())) * keep;
            drift = (drift + (rng.nextDouble() * 2 - 1) * c.recoilYaw()) * keep;
        }
        p.climb[shots] = climb;
        p.drift[shots] = Math.abs(drift);
        return p;
    }

    private List<Ser> series() {
        List<Ser> list = new ArrayList<>();
        TaczStats.AdvancedData c = cur;
        switch (DamageModel.tab) {
            case 0 -> {
                if (DamageModel.isShotgun(c)) {
                    // shotgun: the TACZ per-pellet line, the every-pellet ceiling, then the zone lines = pellets that really connect at hip spread
                    list.add(new Ser("raw", "Per pellet", RAW_COLOR, true, d -> DamageModel.taczAt(c, d)));
                    list.add(new Ser("real", "All pellets", MWStyle.accent(), false, d -> DamageModel.taczAt(c, d) * c.pellets()));
                } else {
                    list.add(new Ser("raw", "Listed", RAW_COLOR, true, d -> DamageModel.taczAt(c, d) * c.pellets()));
                }
                for (DamageModel.Zone z : DamageModel.activeZones()) {
                    list.add(new Ser(z.name(), z.label, z.color, false, d -> DamageModel.shotValue(c, d, z, false)));
                }
            }
            case 1 -> {
                for (DamageModel.Zone z : DamageModel.activeZones()) {
                    list.add(new Ser(z.name(), z.label, z.color, false,
                            d -> DamageModel.ttkMs(c, DamageModel.shotValue(c, d, z, false))));
                }
            }
            case 2 -> {
                for (int i : new int[]{0, 1, 2, 4}) {
                    final int idx = i;
                    list.add(new Ser("S" + i, STANCE[i], STANCE_COLOR[i], false, d -> DamageModel.radius(d, c.stanceSpread()[idx]) * 100.0));
                }
            }
            default -> {
                final Pattern p = pt;
                list.add(new Ser("climb", "Climb", MWStyle.accent(), false, x -> interp(p.climb, x)));
                list.add(new Ser("drift", "Drift", STANCE_COLOR[2], false, x -> interp(p.drift, x)));
            }
        }
        return list;
    }

    private static double niceCeil(double v) {
        if (v <= 0) {
            return 1;
        }
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        double n = v / mag;
        return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
    }

    /** Legend / visibility bit: zones 0-5, the listed line 6, the shotgun "expected hit" line 7. */
    private static int legendBit(Ser s) {
        return s.raw() ? 6 : "real".equals(s.key()) ? 7 : DamageModel.Zone.valueOf(s.key()).ordinal();
    }

    private boolean hidden(Ser s) {
        if (DamageModel.tab >= 2) {
            return false;
        }
        int bit = legendBit(s);
        return (DamageModel.hiddenMask & (1 << bit)) != 0;
    }

    private double distanceNow() {
        return pinned >= 0 ? pinned : 25.0;
    }

    // ------------------------------------------------------------------ chart area
    private static String radiusText(double metres) {
        return metres >= 2.5 ? String.format(Locale.ROOT, "%.1f m", metres) : String.format(Locale.ROOT, "%.0f cm", metres * 100);
    }

    /** The three numbers that matter for the current tab, big, at the distance being looked at. */
    private void drawHero(GuiGraphics g, Font font, int x, int y, int w) {
        TaczStats.AdvancedData c = cur;
        double d = focusShown, tacz = DamageModel.taczAt(c, d);
        String[] labels, values, units;
        switch (DamageModel.tab) {
            case 0 -> {
                double chest = DamageModel.shotValue(c, d, DamageModel.Zone.CHEST, false), head = DamageModel.shotValue(c, d, DamageModel.Zone.HEAD, false);
                labels = new String[]{"Chest hit", "Shots to kill", "Head hit"};
                values = new String[]{String.format(Locale.ROOT, "%.1f", an("h0", chest)), String.valueOf(Math.round(an("h1", DamageModel.shotsToKill(chest)))),
                        String.format(Locale.ROOT, "%.1f", an("h2", head))};
                units = new String[]{"damage", "to the chest", "damage"};
            }
            case 1 -> {
                double chest = DamageModel.ttkMs(c, DamageModel.shotValue(c, d, DamageModel.Zone.CHEST, false));
                double head = DamageModel.ttkMs(c, DamageModel.shotValue(c, d, DamageModel.Zone.HEAD, false));
                labels = new String[]{"Chest kill", "Headshot kill", "Fire rate"};
                values = new String[]{String.valueOf(Math.round(an("h0", chest))), String.valueOf(Math.round(an("h1", head))), String.valueOf(Math.round(an("h2", c.rpm())))};
                units = new String[]{"ms", "ms", "rpm"};
            }
            case 2 -> {
                labels = new String[]{"Hip", "Moving", "Aiming"};
                int[] idx = {0, 1, 4};
                values = new String[3];
                for (int i = 0; i < 3; i++) {
                    values[i] = radiusText(an("h" + i, DamageModel.radius(d, c.stanceSpread()[idx[i]])));
                }
                units = new String[]{"", "", ""};
            }
            default -> {
                Pattern p = pt;
                labels = new String[]{"Kills in", "On target", "Gun climbs"};
                values = new String[]{p.killShot > 0 ? String.valueOf(p.killShot) : "\u2013", p.pelletsTotal > 0 ? (p.pelletsTotal - p.misses) + "/" + p.pelletsTotal : (p.shots - p.misses) + "/" + p.shots,
                        String.format(Locale.ROOT, "%.1f", an("h2", p.climb[p.shots]))};
                units = new String[]{p.killShot > 0 ? "shots" : "no kill", p.pelletsTotal > 0 ? "pellets" : "shots", "degrees"};
            }
        }
        int bw = w / 3;
        for (int i = 0; i < 3; i++) {
            int bx = x + i * bw;
            MWStyle.strF(g, font, labels[i], bx, y, MWStyle.TEXT_DIM, false);
            big(g, font, values[i], bx, y + 11, MWStyle.TEXT, 2f);
            if (!units[i].isEmpty()) {
                MWStyle.strF(g, font, units[i], bx + font.width(values[i]) * 2 + 5, y + 18, FAINT, false);
            }
        }
    }

    private void renderChartArea(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my) {
        int tab = DamageModel.tab;
        if (tab == 3) {
            // the spray is deterministic (seeded): recompute only when its inputs change, not 60 times a second
            int key = java.util.Objects.hash(distanceNow(), patternStance, System.identityHashCode(cur), DamageModel.targetHp, DamageModel.reductionPercent,
                    DamageModel.armorPoints, DamageModel.toughness, DamageModel.fragile, DamageModel.headshotOverride());
            if (patCache == null || key != patKey) {
                patKey = key;
                patCache = pattern(distanceNow(), patternStance);
            }
            pt = patCache;
        } else {
            pt = null;
        }
        List<Ser> list = series();
        chips.clear();
        int heroH = 36;
        drawHero(g, font, x, y, w);
        int sy = y + heroH + 8;
        if (tab == 3) { // which stance the spray is for
            int cxp = x;
            for (int i : new int[]{0, 1, 2, 4}) {
                int cw = font.width(STANCE[i]) + 12;
                boolean on = patternStance == i, hover = in(mx, my, cxp, sy, cw, 13);
                MWStyle.strF(g, font, STANCE[i], cxp, sy + 2, on ? MWStyle.TEXT : (hover ? 0xFFB4BAC3 : MWStyle.TEXT_DIM), false);
                if (on) {
                    MWStyle.fillF(g, cxp, sy + 12, cxp + cw - 12, sy + 13, STANCE_COLOR[i]);
                }
                chips.add(new int[]{cxp, sy, cw, 13, i});
                cxp += cw + 4;
            }
            sy += 17;
        }
        String caption = tab == 0 ? "Damage per shot by body zone" : tab == 1 ? "Time to kill by body zone" : tab == 2 ? "Group radius at distance" : "Gun climb per shot (estimate, no recoil control)";
        MWStyle.strF(g, font, caption, x, sy, MWStyle.TEXT_DIM, false);
        if (tab < 3) {
            String hint = pinned >= 0 ? "Pinned at " + num(pinned) + " m \u2014 click the chart to unpin" : "Click the chart to pin a distance";
            MWStyle.strF(g, font, hint, x + w - font.width(hint), sy, pinned >= 0 ? MWStyle.TEXT : FAINT, false);
        }
        int zoneCount = DamageModel.activeZones().length;
        int tableH = tab < 2 ? 16 + (w < 360 ? (zoneCount + 1) / 2 : zoneCount) * 12 + 4 : Mth.clamp((int) (h * 0.38f), 92, 132);
        int labelCol = 66;
        chartX = x + 26;
        chartY = sy + 15;
        chartW = w - 26 - labelCol;
        chartH = Math.max(40, h - (chartY - y) - 16 - tableH - 6);
        drawChart(g, font, list, mx, my);
        int areaY = chartY + chartH + 16 + 6;
        if (tab < 2) {
            drawZoneTable(g, font, x, areaY, w, tableH);
        } else {
            drawScene(g, font, x, areaY, w, tableH, tab == 3);
        }
    }

    private void drawChart(GuiGraphics g, Font font, List<Ser> list, int mx, int my) {
        int tab = DamageModel.tab;
        TaczStats.AdvancedData c = cur;
        double xMaxT;
        if (tab == 3) {
            xMaxT = pt.shots;
        } else {
            double finiteMax = 0;
            for (float[] step : c.falloff()) {
                if (step[0] < 1.0e6f) {
                    finiteMax = Math.max(finiteMax, step[0]);
                }
            }
            xMaxT = Math.max(60, niceCeil(finiteMax * 1.3));
        }
        xMaxShown = MWStyle.approach(xMaxShown, (float) xMaxT, 0.2f, MWStyle.frameDt());
        double xMax = xMaxShown;

        double yMaxT = 1e-3;
        Map<Ser, double[]> arrays = new HashMap<>();
        float k = 1f - (float) Math.pow(1f - 0.2f, Math.max(0.0001f, MWStyle.frameDt()));
        for (Ser s : list) {
            double[] target = new double[N];
            for (int i = 0; i < N; i++) {
                target[i] = s.fn().applyAsDouble(xMax * i / (N - 1.0));
                if (!hidden(s)) {
                    yMaxT = Math.max(yMaxT, target[i]);
                }
            }
            double[] now = shown.computeIfAbsent(tab + s.key(), key -> target.clone());
            for (int i = 0; i < N; i++) {
                now[i] += (target[i] - now[i]) * k;
            }
            arrays.put(s, now);
        }
        double niceY = tab == 3 ? niceCeil(Math.max(0.5, yMaxT * 1.1)) : niceCeil(yMaxT * 1.08);
        yMaxShown = MWStyle.approach(yMaxShown, (float) niceY, 0.18f, MWStyle.frameDt());
        double yMax = Math.max(1e-6, yMaxShown);
        double yDiv = tab == 2 && yMax >= 250 ? 100.0 : 1.0;
        String ySuffix = tab == 2 ? (yDiv > 1 ? "m" : "cm") : "";

        // quiet axes: horizontal guides only, labels in the margin
        for (int i = 0; i <= 3; i++) {
            int gy = chartY + chartH - Math.round((chartH - 1) * i / 3f) - 1;
            MWStyle.fillF(g, chartX, gy, chartX + chartW, gy + 1, i == 0 ? 0xFF2A2E36 : GRID);
            String label = num(yMax * i / 3.0 / yDiv);
            MWStyle.strF(g, font, label, chartX - 5 - font.width(label), gy - 3, FAINT, false);
        }
        double xStep = tab == 3 ? (xMax <= 10 ? 1 : xMax <= 20 ? 2 : 5) : (xMax <= 80 ? 10 : xMax <= 160 ? 20 : 50);
        String xSuffix = tab == 3 ? "" : " m";
        for (double d = 0; d <= xMax + 0.01; d += xStep) {
            int gx = chartX + (int) Math.round((chartW - 1) * d / xMax);
            MWStyle.fillF(g, gx, chartY + chartH, gx + 1, chartY + chartH + 3, 0xFF2A2E36);
            String label = num(d) + xSuffix;
            MWStyle.strF(g, font, label, gx - font.width(label) / 2, chartY + chartH + 6, FAINT, false);
        }

        // the lines: one solid, anti-aliased stroke each - no fills, no glow
        float revealX = chartX + chartW * MWStyle.easeOutCubic(reveal);
        Batch b = new Batch(g);
        for (Ser s : list) {
            if (hidden(s)) {
                continue;
            }
            double[] a = arrays.get(s);
            int color = s.raw() ? alphaOf(s.color(), 0.55f) : s.color();
            float width = s.raw() ? 0.8f : 1.2f;
            for (int i = 0; i < N - 1; i++) {
                float x0 = chartX + chartW * i / (N - 1f), x1 = chartX + chartW * (i + 1) / (N - 1f);
                if (x0 >= revealX) {
                    break;
                }
                float y0 = chartY + chartH - 1 - (float) ((chartH - 1) * Math.min(1.0, a[i] / yMax));
                float y1 = chartY + chartH - 1 - (float) ((chartH - 1) * Math.min(1.0, a[i + 1] / yMax));
                if (x1 > revealX) {
                    float t = (revealX - x0) / (x1 - x0);
                    y1 = y0 + (y1 - y0) * t;
                    x1 = revealX;
                }
                b.line(x0, y0, x1, y1, width, color);
            }
        }
        b.end();

        // names sit at the END of their line instead of in a legend; clicking a name hides / shows that line
        List<float[]> ends = new ArrayList<>();
        for (int si = 0; si < list.size(); si++) {
            Ser s = list.get(si);
            if (!hidden(s)) {
                double[] a = arrays.get(s);
                ends.add(new float[]{chartY + chartH - 1 - (float) ((chartH - 1) * Math.min(1.0, a[N - 1] / yMax)), si});
            }
        }
        ends.sort((p, q) -> Float.compare(p[0], q[0]));
        for (int i = 1; i < ends.size(); i++) {
            ends.get(i)[0] = Math.max(ends.get(i)[0], ends.get(i - 1)[0] + 10f);
        }
        float lastY = ends.isEmpty() ? chartY : ends.get(ends.size() - 1)[0];
        float overflow = lastY - (chartY + chartH - 3);
        if (overflow > 0) {
            for (int i = ends.size() - 1; i >= 0; i--) {
                ends.get(i)[0] -= overflow;
                if (i > 0 && ends.get(i)[0] - ends.get(i - 1)[0] >= 10f) {
                    overflow = 0;
                }
            }
        }
        int labelX = chartX + chartW + 7;
        for (float[] e : ends) {
            Ser s = list.get((int) e[1]);
            int ly = Math.round(e[0]) - 4;
            boolean hover = in(mx, my, labelX - 3, ly - 1, font.width(s.label()) + 6, 10);
            MWStyle.fillF(g, labelX - 5, ly + 3, labelX - 2, ly + 5, s.color());
            MWStyle.strF(g, font, s.label(), labelX, ly, hover ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
            if (tab < 2) {
                chips.add(new int[]{labelX - 3, ly - 1, font.width(s.label()) + 6, 10, legendBit(s)});
            }
        }
        if (tab < 2) { // hidden lines wait underneath, dimmed
            int hy = (int) Math.max(chartY, ends.isEmpty() ? chartY : ends.get(ends.size() - 1)[0] + 8) + 6;
            for (Ser s : list) {
                if (hidden(s)) {
                    boolean hover = in(mx, my, labelX - 3, hy - 1, font.width(s.label()) + 6, 10);
                    MWStyle.strF(g, font, s.label(), labelX, hy, hover ? MWStyle.TEXT_DIM : 0xFF454A52, false);
                    chips.add(new int[]{labelX - 3, hy - 1, font.width(s.label()) + 6, 10, legendBit(s)});
                    hy += 11;
                }
            }
        }

        boolean inside = in(mx, my, chartX, chartY, chartW, chartH);
        double focus;
        if (tab == 3) {
            focus = distanceNow();
        } else if (inside) {
            hoverX = hoverX < 0 ? mx : MWStyle.approach(hoverX, mx, 0.5f, MWStyle.frameDt());
            focus = xMax * (mx - chartX) / (double) chartW;
        } else {
            hoverX = -1f;
            focus = distanceNow();
        }
        if (!inside) {
            hoverX = -1f;
        }
        focusShown = MWStyle.approach(focusShown, (float) focus, tab != 3 && inside ? 0.6f : 0.25f, MWStyle.frameDt());
        float focusPx = chartX + (float) (chartW * Math.min(1.0, focusShown / xMax));
        boolean showMarker = inside || (tab != 3 && pinned >= 0);
        if (showMarker) {
            float mxp = inside && hoverX >= 0 ? hoverX : focusPx;
            double at = inside ? (tab == 3 ? xMax * (mx - chartX) / (double) chartW : focus) : focusShown;
            Batch m = new Batch(g);
            m.quad(snap(mxp), chartY, snap(mxp) + 1f / kDev, chartY + chartH, alphaOf(0xFFFFFFFF, inside ? 0.28f : 0.4f));
            for (Ser s : list) {
                if (hidden(s)) {
                    continue;
                }
                double v = s.fn().applyAsDouble(at);
                float vy = chartY + chartH - 1 - (float) ((chartH - 1) * Math.min(1.0, v / yMax));
                m.disc(mxp, vy, 1.9f, s.color());
            }
            m.end();
            if (inside) {
                drawTooltip(g, font, list, at, yDiv, ySuffix, mx, my);
            }
        }
    }

    private void drawTooltip(GuiGraphics g, Font font, List<Ser> list, double at, double yDiv, String ySuffix, int mx, int my) {
        int tab = DamageModel.tab;
        List<String[]> rows = new ArrayList<>();
        for (Ser s : list) {
            if (hidden(s)) {
                continue;
            }
            double v = s.fn().applyAsDouble(at);
            String text = tab == 0 ? num(v) : tab == 1 ? Math.round(v) + " ms" : tab == 2 ? num(v / yDiv) + " " + ySuffix : String.format(Locale.ROOT, "%.2f\u00b0", v);
            if (tab == 0 && !s.raw()) {
                text += "  \u00b7  " + (int) DamageModel.shotsToKill(v) + " shots";
            }
            rows.add(new String[]{s.label(), text, String.valueOf(s.color())});
        }
        String title = tab == 3 ? "Shot " + Math.max(1, (int) Math.round(at)) : num(at) + " m";
        int w = font.width(title);
        for (String[] r : rows) {
            w = Math.max(w, font.width(r[0]) + font.width(r[1]) + 22);
        }
        w += 16;
        int h = (rows.size() + 1) * 11 + 10;
        int tx = mx + 14, ty = my - h - 6;
        if (tx + w > px + pw - 6) {
            tx = mx - w - 12;
        }
        if (ty < py + 46) {
            ty = my + 14;
        }
        MWStyle.fillF(g, tx, ty, tx + w, ty + h, 0xF4141719);
        MWStyle.fillF(g, tx, ty, tx + w, ty + 1, 0xFF2E333B);
        MWStyle.fillF(g, tx, ty + h - 1, tx + w, ty + h, 0xFF2E333B);
        MWStyle.fillF(g, tx, ty, tx + 1, ty + h, 0xFF2E333B);
        MWStyle.fillF(g, tx + w - 1, ty, tx + w, ty + h, 0xFF2E333B);
        int yy = ty + 6;
        MWStyle.strF(g, font, title, tx + 8, yy, MWStyle.TEXT, false);
        yy += 11;
        for (String[] r : rows) {
            MWStyle.fillF(g, tx + 8, yy + 3, tx + 11, yy + 5, Integer.parseInt(r[2]));
            MWStyle.strF(g, font, r[0], tx + 15, yy, MWStyle.TEXT_DIM, false);
            MWStyle.strF(g, font, r[1], tx + w - 8 - font.width(r[1]), yy, MWStyle.TEXT, false);
            yy += 11;
        }
    }

    // ------------------------------------------------------------------ per-zone table: plain, right-aligned numbers
    private void drawZoneTable(GuiGraphics g, Font font, int x, int y, int w, int h) {
        TaczStats.AdvancedData c = cur;
        double tacz = DamageModel.taczAt(c, focusShown);
        MWStyle.fillF(g, x, y, x + w, y + 1, RULE);
        String head = DamageModel.isShotgun(c)
                ? "At " + num(focusShown) + " m: " + num(tacz) + " per pellet x " + c.pellets() + " = " + num(tacz * c.pellets())
                + ", about " + num(DamageModel.expectedPellets(c, focusShown, c.stanceSpread()[0])) + " land (hip)"
                + (DamageModel.headshotOverride() ? " (headshot override on)" : "")
                : "At " + num(focusShown) + " m, the listed damage is " + num(tacz * c.pellets())
                + (DamageModel.headshotOverride() ? " (headshot override on)" : "");
        MWStyle.strF(g, font, MWStyle.ellipsize(font, head, w), x, y + 6, MWStyle.TEXT_DIM, false);
        boolean two = w < 360;
        DamageModel.Zone[] zones = DamageModel.activeZones();
        int rowsPer = two ? (zones.length + 1) / 2 : zones.length;
        int cellW = two ? (w - 10) / 2 : w;
        if (!two) {
            String[] cols = {"Mult", DamageModel.isShotgun(c) ? "Per pellet" : "Damage", "Shots", "Time"};
            int[] right = {(int) (w * 0.42), (int) (w * 0.60), (int) (w * 0.76), w};
            for (int i = 0; i < cols.length; i++) {
                MWStyle.strF(g, font, cols[i], x + right[i] - font.width(cols[i]), y + 19, FAINT, false);
            }
        }
        for (int i = 0; i < zones.length; i++) {
            DamageModel.Zone z = zones[i];
            int col = two ? i / rowsPer : 0, rowIdx = two ? i % rowsPer : i;
            int cx = x + col * (cellW + 10), yy = y + (two ? 19 : 31) + rowIdx * 12;
            double shot = DamageModel.shotValue(c, focusShown, z, false);            // what a pull is worth (landed pellets for a shotgun)
            double shownDmg = DamageModel.isShotgun(c) ? DamageModel.perHit(c, tacz, z) : shot; // the Damage column: ONE pellet / bullet
            float dmg = an("z" + z + "d", shownDmg);
            float mult = an("z" + z + "m", DamageModel.zoneMultiplier(c, z));
            float stk = an("z" + z + "s", DamageModel.shotsToKill(shot));
            float ttk = an("z" + z + "t", DamageModel.ttkMs(c, shot));
            MWStyle.fillF(g, cx, yy, cx + 2, yy + 8, z.color);
            MWStyle.strF(g, font, z.label, cx + 7, yy, MWStyle.TEXT, false);
            if (two) {
                String a = String.format(Locale.ROOT, "%.1f", dmg), bb = Math.round(stk) + " shots";
                MWStyle.strF(g, font, a, cx + cellW - 52 - font.width(a), yy, MWStyle.TEXT, false);
                MWStyle.strF(g, font, bb, cx + cellW - font.width(bb), yy, MWStyle.TEXT_DIM, false);
            } else {
                String[] v = {"\u00d7" + String.format(Locale.ROOT, "%.2f", mult), String.format(Locale.ROOT, "%.1f", dmg), String.valueOf(Math.round(stk)), Math.round(ttk) + " ms"};
                int[] right = {(int) (w * 0.42), (int) (w * 0.60), (int) (w * 0.76), w};
                for (int j = 0; j < 4; j++) {
                    MWStyle.strF(g, font, v[j], x + right[j] - font.width(v[j]), yy, j == 1 ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Minecraft-style target: spread rings / spray dots
    private void rect(Batch b, float x0, float y0, float x1, float y1, float bx0, float by0, float bx1, float by1, int color) {
        float ax = Math.max(x0, bx0), ay = Math.max(y0, by0), bx = Math.min(x1, bx1), by = Math.min(y1, by1);
        if (bx > ax && by > ay) {
            b.quad(ax, ay, bx, by, color);
        }
    }

    private void drawScene(GuiGraphics g, Font font, int x, int y, int w, int h, boolean spray) {
        TaczStats.AdvancedData c = cur;
        MWStyle.fillF(g, x, y, x + w, y + h, 0xFF0B0D10);
        double distance = focusShown;
        String head = spray ? "Spray at " + num(distance) + " m" : "Spread at " + num(distance) + " m";
        MWStyle.strF(g, font, head, x + 9, y + 6, MWStyle.TEXT, false);
        int stepX = x + w - 32;
        for (int i = 0; i < 2; i++) { // pin a nearer / farther distance without leaving the tab
            int bx = stepX + i * 15;
            MWStyle.fillF(g, bx, y + 4, bx + 13, y + 17, 0xFF1A1D22);
            MWStyle.strF(g, font, i == 0 ? "\u2212" : "+", bx + 4, y + 6, MWStyle.TEXT_DIM, false);
            chips.add(new int[]{bx, y + 4, 13, 13, 100 + i});
        }
        int infoW = Math.min(150, w * 42 / 100);
        int ly = y + 22;
        if (!spray) {
            for (int i : new int[]{4, 2, 0, 1}) {
                double rm = DamageModel.radius(distance, c.stanceSpread()[i]);
                MWStyle.fillF(g, x + 9, ly, x + 11, ly + 8, STANCE_COLOR[i]);
                MWStyle.strF(g, font, STANCE[i], x + 16, ly, MWStyle.TEXT_DIM, false);
                String v = radiusText(an("rv" + i, rm));
                MWStyle.strF(g, font, v, x + infoW - 8 - font.width(v), ly, MWStyle.TEXT, false);
                ly += 12;
            }
            String note = DamageModel.radius(distance, c.stanceSpread()[0]) > 0.22 ? "Hip fire is wider than the body here" : "Hip fire stays inside the body here";
            MWStyle.strF(g, font, MWStyle.ellipsize(font, note, infoW - 10), x + 9, y + h - 13, FAINT, false);
        } else {
            Pattern p = pt;
            DamageModel.Zone[] zones = DamageModel.Zone.values();
            for (int i = 0; i < zones.length && ly < y + h - 26; i++) {
                if (p.zoneHits[i] > 0) {
                    MWStyle.fillF(g, x + 9, ly, x + 11, ly + 8, zones[i].color);
                    MWStyle.strF(g, font, zones[i].label, x + 16, ly, MWStyle.TEXT_DIM, false);
                    String n = String.valueOf(p.zoneHits[i]);
                    MWStyle.strF(g, font, n, x + infoW - 8 - font.width(n), ly, MWStyle.TEXT, false);
                    ly += 12;
                }
            }
            if (p.misses > 0 && ly < y + h - 14) {
                MWStyle.strF(g, font, "Missed", x + 16, ly, MWStyle.NEGATIVE, false);
                String n = String.valueOf(p.misses);
                MWStyle.strF(g, font, n, x + infoW - 8 - font.width(n), ly, MWStyle.NEGATIVE, false);
            }
            String kill = p.killShot > 0 ? "Kills in " + p.killShot + " shots" : "Doesn't kill in " + p.shots;
            MWStyle.strF(g, font, MWStyle.ellipsize(font, kill, infoW - 10), x + 9, y + h - 13, p.killShot > 0 ? MWStyle.POSITIVE : MWStyle.NEGATIVE, false);
        }

        float bx0 = x + infoW + 4, bx1 = x + w - 4, by0 = y + 19, by1 = y + h - 3;
        float s = Math.max(1f / kDev, (float) Math.floor(((by1 - by0 - 4) / 32f) * kDev) / kDev);
        float cx = snap((bx0 + bx1) / 2f), ground = snap(by1 - 2);
        // the target: pure white, one separate block per Limb Damage zone (1 model pixel apart), Steve proportions (32 px = 1.8 m)
        Batch b = new Batch(g);
        int white = 0xFFFFFFFF;
        float ins = 0.5f;
        float[][] blocks = {
                {-4, 24, 4, 32}, {-4, 17, 4, 24}, {-4, 12, 4, 17},
                {-8, 12, -4, 24}, {4, 12, 8, 24},
                {-4, 2, 0, 12}, {0, 2, 4, 12},
                {-4, 0, 0, 2}, {0, 0, 4, 2}
        };
        for (float[] k : blocks) {
            rect(b, snap(cx + (k[0] + ins) * s), snap(ground - (k[3] - ins) * s), snap(cx + (k[2] - ins) * s), snap(ground - (k[1] + ins) * s),
                    bx0, by0, bx1, by1, white);
        }
        b.end();
        DamageModel.Zone[] zoneList = DamageModel.Zone.values();
        float[] labelY = {28f, 20.5f, 14.5f, 18f, 7f, 1f};
        for (int i = 0; i < zoneList.length; i++) {
            DamageModel.Zone z = zoneList[i];
            if (!DamageModel.zoneShown(z)) {
                continue; // per-part labels belong to Limb Damage
            }
            boolean left = z == DamageModel.Zone.ARMS;
            String label = z.label + " \u00d7" + String.format(Locale.ROOT, "%.2f", DamageModel.zoneMultiplier(c, z));
            int ty = Math.round(ground - labelY[i] * s) - 4;
            float edge = left ? cx - 9 * s : cx + 9 * s;
            int lw = font.width(label);
            int tx = left ? Math.round(edge) - lw - 6 : Math.round(edge) + 6;
            if (tx >= bx0 && tx + lw <= bx1 && ty >= by0 && ty + 8 <= by1) {
                MWStyle.fillF(g, left ? tx + lw + 1 : tx - 4, ty + 4, left ? tx + lw + 5 : tx - 1, ty + 5, z.color);
                MWStyle.strF(g, font, label, tx, ty, MWStyle.TEXT_DIM, false);
            }
        }

        float aimY = ground - 20.5f * s;
        Batch f = new Batch(g);
        float t = 2f / kDev;
        if (!spray) {
            for (int i : new int[]{4, 2, 0, 1}) {
                float half = (float) (DamageModel.radius(distance, c.stanceSpread()[i]) / MODEL_PX_M) * s;
                float shownHalf = an("sq" + i, half);
                float x0 = snap(cx - shownHalf), x1 = snap(cx + shownHalf), y0 = snap(aimY - shownHalf), y1 = snap(aimY + shownHalf);
                int col = STANCE_COLOR[i];
                float u = 1f / kDev;
                rect(f, x0, y0, x1, y1, bx0, by0, bx1, by1, alphaOf(col, 0.10f));
                rect(f, x0 - u, y0 - u, x1 + u, y0 + t + u, bx0, by0, bx1, by1, 0xE6101216);
                rect(f, x0 - u, y1 - t - u, x1 + u, y1 + u, bx0, by0, bx1, by1, 0xE6101216);
                rect(f, x0 - u, y0 - u, x0 + t + u, y1 + u, bx0, by0, bx1, by1, 0xE6101216);
                rect(f, x1 - t - u, y0 - u, x1 + u, y1 + u, bx0, by0, bx1, by1, 0xE6101216);
                rect(f, x0, y0, x1, y0 + t, bx0, by0, bx1, by1, alphaOf(col, 1f));
                rect(f, x0, y1 - t, x1, y1, bx0, by0, bx1, by1, alphaOf(col, 1f));
                rect(f, x0, y0, x0 + t, y1, bx0, by0, bx1, by1, alphaOf(col, 1f));
                rect(f, x1 - t, y0, x1, y1, bx0, by0, bx1, by1, alphaOf(col, 1f));
            }
        } else {
            Pattern p = pt;
            float ds = Math.max(2f / kDev, s * 1.1f);
            float prevX = 0, prevY = 0;
            for (int i = 0; i < p.hits.size(); i++) {
                Shot sh = p.hits.get(i);
                float dx = cx + sh.xPx() * s, dy = ground - sh.yPx() * s;
                float tt = p.hits.size() <= 1 ? 0f : i / (float) (p.hits.size() - 1);
                int col = MWStyle.blend(0xFF2EDC6E, 0xFFFF3B30, tt);
                boolean in = dx - ds >= bx0 && dx + ds <= bx1 && dy - ds >= by0 && dy + ds <= by1;
                if (i > 0 && in) {
                    f.line(prevX, prevY, dx, dy, 0.5f, alphaOf(0xFF101216, 0.45f));
                }
                if (in) {
                    f.quad(snap(dx - ds), snap(dy - ds), snap(dx + ds), snap(dy + ds), 0xFF101216);
                    f.quad(snap(dx - ds) + 1f / kDev, snap(dy - ds) + 1f / kDev, snap(dx + ds) - 1f / kDev, snap(dy + ds) - 1f / kDev, col);
                }
                prevX = dx;
                prevY = dy;
            }
        }
        f.quad(snap(cx) - 4f / kDev, snap(aimY), snap(cx) + 4f / kDev, snap(aimY) + 1f / kDev, 0xFF101216);
        f.quad(snap(cx), snap(aimY) - 4f / kDev, snap(cx) + 1f / kDev, snap(aimY) + 4f / kDev, 0xFF101216);
        f.end();
        if (spray && pt.hits.size() > 0) {
            Shot first = pt.hits.get(0), last = pt.hits.get(pt.hits.size() - 1);
            float fx = cx + first.xPx() * s, fy = ground - first.yPx() * s, lx = cx + last.xPx() * s, lyy = ground - last.yPx() * s;
            if (fx > bx0 + 8 && fx < bx1 - 8 && fy > by0 + 8 && fy < by1 - 4) {
                MWStyle.strF(g, font, "1", (int) fx + 4, (int) fy - 9, MWStyle.TEXT, false);
            }
            if (lx > bx0 + 8 && lx < bx1 - 14 && lyy > by0 + 8 && lyy < by1 - 4) {
                MWStyle.strF(g, font, String.valueOf(pt.shots), (int) lx + 4, (int) lyy - 9, MWStyle.TEXT_DIM, false);
            }
        }
    }
}
