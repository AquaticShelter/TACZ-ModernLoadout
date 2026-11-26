package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.resource.index.CommonGunIndex;
import dev.l41nx.mwloadout.MWSlots;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TACZ 1.2.0 makes a shotgun fire ONE slug instead of its pellet cloud when a slug ammo mod is fitted: its default property modification answers
 * "bullet_amount" with 1 - but it only reads the EXTENDED_MAG slot. With the slug in the AMMO TYPE slot that check no longer sees it, so the
 * shotgun kept firing pellets. This applies the same rule for the AMMO TYPE slot (an @Inject at HEAD only: nothing TACZ does is replaced).
 */
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunItem$DefaultPropertyModification", remap = false)
public abstract class MixinDefaultPropertyModification {
    @Inject(method = "modify", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void mwloadout$slugInAmmoSlot(ItemStack stack, LivingEntity shooter, CommonGunIndex index, String key, Object value,
                                          CallbackInfoReturnable<Object> cir) {
        if ("bullet_amount".equals(key) && MWSlots.active() && MWSlots.slugFitted(stack, false)) {
            cir.setReturnValue(1);
        }
    }
}
