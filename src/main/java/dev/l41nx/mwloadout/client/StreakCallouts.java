package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.client.gui.DisplayNames;
import dev.l41nx.mwloadout.client.gui.IconCache;
import dev.l41nx.mwloadout.client.gui.MWStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * "X used a killstreak" banners (Modern Warfare callouts): item icon on the left, the player's name on top and
 * the killstreak's name below, BLUE for allies (same scoreboard team, and yourself) and RED for enemies.
 * Everything is configurable in the client config [hud]: anchor (top_left by default), offsets, scale, duration,
 * how many stack, both colours, and the icon. Banners slide in from the anchored side and retract after.
 * Fed by Net.S2CStreakUse. Cheap per frame: no allocations beyond the visible entries.
 */
public final class StreakCallouts {

    private static final long IN_MS = 260;
    private static final long OUT_MS = 320;

    private record Entry(String player, String itemId, boolean ally, boolean self, long start) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private StreakCallouts() {
    }

    public static void push(String player, String itemId, boolean ally) {
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && mc.player.getGameProfile().getName().equals(player);
        if (self ? !ClientCfg.STREAK_CALLOUT_SHOW_SELF.get()
                : ally ? !ClientCfg.STREAK_CALLOUT_SHOW_ALLY.get() : !ClientCfg.STREAK_CALLOUT_SHOW_ENEMY.get()) {
            return;
        }
        ENTRIES.add(new Entry(player, itemId, ally, self, System.currentTimeMillis()));
        int max = ClientCfg.STREAK_CALLOUT_MAX.get();
        while (ENTRIES.size() > max) {
            ENTRIES.remove(0);
        }
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private static String anchor() {
        try {
            return ScreenAnchor.orDefault(ClientCfg.STREAK_CALLOUT_ANCHOR.get());
        } catch (Exception e) {
            return "top_left";
        }
    }

    public static void render(GuiGraphics g, int screenW, int screenH) {
        if (ENTRIES.isEmpty() || !ClientCfg.STREAK_CALLOUT_ENABLED.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        long now = System.currentTimeMillis();
        long hold = ClientCfg.STREAK_CALLOUT_DURATION_MS.get();
        ENTRIES.removeIf(entry -> now - entry.start() > IN_MS + hold + OUT_MS);
        if (ENTRIES.isEmpty()) {
            return;
        }
        var font = mc.font;
        float scale = ClientCfg.STREAK_CALLOUT_SCALE.get() / 100f;
        int sw = Math.round(screenW / scale);
        int sh = Math.round(screenH / scale);
        String anchor = anchor();
        float nx = ScreenAnchor.normX(anchor);
        float ny = ScreenAnchor.normY(anchor);
        int xOff = ClientCfg.STREAK_CALLOUT_X_OFFSET.get();
        int yOff = ClientCfg.STREAK_CALLOUT_Y_OFFSET.get();
        int allyColor = ClientCfg.color(ClientCfg.STREAK_CALLOUT_ALLY_COLOR, 0xFF2D5C9C);
        int enemyColor = ClientCfg.color(ClientCfg.STREAK_CALLOUT_ENEMY_COLOR, 0xFF8A3030);
        int selfColor = ClientCfg.color(ClientCfg.STREAK_CALLOUT_SELF_COLOR, 0xFFB58A1E);
        boolean icons = ClientCfg.STREAK_CALLOUT_SHOW_ICON.get();
        final int h = 34;
        final int gap = 4;
        int stackH = ENTRIES.size() * h + (ENTRIES.size() - 1) * gap;

        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        // top anchors stack downward, bottom anchors upward (newest nearest the edge), middle centres the stack
        int y = ny == 0f ? yOff : ny == 1f ? sh - yOff - stackH : sh / 2 - stackH / 2 + yOff;
        {   // step out of other HUD mods' elements (ModernHud), measured over the whole stack at its widest
            int cw = 240;
            int cx = nx == 0f ? xOff : nx == 1f ? sw - cw - xOff : sw / 2 - cw / 2 + xOff;
            y += Math.round(dev.l41nx.mwloadout.compat.HudAvoid.dy(cx * scale, y * scale, cw * scale, stackH * scale, ny == 1f) / scale);
        }
        for (Entry entry : ENTRIES) {
            long age = now - entry.start();
            String itemName = DisplayNames.ofAny(entry.itemId());
            int textW = Math.max(font.width(entry.player()), font.width(itemName));
            int w = Mth.clamp(textW + (icons ? 56 : 24), 130, 240);
            int baseX = nx == 0f ? xOff : nx == 1f ? sw - w - xOff : sw / 2 - w / 2 + xOff;
            // slide in from / out to the anchored side (no alpha: icons and text can't be faded cleanly)
            float slide;
            if (age < IN_MS) {
                float t = age / (float) IN_MS;
                slide = (1f - t) * (1f - t);
            } else if (age > IN_MS + hold) {
                float t = (age - IN_MS - hold) / (float) OUT_MS;
                slide = t * t;
            } else {
                slide = 0f;
            }
            int direction = nx >= 1f ? 1 : -1;
            int x = baseX + Math.round(direction * slide * (w + Math.max(xOff, 0) + 8));
            int bg = entry.self() ? selfColor : entry.ally() ? allyColor : enemyColor; // you = gold, allies = blue, enemies = red
            int line = MWStyle.blend(bg, 0xFFFFFFFF, 0.38f);
            int sub = MWStyle.blend(bg, 0xFFFFFFFF, 0.78f);
            if (MWStyle.angular()) {
                // the top corner on the CENTRE-facing side is cut: top-left for a banner on the right of the screen, top-right for one on the left
                boolean cutRight = nx < 0.5f;
                MWStyle.cutFill(g, x, y, w, h, 8, cutRight, MWStyle.withAlpha(bg, 0.88f));
                MWStyle.cutEdge(g, x, y, w, h, 8, cutRight, MWStyle.withAlpha(line, 0.9f));
                g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, line);
            } else {
                g.fill(x, y, x + w, y + h, MWStyle.withAlpha(bg, 0.88f));
                g.fill(x, y + h - 2, x + w, y + h, line);
            }
            int textX = x + 10;
            if (icons) {
                ItemStack icon = IconCache.anyIcon(entry.itemId());
                if (!icon.isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(x + 8, y + 4, 0);
                    g.pose().scale(1.5f, 1.5f, 1f);
                    g.renderItem(icon, 0, 0);
                    g.pose().popPose();
                }
                textX = x + 40;
            }
            g.drawString(font, MWStyle.ellipsize(font, entry.player(), x + w - textX - 6), textX, y + 7, 0xFFFFFFFF, true);
            g.drawString(font, MWStyle.ellipsize(font, itemName, x + w - textX - 6), textX, y + 19, sub, false);
            y += h + gap;
        }
        g.pose().popPose();
    }
}
