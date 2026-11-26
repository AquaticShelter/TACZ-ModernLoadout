package dev.l41nx.mwloadout.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

/**
 * Real gaussian background blur for the loadout menus, done the Blur-mod way: a shipped two-pass
 * post-effect ({@code shaders/post/mw_blur.json}) is run over the main framebuffer just before the
 * menu chrome draws, so the world behind the panels goes soft instead of merely dim. This is the
 * same {@link PostChain} pipeline vanilla uses for entity/spider shaders.
 *
 * <p>Everything is defensive: the chain is loaded lazily, re-initialised on resize, and every GL
 * call is wrapped so a driver/shader problem can never crash or freeze the game — it just disables
 * itself for the session and the frosted dim in {@link dev.l41nx.mwloadout.client.gui.MWStyle}
 * remains as the fallback. A config toggle lets players turn it off outright.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = dev.l41nx.mwloadout.MWLoadoutMod.MOD_ID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class BlurHandler {

    private static final ResourceLocation EFFECT = new ResourceLocation("mwloadout", "shaders/post/mw_blur.json");

    private static PostChain chain;
    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static boolean failed;
    private static long frame;        // render-frame counter (bumped at the start of every frame)
    private static long lastApplied = -1;

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRenderTick(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.START) {
            frame++;
        }
    }

    private BlurHandler() {
    }

    /** Blur the current framebuffer in place. Call once per menu frame before drawing panels. */
    public static void apply(float partialTick) {
        if (failed || !ClientCfg.blurEnabled()) {
            return;
        }
        if (lastApplied == frame) {
            return; // the framebuffer was already blurred this frame: a second pass would soften it twice and smear the HUD behind
        }
        lastApplied = frame;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) {
            return;
        }
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, EFFECT);
                chain.resize(main.width, main.height);
                lastWidth = main.width;
                lastHeight = main.height;
            } else if (main.width != lastWidth || main.height != lastHeight) {
                chain.resize(main.width, main.height);
                lastWidth = main.width;
                lastHeight = main.height;
            }
            chain.process(partialTick);
            // Rebind the main target for the GUI drawing that follows.
            main.bindWrite(false);
        } catch (Throwable t) {
            // Any failure: disable for the session and fall back to the frosted dim.
            failed = true;
            dispose();
        }
    }

    public static void dispose() {
        if (chain != null) {
            try {
                chain.close();
            } catch (Throwable ignored) {
                dev.l41nx.mwloadout.MWLog.quiet("client/BlurHandler:66", ignored);
            }
            chain = null;
        }
        lastWidth = lastHeight = -1;
    }
}
