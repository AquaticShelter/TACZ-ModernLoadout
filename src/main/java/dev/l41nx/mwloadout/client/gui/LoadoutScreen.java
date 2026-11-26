package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientCache;
import dev.l41nx.mwloadout.client.ClientEvents;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.server.LoadoutApplier;
import dev.l41nx.mwloadout.util.Json;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The MW19 loadout screen, laid out like the reference frame: preset rail with "+ NEW CLASS" on
 * the left, then the stacked card column — two tall weapon cards rendering the guns' own HUD
 * silhouettes with a GUNSMITH button docked beside each, the PERKS card as a row of three icon
 * boxes, and compact equipment cards (name left, real item icon right). Everything scrolls;
 * SAVE / EQUIP / CLOSE stay pinned.
 *
 * Live validation: cards whose selection is no longer legal under the CURRENT working perks
 * (picked Overkill -> the old pistol is instantly illegal) flag REPLACE in red the moment you
 * return from the perk picker, using the same effectiveCategories the server enforces — no more
 * "GUI only updates after reopening".
 *
 * The working copy survives init() re-entry (picker returns, window resizes) — created once per
 * screen instance or on explicit preset switch, never silently reloaded.
 */
public final class LoadoutScreen extends MwScreen {

    private static final int RAIL_WIDTH = 98;
    private static final int GUNSMITH_WIDTH = 54;
    private static final int LOADOUT_OUT_MS = (int) MWStyle.ANIM_SCREEN_CLOSE; // retract animation: same clock as the chrome fade
    private int railStride = 19; // row height + gap of the class rail (scroll maths)
    /** Opening cascade: rail rows, cards and the killstreak column slide in one after another when the menu opens. */
    private long cascadeStart = System.currentTimeMillis();
    private long cardCascadeStart = System.currentTimeMillis();   // re-armed on class switch so the cards re-cascade
    private float railIndicatorY = -1f;
    private int streakCacheKey = Integer.MIN_VALUE;
    private java.util.List<String> streakCache = java.util.List.of();

    private java.util.List<String> cachedStreaks(Rules rules) {
        int key = (working.streaks == null ? 7 : working.streaks.hashCode()) * 31 + rules.streakRewards.size() * 17
                + rules.streakRewards.hashCode();
        if (key != streakCacheKey) {
            streakCacheKey = key;
            streakCache = dev.l41nx.mwloadout.data.StreakPool.effectiveIds(rules, working);
        }
        return streakCache;
    }

    private PlayerProfile profile;
    private int presetIndex = -1;
    private net.minecraft.client.gui.components.Button cloudButton;
    private long cloudConfirmUntil;
    private long saveConfirmUntil;
    private boolean tutorialChecked;
    // Right-click context menu on class rows: (x, y) anchor, target preset, entries in order.
    private int menuX = -1, menuY, menuPreset = -1;
    private long menuOpenedAt; // drives the context menu's pop-in animation, see render()
    // "equip" leads: the right-click path to deploy a class. Single left-click only SELECTS.
    private static final String[] MENU_OPS = {"equip", "random", "reset", "clear", "favorite", "duplicate", "delete"};
    // Class rail "push in / push out" (like the MW class-select reference): once a class has been picked the rail slides away to
    // the left leaving a thin handle; hovering the left edge pushes it back out, and it tucks away again when the mouse leaves.
    /** Tab pins the class rail OPEN (or shut): while pinned it never tucks itself away after a pick. Static = survives re-opening the screen. */
    private static boolean railPinned;
    private boolean railLatched;       // becomes true after the first class pick - before that the rail simply stays out
    private boolean railCollapsed;
    private boolean railNeedsLeave;    // after a pick the rail stays in until the mouse has left it once
    // XP bar memo (see render): recomputed only when xp / the curve rules change
    private long xpCacheXp = Long.MIN_VALUE;
    private int xpCachePer = -1;
    private double xpCacheGrowth = Double.NaN;
    private String xpCacheLevelLabel;
    private String xpCacheText = "";
    private float xpCacheProgress;
    private float railSlide;           // 0 = out, 1 = pushed in
    private int railShift;             // current x offset of everything that belongs to the rail (<= 0)
    private int contentShiftX;         // how far the loadout panel itself has followed the rail towards the left edge (<= 0)
    private net.minecraft.client.gui.components.Button railEquipButton;
    private int lastClickedRow = -1;
    private long lastClickAt;
    // The JSON last persisted for the current index — auto-save skips when nothing changed,
    // so merely opening/closing or equipping never re-sends (and never re-triggers dedupe).
    // selection "pop" animations: when a perk column / killstreak changes, that icon bounces + flashes once
    private final java.util.Map<String, Long> perkPopAt = new java.util.HashMap<>();
    private final java.util.Map<String, String> lastPerks = new java.util.HashMap<>();
    private final java.util.Map<String, Long> streakPopAt = new java.util.HashMap<>();
    private java.util.List<String> lastStreaks;

    private String lastSavedJson;
    private int lastSavedIndex = -1;
    private LoadoutPreset working;
    private EditBox nameBox;
    private long openedAt = System.currentTimeMillis();
    private boolean openChimePlayed;
    // Screen transition state: transitionTo() arms a 120ms fade-out veil, then the real
    // setScreen fires; opening plays the veil in reverse. Input is blocked while closing.
    private net.minecraft.client.gui.screens.Screen pendingScreen;
    private boolean pendingClose;
    private long closingAt;

    private final List<Card> cards = new ArrayList<>();
    private final List<PresetRow> presetRows = new ArrayList<>();
    private double cardScroll;
    private double railScroll;
    // Scroll targets: wheel sets the target, render eases the visible value toward it (smooth).
    private double cardScrollTarget;
    private double railScrollTarget;
    private long lastFrameMillis = System.currentTimeMillis();
    private int listTop;
    private int listBottom;
    private int contentX;
    private int contentWidth;
    private String lockMsg;
    private long lockMsgAt;

    public LoadoutScreen() {
        super(Component.translatable("screen.mwloadout.title"));
    }

    @Override
    protected void init() {
        // Transition state MUST reset on every (re)show: Minecraft reuses this instance when a
        // child screen returns, and a latched closingAt (-1) would leave transitionTo() dead —
        // the v1.2.1 "stuck after gunsmith" brick. Re-arming openedAt also replays the fade-in.
        closingAt = 0;
        MWStyle.screenOpened();
        pendingScreen = null;
        pendingClose = false;
        openedAt = System.currentTimeMillis();
        if (!openChimePlayed) {
            openChimePlayed = true;
            MWStyle.playOpen();
        }
        // Auto-save: any change made in a child screen (picker/gunsmith/perks) lands here on
        // return — persist immediately, MW19-style, no SAVE button. But only when something
        // ACTUALLY changed, so returning from a screen that touched nothing is silent.
        autoSaveIfDirty();
        profile = ClientCache.profile();
        if (profile == null) {
            onClose();
            return;
        }
        if (working == null) {
            presetIndex = Mth.clamp(profile.activeIndex, 0,
                    Math.min(LoadoutRegistry.get().rules().maxPresets, PlayerProfile.HARD_MAX_PRESETS) - 1);
            loadWorkingCopy();
        }
        rebuildLayout();
    }

    /** Persist the working copy only if it differs from what we last sent for this index —
     *  the guard that stops redundant saves (and the dedupe churn they caused). */
    private void autoSaveIfDirty() {
        if (working == null || presetIndex < 0) {
            return;
        }
        String json = Json.GSON.toJson(working.toJson());
        if (presetIndex == lastSavedIndex && json.equals(lastSavedJson)) {
            return; // nothing changed since the last save for this class
        }
        lastSavedIndex = presetIndex;
        lastSavedJson = json;
        Net.CHANNEL.sendToServer(new Net.C2SSaveLoadout(presetIndex, json));
    }

    /** Deploy a class: server decides equip vs. deny (combat/cooldown/hard) and answers with a
     *  toast; selection is remembered regardless. Reached via double-click or right-click Equip. */
    private void equipPreset(int index) {
        autoSaveIfDirty();     // flush edits to the class we're leaving
        presetIndex = index;
        cardCascadeStart = System.currentTimeMillis() - 60; // cards re-cascade when switching class
        loadWorkingCopy();
        rebuildLayout(false); // trust the freshly-loaded class's own name, not the old nameBox text
        Net.CHANNEL.sendToServer(new Net.C2SSelectLoadout(index));
    }

    private void loadWorkingCopy() {
        LoadoutPreset stored = profile.ensurePreset(presetIndex, LoadoutRegistry.get().rules().maxPresets);
        working = stored == null ? new LoadoutPreset() : LoadoutPreset.fromJson(stored.toJson());
        applyDefaultPerks(working);
        applyDefaultStreaks(working);
        lastPerks.clear();
        lastStreaks = null;
        // Baseline the dirty-tracker to what we just loaded, so no phantom save fires.
        lastSavedIndex = presetIndex;
        lastSavedJson = Json.GSON.toJson(working.toJson());
    }

