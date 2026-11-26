package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientCfg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Three-page first-run walkthrough (skippable, never shown twice): the loadout basics, the
 * gunsmith, and perks/progression. The seen-flag persists in the client config, so it survives
 * restarts and can be reset by hand (tutorialSeen=false) for a re-tour.
 */
public final class TutorialScreen extends MwScreen {

    private static final int PAGES = 3;
    private final Screen parent;
    private int page;
    private long openedAt = System.currentTimeMillis();

    public TutorialScreen(Screen parent) {
        super(Component.translatable("screen.mwloadout.tutorial_title"));
        this.parent = parent;
    }

    public static boolean shouldShow() {
        try {
            return !ClientCfg.TUTORIAL_SEEN.get();
        } catch (Exception e) {
            return false; // config not ready: never block the actual GUI over a tour
        }
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        clearWidgets();
        addRenderableWidget(MWStyle.flatButton(width / 2 - 110, height - 40, 70, 20,
                Component.translatable("screen.mwloadout.tutorial_skip"), button -> finish()));
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width / 2 + 40, height - 40, 70, 20,
                page < PAGES - 1 ? Component.translatable("screen.mwloadout.tutorial_next")
                        : Component.translatable("screen.mwloadout.tutorial_done"),
                button -> {
                    if (page < PAGES - 1) {
                        page++;
                        MWStyle.playTab();
                        init(); // relabel NEXT -> DONE on the last page
                    } else {
                        finish();
                    }
                })).accent());
    }

    private void finish() {
        try {
            ClientCfg.TUTORIAL_SEEN.set(true);
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/TutorialScreen:56", ignored);
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(graphics, width,
                MWStyle.tr("screen.mwloadout.tutorial_title"), openedAt);
        int panelWidth = Math.min(300, width - 40);
        int x = (width - panelWidth) / 2;
        MWStyle.panel(graphics, x, 44, panelWidth, height - 96);
        String heading = Component.translatable("screen.mwloadout.tutorial_h" + (page + 1)).getString();
        MWStyle.strF(graphics, font, heading.toUpperCase(), x + 12, 54, MWStyle.accent(), false);
        MWStyle.fillF(graphics, x + 12, 65, x + panelWidth - 12, 66, MWStyle.border());
        int lineY = 72;
        String body = Component.translatable("screen.mwloadout.tutorial_p" + (page + 1)).getString();
        for (net.minecraft.util.FormattedCharSequence line
                : font.split(Component.literal(body), panelWidth - 24)) {
            if (lineY > height - 60) {
                break;
            }
            MWStyle.strF(graphics, font, line, x + 12, lineY, MWStyle.TEXT, false);
            lineY += 11;
        }
        // Page dots
        for (int i = 0; i < PAGES; i++) {
            int dotX = width / 2 - PAGES * 6 + i * 12;
            MWStyle.fillF(graphics, dotX, height - 52, dotX + 6, height - 48,
                    i == page ? MWStyle.accent() : MWStyle.PANEL_RAISED);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        MWStyle.veil(graphics, 0, 0, width, height, (1f - MWStyle.openProgress(openedAt)) * 0.9f);
    }

    @Override
    public void onClose() {
        finish();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
