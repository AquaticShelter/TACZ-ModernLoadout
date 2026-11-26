package dev.l41nx.mwloadout.attribute;

import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.world.entity.LivingEntity;

/**
 * CLIENT: is the local player really drawing / reloading the item in hand right now? Only then do draw / reload animation clips play at
 * mw:draw_speed / mw:reload_speed. Other mods play "draw" clips of their own on top of a held gun (TaCZ Modern Tweaks' quick throw, stim,
 * armor plate overlays); those are timed by their own code and must keep their normal speed, or the clip ends early and freezes for a beat.
 */
public final class MWAnimWindow {
    /** end of the current LRTactical item draw on this client (ms), set by MixinMWLrDraw */
    private static volatile long lrDrawUntil;

    private MWAnimWindow() {
    }

    /** MixinMWLrDraw (client side only): the held LRT item's draw lasts this many ticks from now */
    public static void lrDraw(LivingEntity entity, int ticks) {
        try {
            if (entity == net.minecraft.client.Minecraft.getInstance().player) {
                lrDrawUntil = System.currentTimeMillis() + ticks * 50L + 100L;
            }
        } catch (Throwable ignored) {
            // never break the draw
        }
    }

    public static boolean drawing(LivingEntity player) {
        if (player == null) {
            return false;
        }
        if (System.currentTimeMillis() < lrDrawUntil) {
            return true;
        }
        try {
            return IGunOperator.fromLivingEntity(player).getSynDrawCoolDown() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean reloading(LivingEntity player) {
        if (player == null) {
            return false;
        }
        try {
            return IGunOperator.fromLivingEntity(player).getSynReloadState().getStateType().isReloading();
        } catch (Throwable t) {
            return false;
        }
    }
}
