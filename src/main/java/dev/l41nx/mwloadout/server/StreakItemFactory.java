package dev.l41nx.mwloadout.server;

import com.tacz.guns.api.TimelessAPI;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * Turns a killstreak reward id into a real ItemStack - ANY item, not just TACZ guns. Accepted forms,
 * tried in this order:
 * <ol>
 *   <li>a catalog entry id ({@code gun_tacz_rpg7}, {@code lrt_drone}) - uses that entry's kind/NBT</li>
 *   <li>a TACZ gun id ({@code tacz:rpg7})</li>
 *   <li>any registered item id, optionally with SNBT right after it: {@code ns:item} or
 *       {@code ns:item{Tag:1b}}</li>
 *   <li>a raw lang key ({@code item.ns.path}) - converted back to {@code ns:path}</li>
 * </ol>
 * Used by both the server (granting) and the client (name / icon / preview), so what the editor shows is
 * exactly what the player will receive. Returns {@link ItemStack#EMPTY} when nothing matches.
 */
public final class StreakItemFactory {

    private StreakItemFactory() {
    }

    public static ItemStack build(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return ItemStack.EMPTY;
        }
        String id = rawId.trim();
        String nbt = "";
        int brace = id.indexOf('{');
        if (brace > 0) {
            nbt = id.substring(brace);
            id = id.substring(0, brace).trim();
        }
        try {
            CatalogEntry entry = LoadoutRegistry.get().entry(id);
            if (entry != null) {
                return fromEntry(entry);
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                if (TimelessAPI.getCommonGunIndex(rl).isPresent()) {
                    return TaczStackFactory.buildGun(rl.toString(), Map.of(), 0);
                }
                ItemStack stack = plainItem(rl, nbt);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
            // "item.ns.path" / "block.ns.path" translation keys -> ns:path
            if (id.startsWith("item.") || id.startsWith("block.")) {
                String rest = id.substring(id.indexOf('.') + 1);
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    ResourceLocation fromKey = ResourceLocation.tryParse(rest.substring(0, dot) + ":" + rest.substring(dot + 1).replace('.', '/'));
                    if (fromKey != null) {
                        return plainItem(fromKey, nbt);
                    }
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.swallowed("server/StreakItemFactory:70", ignored);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack fromEntry(CatalogEntry entry) {
        if (entry.kind == CatalogEntry.Kind.GUN) {
            return TaczStackFactory.buildGun(entry.gameId, Map.of(), 0);
        }
        if (entry.kind == CatalogEntry.Kind.ATTACHMENT) {
            return TaczStackFactory.buildAttachment(entry.gameId);
        }
        ResourceLocation rl = ResourceLocation.tryParse(entry.gameId);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = plainItem(rl, entry.nbt == null ? "" : entry.nbt);
        if (!stack.isEmpty()) {
            stack.setCount(Math.max(1, entry.count));
        }
        return stack;
    }

    private static ItemStack plainItem(ResourceLocation rl, String nbt) {
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (nbt != null && !nbt.isBlank()) {
            try {
                CompoundTag tag = TagParser.parseTag(nbt);
                stack.setTag(tag.merge(stack.getOrCreateTag()));
            } catch (Exception ignored) {
                // bad SNBT: hand over the plain item rather than nothing
            }
        }
        return stack;
    }

    /** True when the stack is a TACZ gun (needs the one-round magazine treatment; plain items don't). */
    public static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && com.tacz.guns.api.item.IGun.getIGunOrNull(stack) != null;
    }
}
