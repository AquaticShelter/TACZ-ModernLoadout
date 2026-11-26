package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.client.gui.PerkIcons;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hitmarker cross-compat. When YOUR TACZ bullet hits another player, the perks that player carries show as small icons under the hitmarker,
 * timed to the hit. Independent of TACZ Hitmarker (it works with the default marker too) but written to sit with it: same trigger as that mod
 * (TACZ's client-side EntityHurtByGunEvent.Post, found by name so no hard TACZ API dependency), drawn at the crosshair after the crosshair layer.
 * The server tells every client which of the eight tracked perks a nearby player has (Net.S2CPerkMarks).
 */
public final class HitPerkOverlay {
    private static final String HURT_EVENT = "com.tacz.guns.api.event.common.EntityHurtByGunEvent$Post";

    private static final Map<UUID, Integer> MARKS = new ConcurrentHashMap<>();
    private static Method getAttacker;
    private static Method getHurtEntity;
    private static Method getLogicalSide;

    private static volatile int shownMask;
    private static volatile long shownAt;

    private HitPerkOverlay() {
    }

    /** Called from the client-setup event (MWLoadoutMod). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new HitPerkOverlay.Draw());
        try {
            Class<?> hurt = Class.forName(HURT_EVENT, false, HitPerkOverlay.class.getClassLoader());
            getAttacker = hurt.getMethod("getAttacker");
            getHurtEntity = hurt.getMethod("getHurtEntity");
            try {
                getLogicalSide = hurt.getMethod("getLogicalSide");
            } catch (NoSuchMethodException ignored) {
                getLogicalSide = null; // older TACZ: the client-only check below still applies
            }
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) hurt, (java.util.function.Consumer<Event>) HitPerkOverlay::onHurt);
            MWLoadoutMod.LOGGER.info("[MWLoadout] Hitmarker perk marks listening to {}", HURT_EVENT);
        } catch (ReflectiveOperationException | LinkageError e) {
            MWLoadoutMod.LOGGER.info("[MWLoadout] TACZ hit event not found - perk marks under the hitmarker are off ({})", e.toString());
        }
    }

    public static void setMarks(UUID player, int mask) {
        if (mask == 0) {
            MARKS.remove(player);
        } else {
            MARKS.put(player, mask);
        }
    }

    /** Does this player (the local one included - the server sends a player their own marks too) carry the perk? Only the eight PERK_MARKS. */
    public static boolean hasMark(UUID player, String perkId) {
        if (player == null) {
            return false;
        }
        Integer mask = MARKS.get(player);
        if (mask == null) {
            return false;
        }
        for (int i = 0; i < Net.PERK_MARKS.length; i++) {
            if (Net.PERK_MARKS[i].equals(perkId)) {
                return (mask & (1 << i)) != 0;
            }
        }
        return false;
    }

    public static void clear() {
        MARKS.clear();
        shownMask = 0;
    }

    private static void onHurt(Event event) {
        try {
            if (!ClientCfg.HIT_PERK_ENABLED.get()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || getAttacker == null) {
                return;
            }
            if (getLogicalSide != null && !"CLIENT".equals(String.valueOf(getLogicalSide.invoke(event)))) {
                return; // singleplayer posts the server copy too: only the client copy draws
            }
            Object attacker = getAttacker.invoke(event);
            Object hurt = getHurtEntity.invoke(event);
            if (attacker != mc.player || !(hurt instanceof Entity target) || target == mc.player) {
                return;
            }
            Integer mask = MARKS.get(target.getUUID());
            if (mask == null || mask == 0) {
                return;
            }
            shownMask = mask;
            shownAt = System.currentTimeMillis(); // a new hit restarts the strip: it follows the hitmarker pulse
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // a TACZ event shaped differently must never break the game
        }
    }

    /** Draws after the crosshair layer (the hitmarker draws on the crosshair too). */
    public static final class Draw {
        @SubscribeEvent
        public void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            clear(); // marks of the last server must not leak into the next one
        }

        // Pre + receiveCanceled: TACZ cancels the vanilla crosshair layer while a gun is held, and Forge never fires Post for a cancelled layer - that
        // is why the icons only showed now and then. The hitmarker mod listens on Pre for the same reason.
        @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = true)
        public void onOverlay(RenderGuiOverlayEvent.Pre event) {
            if (event.getOverlay().id() != VanillaGuiOverlay.CROSSHAIR.id()) {
                return;
            }
            int mask = shownMask;
            if (mask == 0) {
                return;
            }
            int duration = ClientCfg.HIT_PERK_DURATION_MS.get();
            long age = System.currentTimeMillis() - shownAt;
            if (age >= duration) {
                shownMask = 0;
                return;
            }
            float t = age / (float) duration;
            float alpha = t < 0.6f ? 1f : 1f - (t - 0.6f) / 0.4f;           // hold, then fade with the marker
            float pop = t < 0.12f ? 1.25f - 0.25f * (t / 0.12f) : 1f;        // small pop on the hit
            int size = ClientCfg.HIT_PERK_SIZE.get();
            int count = Integer.bitCount(mask);
            int gap = 3;
            int totalW = count * size + (count - 1) * gap;
            GuiGraphics g = event.getGuiGraphics();
            int cx = event.getWindow().getGuiScaledWidth() / 2;
            int cy = event.getWindow().getGuiScaledHeight() / 2 + ClientCfg.HIT_PERK_OFFSET_Y.get();
            int x = cx - totalW / 2;
            g.pose().pushPose();
            g.pose().translate(cx, cy + size / 2f, 0);
            g.pose().scale(pop, pop, 1f);
            g.pose().translate(-cx, -(cy + size / 2f), 0);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, alpha); // transparent icons, faded by real alpha - no plate behind them
            for (int i = 0; i < Net.PERK_MARKS.length; i++) {
                if ((mask & (1 << i)) == 0) {
                    continue;
                }
                var perk = LoadoutRegistry.get().perk(Net.PERK_MARKS[i]);
                if (perk != null && perk.icon != null && !perk.icon.isEmpty()) {
                    PerkIcons.drawPlain(g, perk.icon, x, cy, size);
                }
                x += size + gap;
            }
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.pose().popPose();
        }
    }
}
