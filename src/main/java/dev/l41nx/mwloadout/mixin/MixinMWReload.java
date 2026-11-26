package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.entity.shooter.LivingEntityReload;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import dev.l41nx.mwloadout.attribute.MWAttributes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** mw:reload_speed - the reload clock runs faster: for the duration of the tick the reload start is moved back by the extra time. */
@Mixin(value = LivingEntityReload.class, remap = false)
public abstract class MixinMWReload {
    @Shadow(remap = false) @Final
    private LivingEntity shooter;
    @Shadow(remap = false) @Final
    private ShooterDataHolder data;
    @Unique
    private long mwloadout$realReloadStart = -1L;

    @Inject(method = "tickReloadState", at = @At("HEAD"), remap = false, require = 0)
    private void mwloadout$speedUp(CallbackInfoReturnable<ReloadState> cir) {
        mwloadout$realReloadStart = -1L;
        if (data == null || data.reloadTimestamp == -1L) {
            return;
        }
        double speed = MWAttributes.reload(shooter);
        if (speed == 1.0) {
            return;
        }
        mwloadout$realReloadStart = data.reloadTimestamp;
        long now = System.currentTimeMillis();
        data.reloadTimestamp = now - (long) ((now - data.reloadTimestamp) * speed);
    }

    @Inject(method = "tickReloadState", at = @At("RETURN"), remap = false, require = 0)
    private void mwloadout$restore(CallbackInfoReturnable<ReloadState> cir) {
        if (mwloadout$realReloadStart == -1L) {
            return;
        }
        if (data.reloadTimestamp != -1L) {
            data.reloadTimestamp = mwloadout$realReloadStart; // a finished / cancelled reload sets -1: keep that
        }
        mwloadout$realReloadStart = -1L;
    }
}
