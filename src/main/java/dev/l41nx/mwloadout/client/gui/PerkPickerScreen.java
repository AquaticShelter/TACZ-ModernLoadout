package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * PERK SELECT, MW19 layout (per the reference shot): the three perk columns as top tabs — each
 * tab wearing the icon of whatever's equipped in that column — a VERTICAL list of the active
 * column's perks below (icon + name rows, equipped one marked), and a detail card on the right
 * with the big icon, NAME and description of whatever the cursor rests on. Clicking a row equips
 * it in the active column (click the equipped row again to clear); columns switch without
 * leaving the screen, so a full three-perk pass is one visit.
 */
public final class PerkPickerScreen extends MwScreen {

    private final LoadoutScreen parent;
    private final LoadoutPreset working;
    private final List<Rules.PerkSlot> columns;
    private int activeColumn;
    private final List<Row> rows = new ArrayList<>();
    // Specialist integrated into this page (MW): the three perk columns are followed by three
    // "kill tabs" (2/4/6 KILL) plus an activate toggle. When a specialist tab is the active
    // column, the vertical list picks that stage's perk instead of a normal perk slot.
    private static final int[] SPEC_KILLS = {2, 4, 6};
    /** The 4th tab: not a pick, a preview of what the 8-kill full unlock brings (every available perk). */
    private static final int FULL_KILLS = 8;
    private PerkDefinition lastHoverPerk; // detail card hold: no flicker while the cursor crosses the gap between two rows
    private long lastHoverAt;
    private float scroll;                // vertical scroll of the perk list (px)
    private int maxScroll;
    private static final int LIST_TOP = 92;

    private int listBottom() {
        return height - 10;
    }

    /** Killchain / Hardline in a class column while Specialist is switched on. */
    private boolean isLockedPerk(String perkId) {
        return specialistActive && !isSpecTab(activeColumn) && Rules.SPECIALIST_LOCKED.contains(perkId);
    }

    private boolean isFullTab(int col) { return col == columns.size() + SPEC_KILLS.length; }
    private boolean specialistActive;
    /** 0..1: how far the Specialist kill-tabs are pushed out (1) or retracted into the toggle (0). */
    private float specAnim;
    private final java.util.Map<String, Long> popAt = new java.util.HashMap<>();
    private final java.util.List<String> specialistPerks = new java.util.ArrayList<>(java.util.List.of("", "", ""));
    /** activeColumn >= columns.size() means a specialist kill-tab (index = activeColumn - columns.size()). */
    private boolean isSpecTab(int col) { return col >= columns.size(); }
    private boolean specialistFeatureOn() { return LoadoutRegistry.get().rules().specialistEnabled; }
    private int specIndex(int col) { return col - columns.size(); }
    private long openedAt = System.currentTimeMillis();
    private net.minecraft.client.gui.screens.Screen pendingScreen;
    private boolean pendingClose;
    private long closingAt;
    private long listFlashStart;
    private int hoveredIndex = -1;
    private static final int TAB_Y = 34, TAB_H = 58, TOGGLE_H = 24, TOGGLE_W = 92, TAB_GAP = 4;
    private int tabW = 62;
    private int toggleX;
    private int drawerX;

    /** Tab size adapts to the screen so the 3 perk tabs + toggle + 3 kill-tabs always fit. */
    private void layoutTabs() {
        int cols = Math.max(1, columns.size());
        tabW = net.minecraft.util.Mth.clamp((width - 24 - TOGGLE_W - 12) / (cols + SPEC_KILLS.length + 1) - TAB_GAP, 40, 62);
        toggleX = 12 + cols * (tabW + TAB_GAP) + 2;
        drawerX = toggleX + TOGGLE_W + 4;
    }

    private int perkTabX(int i) {
        return 12 + i * (tabW + TAB_GAP);
    }

