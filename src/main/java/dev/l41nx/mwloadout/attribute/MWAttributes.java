package dev.l41nx.mwloadout.attribute;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * MW attributes, built in - no attribute library needed (ApothicAttributes / tacz_attributes / gunsmithlib are no longer required).
 * All are speed multipliers on the player, base 1.0 ("/attribute @s mw:reload_speed base set 1"), synced to the client.
 * <ul>
 *   <li>{@code mw:reload_speed} - TaCZ reloads (server timing + the reload animation).</li>
 *   <li>{@code mw:draw_speed} - drawing and putting away: TaCZ guns AND every LRTactical item (melee, throwables, consumables), timing + animation.</li>
 *   <li>{@code mw:ads_speed} - aim down sights (server and client progress).</li>
 * </ul>
 * Fast Hands ships as reload_speed +0.35 and draw_speed +0.35 (1.35x). Applied by MixinMWReload / MixinMWDraw / MixinMWLrDraw /
 * MixinMWAim / MixinMWAimClient / MixinMWAnimSpeed.
 */
public final class MWAttributes {
    public static final String NAMESPACE = "mw";
    public static final DeferredRegister<Attribute> REGISTER = DeferredRegister.create(ForgeRegistries.ATTRIBUTES, NAMESPACE);

    public static final RegistryObject<Attribute> RELOAD_SPEED = REGISTER.register("reload_speed",
            () -> new RangedAttribute("attribute.mw.reload_speed", 1.0, 0.1, 10.0).setSyncable(true));
    public static final RegistryObject<Attribute> DRAW_SPEED = REGISTER.register("draw_speed",
            () -> new RangedAttribute("attribute.mw.draw_speed", 1.0, 0.1, 10.0).setSyncable(true));
    public static final RegistryObject<Attribute> ADS_SPEED = REGISTER.register("ads_speed",
            () -> new RangedAttribute("attribute.mw.ads_speed", 1.0, 0.1, 10.0).setSyncable(true));

    private MWAttributes() {
    }

    public static void init(IEventBus modBus) {
        REGISTER.register(modBus);
        modBus.addListener(MWAttributes::onAttributes);
    }

    private static void onAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, RELOAD_SPEED.get());
        event.add(EntityType.PLAYER, DRAW_SPEED.get());
        event.add(EntityType.PLAYER, ADS_SPEED.get());
    }

    public static double reload(LivingEntity entity) {
        return value(entity, RELOAD_SPEED);
    }

    public static double draw(LivingEntity entity) {
        return value(entity, DRAW_SPEED);
    }

    public static double ads(LivingEntity entity) {
        return value(entity, ADS_SPEED);
    }

    /** The multiplier, 1.0 when the entity has no such attribute; clamped to a sane 0.1..10. */
    public static double value(LivingEntity entity, RegistryObject<Attribute> attribute) {
        try {
            if (entity == null || !attribute.isPresent()) {
                return 1.0;
            }
            AttributeInstance instance = entity.getAttribute(attribute.get());
            if (instance == null) {
                return 1.0;
            }
            double v = instance.getValue();
            if (!Double.isFinite(v)) {
                return 1.0;
            }
            return Math.max(0.1, Math.min(10.0, v));
        } catch (Throwable t) {
            return 1.0;
        }
    }
}
