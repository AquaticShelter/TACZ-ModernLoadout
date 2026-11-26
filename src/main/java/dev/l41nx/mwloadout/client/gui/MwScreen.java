package dev.l41nx.mwloadout.client.gui;

import com.mojang.blaze3d.platform.Window;
import dev.l41nx.mwloadout.client.ClientCfg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base of every loadout screen.
 *
 * <p>The loadout screens have their own UI scale ({@code uiScale}, percent, 0 = auto from the window height), separate from the game's GUI
 * scale. The window has a single GUI scale, so it is switched per phase of the frame (see {@link MwScaleEvents}):
 * <ul>
 *   <li>render tick start: back to the game's own scale, so the HUD (vanilla, ModernHUD, any overlay) is drawn exactly as without us;</li>
 *   <li>around this screen's render: the loadout scale, with a matching projection and mouse position;</li>
 *   <li>render tick end: the loadout scale again, so mouse input between frames maps onto the screen's layout.</li>
 * </ul>
 * The game's scale is put back for good in {@link #removed()}.
 */
public abstract class MwScreen extends Screen {

    protected MwScreen(Component title) {
        super(title);
    }

    /** the game's own GUI scale for the current window */
    static double naturalScale(Minecraft mc) {
        return mc.getWindow().calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
    }

    /** the loadout screens' scale for the current window: the config value, or picked from the window height; never so big that
     *  the layout gets less than 640 x 360 units */
    static double loadoutScale(Minecraft mc) {
        Window window = mc.getWindow();
        int fbW = Math.max(1, window.getWidth()), fbH = Math.max(1, window.getHeight());
        int pct = ClientCfg.uiScale();
        double s;
        if (pct <= 0) {
            s = Math.round(fbH / 540.0 * 2.0) / 2.0; // 1080p -> 2, 1440p -> 2.5, 2160p -> 4
        } else {
            s = Math.max(50, pct) / 100.0;
        }
        double room = Math.min(fbW / 640.0, fbH / 360.0);
        s = Math.min(s, Math.max(1.0, Math.floor(room * 4.0) / 4.0));
        return Math.max(0.5, s);
    }

    /** Switches the window to the loadout scale and re-reads the size into the screen. Called from {@link MwScaleEvents} (Screen.init(mc,w,h) is final). */
    static void applyTo(Minecraft mc, Screen screen) {
        useLoadoutScale(mc);
        Window window = mc.getWindow();
        screen.width = window.getGuiScaledWidth();
        screen.height = window.getGuiScaledHeight();
    }

    static void useLoadoutScale(Minecraft mc) {
        setScale(mc.getWindow(), loadoutScale(mc));
    }

    static void useNaturalScale(Minecraft mc) {
        setScale(mc.getWindow(), naturalScale(mc));
    }

    private static void setScale(Window window, double scale) {
        if (window.getGuiScale() != scale) {
            window.setGuiScale(scale);
        }
    }

    /** the GUI projection for whatever scale the window has right now (same matrix GameRenderer builds before the HUD) */
    static void applyProjection(Minecraft mc) {
        Window window = mc.getWindow();
        org.joml.Matrix4f ortho = new org.joml.Matrix4f().setOrtho(0f, (float) (window.getWidth() / window.getGuiScale()),
                (float) (window.getHeight() / window.getGuiScale()), 0f, 1000f, net.minecraftforge.client.ForgeHooksClient.getGuiFarPlane());
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(ortho, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
    }

    private static void restore(Minecraft mc) {
        useNaturalScale(mc);
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        applyTo(mc, this); // a window resize resets the scale to the game's own value first
        super.resize(mc, this.width, this.height);
    }

    @Override
    public void removed() {
        super.removed();
        restore(Minecraft.getInstance());
    }

    // ------------------------------------------------------------------ deferred tooltips
    // Tooltips used to be drawn in the middle of a screen's render pass. Anything drawn after them (the gun preview wipes the depth buffer,
    // panels, pop-up menus, the open / close veil) could land on top, which looked like an invisible box eating the tooltip. Screens now
    // queue the tooltip here and MwScaleEvents draws it once the whole screen is done, on a fresh depth buffer.

    private static java.util.function.Consumer<GuiGraphics> pendingTooltip;

    public static void deferTooltip(net.minecraft.client.gui.Font font, java.util.List<Component> lines, int x, int y) {
        if (lines != null && !lines.isEmpty()) {
            pendingTooltip = g -> g.renderComponentTooltip(font, lines, x, y);
        }
    }

    public static void deferTooltip(net.minecraft.client.gui.Font font, Component line, int x, int y) {
        if (line != null) {
            pendingTooltip = g -> g.renderTooltip(font, line, x, y);
        }
    }

    public static void deferTooltip(net.minecraft.client.gui.Font font, net.minecraft.world.item.ItemStack stack, int x, int y) {
        if (stack != null && !stack.isEmpty()) {
            pendingTooltip = g -> g.renderTooltip(font, stack, x, y);
        }
    }

    static void dropTooltip() {
        pendingTooltip = null;
    }

    static void flushTooltip(GuiGraphics g) {
        java.util.function.Consumer<GuiGraphics> tip = pendingTooltip;
        pendingTooltip = null;
        if (tip == null) {
            return;
        }
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        g.pose().pushPose();
        try {
            tip.accept(g);
        } finally {
            g.pose().popPose();
        }
    }

    // ------------------------------------------------------------------ shared open / close transition
    // One copy of the fade-in / fade-out hand-off every loadout screen used to carry itself (UI rework prep: new screens
    // call these; older screens move over one by one - see docs/UI_MW4_PREP.md).

    private long mwOpenedAt;
    private long mwClosingAt;
    private Screen mwNext;
    private boolean mwCloseAll;

    /** call at the top of init(): starts the open fade */
    protected final void mwOpen() {
        MWStyle.screenOpened();
        mwOpenedAt = System.currentTimeMillis();
        mwClosingAt = 0;
        mwNext = null;
        mwCloseAll = false;
    }

    protected final long mwOpenedAt() {
        return mwOpenedAt;
    }

    /** true while the close fade runs (ignore input then) */
    protected final boolean mwClosing() {
        return mwClosingAt != 0;
    }

    /** fades out, then opens {@code next} (null = back to the game) */
    protected final void mwLeave(Screen next) {
        if (mwClosingAt != 0) {
            return;
        }
        mwNext = next;
        mwCloseAll = next == null;
        mwClosingAt = System.currentTimeMillis();
        MWStyle.beginClose(next != null);
    }

    /** call last in render(): draws the open / close veil and performs the hand-off when the close fade is done */
    protected final void mwFade(GuiGraphics g) {
        float opening = 1f - MWStyle.openProgress(mwOpenedAt);
        float closing = MWStyle.closeProgress(mwClosingAt);
        MWStyle.veil(g, 0, 0, width, height, Math.max(opening * 0.9f, closing));
        if (mwClosingAt > 0 && System.currentTimeMillis() - mwClosingAt >= MWStyle.closeMillis()) {
            mwClosingAt = -1;
            if (mwCloseAll) {
                minecraft.setScreen(null);
            } else if (mwNext != null) {
                MWStyle.handoff();
                minecraft.setScreen(mwNext);
            }
        }
    }
}
