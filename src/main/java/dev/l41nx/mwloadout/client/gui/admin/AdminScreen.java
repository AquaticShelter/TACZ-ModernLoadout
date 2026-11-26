package dev.l41nx.mwloadout.client.gui.admin;

import dev.l41nx.mwloadout.client.gui.MWStyle;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole admin panel in one screen: SLOTS / CATALOG / PERKS / RULES tabs, each a selectable
 * list on the left and an edit form on the right. Every field the spec asks for is editable
 * without touching Java or JSON files by hand; SAVE ships a C2SAdminUpdate (permission re-checked
 * server-side), the server persists and re-syncs everyone, and this screen redraws from the
 * fresh registry.
 *
 * Form fields are string-based on purpose (lists as comma-separated, maps as k=v pairs, perk
 * effects as raw JSON): admins configuring a server are exactly the audience raw-but-visible
 * beats ten nested sub-dialogs for, and every value round-trips losslessly through the same
 * to/fromJson the files use. Malformed input falls back to the previous value rather than
 * crashing or sending garbage.
 */
public final class AdminScreen extends Screen {

    private enum Tab { SLOTS, CATALOG, PERKS, RULES, ADD, STATS }
    private enum StatsView { WEAPONS, PERKS, PLAYERS, CATEGORIES }
    private enum CatalogFilter { ALL, ACTIVE, INACTIVE, STALE }

    /** The perk balance numbers exposed as friendly quick-edit fields (PerkEffects.paramsForKey
     *  reads these at runtime). Shown only when the selected perk's special effect actually has
     *  that key, so each perk only ever shows the ones relevant to it. */
    private static final String[] QUICK_PARAM_KEYS = {
            "resistPercent", "speedPercent", "minPercent", "maxPercent",
            "durationSeconds", "level", "rangeBlocks", "coneDegrees", "durationMultiplier", "volumeMultiplier"
    };
    /** What each quick field showed when the form was built: on save only a field the admin actually CHANGED overrides the raw
     *  "effects" JSON (a stale quick field used to silently overwrite JSON edits - and truncated every value to an int). */
    private final Map<String, String> quickOriginal = new java.util.HashMap<>();

    private Tab tab = Tab.SLOTS;
    private StatsView statsView = StatsView.WEAPONS;
    private String statsHoveredKey = "";
    private boolean statsRequestedThisOpen = false;
    private String selectedId = "";
    private EditBox searchBox;
    private double listScroll;
    private double listScrollTarget;
    private CatalogFilter catalogFilter = CatalogFilter.ALL;
    private boolean scrollbarDragging;
    private long lastFrameMillis = System.currentTimeMillis();
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final Map<String, Boolean> toggles = new LinkedHashMap<>();
    private final List<ToggleRect> toggleRects = new ArrayList<>();
    /** Long forms (slots have 14+ fields) scroll instead of marching into the footer buttons —
     *  the v1.1.2 overlap. Wheel over the form pans; widgets are physically repositioned so
     *  hit-tests stay honest; footer stays pinned. */
    private double formScroll;
    private int formContentBottom;
    /** Auto-save: any field/toggle edit arms this; 700ms of quiet -> saveCurrent() fires by
     *  itself. NEW records (empty selection) never auto-save — half-typed ids must not become
     *  entries; the indicator says so. */
    private long dirtyAt;
    private EditBox quickIdBox;
    private EditBox quickSlotBox;
    private net.minecraft.client.gui.components.Button quickAddButton;
    private net.minecraft.client.gui.components.Button newButton;
    private net.minecraft.client.gui.components.Button saveButton;
    private net.minecraft.client.gui.components.Button deleteButton;
    private net.minecraft.client.gui.components.Button fromHandButton;
    private String flashMessage = "";
    private long flashUntil;
    private long autoSavedFlashUntil;

    // ---- v2 panel state: per-tab identity, sectioned forms, QoL ----
    private static final int LABEL_W = 128;
    private static final String LEGEND = "Ctrl+S Save  \u2022  Ctrl+N New  \u2022  Ctrl+D Duplicate  \u2022  Ctrl+R Revert  \u2022  Ctrl+F Search  \u2022  \u2191\u2193 Browse  \u2022  Ctrl+Click multi-select  \u2022  Ctrl+B Creative items  \u2022  Ctrl+1-6 Tabs";
    private long tabAnimStart = System.currentTimeMillis();
    private boolean tabAnimDone;
    private float tabIndX = -1, tabIndW;
    private double formScrollTarget;
    private long lastSavedAt;
    private long statsKpiAt;
    private int kpiKills;
    private float kpiHeadshot = -1f;
    private int kpiPlayers;
    private long searchApplyAt;       // >0 = a typed search is waiting to be applied (debounce)
    private long deleteArmedUntil;
    private String deleteArmedId = "";
    private boolean discardArmed;
    private long pendingSelectUntil;
    private String activeSection = "";
    /** Sections the admin collapsed (key = TAB:TITLE). PERKS' raw-JSON ADVANCED block starts folded. */
    private final java.util.Set<String> collapsedSections = new java.util.HashSet<>(java.util.Set.of("PERKS:ADVANCED"));
    private final List<FormRow> formRows = new ArrayList<>();
    private final List<HeaderRect> headerRects = new ArrayList<>();
    private final Map<String, Integer> sectionContentY = new LinkedHashMap<>();
    private final Map<String, Float> toggleAnim = new java.util.HashMap<>();
    private net.minecraft.client.gui.components.Button ksEditorButton;
    private net.minecraft.client.gui.components.Button reconcileButton;
    private net.minecraft.client.gui.components.Button cleanStaleButton;
    private net.minecraft.client.gui.components.Button browseButton;
    private net.minecraft.client.gui.components.Button bulkEnableBtn;
    private net.minecraft.client.gui.components.Button bulkDisableBtn;
    private net.minecraft.client.gui.components.Button bulkClearBtn;
    private net.minecraft.client.gui.components.Button bulkCategoryBtn;
    private net.minecraft.client.gui.components.Button bulkUnlockBtn;
    private net.minecraft.client.gui.components.Button bulkDeleteBtn;
    private EditBox bulkCategoryBox;
    private EditBox bulkUnlockBox;
    private final java.util.Set<String> bulkSelected = new java.util.LinkedHashSet<>();
    private int bulkAnchor = -1;
    private long bulkDeleteArmedUntil;

    private boolean autoSaveEligible() {
        return tab == Tab.RULES || !selectedId.isEmpty();
    }

    private void markDirty() {
        dirtyAt = System.currentTimeMillis();
        discardArmed = false;
    }

    private int listX, listWidth, listTop, listBottom;
    private int formX, formTop;

    public AdminScreen() {
        super(Component.translatable("screen.mwloadout.admin.title"));
    }

