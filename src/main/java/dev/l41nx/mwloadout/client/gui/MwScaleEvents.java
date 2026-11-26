package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.MWLoadoutMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps the loadout UI scale to the loadout screens only (see {@link MwScreen}). The HUD is drawn between render tick start and the screen,
 * so it always sees the game's own GUI scale; the screen itself is drawn at the loadout scale.
 */
@Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID, value = Dist.CLIENT)
public final class MwScaleEvents {
    private MwScaleEvents() {
    }

    private static boolean drawing;

    private static boolean ours(Screen screen) {
        return screen instanceof MwScreen && screen == Minecraft.getInstance().screen;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Pre event) {
        if (event.getScreen() instanceof MwScreen) {
            MwScreen.applyTo(Minecraft.getInstance(), event.getScreen());
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof MwScreen)) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            MwScreen.useNaturalScale(mc); // GameRenderer reads the scale for the HUD projection and mouse right after this
        } else {
            MwScreen.useLoadoutScale(mc); // input between frames maps onto the loadout layout
        }
    }

    /** Draws the screen itself at the loadout scale. GameRenderer computed the mouse position and projection at the game's scale for the
     *  HUD, so both are redone here; the original draw call is cancelled (Render.Post still fires for other mods). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        if (!ours(screen) || drawing) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MwScreen.dropTooltip();
        MwScreen.useLoadoutScale(mc);
        if (screen.width != mc.getWindow().getGuiScaledWidth() || screen.height != mc.getWindow().getGuiScaledHeight()) {
            screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()); // config changed while open
        }
        MwScreen.applyProjection(mc);
        var window = mc.getWindow();
        int mx = (int) (mc.mouseHandler.xpos() * window.getGuiScaledWidth() / Math.max(1, window.getScreenWidth()));
        int my = (int) (mc.mouseHandler.ypos() * window.getGuiScaledHeight() / Math.max(1, window.getScreenHeight()));
        drawing = true;
        try {
            screen.renderWithTooltip(event.getGuiGraphics(), mx, my, event.getPartialTick());
        } finally {
            drawing = false;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof MwScreen)) {
            return;
        }
        MwScreen.flushTooltip(event.getGuiGraphics());
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MwScreen) {
            // toasts and the rest of the frame go back to the game's scale; render tick end switches back for input
            event.getGuiGraphics().flush();
            MwScreen.useNaturalScale(mc);
            MwScreen.applyProjection(mc);
        }
    }
}
