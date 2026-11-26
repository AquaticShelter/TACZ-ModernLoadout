package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.server.PerkEffects;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ninja perk: movement sounds made by a ninja play at a fraction of their volume.
 *
 *  Client-side, at the sound engine - the one place every footstep passes through: the owner's own steps are played locally (the server
 *  never sends them back), other players' steps arrive as sound packets, and Presence Footsteps (client-only) plays its own
 *  "presencefootsteps:pf_presence.*" sounds. A sound is a ninja's when it is a movement sound (see {@link #isMovementSound}) and the
 *  NEAREST player to where it plays is a ninja, recognised by the zero-value marker modifier the perk puts on LUCK.
 *
 *  Compatibility: the sound is never copied or resolved here. It is WRAPPED ({@link ScaledSoundInstance}) so whatever another mod
 *  already put in the event (Tactical Footsteps' adjusted instance, Presence Footsteps' uncapped instance, a sound-physics wrapper) keeps
 *  working underneath, and this handler runs LAST so it scales their result instead of being overwritten by it. */
@Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID, value = Dist.CLIENT)
public final class NinjaSounds {

    /** Matched against the full "namespace:path" id, lower-case. Built in, so older saved perk definitions keep working;
     *  the perk's own "quietSounds" list only ADDS to these. */
    private static final String[] BUILT_IN = {
            ".step", ".fall", "small_fall", "big_fall", "swim", "splash", "presencefootsteps", "pf_presence"
    };

    private static float multiplier = 1f;
    private static List<String> extraTokens = List.of();
    private static long configAt;

    private NinjaSounds() {
    }

    private static void refreshConfig() {
        long now = System.currentTimeMillis();
        if (now - configAt < 1000) {
            return;
        }
        configAt = now;
        multiplier = 1f;
        extraTokens = List.of();
        try {
            for (PerkDefinition perk : LoadoutRegistry.get().perkList()) {
                if (!perk.enabled) {
                    continue;
                }
                for (JsonObject effect : perk.effects) {
                    if ("special".equals(Json.str(effect, "type", "")) && "quiet_steps".equals(Json.str(effect, "key", ""))) {
                        multiplier = (float) Math.max(0.0, Math.min(1.0, Json.doubleValue(effect, "volumeMultiplier", 0.1)));
                        List<String> list = new ArrayList<>();
                        for (String token : Json.strList(effect, "quietSounds")) {
                            if (!token.isBlank()) {
                                list.add(token.toLowerCase(Locale.ROOT));
                            }
                        }
                        extraTokens = list;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/NinjaSounds:83", ignored);
        }
    }

    public static boolean isNinja(Player player) {
        AttributeInstance luck = player.getAttribute(Attributes.LUCK);
        return luck != null && luck.getModifier(PerkEffects.ninjaMarkerUuid()) != null;
    }

    static boolean isMovementSound(ResourceLocation id) {
        String full = (id.getNamespace() + ":" + id.getPath()).toLowerCase(Locale.ROOT);
        for (String token : BUILT_IN) {
            if (full.contains(token)) {
                return true;
            }
        }
        for (String token : extraTokens) {
            if (full.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** LOWEST: after every other mod has put its own instance in the event, so we scale the final one. Never throws - a sound
     *  tweak must not be able to crash the ticking player (it did, once: see the unresolved-sound note on ScaledSoundInstance). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlaySound(PlaySoundEvent event) {
        try {
            SoundInstance sound = event.getSound();
            Minecraft mc = Minecraft.getInstance();
            if (sound == null || mc.level == null || sound instanceof TickableSoundInstance || sound instanceof ScaledSoundInstance) {
                return;
            }
            if (sound.getSource() != SoundSource.PLAYERS && sound.getSource() != SoundSource.NEUTRAL
                    && sound.getSource() != SoundSource.BLOCKS && !sound.getLocation().getNamespace().startsWith("presencefootsteps")) {
                return;
            }
            refreshConfig();
            if (multiplier >= 1f || !isMovementSound(sound.getLocation())) {
                return;
            }
            // whose step is it? the nearest player to where the sound plays (same rule Tactical Footsteps uses: 1.5 blocks across)
            Player emitter = null;
            double best = 2.25;
            for (Player player : mc.level.players()) {
                double dx = player.getX() - sound.getX(), dz = player.getZ() - sound.getZ();
                double flat = dx * dx + dz * dz;
                if (flat < best && Math.abs(player.getY() - sound.getY()) < 2.5) {
                    best = flat;
                    emitter = player;
                }
            }
            if (emitter != null && isNinja(emitter)) {
                event.setSound(new ScaledSoundInstance(sound, multiplier));
            }
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/NinjaSounds:139", ignored);
        }
    }

    /** A SoundInstance that forwards everything to the one it wraps and only scales the volume. Deliberately NOT a copy: at
     *  PlaySoundEvent time the original is still unresolved (its Sound is null until the engine calls resolve()), so reading its volume
     *  here NPE'd - the crash. The volume is only read lazily, after the engine has resolved the wrapped sound through us. */
    static final class ScaledSoundInstance implements SoundInstance {
        private final SoundInstance original;
        private final float factor;

        ScaledSoundInstance(SoundInstance original, float factor) {
            this.original = original;
            this.factor = factor;
        }

        @Override public ResourceLocation getLocation() { return original.getLocation(); }
        @Override public WeighedSoundEvents resolve(SoundManager manager) { return original.resolve(manager); }
        @Override public Sound getSound() { return original.getSound(); }
        @Override public SoundSource getSource() { return original.getSource(); }
        @Override public boolean isLooping() { return original.isLooping(); }
        @Override public boolean isRelative() { return original.isRelative(); }
        @Override public int getDelay() { return original.getDelay(); }
        @Override public float getVolume() { return original.getVolume() * factor; }
        @Override public float getPitch() { return original.getPitch(); }
        @Override public double getX() { return original.getX(); }
        @Override public double getY() { return original.getY(); }
        @Override public double getZ() { return original.getZ(); }
        @Override public Attenuation getAttenuation() { return original.getAttenuation(); }
        @Override public boolean canStartSilent() { return original.canStartSilent(); }
        @Override public boolean canPlaySound() { return original.canPlaySound(); }
    }
}
