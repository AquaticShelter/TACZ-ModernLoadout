package dev.l41nx.mwloadout.mixin;

import com.tacz.guns.api.item.attachment.AttachmentType;
import dev.l41nx.mwloadout.MWSlots;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Appends the AMMO_MOD constant to TACZ's AttachmentType enum at the very end of the enum's own static initialiser - before any other class can
 * see {@code values()} - so every switch map / EnumMap / Gson adapter TACZ builds later already knows it. Same technique as TACZ-Arcana's
 * MixinAttachmentType.addVariant.
 */
@Mixin(value = AttachmentType.class, remap = false)
public abstract class MixinAttachmentType {
    @Shadow
    @Final
    @Mutable
    private static AttachmentType[] $VALUES;

    @Invoker("<init>")
    public static AttachmentType mwloadout$construct(String name, int ordinal) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false, require = 0)
    private static void mwloadout$addAmmoSlot(CallbackInfo ci) {
        try {
            for (AttachmentType existing : $VALUES) {
                if ("AMMO_MOD".equals(existing.name())) {
                    MWSlots.AMMO = existing;
                    return;
                }
            }
            List<AttachmentType> all = new ArrayList<>(Arrays.asList($VALUES));
            AttachmentType added = mwloadout$construct("AMMO_MOD", all.size());
            all.add(added);
            $VALUES = all.toArray(new AttachmentType[0]);
            MWSlots.AMMO = added;
        } catch (Throwable t) {
            MWSlots.fail(t);
        }
    }
}
