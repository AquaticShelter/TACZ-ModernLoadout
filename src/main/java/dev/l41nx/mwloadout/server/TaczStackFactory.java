package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns catalog entries into real ItemStacks. Gun building follows CodMatchmaking's proven
 * TaczLoadoutBuilder sequence exactly (MODERN_KINETIC_GUN base item -> setGunId -> full magazine
 * + first fire mode from the gun's own index -> installAttachment per selection); item building
 * is registry item + parsed SNBT + count.
 *
 * Ammo is where this deliberately goes beyond CodMatchmaking (which handed out a creative ammo
 * box): the gun's ammo TYPE is read from its own TACZ index (allowedAmmoId? no — getAmmoId), so
 * reserve ammo works for every gunpack automatically, and the AMOUNT comes from Rules
 * (category-based with per-gun overrides).
 */
public final class TaczStackFactory {

    private TaczStackFactory() {
    }

    /** Reserved key inside the SAME attachments Map<String,String> every build already carries —
     *  stores the player's fire-mode choice (FireMode enum name, e.g. "SEMI"/"BURST"/"AUTO")
     *  without needing any data-model or serialization changes, since GunBuilds/Selection.attachments
     *  is already a generic string map. Never passed to buildAttachment() — filtered out of the
     *  real attachment-install loop below. */
    public static final String FIRE_MODE_KEY = "__fireMode";

    /** Laser colour of the gun's laser attachment, "#RRGGBB". Rides in the same attachments map as FIRE_MODE_KEY (no schema change: every
     *  existing save / network / cloud path already carries the map) and, like it, is NOT an attachment. */
    public static final String LASER_COLOR_KEY = "__laserColor";

    /** TACZ 1.2.0 optic risers (slot adapters) ride in the same map as "__riser.SCOPE" -> adapter id. A riser is a mount, not an attachment:
     *  it never counts against the attachment limit and is simply ignored on TACZ versions that do not have them. */
    public static final String RISER_KEY_PREFIX = "__riser.";

    /** Entries a gun build must not install: the settings keys, and an AMMO_MOD pick while the AMMO TYPE slot is off (it would land in - and
     *  replace the magazine in - the shared EXTENDED_MAG slot). */
    private static boolean skipOnBuild(String key) {
        return isMeta(key) || "AMMO_MOD".equals(key) && !dev.l41nx.mwloadout.MWSlots.active();
    }

    /** Keys of the attachments map that are settings, not attachments - never installed, never counted against the point budget. */
    public static boolean isMeta(String key) {
        return FIRE_MODE_KEY.equals(key) || LASER_COLOR_KEY.equals(key) || key != null && key.startsWith(RISER_KEY_PREFIX);
    }

    private static boolean laserColorLogged;

    /** Applies the chosen laser colour to a freshly built attachment stack BEFORE it is installed (the gun stores the whole attachment stack,
     *  so the colour travels with it). TACZ's own refit screen edits this value on attachments whose display sets laser can_edit; the colour is
     *  written through TACZ's API when it exposes a setter, otherwise to the "LaserColor" NBT tag it reads. */
    public static void applyLaserColor(ItemStack attachmentStack, String typeKey, String hex) {
        if (hex == null || !"LASER".equals(typeKey) || attachmentStack.isEmpty()) {
            return;
        }
        int rgb;
        try {
            rgb = Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return;
        }
        String how = "NBT LaserColor";
        boolean done = false;
        try {
            IAttachment attachment = IAttachment.getIAttachmentOrNull(attachmentStack);
            if (attachment != null) {
                for (String name : new String[]{"setLaserColor", "setColor"}) {
                    try {
                        attachment.getClass().getMethod(name, ItemStack.class, int.class).invoke(attachment, attachmentStack, rgb);
                        how = "IAttachment." + name;
                        done = true;
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // try the next name
                    }
                }
            }
        } catch (Throwable t) {
            // fall through to the NBT write
        }
        if (!done) {
            attachmentStack.getOrCreateTag().putInt("LaserColor", rgb);
        }
        if (!laserColorLogged) {
            laserColorLogged = true;
            MWLoadoutMod.LOGGER.info("[MWLoadout] Laser colour #{} applied via {}; attachment tag now {}", String.format("%06X", rgb), how, attachmentStack.getTag());
        }
    }

    public static ItemStack buildGun(String gunId, Map<String, String> attachments) {
        return buildGun(gunId, attachments, 0);
    }

    /** dummyRounds > 0 writes TACZ's "DummyAmmo" int tag — the gun then reloads from ITSELF
     *  (useDummyAmmo() in GunItemDataAccessor is literally "does the tag exist"), the verified
     *  NBT approach: reserve travels with the weapon, no loose ammo stacks in the inventory. */
    public static ItemStack buildGun(String gunId, Map<String, String> attachments, int dummyRounds) {
        try {
            ItemStack stack = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
            IGun igun = IGun.getIGunOrNull(stack);
            if (igun == null) {
                return ItemStack.EMPTY;
            }
            ResourceLocation gunRL = new ResourceLocation(gunId);
            igun.setGunId(stack, gunRL);
            if (dummyRounds > 0) {
                igun.setDummyAmmoAmount(stack, dummyRounds);
            }
            var indexOpt = TimelessAPI.getCommonGunIndex(gunRL);
            List<FireMode> fireModes = indexOpt.map(index -> index.getGunData().getFireModeSet()).orElse(null);
            if (fireModes != null && !fireModes.isEmpty()) {
                FireMode chosen = fireModes.get(0);
                String savedMode = attachments != null ? attachments.get(FIRE_MODE_KEY) : null;
                if (savedMode != null) {
                    try {
                        FireMode requested = FireMode.valueOf(savedMode);
                        if (fireModes.contains(requested)) {
                            chosen = requested; // still valid for this gun (e.g. after a swap) - use it
                        }
                    } catch (IllegalArgumentException ignored) {
                        // stale/unknown mode name (gun changed, or hand-edited data) - keep the default
                    }
                }
                igun.setFireMode(stack, chosen);
            }
            // Attachments MUST install before the magazine-capacity calculation below — an
            // Extended Mag changes capacity, and getAmmoCountWithAttachment reads whatever's
            // CURRENTLY installed on the stack. Installing them first (instead of after, like the
            // old order) is the actual fix for "add Extended Mag, ammo count doesn't change until
            // a manual reload": build it right the first time instead of relying on the player to
            // reload afterward to pick up the new capacity.
            if (attachments != null) {
                for (Map.Entry<String, String> attachment : attachments.entrySet()) {
                    if (skipOnBuild(attachment.getKey())) {
                        continue; // not a real attachment point - the fire-mode / laser-colour settings
                    }
                    String attachmentId = attachment.getValue();
                    if (attachmentId == null || attachmentId.isEmpty()) {
                        continue;
                    }
                    try {
                        ItemStack attachmentStack = buildAttachment(attachmentId);
                        if (!attachmentStack.isEmpty()) {
                            applyLaserColor(attachmentStack, attachment.getKey(), attachments.get(LASER_COLOR_KEY));
                            igun.installAttachment(stack, attachmentStack);
                        }
                    } catch (Exception e) {
                        MWLoadoutMod.LOGGER.warn("[MWLoadout] Could not install {} on {}", attachmentId, gunId, e);
                    }
                }
                TaczCompat.applyAdapters(stack, attachments); // TACZ 1.2.0 risers; a no-op on older TACZ
            }
            // Deploy chamber-ready at the BUILD's true capacity: extended mags count (the same
            // AttachmentDataUtils call TACZ's HUD uses), so a Mag3 gun spawns with Mag3 rounds
            // instead of base-mag rounds + a mandatory first reload. Set exactly once, through
            // TACZ's own setCurrentAmmoCount, so there's a single source of truth for the tag —
            // the old code wrote the correct value here and then immediately overwrote it with
            // the raw base gunData amount in a second call right after, silently discarding it.
            if (indexOpt.isPresent()) {
                try {
                    int magazine = com.tacz.guns.util.AttachmentDataUtils
                            .getAmmoCountWithAttachment(stack, indexOpt.get().getGunData());
                    igun.setCurrentAmmoCount(stack, magazine);
                    stack.getOrCreateTag().putBoolean("HasBulletInBarrel", true);
                } catch (Exception ignored) {
                    igun.setCurrentAmmoCount(stack, indexOpt.get().getGunData().getAmmoAmount());
                }
            }
            return stack;
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Failed to build gun {}", gunId, e);
            return ItemStack.EMPTY;
        }
    }

    /** Re-installs {@code attachments} on a gun stack that already exists (the one in a player's inventory), keeping its runtime state:
     *  rounds in the magazine (clamped to the new capacity), reserve ammo, chamber. Used for the gunsmith's live apply - the build used to
     *  be saved to the profile only, so the gun in hand kept the old attachments until the class was re-applied. */
    public static boolean refreshAttachments(ItemStack stack, String gunId, Map<String, String> attachments) {
        try {
            IGun igun = IGun.getIGunOrNull(stack);
            if (igun == null) {
                return false;
            }
            net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
            for (String key : new java.util.ArrayList<>(tag.getAllKeys())) {
                if (key.startsWith("Attachment")) {
                    tag.remove(key); // every installed attachment slot: AttachmentSCOPE, AttachmentMUZZLE, ...
                }
            }
            var indexOpt = TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId));
            if (attachments != null) {
                String savedMode = attachments.get(FIRE_MODE_KEY);
                List<FireMode> fireModes = indexOpt.map(index -> index.getGunData().getFireModeSet()).orElse(null);
                if (savedMode != null && fireModes != null) {
                    try {
                        FireMode requested = FireMode.valueOf(savedMode);
                        if (fireModes.contains(requested)) {
                            igun.setFireMode(stack, requested);
                        }
                    } catch (IllegalArgumentException ignored) {
                        dev.l41nx.mwloadout.MWLog.swallowed("server/TaczStackFactory:208", ignored);
                    }
                }
                for (Map.Entry<String, String> attachment : attachments.entrySet()) {
                    String attachmentId = attachment.getValue();
                    if (skipOnBuild(attachment.getKey()) || attachmentId == null || attachmentId.isEmpty()) {
                        continue;
                    }
                    ItemStack attachmentStack = buildAttachment(attachmentId);
                    if (!attachmentStack.isEmpty()) {
                        applyLaserColor(attachmentStack, attachment.getKey(), attachments.get(LASER_COLOR_KEY));
                        igun.installAttachment(stack, attachmentStack);
                    }
                }
                TaczCompat.applyAdapters(stack, attachments);
            }
            if (indexOpt.isPresent()) {
                int magazine = com.tacz.guns.util.AttachmentDataUtils.getAmmoCountWithAttachment(stack, indexOpt.get().getGunData());
                if (igun.getCurrentAmmoCount(stack) > magazine) {
                    igun.setCurrentAmmoCount(stack, magazine); // a removed Extended Mag can't leave an over-full magazine behind
                }
            }
            return true;
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Could not refresh attachments on {}", gunId, e);
            return false;
        }
    }

    /** Magazine capacity of a gun stack AS IT IS NOW (attachments already on it, Extended Mag included). -1 when it cannot be read. */
    public static int currentMagazineCapacity(ItemStack stack, String gunId) {
        try {
            var indexOpt = TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId));
            if (indexOpt.isEmpty()) {
                return -1;
            }
            return com.tacz.guns.util.AttachmentDataUtils.getAmmoCountWithAttachment(stack, indexOpt.get().getGunData());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Effective magazine capacity for dynamic ammo reserve (Rules.dynamicAmmoEnabled): base TACZ
     *  capacity, or the attachment-adjusted capacity when `attachments` includes something that
     *  changes it (Extended Mag and friends). Builds a throwaway probe stack using the SAME
     *  install sequence as buildGun() just to read the number back off it — the stack itself is
     *  discarded, never handed to a player. Returns -1 when the gun id doesn't resolve (unknown/
     *  dead gunId), so callers can tell "lookup failed" apart from "empty gun" and fall back to
     *  the static ammoByCategory table instead of silently reserving zero rounds. */
    public static int getMagazineCapacity(String gunId, Map<String, String> attachments) {
        try {
            ResourceLocation gunRL = new ResourceLocation(gunId);
            var indexOpt = TimelessAPI.getCommonGunIndex(gunRL);
            if (indexOpt.isEmpty()) {
                return -1;
            }
            boolean hasRealAttachment = false;
            if (attachments != null) {
                for (Map.Entry<String, String> attachment : attachments.entrySet()) {
                    if (!isMeta(attachment.getKey())
                            && attachment.getValue() != null && !attachment.getValue().isEmpty()) {
                        hasRealAttachment = true;
                        break;
                    }
                }
            }
            if (!hasRealAttachment) {
                return indexOpt.get().getGunData().getAmmoAmount();
            }
            ItemStack probe = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
            IGun igun = IGun.getIGunOrNull(probe);
            if (igun == null) {
                return indexOpt.get().getGunData().getAmmoAmount();
            }
            igun.setGunId(probe, gunRL);
            for (Map.Entry<String, String> attachment : attachments.entrySet()) {
                if (skipOnBuild(attachment.getKey())) {
                    continue;
                }
                String attachmentId = attachment.getValue();
                if (attachmentId == null || attachmentId.isEmpty()) {
                    continue;
                }
                try {
                    ItemStack attachmentStack = buildAttachment(attachmentId);
                    if (!attachmentStack.isEmpty()) {
                        igun.installAttachment(probe, attachmentStack);
                    }
                } catch (Exception ignored) {
                    dev.l41nx.mwloadout.MWLog.swallowed("server/TaczStackFactory:295", ignored);
                }
            }
            return com.tacz.guns.util.AttachmentDataUtils.getAmmoCountWithAttachment(probe, indexOpt.get().getGunData());
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Failed to read magazine capacity for {}", gunId, e);
            return -1;
        }
    }

    public static ItemStack buildAttachment(String attachmentId) {
        ResourceLocation attachmentRL = new ResourceLocation(attachmentId);
        try {
            ItemStack built = AttachmentItemBuilder.create().setId(attachmentRL).build();
            if (!built.isEmpty()) {
                return built;
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.swallowed("server/TaczStackFactory:312", ignored);
        }
        try {
            ItemStack fallback = new ItemStack(ModItems.ATTACHMENT.get());
            IAttachment attachment = IAttachment.getIAttachmentOrNull(fallback);
            if (attachment != null) {
                attachment.setAttachmentId(fallback, attachmentRL);
                return fallback;
            }
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.swallowed("server/TaczStackFactory:321", ignored);
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack buildItem(CatalogEntry entry, int countCap) {
        return buildItem(entry, countCap, 0);
    }

    /** bonusCount ADDS items (perk equipment bonuses) — still hard-capped by the slot. */
    public static ItemStack buildItem(CatalogEntry entry, int countCap, int bonusCount) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.gameId));
            if (item == null) {
                return ItemStack.EMPTY;
            }
            int count = Math.max(1, Math.min(entry.count + bonusCount, countCap));
            ItemStack stack = new ItemStack(item, count);
            if (entry.nbt != null && !entry.nbt.isBlank()) {
                CompoundTag tag = TagParser.parseTag(entry.nbt);
                stack.setTag(tag.merge(stack.getOrCreateTag()));
            }
            return stack;
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Failed to build item {} ({})", entry.id, entry.gameId, e);
            return ItemStack.EMPTY;
        }
    }

    /**
     * Reserve ammo for a gun, split into properly-sized stacks. The ammo id comes from the gun's
     * own index; a gun whose pack defines no ammo (or an unknown gun) yields nothing rather than
     * guessing.
     */
    public static List<ItemStack> buildReserveAmmo(String gunId, int totalRounds) {
        List<ItemStack> stacks = new ArrayList<>();
        if (totalRounds <= 0) {
            return stacks;
        }
        try {
            var indexOpt = TimelessAPI.getCommonGunIndex(new ResourceLocation(gunId));
            if (indexOpt.isEmpty()) {
                return stacks;
            }
            ResourceLocation ammoId = indexOpt.get().getGunData().getAmmoId();
            if (ammoId == null) {
                return stacks;
            }
            int remaining = totalRounds;
            while (remaining > 0) {
                ItemStack ammo = new ItemStack(ModItems.AMMO.get());
                IAmmo iammo = IAmmo.getIAmmoOrNull(ammo);
                if (iammo == null) {
                    return stacks;
                }
                iammo.setAmmoId(ammo, ammoId);
                int size = Math.min(remaining, ammo.getMaxStackSize());
                ammo.setCount(size);
                stacks.add(ammo);
                remaining -= size;
            }
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Failed to build ammo for {}", gunId, e);
        }
        return stacks;
    }
}
