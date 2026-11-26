package dev.l41nx.mwloadout.registry;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * Suggests which loadout category a creative-inventory item belongs to, so the admin's item
 * browser can file hundreds of items correctly in one click. Order of evidence: TACZ gun type
 * (authoritative) -> LRTactical NBT id keys -> vanilla item class -> keyword match on the item id
 * and its creative tab name -> fallback "extra3" (the opt-in novelty slot). It is only a SUGGESTION:
 * the browser shows it and lets the admin override before anything is added.
 * Client-safe: touches no server-only classes.
 */
public final class CategoryGuesser {

    private CategoryGuesser() {
    }

    /** Result of a guess; `confident` = backed by hard data (gun type / NBT key / item class). */
    public record Guess(String category, boolean confident) {
    }

    public static Guess guess(ItemStack stack, String tabName) {
        try {
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun != null) {
                ResourceLocation gunId = gun.getGunId(stack);
                String type = "";
                if (gunId != null) {
                    var index = TimelessAPI.getCommonGunIndex(gunId);
                    if (index.isPresent()) {
                        type = index.get().getType();
                    }
                }
                return new Guess(CreativeTabScanner.normalizeCategory(type), true);
            }
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                if (!tag.getString("MeleeWeaponId").isEmpty()) {
                    return new Guess("melee", true);
                }
                if (!tag.getString("ConsumableId").isEmpty()) {
                    return new Guess("consumable", true);
                }
                if (!tag.getString("ThrowableId").isEmpty()) {
                    return new Guess(CreativeTabScanner.throwableCategory(tag.getString("ThrowableId")), true);
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.swallowed("registry/CategoryGuesser:59", ignored);
        }
        String path = "";
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key != null) {
            path = key.getPath().toLowerCase(Locale.ROOT);
        }
        String hay = path + " " + (tabName == null ? "" : tabName.toLowerCase(Locale.ROOT));

        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem
                || has(hay, "knife", "sword", "machete", "katana", "dagger", "tomahawk", "melee")) {
            return new Guess("melee", stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem);
        }
        if (has(hay, "grenade", "frag", "semtex", "c4", "claymore", "landmine", "molotov", "thermite", "incendiary", "tnt", "dynamite")) {
            return new Guess("lethal", false);
        }
        if (has(hay, "flash", "smoke", "stun", "decoy", "tear", "gas", "sensor")) {
            return new Guess("tactical", false);
        }
        if (stack.getItem().isEdible() || has(hay, "medkit", "bandage", "heal", "stim", "syringe", "potion", "food", "ration")) {
            return new Guess("consumable", stack.getItem().isEdible());
        }
        if (stack.getItem() instanceof ArmorItem) {
            return new Guess("extra3", false);
        }
        return new Guess("extra3", false);
    }

    private static boolean has(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
