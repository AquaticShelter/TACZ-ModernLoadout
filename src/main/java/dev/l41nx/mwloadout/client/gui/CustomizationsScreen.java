package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.compat.KillFeedCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import dev.l41nx.mwloadout.network.Net;

import java.util.List;

/**
 * CUSTOMIZATIONS, MW-styled: calling cards and emblems in one themed screen — grid tiles with the
 * house hover treatment plus a selection pop animation, right-click to star favorites (gold
 * corner) or randomize. Every read/write goes through KillFeedCompat reflection into the killfeed
 * mod, which still owns the GIF pipeline and the tile data itself — but the actual card/emblem
 * CHOICE, and now favorites too (v5.0.0 — the real Killfeed mod never had a favorites API to
 * begin with, KillFeed never had such an API), are owned entirely by MWLoadout's own profile
 * (KillFeedCompat.choose saves it server-side, restoreOnLogin pushes it back into Killfeed on
 * join), since Killfeed's own client-side storage doesn't survive a rejoin reliably. This screen
 * is pure chrome either way, present only when BOTH mods are installed (the "only when both mods
 * are installed" integration): killfeed alone keeps its native picker, mwloadout alone shows no button.
 */
public final class CustomizationsScreen extends MwScreen {

    private static final int TILE_GAP = 6;
    private final Screen parent;
    private boolean showingEmblems;
    private double scroll;
    private String selectedCard;
    private String selectedEmblem;
    private long openedAt = System.currentTimeMillis();
    private net.minecraft.client.gui.screens.Screen pendingScreen;
    private boolean pendingClose;
    private long closingAt;
    private long listFlashStart;
    private long savedFlashUntil;
    private long selectionPopAt; // timestamp of the last selection change, drives the tile pop-in below
    // Right-click context menu on a tile: equip / unequip / favorite.
    private int menuX = -1, menuY, menuItem = -1;

    public CustomizationsScreen(Screen parent) {
        super(Component.translatable("screen.mwloadout.customize_title"));
        this.parent = parent;
        this.selectedCard = KillFeedCompat.myCard();
        this.selectedEmblem = KillFeedCompat.myEmblem();
    }

