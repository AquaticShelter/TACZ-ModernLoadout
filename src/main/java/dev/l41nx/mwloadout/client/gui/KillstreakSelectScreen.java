package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.StreakPool;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modern Warfare style killstreak loadout: three equipped slots on the left, the whole pool as a grid
 * grouped by kill requirement (lowest to highest) on the right. A class brings at most THREE rewards and
 * never two with the same kill cost - clicking a second reward from a tier you already filled swaps it
 * (and says so), a fourth distinct one is refused with a warning, clicking an equipped one removes it.
 * Changes go straight into the class being edited; the loadout screen's own dirty-tracking saves them.
 */
public final class KillstreakSelectScreen extends MwScreen {

    private static final int MARGIN = 12;
    private static final int SLOT_H = 50;
    private static final int TILE_H = 76;
    private static final int HEADER_H = 16;
    private static final int GAP = 6;

    private record Cell(int kind, int kills, int poolIndex, int x, int y, int w, int h) {
        // kind 0 = tier header, 1 = reward tile (positions are in content space, below the grid top)
    }

    private final Screen parent;
    private final LoadoutPreset working;
    private List<String> equipped = new ArrayList<>();      // sorted by kills (what is drawn / stored)
    private List<String> order = new ArrayList<>();         // pick order, oldest first (who gets swapped out when full)
    private final java.util.Map<String, Long> popAt = new java.util.HashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private int contentH;
    private double scroll;
    private double scrollTarget;
    private boolean draggingScroll;
    private double grab;

    private long openedAt = System.currentTimeMillis();
    private long closingAt;
    private String toast = "";
    private int toastColor = MWStyle.TEXT;
    private long toastUntil;
    private int hoveredPool = -1;

    private int leftX, leftW, gridX, gridY, gridW, gridH, previewY, previewH;

    public KillstreakSelectScreen(Screen parent, LoadoutPreset working) {
        super(Component.translatable("screen.mwloadout.killstreak_title"));
        this.parent = parent;
        this.working = working;
    }

    private Rules rules() {
        return LoadoutRegistry.get().rules();
    }

