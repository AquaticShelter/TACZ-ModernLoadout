package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.compat.KillFeedCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * KillFeed settings inside the loadout UI: each KillFeed switch the installed version has, as an MW switch row.
 * A click flips the real killfeed-client.toml value (applies live). Opens / closes with the house fade.
 */
public final class KillFeedSettingsScreen extends MwScreen {
    private static final int ROW_H = 22, ROW_GAP = 4, TOP = 46;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    public KillFeedSettingsScreen(Screen parent) {
        super(Component.translatable("screen.mwloadout.killfeed_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        mwOpen();
        rows.clear();
        int w = Math.min(240, width - 40);
        int x = (width - w) / 2;
        int y = TOP;
        for (KillFeedCompat.Toggle t : KillFeedCompat.toggles()) {
            if (!KillFeedCompat.has(t.field())) {
                continue;
            }
            rows.add(new Row(t, x, y, w));
            y += ROW_H + ROW_GAP;
        }
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width / 2 - 37, y + 8, 74, 20,
                Component.translatable("screen.mwloadout.back"), b -> onClose())).accent());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(g, width, MWStyle.tr("screen.mwloadout.killfeed_title"), mwOpenedAt());
        for (Row r : rows) {
            r.draw(g, mouseX, mouseY, partialTick);
        }
        super.render(g, mouseX, mouseY, partialTick);
        mwFade(g);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mwClosing()) {
            return true;
        }
        if (button == 0) {
            for (Row r : rows) {
                if (r.hit(mouseX, mouseY)) {
                    KillFeedCompat.flip(r.toggle.field());
                    MWStyle.playClick();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        mwLeave(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class Row {
        final KillFeedCompat.Toggle toggle;
        final int x, y, w;
        float hover;
        boolean hovered;

        Row(KillFeedCompat.Toggle toggle, int x, int y, int w) {
            this.toggle = toggle;
            this.x = x;
            this.y = y;
            this.w = w;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + ROW_H;
        }

        void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean over = hit(mouseX, mouseY);
            if (over && !hovered) {
                MWStyle.playHover();
            }
            hovered = over;
            hover = MWStyle.easeHover(hover, over, partialTick);
            boolean on = KillFeedCompat.isOn(toggle.field());
            MWStyle.tile(g, x, y, w, ROW_H, hover, false);
            MWStyle.strF(g, font, toggle.label(), x + 8, y + 7, MWStyle.TEXT, false);
            // switch: track + knob, green when on
            int tw = 22, tx = x + w - tw - 8, ty = y + 6;
            MWStyle.fillF(g, tx, ty, tx + tw, ty + 10, on ? MWStyle.withAlpha(MWStyle.POSITIVE, 0.35f) : MWStyle.PANEL_RAISED);
            MWStyle.hairline(g, tx, ty, tw, 10, on ? MWStyle.POSITIVE : MWStyle.border());
            int kx = on ? tx + tw - 9 : tx + 1;
            MWStyle.fillF(g, kx, ty + 1, kx + 8, ty + 9, on ? MWStyle.POSITIVE : MWStyle.TEXT_DIM);
        }
    }
}
