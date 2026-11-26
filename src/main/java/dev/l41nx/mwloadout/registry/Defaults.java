package dev.l41nx.mwloadout.registry;

import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * First-run configuration. Everything here is just DATA the admin can freely rewrite afterwards —
 * this class only exists so a fresh install already behaves like the spec without touching a
 * single file:
 *
 *   Slot 1 PRIMARY   (ar/smg/lmg/sr/dmr/sg/br)     Slot 6 TACTICAL  (lrt throwables)
 *   Slot 2 SECONDARY (pistol/launcher)             Slot 7-9 EXTRA   (admin-stocked, disabled
 *   Slot 3 MELEE     (lrt melee)                                     until an admin fills them)
 *   Slot 4 CONSUMABLE(lrt consumables)
 *   Slot 5 LETHAL    (lrt throwables)
 *
 *   Perk column 1: Overkill / Underkill / Quick Fix / Fragiles
 *   Perk column 2: Sleight of Hands / Battle Hardened / Scavenger / Double Time
 *
 *   Ammo reserve (v2.2.0 balance): AR/SMG 120 / LMG 200 / BR 100 / DMR 80 / shotguns 50 /
 *   snipers 25 (tacz:50bgm exactly 15) / pistols 60 / launchers 3 — gun's ammo TYPE always
 *   auto-detected from its own TACZ index. dynamicAmmoEnabled (off by default) replaces this
 *   whole table with a per-gun magazine-capacity calculation instead (see Rules.dynamicAmmoTiers).
 *
 * Attribute perk amounts are ADDITION modifiers pre-translated so the end result equals the
 * reference /attribute base-set commands (see PerkDefinition javadoc for why modifiers, not
 * base overwrites): movement 0.1+0.011=0.111, armor 0+20=20, reload_speed 1.0+0.35=1.35.
 */
final class Defaults {

    private Defaults() {
    }

    static void seed(LoadoutRegistry registry) {
        seedSlots(registry);
        seedCatalog(registry);
        seedPerks(registry);
        seedRules(registry);
    }

    private static void seedSlots(LoadoutRegistry registry) {
        registry.upsertSlot(SlotDefinition.of("primary", 0, "PRIMARY", "Main weapon",
                "minecraft:crossbow", List.of("ar", "smg", "lmg", "sr", "dmr", "sg", "br"), "", true));
        registry.upsertSlot(SlotDefinition.of("secondary", 1, "SECONDARY", "Sidearm or launcher",
                "minecraft:bow", List.of("pistol", "launcher"), "", false));
        registry.upsertSlot(SlotDefinition.of("melee", 2, "MELEE", "Melee weapon",
                "minecraft:iron_sword", List.of("melee"), "", false));
        SlotDefinition consumable = SlotDefinition.of("consumable", 3, "CONSUMABLE", "Medical / utility",
                "minecraft:potion", List.of("consumable"), "", false);
        consumable.maxCount = 4; // default maxCount=1 was silently capping entry counts to 1
        registry.upsertSlot(consumable);
        SlotDefinition lethal = SlotDefinition.of("lethal", 4, "LETHAL", "Lethal equipment",
                "minecraft:tnt", List.of("lethal"), "", false);
        lethal.maxCount = 4;
        registry.upsertSlot(lethal);
        SlotDefinition tactical = SlotDefinition.of("tactical", 5, "TACTICAL", "Tactical equipment",
                "minecraft:snowball", List.of("tactical"), "", false);
        tactical.maxCount = 4;
        registry.upsertSlot(tactical);

        for (int extra = 1; extra <= 3; extra++) {
            SlotDefinition slot = SlotDefinition.of("extra" + extra, 5 + extra, "FIELD UPGRADE " + extra,
                    "Admin-configured slot", "minecraft:chest", List.of("extra" + extra), "", false);
            slot.enabled = false; // invisible until an admin stocks a category/whitelist and enables it
            slot.maxCount = 16;
            // Extra 3 is the opt-in novelty slot: not required, never random-filled — only shows
            // gear the player deliberately picks (a pufferfish, a party popper, whatever).
            if (extra == 3) {
                slot.optional = true;
            }
            registry.upsertSlot(slot);
        }
    }

