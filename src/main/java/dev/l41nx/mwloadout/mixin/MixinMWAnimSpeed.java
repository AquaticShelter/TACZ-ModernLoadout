package dev.l41nx.mwloadout.mixin;

import dev.l41nx.mwloadout.attribute.MWAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * First-person animations keep pace with the faster timings: TaCZ's animation runner (guns AND LRTactical items, which use the same
 * state machines) plays "draw" / "put_away" clips at mw:draw_speed and "reload" clips at mw:reload_speed of the local player - but only
 * while the held item is really being drawn / reloaded (MWAnimWindow), never other mods' overlay clips that happen to be named "draw".
 */
@Mixin(targets = "com.tacz.guns.api.client.animation.ObjectAnimationRunner", remap = false)
public abstract class MixinMWAnimSpeed {
    @Shadow(remap = false)
    public abstract com.tacz.guns.api.client.animation.ObjectAnimation getAnimation();

    @ModifyVariable(method = "updateProgress", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
    private long mwloadout$speed(long alphaProgress) {
        try {
            com.tacz.guns.api.client.animation.ObjectAnimation animation = getAnimation();
            if (animation == null || animation.name == null) {
                return alphaProgress;
            }
            String name = animation.name;
            var player = net.minecraft.client.Minecraft.getInstance().player;
            double speed;
            if (name.startsWith("draw") || name.startsWith("put_away")) {
                if (!dev.l41nx.mwloadout.attribute.MWAnimWindow.drawing(player)) {
                    return alphaProgress; // someone else's "draw" clip on top of the held item (quick throw, stim...): their own timing
                }
                speed = MWAttributes.draw(player);
            } else if (name.startsWith("reload")) {
                if (!dev.l41nx.mwloadout.attribute.MWAnimWindow.reloading(player)) {
                    return alphaProgress;
                }
                speed = MWAttributes.reload(player);
            } else {
                return alphaProgress;
            }
            return speed == 1.0 ? alphaProgress : (long) (alphaProgress * speed);
        } catch (Throwable t) {
            return alphaProgress;
        }
    }
}
