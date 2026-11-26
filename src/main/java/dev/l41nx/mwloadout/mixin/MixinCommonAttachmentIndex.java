package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.resource.pojo.AttachmentIndexPOJO;
import dev.l41nx.mwloadout.MWSlots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ammo-mod attachments report the AMMO_MOD slot instead of EXTENDED_MAG (while the rule is on), so TACZ installs them into the second slot. */
@Mixin(value = CommonAttachmentIndex.class, remap = false)
public abstract class MixinCommonAttachmentIndex {
    @Shadow
    private AttachmentIndexPOJO pojo;

    @Unique
    private Boolean mwloadout$ammoMod;

    @Inject(method = "getType", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void mwloadout$retype(CallbackInfoReturnable<AttachmentType> cir) {
        if (cir.getReturnValue() != AttachmentType.EXTENDED_MAG || !MWSlots.active()) {
            return;
        }
        if (mwloadout$ammoMod == null) {
            mwloadout$ammoMod = pojo != null && MWSlots.isAmmoModData(pojo.getData()); // decided once per index
        }
        if (mwloadout$ammoMod) {
            cir.setReturnValue(MWSlots.AMMO);
        }
    }
}
