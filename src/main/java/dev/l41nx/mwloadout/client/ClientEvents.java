package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.client.gui.MWStyle;
import dev.l41nx.mwloadout.network.Net;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import dev.l41nx.mwloadout.client.gui.LoadoutScreen;
import dev.l41nx.mwloadout.client.gui.SlotPickerScreen;
import dev.l41nx.mwloadout.client.gui.AttachmentEditorScreen;
import dev.l41nx.mwloadout.client.gui.PerkPickerScreen;
import dev.l41nx.mwloadout.client.gui.SettingsScreen;
import dev.l41nx.mwloadout.client.gui.KillstreakWeaponScreen;
import dev.l41nx.mwloadout.client.gui.CustomizationsScreen;
import dev.l41nx.mwloadout.client.gui.TutorialScreen;
import dev.l41nx.mwloadout.client.gui.KillFeedSettingsScreen;
import dev.l41nx.mwloadout.client.gui.admin.AdminScreen;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Keybind (default L) + the LOADOUT button injected top-left into the vanilla pause screen —
 *  both routes go through the server (C2SOpenLoadout) so editability is always freshly decided. */
public final class ClientEvents {

    public static final KeyMapping OPEN_LOADOUT = new KeyMapping(
            "key.mwloadout.open", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, "key.category.mwloadout");

    /** Hold (or tap) X: the killstreak radial. */
    public static final KeyMapping STREAK_RADIAL = new KeyMapping(
            "key.mwloadout.streak_radial", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.category.mwloadout");

    private ClientEvents() {
    }

    @Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_LOADOUT);
            event.register(STREAK_RADIAL);
        }

        @SubscribeEvent
        public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("high_alert", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                    HighAlertOverlay.render(graphics, screenWidth, screenHeight));
            // premium animated killstreak/specialist toasts (see ToastHud). No partialTick
            // passed — this class deliberately never uses it (see its class javadoc).
            event.registerAboveAll("mwloadout_toasts", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                    ToastHud.render(graphics, screenWidth, screenHeight));
            // CoD-style vertical killstreak tracker, bottom-right (see StreakHud; toggle in [hud] config).
            event.registerAboveAll("mwloadout_streak_callouts", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                    StreakCallouts.render(graphics, screenWidth, screenHeight));
            event.registerAboveAll("mwloadout_streak", (gui, graphics, partialTick, screenWidth, screenHeight) ->
                    StreakHud.render(graphics, screenWidth, screenHeight));
        }
    }

    @Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeBus {
        // HUD-hide bookkeeping: while ANY of our loadout screens is open we set Minecraft's
        // hideGui (the same flag F1 flips) so the in-world HUD isn't drawn behind the now
        // semi-transparent menu — cleaner and a little cheaper. Restored the instant we leave.
        private static boolean hudHiddenByUs;
        private static boolean priorHideGui;

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean onOurScreen = isOurScreen(mc.screen);
            if (onOurScreen && !hudHiddenByUs) {
                priorHideGui = mc.options.hideGui;
                mc.options.hideGui = true;
                hudHiddenByUs = true;
            } else if (!onOurScreen && hudHiddenByUs) {
                mc.options.hideGui = priorHideGui; // restore the player's own F1 state
                hudHiddenByUs = false;
            }
            while (OPEN_LOADOUT.consumeClick()) {
                if (mc.player == null) {
                    continue;
                }
                if (mc.screen == null) {
                    Net.CHANNEL.sendToServer(new Net.C2SOpenLoadout());
                }
                // Closing with L is handled inside the screen's keyPressed (keybinds don't poll
                // while a GUI is open), see LoadoutScreen.
            }
        }

        /** Belt-and-suspenders HUD suppression: cancel the whole GUI/HUD render pass while one
         *  of our screens is open. hideGui alone can miss mod-added HUD layers; cancelling the
         *  render event stops ALL of them, and it runs every frame so there's no timing gap
         *  (the tick-based hideGui could lag a frame on the packet-driven open). */
        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Pre event) {
            if (isOurScreen(Minecraft.getInstance().screen)) {
                event.setCanceled(true);
            }
        }

        private static boolean isOurScreen(net.minecraft.client.gui.screens.Screen screen) {
            return screen instanceof LoadoutScreen
                    || screen instanceof SlotPickerScreen
                    || screen instanceof AttachmentEditorScreen
                    || screen instanceof PerkPickerScreen
                    || screen instanceof SettingsScreen
                    || screen instanceof KillstreakWeaponScreen
                    || screen instanceof dev.l41nx.mwloadout.client.gui.KillstreakSelectScreen
                    || screen instanceof CustomizationsScreen
                    || screen instanceof TutorialScreen
                    || screen instanceof KillFeedSettingsScreen
                    || screen instanceof AdminScreen;
        }

        @SubscribeEvent
        public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            dev.l41nx.mwloadout.client.ClientCache.resetSessionState();
            ToastHud.clear(); // don't carry stale toasts into the next server/session
            StreakHud.clear();
            StreakCallouts.clear();
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            if (!(event.getScreen() instanceof PauseScreen) || Minecraft.getInstance().player == null) {
                return;
            }
            event.addListener(MWStyle.flatButton(8, 8, 88, 20,
                    Component.translatable("screen.mwloadout.pause_button"),
                    button -> Net.CHANNEL.sendToServer(new Net.C2SOpenLoadout())));
        }
    }
}
