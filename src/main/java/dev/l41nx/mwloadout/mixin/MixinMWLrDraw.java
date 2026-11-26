package dev.l41nx.mwloadout.mixin;

import dev.l41nx.mwloadout.attribute.MWAttributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * mw:draw_speed for every LRTactical item (melee, throwables, consumables): CombatProperties.reset runs on both sides when a new LRT item comes
 * out and sets the draw (+ put-away of the previous LRT item) wait in ticks; it is shortened by the multiplier. Optional mod: @Pseudo, no
 * compile dependency, nothing happens without LRTactical.
 */
@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.capability.CombatProperties", remap = false)
public abstract class MixinMWLrDraw {
    @Shadow(remap = false)
    private Player entity;
    @Shadow(remap = false)
    private int coolDownTick;
    @Shadow(remap = false)
    private int lastMaxTick;
    @Shadow(remap = false)
    private int drawingTick;

    @Inject(method = "reset", at = @At("TAIL"), remap = false, require = 0)
    private void mwloadout$fasterDraw(CallbackInfo ci) {
        double speed = MWAttributes.draw(entity);
        if (speed == 1.0 || drawingTick <= 0) {
            return;
        }
        int scaled = Math.max(0, (int) Math.round(drawingTick / speed));
        coolDownTick = scaled;
        lastMaxTick = scaled;
        drawingTick = scaled;
        if (entity != null && entity.level().isClientSide()) {
            dev.l41nx.mwloadout.attribute.MWAnimWindow.lrDraw(entity, scaled);
        }
    }
}