    /** Fresh/blank classes start with a random valid perk per column instead of three empty perk
     *  slots. Runs per-column so a single invalid perk gets healed too, not only a fully-blank
     *  preset — and it's random rather than a fixed preferred pick, because hardcoding specific
     *  perk ids here was the actual cause of "invalid perk" showing up: the moment an admin
     *  renamed, disabled, or moved one of those exact ids to a different column, every class
     *  loading this screen would try to re-apply a now-broken reference. Picking randomly from
     *  whatever's currently enabled in that column can never go stale the same way. */
    private static void applyDefaultPerks(LoadoutPreset preset) {
        var registry = LoadoutRegistry.get();
        java.util.Random random = new java.util.Random();
        for (Rules.PerkSlot column : registry.rules().perkSlots) {
            String current = preset.perks.get(column.id);
            PerkDefinition currentPerk = current == null ? null : registry.perk(current);
            if (currentPerk != null && currentPerk.enabled && column.id.equals(currentPerk.perkSlot)) {
                continue; // already valid, leave the player's choice alone
            }
            java.util.List<PerkDefinition> pool = new java.util.ArrayList<>();
            for (PerkDefinition perk : registry.perkList()) {
                if (perk.enabled && column.id.equals(perk.perkSlot)) {
                    pool.add(perk);
                }
            }
            if (!pool.isEmpty()) {
                preset.perks.put(column.id, pool.get(random.nextInt(pool.size())).id);
            }
        }
    }

    /** Empty killstreak slots roll random legal picks (distinct kill costs, max 3) - written into the class so the
     *  loadout shows exactly what will deploy, same idea as the random default perks above. */
    private static void applyDefaultStreaks(LoadoutPreset preset) {
        Rules rules = LoadoutRegistry.get().rules();
        if (dev.l41nx.mwloadout.data.StreakPool.needsFill(rules, preset)) {
            java.util.List<String> keep = preset.streaks == null ? java.util.List.of() : preset.streaks;
            preset.streaks = dev.l41nx.mwloadout.data.StreakPool.sanitize(
                    dev.l41nx.mwloadout.data.StreakPool.fillRandom(rules, keep, new java.util.Random()), rules);
        }
    }

    /** RESET wipes the current class back to a blank slate (then default perks re-apply) —
     *  the "delete custom loadout / clear everything" the screen was missing. */
    private void resetCurrentClass() {
        working = new LoadoutPreset();
        working.name = "Custom Class";
        applyDefaultPerks(working);
        applyDefaultStreaks(working);
        String json = Json.GSON.toJson(working.toJson());
        lastSavedIndex = presetIndex;
        lastSavedJson = json;
        Net.CHANNEL.sendToServer(new Net.C2SSaveLoadout(presetIndex, json));
        rebuildLayout(false); // show the reset "Custom Class" name, not whatever was typed before
        MWStyle.playClick();
    }

    public void onDataRefreshed() {
        PlayerProfile fresh = ClientCache.profile();
        if (fresh != null) {
            profile = fresh;
            // Delete/Favorite (and anything else that can remove or reorder presets) clamp/
            // reassign profile.activeIndex on the SERVER, but this screen's own local
            // `presetIndex` never followed along — after a delete it could end up out of range
            // (the guard below then silently skips reloading the content pane, so the name box
            // and slot cards keep showing the just-deleted class while the rail on the left
            // already shows the shrunk list). Only resync when the local index is actually
            // invalid — NOT whenever it merely differs from profile.activeIndex, since a normal
            // class switch sends C2SSelectLoadout and then keeps editing locally while that
            // round-trip is still in flight; treating "differs" as "wrong" snapped the screen
            // back to whatever class the last sync still thought was active, which just looked
            // like clicking a different class silently undid itself.
            if (presetIndex < 0 || presetIndex >= profile.presets.size()) {
                presetIndex = Mth.clamp(profile.activeIndex, 0, Math.max(0, profile.presets.size() - 1));
            }
            // Reload the working copy from the fresh profile so a server-side mutation
            // (randomize / reset / clear / favorite reorder) shows on the slot cards and HUD
            // THIS frame — no more closing and reopening to see the new gear. The name box is
            // the one thing we preserve, since the player may be mid-edit.
            if (presetIndex >= 0 && presetIndex < profile.presets.size()) {
                String typedName = nameBox != null && nameBox.isFocused() ? nameBox.getValue() : null;
                loadWorkingCopy();
                if (typedName != null) {
                    working.name = typedName;
                }
            }
            rebuildLayout();
        }
    }

    private void rebuildLayout() {
        rebuildLayout(true);
    }

