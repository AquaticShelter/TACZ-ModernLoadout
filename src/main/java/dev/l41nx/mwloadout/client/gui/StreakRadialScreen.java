package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientEvents;
import dev.l41nx.mwloadout.client.StreakHud;
import dev.l41nx.mwloadout.client.StreakRadial;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Killstreak radial: hold X, move to a slot, release to choose. One wedge per streak the class has equipped - lit when the item is in the
 * inventory, dim when it is not earned yet - and an X wedge at the bottom that puts the previous weapon back in hand. A TACZ gun is simply taken in
 * hand; any other item is taken in hand and right-clicked (see StreakRadial), because streak items come from many mods. You keep walking while it is open.
 */
public final class StreakRadialScreen extends Screen {
    private static final float R_IN = 46f, R_OUT = 98f, DEAD = 22f;

    private record Entry(String id, int kills, boolean ready) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private float[] centers;
    private float half;
    private long openedAt;
    private boolean wasDown = true;
    private int hovered = -1; // -1 none, entries.size() = the X wedge

    public StreakRadialScreen() {
        super(Component.translatable("key.mwloadout.streak_radial"));
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        entries.clear();
        String[] ids = StreakHud.idsArray();
        int[] kills = StreakHud.killsArray();
        // highest cost first, like the killstreak card
        for (int i = ids.length - 1; i >= 0; i--) {
            if (ids[i] != null && !ids[i].isEmpty()) {
                entries.add(new Entry(ids[i], kills[i], StreakRadial.find(ids[i]) >= 0));
            }
        }
        int n = entries.size();
        centers = new float[n + 1];
        // streaks spread over the upper 270 degrees, the X wedge sits straight down
        for (int i = 0; i < n; i++) {
            centers[i] = -135f + (i + 0.5f) * 270f / Math.max(1, n);
        }
        centers[n] = 180f;
        half = Math.min(42f, 270f / Math.max(1, n) / 2f - 1.5f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        // keep walking: the movement keys follow the real keyboard while the cursor is free
        Minecraft mc = Minecraft.getInstance();
        for (KeyMapping key : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                mc.options.keyJump, mc.options.keySprint, mc.options.keyShift}) {
            KeyMapping.set(key.getKey(), StreakRadial.isHeld(key));
        }
        boolean down = StreakRadial.isHeld(ClientEvents.STREAK_RADIAL);
        if (wasDown && !down) {
            wasDown = false;
            confirm(); // release = choose what is under the cursor (nothing under it = just close)
        }
    }

    private void confirm() {
        int n = entries.size();
        if (hovered < 0) {
            onClose();
            return;
        }
        if (hovered == n) {
            StreakRadial.cancel();
            MWStyle.playClick();
            onClose();
            return;
        }
        Entry entry = entries.get(hovered);
        if (!entry.ready() || !StreakRadial.activate(entry.id())) {
            MWStyle.playTab(); // not earned / already spent
            onClose();
            return;
        }
        MWStyle.playClick();
        onClose();
    }

    private int hoverAt(double mouseX, double mouseY) {
        float dx = (float) (mouseX - width / 2.0), dy = (float) (mouseY - height / 2.0);
        float r = (float) Math.sqrt(dx * dx + dy * dy);
        if (r < DEAD || r > R_OUT + 40f) {
            return -1;
        }
        float angle = (float) Math.toDegrees(Math.atan2(dx, -dy)); // 0 = up, clockwise
        int best = -1;
        float bestDiff = 999f;
        for (int i = 0; i < centers.length; i++) {
            float diff = Math.abs(((angle - centers[i] + 540f) % 360f) - 180f);
            float limit = i == entries.size() ? 36f : half + 3f;
            if (diff <= limit && diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x38000000); // a light veil, the game stays readable
        hovered = hoverAt(mouseX, mouseY);
        float cx = width / 2f, cy = height / 2f;
        float open = MWStyle.easeOutCubic(Math.min(1f, (System.currentTimeMillis() - openedAt) / 140f));
        float rIn = R_IN * (0.75f + 0.25f * open), rOut = R_OUT * (0.75f + 0.25f * open);
        int n = entries.size();
        for (int i = 0; i <= n; i++) {
            boolean isX = i == n;
            boolean ready = isX || entries.get(i).ready();
            boolean hot = i == hovered && ready;
            float a0 = centers[i] - (isX ? 32f : half), a1 = centers[i] + (isX ? 32f : half);
            int fill = hot ? MWStyle.withAlpha(MWStyle.signal(), 0.55f * open)
                    : MWStyle.withAlpha(0xFF0B0C0E, (ready ? 0.74f : 0.45f) * open);
            MWStyle.ringSector(g, cx, cy, rIn, rOut, a0, a1, fill);
            MWStyle.ringSector(g, cx, cy, rOut - 1.5f, rOut, a0, a1, MWStyle.withAlpha(hot ? MWStyle.signal() : MWStyle.border(), open));
            double mid = Math.toRadians(centers[i]);
            float ix = cx + (float) Math.sin(mid) * (rIn + rOut) / 2f, iy = cy - (float) Math.cos(mid) * (rIn + rOut) / 2f;
            if (isX) {
                int c = hot ? 0xFF0B0C0E : MWStyle.TEXT;
                float s = 7f;
                MWStyle.poly(g, MWStyle.withAlpha(c, open), ix - s, iy - s + 1.4f, ix - s + 1.8f, iy - s, ix + s, iy + s - 1.4f, ix + s - 1.8f, iy + s);
                MWStyle.poly(g, MWStyle.withAlpha(c, open), ix + s - 1.8f, iy - s, ix + s, iy - s + 1.4f, ix - s + 1.8f, iy + s, ix - s, iy + s - 1.4f);
                continue;
            }
            Entry entry = entries.get(i);
            ItemStack icon = IconCache.anyIcon(entry.id());
            if (!icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(ix, iy - 4, 0);
                g.pose().scale(1.6f, 1.6f, 1f);
                g.pose().translate(-8, -8, 0);
                if (!ready) {
                    com.mojang.blaze3d.systems.RenderSystem.setShaderColor(0.55f, 0.55f, 0.55f, 0.7f);
                }
                g.renderItem(icon, 0, 0);
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                g.pose().popPose();
            }
            String badge = "\u25C6 " + entry.kills();
            MWStyle.strF(g, font, badge, (int) (ix - font.width(badge) / 2f), (int) (iy + 12), ready ? MWStyle.TEXT : MWStyle.LOCKED, false);
        }
        // centre readout
        String line1 = "", line2 = "";
        if (hovered >= 0 && hovered < n) {
            line1 = DisplayNames.ofAny(entries.get(hovered).id());
            line2 = MWStyle.tr(entries.get(hovered).ready() ? "screen.mwloadout.radial_ready" : "screen.mwloadout.radial_locked");
        } else if (hovered == n) {
            line1 = MWStyle.tr("screen.mwloadout.radial_cancel");
        } else if (n == 0) {
            line1 = MWStyle.tr("screen.mwloadout.radial_none");
        }
        if (!line1.isEmpty()) {
            MWStyle.centerF(g, font, MWStyle.ellipsize(font, line1.toUpperCase(), (int) (rIn * 2 - 8)), (int) cx, (int) cy - 8, MWStyle.TEXT);
            if (!line2.isEmpty()) {
                MWStyle.centerF(g, font, line2, (int) cx, (int) cy + 4, hovered >= 0 && hovered < n && entries.get(hovered).ready() ? MWStyle.signal() : MWStyle.LOCKED);
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
