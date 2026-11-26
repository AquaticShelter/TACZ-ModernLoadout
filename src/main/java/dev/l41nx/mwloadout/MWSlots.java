package dev.l41nx.mwloadout;

import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;

/**
 * The extra gunsmith slot "AMMO TYPE" (TACZ 1.2.0 ammo mods: fmj / he / hp / i / overpressure_recoil / slug).
 *
 * <p>In TACZ the ammo mods are attachments of type {@code extended_mag}, so they compete with the extended magazines for ONE slot. This mod adds a
 * second enum constant {@code AMMO_MOD} to {@link AttachmentType} (mixin {@code MixinAttachmentType}, the same technique TACZ-Arcana uses), retypes
 * the ammo-mod attachments to it ({@code MixinCommonAttachmentIndex}) and lets guns that take an extended mag take one (MixinAbstractGunItem).
 * Because TACZ walks {@code AttachmentType.values()} everywhere it reads attachment modifiers, damage / pierce / explosion / weight of the ammo mod
 * then apply natively, next to the extended magazine. Magazine capacity only ever reads the EXTENDED_MAG slot, so the extended mag wins by design.
 *
 * <p>Everything here is null-safe: when the mixin did not apply (or the rule is off) {@link #AMMO} is null / {@link #active()} is false and the
 * gunsmith behaves exactly as before.
 */
public final class MWSlots {
    /** The injected constant, set by MixinAttachmentType right after TACZ's enum is initialised. Null when the mixin did not apply. */
    public static volatile AttachmentType AMMO;
    private static volatile boolean failed;
    private static volatile String failReason = "";
    private static volatile String configProbe = "";

    private MWSlots() {
    }

    /**
     * Makes sure our mixin config is registered with Mixin as early as the mod constructor. Normally the jar manifest ("MixinConfigs") already did
     * it at launch and this finds it; if not, it registers the config here (works only while TACZ's AttachmentType has not been loaded yet, which
     * is why this is the first thing the constructor does). What happened is recorded for {@link #logStatus()}.
     */
    public static void ensureMixinConfig() {
        try {
            boolean has = org.spongepowered.asm.mixin.Mixins.getConfigs().stream().anyMatch(c -> c.getName().contains("mwloadout"));
            if (has) {
                configProbe = "registered at launch";
            } else {
                org.spongepowered.asm.mixin.Mixins.addConfiguration("mwloadout.mixins.json");
                configProbe = "registered late (mod constructor)";
            }
        } catch (Throwable t) {
            configProbe = "Mixin API not usable: " + t;
        }
    }

    /** Called by the mixin if adding the constant failed: the feature stays off and the reason is logged once. */
    public static void fail(Throwable cause) {
        failed = true;
        failReason = String.valueOf(cause);
        MWLoadoutMod.LOGGER.warn("[MWLoadout] AMMO TYPE slot unavailable: {}", failReason);
    }

    /**
     * One line in latest.log after every mod is loaded, saying WHERE the AMMO TYPE slot stands: was the mixin config registered, does TACZ's enum
     * contain the constant, did the mixin report a failure. (A missing slot in the gunsmith always traces back to one of these three.)
     */
    public static void logStatus() {
        boolean inEnum = false;
        try {
            for (AttachmentType type : AttachmentType.values()) {
                if ("AMMO_MOD".equals(type.name())) {
                    inEnum = true;
                    if (AMMO == null) {
                        AMMO = type; // the mixin ran but the static was not set (should not happen) - heal it
                    }
                }
            }
        } catch (Throwable t) {
            failReason = String.valueOf(t);
        }
        MWLoadoutMod.LOGGER.info("[MWLoadout] AMMO TYPE slot: mixin config {}, enum constant present={}, failure={}",
                configProbe.isEmpty() ? "not probed" : configProbe, inEnum, failReason.isEmpty() ? "none" : failReason);
        if (!inEnum) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] AMMO TYPE slot is OFF: TACZ's AttachmentType has no AMMO_MOD constant (the mixin did not apply). "
                    + "Search this log for lines containing 'mixin' / 'mwloadout.mixins.json' for the reason.");
        }
    }

    /** The constant exists (the mixin ran). */
    public static boolean available() {
        return AMMO != null && !failed;
    }

    /** Available AND switched on by the admin rule {@code dualAmmoSlot}. */
    public static boolean active() {
        if (!available()) {
            return false;
        }
        try {
            return dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules().dualAmmoSlot;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final ResourceLocation SLUG_TAG = new ResourceLocation("tacz", "intrinsic/slug");

    /**
     * True when a slug ammo mod is fitted. TACZ turns a shotgun into one-projectile slug rounds itself (ModernKineticGunItem's default property
     * modification returns bullet_amount = 1) - but it only looks at the EXTENDED_MAG slot, so once the slug lives in the AMMO TYPE slot it has to
     * be told (MixinDefaultPropertyModification). {@code includeMagSlot} also checks the native slot (for the gunsmith's stats).
     */
    public static boolean slugFitted(net.minecraft.world.item.ItemStack gun, boolean includeMagSlot) {
        try {
            com.tacz.guns.api.item.IGun igun = com.tacz.guns.api.item.IGun.getIGunOrNull(gun);
            if (igun == null) {
                return false;
            }
            if (AMMO != null && isSlug(igun.getAttachmentId(gun, AMMO))) {
                return true;
            }
            return includeMagSlot && isSlug(igun.getAttachmentId(gun, AttachmentType.EXTENDED_MAG));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSlug(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        try {
            if (com.tacz.guns.util.AllowAttachmentTagMatcher.matchTag(SLUG_TAG, id)) { // TACZ's own tag: tacz:intrinsic/slug
                return true;
            }
        } catch (Throwable ignored) {
            // older TACZ without the matcher: fall through to the id check
        }
        return id.getPath().equals("ammo_mod_slug");
    }

    public static boolean isAmmoSlot(AttachmentType type) {
        return type != null && type == AMMO;
    }

    /** An ammo mod = an extended_mag-typed attachment whose data id starts with "ammo_mod" (tacz:ammo_mod_fmj_data ...). */
    public static boolean isAmmoModData(ResourceLocation dataId) {
        return dataId != null && dataId.getPath().startsWith("ammo_mod");
    }

    /** Name shown in the gunsmith for a slot (EXTENDED_MAG is the MAGAZINE once the ammo slot exists next to it). */
    public static String label(AttachmentType type) {
        if (isAmmoSlot(type)) {
            return "AMMO TYPE";
        }
        if (type == AttachmentType.EXTENDED_MAG && available()) {
            return "MAGAZINE";
        }
        return type.name().replace('_', ' ');
    }
}
