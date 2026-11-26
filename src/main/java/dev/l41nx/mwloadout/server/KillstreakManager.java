package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The killstreak brain, MW-style. Two mutually-exclusive modes per player (README):
 * <ul>
 *   <li><b>Item streaks (default):</b> hit a streak threshold → the reward gun is QUEUED. The
 *       player pulls it into their inventory with a keybind; it stays queued until claimed. Once
 *       carried and fired dry (magazine hits 0), it's auto-removed (see {@link KillstreakItems}).</li>
 *   <li><b>Specialist:</b> no items. Instead the player pre-orders three perks; kills 2/4/6 each
 *       auto-activate the next queued perk, and kill 8 unlocks ALL perks at once. Dying resets the
 *       whole chain and the perks.</li>
 * </ul>
 * Perk interactions: <b>Hardline</b> makes every threshold cost one fewer kill (item and specialist
 * alike). <b>Killchain</b> lets the FIRST death of a life keep the streak going; the second death
 * resets it.
 */
public final class KillstreakManager {

    /** Specialist stage thresholds (MW): 2, 4, 6 kills — each unlocks the next of the 3 pre-picked
     *  perks. Full unlock (every game perk) is a SEPARATE, later milestone; see below. */
    private static final int[] SPEC_STAGE_KILLS = {2, 4, 6};
    /** Full unlock (every game perk) is earned only at the 8th kill, past the three staged perks. */
    private static final int SPEC_FULL_UNLOCK_KILLS = 8;

    private KillstreakManager() {
    }

    /** Call when {@code killer} gets a kill. Advances the streak and grants rewards/perks.
     *  @param victimIsPlayer whether the kill was PvP (a player) as opposed to a mob — decides
     *      whether Rules.killstreakPvpOnly blocks it and which weight it counts for.
     *  @param victimIsHostile whether the victim is a hostile mob (Monster) — decides whether
     *      Rules.killstreakSourceRestricted blocks it (passive/neutral mobs never count when on). */
    public static void onKill(ServerPlayer killer, boolean victimIsPlayer, boolean victimIsHostile) {
        Rules rules = LoadoutRegistry.get().rules();
        if (!rules.killstreakEnabled) {
            return;
        }
        // "Source" — only a hostile mob or a player kill counts at all; "PVP Only
        // Mode" — a stricter override, mobs never count regardless of hostility.
        if (rules.killstreakPvpOnly && !victimIsPlayer) {
            return;
        }
        if (rules.killstreakSourceRestricted && !victimIsPlayer && !victimIsHostile) {
            return;
        }
        PlayerProfile profile = ProfileManager.get().profile(killer.getUUID());
        // "Hostile Mob Multiplier": a player kill always advances the streak by a full point; a
        // mob kill only advances it once `hostileMobMultiplier` of them have accumulated (1 = no
        // scaling, every kill is a full point exactly like before). Keeping currentStreak itself
        // as a plain "effective kill" integer means the HUD/streak-reward thresholds need no
        // changes at all — only how fast it fills up differs.
        int multiplier = Math.max(1, rules.hostileMobMultiplier);
        if (victimIsPlayer || multiplier <= 1) {
            profile.currentStreak++;
        } else {
            profile.mobKillProgress++;
            if (profile.mobKillProgress >= multiplier) {
                profile.mobKillProgress -= multiplier;
                profile.currentStreak++;
            } else {
                syncProgress(killer); // the bar still creeps forward on a partial mob point
                return; // partial progress only — no reward/specialist check yet on this kill
            }
        }
        profile.highestStreakThisLife = Math.max(profile.highestStreakThisLife, profile.currentStreak);
        profile.killchainConsumed = false; // a kill after a saved death re-arms Killchain ("consecutive" deaths only)

        boolean specialist = profile.specialistActive;
        if (specialist) {
            fillSpecialistPerks(killer); // empty staged perks cycle random ones from the pool
        }
        // Hardline/Killchain shift ITEM killstreak thresholds only — Specialist is unaffected.
        int hardline = PerkEffects.playerHasSpecial(killer, "hardline") ? 1 : 0;

        if (specialist) {
            advanceSpecialist(killer, profile);
        } else {
            advanceItemStreaks(killer, profile, rules, hardline);
        }
        syncProgress(killer);
    }

