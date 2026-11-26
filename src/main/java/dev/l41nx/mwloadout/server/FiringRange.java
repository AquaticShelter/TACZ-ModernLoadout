package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FIRING RANGE: a void dimension (data/mwloadout/dimension/firing_range.json) holding one prebuilt, unbreakable structure
 * (data/mwloadout/structures/firing_range.nbt, converted from range.schem by tools/schem_to_structure.py).
 *
 * Server-authoritative. The client only sends C2SRangeToggle from the gunsmith; everything below decides.
 *   enter  - refused while in combat (damage taken OR dealt), while a swap-gate (use lock / cooldown / ...) is active,
 *            or inside the re-entry cooldown; otherwise the exact position is saved and the player lands in the lobby.
 *   exit   - back to the exact saved position (dimension, x/y/z, yaw/pitch); falls back to the overworld spawn.
 *   plots  - every player gets their OWN copy of the range (a plot): copies sit PLOT_SPACING blocks apart in the void, far past any
 *            render / tracking distance, so nobody shares a lane or sees anyone else. A player keeps the same plot for good (saved),
 *            each plot's structure is placed the first time its owner enters.
 *   inside - invulnerable (no PvP, no self-damage), structure cannot be broken / built on, loadout rules never restrict
 *            (EditRuleEngine.check), and gear use inside is not tracked, so nothing carries over to the outside.
 */
@Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID)
public final class FiringRange {