    /**
     * @param preserveTypedName true = keep whatever is currently typed in the (about-to-be-
     *     replaced) nameBox — correct when this rebuild is just a layout refresh for the SAME
     *     class (returning from a child screen, a mid-typing perk change, onDataRefreshed()).
     *     false = trust {@code working.name} instead — required whenever `working` was just
     *     (re)loaded for a DIFFERENT class (switching preset, reset). Without this distinction
     *     the old nameBox's leftover text (e.g. "sigma1" from the class just left) got copied
     *     onto the newly-loaded class and then auto-saved onto IT — v4.3.6 bug #2, class names
     *     overwriting each other on every switch.
     */
    private void rebuildLayout(boolean preserveTypedName) {
        String preservedName = preserveTypedName && nameBox != null ? nameBox.getValue() : working.name;
        clearWidgets();
        cards.clear();
        presetRows.clear();

        contentX = 10 + RAIL_WIDTH + 10;
        // slim column (concept: ~215 at GUI 2); the BUILD dock slides out over the space on its right, so it reserves nothing
        contentWidth = Math.max(170, Math.min(244, width - contentX - 14));
        listTop = 56;
        listBottom = height - 32;

        // Preset rail: existing classes + one "+ NEW CLASS" row (up to maxPresets, hard-ceilinged
        // at PlayerProfile.HARD_MAX_PRESETS regardless of what the admin rule says).
        int maxPresets = Math.min(LoadoutRegistry.get().rules().maxPresets, PlayerProfile.HARD_MAX_PRESETS);
        int shown = Math.min(profile.presets.size(), maxPresets);
        int railY = 44;
        int rowCount = shown + (shown < maxPresets ? 1 : 0);
        int railAvail = (height - 52) - railY;
        int rowGap = 3;
        int rowH = Mth.clamp((railAvail - Math.max(0, rowCount - 1) * rowGap) / Math.max(1, rowCount), 14, 18); // thinner rows (was 15..20)
        railStride = rowH + rowGap;
        for (int i = 0; i < shown; i++) {
            presetRows.add(new PresetRow(i, false, 10, railY, RAIL_WIDTH, rowH));
            railY += rowH + rowGap;
        }
        if (shown < maxPresets) {
            presetRows.add(new PresetRow(shown, true, 10, railY, RAIL_WIDTH, rowH));
        }

        // Class name: label ABOVE the box — nothing overlaps.
        nameBox = new EditBox(font, contentX, 45, contentWidth, 14, Component.empty());
        nameBoxHiddenByPeek = false; // a fresh box starts visible
        nameBox.setMaxLength(24);
        nameBox.setValue(preservedName);
        nameBox.setBordered(false);
        nameBox.setTextColor(MWStyle.TEXT);
        nameBox.setEditable(true); // editing is always free now — only SWAPPING is gated
        nameBox.setResponder(value -> working.name = value);
        addRenderableWidget(nameBox);

        // ---- Card column, after the MW reference: weapons full width and tall (big silhouette, name below),
        // melee full width, then a left stack of TACTICAL / LETHAL / FIELD UPGRADE / PERKS with the KILLSTREAK
        // column on the right spanning all of it. Heights shrink together (down to ~78%) so it fits without scrolling.
        boolean overkill = LoadoutApplier.hasSpecialPerk(working, "overkill");
        boolean underkill = !overkill && LoadoutApplier.hasSpecialPerk(working, "underkill");
        Rules rules = LoadoutRegistry.get().rules();
        List<SlotDefinition> wide = new ArrayList<>();      // primary / secondary (+ gunsmith dock)
        List<SlotDefinition> melee = new ArrayList<>();
        List<SlotDefinition> equipment = new ArrayList<>(); // everything else: left stack
        for (SlotDefinition slot : visibleSlots()) {
            if ("primary".equals(slot.id) || "secondary".equals(slot.id)) {
                wide.add(slot);
            } else if ("melee".equals(slot.id)) {
                melee.add(slot);
            } else {
                equipment.add(slot);
            }
        }
        boolean hasPerks = !rules.perkSlots.isEmpty();
        // Sized from the screen height after the MW4 class screen: the column fills ~79% of it (weapons 15%, melee 9.5%, equipment 7.6%,
        // perks 9.5%), so it reads the same at any GUI scale. The floors below keep the text legible; the fit factor handles short windows.
        final int baseWeapon = Math.round(height * 0.150f), baseMelee = Math.round(height * 0.095f),
                baseEquip = Math.round(height * 0.076f), basePerk = Math.round(height * 0.095f), baseGap = 2;
        int count = wide.size() + melee.size() + equipment.size() + (hasPerks ? 1 : 0);
        int needed = wide.size() * baseWeapon + melee.size() * baseMelee + equipment.size() * baseEquip
                + (hasPerks ? basePerk : 0) + Math.max(0, count - 1) * baseGap;
        float fit = needed <= 0 ? 1f : Mth.clamp((listBottom - listTop - 8) / (float) needed, 0.60f, 1f);
        // Floors keep the text from hugging the card outline at any GUI scale; the column scrolls instead of squashing.
        int weaponH = Math.max(38, Math.round(baseWeapon * fit)), meleeH = Math.max(22, Math.round(baseMelee * fit));
        int equipH = Math.max(25, Math.round(baseEquip * fit)), perkH = Math.max(44, Math.round(basePerk * fit)), gap = Math.max(2, Math.round(baseGap * fit));
        int ksW = Mth.clamp(contentWidth * 30 / 100, 60, 80);
        int equipW = contentWidth - ksW - 6;

        int cardY = listTop + 6;
        for (SlotDefinition slot : wide) {
            cards.add(new SlotCard(slot, true, overkill, underkill, contentX, cardY, contentWidth, weaponH));
            cardY += weaponH + gap;
        }
        for (SlotDefinition slot : melee) {
            cards.add(new SlotCard(slot, false, overkill, underkill, contentX, cardY, contentWidth, meleeH));
            cardY += meleeH + gap;
        }
        int equipmentTop = cardY;
        for (SlotDefinition slot : equipment) {
            cards.add(new SlotCard(slot, false, overkill, underkill, contentX, cardY, equipW, equipH));
            cardY += equipH + gap;
        }
        if (hasPerks) {
            cards.add(new PerkCard(rules.perkSlots, contentX, cardY, equipW, perkH));
            cardY += perkH + gap;
        }
        // Killstreak column: top-aligned with the first left-stack card, bottom-aligned with the perks card.
        if (rules.killstreakEnabled) {
            int panelBottom = cardY - gap;
            int panelH = Math.max(100, panelBottom - equipmentTop);
            cards.add(new KillstreakCard(contentX + equipW + 6, equipmentTop, ksW, panelH));
        }

        int footerY = height - 28;
        // EQUIP docked bottom-LEFT (under the class rail) per request.
        railEquipButton = addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(10, footerY, RAIL_WIDTH, 20,
                Component.translatable("screen.mwloadout.equip"), button -> save(true))).primary());
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width - 80, footerY, 70, 20,
                Component.translatable("screen.mwloadout.close"), button -> onClose())));
        // SETTINGS always available (point-and-click config) — top-right anchor.
        addRenderableWidget(MWStyle.flatButton(width - 80, 32, 70, 16,
                Component.translatable("screen.mwloadout.settings_btn"),
                button -> transitionTo(new SettingsScreen(this))));
        int rightStackY = 52; // next free row of the top-right utility stack (SETTINGS / CLOUD / TO DEVICE / FIRING RANGE)
        // CLOUD: re-send this device's saved classes (the automatic offer happens once per join and is silent when it fails).
        // Two clicks - it replaces the classes you have on this server.
        if (dev.l41nx.mwloadout.client.CloudProfile.hasLocalCopy() && LoadoutRegistry.get().rules().allowCloudImport) {
            rightStackY = 72;
            cloudButton = addRenderableWidget(MWStyle.flatButton(width - 80, 52, 70, 16,
                    Component.translatable("screen.mwloadout.cloud_btn"), button -> {
                        long now = System.currentTimeMillis();
                        if (now > cloudConfirmUntil) {
                            cloudConfirmUntil = now + 3500;
                            button.setMessage(Component.translatable("screen.mwloadout.cloud_confirm"));
                            return;
                        }
                        cloudConfirmUntil = 0;
                        button.setMessage(Component.translatable("screen.mwloadout.cloud_btn"));
                        if (!dev.l41nx.mwloadout.client.CloudProfile.manualImport()) {
                            dev.l41nx.mwloadout.client.ClientCache.toast("message.mwloadout.cloud_import_refused", "");
                        }
                    }));
        }
        // TO DEVICE: the reverse - keep the classes this server holds for you in this device's copy (backed up first). Only on a remote server;
        // in single player the copy follows automatically. Two clicks, it replaces the copy.
        if (dev.l41nx.mwloadout.client.CloudProfile.canSaveFromServer()) {
            int toDeviceY = rightStackY;
            rightStackY += 20;
            addRenderableWidget(MWStyle.flatButton(width - 80, toDeviceY, 70, 16,
                    Component.translatable("screen.mwloadout.todevice_btn"), button -> {
                        long now = System.currentTimeMillis();
                        if (now > saveConfirmUntil) {
                            saveConfirmUntil = now + 3500;
                            button.setMessage(Component.translatable("screen.mwloadout.todevice_confirm"));
                            return;
                        }
                        saveConfirmUntil = 0;
                        button.setMessage(Component.translatable("screen.mwloadout.todevice_btn"));
                        int saved = dev.l41nx.mwloadout.client.CloudProfile.saveFromServer();
                        if (saved >= 0) {
                            dev.l41nx.mwloadout.client.ClientCache.toast("message.mwloadout.cloud_saved", String.valueOf(saved));
                        } else {
                            dev.l41nx.mwloadout.client.ClientCache.toast("message.mwloadout.cloud_save_failed", "");
                        }
                    }));
        }
        // FIRING RANGE / LEAVE RANGE right from class select, under CLOUD (the gunsmith keeps its own button). The class is saved first:
        // the dimension change closes the screen.
        if (LoadoutRegistry.get().rules().firingRangeEnabled) {
            boolean inRange = isInFiringRangeClient();
            addRenderableWidget(MWStyle.flatButton(contentX, footerY, 100, 20, // bottom-left like the MW4 class screen
                    Component.translatable(inRange ? "screen.mwloadout.leave_range" : "screen.mwloadout.firing_range"), button -> {
                        autoSaveIfDirty();
                        Net.CHANNEL.sendToServer(new Net.C2SRangeToggle());
                        MWStyle.playClick();
                        net.minecraft.client.Minecraft.getInstance().setScreen(null);
                    }));
        }
        // CARDS and KILLFEED used to sit under SETTINGS as two more buttons; they now live inside the Settings screen
        // (one entry point, a cleaner corner).

        // re-clamp both scroll targets against the just-rebuilt content size. Without
        // this, deleting/reducing rows (a class delete, fewer visible slots after a perk change)
        // left the scroll TARGET sitting at its old, now-out-of-range value forever — approach()
        // keeps chasing a target beyond the new (smaller) overflow, so the rail/card list renders
        // scrolled permanently past its own content and never recovers until the screen is closed
        // and reopened (which reset scroll to 0 via a fresh instance). Clamping the target here
        // lets approach() smoothly animate back into view instead, on THIS rebuild already.
        int railClipBottom = height - 52;
        int railOverflow = Math.max(0, presetRows.size() * railStride - (railClipBottom - 44));
        railScrollTarget = Mth.clamp(railScrollTarget, 0, railOverflow);
        int cardOverflow = Math.max(0, cardContentHeight() - (listBottom - listTop));
        cardScrollTarget = Mth.clamp(cardScrollTarget, 0, cardOverflow);
    }

    // ---- hover PEEK: after a short dwell on an unselected class the main card column shows that class exactly as clicking it would
    // (weapons with attachments, perks, killstreaks) - read-only, no selection change, gone the moment the mouse leaves the row.
    private static final long PEEK_DWELL_MS = 220L;
    private int peekIndex = -1;
    private long peekSince;
    private LoadoutPreset peekPreset;
    private boolean nameBoxHiddenByPeek;

    private void updatePeek(int mouseX, int mouseY) {
        int hovered = -1;
        if (closingAt <= 0 && railSlide <= 0.5f) {
            for (PresetRow row : presetRows) {
                if (!row.isNew && row.index != presetIndex && row.index >= 0 && row.index < profile.presets.size()
                        && row.contains(mouseX, mouseY)) {
                    hovered = row.index;
                    break;
                }
            }
        }
        if (hovered != peekIndex) {
            peekIndex = hovered;
            peekSince = System.currentTimeMillis();
            peekPreset = null;
        }
        if (peekIndex >= 0 && peekPreset == null && System.currentTimeMillis() - peekSince >= PEEK_DWELL_MS) {
            peekPreset = LoadoutPreset.fromJson(profile.presets.get(peekIndex).toJson());
        }
    }

    private List<SlotDefinition> visibleSlots() {
        List<SlotDefinition> out = new ArrayList<>();
        for (SlotDefinition slot : LoadoutRegistry.get().slotList()) {
            if (slot.enabled && !slot.hidden) {
                out.add(slot);
            }
        }
        out.sort((a, b) -> Integer.compare(a.hotbarIndex, b.hotbarIndex));
        return out;
    }

    private void save(boolean equip) {
        working.name = nameBox.getValue();
        Net.CHANNEL.sendToServer(new Net.C2SSaveLoadout(presetIndex, Json.GSON.toJson(working.toJson())));
        if (equip) {
            Net.CHANNEL.sendToServer(new Net.C2SSelectLoadout(presetIndex));
            onClose();
        }
    }

    private int cardContentHeight() {
        if (cards.isEmpty()) {
            return 0;
        }
        int bottom = 0;
        for (Card card : cards) {
            bottom = Math.max(bottom, card.y + card.h);
        }
        return bottom + 6 - listTop;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!tutorialChecked) {
            tutorialChecked = true;
            if (TutorialScreen.shouldShow()) {
                minecraft.setScreen(new TutorialScreen(this));
                return;
            }
        }
        // "EDIT LOADOUTS" (its own lang key) instead of "Loadout - <class>" — a clearer,
        // more action-oriented CoD-style header; the active class name is already shown in the
        // name box right below it, so repeating it up here was redundant. Bolded (§l) — this is
        // the highest-priority text on the whole screen and should read that way at a glance.
        String title = "\u00a7l" + MWStyle.tr("screen.mwloadout.title_bar");
        long nowMs = System.currentTimeMillis();
        float dtTicks = Math.min(4f, (nowMs - lastFrameMillis) / 50f);
        lastFrameMillis = nowMs;
        cardScroll = MWStyle.approach((float) cardScroll, (float) cardScrollTarget, 0.5f, dtTicks);
        railScroll = MWStyle.approach((float) railScroll, (float) railScrollTarget, 0.5f, dtTicks);
        MWStyle.screenChrome(graphics, width, title, openedAt);
        String versionTag = modVersionTag();
        if (!versionTag.isEmpty()) { // gold tag after the title, like the BETA tag of the MW4 header
            int tagX = 10 + font.width(title.toUpperCase(java.util.Locale.ROOT)) + 8;
            MWStyle.fillF(graphics, tagX, 11, tagX + font.width(versionTag) + 8, 23, MWStyle.signal());
            MWStyle.strF(graphics, font, versionTag, tagX + 4, 13, 0xFF101114, false);
        }
        renderDebugOverlay(graphics);

        if (!ClientCache.editable()) {
            // Editing is always free — this banner reports SWAP availability, live-counting.
            String reason = switch (ClientCache.editReasonKey()) {
                case "hard" -> MWStyle.tr("screen.mwloadout.swap_on_death");
                case "cooldown" -> Component.translatable("screen.mwloadout.swap_in", ClientCache.editSecondsLive()).getString();
                case "use_lock" -> ClientCache.editSecondsLive() > 0
                        ? Component.translatable("screen.mwloadout.use_lock", ClientCache.editSecondsLive()).getString()
                        : MWStyle.tr("screen.mwloadout.use_lock_respawn");
                default -> Component.translatable("screen.mwloadout.readonly_combat", ClientCache.editSecondsLive()).getString();
            };
            int reasonWidth = font.width(reason);
            MWStyle.fillF(graphics, width - reasonWidth - 24, 9, width - 10, 23, MWStyle.fa(0x90C0392B));
            MWStyle.text(graphics, font, reason, width - reasonWidth - 17, 13, MWStyle.TEXT);
        }

        // ---- rail push in / out ----
        int railRight = 10 + RAIL_WIDTH + 10;
        if (railLatched && closingAt <= 0) {
            if (railCollapsed) {
                if (railNeedsLeave && mouseX > 20) {
                    railNeedsLeave = false;
                }
                if (!railNeedsLeave && mouseX <= 24) { // a wider edge: the old 12 px strip was easy to miss
                    railCollapsed = false; // hovering the edge pushes the rail back out
                }
            } else if (mouseX >= railRight && !railPinned) {
                railCollapsed = true;      // mouse left the opened rail: tuck it away again (unless pinned with Tab)
            }
        }
        // 0.627 = 1 - (1 - 0.28)^3: the speed the old easeOutCubic(railSlide) mapping gave the push-IN. That mapping was flat near
        // 1, so the push-OUT (1 -> 0) crawled away from the edge. Driving the value directly makes both directions mirror images.
        railSlide = MWStyle.approach(railSlide, railCollapsed ? 1f : 0f, 0.627f, MWStyle.frameDt());
        if (railSlide < 0.004f) {
            railSlide = 0f;
        } else if (railSlide > 0.996f) {
            railSlide = 1f;
        }
        // The rail leaves COMPLETELY (no sliver / border left at the edge); the panel follows it to a 12px margin.
        float railEase = railSlide;
        railShift = -Math.round(railEase * (RAIL_WIDTH + 18));
        contentShiftX = -Math.round(railEase * Math.max(0, contentX - 12));
        // (the EQUIP button stays where it is: it must stay reachable while the rail is pushed in)
        graphics.pose().pushPose();
        graphics.pose().translate(railShift, 0, 0);
        MWStyle.header(graphics, 10, 32, RAIL_WIDTH, MWStyle.tr("screen.mwloadout.classes"));
        // Clip the class rail so a long list (12+ classes) scrolls UNDER a clean edge instead of
        // drawing over the footer buttons. Fade the bottom few px for a soft cut-off.
        int railTop = 44;
        int railClipBottom = height - 52; // leaves room for footer buttons at height-28
        // (scissor works in screen space, so it has to be shifted by hand)
        int scissorX0 = Math.max(0, 4 + railShift);
        int scissorX1 = Math.max(scissorX0 + 1, 10 + RAIL_WIDTH + 6 + railShift);
        graphics.enableScissor(scissorX0, railTop, scissorX1, railSlide >= 0.999f ? railTop + 1 : railClipBottom);
        long cascadeNow = nowMs - cascadeStart;
        // closing (or handing over to a child screen): everything retracts, last cards first
        float outT = closingAt > 0 ? Mth.clamp((nowMs - closingAt) / (float) MWStyle.closeMillis(), 0f, 1f) : 0f;
        for (int rowIndex = 0; rowIndex < presetRows.size(); rowIndex++) {
            float enter = Math.min(MWStyle.easeOutCubic(Mth.clamp((cascadeNow - rowIndex * 26L) / 280f, 0f, 1f)),
                    1f - MWStyle.easeOutCubic(Mth.clamp(outT * 1.15f - 0.04f * (presetRows.size() - rowIndex), 0f, 1f)));
            if (enter < 0.03f) {
                continue;
            }
            graphics.pose().pushPose();
            graphics.pose().translate(-(1f - enter) * (RAIL_WIDTH + 12), 0, 0); // glides in from behind the left edge
            PresetRow railRow = presetRows.get(rowIndex);
            railRow.render(graphics, mouseX, mouseY, partialTick);
            if (enter < 0.99f) {
                if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, railRow.x, railRow.screenY(), railRow.w, railRow.h, (1f - enter) * 0.9f);
        }
            }
            graphics.pose().popPose();
        }
        // one accent bar glides between class rows instead of each row snapping its own highlight
        for (PresetRow indicatorRow : presetRows) {
            if (!indicatorRow.isNew && indicatorRow.index == presetIndex) {
                float target = indicatorRow.screenY();
                railIndicatorY = railIndicatorY < 0 ? target : MWStyle.approach(railIndicatorY, target, 0.35f, MWStyle.frameDt());
                MWStyle.fillF(graphics, 10, Math.round(railIndicatorY) + 1, 12, Math.round(railIndicatorY) + indicatorRow.h - 1, MWStyle.fa(MWStyle.signal()));
            }
        }
        graphics.disableScissor();
        // Soft fade at the clip edge (top + bottom) when the rail is scrollable.
        boolean railScrolls = presetRows.size() * railStride > railClipBottom - railTop;
        if (railScrolls) {
            MWStyle.gradF(graphics, 10, railClipBottom - 10, 10 + RAIL_WIDTH, railClipBottom,
                    0x00000000, MWStyle.fa(0xCC000000));
            if (railScroll > 1) {
                MWStyle.gradF(graphics, 10, railTop, 10 + RAIL_WIDTH, railTop + 10,
                        MWStyle.fa(0xCC000000), 0x00000000);
            }
        }
        graphics.pose().popPose();
        if (railSlide > 0.02f) {
            // the handle that stays behind: a slim accent line + arrow, hover it to push the rail out again
            int handleBottom = height - 52;
            MWStyle.arrowRight(graphics, 3, railTop + (handleBottom - railTop) / 2f - 1, 3.5f,
                    MWStyle.withAlpha(MWStyle.accent(), 0.7f * railSlide));
            MWStyle.strF(graphics, font, "Tab", 2, railTop + (handleBottom - railTop) / 2 + 6,
                    MWStyle.withAlpha(MWStyle.TEXT_DIM, 0.8f * railSlide), false);
        }

        // When the class rail is pushed in, the WHOLE loadout panel follows it to the left edge (as in the reference: it
        // leaves empty space on the right instead of sitting where the rail used to be).
        int cx = contentX + contentShiftX;
        nameBox.setX(cx);
        MWStyle.text(graphics, font, MWStyle.tr("screen.mwloadout.class_name").toUpperCase(),
                cx, 34, MWStyle.TEXT_DIM);
        // the line under the class name draws on / retracts with the screen, like the title line at the top
        MWStyle.fillF(graphics, cx, 60, cx + Math.round(contentWidth * MWStyle.chromeOpen), 61, MWStyle.border());
        // the name box text follows the same fade (an EditBox draws its own text, so tint it here)
        int nameColor = MWStyle.fa(MWStyle.TEXT);
        nameBox.setTextColor(nameColor);
        nameBox.visible = (nameColor >>> 24) >= 8;

        updatePeek(mouseX, mouseY);
        final boolean peeking = peekPreset != null;
        final LoadoutPreset realWorking = working;
        final java.util.Map<String, String> realLastPerks = peeking ? new java.util.HashMap<>(lastPerks) : null;
        final java.util.List<String> realLastStreaks = lastStreaks;
        if (peeking) {
            working = peekPreset; // the cards read `working`; swapped back (with their change-detection memory) right after the loop
        }
        graphics.enableScissor(0, listTop, width, listBottom);
        graphics.pose().pushPose();
        graphics.pose().translate(contentShiftX, 0, 0);
        for (int cardIndex = 0; cardIndex < cards.size(); cardIndex++) {
            Card card = cards.get(cardIndex);
            boolean fromRight = card instanceof KillstreakCard;
            long cardNow = nowMs - cardCascadeStart;
            float inT = MWStyle.easeOutCubic(Mth.clamp((cardNow - (fromRight ? 300L : 80L + cardIndex * 50L)) / 340f, 0f, 1f));
            float outCard = MWStyle.easeOutCubic(Mth.clamp(outT * 1.2f - 0.05f * (cards.size() - cardIndex), 0f, 1f));
            float enter = Math.min(inT, 1f - outCard);
            if (enter < 0.02f) {
                continue;
            }
            card.intro = enter; // the thin frame draws on / retracts; content shows once it is mostly drawn
            graphics.pose().pushPose();
            graphics.pose().translate((fromRight ? 1f : -1f) * (1f - enter) * 14f + card.hover * 2f, 0, 0);
            // a peek is read-only: the cards get a mouse that is nowhere, so no hover glow / tooltips / pop animations
            card.render(graphics, peeking ? -10000 : mouseX - contentShiftX, peeking ? -10000 : mouseY, partialTick);
            // Content (text, icons, silhouettes) fades in/out with the card instead of popping at a threshold:
            // a dark veil over the card melts away as `enter` rises (and returns on close).
            float contentVis = Mth.clamp((enter - 0.15f) / 0.55f, 0f, 1f);
            if (contentVis < 0.99f) {
                int veilW = card instanceof SlotCard sc && sc.weapon ? card.w + 6 + GUNSMITH_WIDTH : card.w;
                if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, card.x, card.screenY(), veilW, card.h, (1f - contentVis * contentVis * (3f - 2f * contentVis)) * 0.92f);
        }
            }
            drawFlash(graphics, card.flashX >= 0 ? card.flashX : card.x, card.screenY(),
                    card.flashX >= 0 ? card.flashW : card.w, card.h, card.flashAt);
            graphics.pose().popPose();
        }
        // a thin scan line sweeps down the column as the menu opens
        float sweep = Mth.clamp((nowMs - cardCascadeStart) / 520f, 0f, 1f);
        if (sweep < 1f && outT == 0f) {
            int sweepY = listTop + (int) ((listBottom - listTop) * MWStyle.easeOutCubic(sweep));
            MWStyle.fillF(graphics, contentX - 6, sweepY, contentX + contentWidth + GUNSMITH_WIDTH + 8, sweepY + 1,
                    MWStyle.withAlpha(MWStyle.accent(), 0.55f * (1f - sweep)));
        }
        graphics.pose().popPose();
        graphics.disableScissor();
        if (nameBox != null && peeking != nameBoxHiddenByPeek) {
            nameBox.visible = !peeking; // the typed-name box belongs to the REAL class; under a peek it would sit on top of the other class
            nameBoxHiddenByPeek = peeking;
        }
        if (peeking) {
            working = realWorking;
            lastPerks.clear();
            lastPerks.putAll(realLastPerks);
            lastStreaks = realLastStreaks;
            String peekName = peekPreset.name == null || peekPreset.name.isBlank() ? "CLASS" : peekPreset.name.toUpperCase(java.util.Locale.ROOT);
            String tag = "PREVIEW  \u2022  " + peekName;
            int tagW = font.width(tag) + 12;
            int tagX = contentX + contentWidth - tagW;
            MWStyle.fillF(graphics, tagX, 32, tagX + tagW, 44, MWStyle.withAlpha(MWStyle.PANEL, 0.95f));
            MWStyle.fillF(graphics, tagX, 32, tagX + 2, 44, MWStyle.fa(MWStyle.signal()));
            MWStyle.strF(graphics, font, tag, tagX + 7, 35, MWStyle.accent(), false);
        }

        int overflow = cardContentHeight() - (listBottom - listTop);
        if (overflow > 0) {
            int trackHeight = listBottom - listTop;
            int barHeight = Math.max(16, trackHeight * trackHeight / cardContentHeight());
            int barY = listTop + (int) (cardScroll / overflow * (trackHeight - barHeight));
            MWStyle.fillF(graphics, width - 8, listTop, width - 6, listBottom, MWStyle.fa(MWStyle.PANEL_RAISED));
            MWStyle.fillF(graphics, width - 8, barY, width - 6, barY + barHeight, MWStyle.fa(MWStyle.border()));
            // Soft top/bottom fade on the card column so rows melt in/out instead of hard-cutting.
            if (cardScroll > 2) {
                MWStyle.gradF(graphics, cx, listTop, cx + contentWidth, listTop + 10, MWStyle.fa(0xCC000000), 0x00000000);
            }
            if (cardScroll < overflow - 2) {
                MWStyle.gradF(graphics, cx, listBottom - 10, cx + contentWidth, listBottom, 0x00000000, MWStyle.fa(0xCC000000));
            }
        }
        graphics.pose().pushPose();
        graphics.pose().translate(railShift, 0, 0);
        Rules levelRules = LoadoutRegistry.get().rules();
        if (levelRules.progressionEnabled) {
            int barY = height - 44;
            // The XP maths (level search + curve sums, up to 2000 iterations each with a growth curve) and the two strings only
            // change when xp / the curve rules change - recomputing them every frame was pure waste.
            if (xpCacheXp != profile.xp || xpCachePer != levelRules.xpPerLevel || xpCacheGrowth != levelRules.xpCurveGrowth
                    || xpCacheLevelLabel == null) {
                xpCacheXp = profile.xp;
                xpCachePer = levelRules.xpPerLevel;
                xpCacheGrowth = levelRules.xpCurveGrowth;
                int playerLevel = profile.level(levelRules.xpPerLevel, levelRules.xpCurveGrowth);
                xpCacheLevelLabel = Component.translatable("screen.mwloadout.level", playerLevel).getString();
                // curve-aware numbers (a raw `xp % xpPerLevel` stops matching the bar once xpCurveGrowth > 1).
                long levelFloor = dev.l41nx.mwloadout.data.XpCurve.xpForLevel(playerLevel, levelRules.xpPerLevel, levelRules.xpCurveGrowth);
                long levelNeed = dev.l41nx.mwloadout.data.XpCurve.xpForLevel(playerLevel + 1, levelRules.xpPerLevel, levelRules.xpCurveGrowth) - levelFloor;
                xpCacheText = (profile.xp - levelFloor) + " / " + levelNeed;
                xpCacheProgress = levelNeed <= 0 ? 1f : Math.max(0f, Math.min(1f, (profile.xp - levelFloor) / (float) levelNeed));
            }
            MWStyle.text(graphics, font, xpCacheLevelLabel, 10, barY - 10, MWStyle.accent());
            MWStyle.text(graphics, font, xpCacheText, 10 + RAIL_WIDTH - font.width(xpCacheText), barY - 10, MWStyle.TEXT_DIM);
            MWStyle.fillF(graphics, 10, barY, 10 + RAIL_WIDTH, barY + 3, MWStyle.fa(MWStyle.PANEL_HOVER));
            MWStyle.fillF(graphics, 10, barY, 10 + (int) (RAIL_WIDTH * xpCacheProgress), barY + 3,
                    MWStyle.fa(MWStyle.signal()));
        }

        graphics.pose().popPose();
        renderLockMessage(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (menuX >= 0) {
            // Pop-in from the click point instead of snapping into existence.
            long age = System.currentTimeMillis() - menuOpenedAt;
            float p = Math.min(1f, age / (float) MWStyle.ANIM_FAST);
            float eased = MWStyle.easeOutCubic(p);
            int itemHeight = 14;
            int menuHeight = MENU_OPS.length * itemHeight;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400); // above every widget: the footer EQUIP button used to show through a menu opened near the bottom
            graphics.pose().translate(menuX, menuY, 0);
            float s = 0.85f + 0.15f * eased;
            graphics.pose().scale(s, s, 1f);
            graphics.pose().translate(-menuX, -menuY, 0);
            MWStyle.fillF(graphics, menuX, menuY, menuX + 92, menuY + menuHeight, MWStyle.withAlpha(0xF5101216, eased));
            MWStyle.hairline(graphics, menuX, menuY, 92, menuHeight, MWStyle.withAlpha(MWStyle.borderActive(), eased));
            for (int i = 0; i < MENU_OPS.length; i++) {
                int itemY = menuY + i * itemHeight;
                boolean hovered = mouseX >= menuX && mouseX < menuX + 92 && mouseY >= itemY && mouseY < itemY + itemHeight;
                if (hovered) {
                    MWStyle.fillF(graphics, menuX + 1, itemY, menuX + 91, itemY + itemHeight, MWStyle.withAlpha(MWStyle.PANEL_HOVER, eased));
                }
                boolean danger = MENU_OPS[i].equals("delete");
                int textColor = danger ? MWStyle.NEGATIVE : hovered ? MWStyle.TEXT : MWStyle.TEXT_DIM;
                MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.op_" + MENU_OPS[i]).getString(),
                        menuX + 7, itemY + 3, MWStyle.withAlpha(textColor, eased), false);
            }
            graphics.pose().popPose();
        }
        transitionRender(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int railClipBottom = height - 52;
        if (railSlide < 0.5f && mouseX - railShift < 10 + RAIL_WIDTH && presetRows.size() * railStride > railClipBottom - 44) {
            int overflow = presetRows.size() * railStride - (railClipBottom - 44);
            railScrollTarget = Mth.clamp(railScrollTarget - delta * 24, 0, Math.max(0, overflow));
            return true;
        }
        if (mouseY >= listTop && mouseY < listBottom) {
            int overflow = Math.max(0, cardContentHeight() - (listBottom - listTop));
            cardScrollTarget = Mth.clamp(cardScrollTarget - delta * 28, 0, overflow);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void flashLockMessage(String msg) {
        this.lockMsg = msg;
        this.lockMsgAt = System.currentTimeMillis();
    }

    private static String versionTagCache;

    static String modVersionTag() {
        if (versionTagCache == null) {
            versionTagCache = net.minecraftforge.fml.ModList.get().getModContainerById(dev.l41nx.mwloadout.MWLoadoutMod.MOD_ID)
                    .map(container -> "V" + container.getModInfo().getVersion()).orElse("");
        }
        return versionTagCache;
    }

    private void renderLockMessage(GuiGraphics graphics) {
        if (lockMsg == null) {
            return;
        }
        long age = System.currentTimeMillis() - lockMsgAt;
        if (age > 2600) {
            lockMsg = null;
            return;
        }
        float a = age < 200 ? age / 200f : (age > 2200 ? 1f - (age - 2200f) / 400f : 1f);
        int alpha = (int) (Math.max(0f, a) * 255) & 0xFF;
        int tw = font.width(lockMsg);
        int bx = (width - tw) / 2;
        int by = height - 52;
        MWStyle.fillF(graphics, bx - 8, by - 4, bx + tw + 8, by + 12, MWStyle.withAlpha(0xFF0A0D12, a * 0.85f));
        MWStyle.hairline(graphics, bx - 8, by - 4, tw + 16, 16, MWStyle.withAlpha(MWStyle.accent(), a));
        MWStyle.strF(graphics, font, lockMsg, bx, by, (alpha << 24) | 0xFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        // Context menu interception (drawn topmost, so it eats clicks first).
        if (menuX >= 0) {
            int itemHeight = 14;
            if (mouseX >= menuX && mouseX < menuX + 92 && mouseY >= menuY && mouseY < menuY + MENU_OPS.length * itemHeight) {
                String op = MENU_OPS[(int) ((mouseY - menuY) / itemHeight)];
                if (op.equals("equip")) {
                    equipPreset(menuPreset);
                } else {
                    Net.CHANNEL.sendToServer(new Net.C2SPresetOp(op, menuPreset));
                }
                MWStyle.playClick();
            }
            menuX = -1;
            return true;
        }
        if (button == 1) {
            for (PresetRow row : presetRows) {
                if (!row.isNew && row.contains(mouseX, mouseY)) {
                    menuX = (int) Math.min(mouseX, width - 96);
                    menuY = (int) Math.min(mouseY, height - MENU_OPS.length * 14 - 4);
                    menuPreset = row.index;
                    menuOpenedAt = System.currentTimeMillis();
                    MWStyle.playTab();
                    return true;
                }
            }
        }
        if (button == 0) {
            for (PresetRow row : presetRows) {
                if (row.contains(mouseX, mouseY)) {
                    MWStyle.playClick();
                    long now = System.currentTimeMillis();
                    row.flashAt = now;
                    boolean doubleClick = row.index == lastClickedRow && now - lastClickAt < 400;
                    lastClickedRow = row.index;
                    lastClickAt = now;
                    railLatched = true;
                    if (!railPinned) {
                        railCollapsed = true; // class picked: push the rail in (hover the left edge / press Tab to bring it back)
                        railNeedsLeave = true;
                    }
                    if (row.isNew || presetIndex != row.index) {
                        // Single click just SELECTS the class (loads it for viewing/editing) —
                        // no more accidental deploys from browsing the rail. Flush any pending
                        // edit to the class we're leaving first.
                        autoSaveIfDirty();
                        presetIndex = row.index;
                        loadWorkingCopy();
                        rebuildLayout(false); // switching class — don't drag the old class's typed name along
                    } else if (!row.isNew && doubleClick) {
                        // Double-click the already-selected class = deploy it.
                        equipPreset(row.index);
                    }
                    return true;
                }
            }
            if (mouseY >= listTop && mouseY < listBottom) {
                for (Card card : cards) {
                    if (card.click(mouseX - contentShiftX, mouseY)) {
                        card.flashAt = System.currentTimeMillis();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- widgets

    private abstract class Card {
        final int x, y, w, h;
        float hover;
        float intro = 1f; // 0..1 open/close progress: frame draws on, content appears after ~60%
        boolean wasHovered;
        long flashAt;      // click flash (soft accent fill that fades out)
        int flashX = -1, flashW; // region to flash; defaults to the whole card

        Card(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        int screenY() {
            return y - (int) cardScroll;
        }

        boolean visible() {
            int sy = screenY();
            return sy + h > listTop && sy < listBottom;
        }

        boolean containsOnScreen(double mx, double my, int x, int width) {
            int sy = screenY();
            return mx >= x && mx < x + width && my >= sy && my < sy + h && visible();
        }

        void trackHover(boolean hovered, float partialTick) {
            if (hovered && !wasHovered) {
                MWStyle.playHover();
            }
            wasHovered = hovered;
            hover = MWStyle.easeHover(hover, hovered, partialTick);
        }

        abstract void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

        abstract boolean click(double mouseX, double mouseY);
    }

    private final class SlotCard extends Card {
        final SlotDefinition slot;
        final boolean weapon;
        final boolean overkill;
        final boolean underkill;
        float gunsmithHover;
        float dockOpen; // BUILD dock: 0 tucked behind the card, 1 pushed out (only while the card is hovered)

        SlotCard(SlotDefinition slot, boolean weapon, boolean overkill, boolean underkill, int x, int y, int w, int h) {
            super(x, y, w, h);
            this.slot = slot;
            this.weapon = weapon;
            this.overkill = overkill;
            this.underkill = underkill;
        }

        private CatalogEntry shownEntry() {
            if (slot.locked) {
                return slot.defaultEntry.isEmpty() ? null : LoadoutRegistry.get().entry(slot.defaultEntry);
            }
            LoadoutPreset.Selection selection = working.slots.get(slot.id);
            if (selection != null && !selection.entryId.isEmpty()) {
                return LoadoutRegistry.get().entry(selection.entryId);
            }
            return slot.defaultEntry.isEmpty() ? null : LoadoutRegistry.get().entry(slot.defaultEntry);
        }

        /** Same legality the server enforces — red REPLACE the instant perks change. */
        private boolean invalidUnderPerks(CatalogEntry entry) {
            if (entry == null || slot.locked) {
                return false;
            }
            if (slot.whitelist.contains(entry.id)) {
                return false;
            }
            List<String> effective = LoadoutApplier.effectiveCategories(slot, overkill, underkill);
            return !effective.contains(entry.category);
        }

        @Override
        void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            int sy = screenY();
            boolean interactive = !slot.locked;
            boolean cardHovered = interactive && containsOnScreen(mouseX, mouseY, x, w);
            trackHover(cardHovered, partialTick);

            CatalogEntry entry = shownEntry();
            boolean invalid = invalidUnderPerks(entry);
            MWStyle.thinTile(graphics, x, sy, w, h, hover, false, intro);
            if (intro < 0.12f) {
                return;
            }
            // Vertical rhythm: weapons keep a fixed top/bottom pad; equipment cards centre their two text lines.
            int textTop = weapon ? sy + 5 : sy + Math.max(5, (h - 19) / 2);
            MWStyle.strF(graphics, font, slot.displayName.toUpperCase(), x + 7, textTop,
                    slot.locked ? MWStyle.LOCKED : MWStyle.TEXT_DIM, false);

            String label = entry == null ? MWStyle.tr("screen.mwloadout.empty")
                    : invalid ? MWStyle.tr("screen.mwloadout.replace")
                    : DisplayNames.of(entry);
            int labelColor = entry == null ? MWStyle.TEXT_DIM : invalid ? MWStyle.DANGER : MWStyle.TEXT;

            if (weapon) {
                LoadoutPreset.Selection selection = working.slots.get(slot.id);
                boolean isGun = entry != null && entry.kind == CatalogEntry.Kind.GUN && !invalid;
                int slotCount = isGun ? TaczStats.attachmentSlotCount(entry.gameId) : 0;
                int mods = isGun ? Math.min(slotCount, TaczStats.installedAttachments(entry.gameId, selection == null ? null : selection.attachments)) : 0;
                int textW = Math.max(96, (int) (w * 0.41f)); // wide enough for "MARKSMAN RIFLES" without an ellipsis
                if (isGun) {
                    // the silhouette gets the whole right ~64% of the card, full card height
                    GunHud.render(graphics, entry, x + textW, sy + 3, w - textW - 6, h - 6);
                    // Slim 3-row card: row 1 = slot label + one gold square per INSTALLED attachment (no empty boxes, no n/max text),
                    // row 2 = weapon class, row 3 = weapon name.
                    if (TaczStats.hasAttachmentSupport(entry.gameId) && mods > 0) {
                        int dotsX = x + 7 + font.width(slot.displayName.toUpperCase()) + 8;
                        for (int i = 0; i < mods; i++) {
                            int sqx = dotsX + i * 7;
                            MWStyle.fillF(graphics, sqx, sy + 6, sqx + 5, sy + 11, MWStyle.signal());
                        }
                    }
                    // Weapon category (the selected gun's class), above its name.
                    if (!entry.category.isEmpty()) {
                        String cat = SlotPickerScreen.categoryDisplayName(entry.category).toUpperCase(java.util.Locale.ROOT);
                        MWStyle.strF(graphics, font, MWStyle.ellipsize(font, cat, textW - 6), x + 7, sy + h - 24,
                                MWStyle.TEXT_DIM, false);
                    }
                }
                MWStyle.scrollText(graphics, font, label, x + 7, sy + h - 13, textW - 10, labelColor, cardHovered);
                // GUNSMITH dock, MW19-style square beside the card.
                // guns whose pack has no attachment slots get NO gunsmith dock at all
                boolean hasGun = entry != null && entry.kind == CatalogEntry.Kind.GUN && !invalid && interactive
                        && TaczStats.hasAttachmentSupport(entry.gameId);
                // The BUILD dock only pushes out while the card (or the dock itself) is hovered, then tucks back in.
                boolean inDock = dockOpen > 0.05f && containsOnScreen(mouseX, mouseY, x + w, 6 + GUNSMITH_WIDTH);
                dockOpen = MWStyle.easeHover(dockOpen, hasGun && (cardHovered || inDock), partialTick);
                if (dockOpen > 0.02f) {
                    int dockW = Math.round(GUNSMITH_WIDTH * MWStyle.easeOutCubic(dockOpen));
                    boolean gunsmithHovered = hasGun && dockOpen > 0.6f && containsOnScreen(mouseX, mouseY, x + w + 6, GUNSMITH_WIDTH);
                    gunsmithHover = MWStyle.easeHover(gunsmithHover, gunsmithHovered, partialTick);
                    MWStyle.thinTile(graphics, x + w + 6, sy, dockW, h, gunsmithHover, false, intro);
                    if (dockW >= GUNSMITH_WIDTH - 6) {
                        MWStyle.centerF(graphics, font, MWStyle.tr("screen.mwloadout.gunsmith_short"),
                                x + w + 6 + GUNSMITH_WIDTH / 2, sy + h / 2 - 4, gunsmithHovered ? MWStyle.TEXT : MWStyle.TEXT_DIM);
                    }
                }
            } else {
                // name on the left under the small label, a thin divider, then the item icon (bigger)
                MWStyle.scrollText(graphics, font, label, x + 7, textTop + 11, w - 46, labelColor, cardHovered);
                MWStyle.fillF(graphics, x + w - 31, sy + 6, x + w - 30, sy + h - 6, MWStyle.border());
                if (entry != null && !invalid) {
                    ItemStack icon = IconCache.iconFor(entry);
                    if (!icon.isEmpty()) {
                        float iconScale = 1.25f * (1f + 0.08f * hover); // eases up a touch on hover
                        graphics.pose().pushPose();
                        graphics.pose().translate(x + w - 15.5f, sy + h / 2f, 0);
                        graphics.pose().scale(iconScale, iconScale, 1f);
                        graphics.pose().translate(-8, -8, 0);
                        graphics.renderItem(icon, 0, 0);
                        graphics.pose().popPose();
                    }
                }
                if (slot.locked) {
                    MWStyle.strF(graphics, font, "LOCKED", x + w - font.width("LOCKED") - 38, textTop + 11, MWStyle.LOCKED, false);
                }
            }
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            if (slot.locked) {
                return false;
            }
            CatalogEntry entry = shownEntry();
            if (weapon && entry != null && entry.kind == CatalogEntry.Kind.GUN && !invalidUnderPerks(entry)
                    && TaczStats.hasAttachmentSupport(entry.gameId)
                    && dockOpen > 0.6f
                    && containsOnScreen(mouseX, mouseY, x + w + 6, GUNSMITH_WIDTH)) {
                MWStyle.playClick();
                flashX = x + w + 6;
                flashW = GUNSMITH_WIDTH;
                LoadoutPreset.Selection selection = working.slots.computeIfAbsent(slot.id, key -> {
                    LoadoutPreset.Selection created = new LoadoutPreset.Selection();
                    created.entryId = entry.id;
                    return created;
                });
                transitionTo(new AttachmentEditorScreen(LoadoutScreen.this, selection, entry));
                return true;
            }
            if (containsOnScreen(mouseX, mouseY, x, w)) {
                MWStyle.playClick();
                transitionTo(new SlotPickerScreen(LoadoutScreen.this, working, slot));
                return true;
            }
            return false;
        }
    }

    private final class PerkCard extends Card {
        final List<Rules.PerkSlot> perkSlots;

        PerkCard(List<Rules.PerkSlot> perkSlots, int x, int y, int w, int h) {
            super(x, y, w, h);
            this.perkSlots = perkSlots;
        }

        private int cellW() {
            return Math.max(20, (w - 10) / Math.max(1, perkSlots.size()));
        }

        private int boxX(int index) {
            return x + 5 + index * cellW();
        }

        @Override
        void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            int sy = screenY();
            MWStyle.thinTile(graphics, x, sy, w, h, 0f, false, intro);
            if (intro < 0.12f) {
                return;
            }
            MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.perks").toUpperCase(),
                    x + 7, sy + 6, MWStyle.TEXT_DIM, false);
            int cellTop = sy + 17;
            int cellH = h - 17 - 6;
            int cw = cellW();
            long now = System.currentTimeMillis();
            for (int i = 0; i < perkSlots.size(); i++) {
                String slotId = perkSlots.get(i).id;
                int bx = boxX(i);
                String perkId = working.perks.get(slotId);
                // a changed pick bounces + flashes once (not on the very first frame / class switch)
                String before = lastPerks.get(slotId);
                if (lastPerks.containsKey(slotId) && !java.util.Objects.equals(before, perkId)) {
                    perkPopAt.put(slotId, now);
                }
                lastPerks.put(slotId, perkId);
                PerkDefinition perk = perkId == null ? null : LoadoutRegistry.get().perk(perkId);
                boolean hovered = mouseX >= bx && mouseX < bx + cw && mouseY >= cellTop && mouseY < cellTop + cellH && visible();
                if (hovered) {
                    MWStyle.fillF(graphics, bx, cellTop, bx + cw, cellTop + cellH, MWStyle.withAlpha(MWStyle.accent(), 0.10f));
                    MWStyle.fillF(graphics, bx, cellTop + cellH - 1, bx + cw, cellTop + cellH, MWStyle.signal());
                }
                MWStyle.sweepFill(graphics, bx, cellTop, cw, cellH, perkPopAt.getOrDefault(slotId, 0L), 380, MWStyle.accent());
                if (i > 0) {
                    MWStyle.fillF(graphics, bx, cellTop + 3, bx + 1, cellTop + cellH - 3, MWStyle.border()); // divider between perks
                }
                if (perk != null && !perk.icon.isEmpty()) {
                    // fills the cell (the old 13-22px was tiny): as tall as the cell allows, never wider than it, capped at 32
                    int iconSize = Math.max(12, Math.min(Math.min(cellH - 2, cw - 8), 32));
                    PerkIcons.draw(graphics, perk.icon, bx + cw / 2 - iconSize / 2, cellTop + (cellH - iconSize) / 2, iconSize);
                } else {
                    MWStyle.centerF(graphics, font, "+", bx + cw / 2, cellTop + cellH / 2 - 4, MWStyle.TEXT_DIM);
                }
            }
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            int sy = screenY();
            int cellTop = sy + 17;
            int cellH = h - 17 - 6;
            for (int i = 0; i < perkSlots.size(); i++) {
                int bx = boxX(i);
                if (mouseX >= bx && mouseX < bx + cellW() && mouseY >= cellTop && mouseY < cellTop + cellH) {
                    MWStyle.playClick();
                    transitionTo(new PerkPickerScreen(LoadoutScreen.this, working, perkSlots.get(i)));
                    return true;
                }
            }
            return false;
        }
    }

    /** The killstreak panel (MW2022 reference): the class's equipped killstreaks as a vertical list, highest kill
     *  cost on top, each an icon with its cost underneath. Click anywhere to open the selector. LOCKED while
     *  Specialist is active (killstreaks and Specialist are mutually exclusive). */
    private final class KillstreakCard extends Card {
        KillstreakCard(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        private boolean specialistOn() {
            dev.l41nx.mwloadout.data.PlayerProfile p = ClientCache.profile();
            return p != null && p.specialistActive;
        }

        @Override
        void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            int sy = screenY();
            boolean locked = specialistOn();
            boolean hovered = !locked && mouseX >= x && mouseX < x + w && mouseY >= sy && mouseY < sy + h && visible();
            Rules rules = LoadoutRegistry.get().rules();
            MWStyle.thinTile(graphics, x, sy, w, h, hovered ? 1f : 0f, false, intro);
            if (intro < 0.12f) {
                return;
            }
            String title = MWStyle.ellipsize(font, MWStyle.tr("screen.mwloadout.killstreak_btn").toUpperCase(), w - 8);
            MWStyle.strF(graphics, font, title, x + w / 2 - font.width(title) / 2, sy + 6, MWStyle.TEXT_DIM, false);
            if (locked) {
                MWStyle.centerF(graphics, font, MWStyle.tr("screen.mwloadout.ks_locked"),
                        x + w / 2, sy + h / 2 - 4, MWStyle.LOCKED);
                return;
            }
            java.util.List<String> chosen = cachedStreaks(rules);
            long now = System.currentTimeMillis();
            if (lastStreaks != null) {
                for (String id : chosen) {
                    if (!lastStreaks.contains(id)) {
                        streakPopAt.put(id, now); // newly equipped: bounce + flash
                    }
                }
            }
            lastStreaks = new java.util.ArrayList<>(chosen);
            if (chosen.isEmpty()) {
                MWStyle.centerF(graphics, font, MWStyle.tr("screen.mwloadout.ks_open"),
                        x + w / 2, sy + h / 2 - 4, hovered ? MWStyle.accent() : MWStyle.TEXT_DIM);
                return;
            }
            int entryH = Math.max(28, (h - 20) / dev.l41nx.mwloadout.data.StreakPool.MAX_SELECTED);
            String tooltipName = null;
            for (int k = 0; k < chosen.size(); k++) {
                String id = chosen.get(chosen.size() - 1 - k); // highest cost first
                int ey = sy + 19 + k * entryH;
                int index = dev.l41nx.mwloadout.data.StreakPool.indexOf(rules, id);
                long popTime = streakPopAt.getOrDefault(id, 0L);
                MWStyle.sweepFill(graphics, x + 3, ey, w - 6, entryH - 2, popTime, 380, MWStyle.accent());
                int iconTop = ey + Math.max(1, (entryH - 36) / 2);
                ItemStack icon = IconCache.anyIcon(id);
                if (!icon.isEmpty()) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(x + w / 2f, iconTop + 12, 0);
                    graphics.pose().scale(1.5f, 1.5f, 1f);
                    graphics.pose().translate(-8, -8, 0);
                    graphics.renderItem(icon, 0, 0);
                    graphics.pose().popPose();
                }
                if (index >= 0) {
                    String badge = "\u25C6 " + rules.streakRewards.get(index).kills;
                    MWStyle.strF(graphics, font, badge, x + w / 2 - font.width(badge) / 2, iconTop + 26, MWStyle.TEXT, false);
                }
                if (mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= ey && mouseY < ey + entryH && visible()) {
                    tooltipName = DisplayNames.ofAny(id);
                }
            }
            if (tooltipName != null) {
                MwScreen.deferTooltip(font, Component.literal(tooltipName), mouseX, mouseY);
            }
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            int sy = screenY();
            if (mouseX >= x && mouseX < x + w && mouseY >= sy && mouseY < sy + h) {
                if (specialistOn()) {
                    flashLockMessage(MWStyle.tr("screen.mwloadout.ks_locked_msg"));
                    MWStyle.playTab();
                } else {
                    MWStyle.playClick();
                    transitionTo(new KillstreakSelectScreen(LoadoutScreen.this, working));
                }
                return true;
            }
            return false;
        }
    }

    private final class PresetRow {
        final int index, x, y, w, h;
        final boolean isNew;
        float hover;
        boolean wasHovered;
        long flashAt;

        PresetRow(int index, boolean isNew, int x, int y, int w, int h) {
            this.index = index;
            this.isNew = isNew;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        int screenY() {
            return y - (int) railScroll;
        }

        boolean contains(double mx, double my) {
            if (railSlide > 0.5f) {
                return false; // pushed in: only the handle is left
            }
            mx -= railShift;
            int sy = screenY();
            return mx >= x && mx < x + w && my >= sy && my < sy + h && sy >= 40 && sy + h <= listBottom + 6;
        }

        void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int sy = screenY();
            if (sy + h < 40 || sy > listBottom) {
                return;
            }
            boolean hovered = contains(mouseX, mouseY);
            if (hovered && !wasHovered) {
                MWStyle.playHover();
            }
            wasHovered = hovered;
            hover = MWStyle.easeHover(hover, hovered, partialTick);
            boolean selected = !isNew && index == presetIndex;
            // flat rows like the MW class list: no box - a hover wash and a hairline divider; the selected row gets the glow
            if (hover > 0.01f) {
                MWStyle.wash(graphics, x, sy, w, h, MWStyle.withAlpha(MWStyle.accent(), 0.12f * hover));
            }
            if (!selected) { // hairline divider between flat rows (the selected plate has its own edge)
                MWStyle.fillF(graphics, x + 2, sy + h, x + w - 2, sy + h + 1, MWStyle.withAlpha(MWStyle.border(), 0.45f));
            }
            if (selected) {
                // Clear "this one is selected" highlight: accent wash, bright edge, a soft pulsing outer glow and a bar.
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() * 0.004);
                float glow = 0.72f + 0.28f * pulse;
                MWStyle.wash(graphics, x, sy, w, h, MWStyle.withAlpha(MWStyle.accent(), 0.16f * glow));
                MWStyle.glowOutline(graphics, x, sy, w, h, MWStyle.signal(), glow); // angular: halo + edge + strip on one silhouette
                if (!MWStyle.angular()) {
                    MWStyle.fillF(graphics, x, sy, x + 2, sy + h, MWStyle.signal());
                }
            }
            drawFlash(graphics, x, sy, w, h, flashAt);
            int textY = sy + (h - 8) / 2;
            if (isNew) {
                MWStyle.strF(graphics, font, "+ " + MWStyle.tr("screen.mwloadout.new_class").toUpperCase(),
                        x + 6, textY, MWStyle.TEXT_DIM, false);
            } else {
                String label = index < profile.presets.size() ? profile.presets.get(index).name : "CLASS " + (index + 1);
                MWStyle.strF(graphics, font, String.format("%02d", index + 1), x + 6, textY,
                        selected ? MWStyle.accent() : MWStyle.TEXT_DIM, false);
                MWStyle.strF(graphics, font, font.plainSubstrByWidth(label.toUpperCase(), w - 22 - 20), x + 26, textY,
                        selected ? MWStyle.accent() : MWStyle.TEXT, false);
                if (index == profile.activeIndex) {
                    MWStyle.arrowRight(graphics, x + w - 12, sy + h / 2f, 3.5f, MWStyle.accent()); // the active class: smooth pointer, not a glyph
                }
            }
        }
    }
    /** Soft accent flash over a clicked card / row: strong for an instant, fading out in ~280ms. */
    private void drawFlash(GuiGraphics graphics, int fx, int fy, int fw, int fh, long flashAt) {
        if (flashAt <= 0) {
            return;
        }
        float t = (System.currentTimeMillis() - flashAt) / 280f;
        if (t >= 1f) {
            return;
        }
        float k = (1f - t) * (1f - t);
        MWStyle.wash(graphics, fx, fy, fw, fh, MWStyle.withAlpha(MWStyle.accent(), 0.36f * k));
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
        float out = Math.min(1f, closingAt > 0 ? (System.currentTimeMillis() - closingAt) / (float) MWStyle.closeMillis() : 0f);
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Press the loadout keybind again (default L) to close — toggle. Ignored while typing
        // in the name box so 'l' can be part of a class name.
        if (!nameBox.isFocused()
                && ClientEvents.OPEN_LOADOUT.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == 256 && menuX >= 0) { // ESC closes the right-click menu first, the screen second
            menuX = -1;
            return true;
        }
        if (!nameBox.isFocused() && closingAt <= 0 && menuX < 0) {
            if (keyCode == 258) { // Tab: pin the class rail open / shut
                railCollapsed = !railCollapsed;
                railPinned = !railCollapsed;
                railLatched = true;
                railNeedsLeave = false;
                MWStyle.playTab();
                return true;
            }
            if ((keyCode == 264 || keyCode == 265) && profile != null && !profile.presets.isEmpty()) { // Down / Up: next / previous class
                int count = profile.presets.size();
                int next = presetIndex < 0 ? 0 : Mth.clamp(presetIndex + (keyCode == 264 ? 1 : -1), 0, count - 1);
                if (next != presetIndex) {
                    autoSaveIfDirty();
                    presetIndex = next;
                    loadWorkingCopy();
                    rebuildLayout(false);
                    MWStyle.playTab();
                }
                return true;
            }
            if ((keyCode == 257 || keyCode == 335) && presetIndex >= 0) { // Enter: equip the class on screen
                equipPreset(presetIndex);
                return true;
            }
        }
        if (keyCode == 261 && presetIndex >= 0 && !nameBox.isFocused()) { // GLFW_KEY_DELETE
            Net.CHANNEL.sendToServer(new Net.C2SPresetOp("delete", presetIndex));
            MWStyle.playClick();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Developer overlay (client config debugMode): surfaces the live equip state so testing
     *  the difficulty/cooldown/combat rules doesn't require guesswork. Reads only client-cached
     *  values — no extra packets. */
    private void renderDebugOverlay(GuiGraphics graphics) {
        boolean debug;
        try {
            debug = dev.l41nx.mwloadout.client.ClientCfg.DEBUG_MODE.get();
        } catch (Exception e) {
            return;
        }
        if (!debug) {
            return;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("\u00A7e[MWLOADOUT DEBUG]");
        lines.add("equip allowed: " + (ClientCache.editable() ? "\u00A7aYES" : "\u00A7cNO"));
        String reason = ClientCache.editReasonKey();
        lines.add("reason: " + (reason == null || reason.isEmpty() ? "-" : reason));
        lines.add("timer: " + ClientCache.editSecondsLive() + "s");
        lines.add("index: " + presetIndex + " / dirtyIdx " + lastSavedIndex);
        lines.add("presets: " + (profile != null ? profile.presets.size() : 0));
        int y = 44;
        for (String line : lines) {
            MWStyle.strF(graphics, font, line, 10, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        autoSaveIfDirty(); // persist a rename / last edit before the screen tears down
        transitionTo(null);
    }


    private static boolean isInFiringRangeClient() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null && level.dimension().location().equals(dev.l41nx.mwloadout.server.FiringRange.DIM_ID);
    }
}