    private static void advanceItemStreaks(ServerPlayer killer, PlayerProfile profile, Rules rules, int hardline) {
        // >= (not ==) plus a per-life "already granted" set — mirrors advanceSpecialist's
        // pattern just below. Hardline can change `required` between kills (e.g. a mid-life
        // loadout swap), and with a plain == check a reward whose recomputed threshold jumps
        // to meet-or-below the CURRENT streak on the same kill that would've crossed the old
        // one gets skipped forever. >= plus tracking what's granted keeps "exactly once, right
        // when first qualified" without that skip risk.
        // Only what this CLASS equipped (max 3, distinct kill counts) - the rest of the pool is just a menu.
        for (int i : dev.l41nx.mwloadout.data.StreakPool.effective(rules, profile.activePreset())) {
            Rules.StreakReward reward = rules.streakRewards.get(i);
            int required = Math.max(1, reward.kills - hardline);
            if (profile.currentStreak < required || !profile.grantedStreakRewards.add(i)) {
                continue;
            }
            if (reward.itemId == null || reward.itemId.isEmpty()) {
                continue;
            }
            if (KillstreakItems.giveStreakItem(killer, reward.itemId)) {
                notify(killer, "message.mwloadout.streak_ready", reward.itemId);
                killer.level().playSound(null, killer.blockPosition(),
                        SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
            } else {
                profile.grantedStreakRewards.remove(i); // give failed — allow a retry next kill
            }
        }
    }

    private static void advanceSpecialist(ServerPlayer killer, PlayerProfile profile) {
        // Specialist is NOT affected by Hardline/Killchain. Three staged perks unlock at 2/4/6
        // kills; the FULL unlock (every game perk) happens only at kill 8.
        int[] stageKills = SPEC_STAGE_KILLS;
        int fullUnlock = SPEC_FULL_UNLOCK_KILLS;

        // Activate each staged perk once its threshold is crossed. Set.add(i) both checks AND
        // marks "granted" in one step — same pattern as advanceItemStreaks' grantedStreakRewards,
        // deliberately not a raw counter compared with <=: a counter only needs one stray write
        // to end up too low for every subsequent kill to look "not yet granted" again and re-fire
        // the same stage's perk over and over regardless of how far the streak has actually gone.
        for (int i = 0; i < 3; i++) {
            if (profile.currentStreak >= stageKills[i] && profile.specialistStagesGranted.add(i)) {
                String perkId = i < profile.specialistPerks.size() ? profile.specialistPerks.get(i) : null;
                if (perkId != null && !perkId.isEmpty()) {
                    applySpecialistPerk(killer, perkId);
                    PerkDefinition perkDef = LoadoutRegistry.get().perk(perkId);
                    notify(killer, "message.mwloadout.specialist_perk",
                            perkDef != null ? perkDef.displayName : perkId);
                    killer.level().playSound(null, killer.blockPosition(),
                            SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.2f);
                }
            }
        }
        // Full unlock at the final stage: every enabled game perk, once.
        if (profile.currentStreak >= fullUnlock && !profile.specialistFullUnlock) {
            profile.specialistFullUnlock = true;
            applyAllGamePerks(killer);
            notify(killer, "message.mwloadout.specialist_full", "");
            killer.level().playSound(null, killer.blockPosition(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 0.8f);
        }
    }

    /** Reset on death — unless Killchain saves the first death of the life. */
    public static void onDeath(ServerPlayer player) {
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        boolean killchain = PerkEffects.playerHasSpecial(player, "killchain");
        // Killchain saves a streak so its items can still be earned. When EVERY equipped streak item has already been earned there is nothing left to
        // save: the chain resets on this death, otherwise the kept streak stayed above all thresholds and it took several more deaths before
        // a new chain could start.
        if (killchain && !profile.killchainConsumed && !allStreakItemsEarned(profile)) {
            // First death in a row: keep the streak (and its items), but burn the Killchain save. The flag
            // now survives the respawn - it used to be cleared right there, so the "second consecutive
            // death resets" half of Killchain never happened and the streak was kept forever.
            profile.killchainConsumed = true;
            syncProgress(player);
            return;
        }
        resetStreak(profile);
        if (LoadoutRegistry.get().rules().streakResetOnDeath) {
            if (LoadoutRegistry.get().rules().streakItemsLostOnDeath) {
                // Hardcore option: rewards are lost with the life (queued ones and any streak item still carried).
                KillstreakItems.clearForNewLife(player);
            } else {
                // Default: the streak COUNTER resets, but earned-and-unused rewards are kept - carried ones are stashed
                // and handed back on respawn, queued ones stay queued. (They used to vanish on death.)
                KillstreakItems.stashCarriedForRespawn(player);
            }
        }
        syncProgress(player);
    }

    /** Full reset on (re)spawn bookkeeping — clears the Killchain save for the new life. */
    public static void onRespawn(ServerPlayer player) {
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        // (killchainConsumed is NOT cleared here any more - see onDeath.)
        // Specialist perks are life-scoped: a fresh life starts with none active again.
        // Unconditional (NOT gated on profile.specialistActive): if a player toggles Specialist
        // off (or switches their 3 chosen perks) while an earlier stage's perk is still actively
        // granted, the old `if (specialistActive)` guard here meant that already-applied
        // attribute/mob-effect (e.g. Shield Up's Absorption, Battle Hardened's +20 armor) was
        // never stripped and persisted across every future death — a permanent free buff. This
        // always clears, so a stray specialist perk can never outlive the life it was earned in.
        clearSpecialistPerks(player);
        profile.specialistStagesGranted.clear();
        profile.specialistFullUnlock = false;
        // Belt and braces for the new life: nothing carries a streak but a Killchain-saved one.
        if (profile.currentStreak <= 0 && LoadoutRegistry.get().rules().streakResetOnDeath
                && LoadoutRegistry.get().rules().streakItemsLostOnDeath) {
            KillstreakItems.clearForNewLife(player);
        }
        KillstreakItems.restoreAfterRespawn(player);
        syncProgress(player);
    }

    private static boolean allStreakItemsEarned(PlayerProfile profile) {
        java.util.List<Integer> equipped = dev.l41nx.mwloadout.data.StreakPool.effective(LoadoutRegistry.get().rules(), profile.activePreset());
        if (equipped.isEmpty()) {
            return true; // no streak items equipped: nothing to protect
        }
        for (int i : equipped) {
            if (!profile.grantedStreakRewards.contains(i)) {
                return false;
            }
        }
        return true;
    }

    private static void resetStreak(PlayerProfile profile) {
        profile.killchainConsumed = false;
        profile.currentStreak = 0;
        profile.highestStreakThisLife = 0;
        profile.mobKillProgress = 0;
        profile.specialistStagesGranted.clear();
        profile.specialistFullUnlock = false;
        profile.activeSpecialistPerks.clear();
        profile.grantedStreakRewards.clear();
        // Queued (unclaimed) items and any carried streak weapon are NOT wiped here: carried
        // whitelist items only leave when fired dry; queued rewards persist for the claim key.
    }

    /** Full unlock: apply every enabled perk in the game (MW Specialist's 8-kill bonus). */
    private static void applyAllGamePerks(ServerPlayer player) {
        for (var perk : LoadoutRegistry.get().perkList()) {
            if (perk.enabled && !Rules.SPECIALIST_EXCLUDED.contains(perk.id)) { // Killchain / Hardline / Overkill / Underkill never come with the 8-kill unlock
                applySpecialistPerk(player, perk.id);
            }
        }
    }

    private static void applySpecialistPerk(ServerPlayer player, String perkId) {
        // Reuse the loadout's perk-application path so a specialist perk behaves identically to a
        // slotted one (attributes, effects). Record the id so special-perk checks (Ghost, Hardline,
        // etc.) recognise it at runtime — not just loadout-slotted perks.
        ProfileManager.get().profile(player.getUUID()).activeSpecialistPerks.add(perkId);
        LoadoutApplier.applySinglePerk(player, perkId);
    }

    private static void clearSpecialistPerks(ServerPlayer player) {
        ProfileManager.get().profile(player.getUUID()).activeSpecialistPerks.clear();
        LoadoutApplier.reapplyActiveLoadoutPerks(player);
    }

    /** Full reset of streak/Specialist progress, including stripping any already-active
     *  Specialist perk effects. Called whenever the player toggles Specialist mode or changes
     *  their 3 staged picks (see C2SSetSpecialist) — without this, a streak accumulated in Item
     *  mode (or under the OLD perk picks) carries straight over, and the very next kill can cross
     *  several thresholds at once (2/4/6/8 all on "1 kill") instead of progressing normally. */
    public static void resetSpecialistProgress(ServerPlayer player) {
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        clearSpecialistPerks(player);
        profile.currentStreak = 0;
        profile.highestStreakThisLife = 0;
        profile.mobKillProgress = 0;
        profile.specialistStagesGranted.clear();
        profile.specialistFullUnlock = false;
        profile.grantedStreakRewards.clear();
        syncProgress(player);
    }

    /** Specialist with empty (or now-clashing) staged perks cycles random ones from the pool, like empty perk
     *  columns do: keeps every valid pick, then fills up to three with distinct, enabled pool perks that are not
     *  already equipped. Runs when Specialist is switched on, on every class swap, and lazily on kills. */
    public static void fillSpecialistPerks(ServerPlayer player) {
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        if (!profile.specialistActive) {
            return;
        }
        Rules rules = LoadoutRegistry.get().rules();
        LoadoutPreset active = profile.activePreset();
        java.util.Set<String> equipped = active != null ? new java.util.HashSet<>(active.perks.values()) : java.util.Set.of();
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String id : profile.specialistPerks) {
            var def = id == null ? null : LoadoutRegistry.get().perk(id);
            int onlyColumn = def == null ? 0 : def.specialistColumn();
            if (clean.size() < 3 && id != null && !id.isEmpty() && LoadoutRegistry.get().specialistPool().contains(id)
                    && !equipped.contains(id) && !clean.contains(id) && (onlyColumn == 0 || onlyColumn == clean.size() + 1)) {
                clean.add(id);
            }
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String id : LoadoutRegistry.get().specialistPool()) {
            var perk = LoadoutRegistry.get().perk(id);
            if (perk != null && perk.enabled && !equipped.contains(id) && !clean.contains(id)) {
                candidates.add(id);
            }
        }
        java.util.Collections.shuffle(candidates);
        while (clean.size() < 3) {
            int position = clean.size() + 1; // the column being filled; Specialist-only perks (Shield Up) only fit their own
            String pick = null;
            for (String id : candidates) {
                var def = LoadoutRegistry.get().perk(id);
                int onlyColumn = def == null ? 0 : def.specialistColumn();
                if (onlyColumn == 0 || onlyColumn == position) {
                    pick = id;
                    break;
                }
            }
            if (pick == null) {
                break;
            }
            clean.add(pick);
            candidates.remove(pick);
        }
        if (!clean.equals(profile.specialistPerks)) {
            profile.specialistPerks = clean;
            ProfileManager.get().save(player.getUUID());
        }
    }

    /** Pushes this player's streak progress to their HUD: the ladder (Hardline already applied to the
     *  thresholds), what is earned, and how far along the bar is. Cheap; called on every change. */
    public static void syncProgress(ServerPlayer player) {
        Rules rules = LoadoutRegistry.get().rules();
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        boolean enabled = rules.killstreakEnabled;
        boolean specialist = profile.specialistActive && rules.specialistEnabled;
        java.util.List<int[]> rungs = new java.util.ArrayList<>(); // {required, originalIndex}
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<Boolean> earned = new java.util.ArrayList<>();
        if (specialist) {
            for (int i = 0; i < SPEC_STAGE_KILLS.length; i++) {
                rungs.add(new int[]{SPEC_STAGE_KILLS[i], i});
                ids.add(i < profile.specialistPerks.size() ? profile.specialistPerks.get(i) : "");
                earned.add(profile.specialistStagesGranted.contains(i));
            }
            rungs.add(new int[]{SPEC_FULL_UNLOCK_KILLS, 3});
            ids.add("");
            earned.add(profile.specialistFullUnlock);
        } else {
            int hardline = PerkEffects.playerHasSpecial(player, "hardline") ? 1 : 0;
            // the equipped selection, ascending by kills (what the HUD tiles show, bottom to top)
            for (int i : dev.l41nx.mwloadout.data.StreakPool.effective(rules, profile.activePreset())) {
                Rules.StreakReward reward = rules.streakRewards.get(i);
                rungs.add(new int[]{Math.max(1, reward.kills - hardline), i});
                ids.add(reward.itemId == null ? "" : reward.itemId);
                earned.add(profile.grantedStreakRewards.contains(i));
            }
        }
        int n = rungs.size();
        int[] kills = new int[n];
        String[] idArray = new String[n];
        boolean[] earnedArray = new boolean[n];
        for (int i = 0; i < n; i++) {
            kills[i] = rungs.get(i)[0];
            idArray[i] = ids.get(i);
            earnedArray[i] = earned.get(i);
        }
        int multiplier = Math.max(1, rules.hostileMobMultiplier);
        float progress = profile.currentStreak + (multiplier > 1 ? profile.mobKillProgress / (float) multiplier : 0f);
        Net.sendToPlayer(player, new Net.S2CStreakProgress(enabled, specialist, progress, kills, idArray, earnedArray));
    }

    private static void notify(ServerPlayer player, String key, String arg) {
        // toast-only now — the chat line was redundant once the animated card existed,
        // and the player explicitly asked for these to stop double-reporting to chat.
        Net.sendToPlayer(player, new Net.S2CToast(key, arg == null ? "" : prettyId(arg)));
    }

    /** Turn a perk/item id into a readable label for chat (e.g. "nmw2:bomb_drone" -> "Bomb Drone"). */
    private static String prettyId(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        s = s.replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return out.toString().trim();
    }
}