    private static void seedCatalog(LoadoutRegistry registry) {
        // Gun entries are auto-seeded from every loaded gunpack by WeaponCatalog at server start.
        // Only the LRTactical mw19 equipment the spec names is pre-seeded here — the exact same
        // item-id + NBT construction CodMatchmaking's TaczLoadoutBuilder proved out.
        registry.upsertEntry(lrt("melee_knife", "Combat Knife", "melee", "lrtactical:melee", "MeleeWeaponId", "mw19:knife", 1));
        registry.upsertEntry(lrt("frag", "Frag Grenade", "lethal", "lrtactical:throwable", "ThrowableId", "mw19:frag_gn", 2));
        registry.upsertEntry(lrt("toxic", "Toxic Gas", "lethal", "lrtactical:throwable", "ThrowableId", "mw19:toxic", 2));
        registry.upsertEntry(lrt("incendiary", "Incendiary", "lethal", "lrtactical:throwable", "ThrowableId", "mw19:incendiary", 2));
        registry.upsertEntry(lrt("flash", "Flash Grenade", "tactical", "lrtactical:throwable", "ThrowableId", "mw19:flash", 2));
        registry.upsertEntry(lrt("stun", "Stun Grenade", "tactical", "lrtactical:throwable", "ThrowableId", "mw19:stun", 2));
        registry.upsertEntry(lrt("smoke", "Smoke Grenade", "tactical", "lrtactical:throwable", "ThrowableId", "mw19:smoke", 2));
        registry.upsertEntry(lrt("special", "Special", "tactical", "lrtactical:throwable", "ThrowableId", "mw19:special", 2));
        registry.upsertEntry(lrt("stim", "Stim", "consumable", "lrtactical:consumable", "ConsumableId", "mw19:stim", 2));
        // Default novelty item for the opt-in Extra 3 slot — a pufferfish. Plain vanilla item,
        // no NBT; only appears when the player deliberately picks it (extra3 is never auto-filled).
        registry.upsertEntry(CatalogEntry.item("pufferfish", "Pufferfish", "extra3", "minecraft:pufferfish", "", 1));
    }

    private static CatalogEntry lrt(String id, String name, String category, String itemId, String nbtKey, String nbtValue, int count) {
        return CatalogEntry.item(id, name, category, itemId, "{" + nbtKey + ":\"" + nbtValue + "\"}", count);
    }

    /** Perks + perk columns only — used by first-run seeding AND /mwloadout resetperks. */
    static void seedPerksAndColumns(LoadoutRegistry registry) {
        seedPerks(registry);
        addPerkSlotIfAbsent(registry, "perk1", "PERK 1");
        addPerkSlotIfAbsent(registry, "perk2", "PERK 2");
        addPerkSlotIfAbsent(registry, "perk3", "PERK 3");
    }

    private static void addPerkSlotIfAbsent(LoadoutRegistry registry, String id, String displayName) {
        for (Rules.PerkSlot existing : registry.rules().perkSlots) {
            if (existing.id.equals(id)) {
                return; // already there — never duplicate, never touch an admin's column edits
            }
        }
        registry.rules().perkSlots.add(Rules.PerkSlot.of(id, displayName));
    }

