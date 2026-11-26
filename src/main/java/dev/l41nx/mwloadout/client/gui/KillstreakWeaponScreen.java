package dev.l41nx.mwloadout.client.gui;

import com.tacz.guns.api.item.IGun;
import dev.l41nx.mwloadout.client.gui.admin.ItemBrowserScreen;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.server.StreakItemFactory;
import dev.l41nx.mwloadout.util.Json;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The killstreak page: a big preview stage, the streak ladder as a row of slot cards, and (admin) an
 * editor panel to choose what each slot rewards. Rewards can be ANY item - TACZ guns get the spinning
 * 3D model, everything else (drones, beacons, vanilla items, modded gadgets) is shown as a large item
 * icon - and can be picked from the catalog list, typed as an id, or chosen from the whole creative
 * inventory via the Creative Browser.
 *
 * <p>Layout is computed once per init from fixed rows - stage, ladder, editor panel, footer - so nothing
 * can overlap whatever the window size. (The old version re-added every widget on each +SLOT / -SLOT
 * press and reloaded the ladder from the server each time, so slot edits vanished and buttons stacked
 * on top of each other.)
 */
public final class KillstreakWeaponScreen extends MwScreen {

    /** Size of the POOL admins can offer; players equip at most 3 of it (one per kill cost). */
    private static final int MAX_SLOTS = 50;
    private static final int COLS_MAX = 8;
    private static final int MIN_CARD_W = 70;
    /** Ladder rows visible at once - anything beyond scrolls inside its own viewport. */
    private static final int LADDER_VIEW_ROWS = 2;
    private static final int CARD_GAP = 6;
    /** "+ Slot" with Shift held adds this many at once (filling a 50-slot pool one click at a time is tedious). */
    private static final int ADD_BULK = 5;
    private static final int MARGIN = 12;
    private static final int LADDER_CARD_H = 46;
    private static final int ROW_H = 16;

    private final Screen parent;
    private final boolean admin;
    private final GunPreview preview = new GunPreview();
    private final List<Rules.StreakReward> ladder = new ArrayList<>();
    private final List<CatalogEntry> pickable = new ArrayList<>();
    private List<CatalogEntry> filtered = new ArrayList<>();
    private boolean ladderLoaded;
    private int selected;

    private EditBox itemIdBox;
    private EditBox searchBox;
    /** TACZ guns only: bullets in the magazine / reserve rounds the reward is granted with (blank = the legacy one-round gun). */
    private EditBox magBox;
    private EditBox reserveBox;
    private boolean syncingAmmo;
    private long openedAt = System.currentTimeMillis();
    private long closingAt;

    private ItemStack previewStack = ItemStack.EMPTY;
    private boolean previewIsGun;
    private boolean stageDragging;

    // fixed-row geometry, resolved in layout()
    private int stageX, stageY, stageW, stageH;
    private int ladderY, slotW, slotStartX, ladderViewH, ladderContentH, ladderCols, ladderTotalW;
    private boolean ladderScrollable;
    private double ladderScroll, ladderScrollTarget;
    private boolean draggingLadder;
    private double ladderGrab;
    private int editorX, editorY, editorW, editorH;
    private int listTop, listBottom;
    private double listScroll, listScrollTarget;
    private boolean draggingList;
    private double listGrab;
    private String toast = "";
    private long toastUntil;

    public KillstreakWeaponScreen(Screen parent) {
        this(parent, false);
    }

    public KillstreakWeaponScreen(Screen parent, boolean admin) {
        super(Component.translatable("screen.mwloadout.killstreak_title"));
        this.parent = parent;
        this.admin = admin;
    }

