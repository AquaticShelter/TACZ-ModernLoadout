package dev.l41nx.mwloadout.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tacz.guns.api.item.IGun;
import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.client.gui.StreakRadialScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Killstreak radial: finding the streak item in the inventory, taking it in hand and - for anything that is not a TACZ gun - right-clicking it.
 * Streak items carry the tags the server puts on them (KillstreakItems.STREAK_TAG / REWARD_ID_TAG), so the lookup works for items from any mod.
 */
@Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID, value = Dist.CLIENT)
public final class StreakRadial {
    private static final String STREAK_TAG = "mwloadout_streak";
    private static final String REWARD_ID_TAG = "MWStreakRewardId";

    /** Hotbar slot the player was on before the radial put a streak item in hand: what the X slot returns to. */
    private static int returnSlot = -1;
    /** Main-inventory index (9-35) the previous weapon was swapped OUT to when the streak item came from there, else -1. */
    private static int swappedFrom = -1;
    private static String trackedId;     // streak item chosen through the radial, waiting to be spent
    private static int trackedSlot = -1; // hotbar slot it sits in
    private static boolean trackedSeen;
    private static int trackedSince;
    private static boolean usePending;
    private static boolean useHeld;
    private static int useAtTick;
    private static int releaseAtTick;

    private StreakRadial() {
    }

    /** Inventory index (0-35) of the streak item for this reward id, or -1. Hotbar first. */
    public static int find(String rewardId) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || rewardId == null) {
            return -1;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(STREAK_TAG)
                    && rewardId.equals(stack.getTag().getString(REWARD_ID_TAG))) {
                return i;
            }
        }
        return -1;
    }

    /** Takes the streak item in hand; a non-gun is then right-clicked automatically. @return false when it is not in the inventory. */
    public static boolean activate(String rewardId) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int index = find(rewardId);
        if (player == null || mc.gameMode == null || index < 0) {
            return false;
        }
        int selected = player.getInventory().selected;
        if (index == selected) {
            // already in hand: nothing to swap, and the way back stays what it was
        } else {
            returnSlot = selected;
            swappedFrom = index < 9 ? -1 : index;
            if (index < 9) {
                player.getInventory().selected = index; // hotbar: just select it
            } else {
                // main inventory: swap it into the selected hotbar slot, exactly like hovering it and pressing that number key
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, index, selected, ClickType.SWAP, player);
            }
        }
        ItemStack inHand = player.getInventory().getItem(player.getInventory().selected);
        boolean tacz = IGun.getIGunOrNull(inHand) != null;
        // once this item is spent the player goes back to the weapon they came from - unless the item is on the no-return list
        if (returnSlot >= 0 && !noReturn(rewardId, inHand)) {
            trackedId = rewardId;
            trackedSlot = player.getInventory().selected;
            trackedSeen = false;
            trackedSince = player.tickCount;
        } else {
            trackedId = null;
        }
        if (!tacz && ClientCfg.RADIAL_AUTO_USE.get()) {
            usePending = true;
            useAtTick = player.tickCount + 2; // let the selected slot reach the server first
        }
        return true;
    }

    private static boolean noReturn(String rewardId, ItemStack stack) {
        net.minecraft.resources.ResourceLocation itemKey = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        String itemId = itemKey == null ? "" : itemKey.toString();
        try {
            for (String entry : ClientCfg.RADIAL_NO_RETURN.get()) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String e = entry.trim();
                boolean wildcard = e.endsWith("*");
                String prefix = wildcard ? e.substring(0, e.length() - 1) : e;
                if (wildcard ? (itemId.startsWith(prefix) || (rewardId != null && rewardId.startsWith(prefix)))
                        : (e.equals(itemId) || e.equals(rewardId))) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // config not readable: default behaviour (return)
        }
        return false;
    }

    /**
     * The streak item is spent: put the weapon the player came from back in hand. When the item was taken from the main inventory
     * (swapped into the held hotbar slot), the previous weapon is waiting in that inventory slot and the hotbar slot is now empty -
     * swap it back first, otherwise "back to the previous slot" would just select an empty slot.
     */
    private static void restoreWeapon(Minecraft mc, LocalPlayer player) {
        var inventory = player.getInventory();
        if (swappedFrom >= 9 && trackedSlot == returnSlot && mc.gameMode != null
                && inventory.getItem(trackedSlot).isEmpty() && !inventory.getItem(swappedFrom).isEmpty()) {
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, swappedFrom, trackedSlot, ClickType.SWAP, player);
        }
        inventory.selected = returnSlot;
    }

    /** The X slot: put the previous weapon back in hand. */
    public static void cancel() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && returnSlot >= 0 && returnSlot < 9) {
            // the streak item was swapped in from the main inventory: the previous weapon is still sitting there, and the held
            // hotbar slot holds the streak item - swap them back instead of just re-selecting that same slot
            if (swappedFrom >= 9 && mc.gameMode != null && player.getInventory().selected == returnSlot
                    && !player.getInventory().getItem(swappedFrom).isEmpty()) {
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, swappedFrom, returnSlot, ClickType.SWAP, player);
            }
            player.getInventory().selected = returnSlot;
        }
        returnSlot = -1;
        swappedFrom = -1;
        usePending = false;
        trackedId = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            usePending = false;
            useHeld = false;
            return;
        }
        // streak item spent (gone from the inventory) while it is still in hand: back to the weapon the player came from
        if (trackedId != null) {
            if (find(trackedId) >= 0) {
                trackedSeen = true;
            } else if (trackedSeen) {
                if (player.getInventory().selected == trackedSlot && returnSlot >= 0 && returnSlot < 9) {
                    restoreWeapon(mc, player);
                }
                trackedId = null;
                returnSlot = -1;
                swappedFrom = -1;
            } else if (player.tickCount - trackedSince > 60) {
                trackedId = null; // never showed up: stop watching
            }
        }
        // open the radial
        if (mc.screen == null && ClientEvents.STREAK_RADIAL.consumeClick()) {
            if (StreakHud.idsArray().length > 0) {
                mc.setScreen(new StreakRadialScreen());
            }
        }
        // the automatic right-click: one click plus a short hold
        KeyMapping use = mc.options.keyUse;
        if (usePending && mc.screen == null && player.tickCount >= useAtTick) {
            usePending = false;
            useHeld = true;
            releaseAtTick = player.tickCount + ClientCfg.RADIAL_USE_HOLD_TICKS.get();
            use.setDown(true);
            KeyMapping.click(use.getKey());
        }
        if (useHeld && player.tickCount >= releaseAtTick) {
            useHeld = false;
            use.setDown(false);
        }
    }

    /** True while the physical key / mouse button of this mapping is held (the screen cannot rely on KeyMapping.isDown, which is cleared in a screen). */
    public static boolean isHeld(KeyMapping mapping) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, key.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key.getValue());
    }
}