    @Override
    protected void init() {
        listX = 10;
        listWidth = 170;
        listTop = 74;
        listBottom = height - 60;
        formX = listX + listWidth + 20; // 8px gutter between the list panel and the form panel
        formTop = 74;

        searchBox = new EditBox(font, listX, 54, listWidth - 30, 14, Component.empty());
        searchBox.setBordered(false);
        searchBox.setTextColor(MWStyle.TEXT);
        searchBox.setHint(Component.translatable("screen.mwloadout.search"));
        searchBox.setResponder(text -> searchApplyAt = System.currentTimeMillis() + 110); // debounced in render()
        addRenderableWidget(searchBox);

        int footerY = height - 30;
        newButton = MWStyle.flatButton(formX, footerY, 70, 20,
                Component.translatable("screen.mwloadout.admin.new"), button -> startNew());
        addRenderableWidget(newButton);
        fromHandButton = MWStyle.flatButton(formX + 228, footerY, 84, 20,
                Component.translatable("screen.mwloadout.admin.from_hand"), button -> fillFromHand());
        addRenderableWidget(fromHandButton);
        saveButton = ((MWStyle.FlatButton) MWStyle.flatButton(formX + 76, footerY, 70, 20,
                Component.translatable("screen.mwloadout.save"), button -> saveCurrent())).accent();
        addRenderableWidget(saveButton);
        deleteButton = MWStyle.flatButton(formX + 152, footerY, 70, 20,
                Component.translatable("screen.mwloadout.admin.delete"), button -> requestDelete());
        addRenderableWidget(deleteButton);
        addRenderableWidget(MWStyle.flatButton(width - 100, footerY, 90, 20,
                Component.translatable("screen.mwloadout.close"), button -> onClose()));

        // Tab-specific footer buttons are created ONCE and shown/hidden per tab in render().
        // (They used to be created only when init() happened to run on that tab — which only
        // occurs at open / resize — so the killstreak editor and RECONCILE / CLEAN STALE buttons
        // never appeared after clicking the tab. That was a real "panel unstable" bug.)
        ksEditorButton = MWStyle.flatButton(width - 200, footerY, 94, 20,
                Component.translatable("screen.mwloadout.ks_editor_btn"),
                button -> minecraft.setScreen(new dev.l41nx.mwloadout.client.gui.KillstreakWeaponScreen(this, true)));
        addRenderableWidget(ksEditorButton);
        reconcileButton = MWStyle.flatButton(width - 300, footerY, 94, 20,
                Component.translatable("screen.mwloadout.admin.reconcile"), button -> {
                    Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate("SCANNER", "RECONCILE", "{}"));
                    MWStyle.playClick();
                });
        addRenderableWidget(reconcileButton);
        cleanStaleButton = MWStyle.flatButton(width - 200, footerY, 94, 20,
                Component.translatable("screen.mwloadout.admin.clean_stale"), button -> {
                    Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate("SCANNER", "CLEANSTALE", "{}"));
                    MWStyle.playClick();
                });
        addRenderableWidget(cleanStaleButton);

        // ADD-tab widgets: laid out in the form column with clear boxed fields, never over the
        // CATALOG search bar. Boxed (setBordered true) so they read as real input fields.
        quickIdBox = new EditBox(font, formX, 106, Math.min(360, width - formX - 18), 18, Component.empty());
        quickIdBox.setBordered(true);
        quickIdBox.setTextColor(MWStyle.TEXT);
        quickIdBox.setHint(Component.translatable("screen.mwloadout.admin.quick_id_hint"));
        quickIdBox.setMaxLength(256);
        addRenderableWidget(quickIdBox);

        quickSlotBox = new EditBox(font, formX, 140, 150, 18, Component.empty());
        quickSlotBox.setBordered(true);
        quickSlotBox.setTextColor(MWStyle.TEXT);
        quickSlotBox.setHint(Component.translatable("screen.mwloadout.admin.quick_slot_hint"));
        quickSlotBox.setValue("extra3");
        addRenderableWidget(quickSlotBox);

        quickAddButton = MWStyle.flatButton(formX + 156, 139, 90, 20,
                Component.translatable("screen.mwloadout.admin.quick_add"), button -> quickAddItem());
        addRenderableWidget(quickAddButton);

        browseButton = ((MWStyle.FlatButton) MWStyle.flatButton(formX, 224, Math.min(360, width - formX - 18), 24,
                Component.literal("BROWSE CREATIVE ITEMS  (Ctrl+B)"),
                button -> minecraft.setScreen(new ItemBrowserScreen(this)))).accent();
        addRenderableWidget(browseButton);

        // Bulk-edit panel (CATALOG, shown while rows are multi-selected).
        bulkEnableBtn = MWStyle.flatButton(formX, 104, 96, 20, Component.literal("ENABLE ALL"), button -> bulkApply("ENABLE", null, "Enabled"));
        bulkDisableBtn = MWStyle.flatButton(formX + 102, 104, 96, 20, Component.literal("DISABLE ALL"), button -> bulkApply("DISABLE", null, "Disabled"));
        bulkClearBtn = MWStyle.flatButton(formX + 204, 104, 96, 20, Component.literal("DESELECT"), button -> {
            bulkSelected.clear();
            layoutForm();
        });
        bulkCategoryBox = new EditBox(font, formX, 142, 150, 18, Component.empty());
        bulkCategoryBox.setBordered(true);
        bulkCategoryBox.setTextColor(MWStyle.TEXT);
        bulkCategoryBox.setHint(Component.literal("category, e.g. sr"));
        bulkCategoryBtn = MWStyle.flatButton(formX + 156, 141, 110, 20, Component.literal("MOVE CATEGORY"), button -> {
            String category = bulkCategoryBox.getValue().trim().toLowerCase(Locale.ROOT);
            if (category.isEmpty()) {
                flash("\u00A7cENTER A CATEGORY");
                return;
            }
            JsonObject extra = new JsonObject();
            extra.addProperty("category", category);
            bulkApply("CATEGORY", extra, "Moved to " + category);
        });
        bulkUnlockBox = new EditBox(font, formX, 176, 150, 18, Component.empty());
        bulkUnlockBox.setBordered(true);
        bulkUnlockBox.setTextColor(MWStyle.TEXT);
        bulkUnlockBox.setHint(Component.literal("unlock level, 0 = free"));
        bulkUnlockBtn = MWStyle.flatButton(formX + 156, 175, 110, 20, Component.literal("SET UNLOCK"), button -> {
            int level;
            try {
                level = Integer.parseInt(bulkUnlockBox.getValue().trim());
            } catch (NumberFormatException e) {
                flash("\u00A7cLEVEL MUST BE A NUMBER");
                return;
            }
            JsonObject extra = new JsonObject();
            extra.addProperty("level", Math.max(0, level));
            bulkApply("UNLOCK", extra, "Unlock set");
        });
        bulkDeleteBtn = MWStyle.flatButton(formX, 214, 200, 22, Component.literal("DELETE"), button -> {
            long now = System.currentTimeMillis();
            if (now < bulkDeleteArmedUntil) {
                bulkApply("DELETE", null, "Deleted");
                bulkSelected.clear();
                bulkDeleteArmedUntil = 0;
                layoutForm();
            } else {
                bulkDeleteArmedUntil = now + 3000;
                flash("\u00A7eCLICK AGAIN TO DELETE " + bulkSelected.size());
            }
        });
        for (var widget : List.of(bulkEnableBtn, bulkDisableBtn, bulkClearBtn, bulkCategoryBtn, bulkUnlockBtn, bulkDeleteBtn)) {
            addRenderableWidget(widget);
        }
        addRenderableWidget(bulkCategoryBox);
        addRenderableWidget(bulkUnlockBox);

        rebuildList();
        rebuildForm();
    }

    /** Frictionless add-by-id: type an item id (minecraft:pufferfish) + a slot category
     *  (extra3), press ADD. Builds a plain ITEM entry, ships it, clears the box and flashes a
     *  confirmation — ready for the next one. Only meaningful on the CATALOG tab. */
    private void quickAddItem() {
        if (tab != Tab.ADD) {
            return;
        }
        String itemId = quickIdBox.getValue().trim();
        String slotCategory = quickSlotBox.getValue().trim().isEmpty() ? "extra3" : quickSlotBox.getValue().trim();
        if (itemId.isEmpty()) {
            flash("\u00A7cENTER AN ITEM ID");
            return;
        }
        if (!itemId.contains(":")) {
            itemId = "minecraft:" + itemId;
        }
        // Validate against the item registry so a typo doesn't create a ghost entry.
        var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (rl == null || !net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(rl)) {
            flash("\u00A7cUNKNOWN ITEM  " + itemId);
            return;
        }
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
        String niceName = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
        String entryId = "item_" + rl.toString().replace(':', '_');

        JsonObject payload = new JsonObject();
        payload.addProperty("id", entryId);
        payload.addProperty("displayName", niceName);
        payload.addProperty("category", slotCategory);
        payload.addProperty("gameId", itemId);
        payload.addProperty("nbt", "");
        payload.addProperty("count", 1);
        payload.addProperty("kind", "ITEM");
        payload.addProperty("enabled", true);
        Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate("ENTRY", "UPSERT", Json.GSON.toJson(payload)));

        flash("\u00A7a+ " + niceName + " \u2192 " + slotCategory);
        quickIdBox.setValue(""); // ready for the next item
        quickIdBox.setFocused(true);
    }

    private void flash(String message) {
        flashMessage = message;
        flashUntil = System.currentTimeMillis() + 2600;
    }

    // ---------------------------------------------------------------- data plumbing

    private void rebuildList() {
        double keepScroll = listScrollTarget;
        rows.clear();
        String query = searchBox == null ? "" : searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        switch (tab) {
            case SLOTS -> LoadoutRegistry.get().slotList().stream()
                    .sorted(java.util.Comparator.comparingInt(s -> s.hotbarIndex))
                    .forEach(slot ->
                    addRow(slot.id, slot.displayName + "  [" + (slot.hotbarIndex + 1) + "]" + (slot.enabled ? "" : " (off)"), query));
            case CATALOG -> LoadoutRegistry.get().catalogList().stream()
                    .filter(entry -> switch (catalogFilter) {
                        case ALL -> true;
                        case ACTIVE -> entry.enabled && !entry.stale;
                        case INACTIVE -> !entry.enabled && !entry.stale;
                        case STALE -> entry.stale;
                    })
                    .forEach(entry ->
                    addRow(entry.id, dev.l41nx.mwloadout.client.gui.DisplayNames.of(entry)
                            + "  <" + entry.category + ">" + (entry.enabled ? "" : " (off)"), query));
            case PERKS -> LoadoutRegistry.get().perkList().forEach(perk ->
                    addRow(perk.id, perk.displayName + "  <" + perk.perkSlot + ">", query));
            case RULES -> sectionsFor(Tab.RULES).forEach(section -> addRow("sec:" + section.title(), section.title(), ""));
            case ADD -> LoadoutRegistry.get().catalogList().forEach(entry ->
                    addRow(entry.id, dev.l41nx.mwloadout.client.gui.DisplayNames.of(entry)
                            + "  <" + entry.category + ">", query));
        }
        listScrollTarget = keepScroll; // preserved across refreshes — no more jump-to-top
    }

    private void addRow(String id, String label, String query) {
        if (query.isEmpty() || id.toLowerCase(Locale.ROOT).contains(query) || label.toLowerCase(Locale.ROOT).contains(query)) {
            rows.add(new Row(id, label));
        }
    }

    private void rebuildForm() {
        dirtyAt = 0; // a freshly populated form is not "edited"
        // Remove old field widgets before rebuilding.
        fields.values().forEach(this::removeWidget);
        fields.clear();
        toggles.clear();
        toggleRects.clear();

        switch (tab) {
            case SLOTS -> {
                SlotDefinition slot = LoadoutRegistry.get().slot(selectedId);
                if (slot == null) {
                    slot = new SlotDefinition();
                }
                field("id", slot.id);
                field("hotbarIndex", String.valueOf(slot.hotbarIndex));
                field("displayName", slot.displayName);
                field("description", slot.description);
                field("iconItem", slot.iconItem);
                field("allowedCategories", String.join(",", slot.allowedCategories));
                field("whitelist", String.join(",", slot.whitelist));
                field("blacklist", String.join(",", slot.blacklist));
                field("maxCount", String.valueOf(slot.maxCount));
                field("defaultEntry", slot.defaultEntry);
                toggle("required", slot.required);
                toggle("locked", slot.locked);
                toggle("hidden", slot.hidden);
                toggle("enabled", slot.enabled);
            }
            case CATALOG -> {
                CatalogEntry entry = LoadoutRegistry.get().entry(selectedId);
                if (entry == null) {
                    entry = new CatalogEntry();
                }
                field("id", entry.id);
                field("displayName", entry.displayName);
                field("category", entry.category);
                field("gameId", entry.gameId);
                // ATTACHMENT entries are scanner-managed (id/category/gameId are
                // TACZ's own attachment id, not admin-editable data) — nbt/count/gun
                // stay hidden for them. unlockLevel is the exception (v5.2.0): it's now meaningful
                // there too — the per-weapon level required to equip this attachment at all (see
                // Rules.weaponProgressionEnabled) — so it's exposed for every kind, same field an
                // admin already uses to level-gate a whole GUN.
                if (entry.kind != CatalogEntry.Kind.ATTACHMENT) {
                    field("nbt", entry.nbt);
                    field("count", String.valueOf(entry.count));
                    toggle("gun", entry.kind == CatalogEntry.Kind.GUN);
                }
                field("unlockLevel", String.valueOf(entry.unlockLevel));
                toggle("enabled", entry.enabled);
                if (entry.kind == CatalogEntry.Kind.GUN) {
                    // Exclusive test weapon: invisible to non-OP players entirely (never even
                    // sent to their client), OP sees/uses it normally, excluded from random-fill.
                    toggle("adminOnly", entry.adminOnly);
                    // This weapon's own ammo reserve multiplier — wins over Rules.categoryMultipliers
                    // and the dynamicAmmoTiers table both (see Rules.effectiveAmmoMultiplierFor).
                    // 0 = not set, falls through to category then tier as normal. Only matters
                    // when Rules.dynamicAmmoEnabled is on.
                    field("ammoMultiplier", String.valueOf(entry.ammoMultiplier));
                }
                if (entry.kind == CatalogEntry.Kind.ITEM) {
                    field("cooldownSeconds", String.valueOf(entry.cooldownSeconds)); // FIELD UPGRADE slot recharge override
                    toggle("cooldownOnClick", entry.cooldownOnClick);
                }
                if (entry.kind == CatalogEntry.Kind.GUN) {
                    // Blueprint platform (MW2022-style): mark this gun as owning a set of
                    // alternate "forms" — other TACZ gun ids (any installed addon pack, same
                    // family/platform, different model) that get hidden from normal slot pickers
                    // and instead become switchable from THIS gun's Gunsmith. Comma-separated
                    // TACZ gameIds (e.g. tacz:g36k, sigma:g36c) — NOT this mod's own catalog ids.
                    // Always created (not gated on the toggle) so layoutForm() can show/hide it
                    // live the instant the toggle is clicked, no reopen needed.
                    toggle("hasBlueprint", entry.hasBlueprint);
                    field("blueprintGameIds", String.join(",", entry.blueprintIds));
                }
            }
            case PERKS -> {
                PerkDefinition perk = LoadoutRegistry.get().perk(selectedId);
                if (perk == null) {
                    perk = new PerkDefinition();
                }
                field("id", perk.id);
                field("displayName", perk.displayName);
                field("description", perk.description);
                field("icon", perk.icon);
                field("perkSlot", perk.perkSlot);
                JsonArray effectsArray = new JsonArray();
                perk.effects.forEach(effectsArray::add);
                field("effects", Json.GSON.toJson(effectsArray));
                // Friendly number fields for the perk's own tunable balance numbers — no JSON
                // typing needed. Only the keys THIS perk's special effect actually has show up
                // (e.g. Shrapnel gets resistPercent; Fragiles gets durationSeconds + level).
                // These take priority over the raw "effects" box above on save.
                JsonObject special = firstSpecialEffect(perk);
                quickOriginal.clear();
                if (special != null) {
                    for (String key : QUICK_PARAM_KEYS) {
                        if (special.has(key)) {
                            String shown = formatQuick(Json.doubleValue(special, key, 0));
                            quickOriginal.put(key, shown);
                            field(key, shown);
                        }
                    }
                }
                toggle("enabled", perk.enabled);
            }
            case RULES -> {
                Rules rules = LoadoutRegistry.get().rules();
                field("editSecondsAfterRespawn", String.valueOf(rules.editSecondsAfterRespawn));
                field("noDamageSeconds", String.valueOf(rules.noDamageSeconds));
                field("useLockSeconds", String.valueOf(rules.useLockSeconds));
                field("fieldUpgradeCooldownSeconds", String.valueOf(rules.fieldUpgradeCooldownSeconds));
                field("maxPresets", String.valueOf(rules.maxPresets));
                field("minPerksRequired", String.valueOf(rules.minPerksRequired));
                field("perkSlots", joinPerkSlots(rules.perkSlots));
                field("ammoByCategory", joinMap(rules.ammoByCategory));
                field("dynamicAmmoTiers", joinAmmoTiers(rules.dynamicAmmoTiers));
                field("fullyLoadedMultiplier", String.valueOf(rules.fullyLoadedMultiplier));
                field("categoryMultipliers", joinDoubleMap(rules.categoryMultipliers));
                JsonArray zonesArray = new JsonArray();
                rules.editZones.forEach(zone -> zonesArray.add(zone.toJson()));
                field("editZones", Json.GSON_COMPACT.toJson(zonesArray));
                field("xpPerKill", String.valueOf(rules.xpPerKill));
                field("xpHeadshotKill", String.valueOf(rules.xpHeadshotKill));
                field("xpPerLevel", String.valueOf(rules.xpPerLevel));
                field("xpMultiplier", String.valueOf(rules.xpMultiplier));
                // XP curve growth + per-weapon leveling (see Rules.java doc).
                field("xpCurveGrowth", String.valueOf(rules.xpCurveGrowth));
                field("weaponXpPerKill", String.valueOf(rules.weaponXpPerKill));
                field("weaponXpHeadshotKill", String.valueOf(rules.weaponXpHeadshotKill));
                field("weaponXpPerLevel", String.valueOf(rules.weaponXpPerLevel));
                field("weaponXpCurveGrowth", String.valueOf(rules.weaponXpCurveGrowth));
                field("attachmentPointLimit", String.valueOf(rules.attachmentPointLimit));
                field("attachmentPointCost", String.valueOf(rules.attachmentPointCost));
                field("spawnZoneRadius", String.valueOf(rules.spawnZoneRadius));
                field("streakRewards", joinStreak(rules.streakRewards));
                field("specialistPerkPool", String.join(", ", rules.specialistPerkPool));
                // Source / PVP Only Mode / Hostile Mob Multiplier.
                field("hostileMobMultiplier", String.valueOf(rules.hostileMobMultiplier));
                toggle("killstreakEnabled", rules.killstreakEnabled);
                toggle("streakResetOnDeath", rules.streakResetOnDeath);
                toggle("specialistEnabled", rules.specialistEnabled);
                toggle("killstreakSourceRestricted", rules.killstreakSourceRestricted);
                toggle("killstreakPvpOnly", rules.killstreakPvpOnly);
                toggle("streakIgnoreListedSources", rules.streakIgnoreListedSources);
                toggle("streakIgnoreStrikeMods", rules.streakIgnoreStrikeMods);
                field("streakExcludedIds", String.join(", ", rules.streakExcludedIds));
                toggle("peacefulAlwaysEditable", rules.peacefulAlwaysEditable);
                toggle("restockOnRespawn", rules.restockOnRespawn);
                toggle("preventDropManaged", rules.preventDropManaged);
                toggle("restrictOpAndCreative", rules.restrictOpAndCreative);
                toggle("requireZoneToEquip", rules.requireZoneToEquip);
                toggle("antiSpamRefillEnabled", rules.antiSpamRefillEnabled);
                toggle("useLockEnabled", rules.useLockEnabled);
                toggle("progressionEnabled", rules.progressionEnabled);
                toggle("weaponProgressionEnabled", rules.weaponProgressionEnabled);
                toggle("dynamicAmmoEnabled", rules.dynamicAmmoEnabled);
                toggle("dualAmmoSlot", rules.dualAmmoSlot);
            }
            case ADD -> { }
        }

        formScroll = 0;
        formScrollTarget = 0;
        layoutForm();
    }

    // ---------------------------------------------------------------- form layout v2

    private record Section(String title, String[] names) {
    }

    private record FormRow(int type, String name, int x, int y, int count, boolean collapsed) {
    }

    private record HeaderRect(String key, int x, int y, int w, int h) {
    }

    private static Section sec(String title, String... names) {
        return new Section(title, names);
    }

    /** Ordered, titled groups for each editable tab. Anything the form has that isn't listed lands
     *  in a trailing OTHER section, so adding a field to rebuildForm() can never make it vanish. */
    private List<Section> sectionsFor(Tab t) {
        return switch (t) {
            case SLOTS -> List.of(
                    sec("IDENTITY", "id", "displayName", "description", "iconItem"),
                    sec("PLACEMENT", "hotbarIndex", "maxCount", "defaultEntry"),
                    sec("ALLOWED ITEMS", "allowedCategories", "whitelist", "blacklist"),
                    sec("BEHAVIOUR", "required", "locked", "hidden", "enabled"));
            case CATALOG -> List.of(
                    sec("ITEM", "id", "displayName", "category", "gameId", "nbt", "count"),
                    sec("ACCESS", "unlockLevel", "enabled", "adminOnly", "cooldownSeconds", "cooldownOnClick"),
                    sec("GUN", "gun", "ammoMultiplier"),
                    sec("BLUEPRINTS", "hasBlueprint", "blueprintGameIds"));
            case PERKS -> List.of(
                    sec("IDENTITY", "id", "displayName", "description", "icon"),
                    sec("PLACEMENT", "perkSlot", "enabled"),
                    sec("BALANCE", QUICK_PARAM_KEYS),
                    sec("ADVANCED", "effects"));
            case RULES -> List.of(
                    sec("GAMEPLAY", "editSecondsAfterRespawn", "noDamageSeconds", "restockOnRespawn", "preventDropManaged",
                            "peacefulAlwaysEditable", "restrictOpAndCreative", "antiSpamRefillEnabled"),
                    sec("ANTI-ABUSE", "useLockEnabled", "useLockSeconds"),
                    sec("FIELD UPGRADES", "fieldUpgradeCooldownSeconds"),
                    sec("CLASSES", "maxPresets", "minPerksRequired", "perkSlots", "attachmentPointLimit", "attachmentPointCost"),
                    sec("ZONES", "requireZoneToEquip", "spawnZoneRadius", "editZones"),
                    sec("AMMO", "dynamicAmmoEnabled", "dualAmmoSlot", "ammoByCategory", "dynamicAmmoTiers", "fullyLoadedMultiplier", "categoryMultipliers"),
                    sec("PLAYER LEVELS", "progressionEnabled", "xpPerKill", "xpHeadshotKill", "xpPerLevel", "xpMultiplier", "xpCurveGrowth"),
                    sec("WEAPON LEVELS", "weaponProgressionEnabled", "weaponXpPerKill", "weaponXpHeadshotKill", "weaponXpPerLevel", "weaponXpCurveGrowth"),
                    sec("KILLSTREAKS", "killstreakEnabled", "streakResetOnDeath", "specialistEnabled", "killstreakSourceRestricted", "killstreakPvpOnly",
                            "hostileMobMultiplier", "streakIgnoreListedSources", "streakIgnoreStrikeMods", "streakExcludedIds", "streakRewards", "specialistPerkPool"));
            default -> List.of();
        };
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("id", "ID"), Map.entry("displayName", "Display Name"), Map.entry("description", "Description"),
            Map.entry("iconItem", "Icon Item"), Map.entry("hotbarIndex", "Hotbar Slot (0-8)"), Map.entry("maxCount", "Max Count"),
            Map.entry("defaultEntry", "Default Entry"), Map.entry("allowedCategories", "Categories"), Map.entry("whitelist", "Whitelist"),
            Map.entry("blacklist", "Blacklist"), Map.entry("category", "Category"), Map.entry("gameId", "Game ID"),
            Map.entry("nbt", "NBT"), Map.entry("count", "Count"), Map.entry("unlockLevel", "Unlock Level"), Map.entry("adminOnly", "Admin Only"), Map.entry("cooldownSeconds", "Field Cooldown (s)"), Map.entry("cooldownOnClick", "Recharge On Click"), Map.entry("fieldUpgradeCooldownSeconds", "Field Upgrade (s)"),
            Map.entry("gun", "Is Gun"), Map.entry("ammoMultiplier", "Ammo Multiplier"), Map.entry("hasBlueprint", "Has Blueprints"),
            Map.entry("blueprintGameIds", "Blueprint Guns"), Map.entry("icon", "Icon"), Map.entry("perkSlot", "Perk Column"),
            Map.entry("effects", "Effects (JSON)"), Map.entry("resistPercent", "Resist %"), Map.entry("speedPercent", "Speed %"),
            Map.entry("minPercent", "Min %"), Map.entry("maxPercent", "Max %"), Map.entry("durationSeconds", "Duration (s)"),
            Map.entry("level", "Level"), Map.entry("rangeBlocks", "Range (blocks)"), Map.entry("coneDegrees", "Cone (deg)"), Map.entry("durationMultiplier", "Duration x (0-1)"), Map.entry("volumeMultiplier", "Volume x (0-1)"),
            Map.entry("editSecondsAfterRespawn", "Edit Window (s)"), Map.entry("noDamageSeconds", "Spawn Protect (s)"),
            Map.entry("maxPresets", "Max Classes"), Map.entry("minPerksRequired", "Min Perks"), Map.entry("perkSlots", "Perk Columns"),
            Map.entry("ammoByCategory", "Reserve / Category"), Map.entry("dynamicAmmoTiers", "Ammo Tiers"),
            Map.entry("fullyLoadedMultiplier", "Fully Loaded x"), Map.entry("categoryMultipliers", "Category x"),
            Map.entry("editZones", "Edit Zones (JSON)"), Map.entry("xpPerKill", "XP / Kill"), Map.entry("xpHeadshotKill", "XP / Headshot"),
            Map.entry("xpPerLevel", "XP / Level"), Map.entry("xpMultiplier", "XP Multiplier"), Map.entry("xpCurveGrowth", "Curve Growth"),
            Map.entry("weaponXpPerKill", "Wpn XP / Kill"), Map.entry("weaponXpHeadshotKill", "Wpn XP / Headshot"),
            Map.entry("weaponXpPerLevel", "Wpn XP / Level"), Map.entry("weaponXpCurveGrowth", "Wpn Curve Growth"),
            Map.entry("attachmentPointLimit", "Attach. Budget"), Map.entry("attachmentPointCost", "Cost / Attach."),
            Map.entry("spawnZoneRadius", "Spawn Zone Radius"), Map.entry("streakRewards", "Streak Rewards"),
            Map.entry("specialistPerkPool", "Specialist Perks"), Map.entry("hostileMobMultiplier", "Mob Kill Weight"),
            Map.entry("killstreakEnabled", "Killstreaks"), Map.entry("streakResetOnDeath", "Reset On Death"), Map.entry("specialistEnabled", "Specialist"),
            Map.entry("killstreakSourceRestricted", "Hostile/Player Only"), Map.entry("killstreakPvpOnly", "PvP Only"),
            Map.entry("streakIgnoreListedSources", "Ignore Streak Sources"), Map.entry("streakIgnoreStrikeMods", "Ignore Vehicle/Strike Mods"), Map.entry("streakExcludedIds", "Extra Excluded Ids"),
            Map.entry("peacefulAlwaysEditable", "Peaceful = Editable"), Map.entry("restockOnRespawn", "Restock On Respawn"),
            Map.entry("preventDropManaged", "Block Dropping"), Map.entry("restrictOpAndCreative", "Restrict OP/Creative"),
            Map.entry("useLockEnabled", "Use Lock"), Map.entry("useLockSeconds", "Use Lock (s, 0=respawn)"),
            Map.entry("requireZoneToEquip", "Require Zone"), Map.entry("antiSpamRefillEnabled", "Anti-Spam Refill"),
            Map.entry("progressionEnabled", "Player Levels"), Map.entry("weaponProgressionEnabled", "Weapon Levels"),
            Map.entry("dynamicAmmoEnabled", "Dynamic Ammo"), Map.entry("dualAmmoSlot", "Ammo Type Slot"));

    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("id", "Unique key. Changing the ID of an existing record saves it as a NEW record (use Duplicate for copies)."),
            Map.entry("hotbarIndex", "Position in the hotbar: 0 = first slot, 8 = last."),
            Map.entry("allowedCategories", "Catalog categories this slot may hold, comma-separated (e.g. ar,smg,pistol)."),
            Map.entry("whitelist", "If filled, ONLY these catalog ids can be picked for this slot. Comma-separated."),
            Map.entry("blacklist", "These catalog ids are never offered in this slot. Comma-separated."),
            Map.entry("defaultEntry", "Catalog id handed out when the player has not chosen (or the slot is locked)."),
            Map.entry("maxCount", "Largest stack size a player can take for this slot."),
            Map.entry("required", "The player must fill this slot before a class can be saved."),
            Map.entry("locked", "Slot always uses its default entry - players cannot change it."),
            Map.entry("hidden", "Hide this slot from the player's loadout screen."),
            Map.entry("category", "Slot category string. Match it in a slot's Categories field to make the entry selectable there."),
            Map.entry("gameId", "TACZ gun / attachment id, or an item id (namespace:name)."),
            Map.entry("nbt", "Optional item NBT in SNBT form. Leave empty for a plain item."),
            Map.entry("unlockLevel", "Level required: player level for guns/items, that weapon's level for attachments. 0 = free."),
            Map.entry("adminOnly", "Test weapon: invisible to non-OP players and excluded from random fill."),
            Map.entry("cooldownOnClick", "For reusable FIELD UPGRADE tools that are not consumed: start the recharge on right-click. Leave OFF for placed / thrown / consumed items - they recharge when actually spent."),
            Map.entry("cooldownSeconds", "Recharge when this item sits in a FIELD UPGRADE slot. 0 = use the rule default (RULES > Field Upgrade). Tune Up shortens it by 40%."),
            Map.entry("fieldUpgradeCooldownSeconds", "Default recharge of field upgrades in seconds (80 = 1m20s). A freshly equipped one must charge before first use; ready ones stay ready across class swaps. 0 = no recharge."),
            Map.entry("ammoMultiplier", "Reserve-ammo multiplier for just this gun. 0 = fall back to category / tier rules."),
            Map.entry("hasBlueprint", "This gun owns alternate forms that players switch between in its Gunsmith."),
            Map.entry("blueprintGameIds", "TACZ gun ids (e.g. tacz:g36k) - NOT catalog ids. Comma-separated; validated live below."),
            Map.entry("perkSlot", "Perk column this perk appears in (ids come from RULES > Perk Columns)."),
            Map.entry("effects", "Raw effect JSON. When the BALANCE fields above exist, they win on save."),
            Map.entry("editSecondsAfterRespawn", "Seconds after respawning during which a player may edit their class."),
            Map.entry("noDamageSeconds", "Spawn-protection duration in seconds."),
            Map.entry("maxPresets", "Saved classes per player (hard-capped at 20)."),
            Map.entry("minPerksRequired", "Perk columns that must be filled before a class can be saved."),
            Map.entry("perkSlots", "Perk columns as id:Display Name pairs, comma-separated."),
            Map.entry("ammoByCategory", "category=rounds, comma-separated (e.g. ar=120,sr=25). Used when Dynamic Ammo is OFF."),
            Map.entry("dynamicAmmoTiers", "min,max,category,multiplier - tiers separated by ';'. Blank max = no ceiling, blank category = all. e.g. 0,9,sg,8.0;50,,,2.0"),
            Map.entry("fullyLoadedMultiplier", "Fully Loaded perk multiplier. STACKS on top of the gun's tier multiplier."),
            Map.entry("categoryMultipliers", "category=multiplier, comma-separated. Overrides the tier table for that category."),
            Map.entry("editZones", "JSON array of zones where players may edit. Leave '[]' for everywhere."),
            Map.entry("requireZoneToEquip", "Players must stand in an edit zone to equip a class."),
            Map.entry("spawnZoneRadius", "Radius in blocks of the automatic spawn edit zone."),
            Map.entry("xpMultiplier", "Multiplies every XP grant (2.0 = double-XP event)."),
            Map.entry("xpCurveGrowth", "1.0 = every level costs the same. 1.03-1.08 = gentle ramp; much higher explodes fast."),
            Map.entry("weaponXpCurveGrowth", "Same idea as the player curve, applied to per-weapon levels."),
            Map.entry("attachmentPointLimit", "Total attachment budget per gun. 0 = unlimited."),
            Map.entry("attachmentPointCost", "Budget each attachment costs (100 / 20 = the classic 5 attachments)."),
            Map.entry("streakRewards", "kills=ITEM pairs separated by ';' - ANY item: a gun (5=tacz:rpg7), a mod item (7=ts:flare), a catalog id. A gun can carry its ammo as ITEM|bullets/reserve (5=tacz:rpg7|1/2). Easier: use the KILLSTREAK EDITOR button."),
            Map.entry("specialistPerkPool", "Perk ids that can be picked once Specialist is active. Comma-separated."),
            Map.entry("hostileMobMultiplier", "How many mob kills equal one player kill of streak progress."),
            Map.entry("killstreakEnabled", "Master switch for the whole killstreak system."),
            Map.entry("streakResetOnDeath", "Dying resets the streak counter. Rewards you already earned and have not used are KEPT: carried ones come back after respawn, queued ones stay queued (hardcore wipe: set streakItemsLostOnDeath in the config). A Killchain-saved death keeps the counter too."),
            Map.entry("specialistEnabled", "Master switch for Specialist (bonus perks after a long streak)."),
            Map.entry("killstreakSourceRestricted", "Only hostile-mob or player kills count toward streaks."),
            Map.entry("killstreakPvpOnly", "Stricter: only player kills count, no mobs at all."),
            Map.entry("streakIgnoreListedSources", "Kills made WITH a streak reward, or BY what it spawns (rocket, drone, grenade, bullet), never advance the streak. ON: every item, TACZ gun and entity id in the streak list counts as a streak source, even if the same weapon is also in the normal catalog. OFF: only the stacks handed out as rewards."),
            Map.entry("streakIgnoreStrikeMods", "Kills by SuperbWarfare (items, vehicles, turrets, superbwarfare:projectile shots), Airstrike and AC-130 never advance the streak."),
            Map.entry("streakExcludedIds", "Extra item / TACZ gun / entity ids that never feed the streak, comma-separated, e.g. superbwarfare:rpg, superbwarfare:*  (a trailing * is a prefix). An item id also covers entities named the same, like rpg_rocket."),
            Map.entry("peacefulAlwaysEditable", "Players can always edit their class on Peaceful difficulty."),
            Map.entry("restockOnRespawn", "Re-issue the loadout and ammo on every respawn."),
            Map.entry("preventDropManaged", "Players cannot drop loadout-managed items."),
            Map.entry("restrictOpAndCreative", "Apply loadout restrictions to OPs and creative players too."),
            Map.entry("antiSpamRefillEnabled", "Rate-limit refills to stop respawn/ammo spamming."),
            Map.entry("useLockEnabled", "After a non-OP player fires a loadout gun or throws/eats/spends a loadout item, they cannot re-equip a loadout until the lock clears - stops swap-and-spam. Also applies in spawn/edit zones."),
            Map.entry("useLockSeconds", "How long the Use Lock lasts after the LAST use. 0 = locked until the player respawns."),
            Map.entry("progressionEnabled", "Master switch for player levels / XP (level-gating uses Unlock Level)."),
            Map.entry("weaponProgressionEnabled", "Master switch for per-gun levels that gate attachments."),
            Map.entry("dynamicAmmoEnabled", "Compute reserve ammo from each gun's magazine size x tier. OFF = flat per-category table."),
            Map.entry("dualAmmoSlot", "Gunsmith: TACZ 1.2.0 ammo mods get their own AMMO TYPE slot next to the MAGAZINE slot (both can be fitted, each counts as an attachment). Needs the mod's mixin; OFF = one shared slot like plain TACZ."));

    private static String labelOf(String name) {
        String label = LABELS.get(name);
        if (label != null) {
            return label;
        }
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return spaced.isEmpty() ? name : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Bottom dock, top to bottom: panels end at formBottom(); the HINT / TOOLS row sits right under
     *  them (hint text on the left, maintenance buttons on the right); the action row (NEW SAVE
     *  DELETE ... CLOSE) is last. Fixed rows = nothing can land on top of anything else. */
    private int formBottom() {
        return height - 60;
    }

    private int toolsRightEdge() {
        return tab == Tab.CATALOG ? width - 208 : tab == Tab.RULES ? width - 104 : width - 10;
    }

    private void layoutFooterButtons() {
        if (ksEditorButton == null) {
            return;
        }
        int y = height - 56;
        cleanStaleButton.setX(width - 104);
        cleanStaleButton.setY(y);
        reconcileButton.setX(width - 204);
        reconcileButton.setY(y);
        ksEditorButton.setX(width - 104);
        ksEditorButton.setY(y);
    }

    // ---- boxed layout helpers

    private void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        MWStyle.fillF(graphics, x, y, x + w, y + h, MWStyle.withAlpha(MWStyle.PANEL, 0.6f));
        MWStyle.hairline(graphics, x, y, w, h, MWStyle.border());
    }

    private int segmentWidth(String label) {
        return font.width(label) + 16;
    }

    /** Bordered segmented control (one box, thin dividers) - reads as ONE control, unlike the loose
     *  tab-looking pills it replaces. Active segment: tinted + underlined in `color`. */
    private void drawSegmented(GuiGraphics graphics, int x, int y, String[] labels, int active, int color,
                               int[] textColors, int mouseX, int mouseY) {
        int total = 0;
        for (String label : labels) {
            total += segmentWidth(label);
        }
        MWStyle.fillF(graphics, x, y, x + total, y + 18, MWStyle.PANEL_RAISED);
        int sx = x;
        for (int i = 0; i < labels.length; i++) {
            int w = segmentWidth(labels[i]);
            boolean isActive = i == active;
            boolean hov = mouseX >= sx && mouseX < sx + w && mouseY >= y && mouseY < y + 18;
            if (isActive) {
                MWStyle.fillF(graphics, sx, y, sx + w, y + 18, MWStyle.withAlpha(color, 0.22f));
                MWStyle.fillF(graphics, sx, y + 16, sx + w, y + 18, color);
            } else if (hov) {
                MWStyle.fillF(graphics, sx, y, sx + w, y + 18, MWStyle.PANEL_HOVER);
            }
            int textColor = isActive ? color : textColors != null && textColors[i] != 0 ? textColors[i]
                    : hov ? MWStyle.TEXT : MWStyle.TEXT_DIM;
            MWStyle.strF(graphics, font, labels[i], sx + 8, y + 5, textColor, false);
            if (i > 0) {
                MWStyle.fillF(graphics, sx, y + 3, sx + 1, y + 15, MWStyle.border());
            }
            sx += w;
        }
        MWStyle.hairline(graphics, x, y, total, 18, MWStyle.border());
    }

    private int segmentAt(int x, int y, String[] labels, int mouseX, int mouseY) {
        if (mouseY < y || mouseY >= y + 18) {
            return -1;
        }
        int sx = x;
        for (int i = 0; i < labels.length; i++) {
            int w = segmentWidth(labels[i]);
            if (mouseX >= sx && mouseX < sx + w) {
                return i;
            }
            sx += w;
        }
        return -1;
    }

    private int[] countsCache = new int[4];
    private long countsAt;
    private List<StatItem> statsCache = new ArrayList<>();
    private long statsCacheAt;
    private int statsCacheKey = -1;

    /** Filter-pill counts walk the whole catalog; refreshing them 2-3x a second is plenty (it ran twice per frame). */
    private int[] catalogCounts() {
        long now = System.currentTimeMillis();
        if (now - countsAt < 400) {
            return countsCache;
        }
        countsAt = now;
        countsCache = computeCatalogCounts();
        return countsCache;
    }

    private int[] computeCatalogCounts() {
        int total = 0;
        int active = 0;
        int inactive = 0;
        int stale = 0;
        for (CatalogEntry e : LoadoutRegistry.get().catalogList()) {
            total++;
            if (e.stale) {
                stale++;
            } else if (e.enabled) {
                active++;
            } else {
                inactive++;
            }
        }
        return new int[]{total, active, inactive, stale};
    }

    private String[] catalogFilterLabels() {
        int[] counts = catalogCounts();
        CatalogFilter[] filters = CatalogFilter.values();
        String[] labels = new String[filters.length];
        for (int i = 0; i < filters.length; i++) {
            labels[i] = sentence(filters[i].name()) + " " + counts[i];
        }
        return labels;
    }

    private String[] statsViewLabels() {
        StatsView[] views = StatsView.values();
        String[] labels = new String[views.length];
        for (int i = 0; i < views.length; i++) {
            labels[i] = sentence(views[i].name());
        }
        return labels;
    }

    private String toolbarTitle() {
        return switch (tab) {
            case SLOTS -> "SLOT DEFINITION";
            case PERKS -> "PERK DEFINITION";
            case RULES -> "GLOBAL RULES";
            case ADD -> "ADD ITEMS";
            default -> "";
        };
    }

    /** Boxed list + form panels and the toolbar row inside the form panel (title, or the CATALOG
     *  filter control). Drawn before the rows/fields so they sit on top of the panels. */
    private void renderPanelsAndToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        panel(graphics, listX - 4, 50, listWidth + 8, listBottom + 4 - 50);
        int formPanelX = formX - 8;
        panel(graphics, formPanelX, 50, width - 6 - formPanelX, formBottom() + 2 - 50);
        int tc = tabColor(tab);
        if (tab == Tab.CATALOG) {
            int labelW = font.width("FILTER") + 8;
            MWStyle.strF(graphics, font, "FILTER", formX, 59, MWStyle.TEXT_DIM, false);
            int[] counts = catalogCounts();
            int[] colors = new int[4];
            colors[3] = counts[3] > 0 ? MWStyle.NEGATIVE : 0;
            drawSegmented(graphics, formX + labelW, 54, catalogFilterLabels(), catalogFilter.ordinal(), tc, colors, mouseX, mouseY);
        } else {
            MWStyle.fillF(graphics, formX, 58, formX + 3, 69, tc);
            MWStyle.strF(graphics, font, toolbarTitle(), formX + 8, 60, tc, false);
        }
        MWStyle.fillF(graphics, formX, 73, width - 14, 74, MWStyle.border());
    }

    private boolean isFormTab() {
        return tab == Tab.SLOTS || tab == Tab.CATALOG || tab == Tab.PERKS || tab == Tab.RULES;
    }

    private boolean isRecordTab() {
        return tab == Tab.SLOTS || tab == Tab.CATALOG || tab == Tab.PERKS;
    }

    /** One accent for every tab: a different colour per tab (blue / amber / purple / red / green / teal) read as decoration, not information. */
    private static int tabColor(Tab t) {
        return MWStyle.signal();
    }

    /** SLOTS -> "Slots": enum constants are upper-case in code, people read sentence case. */
    private static String sentence(String constant) {
        String lower = constant.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private int tabWidthOf(Tab t) {
        return font.width(sentence(t.name())) + 20;
    }

    private static float ease(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private float tabAnimProgress() {
        int duration = switch (tab) {
            case SLOTS -> 260;
            case CATALOG -> 320;
            case PERKS -> 300;
            case RULES -> 380;
            case ADD -> 240;
            case STATS -> 300;
        };
        return Mth.clamp((System.currentTimeMillis() - tabAnimStart) / (float) duration, 0f, 1f);
    }

    /** Each tab enters differently: SLOTS slides in from the right, RULES unfolds from the left,
     *  PERKS drops from above, CATALOG rises (with a cascading list), ADD pops up from below. */
    private int animOffsetX() {
        float e = ease(tabAnimProgress());
        return switch (tab) {
            case SLOTS -> (int) ((1f - e) * 48);
            case RULES -> (int) (-(1f - e) * 36);
            default -> 0;
        };
    }

    private int animOffsetY() {
        float e = ease(tabAnimProgress());
        return switch (tab) {
            case PERKS -> (int) (-(1f - e) * 14);
            case CATALOG -> (int) ((1f - e) * 10);
            case ADD -> (int) ((1f - e) * 18);
            default -> 0;
        };
    }

    private int rowStaggerMs() {
        return switch (tab) {
            case CATALOG -> 14;
            case PERKS -> 26;
            case RULES -> 60;
            case ADD -> 10;
            default -> 0;
        };
    }

    private int blueprintExtraHeight() {
        List<String> typed = splitList(fieldValue("blueprintGameIds"));
        int lines = typed.isEmpty() ? 1 : Math.min(typed.size(), 8) + (typed.size() > 8 ? 1 : 0);
        return 4 + lines * 10 + 2;
    }

    /** Lays the sectioned form out at the current scroll + entrance-animation offset: collapsible
     *  headers, label rows, wrapped toggle pills. Boxes are physically moved so hit-tests match
     *  pixels; anything outside the form region is hidden. Re-run whenever scroll / animation /
     *  collapse state changes. */
    private void layoutForm() {
        formRows.clear();
        headerRects.clear();
        toggleRects.clear();
        sectionContentY.clear();
        if (bulkMode()) {
            fields.values().forEach(box -> box.visible = false);
            formContentBottom = 0;
            return;
        }
        int x0 = formX + animOffsetX();
        int baseY = formTop - (int) formScroll + animOffsetY();
        int fieldWidth = width - formX - 18;
        int bottom = formBottom();
        int y = baseY;
        boolean showBlueprintIds = toggles.getOrDefault("hasBlueprint", false);

        List<Section> sections = new ArrayList<>(sectionsFor(tab));
        java.util.Set<String> listed = new java.util.HashSet<>();
        sections.forEach(section -> listed.addAll(java.util.Arrays.asList(section.names())));
        List<String> leftovers = new ArrayList<>();
        fields.keySet().stream().filter(name -> !listed.contains(name)).forEach(leftovers::add);
        toggles.keySet().stream().filter(name -> !listed.contains(name)).forEach(leftovers::add);
        if (!leftovers.isEmpty()) {
            sections.add(new Section("OTHER", leftovers.toArray(new String[0])));
        }

        for (EditBox box : fields.values()) {
            box.visible = false; // re-enabled below for the rows that are actually on screen
        }
        for (Section section : sections) {
            List<String> fieldNames = new ArrayList<>();
            List<String> toggleNames = new ArrayList<>();
            for (String name : section.names()) {
                if (fields.containsKey(name)) {
                    fieldNames.add(name);
                } else if (toggles.containsKey(name)) {
                    toggleNames.add(name);
                }
            }
            if (fieldNames.isEmpty() && toggleNames.isEmpty()) {
                continue;
            }
            String key = tab.name() + ":" + section.title();
            boolean collapsed = collapsedSections.contains(key);
            sectionContentY.put(section.title(), y - baseY);
            headerRects.add(new HeaderRect(key, x0, y, fieldWidth, 16));
            formRows.add(new FormRow(0, section.title(), x0, y, fieldNames.size() + toggleNames.size(), collapsed));
            y += 20;
            if (collapsed) {
                y += 2;
                continue;
            }
            for (String name : fieldNames) {
                if ("blueprintGameIds".equals(name) && !showBlueprintIds) {
                    continue; // collapsed alongside its toggle: no row, nothing below shifts
                }
                EditBox box = fields.get(name);
                box.setX(x0 + LABEL_W);
                box.setY(y);
                box.setWidth(fieldWidth - LABEL_W);
                box.visible = y >= formTop - 4 && y + 14 <= bottom;
                formRows.add(new FormRow(1, name, x0, y, 0, false));
                y += 20;
                if ("blueprintGameIds".equals(name)) {
                    y += blueprintExtraHeight();
                }
            }
            if (!toggleNames.isEmpty()) {
                int toggleX = x0;
                for (String name : toggleNames) {
                    int toggleWidth = font.width(labelOf(name) + "  OFF") + 32;
                    if (toggleX + toggleWidth > x0 + fieldWidth) {
                        toggleX = x0;
                        y += 20;
                    }
                    if (y >= formTop - 4 && y + 16 <= bottom) {
                        toggleRects.add(new ToggleRect(name, toggleX, y, toggleWidth, 16));
                    }
                    toggleX += toggleWidth + 8;
                }
                y += 22;
            }
            y += 6;
        }
        formContentBottom = y - baseY + 8;
    }

    /** RULES navigator / Ctrl+arrows: expand + scroll so the section header sits at the top. */
    private void jumpToSection(String title) {
        collapsedSections.remove(tab.name() + ":" + title);
        layoutForm();
        Integer y = sectionContentY.get(title);
        if (y != null) {
            int overflow = Math.max(0, formContentBottom - (formBottom() - formTop));
            formScrollTarget = Mth.clamp(y, 0, overflow);
        }
    }

    private void field(String name, String value) {
        EditBox box = new EditBox(font, 0, 0, 100, 14, Component.empty());
        box.setBordered(false);
        box.setTextColor(MWStyle.TEXT);
        box.setMaxLength(65535);
        box.setValue(value == null ? "" : value);
        box.setResponder(text -> {
            markDirty();
            if ("blueprintGameIds".equals(name)) {
                layoutForm();
            }
        });
        fields.put(name, box);
        addRenderableWidget(box);
    }

    private void toggle(String name, boolean value) {
        toggles.put(name, value);
    }

    private String fieldValue(String name) {
        EditBox box = fields.get(name);
        return box == null ? "" : box.getValue().trim();
    }

    private int fieldInt(String name, int fallback) {
        try {
            return Integer.parseInt(fieldValue(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double fieldDouble(String name, double fallback) {
        try {
            return Double.parseDouble(fieldValue(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The perk's first "special"-type effect (if any) — where all its tunable balance numbers
     *  live. There's only ever one per perk in practice. */
    /** 80 -> "80", 0.1 -> "0.1" (quick fields now carry fractions such as the 0.1 duration multiplier). */
    private static String formatQuick(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9 ? String.valueOf((long) value)
                : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static JsonObject firstSpecialEffect(PerkDefinition perk) {
        for (JsonObject effect : perk.effects) {
            if ("special".equals(Json.str(effect, "type", ""))) {
                return effect;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- actions

    private void startNew() {
        if (tab == Tab.RULES) {
            return;
        }
        selectedId = "";
        rebuildForm();
    }

    /** CATALOG shortcut: point at the item you're literally holding — id, full NBT, count and
     *  display name land in the form. Hold a TACZ gun and the gun toggle flips itself. The
     *  "browse creative / just give me what's in my inventory" workflow. */
    private void fillFromHand() {
        if (tab != Tab.CATALOG || Minecraft.getInstance().player == null) {
            return;
        }
        ItemStack held = Minecraft.getInstance().player.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(held);
        if (gun != null) {
            ResourceLocation gunId = gun.getGunId(held);
            if (gunId != null) {
                setField("gameId", gunId.toString());
                setField("nbt", "");
                setField("displayName", held.getHoverName().getString());
                setField("count", "1");
                if (fieldValue("id").isEmpty()) {
                    setField("id", "gun_" + gunId.toString().replace(':', '_'));
                }
                toggles.put("gun", true);
                MWStyle.playClick();
                return;
            }
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (itemId == null) {
            return;
        }
        setField("gameId", itemId.toString());
        setField("nbt", held.getTag() == null ? "" : held.getTag().toString());
        setField("displayName", held.getHoverName().getString());
        setField("count", String.valueOf(held.getCount()));
        if (fieldValue("id").isEmpty()) {
            setField("id", itemId.getPath() + "_" + Integer.toHexString((int) (System.currentTimeMillis() & 0xFFFF)));
        }
        toggles.put("gun", false);
        MWStyle.playClick();
    }

    private void setField(String name, String value) {
        EditBox box = fields.get(name);
        if (box != null) {
            box.setValue(value);
        }
    }

    /** Registry resync landed (this admin's save round-tripped, or another admin edited):
     *  refresh the list immediately; the form only reloads when its subject vanished, so
     *  in-progress typing is never clobbered. */
    public void onDataRefreshed() {
        bulkSelected.removeIf(id -> LoadoutRegistry.get().entry(id) == null);
        rebuildList();
        boolean subjectGone = switch (tab) {
            case SLOTS -> !selectedId.isEmpty() && LoadoutRegistry.get().slot(selectedId) == null;
            case CATALOG -> !selectedId.isEmpty() && LoadoutRegistry.get().entry(selectedId) == null;
            case PERKS -> !selectedId.isEmpty() && LoadoutRegistry.get().perk(selectedId) == null;
            case RULES -> false;
            case ADD -> false;
            case STATS -> false;
        };
        if (subjectGone && System.currentTimeMillis() >= pendingSelectUntil) {
            selectedId = "";
            rebuildForm();
        }
    }

    private void saveCurrent() {
        JsonObject payload;
        String kind;
        switch (tab) {
            case SLOTS -> {
                kind = "SLOT";
                SlotDefinition slot = new SlotDefinition();
                slot.id = fieldValue("id");
                slot.hotbarIndex = fieldInt("hotbarIndex", 0);
                slot.displayName = fieldValue("displayName");
                slot.description = fieldValue("description");
                slot.iconItem = fieldValue("iconItem");
                slot.allowedCategories = splitList(fieldValue("allowedCategories"));
                slot.whitelist = splitList(fieldValue("whitelist"));
                slot.blacklist = splitList(fieldValue("blacklist"));
                slot.maxCount = Math.max(1, fieldInt("maxCount", 1));
                slot.defaultEntry = fieldValue("defaultEntry");
                slot.required = toggles.getOrDefault("required", false);
                slot.locked = toggles.getOrDefault("locked", false);
                slot.hidden = toggles.getOrDefault("hidden", false);
                slot.enabled = toggles.getOrDefault("enabled", true);
                // Two enabled slots sharing a hotbarIndex means the loadout applier silently
                // drops one item into the other's spot at equip time — warn instead of letting
                // an admin footgun themselves with a collision the list won't make obvious until
                // they've already saved it.
                if (slot.enabled) {
                    for (SlotDefinition other : LoadoutRegistry.get().slotList()) {
                        if (other.enabled && !other.id.equals(slot.id) && other.hotbarIndex == slot.hotbarIndex) {
                            flash("\u00A7cHotbar index " + (slot.hotbarIndex + 1) + " already used by \u00A7f" + other.displayName);
                            return;
                        }
                    }
                }
                payload = slot.toJson();
                selectedId = slot.id;
            }
            case CATALOG -> {
                kind = "ENTRY";
                CatalogEntry entry = new CatalogEntry();
                entry.id = fieldValue("id");
                CatalogEntry existing = LoadoutRegistry.get().entry(entry.id);
                if (existing != null) {
                    entry.stale = existing.stale;
                    entry.staleSinceMillis = existing.staleSinceMillis;
                }
                if (existing != null && existing.kind == CatalogEntry.Kind.ATTACHMENT) {
                    // scanner-managed fields are never exposed as editable boxes
                    // for these (see rebuildForm) — copy them straight from the existing entry.
                    // unlockLevel is now an exception — it IS editable for attachments
                    // (the weapon-level gate, see Rules.weaponProgressionEnabled), read from the
                    // form like any other kind instead of being copied through unchanged.
                    entry.kind = CatalogEntry.Kind.ATTACHMENT;
                    entry.displayName = existing.displayName;
                    entry.category = existing.category;
                    entry.gameId = existing.gameId;
                    entry.unlockLevel = Math.max(0, fieldInt("unlockLevel", 0));
                    entry.enabled = toggles.getOrDefault("enabled", existing.enabled);
                    payload = entry.toJson();
                    selectedId = entry.id;
                } else {
                    entry.kind = toggles.getOrDefault("gun", false) ? CatalogEntry.Kind.GUN : CatalogEntry.Kind.ITEM;
                    entry.displayName = fieldValue("displayName");
                    entry.category = fieldValue("category").toLowerCase(Locale.ROOT);
                    entry.gameId = fieldValue("gameId");
                    entry.nbt = fieldValue("nbt");
                    entry.count = Math.max(1, fieldInt("count", 1));
                    entry.unlockLevel = Math.max(0, fieldInt("unlockLevel", 0));
                    entry.enabled = toggles.getOrDefault("enabled", true);
                    entry.adminOnly = toggles.getOrDefault("adminOnly", false);
                    entry.ammoMultiplier = fieldDouble("ammoMultiplier", 0);
                    entry.cooldownSeconds = fields.containsKey("cooldownSeconds") ? Math.max(0, fieldInt("cooldownSeconds", 0)) : entry.cooldownSeconds;
                    entry.cooldownOnClick = toggles.containsKey("cooldownOnClick") ? toggles.get("cooldownOnClick") : entry.cooldownOnClick;
                    entry.hasBlueprint = toggles.getOrDefault("hasBlueprint", false);
                    entry.blueprintIds = entry.hasBlueprint ? splitList(fieldValue("blueprintGameIds")) : new ArrayList<>();
                    payload = entry.toJson();
                    selectedId = entry.id;
                }
            }
            case PERKS -> {
                kind = "PERK";
                PerkDefinition perk = new PerkDefinition();
                perk.id = fieldValue("id");
                perk.displayName = fieldValue("displayName");
                perk.description = fieldValue("description");
                perk.icon = fieldValue("icon");
                perk.perkSlot = fieldValue("perkSlot");
                perk.enabled = toggles.getOrDefault("enabled", true);
                try {
                    JsonParser.parseString(fieldValue("effects")).getAsJsonArray()
                            .forEach(element -> {
                                if (element.isJsonObject()) {
                                    perk.effects.add(element.getAsJsonObject());
                                }
                            });
                } catch (Exception ignored) {
                    PerkDefinition previous = LoadoutRegistry.get().perk(perk.id);
                    if (previous != null) {
                        perk.effects.addAll(previous.effects);
                    }
                }
                // Friendly quick-edit fields win over whatever the raw "effects" box parsed to —
                // they're the primary editing path, the JSON box is the advanced fallback.
                JsonObject special = firstSpecialEffect(perk);
                if (special != null) {
                    for (String key : QUICK_PARAM_KEYS) {
                        if (fields.containsKey(key) && !fieldValue(key).trim().equals(quickOriginal.get(key))) {
                            double value = fieldDouble(key, Json.doubleValue(special, key, 0));
                            if (value == Math.rint(value) && Math.abs(value) < 1e9) {
                                special.addProperty(key, (long) value);
                            } else {
                                special.addProperty(key, value);
                            }
                        }
                    }
                }
                payload = perk.toJson();
                selectedId = perk.id;
            }
            case RULES -> {
                kind = "RULES";
                Rules rules = new Rules();
                rules.editSecondsAfterRespawn = fieldInt("editSecondsAfterRespawn", 10);
                rules.noDamageSeconds = fieldInt("noDamageSeconds", 45);
                rules.useLockSeconds = Math.max(0, fieldInt("useLockSeconds", 60));
                rules.fieldUpgradeCooldownSeconds = Math.max(0, fieldInt("fieldUpgradeCooldownSeconds", 80));
                rules.maxPresets = Math.max(1, fieldInt("maxPresets", 20));
                rules.minPerksRequired = Math.max(0, fieldInt("minPerksRequired", 1));
                rules.perkSlots = parsePerkSlots(fieldValue("perkSlots"));
                rules.ammoByCategory.putAll(parseIntMap(fieldValue("ammoByCategory")));
                rules.dynamicAmmoTiers = parseAmmoTiers(fieldValue("dynamicAmmoTiers"));
                rules.fullyLoadedMultiplier = Math.max(0, fieldDouble("fullyLoadedMultiplier", 4.0));
                rules.categoryMultipliers.putAll(parseDoubleMap(fieldValue("categoryMultipliers")));
                rules.xpPerKill = Math.max(0, fieldInt("xpPerKill", 100));
                rules.xpHeadshotKill = Math.max(0, fieldInt("xpHeadshotKill", 200));
                rules.xpPerLevel = Math.max(100, fieldInt("xpPerLevel", 1000));
                try {
                    rules.xpMultiplier = Math.max(0, Double.parseDouble(fieldValue("xpMultiplier")));
                } catch (Exception e) {
                    rules.xpMultiplier = 1.0;
                }
                try {
                    rules.xpCurveGrowth = Math.max(1.0, Double.parseDouble(fieldValue("xpCurveGrowth")));
                } catch (Exception e) {
                    rules.xpCurveGrowth = 1.0;
                }
                rules.weaponXpPerKill = Math.max(0, fieldInt("weaponXpPerKill", 50));
                rules.weaponXpHeadshotKill = Math.max(0, fieldInt("weaponXpHeadshotKill", 100));
                rules.weaponXpPerLevel = Math.max(100, fieldInt("weaponXpPerLevel", 300));
                try {
                    rules.weaponXpCurveGrowth = Math.max(1.0, Double.parseDouble(fieldValue("weaponXpCurveGrowth")));
                } catch (Exception e) {
                    rules.weaponXpCurveGrowth = 1.04;
                }
                rules.attachmentPointLimit = Math.max(0, fieldInt("attachmentPointLimit", 100));
                rules.attachmentPointCost = Math.max(0, fieldInt("attachmentPointCost", 20));
                rules.spawnZoneRadius = Math.max(0, Math.min(64, fieldInt("spawnZoneRadius", 6)));
                rules.streakRewards = parseStreak(fieldValue("streakRewards"));
                for (String p : fieldValue("specialistPerkPool").split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) rules.specialistPerkPool.add(t);
                }
                rules.hostileMobMultiplier = Math.max(1, fieldInt("hostileMobMultiplier", 1));
                rules.killstreakEnabled = toggles.getOrDefault("killstreakEnabled", true);
                rules.streakResetOnDeath = toggles.getOrDefault("streakResetOnDeath", true);
                rules.specialistEnabled = toggles.getOrDefault("specialistEnabled", true);
                rules.killstreakSourceRestricted = toggles.getOrDefault("killstreakSourceRestricted", true);
                rules.killstreakPvpOnly = toggles.getOrDefault("killstreakPvpOnly", false);
                rules.streakIgnoreListedSources = toggles.getOrDefault("streakIgnoreListedSources", true);
                rules.streakIgnoreStrikeMods = toggles.getOrDefault("streakIgnoreStrikeMods", true);
                for (String id : fieldValue("streakExcludedIds").split(",")) {
                    String t = id.trim();
                    if (!t.isEmpty()) rules.streakExcludedIds.add(t);
                }
                rules.peacefulAlwaysEditable = toggles.getOrDefault("peacefulAlwaysEditable", true);
                rules.restockOnRespawn = toggles.getOrDefault("restockOnRespawn", true);
                rules.restrictOpAndCreative = toggles.getOrDefault("restrictOpAndCreative", false);
                rules.requireZoneToEquip = toggles.getOrDefault("requireZoneToEquip", false);
                rules.antiSpamRefillEnabled = toggles.getOrDefault("antiSpamRefillEnabled", false);
                rules.useLockEnabled = toggles.getOrDefault("useLockEnabled", true);
                rules.preventDropManaged = toggles.getOrDefault("preventDropManaged", true);
                rules.progressionEnabled = toggles.getOrDefault("progressionEnabled", false);
                rules.weaponProgressionEnabled = toggles.getOrDefault("weaponProgressionEnabled", false);
                rules.dynamicAmmoEnabled = toggles.getOrDefault("dynamicAmmoEnabled", false);
                rules.dualAmmoSlot = toggles.getOrDefault("dualAmmoSlot", true);
                try {
                    JsonParser.parseString(fieldValue("editZones")).getAsJsonArray()
                            .forEach(element -> {
                                if (element.isJsonObject()) {
                                    rules.editZones.add(Rules.EditZone.fromJson(element.getAsJsonObject()));
                                }
                            });
                } catch (Exception ignored) {
                    rules.editZones.addAll(LoadoutRegistry.get().rules().editZones);
                }
                payload = rules.toJson();
            }
            default -> {
                return;
            }
        }
        if (tab != Tab.RULES && fieldValue("id").isEmpty()) {
            flash("\u00A7cID REQUIRED");
            return;
        }
        Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate(kind, "UPSERT", Json.GSON.toJson(payload)));
        dirtyAt = 0;
        discardArmed = false;
        lastSavedAt = System.currentTimeMillis();
        flash("\u00A7a\u2713 SAVED  " + (tab == Tab.RULES ? "RULES" : fieldValue("id")));
        if (tab != Tab.RULES && selectedId.isEmpty()) {
            // Follow the record you just created so the next edit updates it instead of re-creating.
            selectedId = fieldValue("id");
            pendingSelectUntil = System.currentTimeMillis() + 4000;
        }
    }

    // ---------------------------------------------------------------- QoL actions

    private boolean bulkMode() {
        return tab == Tab.CATALOG && !bulkSelected.isEmpty();
    }

    /** One BULK packet for the whole selection: one save + one resync server-side. */
    private void bulkApply(String action, JsonObject extra, String doneLabel) {
        if (bulkSelected.isEmpty()) {
            return;
        }
        JsonObject payload = extra == null ? new JsonObject() : extra;
        JsonArray ids = new JsonArray();
        bulkSelected.forEach(ids::add);
        payload.add("ids", ids);
        Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate("BULK", action, payload.toString()));
        flash("\u00A7a" + doneLabel + " \u2014 " + bulkSelected.size() + " entries");
        MWStyle.playClick();
    }

    private void toggleBulk(int index) {
        String id = rows.get(index).id;
        if (hasShiftDown()) {
            int anchor = bulkAnchor;
            if (anchor < 0) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).id.equals(selectedId)) {
                        anchor = i;
                    }
                }
            }
            if (anchor >= 0) {
                for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
                    bulkSelected.add(rows.get(i).id);
                }
                bulkAnchor = index;
                layoutForm();
                return;
            }
        }
        if (!bulkSelected.remove(id)) {
            bulkSelected.add(id);
        }
        bulkAnchor = index;
        layoutForm();
    }

    private void requestDelete() {
        if (!isRecordTab() || selectedId.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (deleteArmedId.equals(selectedId) && now < deleteArmedUntil) {
            deleteCurrent();
            deleteArmedUntil = 0;
            flash("\u00A7cDELETED");
        } else {
            deleteArmedId = selectedId;
            deleteArmedUntil = now + 3000;
            flash("\u00A7eCLICK AGAIN TO DELETE  " + selectedId);
        }
    }

    private void duplicateCurrent() {
        if (!isRecordTab() || selectedId.isEmpty()) {
            flash("\u00A7cSELECT A RECORD FIRST");
            return;
        }
        Map<String, String> fieldValues = new LinkedHashMap<>();
        fields.forEach((name, box) -> fieldValues.put(name, box.getValue()));
        Map<String, Boolean> toggleValues = new LinkedHashMap<>(toggles);
        selectedId = "";
        rebuildForm();
        fieldValues.forEach((name, value) -> {
            if (fields.containsKey(name)) {
                setField(name, value);
            }
        });
        toggleValues.forEach((name, value) -> {
            if (toggles.containsKey(name)) {
                toggles.put(name, value);
            }
        });
        setField("id", fieldValues.getOrDefault("id", "") + "_copy");
        if (fields.containsKey("displayName")) {
            setField("displayName", fieldValues.getOrDefault("displayName", "") + " (copy)");
        }
        layoutForm();
        markDirty();
        flash("\u00A7bDUPLICATED  EDIT ID, THEN SAVE");
    }

    private void revertForm() {
        if (!isFormTab()) {
            return;
        }
        rebuildForm();
        flash("\u00A77REVERTED");
    }

    private void navigateList(int dir) {
        if (rows.isEmpty() || tab == Tab.ADD || tab == Tab.STATS) {
            return;
        }
        int current = -1;
        for (int i = 0; i < rows.size(); i++) {
            String id = rows.get(i).id();
            if (tab == Tab.RULES ? id.equals("sec:" + activeSection) : id.equals(selectedId)) {
                current = i;
                break;
            }
        }
        int next = current < 0 ? (dir > 0 ? 0 : rows.size() - 1) : Mth.clamp(current + dir, 0, rows.size() - 1);
        if (tab == Tab.RULES) {
            jumpToSection(rows.get(next).id().substring(4));
        } else {
            selectedId = rows.get(next).id();
            rebuildForm();
        }
        int track = listBottom - listTop;
        int rowTop = next * 20;
        if (rowTop < listScrollTarget) {
            listScrollTarget = rowTop;
        } else if (rowTop + 20 > listScrollTarget + track) {
            listScrollTarget = rowTop + 20 - track;
        }
        MWStyle.playClick();
    }

    private void switchTab(Tab next) {
        if (next == tab) {
            return;
        }
        MWStyle.playTab();
        tab = next;
        listScroll = 0;
        listScrollTarget = 0; // switching tabs changes the row count - reset, don't carry the old target over
        formScroll = 0;
        formScrollTarget = 0;
        catalogFilter = CatalogFilter.ALL;
        selectedId = tab == Tab.RULES ? "rules" : "";
        searchBox.setValue("");
        tabAnimStart = System.currentTimeMillis();
        resetStatsAnim();
        tabAnimDone = false;
        deleteArmedUntil = 0;
        discardArmed = false;
        bulkSelected.clear();
        bulkAnchor = -1;
        rebuildList();
        rebuildForm();
        if (tab == Tab.STATS && !statsRequestedThisOpen) {
            statsRequestedThisOpen = true;
            // Integrated server (singleplayer/LAN host): client and server share the exact same
            // UsageStats singleton in one JVM, so this screen already sees live data with zero sync
            // needed - requesting one would make the sync handler's clearAll()+reapply race the
            // server thread's concurrent recordPick/recordHit/recordKill calls on that SAME object.
            if (Minecraft.getInstance().hasSingleplayerServer()) {
                onStatsRefreshed();
            } else {
                Net.CHANNEL.sendToServer(new Net.C2SRequestStats());
            }
        }
    }

    private void deleteCurrent() {
        if (tab == Tab.RULES || selectedId.isEmpty()) {
            return;
        }
        String kind = switch (tab) {
            case SLOTS -> "SLOT";
            case CATALOG -> "ENTRY";
            case PERKS -> "PERK";
            default -> "";
        };
        JsonObject payload = new JsonObject();
        payload.addProperty("id", selectedId);
        Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate(kind, "DELETE", Json.GSON.toJson(payload)));
        selectedId = "";
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter while typing an item id fires the add - chain several without touching the mouse.
        if ((keyCode == 257 || keyCode == 335) && tab == Tab.ADD
                && (quickIdBox != null && quickIdBox.isFocused() || quickSlotBox != null && quickSlotBox.isFocused())) {
            quickAddItem();
            return true;
        }
        // While a text box has focus, Ctrl+A / C / V / X / Backspace / arrows are TEXT editing keys. They used
        // to be swallowed by the panel's own shortcuts (Ctrl+A selected every catalog row instead of the text).
        // Only the genuinely global combos stay live while typing: Ctrl+S save, Ctrl+F search, Ctrl+1-6 tabs.
        boolean typingInBox = getFocused() instanceof EditBox focusedBox && focusedBox.isFocused();
        if (Screen.hasControlDown() && typingInBox && keyCode != 83 && keyCode != 70 && !(keyCode >= 49 && keyCode <= 54)) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // Tab / Shift+Tab walk the form fields in order (scrolling the next one into view).
        if (keyCode == 258 && isFormTab() && typingInBox && !bulkMode()) {
            cycleFormFocus(Screen.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (Screen.hasControlDown()) {
            switch (keyCode) {
                case 83 -> { // S
                    if (isFormTab()) {
                        saveCurrent();
                    }
                    return true;
                }
                case 78 -> { // N
                    if (isRecordTab()) {
                        startNew();
                    }
                    return true;
                }
                case 68 -> { // D
                    duplicateCurrent();
                    return true;
                }
                case 82 -> { // R
                    revertForm();
                    return true;
                }
                case 65 -> { // A: select every listed catalog row for bulk edit
                    if (tab == Tab.CATALOG && !(searchBox != null && searchBox.isFocused())) {
                        rows.forEach(row -> bulkSelected.add(row.id));
                        layoutForm();
                        return true;
                    }
                }
                case 66 -> { // B: creative-inventory browser
                    minecraft.setScreen(new ItemBrowserScreen(this));
                    return true;
                }
                case 70 -> { // F
                    if (searchBox != null && searchBox.visible) {
                        setFocused(searchBox);
                        searchBox.setFocused(true);
                    }
                    return true;
                }
                default -> {
                    if (keyCode >= 49 && keyCode <= 54) { // 1-6
                        switchTab(Tab.values()[keyCode - 49]);
                        return true;
                    }
                }
            }
        }
        boolean typingInForm = fields.values().stream().anyMatch(EditBox::isFocused);
        if ((keyCode == 257 || keyCode == 335) && typingInForm && isFormTab()) {
            saveCurrent(); // Enter in any field commits it
            return true;
        }
        if (keyCode == 256 && bulkMode() && !(searchBox != null && searchBox.isFocused())) {
            bulkSelected.clear(); // Esc leaves multi-select before it ever closes the panel
            layoutForm();
            return true;
        }
        if (keyCode == 256 && searchBox != null && searchBox.isFocused() && !searchBox.getValue().isEmpty()) {
            searchBox.setValue(""); // first Esc clears the search, second one closes
            return true;
        }
        if ((keyCode == 265 || keyCode == 264) && !typingInForm && !(quickIdBox != null && quickIdBox.isFocused())
                && !(bulkCategoryBox != null && bulkCategoryBox.isFocused()) && !(bulkUnlockBox != null && bulkUnlockBox.isFocused())) {
            navigateList(keyCode == 264 ? 1 : -1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void cycleFormFocus(int dir) {
        List<EditBox> order = new ArrayList<>();
        for (FormRow row : formRows) {
            if (row.type() == 1 && fields.get(row.name()) != null) {
                order.add(fields.get(row.name()));
            }
        }
        if (order.isEmpty()) {
            return;
        }
        int current = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).isFocused()) {
                current = i;
            }
        }
        EditBox next = order.get(Math.floorMod(current + dir, order.size()));
        for (EditBox box : order) {
            box.setFocused(false);
        }
        next.setFocused(true);
        setFocused(next);
        next.moveCursorToStart();
        next.setHighlightPos(next.getValue().length()); // select the whole value, like a normal form
        // bring it into view
        FormRow target = null;
        for (FormRow row : formRows) {
            if (row.type() == 1 && fields.get(row.name()) == next) {
                target = row;
            }
        }
        if (target != null) {
            int overflow = Math.max(0, formContentBottom - (formBottom() - formTop));
            if (target.y() < formTop + 4) {
                formScrollTarget = Mth.clamp(formScrollTarget + target.y() - (formTop + 24), 0, overflow);
            } else if (target.y() + 18 > formBottom()) {
                formScrollTarget = Mth.clamp(formScrollTarget + target.y() - (formBottom() - 40), 0, overflow);
            }
        }
    }

    @Override
    public void onClose() {
        if (dirtyAt > 0 && isFormTab() && !discardArmed) {
            discardArmed = true;
            flash("\u00A7eUNSAVED  CTRL+S SAVE, ESC AGAIN DISCARDS");
            return;
        }
        super.onClose();
    }

    /** Called indirectly when a registry resync lands: repopulate list + form from fresh data. */
    @Override
    public void tick() {
        super.tick();
        // Cheap staleness check: row count differs from registry -> rebuild (covers admin saves
        // AND other admins editing concurrently). Field contents are only rebuilt on selection
        // change so in-progress typing is never clobbered mid-edit.
        int expected = switch (tab) {
            case SLOTS -> LoadoutRegistry.get().slotList().size();
            case CATALOG -> LoadoutRegistry.get().catalogList().size();
            case PERKS -> LoadoutRegistry.get().perkList().size();
            case RULES -> sectionsFor(Tab.RULES).size();
            case ADD -> LoadoutRegistry.get().catalogList().size();
            case STATS -> 0;
        };
        long visible = rows.size();
        if (searchBox.getValue().isEmpty() && visible != expected) {
            rebuildList();
        }
    }

    // ---------------------------------------------------------------- parse helpers

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Map<String, Integer> parseIntMap(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                try {
                    out.put(parts[0].trim().toLowerCase(Locale.ROOT), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    dev.l41nx.mwloadout.MWLog.quiet("client/gui/admin/AdminScreen:1741", ignored);
                }
            }
        }
        return out;
    }

    /** Format streak ladder as "kills=itemId" entries, semicolon-separated, for the admin text
     *  field — semicolon (not newline) because this is a single-line EditBox: a real newline
     *  shows up as a literal glyph instead of a line break, not a clean list. */
    private static String joinStreak(java.util.List<Rules.StreakReward> rewards) {
        StringBuilder sb = new StringBuilder();
        for (Rules.StreakReward r : rewards) {
            if (sb.length() > 0) sb.append(';');
            sb.append(r.kills).append('=').append(r.itemId);
            if (r.hasAmmoConfig()) {
                sb.append('|').append(Math.max(0, r.mag)).append('/').append(Math.max(0, r.reserve)); // bullets/reserve
            }
        }
        return sb.toString();
    }

    private static java.util.List<Rules.StreakReward> parseStreak(String text) {
        java.util.List<Rules.StreakReward> list = new java.util.ArrayList<>();
        if (text == null) return list;
        for (String entry : text.split("[;\n,]")) {
            String t = entry.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            try {
                int kills = Integer.parseInt(t.substring(0, eq).trim());
                String itemId = t.substring(eq + 1).trim();
                int mag = -1;
                int reserve = -1;
                int bar = itemId.lastIndexOf('|');
                if (bar > 0 && itemId.substring(bar + 1).matches("\\d+/\\d+")) { // optional "|bullets/reserve" suffix
                    String[] ammo = itemId.substring(bar + 1).split("/");
                    mag = Integer.parseInt(ammo[0]);
                    reserve = Integer.parseInt(ammo[1]);
                    itemId = itemId.substring(0, bar).trim();
                }
                if (!itemId.isEmpty()) list.add(new Rules.StreakReward(kills, itemId, mag, reserve));
            } catch (NumberFormatException ignored) { }
        }
        list.sort((a, b) -> Integer.compare(a.kills, b.kills));
        return list;
    }

    /** Format dynamic-ammo tiers as "minCapacity,maxCapacity,category,multiplier" entries,
     *  semicolon-separated, for the admin text field — maxCapacity blank means open-ended ("and
     *  above"), category blank means every category. Example: "0,9,sg,8.0" (shotguns, 0-9 rounds,
     *  x8); "50,,,2.0" (50+ rounds, any category, x2). Semicolon between tiers (not newline) for
     *  the same reason joinStreak uses it — this is a single-line EditBox. */
    private static String joinAmmoTiers(java.util.List<Rules.AmmoTier> tiers) {
        StringBuilder sb = new StringBuilder();
        for (Rules.AmmoTier tier : tiers) {
            if (sb.length() > 0) sb.append(';');
            sb.append(tier.minCapacity).append(',')
                    .append(tier.maxCapacity < 0 ? "" : String.valueOf(tier.maxCapacity)).append(',')
                    .append(tier.category == null ? "" : tier.category).append(',')
                    .append(tier.multiplier);
        }
        return sb.toString();
    }

    private static java.util.List<Rules.AmmoTier> parseAmmoTiers(String text) {
        java.util.List<Rules.AmmoTier> list = new java.util.ArrayList<>();
        if (text == null) return list;
        for (String entry : text.split("[;\n]")) {
            String t = entry.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split(",", -1);
            if (parts.length != 4) continue;
            try {
                int minCapacity = Integer.parseInt(parts[0].trim());
                String maxRaw = parts[1].trim();
                int maxCapacity = maxRaw.isEmpty() ? -1 : Integer.parseInt(maxRaw);
                String category = parts[2].trim();
                double multiplier = Double.parseDouble(parts[3].trim());
                list.add(new Rules.AmmoTier(minCapacity, maxCapacity, category, multiplier));
            } catch (NumberFormatException ignored) { }
        }
        return list;
    }

    private static String joinMap(Map<String, Integer> map) {
        StringBuilder out = new StringBuilder();
        map.forEach((key, value) -> {
            if (out.length() > 0) {
                out.append(",");
            }
            out.append(key).append("=").append(value);
        });
        return out.toString();
    }

    private static String joinDoubleMap(Map<String, Double> map) {
        StringBuilder out = new StringBuilder();
        map.forEach((key, value) -> {
            if (out.length() > 0) {
                out.append(",");
            }
            out.append(key).append("=").append(value);
        });
        return out.toString();
    }

    private static Map<String, Double> parseDoubleMap(String raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                try {
                    out.put(parts[0].trim().toLowerCase(Locale.ROOT), Double.parseDouble(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    dev.l41nx.mwloadout.MWLog.quiet("client/gui/admin/AdminScreen:1843", ignored);
                }
            }
        }
        return out;
    }

    private static List<Rules.PerkSlot> parsePerkSlots(String raw) {
        List<Rules.PerkSlot> out = new ArrayList<>();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split(":");
            if (parts.length >= 1 && !parts[0].trim().isEmpty()) {
                out.add(Rules.PerkSlot.of(parts[0].trim(), parts.length > 1 ? parts[1].trim() : parts[0].trim().toUpperCase(Locale.ROOT)));
            }
        }
        return out;
    }

    private static String joinPerkSlots(List<Rules.PerkSlot> slots) {
        StringBuilder out = new StringBuilder();
        for (Rules.PerkSlot slot : slots) {
            if (out.length() > 0) {
                out.append(",");
            }
            out.append(slot.id).append(":").append(slot.displayName);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- render & input

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The quick-add row lives on CATALOG only; search lives everywhere else. Toggle both
        // sets so they never overlap or steal each other's clicks.
        boolean addTab = tab == Tab.ADD;
        if (quickIdBox != null) {
            quickIdBox.visible = quickIdBox.active = addTab;
            quickSlotBox.visible = quickSlotBox.active = addTab;
            quickAddButton.visible = quickAddButton.active = addTab;
        }
        if (searchBox != null) {
            // Search belongs to the browsable list tabs; hidden on ADD and RULES.
            boolean searchable = tab == Tab.SLOTS || tab == Tab.CATALOG || tab == Tab.PERKS;
            searchBox.visible = searchBox.active = searchable;
        }
        if (newButton != null) {
            // NEW/DELETE only make sense on the three browsable, editable list tabs — not RULES
            // (a singleton, nothing to create/delete), ADD (already IS the "create new" form,
            // via its own quickAddButton), or STATS (read-only dashboard). SAVE also covers
            // RULES (the one thing on that tab you DO save). FROM HAND is CATALOG-only — it fills
            // gameId/nbt from a held item, which only CATALOG entries have (fillFromHand() itself
            // already no-ops everywhere else; this just stops the button looking clickable there).
            boolean editableList = tab == Tab.SLOTS || tab == Tab.CATALOG || tab == Tab.PERKS;
            newButton.visible = newButton.active = editableList;
            deleteButton.visible = deleteButton.active = editableList;
            saveButton.visible = saveButton.active = editableList || tab == Tab.RULES;
            fromHandButton.visible = fromHandButton.active = tab == Tab.CATALOG;
        }
        if (bulkEnableBtn != null) {
            boolean bm = bulkMode();
            for (var widget : List.of(bulkEnableBtn, bulkDisableBtn, bulkClearBtn, bulkCategoryBtn, bulkUnlockBtn, bulkDeleteBtn, bulkCategoryBox, bulkUnlockBox)) {
                widget.visible = bm;
                widget.active = bm;
            }
            browseButton.visible = browseButton.active = tab == Tab.ADD;
            bulkDeleteBtn.setMessage(Component.literal(System.currentTimeMillis() < bulkDeleteArmedUntil
                    ? "\u00A7cSure? Delete " + bulkSelected.size() : "Delete " + bulkSelected.size() + " entries"));
            if (bm) {
                saveButton.visible = saveButton.active = false;
                deleteButton.visible = deleteButton.active = false;
                newButton.visible = newButton.active = false;
                fromHandButton.visible = fromHandButton.active = false;
            }
        }
        layoutFooterButtons();
        if (ksEditorButton != null) {
            ksEditorButton.visible = ksEditorButton.active = tab == Tab.RULES;
            reconcileButton.visible = reconcileButton.active = tab == Tab.CATALOG;
            cleanStaleButton.visible = cleanStaleButton.active = tab == Tab.CATALOG;
        }
        MWStyle.plainChrome(); // own chrome: tick the frame clock and pin chromeOpen (no stale fade from the last screen)
        long nowMs = System.currentTimeMillis();
        float dtTicks = Math.min(4f, (nowMs - lastFrameMillis) / 50f);
        lastFrameMillis = nowMs;
        listScroll = MWStyle.approach((float) listScroll, (float) listScrollTarget, 0.5f, dtTicks);
        // Smooth form scrolling (same easing as the list) + per-tab entrance animation: both just
        // re-run layoutForm() while they are in flight, then settle exactly.
        boolean relayout = false;
        if (Math.abs(formScrollTarget - formScroll) > 0.4) {
            formScroll = MWStyle.approach((float) formScroll, (float) formScrollTarget, 0.5f, dtTicks);
            relayout = true;
        } else if (formScroll != formScrollTarget) {
            formScroll = formScrollTarget;
            relayout = true;
        }
        if (searchApplyAt > 0 && nowMs >= searchApplyAt) {
            searchApplyAt = 0;
            listScrollTarget = 0; // filtering changes the row count - don't keep a now-stale scroll position
            rebuildList();
        }
        float tabP = tabAnimProgress();
        if (tabP < 1f) {
            relayout = true;
            tabAnimDone = false;
        } else if (!tabAnimDone) {
            relayout = true;
            tabAnimDone = true;
        }
        if (relayout && isFormTab()) {
            layoutForm();
        }
        float enter = ease(Math.min(1f, tabP * 1.5f));
        if (deleteButton != null) {
            boolean armed = !deleteArmedId.isEmpty() && deleteArmedId.equals(selectedId) && nowMs < deleteArmedUntil;
            deleteButton.setMessage(armed ? Component.literal("\u00A7cSURE?") : Component.translatable("screen.mwloadout.admin.delete"));
        }
        // Admin backdrop honours the same opacity setting as the player menus.
        MWStyle.fillF(graphics, 0, 0, width, height, MWStyle.withAlpha(MWStyle.BACKDROP,
                dev.l41nx.mwloadout.client.ClientCfg.backdropOpacity()));
        graphics.pose().pushPose();
        graphics.pose().translate(10, 8, 0);
        graphics.pose().scale(2f, 2f, 1f);
        MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.admin.title").getString(), 0, 0, MWStyle.TEXT, false);
        graphics.pose().popPose();
        MWStyle.fillF(graphics, 10, 27, width - 10, 28, MWStyle.border());
        MWStyle.fillF(graphics, 10, 27, 50, 29, MWStyle.signal());

        // Tabs - each carries its own colour; one underline glides between them.
        int tabX = 10;
        int indTargetX = 10;
        int indTargetW = 20;
        for (Tab candidate : Tab.values()) {
            int tabW = tabWidthOf(candidate);
            boolean active = candidate == tab;
            boolean hovered = mouseX >= tabX && mouseX < tabX + tabW && mouseY >= 32 && mouseY < 50;
            int tc = tabColor(candidate);
            MWStyle.strF(graphics, font, sentence(candidate.name()), tabX + 10, 37, active ? MWStyle.TEXT : hovered ? 0xFFB4BAC3 : MWStyle.TEXT_DIM, false);
            if (active) {
                indTargetX = tabX;
                indTargetW = tabW;
            }
            tabX += tabW + 4;
        }
        if (tabIndX < 0) {
            tabIndX = indTargetX;
            tabIndW = indTargetW;
        }
        tabIndX = MWStyle.approach(tabIndX, indTargetX, 0.4f, dtTicks);
        tabIndW = MWStyle.approach(tabIndW, indTargetW, 0.4f, dtTicks);
        MWStyle.fillF(graphics, Math.round(tabIndX), 46, Math.round(tabIndX + tabIndW), 48, tabColor(tab));

        // Status chip: always know whether what you see is saved.
        if (isFormTab()) {
            boolean isNew = tab != Tab.RULES && selectedId.isEmpty();
            String chip;
            int chipColor;
            if (bulkMode()) {
                chip = "Bulk edit \u2014 " + bulkSelected.size() + " selected";
                chipColor = 0xFFF5B945;
            } else if (dirtyAt > 0) {
                chip = isNew ? "New \u2014 not saved yet" : "Unsaved changes";
                chipColor = 0xFFF5B945;
            } else if (nowMs - lastSavedAt < 3500) {
                chip = "Saved";
                chipColor = MWStyle.POSITIVE;
            } else if (isNew) {
                chip = "New record";
                chipColor = MWStyle.TEXT_DIM;
            } else {
                chip = tab == Tab.RULES ? "Global rules" : "Editing " + selectedId;
                chipColor = MWStyle.TEXT_DIM;
            }
            chip = font.plainSubstrByWidth(chip, Math.max(60, width - 130));
            MWStyle.strF(graphics, font, chip, width - 10 - font.width(chip), 14, chipColor, false);
        }

        if (tab == Tab.STATS) {
            renderStatsTab(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        renderPanelsAndToolbar(graphics, mouseX, mouseY);

        if (addTab) {
            // Two stacked groups, each with its own title rule, label rows and widgets on fixed y's -
            // the old layout drew the hint and the flash message right behind the browse button.
            int fw = Math.min(360, width - formX - 18);
            int addColor = tabColor(Tab.ADD);
            MWStyle.strF(graphics, font, "Quick add", formX, 80, MWStyle.TEXT, false);
            MWStyle.fillF(graphics, formX, 90, formX + fw, 91, MWStyle.border());
            MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.admin.add_id_label").getString(),
                    formX, 96, MWStyle.TEXT_DIM, false);
            MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.admin.add_slot_label").getString(),
                    formX, 130, MWStyle.TEXT_DIM, false);
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, Component.translatable("screen.mwloadout.admin.add_hint").getString(), fw),
                    formX, 166, MWStyle.TEXT_DIM, false);
            MWStyle.fillF(graphics, formX, 188, formX + fw, 189, MWStyle.border());
            MWStyle.strF(graphics, font, "Creative inventory", formX, 196, MWStyle.TEXT, false);
            MWStyle.fillF(graphics, formX, 206, formX + fw, 207, MWStyle.border());
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, "Browse every item, auto-sorted into categories.", fw),
                    formX, 212, MWStyle.TEXT_DIM, false);
        }

        // List
        MWStyle.fillF(graphics, listX, 70, listX + listWidth, 71, MWStyle.border());
        int rowAccent = tabColor(tab);
        if (tab == Tab.RULES) {
            MWStyle.strF(graphics, font, "Sections", listX, 57, MWStyle.TEXT, false);
            // scroll-spy: highlight the section currently at the top of the form
            String spy = "";
            int overflowNow = Math.max(0, formContentBottom - (formBottom() - formTop));
            for (Map.Entry<String, Integer> sectionEntry : sectionContentY.entrySet()) {
                if (sectionEntry.getValue() <= formScroll + 24) {
                    spy = sectionEntry.getKey();
                }
            }
            if (overflowNow > 0 && formScroll >= overflowNow - 2 && !sectionContentY.isEmpty()) {
                spy = new ArrayList<>(sectionContentY.keySet()).get(sectionContentY.size() - 1);
            }
            activeSection = spy;
        } else {
            String count = String.valueOf(rows.size());
            MWStyle.strF(graphics, font, count, listX + listWidth - font.width(count), 57, MWStyle.TEXT_DIM, false);
        }
        graphics.enableScissor(0, listTop, listX + listWidth, listBottom);
        long sinceTab = nowMs - tabAnimStart;
        int stagger = rowStaggerMs();
        int rowY = listTop - (int) listScroll;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            if (rowY + 20 >= listTop && rowY <= listBottom) {
                // Rows cascade in on tab switch (rate differs per tab), sliding from the left.
                float rp = stagger <= 0 ? 1f : ease(Mth.clamp((sinceTab - Math.min(rowIndex, 24) * stagger) / 240f, 0f, 1f));
                if (rp > 0.02f) {
                    graphics.pose().pushPose();
                    graphics.pose().translate((int) ((1f - rp) * -16), 0, 0);
                    boolean selected = tab == Tab.RULES ? row.id.equals("sec:" + activeSection) : row.id.equals(selectedId);
                    boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= rowY && mouseY < rowY + 18
                            && mouseY >= listTop && mouseY < listBottom;
                    MWStyle.fillF(graphics, listX, rowY, listX + listWidth, rowY + 18, hovered ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
                    boolean bulkPicked = tab == Tab.CATALOG && bulkSelected.contains(row.id);
                    if (bulkPicked) {
                        MWStyle.fillF(graphics, listX, rowY, listX + listWidth, rowY + 18, MWStyle.withAlpha(rowAccent, 0.22f));
                        MWStyle.strF(graphics, font, "\u2713", listX + listWidth - 11, rowY + 5, rowAccent, false);
                    }
                    if (selected || bulkPicked) {
                        MWStyle.fillF(graphics, listX, rowY, listX + 2, rowY + 18, rowAccent);
                    }
                    int textX = listX + 6;
                    boolean stale = false;
                    if (tab == Tab.CATALOG) {
                        var iconEntry = LoadoutRegistry.get().entry(row.id());
                        if (iconEntry != null) {
                            var icon = dev.l41nx.mwloadout.client.gui.IconCache.iconFor(iconEntry);
                            if (!icon.isEmpty()) {
                                graphics.renderItem(icon, listX + 3, rowY + 1);
                            }
                            stale = iconEntry.stale;
                        }
                        textX = listX + 22;
                    }
                    String label = stale ? "! " + row.label : row.label;
                    int nameColor = stale ? MWStyle.NEGATIVE : selected ? rowAccent : MWStyle.TEXT;
                    int nameWidth = listWidth - (textX - listX) - (bulkPicked ? 18 : 6);
                    if (rp >= 0.98f) {
                        MWStyle.scrollText(graphics, font, label, textX, rowY + 5, nameWidth, nameColor, hovered || selected);
                    } else {
                        ftxt(graphics, MWStyle.ellipsize(font, label, nameWidth), textX, rowY + 5, nameColor, rp);
                    }
                    graphics.pose().popPose();
                }
            }
            rowY += 20;
        }
        graphics.disableScissor();

        // Visual scrollbar — mouse-wheel scrolling already worked, but with no draggable handle
        // there was no way to see how much content there was or jump to a spot in it directly.
        {
            int listTrackHeight = listBottom - listTop;
            int listContentHeight = rows.size() * 20;
            int listOverflow = Math.max(0, listContentHeight - listTrackHeight);
            if (listOverflow > 0) {
                int barX = listX + listWidth + 2;
                int barW = 4;
                MWStyle.fillF(graphics, barX, listTop, barX + barW, listBottom, MWStyle.PANEL_RAISED);
                int thumbH = Math.max(12, listTrackHeight * listTrackHeight / listContentHeight);
                int thumbY = listTop + (int) ((listTrackHeight - thumbH) * (listScroll / listOverflow));
                boolean thumbHovered = mouseX >= barX && mouseX < barX + barW && mouseY >= thumbY && mouseY < thumbY + thumbH;
                MWStyle.fillF(graphics, barX, thumbY, barX + barW, thumbY + thumbH,
                        scrollbarDragging || thumbHovered ? MWStyle.accent() : MWStyle.TEXT_DIM);
            }
        }

        // Form: collapsible sections, label rows and animated toggle pills.
        int fBottom = formBottom();
        int fieldW = width - formX - 18;
        int sectionColor = tabColor(tab);
        if (isFormTab()) {
            graphics.enableScissor(formX - 2, formTop - 4, width, fBottom);
            for (FormRow formRow : formRows) {
                if (formRow.y() + 16 < formTop - 4 || formRow.y() > fBottom) {
                    continue;
                }
                if (formRow.type() == 0) {
                    boolean hov = mouseX >= formRow.x() && mouseX < formRow.x() + fieldW && mouseY >= formRow.y()
                            && mouseY < formRow.y() + 16 && mouseY >= formTop && mouseY < fBottom;
                    MWStyle.fillF(graphics, formRow.x(), formRow.y(), formRow.x() + fieldW, formRow.y() + 16,
                            MWStyle.withAlpha(sectionColor, (hov ? 0.24f : 0.13f) * enter));
                    MWStyle.fillF(graphics, formRow.x(), formRow.y(), formRow.x() + 2, formRow.y() + 16, MWStyle.withAlpha(sectionColor, enter));
                    ftxt(graphics, (formRow.collapsed() ? "\u25B8 " : "\u25BE ") + formRow.name(), formRow.x() + 7, formRow.y() + 4, sectionColor, enter);
                    String countText = String.valueOf(formRow.count());
                    ftxt(graphics, countText, formRow.x() + fieldW - 6 - font.width(countText), formRow.y() + 4, MWStyle.TEXT_DIM, enter);
                } else {
                    EditBox box = fields.get(formRow.name());
                    boolean focused = box != null && box.isFocused();
                    ftxt(graphics, MWStyle.ellipsize(font, labelOf(formRow.name()), LABEL_W - 6), formRow.x() + 4, formRow.y() + 3,
                            focused ? sectionColor : MWStyle.TEXT_DIM, enter);
                    MWStyle.fillF(graphics, formRow.x() + LABEL_W, formRow.y() + 13, formRow.x() + fieldW, formRow.y() + 14,
                            focused ? sectionColor : MWStyle.border());
                }
            }
            for (ToggleRect toggleRect : toggleRects) {
                boolean value = toggles.getOrDefault(toggleRect.name, false);
                boolean hov = mouseX >= toggleRect.x && mouseX < toggleRect.x + toggleRect.w
                        && mouseY >= toggleRect.y && mouseY < toggleRect.y + toggleRect.h;
                float knob = MWStyle.approach(toggleAnim.getOrDefault(toggleRect.name, value ? 1f : 0f), value ? 1f : 0f, 0.5f, dtTicks);
                toggleAnim.put(toggleRect.name, knob);
                if (hov) {
                    MWStyle.fillF(graphics, toggleRect.x - 3, toggleRect.y - 1, toggleRect.x + toggleRect.w, toggleRect.y + toggleRect.h,
                            MWStyle.withAlpha(MWStyle.PANEL_HOVER, 0.8f * enter));
                }
                // MW-style ON/OFF slider pill: track + sliding knob, green-lit when on.
                int trackWidth = 22;
                int trackHeight = 10;
                int trackY = toggleRect.y + 1;
                MWStyle.fillF(graphics, toggleRect.x, trackY, toggleRect.x + trackWidth, trackY + trackHeight, MWStyle.PANEL_RAISED);
                MWStyle.fillF(graphics, toggleRect.x, trackY, toggleRect.x + trackWidth, trackY + trackHeight,
                        MWStyle.withAlpha(MWStyle.POSITIVE, 0.35f * knob * enter));
                MWStyle.hairline(graphics, toggleRect.x, trackY, trackWidth, trackHeight, value ? MWStyle.POSITIVE : MWStyle.border());
                int knobX = toggleRect.x + 1 + Math.round(knob * (trackWidth - 10));
                MWStyle.fillF(graphics, knobX, trackY + 1, knobX + 8, trackY + trackHeight - 1, value ? MWStyle.POSITIVE : MWStyle.TEXT_DIM);
                ftxt(graphics, labelOf(toggleRect.name) + (value ? "  ON" : "  OFF"), toggleRect.x + trackWidth + 5, toggleRect.y + 2,
                        value ? MWStyle.TEXT : MWStyle.TEXT_DIM, enter);
            }
            graphics.disableScissor();
            int formOverflow = formContentBottom - (fBottom - formTop);
            if (formOverflow > 0) {
                int trackHeight = fBottom - formTop;
                int barHeight = Math.max(14, trackHeight * trackHeight / Math.max(1, formContentBottom));
                int barY = formTop + (int) (formScroll / formOverflow * (trackHeight - barHeight));
                MWStyle.fillF(graphics, width - 11, formTop, width - 9, fBottom, MWStyle.PANEL_RAISED);
                MWStyle.fillF(graphics, width - 11, barY, width - 9, barY + barHeight, sectionColor);
            }
        }

        // Live blueprint validation: every comma-separated gameId in blueprintGameIds gets
        // checked against the registry right now (as typed, before Save) and shown as
        // found+enabled / found-but-disabled / not-found — no more guessing whether an id
        // "took" or silently failed to resolve.
        if (tab == Tab.CATALOG && toggles.getOrDefault("hasBlueprint", false)) {
            EditBox blueprintBox = fields.get("blueprintGameIds");
            if (blueprintBox != null && blueprintBox.visible) {
                List<String> typed = splitList(fieldValue("blueprintGameIds"));
                int vx = blueprintBox.getX();
                int vy = blueprintBox.getY() + 16;
                int formBottomLimit = formBottom();
                if (typed.isEmpty()) {
                    MWStyle.strF(graphics, font, "Type TACZ gameIds (e.g. tacz:g36k), separated by commas - as many as you like",
                            vx, vy, MWStyle.TEXT_DIM, false);
                } else {
                    for (String gameId : typed) {
                        if (vy > formBottomLimit) {
                            MWStyle.strF(graphics, font, "... (" + (typed.size()) + " in total, the rest is hidden behind the footer)",
                                    vx, vy, MWStyle.TEXT_DIM, false);
                            break;
                        }
                        CatalogEntry match = LoadoutRegistry.get().entryByGameId(gameId);
                        String line;
                        int color;
                        if (match == null) {
                            line = "\u2717 " + gameId + " \u2014 kh\u00f4ng t\u00ecm th\u1ea5y (addon ch\u01b0a load / g\u00f5 sai?)";
                            color = MWStyle.NEGATIVE;
                        } else if (!match.enabled) {
                            line = "\u2717 " + gameId + " \u2192 " + match.displayName + " (\u0111ang b\u1ecb disable trong catalog)";
                            color = MWStyle.NEGATIVE;
                        } else {
                            line = "\u2713 " + gameId + " \u2192 " + match.displayName;
                            color = MWStyle.POSITIVE;
                        }
                        MWStyle.strF(graphics, font, line, vx, vy, color, false);
                        vy += 10;
                    }
                }
            }
        }

        if (bulkMode()) {
            int right = width - 14;
            MWStyle.fillF(graphics, formX, formTop, right, formTop + 16, MWStyle.withAlpha(0xFFF5B945, 0.2f));
            MWStyle.fillF(graphics, formX, formTop, formX + 2, formTop + 16, 0xFFF5B945);
            MWStyle.strF(graphics, font, "BULK EDIT  \u2022  " + bulkSelected.size() + " SELECTED", formX + 7, formTop + 4, 0xFFF5B945, false);
            MWStyle.strF(graphics, font, "MOVE TO CATEGORY", formX, 131, MWStyle.TEXT_DIM, false);
            MWStyle.strF(graphics, font, "UNLOCK LEVEL", formX, 165, MWStyle.TEXT_DIM, false);
            StringBuilder names = new StringBuilder();
            int shownNames = 0;
            for (String id : bulkSelected) {
                if (shownNames >= 6) {
                    break;
                }
                CatalogEntry entry = LoadoutRegistry.get().entry(id);
                names.append(shownNames == 0 ? "" : ", ").append(entry != null ? dev.l41nx.mwloadout.client.gui.DisplayNames.of(entry) : id);
                shownNames++;
            }
            if (bulkSelected.size() > shownNames) {
                names.append("  +").append(bulkSelected.size() - shownNames).append(" more");
            }
            MWStyle.strF(graphics, font, font.plainSubstrByWidth(names.toString(), right - formX), formX, 246, MWStyle.TEXT_DIM, false);
            MWStyle.strF(graphics, font, "Ctrl+click toggles  \u2022  Shift+click ranges  \u2022  Ctrl+A all listed  \u2022  Esc leaves multi-select",
                    formX, 260, MWStyle.TEXT_DIM, false);
        } else if (isFormTab() || tab == Tab.ADD) {
            renderHintBar(graphics, mouseX, mouseY, nowMs);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Fade-safe text: vanilla draws any colour with alpha < 4/255 as fully OPAQUE, so "fading"
     *  text must be skipped when too faint instead of drawn with a tiny alpha. */
    private void ftxt(GuiGraphics graphics, String text, int x, int y, int color, float alpha) {
        if (alpha < 0.1f) {
            return;
        }
        MWStyle.strF(graphics, font, text, x, y, alpha >= 0.98f ? color : MWStyle.withAlpha(color, alpha), false);
    }

    /** Two-line context bar under the form: a pending toast first, else the hint for the field /
     *  toggle / section under the mouse (or being typed in), else the keyboard-shortcut legend. */
    private void renderHintBar(GuiGraphics graphics, int mouseX, int mouseY, long nowMs) {
        int y = formBottom() + 6;
        int limitX = toolsRightEdge() - 8;
        int maxW = Math.max(80, limitX - listX);
        String text;
        int color = MWStyle.TEXT_DIM;
        if (nowMs < flashUntil) {
            text = flashMessage;
            color = MWStyle.TEXT;
        } else {
            text = tab == Tab.ADD ? null : hoveredHint(mouseX, mouseY);
            if (text == null) {
                text = tab == Tab.ADD ? "Enter adds the typed item  \u2022  Ctrl+B opens the creative browser  \u2022  Ctrl+1-6 tabs" : LEGEND;
            }
        }
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(text), maxW);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            MWStyle.strF(graphics, font, lines.get(i), listX, y + i * 9, color, false);
        }
    }

    private String hoveredHint(int mouseX, int mouseY) {
        if (mouseY >= formTop && mouseY < formBottom() && mouseX >= formX) {
            for (ToggleRect toggleRect : toggleRects) {
                if (mouseX >= toggleRect.x && mouseX < toggleRect.x + toggleRect.w
                        && mouseY >= toggleRect.y && mouseY < toggleRect.y + toggleRect.h) {
                    return HINTS.get(toggleRect.name);
                }
            }
            for (HeaderRect header : headerRects) {
                if (mouseX >= header.x() && mouseX < header.x() + header.w() && mouseY >= header.y() && mouseY < header.y() + header.h()) {
                    return "Click to collapse / expand this section.";
                }
            }
            for (FormRow row : formRows) {
                if (row.type() == 1 && mouseY >= row.y() && mouseY < row.y() + 18) {
                    String hint = HINTS.get(row.name());
                    if (hint != null) {
                        return hint;
                    }
                }
            }
        }
        for (Map.Entry<String, EditBox> entry : fields.entrySet()) {
            if (entry.getValue().visible && entry.getValue().isFocused()) {
                return HINTS.get(entry.getKey());
            }
        }
        return null;
    }

    /** Called by Net.java's S2CSyncStats handler when a fresh snapshot arrives — render() already
     *  reads UsageStats.get() live every frame, so there's nothing to recompute here. */
    public void onStatsRefreshed() {
    }

    /** Stock-chart-style bar chart for the admin Stats tab: view-mode pills at top, vertical bars
     *  sorted descending by count, hover shows the name + detail in a tooltip. Weapons additionally
     *  show kills/headshot%/avg TTK/hits from combat tracking (see UsageStats doc). */
    private void renderStatsTab(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var stats = dev.l41nx.mwloadout.registry.UsageStats.get();
        int areaX = listX;
        int areaRight = width - 10;
        int pillTop = 54;
        int pillBottom = 72;

        // Toolbar: VIEW selector (one bordered segmented control) + RESET, clearly below the tab bar.
        MWStyle.strF(graphics, font, "VIEW", areaX, pillTop + 5, MWStyle.TEXT_DIM, false);
        drawSegmented(graphics, areaX + font.width("VIEW") + 8, pillTop, statsViewLabels(), statsView.ordinal(),
                tabColor(Tab.STATS), null, mouseX, mouseY);
        String resetLabel = "RESET";
        int resetW = font.width(resetLabel) + 16;
        int resetX = areaRight - resetW;
        boolean resetHovered = mouseX >= resetX && mouseX < areaRight && mouseY >= pillTop && mouseY < pillBottom;
        MWStyle.fillF(graphics, resetX, pillTop, areaRight, pillBottom, resetHovered ? MWStyle.withAlpha(MWStyle.NEGATIVE, 0.35f) : MWStyle.PANEL_RAISED);
        MWStyle.hairline(graphics, resetX, pillTop, resetW, 18, resetHovered ? MWStyle.NEGATIVE : MWStyle.border());
        MWStyle.strF(graphics, font, resetLabel, resetX + 8, pillTop + 5, resetHovered ? MWStyle.NEGATIVE : MWStyle.TEXT_DIM, false);
        MWStyle.fillF(graphics, areaX, pillBottom + 5, areaRight, pillBottom + 6, MWStyle.border());

        // ---- dashboard: KPI cards, ranked bars (animated), insight panel, pick-share strip ----
        long nowMs = System.currentTimeMillis();
        long sinceTab = nowMs - tabAnimStart;
        float dt = MWStyle.frameDt();
        int teal = tabColor(Tab.STATS);
        int top = pillBottom + 12;
        int cardH = 38;
        int gap = 6;
        int cardW = (areaRight - areaX - gap * 3) / 4;

        // KPI totals walk the whole catalog + player table: refresh twice a second, not every frame.
        if (nowMs - statsKpiAt > 500) {
            statsKpiAt = nowMs;
            int kills = 0;
            float headshotWeighted = 0f;
            for (CatalogEntry gun : LoadoutRegistry.get().catalogList()) {
                if (gun.kind == CatalogEntry.Kind.GUN) {
                    int gunKills = stats.killsFor(gun.gameId);
                    kills += gunKills;
                    float ratio = stats.headshotRatio(gun.gameId);
                    if (ratio >= 0f) {
                        headshotWeighted += ratio * gunKills;
                    }
                }
            }
            kpiKills = kills;
            kpiHeadshot = kills > 0 ? headshotWeighted / kills : -1f;
            kpiPlayers = stats.topPlayers(10000).size();
        }
        int totalKills = kpiKills;
        float headshotOverall = kpiHeadshot;
        int playerCount = kpiPlayers;
        String since = stats.trackingSince() <= 0 ? "N/A"
                : new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(stats.trackingSince()));
        long days = stats.trackingSince() <= 0 ? 0 : Math.max(0, (nowMs - stats.trackingSince()) / 86_400_000L);
        float countUp = ease(Mth.clamp((sinceTab - 150) / 700f, 0f, 1f));
        statsCard(graphics, 0, areaX, top, cardW, cardH, sinceTab, "TOTAL PICKS", String.valueOf(Math.round(stats.totalPicks() * countUp)), teal);
        statsCard(graphics, 1, areaX + (cardW + gap), top, cardW, cardH, sinceTab, "TOTAL KILLS", String.valueOf(Math.round(totalKills * countUp)), 0xFFFF6B5E);
        statsCard(graphics, 2, areaX + (cardW + gap) * 2, top, cardW, cardH, sinceTab, "HEADSHOT %",
                headshotOverall < 0 ? "-" : Math.round(headshotOverall * 100f * countUp) + "%", 0xFFF5B945);
        statsCard(graphics, 3, areaX + (cardW + gap) * 3, top, cardW, cardH, sinceTab, "PLAYERS \u2022 " + days + "d tracked",
                String.valueOf(Math.round(playerCount * countUp)), 0xFFB57BFF);

        int chartTop = top + cardH + 12;
        int chartBottom = height - 40;
        int leftW = (int) ((areaRight - areaX) * 0.62f);
        int rowH = 15;
        int maxRows = Mth.clamp((chartBottom - chartTop - 16) / rowH, 3, 20);
        List<StatItem> items = statsItems(stats, maxRows);
        statsHoveredKey = "";
        String viewTitle = switch (statsView) {
            case WEAPONS -> "TOP WEAPONS  \u2022  picks";
            case PERKS -> "TOP PERKS  \u2022  picks";
            case CATEGORIES -> "CATEGORIES  \u2022  picks";
            case PLAYERS -> "TOP PLAYERS  \u2022  picks";
        };
        MWStyle.strF(graphics, font, viewTitle, areaX, chartTop, teal, false);
        MWStyle.fillF(graphics, areaX, chartTop + 11, areaX + leftW, chartTop + 12, MWStyle.border());
        if (items.isEmpty()) {
            MWStyle.strF(graphics, font, "No data yet - picks and kills are tracked as players play.", areaX, chartTop + 22, MWStyle.TEXT_DIM, false);
            return;
        }
        int maxValue = 1;
        long totalValue = 0;
        for (StatItem item : items) {
            maxValue = Math.max(maxValue, item.value());
            totalValue += item.value();
        }
        int labelW = Math.min(130, (int) (leftW * 0.38f));
        int barX = areaX + labelW + 4;
        int barMaxW = leftW - labelW - 4 - 34;
        long barSince = nowMs - statsAnimStart;
        int y = chartTop + 16;
        for (int i = 0; i < items.size(); i++) {
            StatItem item = items.get(i);
            boolean hovered = mouseX >= areaX && mouseX < areaX + leftW && mouseY >= y && mouseY < y + rowH - 1;
            if (hovered) {
                statsHoveredKey = item.key();
            }
            float target = barSince > 120 + i * 35L ? item.value() / (float) maxValue : 0f;
            statsBarAnim[i] = MWStyle.approach(statsBarAnim[i], target, 0.35f, dt);
            if (hovered) {
                MWStyle.fillF(graphics, areaX, y - 1, areaX + leftW, y + rowH - 2, MWStyle.withAlpha(MWStyle.PANEL_HOVER, 0.9f));
            }
            MWStyle.strF(graphics, font, font.plainSubstrByWidth(item.label(), labelW - 4), areaX + 2, y + 2,
                    hovered ? teal : MWStyle.TEXT, false);
            MWStyle.fillF(graphics, barX, y + 2, barX + barMaxW, y + rowH - 5, MWStyle.PANEL_RAISED);
            int filled = Math.max(statsBarAnim[i] > 0.001f ? 2 : 0, Math.round(barMaxW * statsBarAnim[i]));
            MWStyle.fillF(graphics, barX, y + 2, barX + filled, y + rowH - 5, MWStyle.withAlpha(teal, hovered ? 1f : 0.7f));
            String valueText = String.valueOf(item.value());
            MWStyle.strF(graphics, font, valueText, areaX + leftW - font.width(valueText), y + 2, MWStyle.TEXT_DIM, false);
            y += rowH;
        }

        // ---- insight panel (hovered row, else the leader) ----
        int panelX = areaX + leftW + 10;
        int panelW = areaRight - panelX;
        StatItem focus = items.get(0);
        for (StatItem item : items) {
            if (item.key().equals(statsHoveredKey)) {
                focus = item;
            }
        }
        MWStyle.strF(graphics, font, "INSIGHT", panelX, chartTop, teal, false);
        MWStyle.fillF(graphics, panelX, chartTop + 11, areaRight, chartTop + 12, MWStyle.border());
        int py = chartTop + 18;
        MWStyle.strF(graphics, font, font.plainSubstrByWidth(focus.label(), panelW), panelX, py, MWStyle.TEXT, false);
        py += 13;
        float share = totalValue > 0 ? focus.value() * 100f / totalValue : 0f;
        py = insightLine(graphics, panelX, py, panelW, "Picks", focus.value() + "  (" + String.format(java.util.Locale.ROOT, "%.1f%%", share) + ")");
        if (statsView == StatsView.WEAPONS) {
            CatalogEntry entry = LoadoutRegistry.get().entry(focus.key());
            if (entry != null) {
                float hs = stats.headshotRatio(entry.gameId);
                float ttk = stats.averageTtkSeconds(entry.gameId);
                py = insightLine(graphics, panelX, py, panelW, "Kills", String.valueOf(stats.killsFor(entry.gameId)));
                py = insightLine(graphics, panelX, py, panelW, "Hits landed", String.valueOf(stats.hitsFor(entry.gameId)));
                py = insightLine(graphics, panelX, py, panelW, "Headshot", hs < 0 ? "N/A" : String.format(java.util.Locale.ROOT, "%.0f%%", hs * 100f));
                if (hs >= 0) {
                    MWStyle.fillF(graphics, panelX, py - 2, panelX + panelW, py + 2, MWStyle.PANEL_RAISED);
                    MWStyle.fillF(graphics, panelX, py - 2, panelX + Math.round(panelW * hs), py + 2, 0xFFF5B945);
                    py += 6;
                }
                py = insightLine(graphics, panelX, py, panelW, "Avg TTK", ttk < 0 ? "N/A" : String.format(java.util.Locale.ROOT, "%.1fs", ttk));
            }
        }

        // ---- pick-share strip: where the volume sits ----
        int stripY = Math.min(chartBottom - 34, py + 8);
        if (stripY > py) {
            MWStyle.strF(graphics, font, "PICK SHARE (top 5)", panelX, stripY, MWStyle.TEXT_DIM, false);
            int[] palette = {0xFF4DD0E1, 0xFF5BB8FF, 0xFFB57BFF, 0xFFF5B945, 0xFFFF6B5E, 0xFF5A5F69};
            int sx = panelX;
            long shown = 0;
            for (int i = 0; i < Math.min(5, items.size()); i++) {
                shown += items.get(i).value();
            }
            long rest = Math.max(0, totalValue - shown);
            for (int i = 0; i < Math.min(5, items.size()) + (rest > 0 ? 1 : 0); i++) {
                long v = i < Math.min(5, items.size()) ? items.get(i).value() : rest;
                int w = totalValue > 0 ? (int) Math.round(panelW * (v / (double) totalValue)) : 0;
                if (w <= 0) {
                    continue;
                }
                int right = Math.min(panelX + panelW, sx + w);
                MWStyle.fillF(graphics, sx, stripY + 12, right, stripY + 20, palette[Math.min(i, palette.length - 1)]);
                sx = right;
            }
        }
    }

    private record StatItem(String key, String label, int value) {
    }

    private float[] statsBarAnim = new float[20];
    private long statsAnimStart = System.currentTimeMillis();

    private void resetStatsAnim() {
        java.util.Arrays.fill(statsBarAnim, 0f);
        statsAnimStart = System.currentTimeMillis();
    }

    /** Ranked stat rows (sorting + name lookups) cached for 400 ms - they used to be rebuilt every frame. */
    private List<StatItem> statsItems(dev.l41nx.mwloadout.registry.UsageStats stats, int limit) {
        long now = System.currentTimeMillis();
        int key = statsView.ordinal() * 100 + limit;
        if (key == statsCacheKey && now - statsCacheAt < 400) {
            return statsCache;
        }
        statsCacheKey = key;
        statsCacheAt = now;
        statsCache = computeStatsItems(stats, limit);
        return statsCache;
    }

    private List<StatItem> computeStatsItems(dev.l41nx.mwloadout.registry.UsageStats stats, int limit) {
        List<StatItem> out = new ArrayList<>();
        switch (statsView) {
            case WEAPONS -> stats.topWeapons(limit).forEach(e -> {
                CatalogEntry entry = LoadoutRegistry.get().entry(e.getKey());
                out.add(new StatItem(e.getKey(), entry != null ? dev.l41nx.mwloadout.client.gui.DisplayNames.of(entry) : e.getKey(), e.getValue()));
            });
            case PERKS -> stats.topPerks(limit).forEach(e -> {
                var perk = LoadoutRegistry.get().perk(e.getKey());
                out.add(new StatItem(e.getKey(), perk != null ? perk.displayName : e.getKey(), e.getValue()));
            });
            case CATEGORIES -> stats.topCategories(limit).forEach(e ->
                    out.add(new StatItem(e.getKey(), e.getKey().toUpperCase(java.util.Locale.ROOT), e.getValue())));
            case PLAYERS -> stats.topPlayers(limit).forEach(e ->
                    out.add(new StatItem(e.getKey().toString(), stats.playerName(e.getKey()), e.getValue())));
        }
        return out;
    }

    private int insightLine(GuiGraphics graphics, int x, int y, int w, String label, String value) {
        MWStyle.strF(graphics, font, label, x, y, MWStyle.TEXT_DIM, false);
        MWStyle.strF(graphics, font, font.plainSubstrByWidth(value, Math.max(20, w - font.width(label) - 6)),
                x + w - font.width(font.plainSubstrByWidth(value, Math.max(20, w - font.width(label) - 6))), y, MWStyle.TEXT, false);
        return y + 11;
    }

    private void statsCard(GuiGraphics graphics, int index, int x, int y, int w, int h, long sinceTab,
                           String label, String value, int color) {
        float p = ease(Mth.clamp((sinceTab - index * 70L) / 260f, 0f, 1f));
        if (p < 0.03f) {
            return;
        }
        int slide = (int) ((1f - p) * 10);
        MWStyle.fillF(graphics, x, y + slide, x + w, y + h + slide, MWStyle.PANEL_RAISED);
        MWStyle.fillF(graphics, x, y + slide, x + 2, y + h + slide, MWStyle.withAlpha(color, p));
        ftxt(graphics, font.plainSubstrByWidth(label, w - 10), x + 7, y + 5 + slide, MWStyle.TEXT_DIM, p);
        if (p >= 0.1f) {
            graphics.pose().pushPose();
            graphics.pose().translate(x + 7, y + 17 + slide, 0);
            graphics.pose().scale(1.6f, 1.6f, 1f);
            MWStyle.strF(graphics, font, value, 0, 0, p >= 0.98f ? color : MWStyle.withAlpha(color, p), false);
            graphics.pose().popPose();
        }
    }

    private void renderStatsTooltip(GuiGraphics graphics, int mouseX, int mouseY, String key, boolean isPlayers) {
        var stats = dev.l41nx.mwloadout.registry.UsageStats.get();
        List<String> lines = new ArrayList<>();
        if (isPlayers) {
            java.util.UUID uuid = java.util.UUID.fromString(key);
            lines.add(stats.playerName(uuid));
        } else if (statsView == StatsView.WEAPONS) {
            CatalogEntry entry = LoadoutRegistry.get().entry(key);
            lines.add(entry != null ? dev.l41nx.mwloadout.client.gui.DisplayNames.of(entry) : key);
            lines.add("Picks: " + stats.pickCount(key));
            if (entry != null) {
                int kills = stats.killsFor(entry.gameId);
                int hits = stats.hitsFor(entry.gameId);
                float hs = stats.headshotRatio(entry.gameId);
                float ttk = stats.averageTtkSeconds(entry.gameId);
                lines.add("Kills: " + kills + "   Hits landed: " + hits);
                lines.add("Headshot ratio: " + (hs < 0 ? "N/A" : String.format(java.util.Locale.ROOT, "%.0f%%", hs * 100)));
                lines.add("Avg TTK: " + (ttk < 0 ? "N/A" : String.format(java.util.Locale.ROOT, "%.1fs", ttk)));
            }
        } else if (statsView == StatsView.PERKS) {
            var perk = LoadoutRegistry.get().perk(key);
            lines.add(perk != null ? perk.displayName : key);
            lines.add("Picks: " + stats.pickCount(key));
        } else {
            lines.add(key.toUpperCase(java.util.Locale.ROOT));
        }
        int tw = 0;
        for (String line : lines) {
            tw = Math.max(tw, font.width(line));
        }
        int tx = Math.min(mouseX + 10, width - tw - 16);
        int ty = mouseY - 8;
        int th = lines.size() * 10 + 8;
        MWStyle.fillF(graphics, tx - 4, ty - 4, tx + tw + 8, ty + th, MWStyle.PANEL_RAISED);
        MWStyle.hairline(graphics, tx - 4, ty - 4, tw + 12, th, MWStyle.borderActive());
        int ly = ty;
        for (int i = 0; i < lines.size(); i++) {
            MWStyle.strF(graphics, font, lines.get(i), tx, ly, i == 0 ? MWStyle.accent() : MWStyle.TEXT, false);
            ly += 10;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= formX && mouseY >= formTop && isFormTab()) {
            int formOverflow = Math.max(0, formContentBottom - (formBottom() - formTop));
            if (formOverflow > 0) {
                formScrollTarget = Mth.clamp(formScrollTarget - delta * 28, 0, formOverflow); // eased in render()
                return true;
            }
        }
        if (mouseX < listX + listWidth) {
            int overflow = Math.max(0, rows.size() * 20 - (listBottom - listTop));
            listScrollTarget = Mth.clamp(listScrollTarget - delta * 24, 0, overflow);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int tabX = 10;
            for (Tab candidate : Tab.values()) {
                int tabW = tabWidthOf(candidate);
                if (mouseX >= tabX && mouseX < tabX + tabW && mouseY >= 32 && mouseY < 50) {
                    switchTab(candidate);
                    return true;
                }
                tabX += tabW + 4;
            }
            if (tab == Tab.STATS) {
                int seg = segmentAt(listX + font.width("VIEW") + 8, 54, statsViewLabels(), (int) mouseX, (int) mouseY);
                if (seg >= 0) {
                    statsView = StatsView.values()[seg];
                    resetStatsAnim();
                    MWStyle.playClick();
                    return true;
                }
                String resetLabel = "RESET";
                int resetW = font.width(resetLabel) + 16;
                int resetX = width - 10 - resetW;
                if (mouseX >= resetX && mouseX < width - 10 && mouseY >= 54 && mouseY < 72) {
                    Net.CHANNEL.sendToServer(new Net.C2SResetStats());
                    MWStyle.playClick();
                    return true;
                }
                return true; // swallow clicks elsewhere on this tab — no list/form to hit below
            }
            if (tab == Tab.CATALOG) {
                int seg = segmentAt(formX + font.width("FILTER") + 8, 54, catalogFilterLabels(), (int) mouseX, (int) mouseY);
                if (seg >= 0) {
                    catalogFilter = CatalogFilter.values()[seg];
                    listScrollTarget = 0;
                    rebuildList();
                    MWStyle.playClick();
                    return true;
                }
            }
            {
                int trackHeight = listBottom - listTop;
                int scrollOverflow = Math.max(0, rows.size() * 20 - trackHeight);
                int barX = listX + listWidth + 2;
                if (scrollOverflow > 0 && mouseX >= barX && mouseX < barX + 4
                        && mouseY >= listTop && mouseY < listBottom) {
                    scrollbarDragging = true;
                    int thumbH = Math.max(12, trackHeight * trackHeight / (rows.size() * 20));
                    double ratio = Mth.clamp((mouseY - listTop - thumbH / 2.0) / (trackHeight - thumbH), 0, 1);
                    listScrollTarget = ratio * scrollOverflow;
                    listScroll = listScrollTarget;
                    return true;
                }
            }
            if (mouseX >= listX && mouseX < listX + listWidth && mouseY >= listTop && mouseY < listBottom) {
                int index = (int) ((mouseY - listTop + listScroll) / 20);
                if (index >= 0 && index < rows.size()) {
                    MWStyle.playClick();
                    if (tab == Tab.ADD) {
                        // Reference click: drop the picked entry's item id into the add box so you
                        // can clone/tweak it, without leaving the ADD form.
                        var entry = LoadoutRegistry.get().entry(rows.get(index).id);
                        if (entry != null && quickIdBox != null) {
                            quickIdBox.setValue(entry.gameId);
                            quickIdBox.setFocused(true);
                        }
                    } else if (tab == Tab.RULES) {
                        jumpToSection(rows.get(index).id.substring(4));
                    } else if (tab == Tab.CATALOG && (hasControlDown() || hasShiftDown() || !bulkSelected.isEmpty())) {
                        toggleBulk(index);
                    } else {
                        selectedId = rows.get(index).id;
                        rebuildForm();
                    }
                    return true;
                }
            }
            if (isFormTab() && mouseY >= formTop && mouseY < formBottom()) {
                for (HeaderRect header : headerRects) {
                    if (mouseX >= header.x() && mouseX < header.x() + header.w() && mouseY >= header.y() && mouseY < header.y() + header.h()) {
                        MWStyle.playClick();
                        if (!collapsedSections.remove(header.key())) {
                            collapsedSections.add(header.key());
                        }
                        layoutForm();
                        int overflow = Math.max(0, formContentBottom - (formBottom() - formTop));
                        formScrollTarget = Math.min(formScrollTarget, overflow);
                        return true;
                    }
                }
            }
            for (ToggleRect toggleRect : toggleRects) {
                if (mouseX >= toggleRect.x && mouseX < toggleRect.x + toggleRect.w
                        && mouseY >= toggleRect.y && mouseY < toggleRect.y + toggleRect.h) {
                    MWStyle.playClick();
                    toggles.put(toggleRect.name, !toggles.getOrDefault(toggleRect.name, false));
                    markDirty();
                    layoutForm();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollbarDragging && button == 0) {
            int trackHeight = listBottom - listTop;
            int scrollOverflow = Math.max(0, rows.size() * 20 - trackHeight);
            if (scrollOverflow > 0) {
                int thumbH = Math.max(12, trackHeight * trackHeight / (rows.size() * 20));
                double ratio = Mth.clamp((mouseY - listTop - thumbH / 2.0) / (trackHeight - thumbH), 0, 1);
                listScrollTarget = ratio * scrollOverflow;
                listScroll = listScrollTarget;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && scrollbarDragging) {
            scrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Row(String id, String label) {
    }

    private record ToggleRect(String name, int x, int y, int w, int h) {
    }
}
