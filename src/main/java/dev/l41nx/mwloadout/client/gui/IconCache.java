package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.data.CatalogEntry;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.init.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side icon stacks, built once per id and reused every frame — GUI grids render dozens of
 * these at 60fps and rebuilding NBT-carrying stacks each frame is pure allocation churn.
 *
 * Gun/attachment construction mirrors CodMatchmaking's TaczItemCache exactly (the pattern with
 * proven correct in-GUI rendering): presence-check via the CLIENT index (getClientGunIndex /
 * getClientAttachmentIndex — the client-side registry TACZ's item renderer actually reads), then
 * the id written straight into the "GunId"/"AttachmentId" NBT key the renderer looks up. Cache
 * invalidates wholesale on registry resync.
 */
public final class IconCache {

    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();

    private IconCache() {
    }

    public static void clear() {
        CACHE.clear();
        try {
            PerkIcons.clear(); // perk definitions / textures may have changed with the resync
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/IconCache:37", ignored);
        }
    }

    public static ItemStack iconFor(CatalogEntry entry) {
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        return CACHE.computeIfAbsent("e:" + entry.id + "|" + entry.gameId + "|" + entry.nbt, key -> buildEntry(entry));
    }

    /** Display stack for any killstreak reward id (see StreakItemFactory). */
    public static ItemStack anyIcon(String id) {
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }
        return CACHE.computeIfAbsent("any:" + id, key -> {
            try {
                return dev.l41nx.mwloadout.server.StreakItemFactory.build(id);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        });
    }

    public static ItemStack attachmentIcon(String attachmentId) {
        if (attachmentId == null || attachmentId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return CACHE.computeIfAbsent("a:" + attachmentId, key -> buildAttachment(attachmentId));
    }

    private static ItemStack buildEntry(CatalogEntry entry) {
        try {
            if (entry.kind == CatalogEntry.Kind.GUN) {
                ResourceLocation gunId = new ResourceLocation(entry.gameId);
                if (TimelessAPI.getClientGunIndex(gunId).isPresent()) {
                    ItemStack stack = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
                    stack.getOrCreateTag().putString("GunId", gunId.toString());
                    return stack;
                }
                return ItemStack.EMPTY;
            }
            // v4.3.6 (bug #8): route ATTACHMENT-kind entries through the real attachment icon
            // builder — entry.gameId is a TACZ attachment id here, not a registered ITEM id, so
            // falling through to the item-lookup branch below would never resolve one correctly.
            if (entry.kind == CatalogEntry.Kind.ATTACHMENT) {
                return buildAttachment(entry.gameId);
            }
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(entry.gameId));
            if (item == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(item);
            if (entry.nbt != null && !entry.nbt.isBlank()) {
                CompoundTag tag = TagParser.parseTag(entry.nbt);
                stack.setTag(tag.merge(stack.getOrCreateTag()));
            }
            return stack;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack buildAttachment(String attachmentId) {
        try {
            ResourceLocation attachmentRL = new ResourceLocation(attachmentId);
            if (TimelessAPI.getClientAttachmentIndex(attachmentRL).isPresent()) {
                ItemStack stack = new ItemStack(ModItems.ATTACHMENT.get());
                stack.getOrCreateTag().putString("AttachmentId", attachmentRL.toString());
                return stack;
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/IconCache:109", ignored);
        }
        return ItemStack.EMPTY;
    }
}
