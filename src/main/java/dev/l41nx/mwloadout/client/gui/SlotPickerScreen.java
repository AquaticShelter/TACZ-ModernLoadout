package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientCache;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.server.LoadoutApplier;
import dev.l41nx.mwloadout.server.TaczStackFactory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MW19 weapon select: a single VERTICAL list per category — guns render as their HUD silhouette
 * rows (no vanilla item icons anywhere near a firearm) with a GUNSMITH dock on every row;
 * non-gun equipment rows keep name + real item icon. Category tabs + one search box that matches
 * name, id, category, AND ammo type ("556" finds every 5.56 gun). The current selection is
 * pinned to the top of its list, scrolling is eased (wheel sets a target, the view chases it),
 * and picking a gun automatically applies that gun's ACTIVE saved build from the player's
 * gunsmith memory.
 *
 * Overkill second-primary: the gun already in the PRIMARY slot is filtered out entirely —
 * no-duplicates enforced at the UI, same rule the server validates.
 */
public final class SlotPickerScreen extends MwScreen {

    private static final int SEARCH_W = 160;
    private static final int STATS_WIDTH = 188; // (kept for the legacy panel maths that remains in helpers)
    private static final int GUN_ROW_HEIGHT = 38;
    private static final int ITEM_ROW_HEIGHT = 24;
    private static final int GUNSMITH_WIDTH = 58;

    private final LoadoutScreen parent;
    private final LoadoutPreset working;
    private final SlotDefinition slot;
    private long openedAt = System.currentTimeMillis();
    // Screen transition state: transitionTo() arms a 120ms fade-out veil, then the real
    // setScreen fires; opening plays the veil in reverse. Input is blocked while closing.
    private net.minecraft.client.gui.screens.Screen pendingScreen;
    private boolean pendingClose;
    private long closingAt;

    private final List<String> categories = new ArrayList<>();
    // Animated hover progress per category tab (0..1), eased the same way every other hoverable
    // element in this UI is (MWStyle.easeHover) — keyed by category string since tabs are static
    // for the screen's lifetime.
    private final java.util.Map<String, Float> tabHoverAnim = new java.util.HashMap<>();
    /** Requested tab order: Assault Rifles, SMGs, Shotguns, LMGs, Marksman Rifles, Sniper Rifles
     *  first — anything else (pistol, launcher, battle rifle...) keeps its existing relative
     *  order and sorts after these six. */
    private static final List<String> CATEGORY_ORDER = List.of("ar", "smg", "sg", "lmg", "dmr", "sr");

    private static int categoryOrder(String category) {
        int index = CATEGORY_ORDER.indexOf(category);
        return index >= 0 ? index : CATEGORY_ORDER.size();
    }

    /** Full display name for the tab/list/detail labels — the underlying category string (ar,
     *  smg, sg, lmg, dmr, sr...) never changes, this is presentation-only. Unknown categories
     *  fall back to their raw uppercased string, same as before. */
    static String categoryDisplayName(String category) {
        return switch (category) {
            case "ar" -> "ASSAULT RIFLES";
            case "smg" -> "SMGS";
            case "sg" -> "SHOTGUNS";
            case "lmg" -> "LMGS";
            case "dmr" -> "MARKSMAN RIFLES";
            case "sr" -> "SNIPER RIFLES";
            case "br" -> "BATTLE RIFLES";
            default -> category.toUpperCase(Locale.ROOT);
        };
    }

    /** Category tabs render at full text scale — same as every other cell in this UI. They used
     *  to run at a 0.8x fractional scale to fit 6 full names (ASSAULT RIFLES, MARKSMAN RIFLES,
     *  SNIPER RIFLES...) side by side, but scaling Minecraft's bitmap font by a non-integer factor
     *  makes it render aliased/jagged — noticeably rougher than every other 1:1-scale label in the
     *  screen. Fixed at 1.0 now; the fit problem is solved with generous inter-tab spacing instead
     *  (see the +20/+10 padding below), not by degrading the font. */
    private static int scaledTabWidth(net.minecraft.client.gui.Font font, String label) {
        return font.width(label) + 20;
    }
    private String activeCategory = "";
    private EditBox searchBox;
    private final List<Row> rows = new ArrayList<>();
    private double scroll;
    private double scrollTarget;
    private int listTop, listBottom, listRight;
    private int reserveCapKey = Integer.MIN_VALUE;
    private int reserveCap = -1;
    private int rowCardW = 200;                  // width of one weapon card (narrow, MW-style)
    private int stageX, stageY, stageW, stageH;  // big rotatable model, right side
    private boolean draggingModel;
    private String lastClickEntryId = "";
    private String descCacheKey = "";
    private List<net.minecraft.util.FormattedCharSequence> descCacheLines = new ArrayList<>();
    /** Set when this picker hands over to the gunsmith: on return, the preview follows whatever the gunsmith left equipped
     *  (a Blueprint switch there changes the equipped entry, and a stale preview would overwrite it on the next EQUIP). */
    private boolean returningFromGunsmith;
    private String previewId = "";   // the row that was clicked once: shown big on the right, NOT equipped yet
    private long lastClickAt;
    private Row hoveredRow;
    private Row lastHoverSoundRow;
    private long listFlashStart;
    private final GunPreview preview = new GunPreview();
    private float[] cachedPanelBars = new float[6];
    private float[] cachedPanelRaw = new float[TaczStats.BAR_COUNT];
    private float[] cachedEquipRaw;
    private TaczStats.AdvancedData cachedPanelAdv;
    private int cachedPanelBarsKey;
    private float[] cachedEquipBars;
    private int cachedEquipBarsKey;
    /** Session compare-pin: gameId chosen via right-click "Compare" — replaces the equipped
     *  gun as the bar baseline until cleared or the screen closes. */
    private String compareBaselineId;
    private java.util.Map<String, String> compareBaselineBuild = java.util.Map.of();
    // Row context menu (equip / compare / favorite).
    private int rowMenuX = -1, rowMenuY;
    private CatalogEntry rowMenuEntry;

    public SlotPickerScreen(LoadoutScreen parent, LoadoutPreset working, SlotDefinition slot) {
        super(Component.literal(slot.displayName));
        this.parent = parent;
        this.working = working;
        this.slot = slot;
    }