    /** Each kill-tab starts a little after the previous one (stagger) and finishes before the strip is fully out. */
    private float specTabProgress(int i) {
        float p = net.minecraft.util.Mth.clamp(specAnim * 2.0f - i * 0.25f, 0f, 1f);
        return MWStyle.easeOutCubic(p);
    }

    private int specTabX(int i, float progress) {
        int finalX = drawerX + i * (tabW + TAB_GAP);
        int hiddenX = drawerX - tabW - 2; // fully tucked left of the clip, i.e. behind the toggle
        return Math.round(hiddenX + (finalX - hiddenX) * progress);
    }

    private static int mulAlpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * net.minecraft.util.Mth.clamp(factor, 0f, 1f));
        return (a << 24) | (color & 0xFFFFFF);
    }

    private void drawTab(GuiGraphics graphics, int x, String label, PerkDefinition perk, boolean active, boolean hovered, float alpha) {
        int base = active ? MWStyle.PANEL_RAISED : hovered ? MWStyle.PANEL_HOVER : MWStyle.PANEL;
        MWStyle.fillF(graphics, x, TAB_Y, x + tabW, TAB_Y + TAB_H, mulAlpha(base, alpha));
        MWStyle.hairline(graphics, x, TAB_Y, tabW, TAB_H, mulAlpha(active ? MWStyle.accent() : MWStyle.border(), alpha));
        if (alpha < 0.08f) {
            return; // font rendering treats very low alpha as opaque - skip text/icon while almost invisible
        }
        MWStyle.centerF(graphics, font, label, x + tabW / 2, TAB_Y + 7,
                MWStyle.withAlpha(active ? MWStyle.accent() : MWStyle.TEXT_DIM, alpha));
        if (perk != null && !perk.icon.isEmpty()) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.setColor(1f, 1f, 1f, alpha);
            PerkIcons.draw(graphics, perk.icon, x + (tabW - 24) / 2, TAB_Y + 21, 24);
            graphics.setColor(1f, 1f, 1f, 1f);
        } else {
            MWStyle.centerF(graphics, font, "-", x + tabW / 2, TAB_Y + 30, MWStyle.withAlpha(MWStyle.LOCKED, alpha));
        }
    }

    public PerkPickerScreen(LoadoutScreen parent, LoadoutPreset working, Rules.PerkSlot perkSlot) {
        super(Component.translatable("screen.mwloadout.perk_select"));
        this.parent = parent;
        this.working = working;
        this.columns = LoadoutRegistry.get().rules().perkSlots;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).id.equals(perkSlot.id)) {
                activeColumn = i;
            }
        }
    }

    @Override
    protected void init() {
        closingAt = 0;
        MWStyle.screenOpened();
        pendingScreen = null;
        pendingClose = false;
        openedAt = System.currentTimeMillis();
        dev.l41nx.mwloadout.data.PlayerProfile profile = dev.l41nx.mwloadout.client.ClientCache.profile();
        if (profile != null) {
            specialistActive = profile.specialistActive;
            for (int i = 0; i < 3; i++) {
                specialistPerks.set(i, i < profile.specialistPerks.size() ? profile.specialistPerks.get(i) : "");
            }
            if (specialistActive) {
                autoFillSpecialist(); // Specialist is on but a staged slot is empty: roll random pool perks
            }
        }
        specAnim = specialistActive ? 1f : 0f;
        rebuildRows();
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width - 84, height - 28, 74, 20,
                Component.translatable("screen.mwloadout.back"), button -> onClose())).accent());
    }

    private void rebuildRows() {
        rows.clear();
        if (columns.isEmpty()) {
            return;
        }
        int y = 96;
        scroll = 0f;
        lastHoverPerk = null;
        if (isFullTab(activeColumn)) {
            // 8 KILL: everything Specialist unlocks at once, shown as a read-only list (Killchain / Hardline / Overkill / Underkill never do)
            for (PerkDefinition perk : LoadoutRegistry.get().perkList()) {
                if (perk.enabled && !Rules.SPECIALIST_EXCLUDED.contains(perk.id)) {
                    rows.add(new Row(perk, 12, y, 190, 28));
                    y += 31;
                }
            }
            return;
        }
        if (isSpecTab(activeColumn)) {
            // Specialist stage: offer the allowed pool minus equipped + other specialist picks.
            Rules rules = LoadoutRegistry.get().rules();
            java.util.Set<String> equipped = new java.util.HashSet<>(working.perks.values());
            int me = specIndex(activeColumn);
            java.util.List<String> ordered = new java.util.ArrayList<>(LoadoutRegistry.get().specialistPool());
            // the perk that exists ONLY in this column (Shield Up in the third) goes first, so it is never lost at the bottom of a long list
            ordered.sort(java.util.Comparator.comparingInt((String id) -> {
                PerkDefinition d = LoadoutRegistry.get().perk(id);
                return d != null && d.specialistColumn() == me + 1 ? 0 : 1;
            }));
            for (String perkId : ordered) {
                if (equipped.contains(perkId)) {
                    continue;
                }
                boolean usedElsewhere = false;
                for (int i = 0; i < specialistPerks.size(); i++) {
                    if (i != me && perkId.equals(specialistPerks.get(i))) {
                        usedElsewhere = true;
                        break;
                    }
                }
                if (usedElsewhere) {
                    continue;
                }
                PerkDefinition perk = LoadoutRegistry.get().perk(perkId);
                // Shield Up lives in the THIRD Specialist column only
                if (perk != null && perk.enabled && (perk.specialistColumn() == 0 || perk.specialistColumn() == me + 1)) {
                    rows.add(new Row(perk, 12, y, 190, 28));
                    y += 31;
                }
            }
            return;
        }
        Rules.PerkSlot column = columns.get(activeColumn);
        for (PerkDefinition perk : LoadoutRegistry.get().perkList()) {
            if (perk.enabled && perk.perkSlot.equals(column.id)) {
                rows.add(new Row(perk, 12, y, 190, 28));
                y += 31;
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(graphics, width,
                MWStyle.tr("screen.mwloadout.perk_select"), openedAt);
        if (columns.isEmpty()) {
            super.render(graphics, mouseX, mouseY, partialTick);
            transitionRender(graphics);
            return;
        }

        layoutTabs();

        // Column tabs: each shows the equipped perk's icon (MW19's PERK 1/2/3 header strip).
        for (int i = 0; i < columns.size(); i++) {
            Rules.PerkSlot column = columns.get(i);
            int tx = perkTabX(i);
            boolean hovered = mouseX >= tx && mouseX < tx + tabW && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H;
            PerkDefinition equipped = LoadoutRegistry.get().perk(working.perks.getOrDefault(column.id, ""));
            drawTab(graphics, tx, column.displayName, equipped, i == activeColumn, hovered, 1f);
        }

        // Specialist: a fixed toggle right after the perk tabs, and the three kill-tabs (2/4/6 KILL) that SLIDE OUT of
        // its right edge (staggered, fading in, clipped at the toggle) and slide back in when it is switched off.
        // Nothing is squashed or rescaled any more - the tabs always keep their real size and just glide.
        // Whole strip is hidden when the admin disables the specialist feature.
        if (specialistFeatureOn()) {
            float target = specialistActive ? 1f : 0f;
            specAnim = Math.abs(specAnim - target) < 0.004f ? target : MWStyle.approach(specAnim, target, 0.22f, MWStyle.frameDt());
            if (specAnim > 0.001f) {
                graphics.enableScissor(drawerX, TAB_Y - 1, width, TAB_Y + TAB_H + 1);
                for (int i = SPEC_KILLS.length; i >= 0; i--) { // back to front: earlier tabs stack over later ones (the last one is the 8 KILL preview)
                    float p = specTabProgress(i);
                    if (p <= 0.01f) {
                        continue;
                    }
                    int col = columns.size() + i;
                    int tx = specTabX(i, p);
                    boolean hovered = specAnim > 0.9f && mouseX >= tx && mouseX < tx + tabW
                            && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H;
                    String pick = i < SPEC_KILLS.length ? specialistPerks.get(i) : "";
                    drawTab(graphics, tx, (i < SPEC_KILLS.length ? SPEC_KILLS[i] : FULL_KILLS) + " KILL", pick.isEmpty() ? null : LoadoutRegistry.get().perk(pick),
                            col == activeColumn, hovered, p);
                }
                graphics.disableScissor();
            }
            boolean tHover = mouseX >= toggleX && mouseX < toggleX + TOGGLE_W && mouseY >= TAB_Y && mouseY < TAB_Y + TOGGLE_H;
            MWStyle.fillF(graphics, toggleX, TAB_Y, toggleX + TOGGLE_W, TAB_Y + TOGGLE_H, MWStyle.fa(tHover ? MWStyle.PANEL_HOVER : MWStyle.PANEL));
            MWStyle.hairline(graphics, toggleX, TAB_Y, TOGGLE_W, TOGGLE_H, MWStyle.fa(specialistActive ? MWStyle.accent() : MWStyle.border()));
            MWStyle.textCentered(graphics, font,
                    Component.translatable(specialistActive
                            ? "screen.mwloadout.spec_on" : "screen.mwloadout.spec_off").getString(),
                    toggleX + TOGGLE_W / 2, TAB_Y + (TOGGLE_H - 8) / 2, specialistActive ? MWStyle.accent() : MWStyle.TEXT_DIM);
            MWStyle.textCentered(graphics, font,
                    MWStyle.tr("screen.mwloadout.spec_hint"),
                    toggleX + TOGGLE_W / 2, TAB_Y + TOGGLE_H + 6, MWStyle.TEXT_DIM);
        }

        // Vertical list: either a specialist stage or a normal perk column.
        if (!specialistFeatureOn() && isSpecTab(activeColumn)) {
            activeColumn = 0;
            rebuildRows();
        }
        boolean specTab = isSpecTab(activeColumn);
        Rules.PerkSlot column = specTab ? null : columns.get(activeColumn);
        String equippedId = specTab
                ? (isFullTab(activeColumn) ? "" : specialistPerks.get(specIndex(activeColumn)))
                : working.perks.getOrDefault(column.id, "");
        hoveredIndex = -1;
        int contentBottom = rows.isEmpty() ? LIST_TOP : rows.get(rows.size() - 1).baseY + 28;
        maxScroll = Math.max(0, contentBottom - listBottom() + 4);
        scroll = net.minecraft.util.Mth.clamp(scroll, 0f, maxScroll);
        graphics.enableScissor(0, LIST_TOP - 2, 214, listBottom());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.y = row.baseY - Math.round(scroll);
            if (row.y + row.h < LIST_TOP - 2 || row.y > listBottom()) { // fully outside the scrolled list: no geometry at all
                row.hover = 0f;
                continue;
            }
            // the hit area includes the 3 px gap under the row, so the cursor never falls "between" rows (that toggled the detail card every frame)
            boolean hovered = mouseX >= row.x && mouseX < row.x + row.w && mouseY >= row.y && mouseY < row.y + row.h + 3
                    && mouseY >= LIST_TOP - 2 && mouseY < listBottom();
            if (hovered) {
                hoveredIndex = i;
            }
            boolean equipped = row.perk.id.equals(equippedId);
            boolean locked = isLockedPerk(row.perk.id);
            row.hover = MWStyle.easeHover(row.hover, hovered, partialTick);
            long rowPop = popAt.getOrDefault(row.perk.id, 0L);
            // rows glide in one after another and fade (veil) with the screen; same clock on close
            float rowVis = MWStyle.easeOutCubic(net.minecraft.util.Mth.clamp(MWStyle.chromeOpen * 1.5f - i * 0.07f, 0f, 1f));
            graphics.pose().pushPose();
            graphics.pose().translate(-(1f - rowVis) * 12f, 0, 0);
            MWStyle.tile(graphics, row.x, row.y, row.w, row.h, row.hover, equipped && !locked);
            if (!row.perk.icon.isEmpty()) {
                if (locked) {
                    com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                    graphics.setColor(1f, 1f, 1f, 0.35f);
                }
                PerkIcons.draw(graphics, row.perk.icon, row.x + 5, row.y + 5, 18);
                graphics.setColor(1f, 1f, 1f, 1f);
            }
            MWStyle.scrollText(graphics, font, row.perk.displayName, row.x + 30, row.y + 10, row.w - (locked ? 74 : 48),
                    locked ? MWStyle.LOCKED : equipped ? MWStyle.accent() : MWStyle.TEXT, hovered || equipped);
            if (locked) {
                String tag = MWStyle.tr("screen.mwloadout.perk_locked");
                MWStyle.strF(graphics, font, tag, row.x + row.w - 8 - font.width(tag), row.y + 10, MWStyle.LOCKED, false);
            }
            if (equipped && !locked) {
                MWStyle.fillF(graphics, row.x + row.w - 12, row.y + row.h / 2 - 3, row.x + row.w - 6, row.y + row.h / 2 + 3,
                        MWStyle.accent());
            }
            // freshly picked: a soft accent fill sweeps across the row and fades (no bouncing)
            MWStyle.sweepFill(graphics, row.x, row.y, row.w, row.h, rowPop, 380, MWStyle.accent());
            if (rowVis < 0.99f) {
                if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, row.x, row.y, row.w, row.h, (1f - rowVis) * 0.92f);
        }
            }
            graphics.pose().popPose();
        }
        graphics.disableScissor();
        if (maxScroll > 0) { // thin scroll track on the list's right edge
            int trackH = listBottom() - LIST_TOP;
            int thumbH = Math.max(14, trackH * trackH / (trackH + maxScroll));
            int thumbY = LIST_TOP + Math.round((trackH - thumbH) * (scroll / maxScroll));
            MWStyle.fillF(graphics, 206, LIST_TOP, 208, listBottom(), MWStyle.PANEL_RAISED);
            MWStyle.fillF(graphics, 206, thumbY, 208, thumbY + thumbH, MWStyle.accent());
        }

        // 8 KILL tab: the caption lives in the right column, under the detail card, so it never sits on the perk list or the tabs
        if (isFullTab(activeColumn) && width - 216 - 12 > 90) {
            int capY = 226;
            for (net.minecraft.util.FormattedCharSequence line : font.split(Component.literal(MWStyle.tr("screen.mwloadout.spec_full_hint")), width - 216 - 12)) {
                graphics.drawString(font, line, 216, capY, MWStyle.fa(MWStyle.accent()), false);
                capY += 10;
            }
        }
        // Detail card, right side: hovered perk (or the equipped one when idle).
        long nowMs = System.currentTimeMillis();
        PerkDefinition hoveredPerk = hoveredIndex >= 0 ? rows.get(hoveredIndex).perk : null;
        if (hoveredPerk != null) {
            lastHoverPerk = hoveredPerk;
            lastHoverAt = nowMs;
        }
        PerkDefinition detail;
        if (hoveredPerk != null) {
            detail = hoveredPerk;
        } else if (isSpecTab(activeColumn)) {
            // Specialist stages: the card follows the cursor only - it holds for a moment (no flicker) and then goes, it does not linger on the pick
            detail = nowMs - lastHoverAt < 160 ? lastHoverPerk : null;
        } else {
            detail = LoadoutRegistry.get().perk(equippedId);
        }
        int cardX = 216;
        int cardWidth = width - cardX - 12;
        if (detail != null && cardWidth > 90) {
            MWStyle.panel(graphics, cardX, 96, cardWidth, 120);
            if (!detail.icon.isEmpty()) {
                PerkIcons.draw(graphics, detail.icon, cardX + 10, 106, 40);
            }
            MWStyle.strF(graphics, font, detail.displayName.toUpperCase(), cardX + 58, 108, MWStyle.TEXT, false);
            MWStyle.fillF(graphics, cardX + 58, 119, cardX + cardWidth - 10, 120, MWStyle.accent());
            int lineY = 126;
            for (net.minecraft.util.FormattedCharSequence line
                    : font.split(Component.literal(detail.description), cardWidth - 68)) {
                if (lineY > 204) {
                    break;
                }
                MWStyle.strF(graphics, font, line, cardX + 58, lineY, MWStyle.TEXT_DIM, false);
                lineY += 10;
            }
            if (MWStyle.chromeOpen < 0.99f) {
                if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, cardX, 96, cardWidth, 120, (1f - MWStyle.chromeOpen) * 0.92f);
        }
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        // Gentle, brief list fade on tab change (eased, low alpha) — not a harsh wipe.
        float flash = 1f - Math.min(1f, (System.currentTimeMillis() - listFlashStart) / 180f);
        if (flash > 0.01f) {
            float eased = flash * flash;
            MWStyle.fillF(graphics, 12, 96, 202, height - 34, MWStyle.withAlpha(0xFF0A0D12, eased * 0.4f));
        }
        transitionRender(graphics);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        if (button == 0) {
            layoutTabs();
            // Normal perk column tabs.
            for (int i = 0; i < columns.size(); i++) {
                int tx = perkTabX(i);
                if (mouseX >= tx && mouseX < tx + tabW && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H) {
                    selectColumn(i);
                    return true;
                }
            }
            // Specialist kill-tabs (only when the feature is on AND they are slid out far enough to be real).
            for (int i = 0; specialistFeatureOn() && specAnim > 0.6f && i <= SPEC_KILLS.length; i++) {
                int tx = specTabX(i, specTabProgress(i));
                if (mouseX >= tx && mouseX < tx + tabW && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H) {
                    if (specialistActive) {
                        selectColumn(columns.size() + i);
                    }
                    return true;
                }
            }
            // Activate-Specialist toggle.
            if (specialistFeatureOn() && mouseX >= toggleX && mouseX < toggleX + TOGGLE_W
                    && mouseY >= TAB_Y && mouseY < TAB_Y + TOGGLE_H) {
                specialistActive = !specialistActive;
                MWStyle.playClick();
                if (specialistActive) {
                    // When enabled, specialist slots must never be empty - auto-fill any blank with
                    // a random non-clashing perk from the pool. The player can still change them,
                    // exactly like main perks, but can't leave one blank.
                    autoFillSpecialist();
                } else if (isSpecTab(activeColumn)) {
                    activeColumn = 0; // jump back to a real perk column
                    listFlashStart = System.currentTimeMillis();
                }
                rebuildRows();
                return true;
            }
            // Row click: assign to the specialist stage or the normal perk slot.
            for (Row row : rows) {
                if (row.contains(mouseX, mouseY) && mouseY >= LIST_TOP - 2 && mouseY < listBottom()) {
                    if (isFullTab(activeColumn)) {
                        return true; // preview only
                    }
                    if (isLockedPerk(row.perk.id)) {
                        MWStyle.playTab(); // locked while Specialist is on
                        return true;
                    }
                    if (isSpecTab(activeColumn)) {
                        int me = specIndex(activeColumn);
                        // Specialist slots behave like perk slots: SWAP only, never unequip to blank.
                        specialistPerks.set(me, row.perk.id);
                        popAt.put(row.perk.id, System.currentTimeMillis());
                        MWStyle.playClick();
                    } else {
                        Rules.PerkSlot column = columns.get(activeColumn);
                        String equippedId = working.perks.getOrDefault(column.id, "");
                        if (!row.perk.id.equals(equippedId)) {
                            // Perk slots are never empty — you SWAP, you can't unequip to blank.
                            working.perks.put(column.id, row.perk.id);
                            popAt.put(row.perk.id, System.currentTimeMillis());
                            MWStyle.playClick();
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Fills any blank specialist slot with a random perk from the pool that isn't already equipped
     * as a main perk and isn't used in another specialist slot — so enabling Specialist never
     * leaves an empty slot. Existing picks are kept; only blanks are filled.
     */
    private void autoFillSpecialist() {
        Rules rules = LoadoutRegistry.get().rules();
        java.util.List<String> pool = new java.util.ArrayList<>(LoadoutRegistry.get().specialistPool());
        java.util.Collections.shuffle(pool);
        java.util.Set<String> equipped = new java.util.HashSet<>(working.perks.values());
        for (int slot = 0; slot < specialistPerks.size(); slot++) {
            String cur = specialistPerks.get(slot);
            if (cur != null && !cur.isEmpty()) {
                continue; // keep player's existing pick
            }
            for (String candidate : pool) {
                if (equipped.contains(candidate)) {
                    continue;
                }
                boolean usedElsewhere = false;
                for (int j = 0; j < specialistPerks.size(); j++) {
                    if (j != slot && candidate.equals(specialistPerks.get(j))) {
                        usedElsewhere = true;
                        break;
                    }
                }
                if (usedElsewhere) {
                    continue;
                }
                PerkDefinition perk = LoadoutRegistry.get().perk(candidate);
                if (perk != null && perk.enabled) {
                    specialistPerks.set(slot, candidate);
                    break;
                }
            }
        }
    }

    private void selectColumn(int col) {
        if (activeColumn != col) {
            activeColumn = col;
            MWStyle.playTab();
            listFlashStart = System.currentTimeMillis();
            rebuildRows();
        }
    }

    @Override
    public void onClose() {
        // Persist the specialist mode + picks (server re-validates against pool + equipped perks).
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String p : specialistPerks) {
            if (p != null && !p.isEmpty()) {
                clean.add(p);
            }
        }
        dev.l41nx.mwloadout.network.Net.CHANNEL.sendToServer(
                new dev.l41nx.mwloadout.network.Net.C2SSetSpecialist(specialistActive, clean));
        transitionTo(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void transitionTo(net.minecraft.client.gui.screens.Screen next) {
        if (closingAt <= 0) {
            pendingScreen = next;
            pendingClose = next == null;
            closingAt = System.currentTimeMillis();
            MWStyle.beginClose(next != null);
        }
    }

    private void transitionRender(GuiGraphics graphics) {
        float in = 1f - MWStyle.openProgress(openedAt);
        float out = MWStyle.closeProgress(closingAt);
        MWStyle.veil(graphics, 0, 0, width, height, Math.max(in * 0.9f, out));
        if (closingAt > 0 && System.currentTimeMillis() - closingAt >= MWStyle.closeMillis()) {
            closingAt = -1;
            if (pendingClose) {
                minecraft.setScreen(null);
            } else if (pendingScreen != null) {
                MWStyle.handoff();
                minecraft.setScreen(pendingScreen);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll > 0 && mouseX < 214 && mouseY >= LIST_TOP - 2 && mouseY < listBottom()) {
            scroll = net.minecraft.util.Mth.clamp(scroll - (float) delta * 28f, 0f, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static final class Row {
        final PerkDefinition perk;
        final int x, w, h, baseY;
        int y;
        float hover;

        Row(PerkDefinition perk, int x, int y, int w, int h) {
            this.perk = perk;
            this.x = x;
            this.baseY = y;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
