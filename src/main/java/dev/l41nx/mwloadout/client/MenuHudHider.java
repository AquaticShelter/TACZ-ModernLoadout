package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.MWLoadoutMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * While one of the loadout menus is open the in-game HUD is not drawn. The menus blur the framebuffer, which already contains the HUD; a mod that
 * draws its HUD later (or through its own pipeline) then showed it a second time, sharp, on top of the blurred copy - the "doubled layers"
 * seen when the loadout opens. Off with hideHudBehindMenus.
 */
@Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID, value = Dist.CLIENT)
public final class MenuHudHider {
    private MenuHudHider() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlay(RenderGuiOverlayEvent.Pre event) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null && ClientCfg.hideHudBehindMenus()
                && screen.getClass().getName().startsWith("dev.l41nx.mwloadout.client.gui")) {
            event.setCanceled(true);
        }
    }
}