    public static final ResourceLocation DIM_ID = new ResourceLocation(MWLoadoutMod.MOD_ID, "firing_range");
    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, DIM_ID);
    private static final ResourceLocation STRUCTURE_ID = new ResourceLocation(MWLoadoutMod.MOD_ID, "firing_range");

    /** Structure-local cell the player stands in (lobby, facing the range, +Z = south = yaw 0). */
    private static final int SPAWN_LX = 17, SPAWN_LY = 7, SPAWN_LZ = 12;
    /** World Y of the structure's floor layer (local y = 6). */
    private static final int FLOOR_Y = 64;
    /** Structure min corner. Chosen so the spawn cell lands on (0, FLOOR_Y + 1, 0). */
    private static final BlockPos ORIGIN = new BlockPos(-SPAWN_LX, FLOOR_Y - (SPAWN_LY - 1), -SPAWN_LZ);
    private static final double SPAWN_X = 0.5, SPAWN_Y = FLOOR_Y + 1, SPAWN_Z = 0.5;
    /** Distance between two players' copies of the range (blocks), and plots per row. */
    private static final int PLOT_SPACING = 1024, PLOTS_PER_ROW = 64;

    private FiringRange() {
    }

    public static boolean isInRange(Entity entity) {
        return entity != null && entity.level().dimension() == DIM;
    }

    /** Only an OP in creative may edit the structure. */
    private static boolean mayEdit(net.minecraft.world.entity.player.Player player) {
        return player != null && player.isCreative() && player.hasPermissions(2);
    }

    // ------------------------------------------------------------------ persistent state

    private record Return(String dim, double x, double y, double z, float yRot, float xRot) {
    }

    private static final class RangeData extends SavedData {
        static final String NAME = "mwloadout_range";
        final Map<UUID, Return> returns = new HashMap<>();
        final Map<UUID, Long> lastExit = new HashMap<>();
        final Map<UUID, Integer> plots = new HashMap<>();
        final java.util.Set<Integer> placedPlots = new java.util.HashSet<>();
        int nextPlot;
        boolean placed; // plot 0, written by versions before per-player plots

        static RangeData load(CompoundTag tag) {
            RangeData data = new RangeData();
            data.placed = tag.getBoolean("placed");
            if (data.placed) {
                data.placedPlots.add(0);
            }
            for (int i : tag.getIntArray("placedPlots")) {
                data.placedPlots.add(i);
            }
            data.nextPlot = tag.getInt("nextPlot");
            ListTag plotList = tag.getList("plots", Tag.TAG_COMPOUND);
            for (int i = 0; i < plotList.size(); i++) {
                CompoundTag e = plotList.getCompound(i);
                try {
                    int plot = e.getInt("p");
                    data.plots.put(UUID.fromString(e.getString("id")), plot);
                    data.nextPlot = Math.max(data.nextPlot, plot + 1);
                } catch (IllegalArgumentException ignored) {
                    // corrupt uuid - drop the entry
                }
            }
            ListTag list = tag.getList("returns", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                try {
                    data.returns.put(UUID.fromString(e.getString("id")), new Return(e.getString("dim"),
                            e.getDouble("x"), e.getDouble("y"), e.getDouble("z"), e.getFloat("yRot"), e.getFloat("xRot")));
                } catch (IllegalArgumentException ignored) {
                    // corrupt uuid - drop the entry
                }
            }
            ListTag exits = tag.getList("lastExit", Tag.TAG_COMPOUND);
            for (int i = 0; i < exits.size(); i++) {
                CompoundTag e = exits.getCompound(i);
                try {
                    data.lastExit.put(UUID.fromString(e.getString("id")), e.getLong("t"));
                } catch (IllegalArgumentException ignored) {
                    // corrupt uuid - drop the entry
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putBoolean("placed", placed);
            ListTag list = new ListTag();
            returns.forEach((id, r) -> {
                CompoundTag e = new CompoundTag();
                e.putString("id", id.toString());
                e.putString("dim", r.dim());
                e.putDouble("x", r.x());
                e.putDouble("y", r.y());
                e.putDouble("z", r.z());
                e.putFloat("yRot", r.yRot());
                e.putFloat("xRot", r.xRot());
                list.add(e);
            });
            tag.put("returns", list);
            ListTag exits = new ListTag();
            lastExit.forEach((id, t) -> {
                CompoundTag e = new CompoundTag();
                e.putString("id", id.toString());
                e.putLong("t", t);
                exits.add(e);
            });
            tag.put("lastExit", exits);
            tag.putIntArray("placedPlots", placedPlots.stream().mapToInt(Integer::intValue).toArray());
            tag.putInt("nextPlot", nextPlot);
            ListTag plotList = new ListTag();
            plots.forEach((id, plot) -> {
                CompoundTag e = new CompoundTag();
                e.putString("id", id.toString());
                e.putInt("p", plot);
                plotList.add(e);
            });
            tag.put("plots", plotList);
            return tag;
        }
    }

    /** World offset of plot i (plot 0 = the original single range, so existing worlds keep their placed copy). */
    private static BlockPos plotOffset(int plot) {
        return new BlockPos((plot % PLOTS_PER_ROW) * PLOT_SPACING, 0, (plot / PLOTS_PER_ROW) * PLOT_SPACING);
    }

    /** The plot whose area holds this position. */
    private static BlockPos plotOffsetAt(double x, double z) {
        int px = Math.max(0, (int) Math.round(x / PLOT_SPACING));
        int pz = Math.max(0, (int) Math.round(z / PLOT_SPACING));
        return new BlockPos(px * PLOT_SPACING, 0, pz * PLOT_SPACING);
    }

    private static int plotOf(RangeData data, UUID player) {
        Integer plot = data.plots.get(player);
        if (plot == null) {
            plot = data.nextPlot++;
            data.plots.put(player, plot);
            data.setDirty();
        }
        return plot;
    }

    private static RangeData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RangeData::load, RangeData::new, RangeData.NAME);
    }

    // ------------------------------------------------------------------ enter / exit

    private static void toast(ServerPlayer player, String key, String argument) {
        Net.sendToPlayer(player, new Net.S2CToast(key, argument));
    }

    /** Gunsmith button: leave if inside, otherwise try to enter. */
    public static void toggle(ServerPlayer player) {
        if (isInRange(player)) {
            exit(player);
        } else {
            enter(player);
        }
    }

    private static void enter(ServerPlayer player) {
        Rules rules = LoadoutRegistry.get().rules();
        MinecraftServer server = player.server;
        if (!rules.firingRangeEnabled) {
            toast(player, "message.mwloadout.range_disabled", "");
            return;
        }
        ServerLevel range = server.getLevel(DIM);
        if (range == null) {
            MWLoadoutMod.LOGGER.error("Firing range dimension {} is not loaded - is data/mwloadout/dimension/firing_range.json present?", DIM_ID);
            toast(player, "message.mwloadout.range_missing", "");
            return;
        }
        if (!player.isAlive()) {
            return;
        }
        EditRuleEngine.Verdict verdict = EditRuleEngine.rangeEntryVerdict(player);
        if (!verdict.allowed()) {
            String key = switch (verdict.reasonKey()) {
                case "cooldown" -> "message.mwloadout.range_cooldown";
                case "use_lock" -> verdict.secondsRemaining() > 0
                        ? "message.mwloadout.range_use_lock" : "message.mwloadout.range_use_lock_respawn";
                case "hard", "zone_required" -> "message.mwloadout.range_swap_rules";
                default -> "message.mwloadout.range_combat";
            };
            toast(player, key, String.valueOf(verdict.secondsRemaining()));
            return;
        }
        RangeData data = data(server);
        long now = server.overworld().getGameTime();
        Long left = data.lastExit.get(player.getUUID());
        if (left != null && rules.firingRangeReentrySeconds > 0 && !isExempt(player, rules)) {
            long wait = rules.firingRangeReentrySeconds * 20L - (now - left);
            if (wait > 0) {
                toast(player, "message.mwloadout.range_reentry", String.valueOf((int) Math.ceil(wait / 20.0)));
                return;
            }
        }
        int plot = plotOf(data, player.getUUID());
        if (!ensurePlaced(range, data, plot)) {
            toast(player, "message.mwloadout.range_missing", "");
            return;
        }
        BlockPos off = plotOffset(plot);
        data.returns.put(player.getUUID(), new Return(player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        data.setDirty();
        sendTo(player, range, SPAWN_X + off.getX(), SPAWN_Y, SPAWN_Z + off.getZ(), 0.0F, 0.0F);
        toast(player, "message.mwloadout.range_entered", "");
    }

    private static boolean isExempt(ServerPlayer player, Rules rules) {
        boolean privileged = player.isCreative() || player.hasPermissions(2);
        return privileged && !rules.restrictOpAndCreative;
    }

    private static void exit(ServerPlayer player) {
        MinecraftServer server = player.server;
        RangeData data = data(server);
        Return back = data.returns.remove(player.getUUID());
        data.lastExit.put(player.getUUID(), server.overworld().getGameTime());
        data.setDirty();
        ServerLevel target = null;
        if (back != null) {
            ResourceLocation id = ResourceLocation.tryParse(back.dim());
            if (id != null) {
                target = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            }
        }
        if (back == null || target == null || target.dimension() == DIM) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            sendTo(player, overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), 0.0F);
        } else {
            sendTo(player, target, back.x(), back.y(), back.z(), back.yRot(), back.xRot());
        }
        toast(player, "message.mwloadout.range_left", "");
    }

    private static void sendTo(ServerPlayer player, ServerLevel level, double x, double y, double z, float yRot, float xRot) {
        player.stopRiding();
        player.teleportTo(level, x, y, z, yRot, xRot);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0.0F;
    }

    /** Places a plot's structure once (persisted). Void dimension, so only the structure's own blocks are written. */
    private static boolean ensurePlaced(ServerLevel range, RangeData data, int plot) {
        if (data.placedPlots.contains(plot)) {
            return true;
        }
        Optional<StructureTemplate> template = range.getStructureManager().get(STRUCTURE_ID);
        if (template.isEmpty()) {
            MWLoadoutMod.LOGGER.error("Firing range structure {} not found (data/mwloadout/structures/firing_range.nbt)", STRUCTURE_ID);
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        BlockPos at = ORIGIN.offset(plotOffset(plot));
        boolean ok = template.get().placeInWorld(range, at, at, settings, range.getRandom(), 2);
        if (ok) {
            data.placedPlots.add(plot);
            if (plot == 0) {
                data.placed = true;
            }
            data.setDirty();
            MWLoadoutMod.LOGGER.info("Firing range plot {} placed at {}", plot, at);
        }
        return ok;
    }

    // ------------------------------------------------------------------ hooks called by ServerEvents

    /** Every 10 ticks per player: catches anyone who ended up under the structure (void). */
    public static void tick(ServerPlayer player) {
        if (isInRange(player) && player.getY() < ORIGIN.getY() - 4) {
            Integer plot = data(player.server).plots.get(player.getUUID());
            BlockPos off = plot != null ? plotOffset(plot) : plotOffsetAt(player.getX(), player.getZ());
            sendTo(player, player.serverLevel(), SPAWN_X + off.getX(), SPAWN_Y, SPAWN_Z + off.getZ(), 0.0F, 0.0F);
        }
    }

    /** A death inside the range respawns the player the vanilla way - the saved return point no longer applies. */
    public static void onRespawn(ServerPlayer player) {
        RangeData data = data(player.server);
        if (data.returns.remove(player.getUUID()) != null) {
            data.setDirty();
        }
    }

    // ------------------------------------------------------------------ protection (range dimension only)

    private static boolean inRange(Object level) {
        return level instanceof Level l && l.dimension() == DIM;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isInRange(player)
                && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true); // no PvP, no self-damage from your own launcher - void / /kill still work
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (inRange(event.getLevel()) && !mayEdit(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (inRange(event.getLevel()) && !(event.getEntity() instanceof net.minecraft.world.entity.player.Player p && mayEdit(p))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFluid(BlockEvent.FluidPlaceBlockEvent event) {
        if (inRange(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBucket(FillBucketEvent event) {
        if (inRange(event.getLevel()) && !mayEdit(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (inRange(event.getLevel())) {
            event.getAffectedBlocks().clear(); // entities still take the blast (they are invulnerable anyway), blocks never do
        }
    }
}
