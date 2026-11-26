package dev.l41nx.mwloadout.network;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.server.EditRuleEngine;
import dev.l41nx.mwloadout.server.KillstreakManager;
import dev.l41nx.mwloadout.server.LoadoutApplier;
import dev.l41nx.mwloadout.server.ProfileManager;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.function.Supplier;

/**
 * All networking in one place, CodMatchmaking's SimpleChannel/registerMessage pattern. Payloads
 * ride as JSON strings — these are low-frequency GUI/config packets (open menu, save class,
 * admin edit), not per-tick gameplay traffic, so schema flexibility beats byte-level compactness:
 * adding a field to any data class never breaks the wire format.
 *
 * Server-side handlers re-validate EVERYTHING (edit window, permissions, preset contents) — the
 * client is a renderer with opinions, never an authority.
 */
public final class Net {

    private static final String PROTOCOL = "8"; // 6: C2SImportCloud is chunked (a 1.20.1 client->server payload may not exceed 32767 bytes)
    private static int packetId;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MWLoadoutMod.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Net() {
    }

    public static void init() {
        CHANNEL.registerMessage(packetId++, C2SOpenLoadout.class, C2SOpenLoadout::encode, C2SOpenLoadout::decode, C2SOpenLoadout::handle);
        CHANNEL.registerMessage(packetId++, C2SSaveLoadout.class, C2SSaveLoadout::encode, C2SSaveLoadout::decode, C2SSaveLoadout::handle);
        CHANNEL.registerMessage(packetId++, C2SSelectLoadout.class, C2SSelectLoadout::encode, C2SSelectLoadout::decode, C2SSelectLoadout::handle);
        CHANNEL.registerMessage(packetId++, C2SAdminUpdate.class, C2SAdminUpdate::encode, C2SAdminUpdate::decode, C2SAdminUpdate::handle);
        CHANNEL.registerMessage(packetId++, C2SSaveGunBuild.class, C2SSaveGunBuild::encode, C2SSaveGunBuild::decode, C2SSaveGunBuild::handle);
        CHANNEL.registerMessage(packetId++, C2SClaimStreak.class, C2SClaimStreak::encode, C2SClaimStreak::decode, C2SClaimStreak::handle);
        CHANNEL.registerMessage(packetId++, C2SSetSpecialist.class, C2SSetSpecialist::encode, C2SSetSpecialist::decode, C2SSetSpecialist::handle);
        CHANNEL.registerMessage(packetId++, S2CSyncRegistry.class, S2CSyncRegistry::encode, S2CSyncRegistry::decode, S2CSyncRegistry::handle);
        CHANNEL.registerMessage(packetId++, S2CSyncProfile.class, S2CSyncProfile::encode, S2CSyncProfile::decode, S2CSyncProfile::handle);
        CHANNEL.registerMessage(packetId++, S2COpenLoadout.class, S2COpenLoadout::encode, S2COpenLoadout::decode, S2COpenLoadout::handle);
        CHANNEL.registerMessage(packetId++, S2COpenAdmin.class, S2COpenAdmin::encode, S2COpenAdmin::decode, S2COpenAdmin::handle);
        CHANNEL.registerMessage(packetId++, S2CToast.class, S2CToast::encode, S2CToast::decode, S2CToast::handle);
        CHANNEL.registerMessage(packetId++, S2CStreakUse.class, S2CStreakUse::encode, S2CStreakUse::decode, S2CStreakUse::handle);
        CHANNEL.registerMessage(packetId++, S2CStreakProgress.class, S2CStreakProgress::encode, S2CStreakProgress::decode, S2CStreakProgress::handle);
        CHANNEL.registerMessage(packetId++, S2CHighAlert.class, S2CHighAlert::encode, S2CHighAlert::decode, S2CHighAlert::handle);
        CHANNEL.registerMessage(packetId++, C2SPresetOp.class, C2SPresetOp::encode, C2SPresetOp::decode, C2SPresetOp::handle);
        CHANNEL.registerMessage(packetId++, C2SSaveKillCard.class, C2SSaveKillCard::encode, C2SSaveKillCard::decode, C2SSaveKillCard::handle);
        CHANNEL.registerMessage(packetId++, C2SToggleFavoriteCard.class, C2SToggleFavoriteCard::encode, C2SToggleFavoriteCard::decode, C2SToggleFavoriteCard::handle);
        CHANNEL.registerMessage(packetId++, C2SRequestStats.class, C2SRequestStats::encode, C2SRequestStats::decode, C2SRequestStats::handle);
        CHANNEL.registerMessage(packetId++, C2SResetStats.class, C2SResetStats::encode, C2SResetStats::decode, C2SResetStats::handle);
        CHANNEL.registerMessage(packetId++, S2CSyncStats.class, S2CSyncStats::encode, S2CSyncStats::decode, S2CSyncStats::handle);
        CHANNEL.registerMessage(packetId++, S2CPerkMarks.class, S2CPerkMarks::encode, S2CPerkMarks::decode, S2CPerkMarks::handle);
        CHANNEL.registerMessage(packetId++, C2SImportCloud.class, C2SImportCloud::encode, C2SImportCloud::decode, C2SImportCloud::handle);
        CHANNEL.registerMessage(packetId++, C2SRangeToggle.class, C2SRangeToggle::encode, C2SRangeToggle::decode, C2SRangeToggle::handle);
    }

