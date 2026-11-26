package dev.l41nx.mwloadout.mixin;

import dev.l41nx.mwloadout.attribute.MWAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** mw:ads_speed (client): the same ADS time change as the server, so the scope comes up in step. */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerAim", remap = false)
public abstract class MixinMWAimClient {
    @ModifyVariable(method = "getAlphaProgress", at = @At(value = "STORE", ordinal = 2), index = 2, require = 0, remap = false)
    private float mwloadout$adsTime(float aimTime) {
        double speed = MWAttributes.ads(net.minecraft.client.Minecraft.getInstance().player);
        return speed == 1.0 ? aimTime : (float) (aimTime / speed);
    }
}
