package dev.l41nx.mwloadout.network;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;

/**
 * The single choke point every admin-panel mutation flows through (already permission-checked by
 * the packet handler). Applies to the live registry, persists to disk, then re-syncs the whole
 * registry to EVERY online player — an admin changing a slot rule is instantly the rule in
 * everyone's open GUI, not just after their next relog.
 */
final class AdminActions {

    private AdminActions() {
    }

    static void handle(ServerPlayer admin, String kind, String action, String payloadJson) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        try {
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            boolean delete = "DELETE".equalsIgnoreCase(action);
            switch (kind.toUpperCase()) {
                case "SLOT" -> {
                    SlotDefinition slot = SlotDefinition.fromJson(payload);
                    if (slot.id.isEmpty()) {
                        return;
                    }
                    if (delete) {
                        registry.removeSlot(slot.id);
                    } else {
                        slot.hotbarIndex = Math.max(0, Math.min(8, slot.hotbarIndex));
                        registry.upsertSlot(slot);
                    }
                }
                case "ENTRY" -> {
                    CatalogEntry entry = CatalogEntry.fromJson(payload);
                    if (entry.id.isEmpty()) {
                        return;
                    }
                    if (delete) {
                        registry.removeEntry(entry.id);
                    } else {
                        registry.upsertEntry(entry);
                    }
                }
                case "PERK" -> {
                    PerkDefinition perk = PerkDefinition.fromJson(payload);
                    if (perk.id.isEmpty()) {
                        return;
                    }
                    // Attribute / mob-effect perks are APPLIED to players once (at class apply). Editing the definition used to leave
                    // every online player on the old numbers until they respawned or re-applied their class. So: strip the effects
                    // using the OLD definitions, change the registry, then re-apply with the NEW ones.
                    var online = admin.getServer() == null ? java.util.List.<ServerPlayer>of() : admin.getServer().getPlayerList().getPlayers();
                    for (ServerPlayer player : online) {
                        dev.l41nx.mwloadout.server.PerkEffects.removeAllPerkEffects(player);
                    }
                    if (delete) {
                        registry.removePerk(perk.id);
                    } else {
                        registry.upsertPerk(perk);
                    }
                    for (ServerPlayer player : online) {
                        if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                            continue;
                        }
                        dev.l41nx.mwloadout.server.LoadoutApplier.reapplyActiveLoadoutPerks(player);
                        for (String specialistPerk : dev.l41nx.mwloadout.server.ProfileManager.get().profile(player.getUUID()).activeSpecialistPerks) {
                            dev.l41nx.mwloadout.server.PerkEffects.applySinglePerk(player, specialistPerk);
                        }
                    }
                }
                case "BULK" -> {
                    // Multi-select / creative-browser operations: ONE save + ONE resync for the whole batch
                    // (per-entry UPSERT packets would re-sync every online player once per item).
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    if (payload.has("ids")) {
                        payload.getAsJsonArray("ids").forEach(element -> ids.add(element.getAsString()));
                    }
                    if (ids.size() > 5000) {
                        return; // sanity cap: no legitimate multi-select is this large
                    }
                    int affected = 0;
                    switch (action.toUpperCase()) {
                        case "ENABLE" -> affected = registry.setEntriesEnabled(ids, true);
                        case "DISABLE" -> affected = registry.setEntriesEnabled(ids, false);
                        case "CATEGORY" -> {
                            String category = payload.has("category") ? payload.get("category").getAsString().trim() : "";
                            if (category.isEmpty()) {
                                return;
                            }
                            affected = registry.setEntriesCategory(ids, category);
                        }
                        case "UNLOCK" -> {
                            int level = Math.max(0, payload.has("level") ? payload.get("level").getAsInt() : 0);
                            for (String id : ids) {
                                CatalogEntry target = registry.entry(id);
                                if (target != null && target.unlockLevel != level) {
                                    target.unlockLevel = level;
                                    affected++;
                                }
                            }
                        }
                        case "DELETE" -> affected = registry.removeEntries(ids);
                        case "ADDMANY" -> {
                            if (payload.has("entries")) {
                                int accepted = 0;
                                for (var element : payload.getAsJsonArray("entries")) {
                                    if (++accepted > 400) {
                                        break; // the client sends <=150 per packet; refuse absurd batches
                                    }
                                    CatalogEntry created = CatalogEntry.fromJson(element.getAsJsonObject());
                                    if (!created.id.isEmpty() && created.id.length() <= 128 && registry.entry(created.id) == null) {
                                        registry.upsertEntry(created);
                                        affected++;
                                    }
                                }
                            }
                        }
                        default -> {
                            return;
                        }
                    }
                    MWLoadoutMod.LOGGER.info("[MWLoadout] Admin {} bulk {}: {} entries affected",
                            admin.getGameProfile().getName(), action, affected);
                }
                case "RULES" -> registry.setRules(Rules.fromJson(payload));
                case "SCANNER" -> {
                    if ("RECONCILE".equalsIgnoreCase(action)) {
                        dev.l41nx.mwloadout.registry.CreativeTabScanner.reconcile(admin.server, registry);
                    } else if ("CLEANSTALE".equalsIgnoreCase(action)) {
                        java.util.List<String> staleIds = new java.util.ArrayList<>();
                        for (CatalogEntry entry : registry.catalogList()) {
                            if (entry.stale) {
                                staleIds.add(entry.id);
                            }
                        }
                        int removed = registry.removeEntries(staleIds);
                        MWLoadoutMod.LOGGER.info("[MWLoadout] Admin {} cleared {} stale (ghost ID) catalog entries",
                                admin.getGameProfile().getName(), removed);
                    }
                }
                default -> {
                    return;
                }
            }
            registry.save();
            MWLoadoutMod.LOGGER.info("[MWLoadout] Admin {} {} {} applied", admin.getGameProfile().getName(), action, kind);
            for (ServerPlayer online : admin.server.getPlayerList().getPlayers()) {
                Net.syncRegistryTo(online);
            }
        } catch (Exception e) {
            MWLoadoutMod.LOGGER.error("[MWLoadout] Admin action {}/{} failed", kind, action, e);
        }
    }
}
