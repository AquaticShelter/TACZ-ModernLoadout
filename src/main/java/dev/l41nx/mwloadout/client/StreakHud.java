package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.client.gui.IconCache;
import dev.l41nx.mwloadout.client.gui.MWStyle;
import dev.l41nx.mwloadout.network.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Modern Warfare style killstreak tracker (bottom-right): equipped streaks as bracketed icon tiles plus a
 * segmented vertical meter that fills every kill. Fed by Net.S2CStreakProgress; on/off, scale, position,
 * opacity, colour and auto-hide are in the client config under [hud].
 */
public final class StreakHud {

    private static boolean enabled;
    private static boolean specialist;
    private static float target;
    private static float shown;
    private static boolean seeded;
    private static int[] kills = new int[0];
    private static String[] ids = new String[0];
    private static boolean[] earned = new boolean[0];
    private static long lastChange;
    private static long lastNanos;
    /** Bumped on every update/clear - lets an external HUD (ModernHud via {@link dev.l41nx.mwloadout.api.StreakApi})
     *  re-read the arrays only when something actually changed. */
    private static long version;
    /** True while another HUD (ModernHud) has taken over drawing the tracker: our own tiles/meter then draw
     *  nothing, but all the tracking state below keeps updating exactly the same. */
    private static boolean external;

    private StreakHud() {
    }

    public static void update(Net.S2CStreakProgress msg) {
        long now = System.currentTimeMillis();
        if (Math.abs(msg.progress() - target) > 0.01f || msg.earned().length != earned.length) {
            lastChange = now; // kill, reset or a newly earned rung: wake the HUD (auto-hide) and pulse
        }
        for (int i = 0; i < Math.min(earned.length, msg.earned().length); i++) {
            if (msg.earned()[i] && !earned[i]) {
                lastChange = now;
            }
        }
        enabled = msg.enabled();
        specialist = msg.specialist();
        target = msg.progress();
        kills = msg.kills();
        ids = msg.ids();
        earned = msg.earned();
        if (!seeded) {
            shown = target;
            seeded = true;
        }
        version++;
    }

    // ---- read-only accessors for dev.l41nx.mwloadout.api.StreakApi (internal arrays: callers must not mutate) ----
    public static long version() { return version; }
    public static boolean isEnabled() { return enabled; }
    public static boolean isSpecialist() { return specialist; }
    public static float targetProgress() { return target; }
    public static int[] killsArray() { return kills; }
    public static String[] idsArray() { return ids; }
    public static boolean[] earnedArray() { return earned; }
    public static boolean isExternal() { return external; }
    public static void setExternal(boolean value) { external = value; }

    private static float avoidShown;

    /** eases the avoidance offset so the tracker glides out of the way instead of jumping */
    private static float smoothAvoid(float target, float dt) {
        avoidShown = MWStyle.approach(avoidShown, target, 0.35f, dt);
        if (Math.abs(avoidShown - target) < 0.25f) avoidShown = target;
        return avoidShown;
    }

    public static void clear() {
        enabled = false;
        kills = new int[0];
        ids = new String[0];
        earned = new boolean[0];
        target = 0;
        shown = 0;
        seeded = false;
        version++;
    }

    private static void brackets(GuiGraphics g, int x, int y, int w, int h, int color) {
        int len = 5;
        g.fill(x, y, x + len, y + 1, color);
        g.fill(x, y, x + 1, y + len, color);
        g.fill(x + w - len, y, x + w, y + 1, color);
        g.fill(x + w - 1, y, x + w, y + len, color);
        g.fill(x, y + h - 1, x + len, y + h, color);
        g.fill(x, y + h - len, x + 1, y + h, color);
        g.fill(x + w - len, y + h - 1, x + w, y + h, color);
        g.fill(x + w - 1, y + h - len, x + w, y + h, color);
    }

