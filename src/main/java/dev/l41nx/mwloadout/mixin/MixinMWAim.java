package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.entity.shooter.LivingEntityAim;
import dev.l41nx.mwloadout.attribute.MWAttributes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** mw:ads_speed (server): the gun's ADS time as read (local 6), divided by the multiplier. A variable modifier, so it stacks with other addons. */
@Mixin(value = LivingEntityAim.class, remap = false)
public abstract class MixinMWAim {
    @Shadow(remap = false) @Final
    private LivingEntity shooter;

    @ModifyVariable(method = "tickAimingProgress", at = @At(value = "STORE", ordinal = 2), index = 6, require = 0, remap = false)
    private float mwloadout$adsTime(float aimTime) {
        double speed = MWAttributes.ads(shooter);
        return speed == 1.0 ? aimTime : (float) (aimTime / speed);
    }
}
