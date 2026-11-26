package dev.l41nx.mwloadout.client.gui.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import dev.l41nx.mwloadout.client.gui.MWStyle;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.CategoryGuesser;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creative-inventory browser for the Admin Panel: every item the creative tabs expose, searchable
 * and filterable by tab, with a suggested loadout category per item (CategoryGuesser). Select a
 * handful (click, shift-click range, Ctrl+A) and add them all in ONE batch; right-click an item to
 * override its category, or force one category for the whole batch. Items already in the catalog are
 * hidden by default (NEW ONLY) so you only ever see what is left to add.
 */
public class ItemBrowserScreen extends Screen {

    private static final int[] CELL_SIZES = {18, 22, 28, 34};
    private static int cellIndex = 1;       // remembered between openings
    private static boolean rememberNewOnly = true;
    private static final int[] PALETTE = {0xFF4DD0E1, 0xFF5BB8FF, 0xFFB57BFF, 0xFFF5B945, 0xFFFF6B5E, 0xFF5FD38D, 0xFFE57BC8, 0xFF9AA5B1};

    private record BItem(ItemStack stack, String itemId, String ns, String nbt, String name, String tab, String key,
                         boolean isGun, String gunId, String lrtId, String guess, boolean confident,
                         String sName, String sId, String sTab) {
    }

