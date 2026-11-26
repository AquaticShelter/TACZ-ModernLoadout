package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import dev.l41nx.mwloadout.MWSlots;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A gun takes the AMMO_MOD slot exactly when it takes an extended magazine (guns list their allowed types in their own data). */
@Mixin(value = AbstractGunItem.class, remap = false)
public abstract class MixinAbstractGunItem {
    @Shadow
    public abstract boolean allowAttachmentType(ItemStack gun, AttachmentType type);

    @Inject(method = "allowAttachmentType", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void mwloadout$ammoSlot(ItemStack gun, AttachmentType type, CallbackInfoReturnable<Boolean> cir) {
        if (MWSlots.isAmmoSlot(type)) {
            cir.setReturnValue(MWSlots.active() && allowAttachmentType(gun, AttachmentType.EXTENDED_MAG));
        }
    }
}