    @Override
    protected void init() {
        closingAt = 0;
        MWStyle.screenOpened();
        openedAt = System.currentTimeMillis();
        equipped = new ArrayList<>(StreakPool.effectiveIds(rules(), working));
        order = new ArrayList<>(equipped);
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width - MARGIN - 74, height - 28, 74, 20,
                Component.translatable("screen.mwloadout.back"), b -> onClose())).accent());

        leftX = MARGIN;
        leftW = Mth.clamp(width * 3 / 10, 150, 210);
        gridX = leftX + leftW + 12;
        gridY = 40;
        gridW = width - gridX - MARGIN - 8;
        gridH = height - gridY - 36;
        previewY = gridY + 3 * (SLOT_H + GAP) + 18;
        previewH = Math.max(0, height - 36 - previewY);
        buildCells();
    }

    /** Pool grouped into tiers (same kill cost), ascending; tiles wrap inside each tier. */
    private void buildCells() {
        cells.clear();
        int cols = Math.max(1, (gridW - 8 + GAP) / (92 + GAP));
        int tileW = (gridW - 8 - (cols - 1) * GAP) / cols;
        int y = 4;
        int lastKills = Integer.MIN_VALUE;
        int col = 0;
        for (int index : StreakPool.sortedPool(rules())) {
            int kills = rules().streakRewards.get(index).kills;
            if (kills != lastKills) {
                if (lastKills != Integer.MIN_VALUE) {
                    y += TILE_H + GAP;
                }
                cells.add(new Cell(0, kills, -1, 4, y, gridW - 8, HEADER_H));
                y += HEADER_H + 4;
                lastKills = kills;
                col = 0;
            } else if (col >= cols) {
                col = 0;
                y += TILE_H + GAP;
            }
            cells.add(new Cell(1, kills, index, 4 + col * (tileW + GAP), y, tileW, TILE_H));
            col++;
        }
        contentH = y + (cells.isEmpty() ? 0 : TILE_H) + 8;
    }

    private int killsOf(int poolIndex) {
        return rules().streakRewards.get(poolIndex).kills;
    }

    private String idOf(int poolIndex) {
        return rules().streakRewards.get(poolIndex).itemId;
    }

    private void say(String text, int color) {
        toast = text;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 3200;
    }

    private void choose(String itemId) {
        StreakPool.Outcome outcome = StreakPool.select(rules(), order, itemId);
        String name = DisplayNames.ofAny(itemId).toUpperCase(Locale.ROOT);
        String other = DisplayNames.ofAny(outcome.other()).toUpperCase(Locale.ROOT);
        switch (outcome.status()) {
            case StreakPool.OK -> {
                say("\u2713 EQUIPPED  " + name, 0xFF5FD38D);
                MWStyle.playClick();
            }
            case StreakPool.REPLACED -> {
                int index = StreakPool.indexOf(rules(), itemId);
                say("SAME KILLS (" + (index >= 0 ? killsOf(index) : "?") + ")  REPLACED  " + other, 0xFFF5B945);
                MWStyle.playClick();
            }
            case StreakPool.DISPLACED -> {
                // all three slots were taken by different costs: the oldest pick made way - no manual unequip
                say("REPLACED  " + other + "  \u2192  " + name, 0xFFF5B945);
                MWStyle.playClick();
            }
            case StreakPool.REMOVED -> {
                say("REMOVED  " + name, MWStyle.TEXT_DIM);
                MWStyle.playTab();
            }
            default -> {
                MWStyle.playTab();
                return;
            }
        }
        if (outcome.status() != StreakPool.REMOVED) {
            popAt.put(itemId, System.currentTimeMillis());
        }
        order = new ArrayList<>(outcome.order());
        equipped = new ArrayList<>(outcome.ids());
        working.streaks = new ArrayList<>(equipped); // from now on this class has an explicit selection
    }

    /** Another equipped reward with the same kill cost (this tile would swap it). */
    private String conflictWith(int poolIndex) {
        String id = idOf(poolIndex);
        if (equipped.contains(id)) {
            return null;
        }
        for (String other : equipped) {
            int otherIndex = StreakPool.indexOf(rules(), other);
            if (otherIndex >= 0 && killsOf(otherIndex) == killsOf(poolIndex)) {
                return other;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(g, width, MWStyle.tr("screen.mwloadout.killstreak_title"), openedAt);
        float dt = MWStyle.frameDt();
        long now = System.currentTimeMillis();
        int maxScroll = Math.max(0, contentH - (gridH - 8));
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll);
        scroll = draggingScroll ? scrollTarget : MWStyle.approach((float) scroll, (float) scrollTarget, 0.5f, dt);

        renderSlots(g, mouseX, mouseY);
        hoveredPool = -1;
        renderGrid(g, mouseX, mouseY, now);
        renderPreview(g);

        // footer: warning / confirmation line
        if (now < toastUntil) {
            MWStyle.scrollText(g, font, toast, MARGIN, height - 24, width - 2 * MARGIN - 90, toastColor, true);
        } else {
            MWStyle.strF(g, font, MWStyle.ellipsize(font, "Pick up to " + StreakPool.MAX_SELECTED
                    + ". One per kill cost - picking more swaps the oldest.", width - 2 * MARGIN - 90), MARGIN, height - 24, MWStyle.TEXT_DIM, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
        transitionRender(g);
    }

    private void renderSlots(GuiGraphics g, int mouseX, int mouseY) {
        MWStyle.strF(g, font, "EQUIPPED  " + equipped.size() + "/" + StreakPool.MAX_SELECTED, leftX, gridY - 12, MWStyle.TEXT_DIM, false);
        for (int i = 0; i < StreakPool.MAX_SELECTED; i++) {
            int y = gridY + i * (SLOT_H + GAP);
            boolean filled = i < equipped.size();
            boolean hover = filled && mouseX >= leftX && mouseX < leftX + leftW && mouseY >= y && mouseY < y + SLOT_H;
            MWStyle.fillF(g, leftX, y, leftX + leftW, y + SLOT_H, hover ? MWStyle.PANEL_HOVER : MWStyle.PANEL);
            MWStyle.hairline(g, leftX, y, leftW, SLOT_H, filled ? MWStyle.borderActive() : MWStyle.border());
            MWStyle.strF(g, font, String.valueOf(i + 1), leftX + 7, y + 6, filled ? MWStyle.accent() : MWStyle.TEXT_DIM, false);
            if (!filled) {
                MWStyle.strF(g, font, "EMPTY SLOT", leftX + 24, y + SLOT_H / 2 - 4, MWStyle.TEXT_DIM, false);
                continue;
            }
            String id = equipped.get(i);
            int index = StreakPool.indexOf(rules(), id);
            long slotPop = popAt.getOrDefault(id, 0L);
            MWStyle.sweepFill(g, leftX + 1, y + 1, leftW - 2, SLOT_H - 2, slotPop, 420, MWStyle.accent());
            ItemStack icon = IconCache.anyIcon(id);
            if (!icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(leftX + 22 + 14, y + 11 + 14, 0);
                g.pose().scale(1.75f, 1.75f, 1f);
                g.pose().translate(-8, -8, 0);
                g.renderItem(icon, 0, 0);
                g.pose().popPose();
            }
            MWStyle.scrollText(g, font, DisplayNames.ofAny(id), leftX + 56, y + 14, leftW - 62, MWStyle.TEXT, hover);
            if (index >= 0) {
                String badge = killsOf(index) + " KILLS";
                MWStyle.strF(g, font, badge, leftX + 56, y + 30, MWStyle.accent(), false);
            }
            if (hover) {
                MWStyle.strF(g, font, "CLICK TO REMOVE", leftX + leftW - 6 - font.width("CLICK TO REMOVE"), y + SLOT_H - 11,
                        MWStyle.TEXT_DIM, false);
            }
        }
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY, long now) {
        MWStyle.fillF(g, gridX - 4, gridY - 4, gridX + gridW + 4 + 8, gridY + gridH + 2, MWStyle.withAlpha(MWStyle.PANEL, 0.6f));
        MWStyle.hairline(g, gridX - 4, gridY - 4, gridW + 16, gridH + 6, MWStyle.border());
        if (cells.isEmpty()) {
            MWStyle.strF(g, font, MWStyle.ellipsize(font, "No killstreaks are available on this server.", gridW), gridX + 4, gridY + 6,
                    MWStyle.TEXT_DIM, false);
            return;
        }
        g.enableScissor(gridX - 3, gridY - 3, gridX + gridW + 4, gridY + gridH + 1);
        boolean full = equipped.size() >= StreakPool.MAX_SELECTED;
        for (Cell cell : cells) {
            int cy = gridY + cell.y() - (int) scroll;
            if (cy + cell.h() < gridY - 4 || cy > gridY + gridH) {
                continue;
            }
            int cx = gridX + cell.x();
            if (cell.kind() == 0) {
                MWStyle.strF(g, font, cell.kills() + " KILLS", cx, cy + 4, MWStyle.accent(), false);
                MWStyle.fillF(g, cx + font.width(cell.kills() + " KILLS") + 6, cy + 8, cx + cell.w(), cy + 9, MWStyle.border());
                continue;
            }
            int index = cell.poolIndex();
            String id = idOf(index);
            boolean isEquipped = equipped.contains(id);
            String conflict = conflictWith(index);
            boolean blockedFull = false; // a full set no longer blocks: the oldest pick is swapped out
            boolean hover = mouseX >= cx && mouseX < cx + cell.w() && mouseY >= cy && mouseY < cy + cell.h()
                    && mouseY >= gridY && mouseY < gridY + gridH && !draggingScroll;
            if (hover) {
                hoveredPool = index;
            }
            long tilePop = popAt.getOrDefault(id, 0L);
            MWStyle.fillF(g, cx, cy, cx + cell.w(), cy + cell.h(), hover ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
            int border = isEquipped ? MWStyle.accent() : conflict != null ? 0xFFF5B945 : hover ? MWStyle.borderActive() : MWStyle.border();
            MWStyle.hairline(g, cx, cy, cell.w(), cell.h(), border);
            if (isEquipped) {
                MWStyle.fillF(g, cx, cy, cx + cell.w(), cy + 2, MWStyle.accent());
            }
            ItemStack icon = IconCache.anyIcon(id);
            if (!icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(cx + cell.w() / 2f - 16, cy + 10, 0);
                g.pose().scale(2f, 2f, 1f);
                g.renderItem(icon, 0, 0);
                g.pose().popPose();
            }
            if (blockedFull || conflict != null && !hover) {
                MWStyle.fillF(g, cx + 1, cy + 1, cx + cell.w() - 1, cy + cell.h() - 1, MWStyle.withAlpha(0xFF000000, blockedFull ? 0.5f : 0.3f));
            }
            String name = DisplayNames.ofAny(id);
            int nameW = cell.w() - 8;
            if (font.width(name) <= nameW) {
                MWStyle.strF(g, font, name, cx + cell.w() / 2 - font.width(name) / 2, cy + cell.h() - 14,
                        isEquipped ? MWStyle.accent() : MWStyle.TEXT, false);
            } else {
                MWStyle.scrollText(g, font, name, cx + 4, cy + cell.h() - 14, nameW, isEquipped ? MWStyle.accent() : MWStyle.TEXT, hover);
            }
            if (isEquipped) {
                MWStyle.strF(g, font, "\u2713", cx + cell.w() - 11, cy + 5, MWStyle.accent(), false);
            } else if (conflict != null) {
                MWStyle.strF(g, font, "SWAP", cx + cell.w() - 6 - font.width("SWAP"), cy + 5, 0xFFF5B945, false);
            }
            MWStyle.strF(g, font, String.valueOf(cell.kills()), cx + 6, cy + 5, isEquipped ? MWStyle.accent() : MWStyle.TEXT_DIM, false);
            MWStyle.sweepFill(g, cx, cy, cell.w(), cell.h(), tilePop, 380, MWStyle.accent());
        }
        g.disableScissor();
        int maxScroll = Math.max(0, contentH - (gridH - 8));
        if (maxScroll > 0) {
            int viewH = gridH;
            int barH = Mth.clamp(viewH * viewH / Math.max(1, contentH), 18, viewH);
            int barY = gridY + (int) (scroll / maxScroll * (viewH - barH));
            int tx = gridX + gridW + 4;
            MWStyle.fillF(g, tx, gridY, tx + 5, gridY + viewH, MWStyle.PANEL_RAISED);
            MWStyle.fillF(g, tx, barY, tx + 5, barY + barH, draggingScroll ? 0xFFFFFFFF : MWStyle.accent());
        }
    }

    private void renderPreview(GuiGraphics g) {
        if (previewH < 60) {
            return;
        }
        MWStyle.strF(g, font, "PREVIEW", leftX, previewY - 12, MWStyle.TEXT_DIM, false);
        MWStyle.fillF(g, leftX, previewY, leftX + leftW, previewY + previewH, MWStyle.withAlpha(0xFF0A0D12, 0.7f));
        MWStyle.hairline(g, leftX, previewY, leftW, previewH, MWStyle.border());
        int index = hoveredPool;
        if (index < 0 && !equipped.isEmpty()) {
            index = StreakPool.indexOf(rules(), equipped.get(equipped.size() - 1));
        }
        if (index < 0) {
            return;
        }
        String id = idOf(index);
        ItemStack icon = IconCache.anyIcon(id);
        if (!icon.isEmpty()) {
            float sc = Math.min(Math.min(previewH - 34, leftW - 20) / 16f, 5f);
            float bob = (float) Math.sin(System.currentTimeMillis() / 600.0) * 2f;
            g.pose().pushPose();
            g.pose().translate(leftX + leftW / 2f - 8 * sc, previewY + (previewH - 22) / 2f - 8 * sc + bob, 0);
            g.pose().scale(sc, sc, 1f);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        MWStyle.fillF(g, leftX + 1, previewY + previewH - 18, leftX + leftW - 1, previewY + previewH - 1, MWStyle.withAlpha(0xFF000000, 0.55f));
        String label = DisplayNames.ofAny(id).toUpperCase(Locale.ROOT) + "  \u2022  " + killsOf(index) + " KILLS";
        MWStyle.scrollText(g, font, label, leftX + 6, previewY + previewH - 13, leftW - 12, MWStyle.TEXT, true);
    }

    // ------------------------------------------------------------------ input

    private int maxScroll() {
        return Math.max(0, contentH - (gridH - 8));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        if (button == 0) {
            // equipped slots: click to remove
            for (int i = 0; i < equipped.size(); i++) {
                int y = gridY + i * (SLOT_H + GAP);
                if (mouseX >= leftX && mouseX < leftX + leftW && mouseY >= y && mouseY < y + SLOT_H) {
                    choose(equipped.get(i));
                    return true;
                }
            }
            // scrollbar
            int tx = gridX + gridW + 4;
            if (maxScroll() > 0 && mouseX >= tx - 3 && mouseX < tx + 9 && mouseY >= gridY && mouseY < gridY + gridH) {
                int barH = Mth.clamp(gridH * gridH / Math.max(1, contentH), 18, gridH);
                int barY = gridY + (int) (scroll / maxScroll() * (gridH - barH));
                grab = mouseY >= barY && mouseY < barY + barH ? mouseY - barY : barH / 2.0;
                draggingScroll = true;
                dragTo(mouseY);
                return true;
            }
            // pool tiles
            for (Cell cell : cells) {
                if (cell.kind() != 1) {
                    continue;
                }
                int cx = gridX + cell.x();
                int cy = gridY + cell.y() - (int) scroll;
                if (mouseX >= cx && mouseX < cx + cell.w() && mouseY >= cy && mouseY < cy + cell.h()
                        && mouseY >= gridY && mouseY < gridY + gridH) {
                    choose(idOf(cell.poolIndex()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void dragTo(double mouseY) {
        int barH = Mth.clamp(gridH * gridH / Math.max(1, contentH), 18, gridH);
        int range = Math.max(1, gridH - barH);
        double ratio = Mth.clamp((mouseY - grab - gridY) / range, 0.0, 1.0);
        scrollTarget = ratio * maxScroll();
        scroll = scrollTarget;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingScroll) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScroll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollTarget = Mth.clamp(scrollTarget - delta * 30, 0, maxScroll());
        return true;
    }

    @Override
    public void onClose() {
        if (closingAt <= 0) {
            closingAt = System.currentTimeMillis();
            MWStyle.beginClose(true);
        }
    }

    private void transitionRender(GuiGraphics g) {
        float in = 1f - MWStyle.openProgress(openedAt);
        float out = MWStyle.closeProgress(closingAt);
        MWStyle.veil(g, 0, 0, width, height, Math.max(in * 0.9f, out));
        if (closingAt > 0 && System.currentTimeMillis() - closingAt >= MWStyle.closeMillis()) {
            MWStyle.handoff();
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