    private static String norm(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static String idOf(CatalogEntry entry) {
        if (entry.kind == CatalogEntry.Kind.GUN) {
            return entry.gameId;
        }
        return entry.gameId + (entry.nbt == null || entry.nbt.isBlank() ? "" : entry.nbt);
    }

    @Override
    protected void init() {
        closingAt = 0;
        MWStyle.screenOpened();
        openedAt = System.currentTimeMillis();
        if (!ladderLoaded) {
            // Only the FIRST init pulls the ladder from the registry. Later inits (window resize, +SLOT,
            // -SLOT) must keep the admin's unsaved edits.
            ladder.clear();
            for (Rules.StreakReward r : LoadoutRegistry.get().rules().streakRewards) {
                ladder.add(r.copy());
            }
            ladder.sort((a, b) -> Integer.compare(a.kills, b.kills));
            ladderLoaded = true;
        }
        selected = Mth.clamp(selected, 0, Math.max(0, ladder.size() - 1));

        if (admin) {
            pickable.clear();
            for (CatalogEntry e : LoadoutRegistry.get().catalogList()) {
                if (e.enabled && e.kind != CatalogEntry.Kind.ATTACHMENT) {
                    pickable.add(e);
                }
            }
            pickable.sort((a, b) -> {
                int c = a.category.compareToIgnoreCase(b.category);
                return c != 0 ? c : a.displayName.compareToIgnoreCase(b.displayName);
            });
        }
        layout();

        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width - MARGIN - 74, height - 26, 74, 18,
                Component.translatable("screen.mwloadout.back"), b -> onClose())).accent());
        if (admin) {
            addRenderableWidget(MWStyle.flatButton(MARGIN, height - 26, 74, 18,
                    Component.translatable("screen.mwloadout.save"), b -> save()));
            addRenderableWidget(MWStyle.flatButton(MARGIN + 80, height - 26, 74, 18,
                    Component.translatable("screen.mwloadout.ks_add"), b -> addSlot()));
            addRenderableWidget(MWStyle.flatButton(MARGIN + 160, height - 26, 74, 18,
                    Component.translatable("screen.mwloadout.ks_remove"), b -> removeSlot()));

            // Item id of the selected slot: ANY id works (catalog id, tacz:rpg7, mod:item, mod:item{nbt}).
            int idX = editorX + 46;
            int browseW = 76;
            itemIdBox = new EditBox(font, idX, editorY + 6, editorW - 46 - 8 - browseW - 4, 16, Component.empty());
            itemIdBox.setBordered(true);
            itemIdBox.setTextColor(MWStyle.TEXT);
            itemIdBox.setMaxLength(256);
            itemIdBox.setHint(Component.literal("catalog id, tacz:rpg7, mod:item or mod:item{nbt}"));
            if (!ladder.isEmpty()) {
                itemIdBox.setValue(ladder.get(selected).itemId);
            }
            itemIdBox.setResponder(text -> {
                if (!ladder.isEmpty() && selected < ladder.size()) {
                    ladder.get(selected).itemId = text.trim();
                    refreshPreview();
                }
            });
            addRenderableWidget(itemIdBox);
            addRenderableWidget(MWStyle.flatButton(editorX + editorW - 8 - browseW, editorY + 5, browseW, 18,
                    Component.literal("BROWSE"), b -> {
                        if (!ladder.isEmpty()) {
                            minecraft.setScreen(new ItemBrowserScreen(this, id -> {
                                ladder.get(selected).itemId = id;
                                itemIdBox.setValue(id);
                                refreshPreview();
                            }));
                        }
                    }));

            searchBox = new EditBox(font, editorX + 66, editorY + 29, editorW - 66 - 70, 16, Component.empty());
            searchBox.setBordered(true);
            searchBox.setTextColor(MWStyle.TEXT);
            searchBox.setMaxLength(60);
            searchBox.setHint(Component.literal("Filter by name, id or category"));
            searchBox.setResponder(text -> {
                listScrollTarget = 0;
                applyFilter();
            });
            addRenderableWidget(searchBox);
            magBox = ammoBox(editorX + 66, true);
            reserveBox = ammoBox(editorX + 186, false);
            applyFilter();
        }
        refreshPreview();
    }

    private EditBox ammoBox(int x, boolean magazine) {
        EditBox box = new EditBox(font, x, editorY + 49, 52, 16, Component.empty());
        box.setBordered(true);
        box.setTextColor(MWStyle.TEXT);
        box.setMaxLength(4);
        box.setFilter(text -> text.matches("\\d*"));
        box.setHint(Component.literal(magazine ? "1" : "0"));
        box.setResponder(text -> {
            if (syncingAmmo || ladder.isEmpty() || selected >= ladder.size()) {
                return;
            }
            int value = text.isEmpty() ? -1 : Rules.StreakReward.clampAmmo(Integer.parseInt(text));
            Rules.StreakReward reward = ladder.get(selected);
            if (magazine) {
                reward.mag = value;
            } else {
                reward.reserve = value;
            }
        });
        addRenderableWidget(box);
        return box;
    }

    /** Shows the bullets / reserve boxes only while the selected reward is a TACZ gun, filled from that slot. */
    private void updateAmmoFields() {
        if (magBox == null || reserveBox == null) {
            return;
        }
        boolean on = previewIsGun && !ladder.isEmpty() && selected < ladder.size();
        magBox.visible = on;
        magBox.active = on;
        reserveBox.visible = on;
        reserveBox.active = on;
        if (!on) {
            return; // a stale value on a non-gun is simply ignored by the server
        }
        Rules.StreakReward reward = ladder.get(selected);
        syncingAmmo = true;
        String mag = reward.mag < 0 ? "" : String.valueOf(reward.mag);
        String reserve = reward.reserve < 0 ? "" : String.valueOf(reward.reserve);
        if (!magBox.getValue().equals(mag)) {
            magBox.setValue(mag);
        }
        if (!reserveBox.getValue().equals(reserve)) {
            reserveBox.setValue(reserve);
        }
        syncingAmmo = false;
    }

    /** Fixed rows, top to bottom: header, stage, ladder, editor panel, footer. */
    private void layout() {
        int top = 40;
        int footer = 34;
        int editorMin = admin ? 136 : 0;
        // The pool wraps into rows of cards; only LADDER_VIEW_ROWS rows are visible, the rest scrolls (up to 50 slots).
        int count = Math.max(1, ladder.size());
        int gap = 8;
        int avail = width - 2 * MARGIN;
        int viewRows = Math.min(LADDER_VIEW_ROWS, rows(count, cardCols(avail, gap, count)));
        // short window: keep the editor usable by showing a single ladder row instead of squeezing the stage away
        if (viewRows > 1 && height - (top + footer + viewH(viewRows) + 16 + editorMin) < 70) {
            viewRows = 1;
        }
        ladderCols = cardCols(avail, gap, count);
        ladderScrollable = rows(count, ladderCols) > viewRows;
        if (ladderScrollable) {
            avail -= 10; // room for the scrollbar
            ladderCols = cardCols(avail, gap, count);
        }
        int ladderRows = rows(count, ladderCols);
        ladderContentH = ladderRows * LADDER_CARD_H + (ladderRows - 1) * CARD_GAP;
        ladderViewH = viewH(Math.min(viewRows, ladderRows));
        ladderScrollable = ladderContentH > ladderViewH;
        ladderScrollTarget = Mth.clamp(ladderScrollTarget, 0, ladderMaxScroll());
        ladderScroll = Mth.clamp(ladderScroll, 0, ladderMaxScroll());
        int reserved = top + footer + ladderViewH + 16 + editorMin;
        stageH = Mth.clamp(height - reserved, 56, admin ? 170 : 260);
        stageW = Math.min(admin ? 420 : 480, width - 2 * MARGIN);
        stageX = (width - stageW) / 2;
        stageY = top;
        ladderY = stageY + stageH + 8;
        slotW = Mth.clamp((avail - (ladderCols - 1) * gap) / ladderCols, MIN_CARD_W, 150);
        ladderTotalW = ladderCols * slotW + (ladderCols - 1) * gap;
        slotStartX = (width - ladderTotalW - (ladderScrollable ? 10 : 0)) / 2;
        editorX = MARGIN;
        editorW = width - 2 * MARGIN;
        editorY = ladderY + ladderViewH + 8;
        editorH = Math.max(0, height - 34 - editorY);
        listTop = editorY + 68;
        listBottom = editorY + editorH - 6;
    }

    private static int cardCols(int avail, int gap, int count) {
        return Mth.clamp((avail + gap) / (MIN_CARD_W + gap), 1, Math.min(COLS_MAX, count));
    }

    private static int rows(int count, int cols) {
        return (count + cols - 1) / cols;
    }

    private static int viewH(int rows) {
        return rows * LADDER_CARD_H + (rows - 1) * CARD_GAP;
    }

    private int ladderMaxScroll() {
        return Math.max(0, ladderContentH - ladderViewH);
    }

    /** Scrolls the ladder just enough that card {@code index} is fully visible. */
    private void revealSlot(int index) {
        if (!ladderScrollable || index < 0) {
            return;
        }
        int top = (index / ladderCols) * (LADDER_CARD_H + CARD_GAP);
        int bottom = top + LADDER_CARD_H;
        if (top < ladderScrollTarget) {
            ladderScrollTarget = top;
        } else if (bottom > ladderScrollTarget + ladderViewH) {
            ladderScrollTarget = bottom - ladderViewH;
        }
        ladderScrollTarget = Mth.clamp(ladderScrollTarget, 0, ladderMaxScroll());
    }

    private int slotX(int i) {
        return slotStartX + (i % ladderCols) * (slotW + 8);
    }

    /** Card top in screen space (already shifted by the ladder scroll). */
    private int slotY(int i) {
        return ladderY + (i / ladderCols) * (LADDER_CARD_H + CARD_GAP) - (int) ladderScroll;
    }

    private boolean inLadderView(double mouseX, double mouseY) {
        return mouseX >= slotStartX && mouseX < slotStartX + ladderTotalW && mouseY >= ladderY && mouseY < ladderY + ladderViewH;
    }

    private void applyFilter() {
        String q = norm(searchBox == null ? "" : searchBox.getValue()).trim();
        List<CatalogEntry> out = new ArrayList<>();
        for (CatalogEntry e : pickable) {
            if (q.isEmpty() || norm(DisplayNames.of(e)).contains(q) || norm(e.gameId).contains(q)
                    || norm(e.category).contains(q) || norm(e.id).contains(q)) {
                out.add(e);
            }
        }
        filtered = out;
    }

    private void refreshPreview() {
        previewStack = ItemStack.EMPTY;
        previewIsGun = false;
        if (selected < 0 || selected >= ladder.size()) {
            updateAmmoFields();
            return;
        }
        try {
            previewStack = StreakItemFactory.build(ladder.get(selected).itemId);
            IGun gun = previewStack.isEmpty() ? null : IGun.getIGunOrNull(previewStack);
            if (gun != null && gun.getGunId(previewStack) != null) {
                previewIsGun = true;
                preview.update(gun.getGunId(previewStack).toString(), java.util.Map.of());
            }
        } catch (Throwable t) {
            previewStack = ItemStack.EMPTY;
        }
        updateAmmoFields();
    }

    private void toast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2800;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(g, width,
                Component.translatable(admin ? "screen.mwloadout.killstreak_admin_title"
                        : "screen.mwloadout.killstreak_title").getString(), openedAt);
        float dt = MWStyle.frameDt();
        listScroll = draggingList ? listScrollTarget : MWStyle.approach((float) listScroll, (float) listScrollTarget, 0.5f, dt);
        ladderScrollTarget = Mth.clamp(ladderScrollTarget, 0, ladderMaxScroll());
        ladderScroll = draggingLadder ? ladderScrollTarget : MWStyle.approach((float) ladderScroll, (float) ladderScrollTarget, 0.5f, dt);

        if (ladder.isEmpty()) {
            MWStyle.centerF(g, font, MWStyle.tr("screen.mwloadout.killstreak_empty"),
                    width / 2, height / 2 - 10, MWStyle.TEXT_DIM);
            if (admin) {
                MWStyle.centerF(g, font, "Press + SLOT to add the first streak reward", width / 2, height / 2 + 4, MWStyle.TEXT_DIM);
            }
            super.render(g, mouseX, mouseY, partialTick);
            transitionRender(g);
            return;
        }
        selected = Mth.clamp(selected, 0, ladder.size() - 1);
        Rules.StreakReward sel = ladder.get(selected);

        renderStage(g, sel);
        renderLadder(g, mouseX, mouseY);
        if (admin) {
            renderEditor(g, mouseX, mouseY, sel);
        }

        // footer toast: sits between SAVE/+SLOT/-SLOT and BACK
        if (admin && System.currentTimeMillis() < toastUntil) {
            int tx = MARGIN + 240;
            MWStyle.scrollText(g, font, toast, tx, height - 22, width - MARGIN - 80 - tx - 8, MWStyle.TEXT, true);
        }

        super.render(g, mouseX, mouseY, partialTick);
        transitionRender(g);
    }

    private void renderStage(GuiGraphics g, Rules.StreakReward sel) {
        MWStyle.fillF(g, stageX, stageY, stageX + stageW, stageY + stageH, MWStyle.withAlpha(0xFF0A0D12, 0.7f));
        MWStyle.hairline(g, stageX, stageY, stageW, stageH, MWStyle.border());
        if (previewIsGun) {
            preview.render(stageX + 6, stageY + 6, stageW - 12, stageH - 12, 60f);
        } else if (!previewStack.isEmpty()) {
            // any non-gun item: one big, gently floating icon
            float sc = Math.min(Math.min(stageH - 30, stageW - 30) / 16f, 7f);
            float bob = (float) Math.sin(System.currentTimeMillis() / 600.0) * 2f;
            g.pose().pushPose();
            g.pose().translate(stageX + stageW / 2f - 8 * sc, stageY + stageH / 2f - 8 * sc - 4 + bob, 0);
            g.pose().scale(sc, sc, 1f);
            g.renderItem(previewStack, 0, 0);
            g.pose().popPose();
        } else {
            MWStyle.centerF(g, font, sel.itemId.isBlank() ? "NO ITEM SET" : "UNKNOWN ITEM", stageX + stageW / 2,
                    stageY + stageH / 2 - 8, sel.itemId.isBlank() ? MWStyle.TEXT_DIM : MWStyle.NEGATIVE);
            if (!sel.itemId.isBlank()) {
                MWStyle.centerF(g, font, MWStyle.ellipsize(font, sel.itemId, stageW - 20), stageX + stageW / 2,
                        stageY + stageH / 2 + 4, MWStyle.TEXT_DIM);
            }
        }
        // overlays: kill threshold top-left, slot counter top-right, item name on a dim strip along the bottom
        MWStyle.strF(g, font, Component.translatable("screen.mwloadout.streak_at", sel.kills).getString(),
                stageX + 8, stageY + 6, MWStyle.accent(), false);
        String slotCount = "SLOT " + (selected + 1) + " / " + ladder.size() + (admin ? "  (MAX " + MAX_SLOTS + ")" : "");
        MWStyle.strF(g, font, slotCount, stageX + stageW - 8 - font.width(slotCount), stageY + 6, MWStyle.TEXT_DIM, false);
        MWStyle.fillF(g, stageX + 1, stageY + stageH - 16, stageX + stageW - 1, stageY + stageH - 1, MWStyle.withAlpha(0xFF000000, 0.55f));
        String name = DisplayNames.ofAny(sel.itemId);
        MWStyle.centerF(g, font, MWStyle.ellipsize(font, name.isEmpty() ? "-" : name, stageW - 20),
                stageX + stageW / 2, stageY + stageH - 12, MWStyle.TEXT);
    }

    private void renderLadder(GuiGraphics g, int mouseX, int mouseY) {
        g.enableScissor(slotStartX - 1, ladderY, slotStartX + ladderTotalW + 1, ladderY + ladderViewH);
        for (int i = 0; i < ladder.size(); i++) {
            int x = slotX(i);
            int y = slotY(i);
            if (y + LADDER_CARD_H < ladderY || y > ladderY + ladderViewH) {
                continue; // culled: off-screen rows cost nothing (no icon lookups, no text)
            }
            Rules.StreakReward r = ladder.get(i);
            boolean isSel = i == selected;
            boolean hover = inLadderView(mouseX, mouseY) && mouseX >= x && mouseX < x + slotW && mouseY >= y && mouseY < y + LADDER_CARD_H;
            MWStyle.tile(g, x, y, slotW, LADDER_CARD_H, hover ? 1f : 0f, isSel);
            // row 1: kill threshold (+ steppers on the selected slot, admin)
            MWStyle.strF(g, font, MWStyle.ellipsize(font, Component.translatable("screen.mwloadout.kill_count", r.kills).getString(),
                    slotW - (admin && isSel ? 40 : 12)), x + 6, y + 6, isSel ? MWStyle.accent() : MWStyle.TEXT, false);
            if (admin && isSel) {
                stepper(g, x + slotW - 30, y + 4, "-", mouseX, mouseY);
                stepper(g, x + slotW - 15, y + 4, "+", mouseX, mouseY);
            }
            // row 2: item icon + name (slides when too long)
            ItemStack icon = IconCache.anyIcon(r.itemId);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x + 6, y + 22);
            } else {
                MWStyle.fillF(g, x + 6, y + 22, x + 22, y + 38, MWStyle.PANEL_RAISED);
                MWStyle.centerF(g, font, "?", x + 14, y + 26, MWStyle.TEXT_DIM);
            }
            MWStyle.scrollText(g, font, DisplayNames.ofAny(r.itemId), x + 26, y + 26, slotW - 32,
                    isSel ? MWStyle.TEXT : MWStyle.TEXT_DIM, hover || isSel);
            if (r.hasAmmoConfig()) { // bullets + reserve this gun is granted with
                MWStyle.strF(g, font, MWStyle.ellipsize(font, Math.max(0, r.mag) + " + " + Math.max(0, r.reserve), slotW - 32),
                        x + 26, y + 35, MWStyle.TEXT_DIM, false);
            }
        }
        g.disableScissor();
        if (ladderScrollable) {
            int[] t = ladderThumb();
            int tx = slotStartX + ladderTotalW + 4;
            MWStyle.fillF(g, tx, ladderY, tx + 5, ladderY + ladderViewH, MWStyle.PANEL_RAISED);
            MWStyle.fillF(g, tx, t[0], tx + 5, t[0] + t[1], draggingLadder ? 0xFFFFFFFF : MWStyle.accent());
        }
    }

    /** {barY, barH, maxScroll} of the ladder scrollbar. */
    private int[] ladderThumb() {
        int maxScroll = ladderMaxScroll();
        int barH = Mth.clamp(ladderViewH * ladderViewH / Math.max(1, ladderContentH), 16, ladderViewH);
        int barY = ladderY + (maxScroll == 0 ? 0 : (int) (ladderScroll / maxScroll * (ladderViewH - barH)));
        return new int[]{barY, barH, maxScroll};
    }

    private void dragLadderTo(double mouseY) {
        int[] t = ladderThumb();
        int range = Math.max(1, ladderViewH - t[1]);
        double ratio = Mth.clamp((mouseY - ladderGrab - ladderY) / range, 0.0, 1.0);
        ladderScrollTarget = ratio * t[2];
        ladderScroll = ladderScrollTarget;
    }

    private void stepper(GuiGraphics g, int x, int y, String glyph, int mouseX, int mouseY) {
        boolean hov = mouseX >= x && mouseX < x + 12 && mouseY >= y && mouseY < y + 12;
        MWStyle.fillF(g, x, y, x + 12, y + 12, hov ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
        MWStyle.hairline(g, x, y, 12, 12, hov ? MWStyle.borderActive() : MWStyle.border());
        MWStyle.centerF(g, font, glyph, x + 6, y + 2, hov ? MWStyle.TEXT : MWStyle.TEXT_DIM);
    }

    private void renderEditor(GuiGraphics g, int mouseX, int mouseY, Rules.StreakReward sel) {
        MWStyle.fillF(g, editorX, editorY, editorX + editorW, editorY + editorH, MWStyle.withAlpha(MWStyle.PANEL, 0.6f));
        MWStyle.hairline(g, editorX, editorY, editorW, editorH, MWStyle.border());
        MWStyle.strF(g, font, "ITEM", editorX + 8, editorY + 10, MWStyle.TEXT_DIM, false);
        MWStyle.strF(g, font, "CATALOG", editorX + 8, editorY + 33, MWStyle.TEXT_DIM, false);
        String count = filtered.size() + "/" + pickable.size();
        MWStyle.strF(g, font, count, editorX + editorW - 8 - font.width(count), editorY + 33, MWStyle.TEXT_DIM, false);
        boolean gun = magBox != null && magBox.visible;
        MWStyle.strF(g, font, "BULLETS", editorX + 8, editorY + 53, gun ? MWStyle.TEXT_DIM : MWStyle.LOCKED, false);
        MWStyle.strF(g, font, "RESERVE", editorX + 128, editorY + 53, gun ? MWStyle.TEXT_DIM : MWStyle.LOCKED, false);
        String ammoHint = gun ? "blank = 1 round, no reserve. Gone once ammo + reserve are spent; announced on the first shot."
                : "Bullets / reserve apply to TACZ guns only.";
        MWStyle.strF(g, font, MWStyle.ellipsize(font, ammoHint, Math.max(40, editorW - 246 - 8)), editorX + 246, editorY + 53, MWStyle.TEXT_DIM, false);
        MWStyle.fillF(g, editorX + 6, editorY + 67, editorX + editorW - 6, editorY + 68, MWStyle.border());

        int rows = Math.max(0, (listBottom - listTop) / ROW_H);
        int contentH = filtered.size() * ROW_H;
        int viewH = Math.max(1, listBottom - listTop);
        int maxScroll = Math.max(0, contentH - viewH);
        listScrollTarget = Mth.clamp(listScrollTarget, 0, maxScroll);
        listScroll = Mth.clamp(listScroll, 0, maxScroll);
        if (filtered.isEmpty()) {
            MWStyle.strF(g, font, MWStyle.ellipsize(font, "No catalog entries match - type an id above, or press BROWSE.", editorW - 16),
                    editorX + 8, listTop + 4, MWStyle.TEXT_DIM, false);
        }
        int rowW = editorW - 24;
        g.enableScissor(editorX + 6, listTop, editorX + 6 + rowW + 6, listBottom);
        int first = Math.max(0, (int) (listScroll / ROW_H));
        for (int i = first; i < filtered.size() && i <= first + rows + 1; i++) {
            CatalogEntry e = filtered.get(i);
            int ry = listTop + i * ROW_H - (int) listScroll;
            boolean hover = mouseX >= editorX + 6 && mouseX < editorX + 6 + rowW && mouseY >= ry && mouseY < ry + ROW_H - 1
                    && mouseY >= listTop && mouseY < listBottom && !draggingList;
            boolean current = idOf(e).equals(sel.itemId);
            MWStyle.fillF(g, editorX + 6, ry, editorX + 6 + rowW, ry + ROW_H - 1, hover ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
            if (current) {
                MWStyle.fillF(g, editorX + 6, ry, editorX + 8, ry + ROW_H - 1, MWStyle.accent());
            }
            ItemStack icon = IconCache.iconFor(e);
            if (!icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(editorX + 12, ry + 1, 0);
                g.pose().scale(0.8f, 0.8f, 1f);
                g.renderItem(icon, 0, 0);
                g.pose().popPose();
            }
            String tag = e.category.toUpperCase(Locale.ROOT);
            int tagW = Math.min(70, font.width(tag));
            MWStyle.scrollText(g, font, DisplayNames.of(e), editorX + 28, ry + 4, rowW - 28 - tagW - 14,
                    current ? MWStyle.accent() : MWStyle.TEXT, hover || current);
            MWStyle.strF(g, font, MWStyle.ellipsize(font, tag, 70), editorX + 6 + rowW - 6 - tagW, ry + 4, MWStyle.TEXT_DIM, false);
        }
        g.disableScissor();
        // scrollbar (draggable)
        if (maxScroll > 0) {
            int barH = Mth.clamp(viewH * viewH / Math.max(1, contentH), 16, viewH);
            int barY = listTop + (int) (listScroll / maxScroll * (viewH - barH));
            int tx = editorX + editorW - 12;
            MWStyle.fillF(g, tx, listTop, tx + 6, listBottom, MWStyle.PANEL_RAISED);
            MWStyle.fillF(g, tx, barY, tx + 6, barY + barH, draggingList ? 0xFFFFFFFF : MWStyle.accent());
        }
    }

    // ------------------------------------------------------------------ input

    private int[] thumb() {
        int viewH = Math.max(1, listBottom - listTop);
        int contentH = Math.max(1, filtered.size() * ROW_H);
        int maxScroll = Math.max(0, contentH - viewH);
        int barH = Mth.clamp(viewH * viewH / contentH, 16, viewH);
        int barY = listTop + (maxScroll == 0 ? 0 : (int) (listScroll / maxScroll * (viewH - barH)));
        return new int[]{barY, barH, maxScroll};
    }

    private void dragListTo(double mouseY) {
        int[] t = thumb();
        int range = Math.max(1, (listBottom - listTop) - t[1]);
        double ratio = Mth.clamp((mouseY - listGrab - listTop) / range, 0.0, 1.0);
        listScrollTarget = ratio * t[2];
        listScroll = listScrollTarget;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        // ladder scrollbar
        if (ladderScrollable && button == 0) {
            int[] t = ladderThumb();
            int tx = slotStartX + ladderTotalW + 4;
            if (mouseX >= tx - 3 && mouseX < tx + 9 && mouseY >= ladderY && mouseY < ladderY + ladderViewH) {
                ladderGrab = mouseY >= t[0] && mouseY < t[0] + t[1] ? mouseY - t[0] : t[1] / 2.0;
                draggingLadder = true;
                dragLadderTo(mouseY);
                return true;
            }
        }
        // slot cards: steppers on the selected one first, then selection (only what is inside the viewport)
        for (int i = 0; inLadderView(mouseX, mouseY) && i < ladder.size(); i++) {
            int x = slotX(i);
            int cardY = slotY(i);
            if (mouseX >= x && mouseX < x + slotW && mouseY >= cardY && mouseY < cardY + LADDER_CARD_H) {
                if (admin && i == selected && button == 0 && mouseY < cardY + 18) {
                    int step = hasShiftDown() ? 5 : 1;
                    if (mouseX >= x + slotW - 30 && mouseX < x + slotW - 18) {
                        ladder.get(i).kills = Math.max(1, ladder.get(i).kills - step);
                        MWStyle.playClick();
                        return true;
                    }
                    if (mouseX >= x + slotW - 15 && mouseX < x + slotW - 3) {
                        ladder.get(i).kills = Math.min(99, ladder.get(i).kills + step);
                        MWStyle.playClick();
                        return true;
                    }
                }
                if (selected != i) {
                    selected = i;
                    MWStyle.playTab();
                    refreshPreview();
                    if (itemIdBox != null) {
                        itemIdBox.setValue(ladder.get(selected).itemId);
                    }
                }
                revealSlot(i); // a half-hidden card scrolls fully into view
                return true;
            }
        }
        if (admin && !ladder.isEmpty() && button == 0) {
            // editor list: scrollbar first, then row pick
            int[] t = thumb();
            int tx = editorX + editorW - 12;
            if (t[2] > 0 && mouseX >= tx - 4 && mouseX < tx + 10 && mouseY >= listTop && mouseY < listBottom) {
                listGrab = mouseY >= t[0] && mouseY < t[0] + t[1] ? mouseY - t[0] : t[1] / 2.0;
                draggingList = true;
                dragListTo(mouseY);
                return true;
            }
            int rowW = editorW - 24;
            if (mouseX >= editorX + 6 && mouseX < editorX + 6 + rowW && mouseY >= listTop && mouseY < listBottom) {
                int index = (int) ((mouseY - listTop + listScroll) / ROW_H);
                if (index >= 0 && index < filtered.size()) {
                    String id = idOf(filtered.get(index));
                    ladder.get(selected).itemId = id;
                    itemIdBox.setValue(id);
                    refreshPreview();
                    MWStyle.playClick();
                    return true;
                }
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled && previewIsGun && mouseX >= stageX && mouseX < stageX + stageW && mouseY >= stageY && mouseY < stageY + stageH) {
            preview.beginDrag();
            stageDragging = true;
            return true;
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingLadder) {
            dragLadderTo(mouseY);
            return true;
        }
        if (draggingList) {
            dragListTo(mouseY);
            return true;
        }
        if (stageDragging) {
            preview.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingList = false;
        draggingLadder = false;
        if (stageDragging) {
            preview.endDrag();
            stageDragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (ladderScrollable && mouseX >= slotStartX && mouseX < slotStartX + ladderTotalW + 12
                && mouseY >= ladderY && mouseY < ladderY + ladderViewH) {
            ladderScrollTarget = Mth.clamp(ladderScrollTarget - delta * (LADDER_CARD_H + CARD_GAP) * 0.75, 0, ladderMaxScroll());
            return true;
        }
        if (admin && mouseX >= editorX && mouseX < editorX + editorW && mouseY >= editorY && mouseY < editorY + editorH) {
            int[] t = thumb();
            listScrollTarget = Mth.clamp(listScrollTarget - delta * ROW_H * 2, 0, t[2]);
            return true;
        }
        if (previewIsGun && mouseX >= stageX && mouseX < stageX + stageW && mouseY >= stageY && mouseY < stageY + stageH) {
            preview.zoom(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (admin && hasControlDown() && keyCode == 83) { // Ctrl+S
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ slot ops

    private void addSlot() {
        if (ladder.size() >= MAX_SLOTS) {
            toast("MAX " + MAX_SLOTS + " SLOTS");
            return;
        }
        int toAdd = Math.min(hasShiftDown() ? ADD_BULK : 1, MAX_SLOTS - ladder.size());
        for (int n = 0; n < toAdd; n++) {
            int nextKills = ladder.isEmpty() ? 3 : Math.min(99, ladder.get(ladder.size() - 1).kills + 2);
            String copyId = ladder.isEmpty() ? "" : ladder.get(ladder.size() - 1).itemId;
            ladder.add(new Rules.StreakReward(nextKills, copyId));
        }
        selected = ladder.size() - 1;
        MWStyle.playClick();
        rebuildWidgets(); // clears the old widgets first - re-running init() alone stacked duplicates
        revealSlot(selected);
        toast("SLOTS " + ladder.size() + "/" + MAX_SLOTS);
    }

    private void removeSlot() {
        if (ladder.size() <= 1) {
            toast("KEEP AT LEAST ONE SLOT");
            return;
        }
        ladder.remove(Math.min(selected, ladder.size() - 1));
        selected = Math.max(0, selected - 1);
        MWStyle.playClick();
        rebuildWidgets();
        revealSlot(selected);
        toast("SLOTS " + ladder.size() + "/" + MAX_SLOTS);
    }

    private void save() {
        Rules current = LoadoutRegistry.get().rules();
        current.streakRewards.clear();
        java.util.Set<String> seenItems = new java.util.HashSet<>();
        for (Rules.StreakReward r : ladder) {
            if (r.itemId == null || r.itemId.isBlank()) {
                continue; // an empty slot has nothing to give
            }
            // players pick pool entries BY item id, so the same item can only appear once
            if (!seenItems.add(r.itemId.trim())) {
                continue;
            }
            current.streakRewards.add(new Rules.StreakReward(r.kills, r.itemId.trim(), r.mag, r.reserve));
        }
        current.streakRewards.sort((a, b) -> Integer.compare(a.kills, b.kills));
        Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate("RULES", "UPSERT", Json.GSON.toJson(current.toJson())));
        MWStyle.playClick();
        onClose();
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
