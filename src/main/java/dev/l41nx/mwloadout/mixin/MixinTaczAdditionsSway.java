package dev.l41nx.mwloadout.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TaCZ Additions compat: with the Tracker perk the scope never sways - the gun stays still while aiming and there is no need to hold your breath.
 * The local player's own perk marks come from the server (Net.S2CPerkMarks is sent to the player too). Optional mod: @Pseudo, no compile
 * dependency, nothing happens without TaCZ Additions.
 */
@Pseudo
@Mixin(targets = "com.raiiiden.taczadditions.client.ScopeSwayHandler", remap = false)
public abstract class MixinTaczAdditionsSway {
    @Inject(method = "onComputeCameraAngles", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void mwloadout$trackerSteady(CallbackInfo ci) {
        try {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null && dev.l41nx.mwloadout.client.HitPerkOverlay.hasMark(player.getUUID(), "tracker")) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // never break the camera
        }
    }
}