    @Override
    protected void init() {
        // Transition state MUST reset on every (re)show: Minecraft reuses this instance when a
        // child screen returns, and a latched closingAt (-1) would leave transitionTo() dead —
        // the v1.2.1 "stuck after gunsmith" brick. Re-arming openedAt also replays the fade-in.
        closingAt = 0;
        MWStyle.screenOpened();
        if (returningFromGunsmith) {
            returningFromGunsmith = false;
            LoadoutPreset.Selection kept = working.slots.get(slot.id);
            if (kept != null && !kept.entryId.isEmpty() && LoadoutRegistry.get().entry(kept.entryId) != null) {
                previewId = kept.entryId; // the Blueprint picked in the gunsmith stays equipped AND shown until another gun is chosen
            }
        }
        pendingScreen = null;
        pendingClose = false;
        openedAt = System.currentTimeMillis();
        boolean overkill = LoadoutApplier.hasSpecialPerk(working, "overkill");
        boolean underkill = !overkill && LoadoutApplier.hasSpecialPerk(working, "underkill");
        categories.clear();
        Set<String> categorySet = new LinkedHashSet<>(LoadoutApplier.effectiveCategories(slot, overkill, underkill));
        for (String whitelistedId : slot.whitelist) {
            CatalogEntry entry = LoadoutRegistry.get().entry(whitelistedId);
            if (entry != null) {
                categorySet.add(entry.category);
            }
        }
        categories.addAll(categorySet);
        categories.sort(java.util.Comparator.comparingInt(SlotPickerScreen::categoryOrder));
        if (activeCategory.isEmpty() && !categories.isEmpty()) {
            // Land on the currently-equipped weapon's category, not always the first by sort
            // order — opening the picker now starts where the player's own gun already is
            // instead of costing an extra click to find it (Blueprint children resolve to their
            // platform's category first, same as the pin-to-top logic below).
            LoadoutPreset.Selection current = working.slots.get(slot.id);
            String defaultCategory = null;
            if (current != null && !current.entryId.isEmpty()) {
                CatalogEntry currentEntry = LoadoutRegistry.get().entry(current.entryId);
                if (currentEntry != null && categories.contains(currentEntry.category)) {
                    defaultCategory = currentEntry.category;
                }
            }
            activeCategory = defaultCategory != null ? defaultCategory : categories.get(0);
        }

        // MW-style: a NARROW weapon list on the left (the BUILDS button slides out of the selected card), the big model
        // on the right with name / description / stat bars under it.
        rowCardW = Mth.clamp((int) (width * 0.36f), 190, 250);
        listRight = 10 + rowCardW + 4 + GUNSMITH_WIDTH + 8;
        listTop = 76;
        listBottom = height - 34;
        stageX = listRight + 14;
        stageW = Math.max(140, width - stageX - 10);
        stageY = 60;
        stageH = Math.max(100, (int) ((listBottom - 60) * 0.56f));

        // Own row, top-right corner, directly above the stats/hover-info panel (panelX) — a
        // column the tab row never reaches regardless of category count or name length, so this
        // can't be overlapped by tabs again the way the old fixed-X placement was.
        searchBox = new EditBox(font, 12, 55, rowCardW - 4, 14, Component.empty());
        searchBox.setBordered(false);
        searchBox.setTextColor(MWStyle.TEXT);
        searchBox.setHint(Component.translatable("screen.mwloadout.search"));
        searchBox.setResponder(text -> rebuildRows());
        addRenderableWidget(searchBox);

        Component backLabel = Component.translatable("screen.mwloadout.back");
        int backW = Math.max(54, font.width(backLabel) + 20);
        addRenderableWidget(MWStyle.flatButton(width - 10 - backW, height - 26, backW, 18,
                backLabel, button -> transitionTo(parent)));
        Component equipLabel = Component.translatable("screen.mwloadout.equip");
        int equipW = Math.max(60, font.width(equipLabel) + 20);
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(10, height - 26, equipW, 18,
                equipLabel, button -> equipPreview())).accent());

        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        String query = searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        boolean overkill = LoadoutApplier.hasSpecialPerk(working, "overkill");
        boolean underkill = !overkill && LoadoutApplier.hasSpecialPerk(working, "underkill");
        List<String> effective = LoadoutApplier.effectiveCategories(slot, overkill, underkill);

        // Overkill second-primary: the exact gun in slot 1 is off the menu. If that gun is a
        // Blueprint variant, it doesn't have its own row in this list (only its platform does —
        // see the blueprintOwner skip below), so the exclusion has to target the PLATFORM id too,
        // the same resolution the "pin equipped row to top" logic below already does — otherwise
        // a Blueprint-equipped primary silently failed to exclude anything and the base platform
        // (which IS in the list) stayed pickable as an identical second primary.
        String excludedEntryId = null;
        if (overkill && "secondary".equals(slot.id)) {
            LoadoutPreset.Selection primary = working.slots.get("primary");
            if (primary != null && !primary.entryId.isEmpty()) {
                excludedEntryId = primary.entryId;
                CatalogEntry excludedEntry = LoadoutRegistry.get().entry(excludedEntryId);
                if (excludedEntry != null) {
                    CatalogEntry excludedPlatform = LoadoutRegistry.get().blueprintOwnerByGameId(excludedEntry.gameId);
                    if (excludedPlatform != null) {
                        excludedEntryId = excludedPlatform.id;
                    }
                }
            }
        }

        // The server validator drops a second copy of the same gun (see LoadoutApplier "duplicate weapon"), and it applies
        // whether or not Overkill is on and in BOTH directions - so hide every gun that another weapon slot already holds
        // (matched by game id AND by blueprint platform), instead of only primary -> secondary.
        java.util.Set<String> takenGameIds = new java.util.HashSet<>();
        java.util.Set<String> takenEntryIds = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, LoadoutPreset.Selection> other : working.slots.entrySet()) {
            if (other.getKey().equals(slot.id) || other.getValue() == null || other.getValue().entryId.isEmpty()) {
                continue;
            }
            CatalogEntry held = LoadoutRegistry.get().entry(other.getValue().entryId);
            if (held == null || held.kind != CatalogEntry.Kind.GUN) {
                continue;
            }
            takenGameIds.add(held.gameId);
            takenEntryIds.add(held.id);
            CatalogEntry heldPlatform = LoadoutRegistry.get().blueprintOwnerByGameId(held.gameId);
            if (heldPlatform != null) {
                takenEntryIds.add(heldPlatform.id);
                takenGameIds.add(heldPlatform.gameId);
            }
        }

        LoadoutPreset.Selection current = working.slots.get(slot.id);
        String currentId = current != null ? current.entryId : "";
        // The equipped selection can be a Blueprint child, which never appears in this list when it
        // shares its platform's category (see the blueprintOwner skip below). Resolve to the
        // platform id here so "you have a Blueprint variant of this equipped" still pins/scrolls
        // that platform row to the top, instead of the pin silently matching nothing.
        if (!currentId.isEmpty()) {
            CatalogEntry currentEntry = LoadoutRegistry.get().entry(currentId);
            if (currentEntry != null) {
                CatalogEntry platform = LoadoutRegistry.get().blueprintOwnerByGameId(currentEntry.gameId);
                if (platform != null) {
                    currentId = platform.id;
                }
            }
        }
        // currentId gets reassigned above, so it isn't effectively final — the sort lambda below
        // needs its own final copy to close over.
        final String pinId = currentId;

        // One blueprint FAMILY, one row per category: a platform (e.g. M4) can bundle many variants
        // that live in OTHER categories (DMR, LMG, ...). Showing every one of them as its own pick
        // floods that category with near-identical guns, so only the variant listed FIRST in the
        // owner's blueprint list is shown per (category, family); the rest are reached through the
        // Gunsmith's Blueprints picker. familyRep maps "category|ownerId" -> the entry to keep.
        java.util.Map<String, CatalogEntry> familyRep = new java.util.HashMap<>();
        java.util.Map<String, Integer> familyRank = new java.util.HashMap<>();
        for (CatalogEntry entry : LoadoutRegistry.get().catalogList()) {
            if (!entry.enabled || slot.blacklist.contains(entry.id)) {
                continue; // an unusable variant must never be the one chosen to represent its family
            }
            CatalogEntry owner = LoadoutRegistry.get().blueprintOwnerByGameId(entry.gameId);
            if (owner == null || owner.category.equals(entry.category)) {
                continue;
            }
            String familyKey = entry.category + "|" + owner.id;
            int rank = owner.blueprintIds.indexOf(entry.gameId);
            if (rank < 0) {
                rank = Integer.MAX_VALUE;
            }
            Integer best = familyRank.get(familyKey);
            if (best == null || rank < best) {
                familyRank.put(familyKey, rank);
                familyRep.put(familyKey, entry);
            }
        }

        List<CatalogEntry> matches = new ArrayList<>();
        for (CatalogEntry entry : LoadoutRegistry.get().catalogList()) {
            if (!entry.enabled || slot.blacklist.contains(entry.id) || entry.id.equals(excludedEntryId)
                    || (entry.kind == CatalogEntry.Kind.GUN
                    && (takenGameIds.contains(entry.gameId) || takenEntryIds.contains(entry.id)))) {
                continue;
            }
            // Blueprint variants (MW2022-style) don't show up as their own separate pick — only
            // their platform owner does; the variants are reached from that owner's Gunsmith.
            // Exception: a blueprint can bundle a genuinely different weapon CATEGORY under the
            // same platform (e.g. an M4 blueprint family that also lists a DMR variant) — that
            // one still needs to appear as its own pick in ITS category's slot, since nothing else
            // will ever offer it there. Only hide the child when it shares the platform's category.
            CatalogEntry blueprintOwner = LoadoutRegistry.get().blueprintOwnerByGameId(entry.gameId);
            if (blueprintOwner != null && blueprintOwner.category.equals(entry.category)) {
                continue;
            }
            if (blueprintOwner != null && familyRep.get(entry.category + "|" + blueprintOwner.id) != entry) {
                continue; // another variant of this family already represents it in this category
            }
            boolean whitelisted = slot.whitelist.contains(entry.id);
            if (!whitelisted && !effective.contains(entry.category)) {
                continue;
            }
            if (query.isEmpty()) {
                if (!entry.category.equals(activeCategory)) {
                    continue;
                }
            } else if (!matchesQuery(entry, query)) {
                continue;
            }
            matches.add(entry);
        }
        // Pin the currently equipped choice to the top of its list, MW19-style.
        matches.sort((a, b) -> {
            boolean aCurrent = a.id.equals(pinId);
            boolean bCurrent = b.id.equals(pinId);
            if (aCurrent != bCurrent) {
                return aCurrent ? -1 : 1;
            }
            // Then favorites (★) float above the rest.
            boolean aFav = isFavorite(a.id);
            boolean bFav = isFavorite(b.id);
            if (aFav != bFav) {
                return aFav ? -1 : 1;
            }
            // Resolved names, not raw keys — raw "item.tacz.modern_kinetic_gun" would
            // cluster every gun under 'i'. DisplayNames caches, so this is cheap.
            return DisplayNames.of(a).compareToIgnoreCase(DisplayNames.of(b));
        });

        // Land the player on their own gun (one row of context above it), MW19 behavior:
        // exact Y accumulation because gun and item rows have different heights.
        int currentRowY = -1;
        int probeY = 0;
        for (CatalogEntry candidate : matches) {
            if (candidate.id.equals(currentId)) {
                currentRowY = probeY;
                break;
            }
            probeY += candidate.kind == CatalogEntry.Kind.GUN ? GUN_ROW_HEIGHT : ITEM_ROW_HEIGHT;
        }
        if (currentRowY > GUN_ROW_HEIGHT * 2) {
            scroll = Math.max(0, currentRowY - GUN_ROW_HEIGHT);
        }
        int rowY = listTop;
        // Row width: a clean 8px gap before the GUNSMITH column (the -4 experiment collided),
        // and slightly shorter overall per the reference proportions.
        int rowWidth = rowCardW;
        for (CatalogEntry entry : matches) {
            int rowHeight = entry.kind == CatalogEntry.Kind.GUN ? GUN_ROW_HEIGHT : ITEM_ROW_HEIGHT;
            rows.add(new Row(entry, 10, rowY, rowWidth, rowHeight - 3));
            rowY += rowHeight;
        }
        scroll = 0;
        scrollTarget = 0;
    }

    private boolean anyGuns(List<CatalogEntry> matches) {
        for (CatalogEntry entry : matches) {
            if (entry.kind == CatalogEntry.Kind.GUN) {
                return true;
            }
        }
        return false;
    }

    /** Name / id / category / ammo type — "556" finds every 5.56 gun in the slot. */
    private boolean matchesQuery(CatalogEntry entry, String query) {
        if (DisplayNames.of(entry).toLowerCase(Locale.ROOT).contains(query)
                || entry.displayName.toLowerCase(Locale.ROOT).contains(query)
                || entry.gameId.toLowerCase(Locale.ROOT).contains(query)
                || entry.category.toLowerCase(Locale.ROOT).contains(query)
                || categoryDisplayName(entry.category).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return entry.kind == CatalogEntry.Kind.GUN
                && TaczStats.ammoId(entry.gameId).toLowerCase(Locale.ROOT).contains(query);
    }

    private int contentHeight() {
        if (rows.isEmpty()) {
            return 0;
        }
        Row last = rows.get(rows.size() - 1);
        return last.y + last.h + 3 - listTop;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        final int realMouseX = mouseX, realMouseY = mouseY;
        if (advanced.isOpen()) {
            mouseX = -10000; // the Advanced stats box is modal: nothing under it hovers
            mouseY = -10000;
        }
        // Eased scroll: wheel moves the target, the view chases — MW19 list feel.
        scroll = MWStyle.approach((float) scroll, (float) scrollTarget, 0.5f, MWStyle.frameDt());

        MWStyle.screenChrome(graphics, width,
                slot.displayName + " - " + MWStyle.tr("screen.mwloadout.select_item"), openedAt);

        int tabX = 10;
        for (String category : categories) {
            String label = categoryDisplayName(category);
            int tabWidth = scaledTabWidth(font, label);
            boolean active = category.equals(activeCategory) && searchBox.getValue().isEmpty();
            boolean hovered = !active && mouseX >= tabX && mouseX < tabX + tabWidth && mouseY >= 32 && mouseY < 50;
            float anim = MWStyle.easeHover(tabHoverAnim.getOrDefault(category, 0f), hovered, partialTick);
            tabHoverAnim.put(category, anim);
            int bg = active ? MWStyle.PANEL_HOVER : MWStyle.blend(MWStyle.PANEL_RAISED, MWStyle.PANEL_HOVER, anim * 0.6f);
            MWStyle.fillF(graphics, tabX, 32, tabX + tabWidth, 50, bg);
            if (active) {
                MWStyle.fillF(graphics, tabX, 48, tabX + tabWidth, 50, MWStyle.signal());
            } else if (anim > 0.01f) {
                MWStyle.fillF(graphics, tabX, 48, tabX + tabWidth, 50, MWStyle.withAlpha(MWStyle.signal(), 0.35f * anim));
            }
            // Centered against the actual box width (was a fixed +8 offset regardless of label
            // length, which left noticeably more empty space on the right than the left).
            int textX = tabX + (tabWidth - font.width(label)) / 2;
            int textColor = active ? MWStyle.accent() : MWStyle.blend(MWStyle.TEXT_DIM, MWStyle.TEXT, anim);
            MWStyle.strF(graphics, font, label, textX, 38, textColor, false);
            tabX += tabWidth + 10;
        }
        // Search box's own row, top-right corner — pinned directly above the stats/hover-info
        // panel (same X as its panelX) rather than hugging the list column. That panel column is
        // never shared with the tab row (which lives over on the list side starting at x=10), so
        // this placement can never collide with tabs no matter how many categories or how long
        // their names are — the actual fix, not just more padding.
        int searchX = 10, searchW = rowCardW;
        MWStyle.fillF(graphics, searchX, 54, searchX + searchW, 69, MWStyle.PANEL_RAISED);
        MWStyle.fillF(graphics, searchX, 68, searchX + searchW, 69, MWStyle.border());
        // Clear (×) glyph, right-aligned inside the search box — only when there's something to
        // clear. QoL: one click back to the full list instead of holding backspace.
        if (!searchBox.getValue().isEmpty()) {
            boolean clearHovered = mouseX >= searchX + searchW - 16 && mouseX < searchX + searchW - 4
                    && mouseY >= 56 && mouseY < 67;
            MWStyle.strF(graphics, font, "\u00D7", searchX + searchW - 14, 57,
                    clearHovered ? MWStyle.accent() : MWStyle.TEXT_DIM, false);
        }

        hoveredRow = null;
        for (Row row : rows) {
            if (row.containsOnScreen(mouseX, mouseY, true)) {
                hoveredRow = row;
                break;
            }
        }
        if (hoveredRow != null && hoveredRow != lastHoverSoundRow) {
            MWStyle.playHover();
        }
        lastHoverSoundRow = hoveredRow;

        graphics.enableScissor(0, listTop, listRight, listBottom);
        for (Row row : rows) {
            row.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();

        int overflow = contentHeight() - (listBottom - listTop);
        if (overflow > 0) {
            int trackHeight = listBottom - listTop;
            int barHeight = Math.max(16, trackHeight * trackHeight / contentHeight());
            int barY = listTop + (int) (scroll / overflow * (trackHeight - barHeight));
            MWStyle.fillF(graphics, listRight + 2, listTop, listRight + 4, listBottom, MWStyle.PANEL_RAISED);
            MWStyle.fillF(graphics, listRight + 2, barY, listRight + 4, barY + barHeight, MWStyle.border());
        }

        renderDetail(graphics, mouseX, mouseY);
        float flash = 1f - Math.min(1f, (System.currentTimeMillis() - listFlashStart) / 110f);
        if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, 10, listTop, listRight - 10, listBottom - listTop, flash * 0.85f);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (rowMenuX >= 0) {
            String[] ops = {"equip", "compare", "favorite"};
            MWStyle.fillF(graphics, rowMenuX, rowMenuY, rowMenuX + 96, rowMenuY + ops.length * 14, 0xF5101216);
            MWStyle.hairline(graphics, rowMenuX, rowMenuY, 96, ops.length * 14, MWStyle.borderActive());
            for (int i = 0; i < ops.length; i++) {
                int itemY = rowMenuY + i * 14;
                boolean overItem = mouseX >= rowMenuX && mouseX < rowMenuX + 96 && mouseY >= itemY && mouseY < itemY + 14;
                if (overItem) {
                    MWStyle.fillF(graphics, rowMenuX + 1, itemY, rowMenuX + 95, itemY + 14, MWStyle.PANEL_HOVER);
                }
                String label = Component.translatable("screen.mwloadout.wop_" + ops[i]).getString();
                if (ops[i].equals("favorite") && rowMenuEntry != null && isFavorite(rowMenuEntry.id)) {
                    label = MWStyle.tr("screen.mwloadout.wop_unfavorite");
                }
                MWStyle.strF(graphics, font, label, rowMenuX + 7, itemY + 3, overItem ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
            }
        }
        advanced.render(graphics, width, height, realMouseX, realMouseY);
        transitionRender(graphics);

        // Floating hover card: off by default (the stat panel covers it and the card blocks
        // the list) — opt in via client config showHoverTooltip.
        boolean showTip;
        try {
            showTip = dev.l41nx.mwloadout.client.ClientCfg.SHOW_HOVER_TOOLTIP.get();
        } catch (Exception e) {
            showTip = false;
        }
        if (showTip && hoveredRow != null && hoveredRow.entry.kind == CatalogEntry.Kind.GUN) {
            ItemStack hoverStack = IconCache.iconFor(hoveredRow.entry);
            if (!hoverStack.isEmpty()) {
                MwScreen.deferTooltip(font, hoverStack, mouseX, mouseY);
            }
        }
    }

    /** Bars for the comparison baseline: the compare-pin if set, else the currently
     *  EQUIPPED gun in this slot; null when the shown gun IS the baseline (no self-compare). */
    private float[] baselineBars(CatalogEntry shown) {
        String baseId;
        java.util.Map<String, String> build;
        if (compareBaselineId != null) {
            baseId = compareBaselineId;
            build = compareBaselineBuild;
        } else {
            LoadoutPreset.Selection current = working.slots.get(slot.id);
            if (current == null || current.entryId.isEmpty()) {
                return null;
            }
            CatalogEntry equipped = LoadoutRegistry.get().entry(current.entryId);
            if (equipped == null || equipped.kind != CatalogEntry.Kind.GUN) {
                return null;
            }
            baseId = equipped.gameId;
            build = current.attachments;
        }
        if (baseId.equals(shown.gameId)) {
            return null;
        }
        int key = baseId.hashCode() * 31 + build.hashCode();
        if (cachedEquipBars == null || key != cachedEquipBarsKey) {
            cachedEquipBarsKey = key;
            cachedEquipBars = TaczStats.barStats(baseId, build);
            cachedEquipRaw = TaczStats.rawStats(baseId, build);
        }
        return cachedEquipBars;
    }

    /** Wrapped description lines (TACZ index tooltip / lang text / item tooltip). Cached per entry + width. */
    private List<net.minecraft.util.FormattedCharSequence> descriptionLines(CatalogEntry shown, int leftW) {
        String descKey = shown.id + "|" + leftW;
        if (!descKey.equals(descCacheKey)) {
            descCacheKey = descKey;
            descCacheLines = new ArrayList<>();
            List<Component> description = new ArrayList<>();
            if (shown.kind == CatalogEntry.Kind.GUN) {
                String key = TaczStats.gunTooltipKey(shown.gameId);
                String tipText = "";
                if (!key.isEmpty()) {
                    if (net.minecraft.client.resources.language.I18n.exists(key)) {
                        tipText = net.minecraft.client.resources.language.I18n.get(key);
                    } else if (!key.matches("[a-z0-9_.:\\-/]+")) {
                        tipText = key; // the index file carries the text itself, not a lang key
                    }
                }
                if (!tipText.isEmpty()) {
                    for (String part : tipText.split("\\n|\\\\n")) {
                        if (!part.isBlank()) {
                            description.add(Component.literal(part.trim()));
                        }
                    }
                }
            }
            if (description.isEmpty()) {
                ItemStack icon = IconCache.iconFor(shown);
                if (!icon.isEmpty()) {
                    try {
                        List<Component> tooltip = icon.getTooltipLines(null, TooltipFlag.Default.NORMAL);
                        for (int i = 1; i < tooltip.size(); i++) {
                            description.add(tooltip.get(i));
                        }
                    } catch (Exception ignored) {
                        dev.l41nx.mwloadout.MWLog.quiet("client/gui/SlotPickerScreen:609", ignored);
                    }
                }
            }
            for (Component part : description) {
                descCacheLines.addAll(font.split(Component.literal(part.getString())
                        .withStyle(net.minecraft.ChatFormatting.ITALIC, net.minecraft.ChatFormatting.GRAY), leftW));
            }
        }
        return descCacheLines;
    }

    private String statKey = "";
    private List<TaczStats.Line> statCache = List.of();

    private List<TaczStats.Line> statLinesFor(String gunGameId) {
        if (!gunGameId.equals(statKey)) {
            statKey = gunGameId;
            statCache = new ArrayList<>(TaczStats.gunStats(gunGameId));
        }
        return statCache;
    }

    /** Right side: big rotatable model, bold name, description (the item's own tooltip) and the stat bars under it. */
    private final AdvancedStatsPanel advanced = new AdvancedStatsPanel();
    private static final int ADV_SIZE = 16;
    private int advX, advY;
    private CatalogEntry advTarget;
    private java.util.Map<String, String> advBuild = java.util.Map.of();

    private final long advShownAt = System.currentTimeMillis();

    private void renderAdvancedButton(GuiGraphics graphics, int mouseX, int mouseY) {
        advX = stageX + stageW - ADV_SIZE - 6;
        advY = stageY + 6;
        AdvancedStatsPanel.drawButton(graphics, advX, advY, ADV_SIZE, mouseX, mouseY, advShownAt);
    }

    private void renderDetail(GuiGraphics graphics, int mouseX, int mouseY) {
        advTarget = null;
        LoadoutPreset.Selection current = working.slots.get(slot.id);
        CatalogEntry shown = hoveredRow != null ? hoveredRow.entry : null;
        if (shown == null && !previewId.isEmpty()) {
            shown = LoadoutRegistry.get().entry(previewId);
        }
        if (shown == null && current != null && !current.entryId.isEmpty()) {
            shown = LoadoutRegistry.get().entry(current.entryId);
        }
        if (shown == null) {
            MWStyle.centerF(graphics, font, MWStyle.tr("screen.mwloadout.hover_hint"),
                    stageX + stageW / 2, stageY + stageH / 2, MWStyle.TEXT_DIM);
            return;
        }
        PlayerProfile profileNow = ClientCache.profile();
        java.util.Map<String, String> build = current != null && shown.id.equals(current.entryId)
                ? current.attachments
                : profileNow == null || shown.kind != CatalogEntry.Kind.GUN ? java.util.Map.of()
                : profileNow.buildsFor(shown.id).activeBuild(); // the gun's saved build, so the model matches the dots
        if (shown.kind == CatalogEntry.Kind.GUN) {
            advTarget = shown;
            advBuild = build;
            renderAdvancedButton(graphics, mouseX, mouseY);
        }
        // ---- layout: the info block under the model grows with a long description (and shrinks for a short one), the model
        // gets whatever is left - no dead space for short guns, full text for long ones.
        int leftW = Math.max(130, (int) (stageW * 0.50f) - 4);
        List<net.minecraft.util.FormattedCharSequence> descLines = descriptionLines(shown, leftW);
        List<TaczStats.Line> statLines = shown.kind == CatalogEntry.Kind.GUN ? statLinesFor(shown.gameId) : List.of();
        int leftH = 21 + 12 + (shown.kind == CatalogEntry.Kind.GUN && shown.hasBlueprint ? 11 : 0) + descLines.size() * 10
                + (statLines.isEmpty() ? 0 : 4 + statLines.size() * 10);
        int rightH = shown.kind == CatalogEntry.Kind.GUN ? 12 + 6 * 11 + 14 : 22;
        int availH = listBottom - 60;
        int infoH = Mth.clamp(Math.max(leftH, rightH) + 4, 96, (int) (availH * 0.66f));
        stageY = 60;
        stageH = Math.max(90, availH - infoH - 6);
        int infoY = stageY + stageH + 6;
        // ---- big model (drag to rotate, wheel to zoom) -------------------------------------------------------------
        preview.setUnclipped(true);
        if (shown.kind == CatalogEntry.Kind.GUN && !dev.l41nx.mwloadout.client.ClientCfg.gunPreview3d()) {
            // flat icon instead of the 3D model (config gunPreview3d = false): works whatever the rendering pipeline does with 3D gun models
            ItemStack flat = IconCache.iconFor(shown);
            if (!flat.isEmpty()) {
                float s = Math.max(2f, Math.min(stageW, stageH) / 24f);
                graphics.pose().pushPose();
                graphics.pose().translate(stageX + stageW / 2f, stageY + stageH / 2f, 0);
                graphics.pose().scale(s, s, 1f);
                graphics.renderItem(flat, -8, -8);
                graphics.pose().popPose();
            }
        } else if (shown.kind == CatalogEntry.Kind.GUN) {
            preview.update(shown.gameId, build);
            float len = AttachmentEditorScreen.lengthFactor(shown.category);
            float scale = Mth.clamp(Math.min(stageW * 0.62f / (3.0f * len), stageH * 0.95f), 36f, 150f);
            preview.render(stageX, stageY, stageW, stageH, scale);
        } else {
            // other mods' items (LR Tactical grenades, field upgrades ...) as their real 3D model, drag / zoom like a gun
            ItemStack bigIcon = IconCache.iconFor(shown);
            if (!bigIcon.isEmpty()) {
                preview.updateItem(bigIcon);
                preview.render(stageX, stageY, stageW, stageH, Mth.clamp(Math.min(stageW * 0.30f, stageH * 0.62f), 40f, 120f));
            }
        }
        // ---- name + class line + description ----------------------------------------------------------------------
        String name = DisplayNames.of(shown).toUpperCase(Locale.ROOT);
        graphics.pose().pushPose();
        graphics.pose().translate(stageX, infoY, 0);
        graphics.pose().scale(2f, 2f, 1f);
        MWStyle.strF(graphics, font, Component.literal(font.plainSubstrByWidth(name, leftW / 2))
                .withStyle(net.minecraft.ChatFormatting.BOLD), 0, 0, MWStyle.TEXT, false);
        graphics.pose().popPose();
        String meta = categoryDisplayName(shown.category);
        String ammo = shown.kind == CatalogEntry.Kind.GUN ? TaczStats.ammoId(shown.gameId) : "";
        if (!ammo.isEmpty()) {
            meta += "  \u00B7  " + (ammo.contains(":") ? ammo.substring(ammo.indexOf(':') + 1) : ammo).toUpperCase(Locale.ROOT);
        }
        int textY = infoY + 21;
        MWStyle.strF(graphics, font, font.plainSubstrByWidth(meta, leftW), stageX, textY, MWStyle.accent(), false);
        textY += 12;
        if (shown.kind == CatalogEntry.Kind.GUN && shown.hasBlueprint) {
            int available = 0;
            for (String blueprintGameId : shown.blueprintIds) {
                CatalogEntry child = LoadoutRegistry.get().entryByGameId(blueprintGameId);
                if (child != null && child.enabled) {
                    available++;
                }
            }
            MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.blueprints_available", available).getString(),
                    stageX, textY, MWStyle.TEXT_DIM, false);
            textY += 11;
        }
        int descRoom = (listBottom - 6 - textY) / 10 - (shown.kind == CatalogEntry.Kind.GUN && !statLines.isEmpty() ? statLines.size() : 0);
        for (int i = 0; i < descLines.size(); i++) {
            if (i >= Math.max(1, descRoom)) {
                // out of room: cut with an ellipsis on the last visible line instead of letting it run into the buttons
                if (i == Math.max(1, descRoom)) {
                    MWStyle.strF(graphics, font, "...", stageX, textY - 10 + 4, MWStyle.TEXT_DIM, false);
                }
                break;
            }
            MWStyle.strF(graphics, font, descLines.get(i), stageX, textY, MWStyle.TEXT_DIM, false);
            textY += 10;
        }
        // stat lines (DMG / HS / PIERCE ...) in the left block too, one per row - nothing runs off the right edge
        if (shown.kind == CatalogEntry.Kind.GUN) {
            textY += 4;
            for (TaczStats.Line l : statLines) {
                if (textY > listBottom - 10) {
                    break;
                }
                MWStyle.strF(graphics, font, font.plainSubstrByWidth(l.text(), leftW), stageX, textY, MWStyle.TEXT_DIM, false);
                textY += 10;
            }
        }
        // ---- stat bars, bottom-right (MW layout): LABEL | bar | value | +/- vs equipped -----------------------------
        if (shown.kind != CatalogEntry.Kind.GUN) {
            MWStyle.strF(graphics, font, "x" + shown.count, stageX + leftW + 12, infoY + 2, MWStyle.TEXT, false);
            return;
        }
        int barsKey = shown.gameId.hashCode() * 31 + build.hashCode();
        if (barsKey != cachedPanelBarsKey) {
            cachedPanelBarsKey = barsKey;
            cachedPanelAdv = null;
            cachedPanelRaw = TaczStats.rawStats(shown.gameId, build);
            cachedPanelBars = TaczStats.barsFromRaw(cachedPanelRaw);
        }
        float[] bars = cachedPanelBars;
        float[] baseline = baselineBars(shown);
        float[] baselineRawNow = baseline == null ? null : cachedEquipRaw;
        int hoveredBar = -1;
        int bx0 = stageX + (int) (stageW * 0.54f);
        int blockW = stageX + stageW - bx0;
        int labelW = 62;
        int barW = Math.max(40, blockW - labelW - 6 - 54);
        int by = infoY;
        String baselineName = null;
        if (compareBaselineId != null) {
            var pinned = LoadoutRegistry.get().entryByGameId(compareBaselineId);
            baselineName = pinned != null ? DisplayNames.of(pinned) : compareBaselineId;
        } else {
            LoadoutPreset.Selection equippedSel = working.slots.get(slot.id);
            if (equippedSel != null && !equippedSel.entryId.isEmpty() && !equippedSel.entryId.equals(shown.id)) {
                CatalogEntry equipped = LoadoutRegistry.get().entry(equippedSel.entryId);
                if (equipped != null && equipped.kind == CatalogEntry.Kind.GUN) {
                    baselineName = DisplayNames.of(equipped);
                }
            }
        }
        if (baselineName != null) {
            MWStyle.strF(graphics, font, font.plainSubstrByWidth(Component.translatable("screen.mwloadout.compare_to",
                    baselineName.toUpperCase(Locale.ROOT)).getString(), blockW), bx0, by, MWStyle.accent(), false);
            by += 12;
        }
        for (int i = 0; i < 6 && by < listBottom - 8; i++) {
            float shownV = Math.max(0.02f, Math.min(1f, bars[i]));
            int bx = bx0 + labelW + 4;
            MWStyle.strF(graphics, font, font.plainSubstrByWidth(TaczStats.BAR_LABELS[i], labelW), bx0, by, MWStyle.TEXT_DIM, false);
            if (mouseX >= bx0 && mouseX < bx0 + blockW && mouseY >= by - 1 && mouseY < by + 10) {
                hoveredBar = i;
            }
            MWStyle.fillF(graphics, bx, by + 3, bx + barW, by + 6, MWStyle.PANEL_HOVER);
            int delta = 0;
            if (baseline != null) {
                float baseV = Math.max(0.02f, Math.min(1f, baseline[i]));
                float common = Math.min(shownV, baseV);
                MWStyle.fillF(graphics, bx, by + 3, bx + (int) (barW * common), by + 6, 0xFFE8EAEC);
                if (shownV > baseV + 0.005f) {
                    MWStyle.fillF(graphics, bx + (int) (barW * baseV), by + 3, bx + (int) (barW * shownV), by + 6, MWStyle.POSITIVE);
                } else if (baseV > shownV + 0.005f) {
                    MWStyle.fillF(graphics, bx + (int) (barW * shownV), by + 3, bx + (int) (barW * baseV), by + 6, MWStyle.NEGATIVE);
                }
                delta = Math.round((shownV - baseV) * 100f);
            } else {
                MWStyle.fillF(graphics, bx, by + 3, bx + (int) (barW * shownV), by + 6, 0xFFE8EAEC);
            }
            // exact TACZ figure (64 reads 64), not the 0-100 bar score
            String exact = TaczStats.formatStat(i, cachedPanelRaw[i]);
            MWStyle.strF(graphics, font, exact, bx + barW + 6, by, MWStyle.TEXT, false);
            if (baselineRawNow != null && Math.abs(cachedPanelRaw[i] - baselineRawNow[i]) > 0.0001f) {
                float rawDelta = cachedPanelRaw[i] - baselineRawNow[i];
                boolean better = TaczStats.LOWER_IS_BETTER[i] ? rawDelta < 0 : rawDelta > 0;
                MWStyle.strF(graphics, font, (rawDelta > 0 ? "+" : "") + TaczStats.formatStat(i, rawDelta), bx + barW + 10 + font.width(exact), by,
                        better ? MWStyle.POSITIVE : MWStyle.NEGATIVE, false);
            }
            by += 11;
        }
        if (hoveredBar >= 0 && !advanced.isOpen()) {
            if (cachedPanelAdv == null) {
                cachedPanelAdv = TaczStats.advancedData(shown.gameId, build); // once per build, not once per frame under the cursor
            }
            MwScreen.deferTooltip(font, AdvancedStatsPanel.statTooltip(hoveredBar, cachedPanelRaw, baselineRawNow,
                    baselineName == null ? "Stock" : baselineName, cachedPanelAdv), mouseX, mouseY);
        }
        // reserve + a short stat line (DMG / HS / PIERCE) under the bars
        Rules rules = LoadoutRegistry.get().rules();
        int reserve;
        if (rules.dynamicAmmoEnabled) {
            // getMagazineCapacity builds a probe gun and installs every attachment: do it when the gun / build changes, not every frame
            int capKey = java.util.Objects.hash(shown.gameId, build);
            if (capKey != reserveCapKey) {
                reserveCapKey = capKey;
                reserveCap = TaczStackFactory.getMagazineCapacity(shown.gameId, build);
            }
            int magazineCapacity = reserveCap;
            reserve = magazineCapacity > 0
                    ? Math.max(1, (int) Math.round(magazineCapacity
                    * rules.effectiveAmmoMultiplierFor(magazineCapacity, shown.category, shown.ammoMultiplier)))
                    : rules.reserveAmmoFor(shown.gameId, shown.category);
        } else {
            reserve = rules.reserveAmmoFor(shown.gameId, shown.category);
        }
        by += 2;
        if (by < listBottom - 4) {
            MWStyle.strF(graphics, font, font.plainSubstrByWidth(
                    Component.translatable("screen.mwloadout.reserve", reserve).getString(), blockW), bx0, by, MWStyle.TEXT_DIM, false);
            by += 10;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (advanced.isOpen()) {
            return advanced.mouseScrolled(mouseX, mouseY, delta);
        }
        if (mouseX >= stageX && mouseY >= stageY && mouseY < stageY + stageH) {
            preview.zoom(delta);
            return true;
        }
        if (mouseX < listRight) {
            int overflow = Math.max(0, contentHeight() - (listBottom - listTop));
            scrollTarget = Mth.clamp(scrollTarget - delta * 30, 0, overflow);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        if (advanced.isOpen()) {
            return advanced.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && advTarget != null && mouseX >= advX && mouseX < advX + ADV_SIZE && mouseY >= advY && mouseY < advY + ADV_SIZE) {
            MWStyle.playClick();
            advanced.open(advTarget.gameId, DisplayNames.of(advTarget), advBuild);
            return true;
        }
        if (button == 0 && !searchBox.getValue().isEmpty()) {
            int searchX = 10;
            if (mouseX >= searchX + rowCardW - 16 && mouseX < searchX + rowCardW - 4
                    && mouseY >= 56 && mouseY < 67) {
                searchBox.setValue("");
                rebuildRows();
                MWStyle.playClick();
                return true;
            }
        }
        if (rowMenuX >= 0) {
            String[] ops = {"equip", "compare", "favorite"};
            if (mouseX >= rowMenuX && mouseX < rowMenuX + 96 && mouseY >= rowMenuY && mouseY < rowMenuY + ops.length * 14) {
                switch (ops[(int) ((mouseY - rowMenuY) / 14)]) {
                    // Equip WITHOUT leaving the list — selection lands, panel + rows update,
                    // you keep browsing (left-click stays the equip-and-exit path).
                    case "equip" -> working.slots.put(slot.id, selectionFor(rowMenuEntry));
                    case "compare" -> {
                        if (rowMenuEntry.kind == CatalogEntry.Kind.GUN) {
                            LoadoutPreset.Selection pinned = selectionFor(rowMenuEntry);
                            compareBaselineId = rowMenuEntry.gameId;
                            compareBaselineBuild = pinned.attachments;
                            cachedEquipBars = null; // rebuild against the new baseline
                        }
                    }
                    case "favorite" -> {
                        toggleFavorite(rowMenuEntry.id);
                        rebuildRows();
                    }
                }
                MWStyle.playClick();
            }
            rowMenuX = -1;
            return true;
        }
        if (button == 1 && mouseY >= listTop && mouseY < listBottom && mouseX < listRight) {
            for (Row row : rows) {
                if (row.containsOnScreen(mouseX, mouseY, false)) {
                    rowMenuX = (int) Math.min(mouseX, width - 100);
                    rowMenuY = (int) Math.min(mouseY, height - 46);
                    rowMenuEntry = row.entry;
                    MWStyle.playTab();
                    return true;
                }
            }
        }
        if (button == 0) {
            int tabX = 10;
            for (String category : categories) {
                int tabWidth = scaledTabWidth(font, categoryDisplayName(category));
                if (mouseX >= tabX && mouseX < tabX + tabWidth && mouseY >= 32 && mouseY < 50) {
                    if (!category.equals(activeCategory)) {
                        activeCategory = category;
                        searchBox.setValue("");
                        MWStyle.playTab();
                        listFlashStart = System.currentTimeMillis();
                        rebuildRows();
                    }
                    return true;
                }
                tabX += tabWidth + 10;
            }
            if (mouseY >= listTop && mouseY < listBottom && mouseX < listRight) {
                for (Row row : rows) {
                    if (row.containsOnScreen(mouseX, mouseY, true)) {
                        MWStyle.playClick();
                        return row.click(mouseX);
                    }
                }
            }
            // buttons / search box first, THEN the model stage (drag to rotate)
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (mouseX >= stageX && mouseX < stageX + stageW && mouseY >= stageY && mouseY < stageY + stageH) {
                preview.beginDrag();
                draggingModel = true;
                return true;
            }
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (advanced.isOpen()) {
            return advanced.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (advanced.isOpen()) {
            return advanced.mouseDragged(mouseX, mouseY);
        }
        if (draggingModel) {
            preview.drag(dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (advanced.isOpen()) {
            return advanced.mouseReleased();
        }
        if (draggingModel) {
            preview.endDrag();
            draggingModel = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private String equippedRowKeyFor = "";
    private String equippedRowId = "";

    /** The id of the LIST row that represents the equipped weapon: the platform for a same-category Blueprint child, else itself. */
    private String equippedRowId() {
        LoadoutPreset.Selection current = working.slots.get(slot.id);
        String id = current == null ? "" : current.entryId;
        if (!id.equals(equippedRowKeyFor)) {
            equippedRowKeyFor = id;
            equippedRowId = id;
            CatalogEntry equipped = id.isEmpty() ? null : LoadoutRegistry.get().entry(id);
            if (equipped != null) {
                CatalogEntry platform = LoadoutRegistry.get().blueprintOwnerByGameId(equipped.gameId);
                if (platform != null && platform.category.equals(equipped.category)) {
                    equippedRowId = platform.id;
                }
            }
        }
        return equippedRowId;
    }

    /** Equips the previewed row (if any) and leaves; with nothing previewed it simply leaves. */
    private void equipPreview() {
        if (!previewId.isEmpty()) {
            CatalogEntry entry = LoadoutRegistry.get().entry(previewId);
            if (entry != null) {
                working.slots.put(slot.id, selectionFor(entry));
            }
        }
        transitionTo(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (advanced.isOpen()) {
            return advanced.keyPressed(keyCode, scanCode, modifiers);
        }
        // Enter = EQUIP the previewed weapon
        if ((keyCode == 257 || keyCode == 335) && !searchBox.isFocused() && closingAt <= 0) {
            equipPreview();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Selecting a gun brings its ACTIVE saved build with it — the gunsmith memory. */
    private LoadoutPreset.Selection selectionFor(CatalogEntry entry) {
        LoadoutPreset.Selection previous = working.slots.get(slot.id);
        if (previous != null && previous.entryId.equals(entry.id)) {
            return previous;
        }
        LoadoutPreset.Selection selection = new LoadoutPreset.Selection();
        selection.entryId = entry.id;
        if (entry.kind == CatalogEntry.Kind.GUN) {
            PlayerProfile profile = ClientCache.profile();
            if (profile != null) {
                selection.attachments.putAll(profile.buildsFor(entry.id).activeBuild());
            }
        }
        return selection;
    }

    static boolean isFavorite(String entryId) {
        try {
            return dev.l41nx.mwloadout.client.ClientCfg.FAVORITE_GUNS.get().contains(entryId);
        } catch (Exception e) {
            return false;
        }
    }

    static void toggleFavorite(String entryId) {
        try {
            java.util.List<String> favorites = new java.util.ArrayList<>(dev.l41nx.mwloadout.client.ClientCfg.FAVORITE_GUNS.get());
            if (!favorites.remove(entryId)) {
                favorites.add(entryId);
            }
            dev.l41nx.mwloadout.client.ClientCfg.FAVORITE_GUNS.set(favorites);
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/SlotPickerScreen:1085", ignored);
        }
    }

    private final class Row {
        final CatalogEntry entry;
        final int x, y, w, h;
        float hover;
        float gunsmithHover;
        float gunsmithAnim; // 0..1: the BUILDS button sliding out of the selected card

        Row(CatalogEntry entry, int x, int y, int w, int h) {
            this.entry = entry;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        int screenY() {
            return y - (int) scroll;
        }

        boolean containsOnScreen(double mx, double my, boolean includeGunsmith) {
            int sy = screenY();
            int right = x + w + (includeGunsmith && hasGunsmith() && gunsmithAnim > 0.5f ? 4 + GUNSMITH_WIDTH : 0);
            return mx >= x && mx < right && my >= sy && my < sy + h
                    && sy + h > listTop && sy < listBottom;
        }

        /** A GUNSMITH button only exists for guns that actually have attachment slots. */
        boolean hasGunsmith() {
            return entry.kind == CatalogEntry.Kind.GUN && TaczStats.hasAttachmentSupport(entry.gameId);
        }

        boolean overGunsmith(double mx) {
            return hasGunsmith() && gunsmithAnim > 0.5f && mx >= x + w + 4;
        }

        long flashAt;

        boolean click(double mouseX) {
            flashAt = System.currentTimeMillis();
            LoadoutPreset.Selection selection = selectionFor(entry);
            if (overGunsmith(mouseX)) {
                working.slots.put(slot.id, selection); // building a gun equips it
                returningFromGunsmith = true;
                transitionTo(new AttachmentEditorScreen(SlotPickerScreen.this, selection, entry)); // CONFIRM returns to this picker
                return true;
            }
            // One click only PREVIEWS (big model on the right - drag / zoom it, the BUILDS button slides out); a quick second
            // click, Enter or the EQUIP button equips. No more in-and-out of the screen just to look at something.
            long now = System.currentTimeMillis();
            boolean again = entry.id.equals(lastClickEntryId) && now - lastClickAt < 450;
            lastClickEntryId = entry.id;
            lastClickAt = now;
            previewId = entry.id;
            if (again) {
                equipPreview();
            }
            return true;
        }

        void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int sy = screenY();
            if (sy + h < listTop || sy > listBottom) {
                return;
            }
            boolean rowHovered = this == hoveredRow && !overGunsmith(mouseX);
            hover = MWStyle.easeHover(hover, this == hoveredRow, partialTick);
            LoadoutPreset.Selection selection = working.slots.get(slot.id);
            // a Blueprint child equipped from this platform highlights the platform's row (the child has no row of its own)
            boolean selected = selection != null && (selection.entryId.equals(entry.id) || equippedRowId().equals(entry.id));
            boolean previewed = entry.id.equals(previewId);
            MWStyle.tile(graphics, x, sy, w, h, rowHovered ? hover : hover * 0.5f, selected || previewed);
            if (previewed && !selected) {
                if (MWStyle.angular()) {
                    MWStyle.plateEdge(graphics, true, x, sy, w, h, MWStyle.cut(w, h, 4), MWStyle.withAlpha(MWStyle.signal(), 0.85f));
                } else {
                    MWStyle.hairline(graphics, x, sy, w, h, MWStyle.withAlpha(MWStyle.signal(), 0.85f));
                }
                MWStyle.wash(graphics, x, sy, w, h, MWStyle.withAlpha(MWStyle.accent(), 0.07f));
            }
            if (selected) {
                // Selected row: accent wash + pulsing inner glow + double border + a check chip - reads instantly.
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() * 0.004);
                float glow = 0.72f + 0.28f * pulse;
                MWStyle.wash(graphics, x, sy, w, h, MWStyle.withAlpha(MWStyle.accent(), 0.10f * glow));
                if (!MWStyle.angular()) { // angular: tile() above already drew the one selection treatment - a second would double the outline
                    MWStyle.glowOutline(graphics, x, sy, w, h, MWStyle.signal(), glow); // one halo + one crisp edge, nothing stacked
                }
            }
            if (isFavorite(entry.id)) {
                MWStyle.strF(graphics, font, "\u2605", x + w - 12, sy + 16, 0xFFE8C24A, false);
            }

            if (flashAt > 0) {
                float ft = (System.currentTimeMillis() - flashAt) / 280f;
                if (ft < 1f) {
                    MWStyle.wash(graphics, x, sy, w, h, MWStyle.withAlpha(MWStyle.accent(), 0.36f * (1f - ft) * (1f - ft)));
                }
            }
            if (entry.kind == CatalogEntry.Kind.GUN) {
                GunHud.render(graphics, entry, x + 5, sy + 2, 84, h - 4);
                // gold slot squares, top-right corner: one per attachment slot the gun allows, filled = installed
                if (hasGunsmith()) {
                    // one gold square per INSTALLED attachment of the gun's saved build (no empty boxes), top-right
                    PlayerProfile builtProfile = ClientCache.profile();
                    java.util.Map<String, String> savedBuild = selected ? selection.attachments
                            : builtProfile == null ? java.util.Map.of() : builtProfile.buildsFor(entry.id).activeBuild();
                    int mods = Math.min(TaczStats.attachmentSlotCount(entry.gameId),
                            TaczStats.installedAttachments(entry.gameId, savedBuild));
                    int dx = x + w - 6 - mods * 7 + 2;
                    for (int i = 0; i < mods; i++) {
                        int sx = dx + i * 7;
                        MWStyle.fillF(graphics, sx, sy + 5, sx + 5, sy + 10, MWStyle.signal());
                    }
                }

                // Text column - no scaled fonts anywhere (scaling is what made the text look noisy), one fixed 3-row rhythm:
                //   row 1 (y+5)  : EQUIPPED tag on the left (the gold slot squares are on the right)
                //   row 2 (y+15) : weapon name
                //   row 3 (y+26) : class + ammo, ellipsised instead of hard-cut
                int textX = x + 94;
                int textW = Math.max(30, w - 100);
                if (selected) {
                    MWStyle.strF(graphics, font, "EQUIPPED", textX, sy + 5, MWStyle.signal(), false);
                }
                MWStyle.scrollText(graphics, font, DisplayNames.of(entry), textX, sy + 15, textW,
                        selected ? MWStyle.accent() : MWStyle.TEXT, selected || this == hoveredRow);
                String meta = categoryDisplayName(entry.category);
                String ammo = TaczStats.ammoId(entry.gameId);
                if (!ammo.isEmpty()) {
                    meta += "  " + (ammo.contains(":") ? ammo.substring(ammo.indexOf(':') + 1) : ammo).toUpperCase(Locale.ROOT);
                }
                int bpWidth = entry.hasBlueprint ? font.width(" BP") : 0;
                String metaShown = MWStyle.ellipsize(font, meta, textW - bpWidth);
                MWStyle.strF(graphics, font, metaShown, textX, sy + 26, MWStyle.TEXT_DIM, false);
                if (entry.hasBlueprint) {
                    MWStyle.strF(graphics, font, " BP", textX + font.width(metaShown), sy + 26, MWStyle.accent(), false);
                }
                // BUILDS button slides out of the selected card (and slides back when another card is picked)
                gunsmithAnim = MWStyle.approach(gunsmithAnim, hasGunsmith() && (previewed || (selected && (previewId.isEmpty() || (selection != null && previewId.equals(selection.entryId))))) ? 1f : 0f, 0.35f, MWStyle.frameDt());
                if (gunsmithAnim < 0.02f) {
                    gunsmithAnim = 0f;
                } else if (gunsmithAnim > 0.98f) {
                    gunsmithAnim = 1f;
                }
                if (gunsmithAnim > 0f) {
                    float e = MWStyle.easeOutCubic(gunsmithAnim);
                    boolean gunsmithHovered = this == hoveredRow && overGunsmith(mouseX);
                    gunsmithHover = MWStyle.easeHover(gunsmithHover, gunsmithHovered, partialTick);
                    int bxOut = x + w - GUNSMITH_WIDTH + Math.round((GUNSMITH_WIDTH + 4) * e);
                    graphics.enableScissor(x + w, sy - 1, x + w + 4 + GUNSMITH_WIDTH + 2, sy + h + 1);
                    MWStyle.localAlpha(e);
                    MWStyle.tile(graphics, bxOut, sy, GUNSMITH_WIDTH, h, gunsmithHover, false);
                    MWStyle.centerF(graphics, font, MWStyle.tr("screen.mwloadout.gunsmith_short"),
                            bxOut + GUNSMITH_WIDTH / 2, sy + h / 2 - 4,
                            gunsmithHovered ? MWStyle.TEXT : MWStyle.TEXT_DIM);
                    MWStyle.localAlpha(1f);
                    graphics.disableScissor();
                }
            } else {
                ItemStack icon = IconCache.iconFor(entry);
                if (!icon.isEmpty()) {
                    graphics.renderItem(icon, x + 5, sy + (h - 16) / 2);
                }
                MWStyle.scrollText(graphics, font, DisplayNames.of(entry), x + 26, sy + 3, w - 60,
                        selected ? MWStyle.accent() : MWStyle.TEXT, selected || this == hoveredRow);
                MWStyle.strF(graphics, font, font.plainSubstrByWidth(categoryDisplayName(entry.category), w - 34),
                        x + 26, sy + 12, MWStyle.TEXT_DIM, false);
            }
        }
    }
    private void transitionTo(net.minecraft.client.gui.screens.Screen next) {
        if (closingAt <= 0) {
            pendingScreen = next;
            pendingClose = next == null;
            closingAt = System.currentTimeMillis();
            MWStyle.beginClose(next != null);
        }
    }

    private boolean transitionRender(net.minecraft.client.gui.GuiGraphics graphics) {
        float in = 1f - MWStyle.openProgress(openedAt);
        float out = MWStyle.closeProgress(closingAt);
        MWStyle.veil(graphics, 0, 0, width, height, Math.max(in * 0.9f, out));
        if (closingAt > 0 && System.currentTimeMillis() - closingAt >= MWStyle.closeMillis()) {
            closingAt = -1; // latch: fire exactly once
            if (pendingClose) {
                minecraft.setScreen(null);
            } else if (pendingScreen != null) {
                MWStyle.handoff();
                minecraft.setScreen(pendingScreen);
            }
            return true;
        }
        return closingAt > 0;
    }

    @Override
    public void onClose() {
        transitionTo(parent);
    }

}