    private static void seedPerks(LoadoutRegistry registry) {
        // Column distribution mirrors MW19's actual perk columns where the perk exists there
        // (Double Time/Quick Fix/Scavenger are P1 in MW19; Overkill/High Alert are P2; Battle
        // Hardened/Shrapnel/Tracker-family are P3), custom perks slotted for balance.
        // Layout after Modern Warfare 4's three-slot pick-3: perk 1 = detection and streak progression, perk 2 = movement, handling and
        // economy, perk 3 = survivability and equipment. Shield Up is a Specialist-only perk (third Specialist column).
        // --- Perk 1 ---
        registry.upsertPerkIfAbsent(PerkDefinition.of("quick_fix", "Quick Fix",
                "Kills trigger health regeneration.",
                "textures/perks/quickfix.png", "perk1",
                List.of(quickFix(), special("quick_fix_supplies"))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("overkill", "Overkill",
                "Carry two primary weapons.",
                "textures/perks/overkill.png", "perk1",
                List.of(special("overkill"))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("underkill", "Underkill",
                "Your primary slot becomes a second secondary: carry two secondary weapons. Inspired by Black Ops 4.",
                "textures/perks/underkill.png", "perk1",
                List.of(special("underkill"))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("high_alert", "High Alert",
                "Your vision pulses when an enemy outside your view looks at you.",
                "textures/perks/high_alert.png", "perk1",
                List.of(highAlert())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("ghost", "Ghost",
                "Undetectable by enemy UAV sweeps, Tracker and High Alert. Firing does not ping you on enemy minimaps.",
                "textures/perks/ghost.png", "perk1",
                List.of(special("ghost"))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("tune_up", "Tune Up",
                "Field Upgrades recharge 40% faster.",
                "textures/perks/tune_up.png", "perk1",
                List.of(tuneUp())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("killchain", "Killchain",
                "Your killstreak progress survives your first death. A second death in a row resets it.",
                "textures/perks/killchain.png", "perk1",
                List.of(special("killchain"))));

        // --- Perk 2 ---
        registry.upsertPerkIfAbsent(PerkDefinition.of("hardline", "Hardline",
                "Killstreaks cost one less kill.",
                "textures/perks/hardline.png", "perk2",
                List.of(special("hardline"))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("double_time", "Blood Rush",
                "Move faster, including while crouched.",
                "textures/perks/doubletime.png", "perk2",
                List.of(attribute("minecraft:generic.movement_speed", 0.015), sneakBoost())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("sleight_of_hands", "Fast Hands",
                "Reload and swap weapons faster.",
                "textures/perks/sledgeofhands.png", "perk2",
                List.of(attribute("mw:reload_speed", 0.35),
                        attribute("mw:draw_speed", 0.35))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("ninja", "Ninja",
                "Your footsteps and movement sounds are 90% quieter.",
                "textures/perks/ninja.png", "perk2",
                // the LUCK entry is a ZERO-value marker so other clients can tell who is a ninja (see PerkEffects.ninjaMarkerUuid)
                List.of(quietSteps(), attribute("minecraft:generic.luck", 0.0))));
        registry.upsertPerkIfAbsent(PerkDefinition.of("shrapnel", "Shrapnel",
                "Take much less explosive damage. Carry extra Lethal and Tactical equipment.",
                "textures/perks/lethal.png", "perk2",
                List.of(shrapnel())));

        // --- Perk 3 ---
        registry.upsertPerkIfAbsent(PerkDefinition.of("battle_hardened", "Battle Hardened",
                "Reduces the effect of enemy Tacticals and status effects. Immune to Glowing, except from Tracker. Slightly tougher.",
                "textures/perks/battle_harden.png", "perk3",
                List.of(attribute("minecraft:generic.armor", 4.0), reducesEffects())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("scavenger", "Scavenger",
                "Kills resupply your ammo. Lethal and Tactical equipment recharge over time.",
                "textures/perks/scavenger.png", "perk3",
                List.of(scavengerKill())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("tracker", "Tracker",
                "Damaging an enemy marks them for a short time. Your scope stays steady: no sway, no need to hold your breath.",
                "textures/perks/tracker.png", "perk3",
                List.of(tracker())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("fragiles", "Fragiles",
                "Increases melee damage. Hits you land stop enemy players from regenerating health for 2 seconds.",
                "textures/perks/fragiles.png", "perk3",
                List.of(mobEffect("minecraft:strength", 3), fragilesRegenBlock())));
        registry.upsertPerkIfAbsent(PerkDefinition.of("fully_loaded", "Fully Loaded",
                "Deploy with double reserve ammo for every weapon.",
                "textures/perks/fully_loaded.png", "perk3",
                List.of()));

        // --- Specialist only (third Specialist column; never offered in Perk 1/2/3) ---
        registry.upsertPerkIfAbsent(PerkDefinition.of("shield_up", "Shield Up",
                "Deploy with extra armor plates. Specialist only.",
                "textures/perks/shield_up.png", "specialist3",
                List.of(mobEffect("minecraft:absorption", 2))));
    }

    /** High Alert with its tuning surface visible: which watcher classes trigger it, a
     *  scoreboard-tag opt-out (vanilla /tag add <player> <tag>) for staff or event referees, and
     *  the detection geometry (how far, how wide a cone) it scans. */
    private static JsonObject highAlert() {
        JsonObject json = special("high_alert");
        json.addProperty("players", true);
        json.addProperty("mobs", true);
        json.addProperty("exemptTag", "");
        json.addProperty("rangeBlocks", 48);
        json.addProperty("coneDegrees", 14); // half-angle: ~14deg matches the old dot>0.97 check
        return json;
    }

    /** Shrapnel's explosion resist — admin-tunable percentage (0-100). */
    /** Tune Up: shorter field-upgrade recharge - admin-tunable percentage. */
    private static JsonObject tuneUp() {
        JsonObject json = special("tune_up");
        json.addProperty("reductionPercent", 40);
        return json;
    }

    /** Ninja: every sound whose id contains one of "quietSounds" and that comes from a ninja plays at volumeMultiplier x volume.
     *  Both are admin-tunable (quick field "Volume x" + the raw effects JSON). Client-side, so vanilla steps AND Presence Footsteps
     *  (which plays its own sounds locally) are covered by the same hook. */
    private static JsonObject quietSteps() {
        JsonObject json = special("quiet_steps");
        json.addProperty("volumeMultiplier", 0.1);
        com.google.gson.JsonArray sounds = new com.google.gson.JsonArray();
        // extra substrings of the full sound id; the client already always covers vanilla steps/falls/swim/splash and Presence Footsteps
        for (String token : new String[]{"jump", "land", "wander"}) {
            sounds.add(token);
        }
        json.add("quietSounds", sounds);
        return json;
    }

    private static JsonObject shrapnel() {
        JsonObject json = special("shrapnel");
        json.addProperty("resistPercent", 80);
        return json;
    }

    /** Double Time's crouch-walk speed boost — admin-tunable percentage. The flat +10% base
     *  move speed is separately configurable via the attribute() effect right next to this one. */
    private static JsonObject sneakBoost() {
        JsonObject json = special("sneak_boost");
        json.addProperty("speedPercent", 40);
        return json;
    }

    /** Scavenger's on-kill ammo resupply — admin-tunable percent RANGE of the gun's reserve
     *  allowance (a random value between the two is picked each kill, as before). */
    private static JsonObject scavengerKill() {
        JsonObject json = special("scavenger");
        json.addProperty("minPercent", 10);
        json.addProperty("maxPercent", 20);
        return json;
    }

    /** Tracker's Glowing mark — admin-tunable duration and level (1 = Glowing I; vanilla Glowing
     *  has no real per-level visual difference, but the knob is there for consistency/future use). */
    private static JsonObject tracker() {
        JsonObject json = special("tracker_damage_only");
        json.addProperty("durationSeconds", 2);
        json.addProperty("level", 1);
        return json;
    }

    /** Fragiles' hit effect: the victim (a player) cannot regenerate health for durationSeconds after you hit them. It used to apply
     *  Wither for the same side effect; this blocks healing directly and does no damage over time. */
    public static JsonObject fragilesRegenBlock() {
        JsonObject json = special("fragiles_regen_block");
        json.addProperty("durationSeconds", 2);
        return json;
    }

    /** Quick Fix's on-kill regen burst — admin-tunable duration and Regeneration level
     *  (1 = Regen I, 2 = Regen II, ...). No passive always-on Regen here by design — only the
     *  on-kill burst below (plus quick_fix_supplies' extra consumable slots, added separately). */
    private static JsonObject quickFix() {
        JsonObject json = special("quick_fix");
        json.addProperty("durationSeconds", 4);
        json.addProperty("level", 5);
        return json;
    }

    private static JsonObject attribute(String attributeId, double amount) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "attribute");
        json.addProperty("attribute", attributeId);
        json.addProperty("amount", amount);
        return json;
    }

    private static JsonObject mobEffect(String effectId, int amplifier) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "mob_effect");
        json.addProperty("effect", effectId);
        json.addProperty("amplifier", amplifier);
        return json;
    }

    /** Battle Hardened: every HARMFUL potion effect lands 90 % shorter. Selectors live in "reduces_effects"
     *  (@harmful / @beneficial / @neutral / @all / effect id / "namespace:*"). */
    public static JsonObject reducesEffects() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "special");
        json.addProperty("key", "reduces_effects");
        com.google.gson.JsonArray selectors = new com.google.gson.JsonArray();
        selectors.add("@harmful");
        json.add("reduces_effects", selectors);
        json.addProperty("durationMultiplier", 0.1);
        // Immune to Glowing from every source - the Tracker perk's own hit mark is the one exception (handled in ServerEvents).
        com.google.gson.JsonArray immune = new com.google.gson.JsonArray();
        immune.add("minecraft:glowing");
        json.add("immune_effects", immune);
        return json;
    }

    private static JsonObject special(String key) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "special");
        json.addProperty("key", key);
        return json;
    }

    private static void seedRules(LoadoutRegistry registry) {
        Rules rules = new Rules();
        rules.perkSlots.add(Rules.PerkSlot.of("perk1", "PERK 1"));
        rules.perkSlots.add(Rules.PerkSlot.of("perk2", "PERK 2"));
        rules.perkSlots.add(Rules.PerkSlot.of("perk3", "PERK 3"));
        rules.fullyLoadedMultiplier = 2.0; // MW-style: a real boost, not x4 on every category (launchers included)
        // Starter reserve rounds per category (README v2.2 balance pass).
        rules.ammoByCategory.put("ar", 120);
        rules.ammoByCategory.put("smg", 120);
        rules.ammoByCategory.put("lmg", 200);
        rules.ammoByCategory.put("br", 100);
        rules.ammoByCategory.put("dmr", 80);
        rules.ammoByCategory.put("sg", 50);
        rules.ammoByCategory.put("sr", 25);
        rules.ammoByCategory.put("pistol", 60);
        rules.ammoByCategory.put("launcher", 3);

        // Default killstreak ladder (README): 3 kills bomb_drone, 5 spotter_scope, 7 laser_pointer, 9 ts:fl10.
        rules.streakRewards.clear();
        rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(3, "nmw2:bomb_drone"));
        rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(5, "nmw2:spotter_scope"));
        rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(7, "nmw2:laser_pointer"));
        rules.streakRewards.add(new dev.l41nx.mwloadout.data.Rules.StreakReward(9, "ts:fl10"));
        // Specialist perk pool — perks the player can queue as streak rewards (must not clash with
        // their equipped perks; enforced at selection time).
        rules.specialistPerkPool.clear();
        rules.specialistPerkPool.add("battle_hardened");
        rules.specialistPerkPool.add("sleight_of_hands");
        rules.specialistPerkPool.add("overkill");
        rules.specialistPerkPool.add("fully_loaded");
        rules.specialistPerkPool.add("scavenger");
        rules.specialistPerkPool.add("high_alert");
        rules.specialistPerkPool.add("shield_up");
        rules.specialistPerkPool.add("shrapnel");
        registry.setRules(rules);
    }
}
