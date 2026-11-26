package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Interprets PerkDefinition.effects for a player. Everything applied here is cleanly reversible:
 *
 *  - "attribute": an ADDITION AttributeModifier whose UUID is derived deterministically from
 *    (perk id + attribute id), so removal never needs bookkeeping — removeAllPerkEffects() can
 *    always reconstruct exactly which modifiers this mod owns and strip only those. Third-party
 *    attributes (gunsmithlib:reload_speed) resolve through the registry at runtime; if the
 *    backing mod is absent the effect is skipped with a log line, never a crash.
 *  - "mob_effect": infinite, ambient, icon-visible potion effect, re-asserted on every apply and
 *    removed on perk change/loadout swap.
 *  - "special": overkill is consumed by the validator (slot rewiring), scavenger by the hooks
 *    below (10-20% reserve ammo of the held gun on player kill; +1 lethal & +1 tactical every 30
 *    seconds, capped at the slot's own maxCount so it can never inflate beyond a fresh loadout).
 */
public final class PerkEffects {

    private static final String MODIFIER_NAME = "mwloadout_perk";

    private PerkEffects() {
    }

    /** Scavenger resupply under NBT-ammo: add rounds straight into every dummy-ammo gun the
     *  player carries (hotbar + main), capped per event — no ammo items to detect or dupe. */
    public static void refillDummyAmmo(ServerPlayer player, int roundsPerGun) {
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            if (igun != null && igun.useDummyAmmo(stack) && !KillstreakItems.isStreakItem(stack)) { // streak ammo is fixed
                igun.addDummyAmmoAmount(stack, roundsPerGun);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    public static boolean hasSpecial(PerkDefinition perk, String key) {
        for (JsonObject effect : perk.effects) {
            if ("special".equals(Json.str(effect, "type", "")) && key.equals(Json.str(effect, "key", ""))) {
                return true;
            }
        }
        return false;
    }

    /** True if the loadout runs the Fully Loaded perk — drives a flat x4 reserve multiplier in
     *  LoadoutApplier (replacing whatever tier multiplier dynamicAmmoTiers would have picked,
     *  across every category, not added on top of it). The old "ammo_bonus" JSON effect + per-
     *  category flat-add mechanism is retired; Fully Loaded no longer carries any effects entry
     *  at all — this id check is the whole mechanism now. */
    public static boolean hasFullyLoaded(LoadoutPreset preset) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (String perkId : preset.perks.values()) {
            if ("fully_loaded".equals(perkId)) {
                PerkDefinition perk = registry.perk(perkId);
                if (perk != null && perk.enabled) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Shrapnel: +N lethal/tactical count (2 per stack of the perk, effectively one). */
    /** Extra deploy COUNT for a slot. Shrapnel's equipment side: +1 lethal, +1 tactical and
     *  +1 in EXTRA 2 (2 claymores instead of 1) — stacked on top of the resist. The legacy
     *  "equipment_bonus" special (+2) still honors any custom perk using it. */
    public static int equipmentBonus(LoadoutPreset preset, dev.l41nx.mwloadout.data.SlotDefinition slot) {
        int bonus = 0;
        boolean throwableSlot = slot.allowedCategories.contains("lethal") || slot.allowedCategories.contains("tactical");
        if (LoadoutApplier.hasSpecialPerk(preset, "shrapnel") && throwableSlot) { // field upgrade slots get no bonus
            bonus += 1;
        }
        if (LoadoutApplier.hasSpecialPerk(preset, "quick_fix_supplies") && slot.allowedCategories.contains("consumable")) {
            bonus += 2; // 4 stims instead of 2
        }
        if (throwableSlot && LoadoutApplier.hasSpecialPerk(preset, "equipment_bonus")) {
            bonus += 2;
        }
        return bonus;
    }

    /** The JSON object of the first active special effect with this key — the admin-tunable
     *  parameter surface (High Alert's players/mobs/exemptTag, Fully Loaded's bonuses map...). */
    public static JsonObject specialParams(LoadoutPreset preset, String key) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (String perkId : preset.perks.values()) {
            PerkDefinition perk = registry.perk(perkId);
            if (perk == null || !perk.enabled) {
                continue;
            }
            for (JsonObject effect : perk.effects) {
                if ("special".equals(Json.str(effect, "type", "")) && key.equals(Json.str(effect, "key", ""))) {
                    return effect;
                }
            }
        }
        return null;
    }

    /** Global params lookup for a special-effect key, independent of any specific player/preset.
     *  Perk balance numbers (resist %, durations, speed %...) are ADMIN CONFIG — there's only
     *  ever one registered perk per special key in practice, so this just finds it directly in
     *  the registry. This is the one canonical way live-effect handlers (Shrapnel, Double Time,
     *  Tracker, Fragiles, Quick Fix, High Alert's range/cone) read their tunable numbers, whether
     *  the player currently has the perk via their equipped preset OR a Specialist grant —
     *  unlike specialParams(preset, key) above, no active preset is required. */
    public static JsonObject paramsForKey(String key) {
        for (PerkDefinition perk : LoadoutRegistry.get().perkList()) {
            if (!perk.enabled) {
                continue;
            }
            for (JsonObject effect : perk.effects) {
                if ("special".equals(Json.str(effect, "type", "")) && key.equals(Json.str(effect, "key", ""))) {
                    return effect;
                }
            }
        }
        return null;
    }

    /** True when the player's active class (or a Specialist pick) carries this perk id - independent of how the perk's effects
     *  are defined in the (possibly hand-edited / older) registry. */
    public static boolean playerHasPerkId(ServerPlayer player, String perkId) {
        dev.l41nx.mwloadout.data.PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        LoadoutPreset active = profile.activePreset();
        if (active != null && active.perks.containsValue(perkId)) {
            return true;
        }
        return profile.activeSpecialistPerks.contains(perkId);
    }

    /** Duration multiplier (1 = untouched) that the player's active perks apply to an incoming potion effect.
     *  Data-driven: a perk effect object {"reduces_effects":["@harmful"],"durationMultiplier":0.1} shortens every effect matching
     *  one of its selectors. Several perks multiply. */
    public static float effectDurationMultiplier(ServerPlayer player, MobEffect effect) {
        dev.l41nx.mwloadout.data.PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        LoadoutRegistry registry = LoadoutRegistry.get();
        Set<String> perkIds = new HashSet<>(profile.activeSpecialistPerks);
        LoadoutPreset active = profile.activePreset();
        if (active != null) {
            perkIds.addAll(active.perks.values());
        }
        float result = 1f;
        for (String perkId : perkIds) {
            PerkDefinition perk = registry.perk(perkId);
            if (perk == null || !perk.enabled) {
                continue;
            }
            for (JsonObject entry : perk.effects) {
                java.util.List<String> selectors = Json.strList(entry, "reduces_effects");
                if (selectors.isEmpty()) {
                    continue;
                }
                for (String selector : selectors) {
                    if (effectMatchesSelector(selector, effect)) {
                        float multiplier = (float) Json.doubleValue(entry, "durationMultiplier", 0.1);
                        result *= Math.max(0f, Math.min(1f, multiplier));
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** True when one of the player's active perks lists this effect under "immune_effects" (same selectors as "reduces_effects"). */
    public static boolean isImmuneToEffect(ServerPlayer player, MobEffect effect) {
        dev.l41nx.mwloadout.data.PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        LoadoutRegistry registry = LoadoutRegistry.get();
        Set<String> perkIds = new HashSet<>(profile.activeSpecialistPerks);
        LoadoutPreset active = profile.activePreset();
        if (active != null) {
            perkIds.addAll(active.perks.values());
        }
        for (String perkId : perkIds) {
            PerkDefinition perk = registry.perk(perkId);
            if (perk == null || !perk.enabled) {
                continue;
            }
            for (JsonObject entry : perk.effects) {
                for (String selector : Json.strList(entry, "immune_effects")) {
                    if (effectMatchesSelector(selector, effect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean effectMatchesSelector(String selector, MobEffect effect) {
        String sel = selector.trim().toLowerCase(java.util.Locale.ROOT);
        switch (sel) {
            case "@harmful":
                return effect.getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL;
            case "@beneficial":
                return effect.getCategory() == net.minecraft.world.effect.MobEffectCategory.BENEFICIAL;
            case "@neutral":
                return effect.getCategory() == net.minecraft.world.effect.MobEffectCategory.NEUTRAL;
            case "@all":
            case "*":
                return true;
            default:
                break;
        }
        ResourceLocation key = ForgeRegistries.MOB_EFFECTS.getKey(effect);
        if (key == null) {
            return false;
        }
        if (sel.endsWith(":*")) {
            return key.getNamespace().equals(sel.substring(0, sel.length() - 2));
        }
        return key.toString().equals(sel);
    }

    public static boolean playerHasSpecial(ServerPlayer player, String key) {
        dev.l41nx.mwloadout.data.PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        if (profile.specialistActive && dev.l41nx.mwloadout.data.Rules.SPECIALIST_LOCKED.contains(key)) {
            return false; // Killchain / Hardline are locked while Specialist is on, whether equipped or granted
        }
        LoadoutPreset active = profile.activePreset();
        if (active != null && LoadoutApplier.hasSpecialPerk(active, key)) {
            return true;
        }
        // Also honour perks granted at runtime by Specialist streaks.
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (String perkId : profile.activeSpecialistPerks) {
            PerkDefinition perk = registry.perk(perkId);
            if (perk == null || !perk.enabled) {
                continue;
            }
            for (JsonObject effect : perk.effects) {
                if ("special".equals(Json.str(effect, "type", "")) && key.equals(Json.str(effect, "key", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void applyPerks(ServerPlayer player, LoadoutPreset preset) {
        removeAllPerkEffects(player);
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (String perkId : preset.perks.values()) {
            PerkDefinition perk = registry.perk(perkId);
            if (perk == null || !perk.enabled || perk.specialistColumn() > 0) {
                continue;
            }
            for (JsonObject effect : perk.effects) {
                switch (Json.str(effect, "type", "")) {
                    case "attribute" -> applyAttribute(player, perk, effect);
                    case "mob_effect" -> applyMobEffect(player, effect);
                    default -> { /* "special" handled elsewhere; unknown types reserved for the future */ }
                }
            }
        }
    }

    /** Saved classes keep perks by column id. When a perk moves column (or becomes Specialist-only) the old entry no longer matches the
     *  picker: put each perk in the column it belongs to now, drop clashes and Specialist-only perks. Unknown ids are left alone.
     *  @return true when something changed, so the caller persists. */
    public static boolean reconcilePresetPerks(dev.l41nx.mwloadout.data.PlayerProfile profile) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        if (registry.perkList().isEmpty()) {
            return false; // registry not loaded yet - never wipe anyone's perks on that
        }
        Set<String> validSlots = new HashSet<>();
        registry.rules().perkSlots.forEach(slot -> validSlots.add(slot.id));
        boolean changed = false;
        for (LoadoutPreset preset : profile.presets) {
            java.util.Map<String, String> fixed = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> entry : new java.util.ArrayList<>(preset.perks.entrySet())) {
                PerkDefinition perk = registry.perk(entry.getValue());
                if (perk == null) {
                    fixed.putIfAbsent(entry.getKey(), entry.getValue());
                } else if (perk.specialistColumn() == 0 && validSlots.contains(perk.perkSlot)) {
                    fixed.putIfAbsent(perk.perkSlot, entry.getValue());
                }
            }
            if (!fixed.equals(preset.perks)) {
                preset.perks.clear();
                preset.perks.putAll(fixed);
                changed = true;
            }
        }
        return changed;
    }

    /** Apply ONE perk additively (for Specialist-granted perks), without clearing the others. */
    public static void applySinglePerk(ServerPlayer player, String perkId) {
        PerkDefinition perk = LoadoutRegistry.get().perk(perkId);
        if (perk == null || !perk.enabled) {
            return;
        }
        for (JsonObject effect : perk.effects) {
            switch (Json.str(effect, "type", "")) {
                case "attribute" -> applyAttribute(player, perk, effect);
                case "mob_effect" -> applyMobEffect(player, effect);
                default -> { }
            }
        }
    }

    private static void applyAttribute(ServerPlayer player, PerkDefinition perk, JsonObject effect) {
        String attributeId = Json.str(effect, "attribute", "");
        double amount = Json.doubleValue(effect, "amount", 0);
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.tryParse(attributeId));
        if (attribute == null) {
            MWLoadoutMod.LOGGER.warn("[MWLoadout] Perk {}: attribute {} not present (backing mod missing?) - skipped",
                    perk.id, attributeId);
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        UUID modifierId = stableUuid(perk.id + "|" + attributeId);
        instance.removeModifier(modifierId);
        instance.addPermanentModifier(new AttributeModifier(modifierId, MODIFIER_NAME,
                amount, AttributeModifier.Operation.ADDITION));
    }

    private static void applyMobEffect(ServerPlayer player, JsonObject effect) {
        MobEffect mobEffect = ForgeRegistries.MOB_EFFECTS.getValue(ResourceLocation.tryParse(Json.str(effect, "effect", "")));
        if (mobEffect == null) {
            return;
        }
        int amplifier = Json.intValue(effect, "amplifier", 0);
        player.addEffect(new MobEffectInstance(mobEffect, MobEffectInstance.INFINITE_DURATION, amplifier, true, false, true));
    }

    /** Strips every modifier and effect this mod could possibly own, derived from perk defs. */
    public static void removeAllPerkEffects(ServerPlayer player) {
        for (PerkDefinition perk : LoadoutRegistry.get().perkList()) {
            for (JsonObject effect : perk.effects) {
                String type = Json.str(effect, "type", "");
                if ("attribute".equals(type)) {
                    Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.tryParse(Json.str(effect, "attribute", "")));
                    if (attribute != null) {
                        AttributeInstance instance = player.getAttribute(attribute);
                        if (instance != null) {
                            instance.removeModifier(stableUuid(perk.id + "|" + Json.str(effect, "attribute", "")));
                        }
                    }
                } else if ("mob_effect".equals(type)) {
                    MobEffect mobEffect = ForgeRegistries.MOB_EFFECTS.getValue(ResourceLocation.tryParse(Json.str(effect, "effect", "")));
                    if (mobEffect != null && player.hasEffect(mobEffect)) {
                        MobEffectInstance current = player.getEffect(mobEffect);
                        // Only remove OUR infinite ambient instances; a potion the player drank isn't ours.
                        if (current != null && current.getDuration() == net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION && current.isAmbient()) {
                            player.removeEffect(mobEffect);
                        }
                    }
                }
            }
        }
    }

    /** The zero-value marker modifier the Ninja perk puts on LUCK (attribute modifiers are synced to every client that tracks the
     *  player): that is how a client knows which players are ninjas without any extra packet. */
    public static UUID ninjaMarkerUuid() {
        return stableUuid("ninja|minecraft:generic.luck");
    }

    private static UUID stableUuid(String seed) {
        return UUID.nameUUIDFromBytes(("mwloadout:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- scavenger

    /** Killer landed a kill (mob or player) while holding a gun: refills 10-20% of that gun's
     *  reserve ammo. Utility perk, not an economy reward, so unlike XP it's not PvP-restricted. */
    public static void onPlayerKill(ServerPlayer killer) {
        LoadoutPreset active = activePresetWithSpecial(killer, "scavenger");
        if (active == null) {
            return;
        }
        ItemStack held = killer.getMainHandItem();
        if (dev.l41nx.mwloadout.server.KillstreakItems.isStreakItem(held)) {
            return; // killstreak reward guns are deliberately one-shot - never refill their ammo
        }
        com.tacz.guns.api.item.IGun igun = com.tacz.guns.api.item.IGun.getIGunOrNull(held);
        if (igun == null) {
            return;
        }
        String gunId = igun.getGunId(held).toString();
        CatalogEntry gunEntry = LoadoutRegistry.get().catalogList().stream()
                .filter(entry -> entry.kind == CatalogEntry.Kind.GUN && entry.gameId.equals(gunId))
                .findFirst().orElse(null);
        String category = gunEntry != null ? gunEntry.category : "";
        int allowance = LoadoutRegistry.get().rules().reserveAmmoFor(gunId, category);
        var params = paramsForKey("scavenger");
        int minPercent = params != null ? Json.intValue(params, "minPercent", 10) : 10;
        int maxPercent = params != null ? Json.intValue(params, "maxPercent", 20) : 20;
        if (maxPercent < minPercent) {
            maxPercent = minPercent;
        }
        int percent = minPercent + ThreadLocalRandom.current().nextInt(maxPercent - minPercent + 1);
        int rounds = Math.max(1, allowance * percent / 100);
        if (LoadoutRegistry.get().rules().ammoInGunNbt && igun.useDummyAmmo(held)) {
            // NBT-ammo mode: the kill feeds the murder weapon directly.
            igun.addDummyAmmoAmount(held, rounds);
            killer.containerMenu.broadcastChanges();
            return;
        }
        for (ItemStack ammo : TaczStackFactory.buildReserveAmmo(gunId, rounds)) {
            if (!killer.getInventory().add(ammo)) {
                break; // full inventory: silently stop, never drop on the ground (anti-farm)
            }
        }
    }

    // ---------------------------------------------------------------- quick fix

    /** "Kills kick-start your regen" — a short Regeneration burst on any kill. This perk was
     *  registered (special("quick_fix")) with a matching description but had no runtime effect
     *  anywhere in the codebase; wiring it up here. Values are a reasonable MW-style default
     *  (Regen II, 4s) — not admin-tunable yet, matching how Shrapnel/Double Time's constants
     *  work today; ask if you want it exposed in the perk JSON like High Alert's params are. */
    public static void quickFixOnKill(ServerPlayer killer) {
        if (!playerHasSpecial(killer, "quick_fix")) {
            return;
        }
        var params = paramsForKey("quick_fix");
        int seconds = params != null ? Json.intValue(params, "durationSeconds", 4) : 4;
        int level = params != null ? Json.intValue(params, "level", 2) : 2;
        // If the player's Quick Fix grants a passive (infinite-duration) Regeneration on top of
        // this kill burst, chain the burst's hiddenEffect back to it — otherwise Minecraft just
        // replaces the passive instance outright, and once this stronger/shorter burst expires
        // the player is left with ZERO regen for the rest of their life instead of the passive
        // resuming. Only chains when an infinite regen is actually currently active, so a Quick
        // Fix definition without the passive effect behaves exactly as before.
        net.minecraft.world.effect.MobEffectInstance current =
                killer.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
        net.minecraft.world.effect.MobEffectInstance hidden =
                current != null && current.getDuration() == net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION ? new net.minecraft.world.effect.MobEffectInstance(current) : null;
        killer.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.REGENERATION,
                Math.max(1, seconds) * 20, Math.max(1, level) - 1, false, true, true, hidden,
                java.util.Optional.empty()));
    }

    /** Every 30s: top lethal/tactical slots back up by one, capped at the slot's own maxCount. */
    public static void scavengerTick(ServerPlayer player) {
        LoadoutPreset active = activePresetWithSpecial(player, "scavenger");
        if (active == null) {
            return;
        }
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (SlotDefinition slot : registry.slotList()) {
            if (!slot.enabled || slot.hotbarIndex < 0 || slot.hotbarIndex > 8) {
                continue;
            }
            // v1.7: EVERY utility slot recharges — consumables, throwables and deployables
            // alike. Guns stay excluded (dummy-ammo is Scavenger's other half, handled on kill).
            // FIELD UPGRADE slots (extra*) are deliberately excluded: they have their own recharge timer
            // (see FieldUpgrades) and Scavenger must not speed them up.
            boolean utilitySlot = !FieldUpgrades.isFieldUpgradeSlot(slot)
                    && (slot.allowedCategories.contains("lethal")
                    || slot.allowedCategories.contains("tactical")
                    || slot.allowedCategories.contains("consumable"));
            if (!utilitySlot) {
                continue;
            }
            LoadoutPreset.Selection selection = active.slots.get(slot.id);
            String entryId = selection != null && !selection.entryId.isEmpty() ? selection.entryId : slot.defaultEntry;
            if (entryId.isEmpty()) {
                continue;
            }
            CatalogEntry entry = registry.entry(entryId);
            if (entry == null || entry.kind != CatalogEntry.Kind.ITEM) {
                continue;
            }
            ItemStack current = player.getInventory().getItem(slot.hotbarIndex);
            // Cap tracks perk equipment bonuses (Shrapnel/Quick Fix) so the trickle refills to
            // the SAME ceiling the deploy handed out.
            int cap = Math.min(entry.count + equipmentBonus(active, slot), slot.maxCount);
            if (current.isEmpty()) {
                ItemStack fresh = TaczStackFactory.buildItem(entry, slot.maxCount);
                if (!fresh.isEmpty()) {
                    fresh.setCount(1);
                    player.getInventory().setItem(slot.hotbarIndex, fresh);
                }
            } else if (current.getCount() < cap && matchesEntry(current, entry)) {
                current.grow(1);
            }
        }
        player.getInventory().setChanged();
    }

    private static boolean matchesEntry(ItemStack stack, CatalogEntry entry) {
        ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return stackId != null && stackId.toString().equals(entry.gameId);
    }

    /** Resolves the active preset ONLY if the player currently has `key`, checking both the
     *  equipped preset's perks AND any Specialist-streak-granted perk (via playerHasSpecial,
     *  which is defined in this same class) — the direct LoadoutApplier.hasSpecialPerk(preset,
     *  ...) this used to call only saw the former, so a Specialist-granted Scavenger never
     *  triggered ammo resupply or the equipment recharge tick. */
    private static LoadoutPreset activePresetWithSpecial(ServerPlayer player, String key) {
        LoadoutPreset active = ProfileManager.get().profile(player.getUUID()).activePreset();
        if (active == null) {
            return null;
        }
        return playerHasSpecial(player, key) ? active : null;
    }
}