    @Override
    protected void init() {
        closingAt = 0;
        MWStyle.screenOpened();
        pendingScreen = null;
        pendingClose = false;
        openedAt = System.currentTimeMillis();
        addRenderableWidget(MWStyle.flatButton(10, 32, 78, 16,
                Component.translatable("screen.mwloadout.cards_tab"), button -> switchTab(false)));
        addRenderableWidget(MWStyle.flatButton(92, 32, 78, 16,
                Component.translatable("screen.mwloadout.emblems_tab"), button -> switchTab(true)));
        // SAVE applies WITHOUT leaving — pick a card, stay right here for the emblem.
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width - 84 - 80, height - 28, 74, 20,
                Component.translatable("screen.mwloadout.save"), button -> {
                    KillFeedCompat.choose(selectedCard, selectedEmblem);
                    savedFlashUntil = System.currentTimeMillis() + 1400;
                    MWStyle.playClick();
                })).accent());
        addRenderableWidget(MWStyle.flatButton(width - 84, height - 28, 74, 20,
                Component.translatable("screen.mwloadout.back"), button -> onClose()));
    }

    private void switchTab(boolean emblems) {
        if (showingEmblems != emblems) {
            showingEmblems = emblems;
            scroll = 0;
            listFlashStart = System.currentTimeMillis();
            MWStyle.playTab();
        }
    }

    /** single choke point for changing the active card/emblem so the tile pop-in
     *  animation (see render()) fires consistently no matter which of the several places
     *  (click, equip, unequip, random) triggered the change. */
    private void applySelection(String value) {
        if (showingEmblems) {
            selectedEmblem = value;
        } else {
            selectedCard = value;
        }
        selectionPopAt = System.currentTimeMillis();
    }

    private List<String> items() {
        return showingEmblems ? KillFeedCompat.emblems() : KillFeedCompat.cards();
    }

    /** MWLoadout's own favorites (see PlayerProfile.favoriteCards) — replaces a dead
     *  KillFeed isFavorite reflection call that never matched any real method on the
     *  actual KillFeed mod (KillFeed never had such an API). Also just plain cheaper: a
     *  Set.contains() instead of a reflective method call on every visible tile, every frame. */
    private boolean isFavorite(String item) {
        dev.l41nx.mwloadout.data.PlayerProfile profile = dev.l41nx.mwloadout.client.ClientCache.profile();
        return profile != null && profile.favoriteCards.contains(item);
    }

    private int columns() {
        return Math.max(1, (width - 20) / (tileWidth() + TILE_GAP));
    }

    private int tileWidth() {
        return showingEmblems ? 52 : 132;
    }

    private int tileHeight() {
        return showingEmblems ? 52 : 33; // cards are 4:1, emblems square
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(graphics, width,
                MWStyle.tr("screen.mwloadout.customize_title"), openedAt);
        long now = System.currentTimeMillis();
        List<String> items = items();
        int listTop = 56;
        int listBottom = height - 36;
        int columns = columns();
        String selected = showingEmblems ? selectedEmblem : selectedCard;
        for (int i = 0; i < items.size(); i++) {
            int x = 10 + (i % columns) * (tileWidth() + TILE_GAP);
            int y = listTop + (i / columns) * (tileHeight() + TILE_GAP) - (int) scroll;
            if (y + tileHeight() < listTop || y > listBottom) {
                continue;
            }
            boolean hovered = mouseX >= x && mouseX < x + tileWidth() && mouseY >= y && mouseY < y + tileHeight();
            boolean isSelected = items.get(i).equals(selected);
            // Just-selected tile pops (scale overshoot) instead of the border silently swapping.
            float pop = 0f;
            if (isSelected) {
                long age = now - selectionPopAt;
                if (age >= 0 && age < MWStyle.ANIM_NORMAL) {
                    float p = age / (float) MWStyle.ANIM_NORMAL;
                    pop = (1f - MWStyle.easeOutBack(p)) * -0.12f; // overshoots past 1.0 then settles
                }
            }
            if (pop != 0f) {
                float cx = x + tileWidth() / 2f;
                float cy = y + tileHeight() / 2f;
                graphics.pose().pushPose();
                graphics.pose().translate(cx, cy, 0);
                graphics.pose().scale(1f - pop, 1f - pop, 1f);
                graphics.pose().translate(-cx, -cy, 0);
            }
            ResourceLocation texture = KillFeedCompat.texture(items.get(i), showingEmblems, now);
            if (texture != null) {
                MWStyle.blitF(graphics, texture, x, y, 0f, 0f, tileWidth(), tileHeight(), tileWidth(), tileHeight());
            }
            MWStyle.hairline(graphics, x - 1, y - 1, tileWidth() + 2, tileHeight() + 2,
                    isSelected ? MWStyle.accent() : hovered ? MWStyle.borderActive() : MWStyle.border());
            if (!showingEmblems && isFavorite(items.get(i))) {
                MWStyle.strF(graphics, font, "\u2605", x + tileWidth() - 10, y + 2, 0xFFE8C24A, true);
            }
            if (pop != 0f) {
                graphics.pose().popPose();
            }
        }
        // Scroll track
        int contentHeight = ((items.size() + columns - 1) / columns) * (tileHeight() + TILE_GAP);
        int overflow = Math.max(0, contentHeight - (listBottom - listTop));
        if (overflow > 0) {
            int trackHeight = listBottom - listTop;
            int barHeight = Math.max(14, trackHeight * trackHeight / Math.max(1, contentHeight));
            int barY = listTop + (int) (scroll / overflow * (trackHeight - barHeight));
            MWStyle.fillF(graphics, width - 6, listTop, width - 4, listBottom, MWStyle.PANEL_RAISED);
            MWStyle.fillF(graphics, width - 6, barY, width - 4, barY + barHeight, MWStyle.border());
        }
        MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.star_hint"),
                10, height - 24, MWStyle.TEXT_DIM, false);
        if (now < savedFlashUntil) {
            String saved = MWStyle.tr("screen.mwloadout.saved");
            MWStyle.strF(graphics, font, saved, width - 84 - 80 + 12, height - 40, MWStyle.POSITIVE, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (menuX >= 0) {
            String[] ops = menuOps();
            int itemHeight = 14;
            MWStyle.fillF(graphics, menuX, menuY, menuX + 96, menuY + ops.length * itemHeight, 0xF5101216);
            MWStyle.hairline(graphics, menuX, menuY, 96, ops.length * itemHeight, MWStyle.borderActive());
            for (int i = 0; i < ops.length; i++) {
                int itemY = menuY + i * itemHeight;
                boolean hovered = mouseX >= menuX && mouseX < menuX + 96 && mouseY >= itemY && mouseY < itemY + itemHeight;
                if (hovered) {
                    MWStyle.fillF(graphics, menuX + 1, itemY, menuX + 95, itemY + itemHeight, MWStyle.PANEL_HOVER);
                }
                MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.cop_" + ops[i]).getString(),
                        menuX + 7, itemY + 3, hovered ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
            }
        }
        float flash = 1f - Math.min(1f, (now - listFlashStart) / 110f);
        if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, 10, listTop, width - 20, listBottom - listTop, flash * 0.85f);
        }
        transitionRender(graphics);
    }

    private int hitTest(double mouseX, double mouseY) {
        List<String> items = items();
        int columns = columns();
        for (int i = 0; i < items.size(); i++) {
            int x = 10 + (i % columns) * (tileWidth() + TILE_GAP);
            int y = 56 + (i / columns) * (tileHeight() + TILE_GAP) - (int) scroll;
            if (mouseX >= x && mouseX < x + tileWidth() && mouseY >= y && mouseY < y + tileHeight()
                    && mouseY >= 56 && mouseY <= height - 36) {
                return i;
            }
        }
        return -1;
    }

    private String[] menuOps() {
        // "random" was missing entirely from this menu — the CARDS/EMBLEMS
        // customize screen never had a working Randomize op, unlike the class-preset context menu
        // (LoadoutScreen.MENU_OPS) which already does the same pattern for classes.
        return showingEmblems ? new String[]{"equip", "unequip", "random"} : new String[]{"equip", "unequip", "favorite", "random"};
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        if (menuX >= 0) {
            String[] ops = menuOps();
            if (mouseX >= menuX && mouseX < menuX + 96 && mouseY >= menuY && mouseY < menuY + ops.length * 14) {
                String item = items().get(menuItem);
                switch (ops[(int) ((mouseY - menuY) / 14)]) {
                    case "equip" -> {
                        applySelection(item);
                        KillFeedCompat.choose(selectedCard, selectedEmblem);
                        savedFlashUntil = System.currentTimeMillis() + 1400;
                    }
                    case "unequip" -> {
                        String fallback = showingEmblems
                                ? (KillFeedCompat.emblems().isEmpty() ? selectedEmblem : KillFeedCompat.emblems().get(0))
                                : (KillFeedCompat.cards().isEmpty() ? selectedCard : KillFeedCompat.cards().get(0));
                        applySelection(fallback);
                        KillFeedCompat.choose(selectedCard, selectedEmblem);
                        savedFlashUntil = System.currentTimeMillis() + 1400;
                    }
                    case "favorite" -> Net.CHANNEL.sendToServer(new Net.C2SToggleFavoriteCard(item));
                    case "random" -> {
                        // prefer favorites when the player has any set (in the CURRENT
                        // tab) — a real "random from favorites" now that favorites are MWLoadout's
                        // own feature, instead of the old dead Mode pill that could never do this
                        // against the real Killfeed API. Falls back to the full pool otherwise.
                        java.util.List<String> pool = items();
                        java.util.List<String> favoritesOnly = pool.stream().filter(this::isFavorite).toList();
                        java.util.List<String> source = favoritesOnly.isEmpty() ? pool : favoritesOnly;
                        if (!source.isEmpty()) {
                            String pick = source.get(new java.util.Random().nextInt(source.size()));
                            applySelection(pick);
                            KillFeedCompat.choose(selectedCard, selectedEmblem);
                            savedFlashUntil = System.currentTimeMillis() + 1400;
                        }
                    }
                }
                MWStyle.playClick();
            }
            menuX = -1;
            return true;
        }
        int hit = hitTest(mouseX, mouseY);
        if (hit >= 0) {
            String item = items().get(hit);
            if (button == 1) {
                menuX = (int) Math.min(mouseX, width - 100);
                menuY = (int) Math.min(mouseY, height - menuOps().length * 14 - 4);
                menuItem = hit;
                MWStyle.playTab();
            } else if (button == 0) {
                applySelection(item);
                // apply on click instead of requiring a separate SAVE press —
                // SAVE now just re-confirms the current pick, same as onClose() already did.
                KillFeedCompat.choose(selectedCard, selectedEmblem);
                savedFlashUntil = System.currentTimeMillis() + 1400;
            }
            MWStyle.playClick();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<String> items = items();
        int columns = columns();
        int contentHeight = ((items.size() + columns - 1) / columns) * (tileHeight() + TILE_GAP);
        int overflow = Math.max(0, contentHeight - (height - 36 - 56));
        scroll = Mth.clamp(scroll - delta * 24, 0, overflow);
        return true;
    }

    @Override
    public void onClose() {
        KillFeedCompat.choose(selectedCard, selectedEmblem); // ESC saves too — house rule
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
}