    /** Lower-cased, accent-stripped text so "Súng" matches "sung" and case never matters. */
    private static String norm(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private enum Kind { ALL, GUNS, ITEMS }

    private enum Sort { DEFAULT, NAME, CATEGORY, MOD }

    /** Parsed search box. Plain words must ALL match (AND). Operators: -word (exclude), @mod, #tab,
     *  cat:xx, id:xx, is:gun | is:item | is:new | is:selected | is:added, "quoted phrase". */
    private static final class Query {
        final List<String> terms = new ArrayList<>();
        final List<String> excluded = new ArrayList<>();
        final List<String> mods = new ArrayList<>();
        final List<String> tabs = new ArrayList<>();
        final List<String> cats = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        boolean onlyGuns;
        boolean onlyItems;
        boolean onlySelected;
        boolean onlyNew;
        boolean onlyAdded;

        static Query parse(String raw) {
            Query q = new Query();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(raw);
            while (m.find()) {
                boolean quoted = m.group(1) != null;
                String token = norm(quoted ? m.group(1) : m.group(2)).trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (quoted) {
                    q.terms.add(token);
                } else if (token.startsWith("-") && token.length() > 1) {
                    q.excluded.add(token.substring(1));
                } else if (token.startsWith("@") && token.length() > 1) {
                    q.mods.add(token.substring(1));
                } else if (token.startsWith("#") && token.length() > 1) {
                    q.tabs.add(token.substring(1));
                } else if (token.startsWith("cat:") || token.startsWith("c:")) {
                    q.cats.add(token.substring(token.indexOf(':') + 1));
                } else if (token.startsWith("id:")) {
                    q.ids.add(token.substring(3));
                } else if (token.equals("is:gun")) {
                    q.onlyGuns = true;
                } else if (token.equals("is:item")) {
                    q.onlyItems = true;
                } else if (token.equals("is:selected")) {
                    q.onlySelected = true;
                } else if (token.equals("is:new")) {
                    q.onlyNew = true;
                } else if (token.equals("is:added")) {
                    q.onlyAdded = true;
                } else {
                    q.terms.add(token);
                }
            }
            return q;
        }

        boolean hasTerms() {
            return !terms.isEmpty();
        }
    }

    private final Screen parent;
    private final List<BItem> all = new ArrayList<>();
    private List<BItem> shown = new ArrayList<>();
    private final Map<String, Integer> tabCounts = new LinkedHashMap<>();
    private final Map<String, Integer> modCounts = new LinkedHashMap<>();
    private final Map<String, Integer> catCounts = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> existingKeys = new HashSet<>();
    private final Map<String, String> overrides = new HashMap<>();
    private final List<String> categories = new ArrayList<>();
    private Map<String, Integer> breakdown = new LinkedHashMap<>();

    private String tabFilter = "";
    private String modFilter = "";
    private String catFilter = "";
    private int leftMode;                 // 0 = tabs, 1 = mods, 2 = suggested categories
    private Kind kindFilter = Kind.ALL;
    private Sort sortMode = Sort.DEFAULT;
    private boolean newOnly = rememberNewOnly;
    private final List<int[]> chipRects = new ArrayList<>();  // x, y, w, h of the active-filter chips
    private final List<String> chipKinds = new ArrayList<>();
    private boolean painting;
    private boolean paintValue;
    private int lastPaintIndex = -1;
    private boolean draggingScroll;
    private double scrollGrab;
    private double dragMouseY;
    private Button kindButton;
    private Button sortButton;
    private String forcedCategory = ""; // "" = AUTO (per-item suggestion)
    private int anchorIndex = -1;
    private double scroll;
    private double scrollTarget;
    private double tabScroll;
    private String toast = "";
    private long toastUntil;
    private long openedAt = System.currentTimeMillis();

    private EditBox search;
    private Button newOnlyButton;
    private Button categoryButton;
    private Button selectAllButton;
    private Button clearButton;
    private Button addButton;
    private Button backButton;

    private int leftX = 10;
    private int leftW = 130;
    private int gridX;
    private int gridRight;
    private int gridTop = 80;
    private int gridBottom;
    private int rightX;
    private int rightW = 172;
    private int cols = 1;
    private int cell = 22;
    // Three boxed panels with fixed 8px gutters: left (filters list) | centre (search + grid) | right (controls)
    private int panelTop = 30;
    private int panelBottom;
    private int leftPanelX, leftPanelW, centerPanelX, centerPanelW, rightPanelX, rightPanelW;
    private int leftListTop, leftListBottom;

    /** Non-null = PICK mode: a click returns that item's id to the caller (killstreak editor) instead of
     *  selecting it for the catalog. */
    private final java.util.function.Consumer<String> picker;

    public ItemBrowserScreen(Screen parent) {
        super(Component.literal("Creative Browser"));
        this.parent = parent;
        this.picker = null;
    }

    public ItemBrowserScreen(Screen parent, java.util.function.Consumer<String> picker) {
        super(Component.literal("Pick an item"));
        this.parent = parent;
        this.picker = picker;
        this.newOnly = false; // picking: show everything, including what is already in the catalog
    }

    @Override
    protected void init() {
        if (all.isEmpty()) {
            collect();
        }
        cell = CELL_SIZES[cellIndex];
        boolean narrow = width < 620;
        leftW = narrow ? 96 : 130;
        rightW = narrow ? 118 : 160;
        panelBottom = height - 38;
        leftPanelX = 6;
        leftPanelW = leftW + 8;
        rightPanelW = rightW + 12;
        rightPanelX = width - 6 - rightPanelW;
        centerPanelX = leftPanelX + leftPanelW + 8;
        centerPanelW = rightPanelX - 8 - centerPanelX;
        leftX = leftPanelX + 4;
        rightX = rightPanelX + 6;
        gridX = centerPanelX + 6;
        gridRight = centerPanelX + centerPanelW - 6 - 10; // 10px kept free for the scrollbar
        gridTop = panelTop + 48;
        gridBottom = panelBottom - 6;
        leftListTop = panelTop + 30;
        leftListBottom = panelBottom - 6;
        cols = Math.max(1, (gridRight - gridX) / cell);

        int searchW = gridRight + 10 - gridX;
        search = new EditBox(font, gridX + 6, panelTop + 10, searchW - 12, 12, Component.empty());
        search.setBordered(false);
        search.setTextColor(MWStyle.TEXT);
        search.setHint(Component.literal("Search...  @mod #tab cat:sr -skip"));
        search.setMaxLength(120);
        search.setResponder(text -> {
            scrollTarget = 0;
            refilter();
        });
        addRenderableWidget(search);

        // Right panel: stacked, so it never collides with the grid whatever the GUI width.
        int half = (rightW - 4) / 2;
        kindButton = MWStyle.flatButton(rightX, 46, half, 18, Component.literal(""), button -> {
            kindFilter = Kind.values()[(kindFilter.ordinal() + 1) % Kind.values().length];
            scrollTarget = 0;
            refilter();
        });
        addRenderableWidget(kindButton);
        sortButton = MWStyle.flatButton(rightX + half + 4, 46, half, 18, Component.literal(""), button -> {
            sortMode = Sort.values()[(sortMode.ordinal() + 1) % Sort.values().length];
            refilter();
        });
        addRenderableWidget(sortButton);
        newOnlyButton = MWStyle.flatButton(rightX, 68, rightW, 18, Component.literal(""), button -> {
            newOnly = !newOnly;
            rememberNewOnly = newOnly;
            scrollTarget = 0;
            refilter();
        });
        addRenderableWidget(newOnlyButton);
        categoryButton = MWStyle.flatButton(rightX, 102, rightW, 18, Component.literal(""), button -> cycleForced(1));
        addRenderableWidget(categoryButton);
        int addY = panelBottom - 6 - 22;
        addButton = MWStyle.flatButton(rightX, addY, rightW, 22, Component.literal(""), button -> addSelected());
        addRenderableWidget(addButton);
        selectAllButton = MWStyle.flatButton(rightX, addY - 22, half, 18, Component.literal("ALL"), button -> selectAllShown());
        addRenderableWidget(selectAllButton);
        clearButton = MWStyle.flatButton(rightX + half + 4, addY - 22, half, 18, Component.literal("NONE"), button -> {
            selected.clear();
            selectionChanged();
        });
        addRenderableWidget(clearButton);
        backButton = MWStyle.flatButton(leftPanelX + 4, height - 30, leftW, 20, Component.literal("\u2190 BACK"), button -> onClose());
        addRenderableWidget(backButton);
        if (picker != null) {
            for (var hidden : List.of(addButton, selectAllButton, clearButton, categoryButton)) {
                hidden.visible = false;
                hidden.active = false;
            }
        }

        if (categories.isEmpty()) {
            LinkedHashSet<String> set = new LinkedHashSet<>(List.of("ar", "smg", "lmg", "sr", "dmr", "sg", "pistol",
                    "launcher", "melee", "lethal", "tactical", "consumable", "extra3"));
            for (SlotDefinition slot : LoadoutRegistry.get().slotList()) {
                set.addAll(slot.allowedCategories);
            }
            set.remove("attachment");
            categories.addAll(set);
        }
        refilter();
    }

    // ------------------------------------------------------------------ data

    private static String entryKey(CatalogEntry entry) {
        return entry.gameId + "|" + (entry.kind == CatalogEntry.Kind.GUN ? "" : entry.nbt);
    }

    private void collect() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.level != null && mc.player != null) {
                CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),
                        mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
            }
        } catch (Throwable ignored) {
            // tabs fall back to whatever was already built (e.g. opened the creative screen earlier)
        }
        for (CatalogEntry entry : LoadoutRegistry.get().catalogList()) {
            existingKeys.add(entryKey(entry));
        }
        Set<String> seen = new HashSet<>();
        try {
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                    continue;
                }
                String tabName = tab.getDisplayName().getString();
                for (ItemStack stack : tab.getDisplayItems()) {
                    if (stack.isEmpty() || stack.getItem() instanceof IAttachment || stack.getItem() instanceof IAmmo) {
                        continue; // attachments / ammo are managed by the scanner, not the loadout catalog
                    }
                    ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (rl == null) {
                        continue;
                    }
                    IGun gun = IGun.getIGunOrNull(stack);
                    String gunId = null;
                    if (gun != null) {
                        ResourceLocation gunRl = gun.getGunId(stack);
                        if (gunRl == null) {
                            continue;
                        }
                        gunId = gunRl.toString();
                    }
                    String nbt = gun != null || stack.getTag() == null ? "" : stack.getTag().toString();
                    String lrtId = null;
                    if (stack.getTag() != null) {
                        for (String keyName : new String[]{"MeleeWeaponId", "ConsumableId", "ThrowableId"}) {
                            if (!stack.getTag().getString(keyName).isEmpty()) {
                                lrtId = stack.getTag().getString(keyName);
                                break;
                            }
                        }
                    }
                    String key = (gun != null ? gunId : rl.toString()) + "|" + nbt;
                    if (!seen.add(key)) {
                        continue;
                    }
                    var guess = CategoryGuesser.guess(stack, tabName);
                    String displayName = stack.getHoverName().getString();
                    String ns = gun != null ? gunId.substring(0, Math.max(0, gunId.indexOf(':'))) : rl.getNamespace();
                    all.add(new BItem(stack.copy(), rl.toString(), ns, nbt, displayName, tabName, key,
                            gun != null, gunId, lrtId, guess.category(), guess.confident(),
                            norm(displayName), norm(rl.toString() + (gunId == null ? "" : " " + gunId)), norm(tabName)));
                    tabCounts.merge(tabName, 1, Integer::sum);
                    modCounts.merge(ns, 1, Integer::sum);
                    catCounts.merge(guess.category(), 1, Integer::sum);
                }
            }
        } catch (Throwable t) {
            toast("\u00A7cSOME TABS UNREADABLE");
        }
    }

    private boolean isExisting(BItem item) {
        return existingKeys.contains(item.key);
    }

    private String categoryOf(BItem item) {
        String override = overrides.get(item.key);
        if (override != null) {
            return override;
        }
        return forcedCategory.isEmpty() ? item.guess : forcedCategory;
    }

    /** Relevance of one plain word against an item; -1 = no match. Name prefix > word start > substring >
     *  id > tab > loose in-order letters ("m4a" finds "M4A1 Carbine"). */
    private static int termScore(BItem item, String term) {
        if (item.sName.startsWith(term)) {
            return 100;
        }
        if (item.sName.contains(" " + term)) {
            return 80;
        }
        if (item.sName.contains(term)) {
            return 60;
        }
        if (item.sId.contains(term)) {
            return 40;
        }
        if (item.sTab.contains(term)) {
            return 20;
        }
        if (term.length() >= 3) {
            int at = 0;
            for (int i = 0; i < item.sName.length() && at < term.length(); i++) {
                if (item.sName.charAt(i) == term.charAt(at)) {
                    at++;
                }
            }
            if (at == term.length()) {
                return 10;
            }
        }
        return -1;
    }

    /** -1 when the item fails the query, else a relevance score (0 for operator-only queries). */
    private int matchScore(BItem item, Query q) {
        if (q.onlyGuns && !item.isGun || q.onlyItems && item.isGun) {
            return -1;
        }
        if (q.onlySelected && !selected.contains(item.key) || q.onlyNew && isExisting(item) || q.onlyAdded && !isExisting(item)) {
            return -1;
        }
        for (String mod : q.mods) {
            if (!norm(item.ns).contains(mod)) {
                return -1;
            }
        }
        for (String tab : q.tabs) {
            if (!item.sTab.contains(tab)) {
                return -1;
            }
        }
        for (String cat : q.cats) {
            if (!norm(categoryOf(item)).startsWith(cat)) {
                return -1;
            }
        }
        for (String id : q.ids) {
            if (!item.sId.contains(id)) {
                return -1;
            }
        }
        for (String bad : q.excluded) {
            if (item.sName.contains(bad) || item.sId.contains(bad) || item.sTab.contains(bad)) {
                return -1;
            }
        }
        int total = 0;
        for (String term : q.terms) {
            int score = termScore(item, term);
            if (score < 0) {
                return -1;
            }
            total += score;
        }
        return total;
    }

    private void refilter() {
        Query q = Query.parse(search == null ? "" : search.getValue());
        List<BItem> out = new ArrayList<>();
        Map<String, Integer> scores = new HashMap<>();
        for (BItem item : all) {
            if (!tabFilter.isEmpty() && !item.tab.equals(tabFilter)) {
                continue;
            }
            if (!modFilter.isEmpty() && !item.ns.equals(modFilter)) {
                continue;
            }
            if (!catFilter.isEmpty() && !categoryOf(item).equals(catFilter)) {
                continue;
            }
            if (kindFilter == Kind.GUNS && !item.isGun || kindFilter == Kind.ITEMS && item.isGun) {
                continue;
            }
            if (newOnly && isExisting(item) && !q.onlyAdded) {
                continue;
            }
            int score = matchScore(item, q);
            if (score < 0) {
                continue;
            }
            scores.put(item.key, score);
            out.add(item);
        }
        switch (sortMode) {
            case NAME -> out.sort((a, b) -> a.sName.compareTo(b.sName));
            case CATEGORY -> out.sort((a, b) -> categoryOf(a).compareTo(categoryOf(b)));
            case MOD -> out.sort((a, b) -> a.ns.compareTo(b.ns));
            default -> {
                if (q.hasTerms()) {
                    out.sort((a, b) -> Integer.compare(scores.get(b.key), scores.get(a.key))); // best match first
                }
            }
        }
        shown = out;
        anchorIndex = -1;
        selectionChanged();
    }

    private void selectionChanged() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BItem item : all) {
            if (selected.contains(item.key) && !isExisting(item)) {
                counts.merge(categoryOf(item), 1, Integer::sum);
            }
        }
        breakdown = counts;
    }

    private int newSelectedCount() {
        int count = 0;
        for (BItem item : all) {
            if (selected.contains(item.key) && !isExisting(item)) {
                count++;
            }
        }
        return count;
    }

    private void cycleForced(int dir) {
        int index = forcedCategory.isEmpty() ? -1 : categories.indexOf(forcedCategory);
        index += dir;
        if (index < -1) {
            index = categories.size() - 1;
        } else if (index >= categories.size()) {
            index = -1;
        }
        forcedCategory = index < 0 ? "" : categories.get(index);
        selectionChanged();
    }

    private void selectAllShown() {
        for (BItem item : shown) {
            selected.add(item.key);
        }
        selectionChanged();
    }

    private void invertSelectionShown() {
        for (BItem item : shown) {
            if (!selected.remove(item.key)) {
                selected.add(item.key);
            }
        }
        selectionChanged();
    }

    private static String entryIdFor(BItem item) {
        if (item.isGun) {
            return "gun_" + item.gunId.replace(':', '_').replace('/', '_');
        }
        if (item.lrtId != null) {
            return "lrt_" + item.lrtId.replace(':', '_').replace('/', '_'); // same naming the scanner uses
        }
        String base = "item_" + item.itemId.replace(':', '_').replace('/', '_');
        return item.nbt.isEmpty() ? base : base + "_" + Integer.toHexString(item.nbt.hashCode());
    }

    private void addSelected() {
        JsonArray batch = new JsonArray();
        Set<String> addedKeys = new HashSet<>();
        int skipped = 0;
        for (BItem item : all) {
            if (!selected.contains(item.key)) {
                continue;
            }
            if (isExisting(item)) {
                skipped++;
                continue;
            }
            String category = categoryOf(item);
            CatalogEntry entry = item.isGun
                    ? CatalogEntry.gun(entryIdFor(item), item.name, category, item.gunId)
                    : CatalogEntry.item(entryIdFor(item), item.name, category, item.itemId, item.nbt, 1);
            batch.add(entry.toJson());
            addedKeys.add(item.key);
            if (batch.size() >= 150) {
                send(batch);
                batch = new JsonArray();
            }
        }
        if (batch.size() > 0) {
            send(batch);
        }
        if (addedKeys.isEmpty()) {
            toast("\u00A7cNOTHING NEW SELECTED" + (skipped > 0 ? "  (" + skipped + " ALREADY ADDED)" : ""));
            return;
        }
        existingKeys.addAll(addedKeys);
        selected.removeAll(addedKeys);
        toast("\u00A7a\u2713 ADDED " + addedKeys.size());
        MWStyle.playClick();
        refilter();
    }

    private void send(JsonArray entries) {
        JsonObject payload = new JsonObject();
        payload.add("entries", entries);
        Net.CHANNEL.sendToServer(new Net.C2SAdminUpdate("BULK", "ADDMANY", payload.toString()));
    }

    private void toast(String message) {
        toast = message;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    private static int categoryColor(String category) {
        return PALETTE[Math.abs(category.hashCode()) % PALETTE.length];
    }

    private void clearFilter(String kind) {
        switch (kind) {
            case "tab" -> tabFilter = "";
            case "mod" -> modFilter = "";
            case "cat" -> catFilter = "";
            case "kind" -> kindFilter = Kind.ALL;
            case "search" -> search.setValue("");
            default -> {
            }
        }
        scrollTarget = 0;
        refilter();
    }

    // ------------------------------------------------------------------ layout helpers

    private int viewHeight() {
        return gridBottom - gridTop;
    }

    private int contentHeight() {
        return ((shown.size() + cols - 1) / cols) * cell;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - viewHeight());
    }

    /** Scrollbar thumb geometry: {top, height}. */
    private int[] thumb() {
        int viewH = viewHeight();
        int contentH = Math.max(1, contentHeight());
        int barH = Mth.clamp(viewH * viewH / contentH, 18, viewH);
        int range = Math.max(1, maxScroll());
        int top = gridTop + (int) (scroll / range * (viewH - barH));
        return new int[]{top, barH};
    }

    private List<String[]> leftRows() {
        List<String[]> rows = new ArrayList<>();
        Map<String, Integer> source = leftMode == 0 ? tabCounts : leftMode == 1 ? modCounts : catCounts;
        rows.add(new String[]{"", "ALL", String.valueOf(all.size())});
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(source.entrySet());
        if (leftMode != 0) {
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // biggest mods / categories first
        }
        for (Map.Entry<String, Integer> entry : entries) {
            rows.add(new String[]{entry.getKey(),
                    leftMode == 2 ? entry.getKey().toUpperCase(Locale.ROOT) : entry.getKey(), String.valueOf(entry.getValue())});
        }
        return rows;
    }

    private String activeLeftFilter() {
        return leftMode == 0 ? tabFilter : leftMode == 1 ? modFilter : catFilter;
    }

    // ------------------------------------------------------------------ render

    private void drawSegmentedModes(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, int accent) {
        String[] labels = {"TABS", "MODS", "CATS"};
        int w = leftW / 3;
        MWStyle.fillF(graphics, x, y, x + leftW, y + 18, MWStyle.PANEL_RAISED);
        for (int i = 0; i < 3; i++) {
            int sx = x + i * w;
            int sw = i == 2 ? leftW - 2 * w : w;
            boolean active = leftMode == i;
            boolean hov = mouseX >= sx && mouseX < sx + sw && mouseY >= y && mouseY < y + 18;
            if (active) {
                MWStyle.fillF(graphics, sx, y, sx + sw, y + 18, MWStyle.withAlpha(accent, 0.22f));
                MWStyle.fillF(graphics, sx, y + 16, sx + sw, y + 18, accent);
            } else if (hov) {
                MWStyle.fillF(graphics, sx, y, sx + sw, y + 18, MWStyle.PANEL_HOVER);
            }
            MWStyle.strF(graphics, font, labels[i], sx + sw / 2 - font.width(labels[i]) / 2, y + 5,
                    active ? accent : hov ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
            if (i > 0) {
                MWStyle.fillF(graphics, sx, y + 3, sx + 1, y + 15, MWStyle.border());
            }
        }
        MWStyle.hairline(graphics, x, y, leftW, 18, MWStyle.border());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MWStyle.plainChrome(); // own chrome: pin chromeOpen so buttons are never drawn at a stale ~0 alpha
        float dt = MWStyle.frameDt();
        long now = System.currentTimeMillis();
        // edge auto-scroll while painting a selection by dragging
        if (painting) {
            if (dragMouseY < gridTop + 10) {
                scrollTarget = Mth.clamp(scrollTarget - 14 * dt, 0, maxScroll());
            } else if (dragMouseY > gridBottom - 10) {
                scrollTarget = Mth.clamp(scrollTarget + 14 * dt, 0, maxScroll());
            }
        }
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        scroll = draggingScroll ? scrollTarget : MWStyle.approach((float) scroll, (float) scrollTarget, 0.5f, dt);
        float enter = Mth.clamp((now - openedAt) / 220f, 0f, 1f);
        int accent = 0xFF5FD38D;

        MWStyle.fillF(graphics, 0, 0, width, height, MWStyle.withAlpha(MWStyle.BACKDROP, dev.l41nx.mwloadout.client.ClientCfg.backdropOpacity()));
        MWStyle.strF(graphics, font, "CREATIVE BROWSER", 10, 14, MWStyle.TEXT, false);
        String stat = shown.size() + "/" + all.size() + " shown  \u2022  " + selected.size() + " selected";
        MWStyle.strF(graphics, font, stat, width - 10 - font.width(stat), 14, MWStyle.TEXT_DIM, false);
        MWStyle.fillF(graphics, 10, 26, width - 10, 27, MWStyle.border());
        MWStyle.fillF(graphics, 10, 26, 50, 28, MWStyle.signal());

        // widget state (labels are short on purpose - the captions above them say what they are)
        kindButton.setMessage(Component.literal(kindFilter.name()));
        sortButton.setMessage(Component.literal(sortMode.name()));
        newOnlyButton.setMessage(Component.literal(newOnly ? "NEW ONLY: ON" : "NEW ONLY: OFF"));
        categoryButton.setMessage(Component.literal(forcedCategory.isEmpty() ? "AUTO" : forcedCategory.toUpperCase(Locale.ROOT)));
        int newCount = newSelectedCount();
        addButton.setMessage(Component.literal(newCount > 0 ? "ADD " + newCount : "ADD"));
        if (picker == null) {
            addButton.active = newCount > 0;
            selectAllButton.active = !shown.isEmpty();
            clearButton.active = !selected.isEmpty();
        }

        // ---- three boxed panels ----
        panelBox(graphics, leftPanelX, panelTop, leftPanelW, panelBottom - panelTop);
        panelBox(graphics, centerPanelX, panelTop, centerPanelW, panelBottom - panelTop);
        panelBox(graphics, rightPanelX, panelTop, rightPanelW, panelBottom - panelTop);

        // ---- centre top: search box + active-filter chips ----
        int searchW = gridRight + 10 - gridX;
        MWStyle.fillF(graphics, gridX, panelTop + 6, gridX + searchW, panelTop + 24, MWStyle.PANEL_RAISED);
        MWStyle.hairline(graphics, gridX, panelTop + 6, searchW, 18, search.isFocused() ? accent : MWStyle.border());
        chipRects.clear();
        chipKinds.clear();
        int chipY = panelTop + 28;
        int chipX = gridX;
        String[][] chips = {
                {"tab", tabFilter.isEmpty() ? "" : "TAB " + tabFilter},
                {"mod", modFilter.isEmpty() ? "" : "MOD " + modFilter},
                {"cat", catFilter.isEmpty() ? "" : "CAT " + catFilter.toUpperCase(Locale.ROOT)},
                {"kind", kindFilter == Kind.ALL ? "" : "SHOW " + kindFilter.name()}};
        boolean anyChip = false;
        for (String[] chip : chips) {
            if (!chip[1].isEmpty()) {
                anyChip = true;
            }
        }
        int chipLimit = gridRight + 10;
        for (String[] chip : chips) {
            if (chip[1].isEmpty()) {
                continue;
            }
            String label = MWStyle.ellipsize(font, chip[1], 90) + " \u00D7";
            int cw = font.width(label) + 10;
            if (chipX + cw > chipLimit) {
                break; // never run past the panel edge
            }
            boolean hov = mouseX >= chipX && mouseX < chipX + cw && mouseY >= chipY && mouseY < chipY + 14;
            MWStyle.fillF(graphics, chipX, chipY, chipX + cw, chipY + 14, hov ? MWStyle.withAlpha(MWStyle.NEGATIVE, 0.3f) : MWStyle.withAlpha(accent, 0.2f));
            MWStyle.hairline(graphics, chipX, chipY, cw, 14, hov ? MWStyle.NEGATIVE : accent);
            MWStyle.strF(graphics, font, label, chipX + 5, chipY + 4, hov ? MWStyle.TEXT : accent, false);
            chipRects.add(new int[]{chipX, chipY, cw, 14});
            chipKinds.add(chip[0]);
            chipX += cw + 4;
        }
        if (!anyChip) {
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, "@mod  #tab  cat:sr  is:gun  -skip  \"phrase\"", searchW),
                    gridX, chipY + 4, MWStyle.TEXT_DIM, false);
        }

        // ---- left: tabs / mods / categories ----
        drawSegmentedModes(graphics, leftX, panelTop + 6, mouseX, mouseY, accent);
        List<String[]> left = leftRows();
        graphics.enableScissor(leftX, leftListTop, leftX + leftW, leftListBottom);
        int ty = leftListTop - (int) tabScroll;
        String activeFilter = activeLeftFilter();
        for (String[] row : left) {
            boolean active = row[0].equals(activeFilter);
            boolean hovered = mouseX >= leftX && mouseX < leftX + leftW && mouseY >= ty && mouseY < ty + 16
                    && mouseY >= leftListTop && mouseY < leftListBottom;
            if (ty + 16 >= leftListTop && ty <= leftListBottom) {
                MWStyle.fillF(graphics, leftX, ty, leftX + leftW, ty + 16, hovered ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
                if (active) {
                    MWStyle.fillF(graphics, leftX, ty, leftX + 2, ty + 16, accent);
                }
                int countW = font.width(row[2]);
                MWStyle.scrollText(graphics, font, row[1], leftX + 6, ty + 4, leftW - countW - 16,
                        active ? accent : MWStyle.TEXT, hovered || active);
                MWStyle.strF(graphics, font, row[2], leftX + leftW - 4 - countW, ty + 4, MWStyle.TEXT_DIM, false);
            }
            ty += 17;
        }
        graphics.disableScissor();

        // ---- centre: item grid ----
        int rows = (shown.size() + cols - 1) / cols;
        int viewH = viewHeight();
        BItem hoveredItem = null;
        graphics.enableScissor(gridX, gridTop, gridRight, gridBottom);
        int firstRow = Math.max(0, (int) (scroll / cell));
        int lastRow = Math.min(rows - 1, (int) ((scroll + viewH) / cell) + 1);
        float sc = (cell - 6) / 16f;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = 0; col < cols; col++) {
                int index = row * cols + col;
                if (index >= shown.size()) {
                    break;
                }
                BItem item = shown.get(index);
                int cx = gridX + col * cell;
                int cy = gridTop + row * cell - (int) scroll;
                boolean hovered = mouseX >= cx && mouseX < cx + cell - 1 && mouseY >= cy && mouseY < cy + cell - 1
                        && mouseY >= gridTop && mouseY < gridBottom && !draggingScroll;
                boolean isSelected = selected.contains(item.key);
                boolean existing = isExisting(item);
                float p = Mth.clamp((now - openedAt - Math.min(row, 12) * 25L) / 200f, 0f, 1f);
                if (p <= 0.02f) {
                    continue;
                }
                int slide = (int) ((1f - p) * 6);
                MWStyle.fillF(graphics, cx, cy + slide, cx + cell - 1, cy + cell - 1 + slide,
                        isSelected ? MWStyle.withAlpha(accent, 0.28f) : hovered ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
                graphics.pose().pushPose();
                graphics.pose().translate(cx + (cell - 1 - 16 * sc) / 2f, cy + (cell - 1 - 16 * sc) / 2f + slide, 0);
                graphics.pose().scale(sc, sc, 1f);
                graphics.renderItem(item.stack, 0, 0);
                graphics.pose().popPose();
                if (existing && picker == null) {
                    MWStyle.fillF(graphics, cx, cy + slide, cx + cell - 1, cy + cell - 1 + slide, MWStyle.withAlpha(0xFF000000, 0.55f));
                    MWStyle.fillF(graphics, cx + cell - 6, cy + 2 + slide, cx + cell - 3, cy + 5 + slide, MWStyle.POSITIVE);
                }
                if (isSelected || hovered) {
                    MWStyle.hairline(graphics, cx, cy + slide, cell - 1, cell - 1, isSelected ? accent : MWStyle.borderActive());
                }
                MWStyle.fillF(graphics, cx + 1, cy + cell - 5 + slide, cx + 4, cy + cell - 2 + slide, categoryColor(categoryOf(item)));
                if (hovered) {
                    hoveredItem = item;
                }
            }
        }
        graphics.disableScissor();
        if (shown.isEmpty()) {
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, all.isEmpty() ? "Creative tabs are empty - open a world first."
                    : "No matches. Clear a filter, or NEW ONLY off.", gridRight - gridX), gridX, gridTop + 4, MWStyle.TEXT_DIM, false);
        }
        // draggable scrollbar: wide track, grabbable thumb, highlight on hover/drag
        if (maxScroll() > 0) {
            int[] thumb = thumb();
            int trackX = gridRight + 4;
            boolean overTrack = mouseX >= gridRight && mouseX < gridRight + 14 && mouseY >= gridTop && mouseY < gridBottom;
            boolean overThumb = overTrack && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
            MWStyle.fillF(graphics, trackX, gridTop, trackX + 6, gridBottom, MWStyle.PANEL_RAISED);
            MWStyle.fillF(graphics, trackX, thumb[0], trackX + 6, thumb[0] + thumb[1],
                    draggingScroll ? 0xFFFFFFFF : overThumb ? MWStyle.TEXT : accent);
        }

        // ---- right: captioned controls, live breakdown ----
        int half = (rightW - 4) / 2;
        MWStyle.strF(graphics, font, "SHOW", rightX, 36, MWStyle.TEXT_DIM, false);
        MWStyle.strF(graphics, font, "SORT", rightX + half + 4, 36, MWStyle.TEXT_DIM, false);
        if (picker != null) {
            MWStyle.strF(graphics, font, "PICK MODE", rightX, 96, accent, false);
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, "Click any item to use", rightW), rightX, 110, MWStyle.TEXT_DIM, false);
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, "it for the streak slot.", rightW), rightX, 121, MWStyle.TEXT_DIM, false);
        } else {
        MWStyle.strF(graphics, font, "FILE AS", rightX, 92, MWStyle.TEXT_DIM, false);
        MWStyle.fillF(graphics, rightX, 124, rightX + rightW, 125, MWStyle.border());
        MWStyle.strF(graphics, font, "SELECTED " + selected.size(), rightX, 130, MWStyle.TEXT_DIM, false);
        int addY = panelBottom - 6 - 22;
        int breakdownLimit = addY - 22 - 6;
        int by = 142;
        if (breakdown.isEmpty()) {
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font, "Click or drag items.", rightW), rightX, by, MWStyle.TEXT_DIM, false);
        } else {
            for (Map.Entry<String, Integer> entry : breakdown.entrySet()) {
                if (by + 9 > breakdownLimit) {
                    MWStyle.strF(graphics, font, "\u2026", rightX, by, MWStyle.TEXT_DIM, false);
                    break;
                }
                MWStyle.fillF(graphics, rightX, by + 1, rightX + 6, by + 7, categoryColor(entry.getKey()));
                MWStyle.strF(graphics, font, MWStyle.ellipsize(font, entry.getKey().toUpperCase(Locale.ROOT), rightW - 30), rightX + 11, by, MWStyle.TEXT, false);
                String countText = String.valueOf(entry.getValue());
                MWStyle.strF(graphics, font, countText, rightX + rightW - font.width(countText), by, MWStyle.TEXT_DIM, false);
                by += 11;
            }
        }
        }

        // footer: toast (slides if long) or a short legend; one row, between BACK and the right edge
        int footX = centerPanelX;
        int footW = rightPanelX - 8 - centerPanelX;
        if (now < toastUntil) {
            MWStyle.scrollText(graphics, font, toast, footX, height - 28, footW, MWStyle.TEXT, true);
        } else {
            MWStyle.strF(graphics, font, MWStyle.ellipsize(font,
                    "Drag select  \u2022  Shift range  \u2022  Ctrl+A all  \u2022  Enter add", footW),
                    footX, height - 28, MWStyle.TEXT_DIM, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (hoveredItem != null && !draggingScroll && !painting) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hoveredItem.name));
            tip.add(Component.literal("\u00A78" + hoveredItem.itemId));
            tip.add(Component.literal("\u00A77" + hoveredItem.tab + "  \u2022  " + hoveredItem.ns));
            String category = categoryOf(hoveredItem);
            boolean overridden = overrides.containsKey(hoveredItem.key);
            tip.add(Component.literal("\u00A77Category: \u00A7f" + category.toUpperCase(Locale.ROOT)
                    + (overridden ? " \u00A7e(manual)" : hoveredItem.confident ? " \u00A7a(detected)" : " \u00A76(guess)")));
            if (isExisting(hoveredItem)) {
                tip.add(Component.literal("\u00A7aAlready in catalog"));
            }
            graphics.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    private void panelBox(GuiGraphics graphics, int x, int y, int w, int h) {
        MWStyle.fillF(graphics, x, y, x + w, y + h, MWStyle.withAlpha(MWStyle.PANEL, 0.6f));
        MWStyle.hairline(graphics, x, y, w, h, MWStyle.border());
    }

    // ------------------------------------------------------------------ input

    private int indexAt(double mouseX, double mouseY) {
        if (mouseX < gridX || mouseX >= gridX + cols * cell || mouseY < gridTop || mouseY >= gridBottom) {
            return -1;
        }
        int col = (int) ((mouseX - gridX) / cell);
        int row = (int) ((mouseY - gridTop + scroll) / cell);
        int index = row * cols + col;
        return index >= 0 && index < shown.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // filter chips
        if (button == 0) {
            for (int i = 0; i < chipRects.size(); i++) {
                int[] r = chipRects.get(i);
                if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                    clearFilter(chipKinds.get(i));
                    MWStyle.playClick();
                    return true;
                }
            }
        }
        // left mode switch
        if (button == 0 && mouseX >= leftX && mouseX < leftX + leftW && mouseY >= panelTop + 6 && mouseY < panelTop + 24) {
            leftMode = Mth.clamp((int) ((mouseX - leftX) / (leftW / 3f)), 0, 2);
            tabScroll = 0;
            MWStyle.playClick();
            return true;
        }
        // left list: toggle the filter for that row
        if (button == 0 && mouseX >= leftX && mouseX < leftX + leftW && mouseY >= leftListTop && mouseY < leftListBottom) {
            List<String[]> left = leftRows();
            int index = (int) ((mouseY - leftListTop + tabScroll) / 17);
            if (index >= 0 && index < left.size()) {
                String value = left.get(index)[0];
                String current = activeLeftFilter();
                String next = value.equals(current) ? "" : value;
                switch (leftMode) {
                    case 0 -> tabFilter = next;
                    case 1 -> modFilter = next;
                    default -> catFilter = next;
                }
                scrollTarget = 0;
                MWStyle.playClick();
                refilter();
                return true;
            }
        }
        // draggable scrollbar: grab the thumb, or click the track to jump there
        if (button == 0 && maxScroll() > 0 && mouseX >= gridRight && mouseX < gridRight + 14
                && mouseY >= gridTop && mouseY < gridBottom) {
            int[] thumb = thumb();
            if (mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]) {
                scrollGrab = mouseY - thumb[0];
            } else {
                scrollGrab = thumb[1] / 2.0;
                dragScrollTo(mouseY);
            }
            draggingScroll = true;
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (index >= 0) {
            BItem item = shown.get(index);
            if (button == 1) {
                // right-click: cycle THIS item's category through the known list
                String current = categoryOf(item);
                int at = categories.indexOf(current);
                overrides.put(item.key, categories.get((at + 1) % categories.size()));
                selected.add(item.key);
                selectionChanged();
                MWStyle.playClick();
                return true;
            }
            if (button == 0 && picker != null) {
                picker.accept(item.isGun ? item.gunId : item.itemId + item.nbt);
                MWStyle.playClick();
                onClose();
                return true;
            }
            if (button == 0) {
                if (hasShiftDown() && anchorIndex >= 0) {
                    for (int i = Math.min(anchorIndex, index); i <= Math.max(anchorIndex, index); i++) {
                        selected.add(shown.get(i).key);
                    }
                    paintValue = true;
                } else {
                    paintValue = !selected.contains(item.key);
                    if (paintValue) {
                        selected.add(item.key);
                    } else {
                        selected.remove(item.key);
                    }
                }
                anchorIndex = index;
                lastPaintIndex = index;
                painting = true; // keep dragging to paint more cells with the same on/off state
                dragMouseY = mouseY;
                selectionChanged();
                MWStyle.playClick();
                return true;
            }
        }
        return false;
    }

    private void dragScrollTo(double mouseY) {
        int[] thumb = thumb();
        int range = Math.max(1, viewHeight() - thumb[1]);
        double ratio = Mth.clamp((mouseY - scrollGrab - gridTop) / range, 0.0, 1.0);
        scrollTarget = ratio * maxScroll();
        scroll = scrollTarget; // 1:1 while dragging - no easing lag under the cursor
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            dragScrollTo(mouseY);
            return true;
        }
        if (painting && button == 0) {
            dragMouseY = mouseY;
            int index = indexAt(mouseX, Mth.clamp(mouseY, gridTop, gridBottom - 1));
            if (index >= 0 && index != lastPaintIndex) {
                // paint every cell between the last and the current one, so fast drags skip nothing
                int from = lastPaintIndex < 0 ? index : lastPaintIndex;
                for (int i = Math.min(from, index); i <= Math.max(from, index); i++) {
                    if (paintValue) {
                        selected.add(shown.get(i).key);
                    } else {
                        selected.remove(shown.get(i).key);
                    }
                }
                lastPaintIndex = index;
                selectionChanged();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = draggingScroll || painting;
        draggingScroll = false;
        painting = false;
        lastPaintIndex = -1;
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < leftPanelX + leftPanelW) {
            int overflow = Math.max(0, leftRows().size() * 17 - (leftListBottom - leftListTop));
            tabScroll = Mth.clamp(tabScroll - delta * 24, 0, overflow);
            return true;
        }
        if (hasControlDown() && mouseX >= gridX && mouseX < gridRight) {
            // Ctrl+wheel zooms the grid (4 sizes), remembered for next time
            int next = Mth.clamp(cellIndex + (delta > 0 ? 1 : -1), 0, CELL_SIZES.length - 1);
            if (next != cellIndex) {
                cellIndex = next;
                double ratio = maxScroll() > 0 ? scrollTarget / maxScroll() : 0;
                cell = CELL_SIZES[cellIndex];
                cols = Math.max(1, (gridRight - gridX) / cell);
                scrollTarget = ratio * maxScroll();
                scroll = scrollTarget;
            }
            return true;
        }
        scrollTarget = Mth.clamp(scrollTarget - delta * cell * 2, 0, maxScroll());
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // type-to-search: just start typing anywhere, no need to click the box first
        if (search != null && !search.isFocused() && codePoint >= 32 && !hasControlDown()) {
            setFocused(search);
            search.setFocused(true);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = search != null && search.isFocused();
        if (keyCode == 256) { // Esc: clear search -> clear selection -> leave
            if (typing && !search.getValue().isEmpty()) {
                search.setValue("");
                return true;
            }
            if (!selected.isEmpty()) {
                selected.clear();
                selectionChanged();
                return true;
            }
        }
        if (hasControlDown() && keyCode == 65 && !typing) { // Ctrl+A
            selectAllShown();
            return true;
        }
        if (hasControlDown() && keyCode == 73 && !typing) { // Ctrl+I
            invertSelectionShown();
            return true;
        }
        if (hasControlDown() && keyCode == 70 && search != null) { // Ctrl+F
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (!typing) {
            int page = viewHeight() - cell;
            switch (keyCode) {
                case 266 -> { // Page Up
                    scrollTarget = Mth.clamp(scrollTarget - page, 0, maxScroll());
                    return true;
                }
                case 267 -> { // Page Down
                    scrollTarget = Mth.clamp(scrollTarget + page, 0, maxScroll());
                    return true;
                }
                case 268 -> { // Home
                    scrollTarget = 0;
                    return true;
                }
                case 269 -> { // End
                    scrollTarget = maxScroll();
                    return true;
                }
                default -> {
                }
            }
        }
        if ((keyCode == 257 || keyCode == 335) && !typing && newSelectedCount() > 0) {
            addSelected();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