    // ------------------------------------------------------------------ perk marks (hitmarker cross-compat)
    /** Perks other players are shown carrying under the hitmarker. Bit i of the mask = PERK_MARKS[i]. */
    public static final String[] PERK_MARKS = {"quick_fix", "battle_hardened", "shield_up", "tracker", "fragiles", "ghost", "shrapnel", "double_time"};
    private static final java.util.Map<java.util.UUID, Integer> LAST_PERK_MASK = new java.util.concurrent.ConcurrentHashMap<>();

    public static int perkMask(ServerPlayer player) {
        int mask = 0;
        for (int i = 0; i < PERK_MARKS.length; i++) {
            if (dev.l41nx.mwloadout.server.PerkEffects.playerHasPerkId(player, PERK_MARKS[i])) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** Called once a second per player: tells everyone who can see this player (and the player) when the visible perks changed. */
    public static void tickPerkMarks(ServerPlayer player, boolean resend) {
        int mask = perkMask(player);
        Integer last = LAST_PERK_MASK.put(player.getUUID(), mask);
        // resend (every few seconds) even when unchanged: a watcher whose first packet was missed would otherwise never learn the marks
        if (last == null || last != mask || resend) {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), new S2CPerkMarks(player.getUUID(), mask));
        }
    }

    /** A player started to see {@code target}: hand over the current marks so the first hit already knows them. */
    public static void sendPerkMarksTo(ServerPlayer watcher, ServerPlayer target) {
        sendToPlayer(watcher, new S2CPerkMarks(target.getUUID(), perkMask(target)));
    }

    public static void forgetPerkMarks(java.util.UUID id) {
        LAST_PERK_MASK.remove(id);
    }

    public record S2CPerkMarks(java.util.UUID player, int mask) {
        public static void encode(S2CPerkMarks msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.player);
            buf.writeVarInt(msg.mask);
        }

        public static S2CPerkMarks decode(FriendlyByteBuf buf) {
            return new S2CPerkMarks(buf.readUUID(), buf.readVarInt());
        }

