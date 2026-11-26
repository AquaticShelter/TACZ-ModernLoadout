package dev.l41nx.mwloadout.server;

import com.tacz.guns.api.item.attachment.AttachmentType;
import dev.l41nx.mwloadout.MWLoadoutMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One build, TACZ 1.1.6 .. 1.2.0. Everything that only exists in newer TACZ goes through here by reflection: a missing class or method
 * simply means "feature unavailable" (the UI hides it, builds skip it), and a feature that throws at runtime switches ITSELF off for the
 * rest of the session with one log line, so a conflict with another mod (or a TACZ build we have not seen) costs that feature, never the game.
 */
public final class TaczCompat {

    /** Features that depend on the TACZ version. Each one can be switched off at runtime after a failure. */
    public enum Feature {
        /** TACZ 1.2.0 slot adapters (the optic "risers"): SlotAdapterHelper. */
        RISER
    }

    private static final EnumMap<Feature, Boolean> OFF = new EnumMap<>(Feature.class);
    private static Method setAdapter, getAdapter, removeAdapter, available;
    private static boolean resolved;

    private TaczCompat() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> helper = Class.forName("com.tacz.guns.util.SlotAdapterHelper");
            setAdapter = helper.getMethod("setSlotAdapter", ItemStack.class, AttachmentType.class, ResourceLocation.class);
            getAdapter = helper.getMethod("getSlotAdapter", ItemStack.class, AttachmentType.class);
            removeAdapter = helper.getMethod("removeSlotAdapter", ItemStack.class, AttachmentType.class);
            available = helper.getMethod("getAvailableSlotAdapters", ItemStack.class, AttachmentType.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            setAdapter = null; // TACZ before 1.2.0: no risers, nothing to hide or patch
        }
    }

    public static boolean enabled(Feature feature) {
        if (OFF.getOrDefault(feature, false)) {
            return false;
        }
        resolve();
        return feature != Feature.RISER || setAdapter != null;
    }

    /** Switches a feature off for this session (called when it threw). Logged once. */
    public static void disable(Feature feature, Throwable cause) {
        if (!OFF.getOrDefault(feature, false)) {
            OFF.put(feature, true);
            MWLoadoutMod.LOGGER.warn("[MWLoadout] TACZ compat: {} disabled for this session ({})", feature, String.valueOf(cause));
        }
    }

    /** Adapter ids (risers) this gun accepts on this slot. Empty on old TACZ, for a slot without adapters, or after a failure. */
    public static List<ResourceLocation> availableAdapters(ItemStack gun, AttachmentType type) {
        List<ResourceLocation> ids = new ArrayList<>();
        if (!enabled(Feature.RISER) || gun.isEmpty()) {
            return ids;
        }
        try {
            Object list = available.invoke(null, gun, type);
            if (list instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map.Entry<?, ?> e && e.getKey() instanceof ResourceLocation id) {
                        ids.add(id);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(Feature.RISER, e);
            ids.clear();
        }
        return ids;
    }

    /** True when {@code id} is an adapter this gun accepts on this slot (a saved riser can go stale after a gun / pack change). */
    public static boolean adapterAvailable(ItemStack gun, AttachmentType type, String id) {
        for (ResourceLocation available : availableAdapters(gun, type)) {
            if (available.toString().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Applies the build's saved risers ({@link TaczStackFactory#RISER_KEY_PREFIX} entries) to a gun - after its attachments are installed. */
    public static void applyAdapters(ItemStack gun, Map<String, String> build) {
        if (build == null || gun.isEmpty() || !enabled(Feature.RISER)) {
            return;
        }
        for (Map.Entry<String, String> entry : build.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(TaczStackFactory.RISER_KEY_PREFIX)) {
                continue;
            }
            try {
                AttachmentType type = AttachmentType.valueOf(key.substring(TaczStackFactory.RISER_KEY_PREFIX.length()));
                ResourceLocation id = ResourceLocation.tryParse(entry.getValue() == null ? "" : entry.getValue());
                if (id != null && adapterAvailable(gun, type, id.toString())) {
                    setAdapter.invoke(null, gun, type, id);
                } else {
                    removeAdapter.invoke(null, gun, type); // stale / unknown riser: leave the slot bare
                }
            } catch (IllegalArgumentException ignored) {
                // unknown slot name in a hand-edited build: skip
            } catch (ReflectiveOperationException | RuntimeException e) {
                disable(Feature.RISER, e);
                return;
            }
        }
    }

    /** The riser currently on a gun for a slot, or null. */
    public static ResourceLocation currentAdapter(ItemStack gun, AttachmentType type) {
        if (!enabled(Feature.RISER) || gun.isEmpty()) {
            return null;
        }
        try {
            return (ResourceLocation) getAdapter.invoke(null, gun, type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(Feature.RISER, e);
            return null;
        }
    }
}