    /** Modern Warfare layout: the equipped killstreaks stacked as icon tiles with corner brackets (lowest
     *  cost at the bottom), and a segmented vertical meter on the right - one segment per kill needed
     *  for the NEXT reward, filling with every kill. No numbers, no captions: the shape is the message.
     *  Specialist uses the same layout with its three perk icons and the 2/4/6(/8) thresholds. */
    public static void render(GuiGraphics g, int screenW, int screenH) {
        if (external || !ClientCfg.STREAK_HUD_ENABLED.get() || !enabled || kills.length == 0) {
            return; // external: ModernHud draws the tracker instead (see StreakApi.setExternalHud)
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        float dt = lastNanos == 0 ? 1f : Mth.clamp((nanos - lastNanos) / 50_000_000f, 0.02f, 2.5f);
        lastNanos = nanos;
        shown = MWStyle.approach(shown, target, 0.25f, dt); // the meter glides up each kill, and drains on death

        float alpha = ClientCfg.STREAK_HUD_OPACITY.get() / 100f;
        if (ClientCfg.STREAK_HUD_AUTO_HIDE.get()) {
            long idle = now - lastChange;
            alpha *= idle < 5000 ? 1f : Math.max(0f, 1f - (idle - 5000) / 1000f);
        }
        if (alpha < 0.05f) {
            return;
        }
        int color = ClientCfg.color(ClientCfg.STREAK_HUD_COLOR, 0xFFE8C24A);
        final int green = 0xFF5FD38D;
        float scale = ClientCfg.STREAK_HUD_SCALE.get() / 100f;

        int n = kills.length;
        int tiles = Math.min(specialist ? 3 : n, 3);
        int next = -1;
        for (int i = 0; i < n; i++) {
            if (!earned[i]) {
                next = i;
                break;
            }
        }
        boolean pulse = now - lastChange < 700;
        final int tile = 28;
        final int tileGap = 4;
        final int meterW = 5;
        int stackH = tiles * tile + Math.max(0, tiles - 1) * tileGap;

        // keep clear of other HUD mods' elements (ModernHud's weapon / player panel) instead of drawing over them
        float boxW = (meterW + 6 + tile) * scale, boxH = stackH * scale;
        float ax = screenW - ClientCfg.STREAK_HUD_X_OFFSET.get(), ay = screenH - ClientCfg.STREAK_HUD_Y_OFFSET.get();
        ay += smoothAvoid(dev.l41nx.mwloadout.compat.HudAvoid.dy(ax - boxW, ay - boxH, boxW, boxH, true), dt);
        g.pose().pushPose();
        g.pose().translate(ax, ay, 0);
        g.pose().scale(scale, scale, 1f);

        // ---- icon tiles, bottom (cheapest) to top ----
        int tileX = -meterW - 6 - tile;
        for (int i = 0; i < tiles; i++) {
            int y = -(i + 1) * tile - i * tileGap;
            boolean isEarned = earned[i];
            boolean isNext = i == next;
            if (MWStyle.angular()) {
                MWStyle.cutFill(g, tileX, y, tile, tile, 6, true, MWStyle.withAlpha(0xFF0B0E13, 0.62f * alpha)); // outer top corner cut
            } else {
                g.fill(tileX, y, tileX + tile, y + tile, MWStyle.withAlpha(0xFF0B0E13, 0.62f * alpha));
            }
            if (specialist) {
                var perk = ids[i] == null || ids[i].isEmpty() ? null : dev.l41nx.mwloadout.registry.LoadoutRegistry.get().perk(ids[i]);
                if (perk != null && perk.icon != null && !perk.icon.isEmpty()) {
                    g.setColor(1f, 1f, 1f, alpha);
                    dev.l41nx.mwloadout.client.gui.PerkIcons.drawPlain(g, perk.icon, tileX + 4, y + 4, 20);
                    g.setColor(1f, 1f, 1f, 1f);
                }
            } else if (ids[i] != null && !ids[i].isEmpty() && alpha >= 0.85f) {
                ItemStack icon = IconCache.anyIcon(ids[i]);
                if (!icon.isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(tileX + 4, y + 4, 0);
                    g.pose().scale(1.25f, 1.25f, 1f);
                    g.renderItem(icon, 0, 0);
                    g.pose().popPose();
                }
            }
            if (!isEarned) {
                if (MWStyle.angular()) {
                    MWStyle.cutFill(g, tileX + 1, y + 1, tile - 2, tile - 2, 5, true, MWStyle.withAlpha(0xFF000000, 0.5f * alpha)); // not unlocked yet
                } else {
                    g.fill(tileX + 1, y + 1, tileX + tile - 1, y + tile - 1, MWStyle.withAlpha(0xFF000000, 0.5f * alpha)); // not unlocked yet
                }
            }
            int bracket = isEarned ? (pulse ? 0xFFFFFFFF : green) : isNext ? color : 0xFF6B7078;
            if (MWStyle.angular()) {
                MWStyle.cutEdge(g, tileX, y, tile, tile, 6, true, MWStyle.withAlpha(bracket, alpha));
            } else {
                brackets(g, tileX, y, tile, tile, MWStyle.withAlpha(bracket, alpha));
            }
        }

        // ---- segmented meter: one segment per kill needed for the next reward ----
        int segments = Mth.clamp(next >= 0 ? kills[next] : kills[n - 1], 1, 24);
        int gap = segments > 12 ? 1 : 2;
        float segH = Math.max(2f, (stackH - (segments - 1) * gap) / (float) segments);
        int whole = (int) Math.floor(shown + 1e-3f);
        float partial = shown - whole;
        boolean allEarned = next < 0;
        for (int s = 0; s < segments; s++) {
            int bottom = -Math.round(s * (segH + gap));
            int top = bottom - Math.round(segH);
            g.fill(-meterW, top, 0, bottom, MWStyle.withAlpha(0xFF0B0E13, 0.7f * alpha));
            int fillColor = allEarned ? green : color;
            if (allEarned || s < whole) {
                g.fill(-meterW, top, 0, bottom, MWStyle.withAlpha(fillColor, alpha));
            } else if (s == whole && partial > 0.02f) {
                int h = Math.max(1, Math.round((bottom - top) * partial)); // a partial mob point fills part of a segment
                g.fill(-meterW, bottom - h, 0, bottom, MWStyle.withAlpha(fillColor, alpha * 0.85f));
            }
        }
        g.pose().popPose();
    }
}
