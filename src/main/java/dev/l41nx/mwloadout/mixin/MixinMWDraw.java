package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.shooter.LivingEntityDrawGun;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import dev.l41nx.mwloadout.attribute.MWAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/** mw:draw_speed for TaCZ guns: the put-away wait and the draw time both shrink by the multiplier. */
@Mixin(value = LivingEntityDrawGun.class, remap = false)
public abstract class MixinMWDraw {
    @Shadow(remap = false) @Final
    private LivingEntity shooter;
    @Shadow(remap = false) @Final
    private ShooterDataHolder data;

    @Inject(method = "draw", at = @At("TAIL"), remap = false, require = 0)
    private void mwloadout$fasterPutAway(Supplier<ItemStack> gunItemSupplier, CallbackInfo ci) {
        double speed = MWAttributes.draw(shooter);
        if (speed == 1.0 || data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long ahead = data.drawTimestamp - now;
        if (ahead > 0L) {
            data.drawTimestamp = now + (long) (ahead / speed);
        }
    }

    @Inject(method = "getDrawCoolDown", at = @At("RETURN"), remap = false, cancellable = true, require = 0)
    private void mwloadout$fasterDraw(CallbackInfoReturnable<Long> cir) {
        long coolDown = cir.getReturnValueJ();
        if (coolDown <= 0L || data == null || data.currentGunItem == null) {
            return;
        }
        double speed = MWAttributes.draw(shooter);
        if (speed == 1.0) {
            return;
        }
        ItemStack gun = data.currentGunItem.get();
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            return;
        }
        TimelessAPI.getCommonGunIndex(iGun.getGunId(gun)).ifPresent(index -> {
            long drawMs = (long) (index.getGunData().getDrawTime() * 1000.0F);
            long cut = (long) (drawMs * (1.0 - 1.0 / speed));
            cir.setReturnValue(Math.max(0L, coolDown - cut));
        });
    }
}