        public static void handle(S2CPerkMarks msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.l41nx.mwloadout.client.HitPerkOverlay.setMarks(msg.player(), msg.mask())));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** What each player's client currently holds (hash of the last registry JSON sent). The whole catalogue (hundreds of
     *  guns / attachments) used to be re-sent, re-parsed and every icon / HUD / name cache wiped on EVERY open of the
     *  loadout - that was the freeze. It is now sent only when it actually differs from what the client already has. */
    private static final java.util.Map<java.util.UUID, Integer> LAST_REGISTRY_SENT = new java.util.concurrent.ConcurrentHashMap<>();

    public static void syncRegistryTo(ServerPlayer player) {
        sendRegistry(player, false);
    }

    /** Same, but always sends (join, admin screen). */
    public static void syncRegistryForce(ServerPlayer player) {
        sendRegistry(player, true);
    }

    public static void forgetRegistryState(java.util.UUID id) {
        LAST_REGISTRY_SENT.remove(id);
    }

    private static void sendRegistry(ServerPlayer player, boolean force) {
        String json = LoadoutRegistry.get().toSyncJson(player.hasPermissions(2));
        int key = json.hashCode() * 31 + json.length();
        Integer previous = LAST_REGISTRY_SENT.get(player.getUUID());
        if (!force && previous != null && previous == key) {
            return; // client already has exactly this
        }
        LAST_REGISTRY_SENT.put(player.getUUID(), key);
        sendToPlayer(player, new S2CSyncRegistry(json));
    }

    public static void syncProfileTo(ServerPlayer player) {
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        if (dev.l41nx.mwloadout.server.PerkEffects.reconcilePresetPerks(profile)) {
            ProfileManager.get().save(player.getUUID()); // saved classes follow perks that moved columns
        }
        sendToPlayer(player, new S2CSyncProfile(Json.GSON.toJson(profile.toJson())));
    }

    // =============================================================== C2S

    /** Client asks to open the loadout screen; server answers with the current edit verdict. */
    /** Gunsmith FIRING RANGE button: enter the range, or leave it when already inside. The server decides everything. */
    public record C2SRangeToggle() {
        public static void encode(C2SRangeToggle msg, FriendlyByteBuf buf) { }
        public static C2SRangeToggle decode(FriendlyByteBuf buf) { return new C2SRangeToggle(); }
        public static void handle(C2SRangeToggle msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    dev.l41nx.mwloadout.server.FiringRange.toggle(player);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Claim the next queued killstreak reward into the inventory. */
    public record C2SClaimStreak() {
        public static void encode(C2SClaimStreak msg, FriendlyByteBuf buf) { }
        public static C2SClaimStreak decode(FriendlyByteBuf buf) { return new C2SClaimStreak(); }
        public static void handle(C2SClaimStreak msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                boolean claimed = dev.l41nx.mwloadout.server.KillstreakItems.claimNext(player);
                if (!claimed) {
                    sendToPlayer(player, new S2CToast("message.mwloadout.no_streak", ""));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Toggle Specialist mode and set its three chosen perks (validated server-side). */
    public record C2SSetSpecialist(boolean active, java.util.List<String> perks) {
        public static void encode(C2SSetSpecialist msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.active);
            buf.writeVarInt(msg.perks.size());
            msg.perks.forEach(p -> buf.writeUtf(p == null ? "" : p));
        }
        public static C2SSetSpecialist decode(FriendlyByteBuf buf) {
            boolean active = buf.readBoolean();
            int n = buf.readVarInt();
            java.util.List<String> perks = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                perks.add(buf.readUtf());
            }
            return new C2SSetSpecialist(active, perks);
        }
        public static void handle(C2SSetSpecialist msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                profile.specialistActive = msg.active;
                // Any change here (toggling active, or re-picking the 3 staged perks) restarts
                // streak/Specialist progress from zero — see resetSpecialistProgress doc for why
                // this is required, not just tidy: without it a leftover streak from Item mode (or
                // the previous picks) can cross several unlock thresholds on a single next kill.
                KillstreakManager.resetSpecialistProgress(player);
                // Validate: specialist perks must be in the pool AND not clash with equipped perks.
                var rules = LoadoutRegistry.get().rules();
                LoadoutPreset active = profile.activePreset();
                java.util.Set<String> equipped = active != null
                        ? new java.util.HashSet<>(active.perks.values()) : java.util.Set.of();
                java.util.List<String> clean = new java.util.ArrayList<>();
                for (String p : msg.perks) {
                    if (clean.size() >= 3) {
                        break; // Specialist has exactly 3 staged slots — ignore anything past that
                    }
                    var def = p == null ? null : LoadoutRegistry.get().perk(p);
                    int onlyColumn = def == null ? 0 : def.specialistColumn(); // a Specialist-only perk fits its own column and no other
                    if (p != null && !p.isEmpty() && LoadoutRegistry.get().specialistPool().contains(p)
                            && !equipped.contains(p) && !clean.contains(p)
                            && (onlyColumn == 0 || onlyColumn == clean.size() + 1)) {
                        clean.add(p);
                    }
                }
                profile.specialistPerks = clean;
                dev.l41nx.mwloadout.server.KillstreakManager.fillSpecialistPerks(player); // empty slots roll random pool perks
                ProfileManager.get().save(player.getUUID());
                syncProfileTo(player);
                dev.l41nx.mwloadout.server.KillstreakManager.syncProgress(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record C2SOpenLoadout() {
        public static void encode(C2SOpenLoadout msg, FriendlyByteBuf buf) {
        }

        public static C2SOpenLoadout decode(FriendlyByteBuf buf) {
            return new C2SOpenLoadout();
        }

        public static void handle(C2SOpenLoadout msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    sendToPlayer(player, new S2CToast("message.mwloadout.disabled", ""));
                    return;
                }
                syncRegistryTo(player);
                syncProfileTo(player);
                EditRuleEngine.Verdict verdict = EditRuleEngine.check(player);
                sendToPlayer(player, new S2COpenLoadout(verdict.allowed(), verdict.reasonKey(), verdict.secondsRemaining()));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Save one preset (does not equip). Always allowed — editing a class in the menu is fine
     *  mid-combat in MW19 too; only APPLYING is gated. */
    public record C2SSaveLoadout(int presetIndex, String presetJson) {
        public static void encode(C2SSaveLoadout msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.presetIndex);
            buf.writeUtf(msg.presetJson, 262144);
        }

        public static C2SSaveLoadout decode(FriendlyByteBuf buf) {
            return new C2SSaveLoadout(buf.readVarInt(), buf.readUtf(262144));
        }

        /** Guarantee the just-saved class has a unique name: if it collides with any OTHER
         *  class (case-insensitive, trimmed), append " 2", " 3", ... until it's unique. This is
         *  why a rail full of "CLASS 1" can't happen anymore — each save is deduped on arrival. */
        private static void dedupeName(PlayerProfile profile, int savedIndex) {
            LoadoutPreset saved = profile.presets.get(savedIndex);
            String name = saved.name == null ? "" : saved.name.trim();
            if (name.isEmpty()) {
                name = "Custom Class";
            }
            // If this exact name doesn't collide with any OTHER class, keep it verbatim. This
            // early-out is what stops the runaway "Sigma 2 2 2": re-saving an already-unique
            // name (what auto-save does on every equip) is now a no-op.
            if (!nameClashes(profile, savedIndex, name)) {
                saved.name = name;
                return;
            }
            // Real collision: strip any numeric suffix WE previously appended so we renumber the
            // true base ("Sigma 2 2" -> base "Sigma", not "Sigma 2 2 2"), then find the next free.
            String base = name.replaceAll("\\s+\\d+$", "").trim();
            if (base.isEmpty()) {
                base = name;
            }
            String candidate = base;
            int suffix = 2;
            while (nameClashes(profile, savedIndex, candidate)) {
                candidate = base + " " + suffix++;
            }
            saved.name = candidate;
        }

        private static boolean nameClashes(PlayerProfile profile, int savedIndex, String candidate) {
            for (int i = 0; i < profile.presets.size(); i++) {
                if (i == savedIndex) {
                    continue;
                }
                String other = profile.presets.get(i).name;
                if (other != null && other.trim().equalsIgnoreCase(candidate)) {
                    return true;
                }
            }
            return false;
        }

        public static void handle(C2SSaveLoadout msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    sendToPlayer(player, new S2CToast("message.mwloadout.disabled", ""));
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                LoadoutPreset incoming;
                try {
                    incoming = LoadoutPreset.fromJson(JsonParser.parseString(msg.presetJson).getAsJsonObject());
                } catch (Exception e) {
                    return; // malformed claim: ignore silently, nothing to break
                }
                List<String> dropped = LoadoutApplier.sanitize(incoming,
                        LoadoutRegistry.get().rules().progressionEnabled
                                ? profile.level(LoadoutRegistry.get().rules().xpPerLevel, LoadoutRegistry.get().rules().xpCurveGrowth)
                                : Integer.MAX_VALUE,
                        player.hasPermissions(2));
                LoadoutPreset target = profile.ensurePreset(msg.presetIndex, LoadoutRegistry.get().rules().maxPresets);
                if (target == null) {
                    return;
                }
                profile.presets.set(msg.presetIndex, incoming);
                profile.customized = true;
                dedupeName(profile, msg.presetIndex); // no two classes may share a name
                ProfileManager.get().save(player.getUUID());
                syncProfileTo(player);
                dev.l41nx.mwloadout.server.KillstreakManager.syncProgress(player); // HUD follows the new killstreak picks
                if (!dropped.isEmpty()) {
                    sendToPlayer(player, new S2CToast("message.mwloadout.dropped", String.join(", ", dropped)));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** "Cloud" import: the client offers the classes / gun builds it saved on this device (single player, another server) when it joins a server
     *  where its profile is still untouched. The server never trusts the contents - every class goes through the same sanitize() as a normal
     *  save (catalog entry exists and is enabled, unlock level, allowed slot, attachments, perk columns), unknown entries are simply dropped.
     *  XP / progression are never imported. */
    public record C2SImportCloud(int index, int total, boolean force, String part) {
        /** Chars per packet. A 1.20.1 client->server custom payload is capped at 32767 BYTES (a whole cloud profile - up to 20 classes plus every
         *  gun build - is far bigger, and sending it as one packet crashed / disconnected the client), and a UTF-8 char can take 3 bytes. */
        public static final int CHUNK_CHARS = 8000;
        private static final int MAX_CHUNKS = 80;
        private static final java.util.Map<java.util.UUID, StringBuilder> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.Map<java.util.UUID, Integer> NEXT = new java.util.concurrent.ConcurrentHashMap<>();

        public static void encode(C2SImportCloud msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.index);
            buf.writeVarInt(msg.total);
            buf.writeBoolean(msg.force);
            buf.writeUtf(msg.part, CHUNK_CHARS);
        }

        public static C2SImportCloud decode(FriendlyByteBuf buf) {
            return new C2SImportCloud(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(CHUNK_CHARS));
        }

        /** Logout: drop a half-received transfer so the buffers never outlive the player. */
        public static void forget(java.util.UUID id) {
            PENDING.remove(id);
            NEXT.remove(id);
        }

        /** Client side: split and send. {@code force} = the player asked for it (button), so it replaces classes they already have on this server. */
        public static void sendAll(String json, boolean force) {
            int total = (json.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
            if (total < 1 || total > MAX_CHUNKS) {
                return; // empty or absurdly large: not worth a packet storm
            }
            for (int i = 0; i < total; i++) {
                CHANNEL.sendToServer(new C2SImportCloud(i, total, force, json.substring(i * CHUNK_CHARS, Math.min(json.length(), (i + 1) * CHUNK_CHARS))));
            }
        }

        public static void handle(C2SImportCloud msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                // reassemble the chunks (in order, bounded) - anything out of sequence drops the whole transfer
                java.util.UUID id = player.getUUID();
                if (msg.total < 1 || msg.total > MAX_CHUNKS || msg.index < 0 || msg.index >= msg.total) {
                    PENDING.remove(id);
                    NEXT.remove(id);
                    return;
                }
                if (msg.index == 0) {
                    PENDING.put(id, new StringBuilder());
                    NEXT.put(id, 0);
                }
                StringBuilder acc = PENDING.get(id);
                if (acc == null || NEXT.getOrDefault(id, -1) != msg.index) {
                    PENDING.remove(id);
                    NEXT.remove(id);
                    return;
                }
                acc.append(msg.part);
                NEXT.put(id, msg.index + 1);
                if (msg.index + 1 < msg.total) {
                    return; // wait for the rest
                }
                PENDING.remove(id);
                NEXT.remove(id);
                String fullJson = acc.toString();
                var rules = LoadoutRegistry.get().rules();
                boolean manual = msg.force; // the player pressed the button: tell them why when nothing happens
                if (!rules.allowCloudImport
                        || !player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    if (manual) {
                        sendToPlayer(player, new S2CToast("message.mwloadout.cloud_import_refused", ""));
                    }
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                if (profile.customized && !manual) {
                    return; // an automatic offer only fills an untouched profile; the button may replace it
                }
                com.google.gson.JsonObject json;
                try {
                    json = JsonParser.parseString(fullJson).getAsJsonObject();
                } catch (Exception e) {
                    if (manual) {
                        sendToPlayer(player, new S2CToast("message.mwloadout.cloud_import_refused", ""));
                    }
                    return;
                }
                int max = Math.max(1, Math.min(PlayerProfile.HARD_MAX_PRESETS, rules.maxPresets));
                int level = rules.progressionEnabled ? profile.level(rules.xpPerLevel, rules.xpCurveGrowth) : Integer.MAX_VALUE;
                boolean op = player.hasPermissions(2);
                List<LoadoutPreset> imported = new java.util.ArrayList<>();
                int offered = 0;          // classes in the client's copy
                int classesTrimmed = 0;   // imported, but lost at least one slot (gun / item this server does not have, level, rules)
                int slotsDropped = 0;
                if (json.has("presets") && json.get("presets").isJsonArray()) {
                    for (var element : json.getAsJsonArray("presets")) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        offered++;
                        if (imported.size() >= max) {
                            continue; // keep counting what did not fit
                        }
                        try {
                            LoadoutPreset preset = LoadoutPreset.fromJson(element.getAsJsonObject());
                            List<String> lost = LoadoutApplier.sanitize(preset, level, op);
                            if (!lost.isEmpty()) {
                                classesTrimmed++;
                                slotsDropped += lost.size();
                            }
                            imported.add(preset);
                        } catch (Exception ignored) {
                            dev.l41nx.mwloadout.MWLog.swallowed("network/Net:484", ignored);
                        }
                    }
                }
                if (imported.isEmpty()) {
                    if (manual) {
                        sendToPlayer(player, new S2CToast("message.mwloadout.cloud_import_refused", ""));
                    }
                    return;
                }
                profile.presets.clear();
                profile.presets.addAll(imported);
                for (int i = 0; i < profile.presets.size(); i++) {
                    C2SSaveLoadout.dedupeName(profile, i);
                }
                profile.activeIndex = Math.max(0, Math.min(profile.presets.size() - 1, dev.l41nx.mwloadout.util.Json.intValue(json, "activeIndex", 0)));
                if (json.has("gunBuilds") && json.get("gunBuilds").isJsonObject()) {
                    for (var entry : json.getAsJsonObject("gunBuilds").entrySet()) {
                        var catalogEntry = LoadoutRegistry.get().entry(entry.getKey());
                        if (catalogEntry == null || catalogEntry.kind != dev.l41nx.mwloadout.data.CatalogEntry.Kind.GUN || !entry.getValue().isJsonObject()) {
                            continue;
                        }
                        try {
                            PlayerProfile.GunBuilds builds = PlayerProfile.GunBuilds.fromJson(entry.getValue().getAsJsonObject());
                            for (var build : builds.builds) {
                                LoadoutApplier.sanitizeAttachmentMap(build);
                            }
                            builds.active = Math.max(0, Math.min(builds.builds.size() - 1, builds.active));
                            profile.gunBuilds.put(entry.getKey(), builds);
                        } catch (Exception ignored) {
                            dev.l41nx.mwloadout.MWLog.swallowed("network/Net:513", ignored);
                        }
                    }
                }
                if (profile.killCard.isEmpty()) {
                    profile.killCard = dev.l41nx.mwloadout.util.Json.str(json, "killCard", "");
                    profile.killEmblem = dev.l41nx.mwloadout.util.Json.str(json, "killEmblem", "");
                    if (profile.killCard.length() > 128 || profile.killEmblem.length() > 128) {
                        profile.killCard = "";
                        profile.killEmblem = "";
                    }
                }
                profile.customized = true;
                ProfileManager.get().save(player.getUUID());
                syncProfileTo(player);
                dev.l41nx.mwloadout.server.KillstreakManager.syncProgress(player);
                int skipped = Math.max(0, offered - imported.size()); // unreadable, or over this server's class limit
                if (slotsDropped > 0 || skipped > 0) {
                    // "Imported N classes - X had items this server does not have (Y slots emptied), Z skipped"
                    sendToPlayer(player, new S2CToast("message.mwloadout.cloud_import_partial",
                            imported.size() + "|" + classesTrimmed + "|" + slotsDropped + "|" + skipped));
                } else {
                    sendToPlayer(player, new S2CToast("message.mwloadout.cloud_imported", String.valueOf(imported.size())));
                }
                dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.info("[MWLoadout] Imported {} saved class(es) for {} from the client's own copy", imported.size(), player.getName().getString());
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Select preset N as active and (if the edit window allows) equip it right now. */
    public record C2SSelectLoadout(int presetIndex) {
        public static void encode(C2SSelectLoadout msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.presetIndex);
        }

        public static C2SSelectLoadout decode(FriendlyByteBuf buf) {
            return new C2SSelectLoadout(buf.readVarInt());
        }

        public static void handle(C2SSelectLoadout msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    sendToPlayer(player, new S2CToast("message.mwloadout.disabled", ""));
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                LoadoutPreset preset = profile.ensurePreset(msg.presetIndex, LoadoutRegistry.get().rules().maxPresets);
                if (preset == null) {
                    return;
                }
                EditRuleEngine.Verdict verdict = EditRuleEngine.check(player);
                profile.activeIndex = msg.presetIndex;
                ProfileManager.get().save(player.getUUID());
                if (verdict.allowed()) {
                    LoadoutApplier.apply(player, preset);
                    dev.l41nx.mwloadout.server.KillstreakManager.syncProgress(player);
                    if (!dev.l41nx.mwloadout.server.FiringRange.isInRange(player)) {
                        EditRuleEngine.markEquip(player); // start the difficulty swap cooldown (not inside the firing range)
                    }
                    sendToPlayer(player, new S2CToast("message.mwloadout.equipped", preset.name));
                } else {
                    // Selection is remembered either way; the toast says exactly WHY not now.
                    String key = switch (verdict.reasonKey()) {
                        case "hard" -> "message.mwloadout.equip_hard";
                        case "cooldown" -> "message.mwloadout.equip_cooldown";
                        case "zone_required" -> "message.mwloadout.equip_zone";
                        case "use_lock" -> verdict.secondsRemaining() > 0
                                ? "message.mwloadout.equip_use_lock" : "message.mwloadout.equip_use_lock_respawn";
                        default -> "message.mwloadout.equip_combat";
                    };
                    sendToPlayer(player, new S2CToast(key, String.valueOf(verdict.secondsRemaining())));
                }
                syncProfileTo(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Persist one gun's 4-slot build memory (MW19 gunsmith saves). Server clamps to 4 builds,
     *  verifies the entry is a real gun, and runs every attachment id through the same existence
     *  check preset saves use — a hacked packet can save junk ids that simply get stripped. */
    public record C2SSaveGunBuild(String entryId, int activeSlot, String buildsJson) {
        public static void encode(C2SSaveGunBuild msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.entryId, 256);
            buf.writeVarInt(msg.activeSlot);
            buf.writeUtf(msg.buildsJson, 65536);
        }

        public static C2SSaveGunBuild decode(FriendlyByteBuf buf) {
            return new C2SSaveGunBuild(buf.readUtf(256), buf.readVarInt(), buf.readUtf(65536));
        }

        public static void handle(C2SSaveGunBuild msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                var entry = LoadoutRegistry.get().entry(msg.entryId);
                if (entry == null || entry.kind != dev.l41nx.mwloadout.data.CatalogEntry.Kind.GUN) {
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                PlayerProfile.GunBuilds builds;
                try {
                    builds = PlayerProfile.GunBuilds.fromJson(JsonParser.parseString(msg.buildsJson).getAsJsonObject());
                } catch (Exception e) {
                    return;
                }
                builds.active = Math.max(0, Math.min(builds.builds.size() - 1, msg.activeSlot));
                for (var build : builds.builds) {
                    LoadoutApplier.sanitizeAttachmentMap(build);
                }
                profile.gunBuilds.put(msg.entryId, builds);
                profile.customized = true;
                // keep the equipped selection in step with the build that was just edited...
                var activePreset = profile.activePreset();
                if (activePreset != null) {
                    for (var selection : activePreset.slots.values()) {
                        if (msg.entryId.equals(selection.entryId)) {
                            selection.attachments.clear();
                            selection.attachments.putAll(builds.activeBuild());
                        }
                    }
                }
                ProfileManager.get().save(player.getUUID());
                syncProfileTo(player);
                // ...and apply it to the gun the player is actually carrying, right now
                if (player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    LoadoutApplier.refreshManagedGun(player, entry, builds.activeBuild());
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** One generic admin mutation: kind + action + JSON payload, permission-gated server-side. */
    public record C2SAdminUpdate(String kind, String action, String payloadJson) {
        public static void encode(C2SAdminUpdate msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.kind, 32);
            buf.writeUtf(msg.action, 32);
            buf.writeUtf(msg.payloadJson, 262144);
        }

        public static C2SAdminUpdate decode(FriendlyByteBuf buf) {
            return new C2SAdminUpdate(buf.readUtf(32), buf.readUtf(32), buf.readUtf(262144));
        }

        public static void handle(C2SAdminUpdate msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) {
                    return;
                }
                AdminActions.handle(player, msg.kind, msg.action, msg.payloadJson);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    // =============================================================== S2C

    public record S2CSyncRegistry(String registryJson) {
        public static void encode(S2CSyncRegistry msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.registryJson, 1048576);
        }

        public static S2CSyncRegistry decode(FriendlyByteBuf buf) {
            return new S2CSyncRegistry(buf.readUtf(1048576));
        }

        public static void handle(S2CSyncRegistry msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.ClientCache.acceptRegistry(msg.registryJson)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2CSyncProfile(String profileJson) {
        public static void encode(S2CSyncProfile msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.profileJson, 1048576);
        }

        public static S2CSyncProfile decode(FriendlyByteBuf buf) {
            return new S2CSyncProfile(buf.readUtf(1048576));
        }

        public static void handle(S2CSyncProfile msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.ClientCache.acceptProfile(msg.profileJson)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2COpenLoadout(boolean editable, String reasonKey, int secondsRemaining) {
        public static void encode(S2COpenLoadout msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.editable);
            buf.writeUtf(msg.reasonKey, 32);
            buf.writeVarInt(msg.secondsRemaining);
        }

        public static S2COpenLoadout decode(FriendlyByteBuf buf) {
            return new S2COpenLoadout(buf.readBoolean(), buf.readUtf(32), buf.readVarInt());
        }

        public static void handle(S2COpenLoadout msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.ClientCache.openLoadoutScreen(msg.editable, msg.reasonKey, msg.secondsRemaining)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2COpenAdmin() {
        public static void encode(S2COpenAdmin msg, FriendlyByteBuf buf) {
        }

        public static S2COpenAdmin decode(FriendlyByteBuf buf) {
            return new S2COpenAdmin();
        }

        public static void handle(S2COpenAdmin msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.ClientCache.openAdminScreen()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** High Alert perk: relative yaw angles (degrees, -180..180 vs the holder's view) of
     *  enemies currently looking at the holder — client draws directional edge pulses. Sent only
     *  while non-empty and at most every 10 ticks per holder; empty screens cost nothing. */
    public record S2CHighAlert(float[] relativeYaws) {
        public static void encode(S2CHighAlert msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.relativeYaws.length);
            for (float yaw : msg.relativeYaws) {
                buf.writeFloat(yaw);
            }
        }

        public static S2CHighAlert decode(FriendlyByteBuf buf) {
            float[] yaws = new float[Math.min(16, buf.readVarInt())];
            for (int i = 0; i < yaws.length; i++) {
                yaws[i] = buf.readFloat();
            }
            return new S2CHighAlert(yaws);
        }

        public static void handle(S2CHighAlert msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.HighAlertOverlay.pulse(msg.relativeYaws)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Killstreak HUD feed: whether the system is on, Specialist vs item mode, the (fractional) streak, and the
     *  ladder - required kills per rung (Hardline already applied), the reward id, and whether it is earned. */
    public record S2CStreakProgress(boolean enabled, boolean specialist, float progress,
                                    int[] kills, String[] ids, boolean[] earned) {
        public static void encode(S2CStreakProgress msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.enabled);
            buf.writeBoolean(msg.specialist);
            buf.writeFloat(msg.progress);
            int n = Math.min(msg.kills.length, 16);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                buf.writeVarInt(msg.kills[i]);
                buf.writeUtf(msg.ids[i], 256);
                buf.writeBoolean(msg.earned[i]);
            }
        }

        public static S2CStreakProgress decode(FriendlyByteBuf buf) {
            boolean enabled = buf.readBoolean();
            boolean specialist = buf.readBoolean();
            float progress = buf.readFloat();
            int n = Math.min(buf.readVarInt(), 16);
            int[] kills = new int[n];
            String[] ids = new String[n];
            boolean[] earned = new boolean[n];
            for (int i = 0; i < n; i++) {
                kills[i] = buf.readVarInt();
                ids[i] = buf.readUtf(256);
                earned[i] = buf.readBoolean();
            }
            return new S2CStreakProgress(enabled, specialist, progress, kills, ids, earned);
        }

        public static void handle(S2CStreakProgress msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.StreakHud.update(msg)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** "<player> used <killstreak>" banner: blue for allies, red for enemies. */
    public record S2CStreakUse(String playerName, String itemId, boolean ally) {
        public static void encode(S2CStreakUse msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.playerName, 64);
            buf.writeUtf(msg.itemId, 256);
            buf.writeBoolean(msg.ally);
        }

        public static S2CStreakUse decode(FriendlyByteBuf buf) {
            return new S2CStreakUse(buf.readUtf(64), buf.readUtf(256), buf.readBoolean());
        }

        public static void handle(S2CStreakUse msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.StreakCallouts.push(msg.playerName(), msg.itemId(), msg.ally())));
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2CToast(String translationKey, String argument) {
        public static void encode(S2CToast msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.translationKey, 128);
            buf.writeUtf(msg.argument, 512);
        }

        public static S2CToast decode(FriendlyByteBuf buf) {
            return new S2CToast(buf.readUtf(128), buf.readUtf(512));
        }

        public static void handle(S2CToast msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.l41nx.mwloadout.client.ClientCache.toast(msg.translationKey, msg.argument)));
            ctx.get().setPacketHandled(true);
        }
    }
    /** Class-row context-menu operations, all server-authoritative: delete (list shrinks),
     *  favorite (moves to slot 1), random (cleared then run through the same auto-fill the
     *  equip path uses — a legal surprise class), reset (blank + default perk trio), clear
     *  (fully empty). After every op, auto-numbered names ("CLASS n") are renumbered 1..n in
     *  list order so duplicates like the screenshot's five CLASS 7s can no longer exist;
     *  custom names are never touched. */
    public record C2SPresetOp(String op, int presetIndex) {
        public static void encode(C2SPresetOp msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.op, 16);
            buf.writeVarInt(msg.presetIndex);
        }

        public static C2SPresetOp decode(FriendlyByteBuf buf) {
            return new C2SPresetOp(buf.readUtf(16), buf.readVarInt());
        }

        public static void handle(C2SPresetOp msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    sendToPlayer(player, new S2CToast("message.mwloadout.disabled", ""));
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                if (msg.presetIndex < 0 || msg.presetIndex >= profile.presets.size()) {
                    return;
                }
                profile.customized = true;
                LoadoutPreset preset = profile.presets.get(msg.presetIndex);
                switch (msg.op) {
                    case "duplicate" -> {
                        int maxPresets = Math.min(LoadoutRegistry.get().rules().maxPresets, PlayerProfile.HARD_MAX_PRESETS);
                        if (profile.presets.size() < maxPresets) {
                            LoadoutPreset copy = LoadoutPreset.fromJson(preset.toJson());
                            profile.presets.add(copy);
                            C2SSaveLoadout.dedupeName(profile, profile.presets.size() - 1);
                        }
                    }
                    case "delete" -> {
                        if (profile.presets.size() > 1) {
                            profile.presets.remove(msg.presetIndex);
                        }
                        // Refuse to delete the last remaining preset: zero presets means no active
                        // preset, which breaks equip/perks/High Alert/etc. until the next login
                        // (when DefaultLoadouts re-pads the profile) — better to just no-op.
                    }
                    case "favorite" -> {
                        profile.presets.remove(msg.presetIndex);
                        profile.presets.add(0, preset);
                        profile.activeIndex = 0;
                    }
                    case "reset", "clear", "random" -> {
                        preset.slots.clear();
                        preset.perks.clear();
                        if (!msg.op.equals("clear")) {
                            preset.perks.put("perk1", "quick_fix");   // one per column, same trio as a new player's classes
                            preset.perks.put("perk2", "double_time"); // Blood Rush
                            preset.perks.put("perk3", "scavenger");
                        }
                        if (msg.op.equals("random")) {
                            LoadoutApplier.randomFill(preset, player.hasPermissions(2));
                        }
                    }
                    default -> {
                        return;
                    }
                }
                renumberAutoNames(profile);
                profile.activeIndex = Mth.clamp(profile.activeIndex, 0, Math.max(0, profile.presets.size() - 1));
                ProfileManager.get().save(player.getUUID());
                syncProfileTo(player);
            });
            ctx.get().setPacketHandled(true);
        }

        /** "CLASS <n>" names renumber to their 1-based list position; anything else is a name
         *  the player typed and stays exactly as typed. */
        private static void renumberAutoNames(PlayerProfile profile) {
            for (int i = 0; i < profile.presets.size(); i++) {
                LoadoutPreset preset = profile.presets.get(i);
                if (preset.name != null && preset.name.matches("CLASS \\d+")) {
                    preset.name = "CLASS " + (i + 1);
                }
            }
        }
    }

    /** Persists the player's kill-card + emblem choice (see KillFeedCompat.choose) into their
     *  MWLoadout profile — this is what makes the choice survive a rejoin instead of depending on
     *  the Killfeed addon's own client-side storage. */
    public record C2SSaveKillCard(String card, String emblem) {
        public static void encode(C2SSaveKillCard msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.card, 128);
            buf.writeUtf(msg.emblem, 128);
        }

        public static C2SSaveKillCard decode(FriendlyByteBuf buf) {
            return new C2SSaveKillCard(buf.readUtf(128), buf.readUtf(128));
        }

        public static void handle(C2SSaveKillCard msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                profile.killCard = msg.card == null ? "" : msg.card;
                profile.killEmblem = msg.emblem == null ? "" : msg.emblem;
                ProfileManager.get().save(player.getUUID());
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** MWLoadout's own favorites toggle for the customize screen (see PlayerProfile.
     *  favoriteCards) — replaces a dead KillFeed toggleFavorite reflection call that never
     *  did anything against the real mod. Echoes a fresh S2CSyncProfile back so the star icon
     *  updates immediately instead of waiting for the next unrelated profile sync. */
    public record C2SToggleFavoriteCard(String card) {
        public static void encode(C2SToggleFavoriteCard msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.card, 128);
        }

        public static C2SToggleFavoriteCard decode(FriendlyByteBuf buf) {
            return new C2SToggleFavoriteCard(buf.readUtf(128));
        }

        public static void handle(C2SToggleFavoriteCard msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || msg.card == null || msg.card.isEmpty()) {
                    return;
                }
                PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
                if (!profile.favoriteCards.remove(msg.card)) {
                    profile.favoriteCards.add(msg.card);
                }
                ProfileManager.get().save(player.getUUID());
                syncProfileTo(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Admin Stats tab sends this on open/refresh to get a fresh snapshot — stats aren't synced
     *  automatically on join like the registry is, since only admins ever look at them. */
    public record C2SRequestStats() {
        public static void encode(C2SRequestStats msg, FriendlyByteBuf buf) {
        }

        public static C2SRequestStats decode(FriendlyByteBuf buf) {
            return new C2SRequestStats();
        }

        public static void handle(C2SRequestStats msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) {
                    return;
                }
                sendToPlayer(player, new S2CSyncStats(dev.l41nx.mwloadout.registry.UsageStats.get().toSyncJson()));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Admin-only wipe of every usage/combat counter — server-authoritative (the client never
     *  mutates UsageStats directly), replies with the fresh empty snapshot immediately so the
     *  Stats tab updates without a separate request round trip. */
    public record C2SResetStats() {
        public static void encode(C2SResetStats msg, FriendlyByteBuf buf) {
        }

        public static C2SResetStats decode(FriendlyByteBuf buf) {
            return new C2SResetStats();
        }

        public static void handle(C2SResetStats msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) {
                    return;
                }
                dev.l41nx.mwloadout.registry.UsageStats.get().resetAll();
                sendToPlayer(player, new S2CSyncStats(dev.l41nx.mwloadout.registry.UsageStats.get().toSyncJson()));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2CSyncStats(String statsJson) {
        public static void encode(S2CSyncStats msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.statsJson, 1048576);
        }

        public static S2CSyncStats decode(FriendlyByteBuf buf) {
            return new S2CSyncStats(buf.readUtf(1048576));
        }

        public static void handle(S2CSyncStats msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                dev.l41nx.mwloadout.registry.UsageStats.get().fromSyncJson(msg.statsJson);
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> {
                    net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                    if (minecraft.screen instanceof dev.l41nx.mwloadout.client.gui.admin.AdminScreen adminScreen) {
                        adminScreen.onStatsRefreshed();
                    }
                });
            });
            ctx.get().setPacketHandled(true);
        }
    }

}
