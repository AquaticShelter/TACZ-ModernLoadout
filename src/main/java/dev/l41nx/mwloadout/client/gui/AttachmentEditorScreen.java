package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientCache;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.util.Json;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MW19 gunsmith, full form: the gun's REAL 3D model rotating center-stage with every selected
 * attachment mounted (GunPreview — TACZ's own smith-table renderer), refit-style stat BARS that
 * preview the hovered attachment's effect as green/red delta segments (the exact drawing pattern
 * of TACZ's GunPropertyDiagrams: background, base fill, delta overlay), plus:
 * type rail LEFT (only the types this gun's pack actually declares installable —
 * TaczStats.allowedTypes), candidate list CENTER (only attachments passing TACZ's own
 * AllowAttachmentTagMatcher for THIS gun — no more every-attachment-on-every-gun), stat panel
 * RIGHT showing the hovered/selected attachment's modifiers with good/bad coloring, plus the
 * gun's own base stats pinned at the panel top so choices read against real numbers.
 */
public final class AttachmentEditorScreen extends MwScreen {

    private static final int RAIL_WIDTH = 108;
    private static final int STATS_WIDTH = 220;
    private static final int ROW_HEIGHT = 26;

    private final Screen parent;
    private final LoadoutPreset.Selection selection;
    private final CatalogEntry gunEntry;
    private long openedAt = System.currentTimeMillis();
    // Screen transition state: transitionTo() arms a 120ms fade-out veil, then the real
    // setScreen fires; opening plays the veil in reverse. Input is blocked while closing.
    private net.minecraft.client.gui.screens.Screen pendingScreen;
    private boolean pendingClose;
    private long closingAt;
    /** MW19 gunsmith memory: this gun's 4 build slots, edited live. Whichever slot is active
     *  when DONE is pressed becomes the preset selection AND the remembered default for the next
     *  time this gun is picked anywhere. */
    private PlayerProfile.GunBuilds builds;
    private final GunPreview preview = new GunPreview();
    private float[] cachedBars = new float[TaczStats.BAR_COUNT];
    private float[] cachedRawStats = new float[TaczStats.BAR_COUNT];
    private float[] cachedHoverRaw;
    private final AdvancedStatsPanel advanced = new AdvancedStatsPanel();
    private float[] baseRaw; // bare-gun raw numbers (stock reference + relevance of optional bars)
    private int cachedBarsKey = -1;
    private float[] cachedHoverBars;
    private float[] baseBars; // bare-gun stats, the fixed reference tick on every bar
    // smoothed, per-frame-eased copies of cachedBars/cachedHoverBars so the bars visibly
    // animate toward a new build/hover value instead of snapping — seeded lazily on first draw.
    private float[] animBars;
    private float[] animHoverBars;
    private boolean cinematic;
    // MW19 hotspot layout (cinematic mode) — attachment types render as boxes ringing the
    // weapon instead of an always-open vertical list; clicking one opens the (unchanged) candidate
    // list as a centered popup for just that type, closes on an outside click or picking a result.
    // Zone rects are recomputed each render() (screen size can change) and reused by mouseClicked.
    private final List<int[]> hotspotZones = new ArrayList<>(); // {x, y, w, h} per zone slot
    private boolean listOpen;
    private float selectAnim; // 0 = hub (cards around the gun), 1 = select view (list left, big title right)
    private String compareFor;
    private float[] compareCurrent;
    private float[] compareOther;
    private int baseMagSize;
    private int cachedMagSize;   // magazine of the ACTIVE build (Extended Mag included), refreshed with the stat cache
    private int cachedReserve;   // reserve the server hands out for that magazine (capacity x multiplier); -1 = unknown
    private static final int STATS_X = 14, STATS_W = 276, STATS_H = 92;
    private int cachedHoverKey = -1;
    private long limitTipUntil;
    private long levelLockTipUntil;
    private String levelLockTipText = "";
    private long savedFlashUntil;
    private long listFlashStart;
    // Preview hit-box (recorded at render) for mouse drag-rotate.
    private int previewX, previewY, previewW, previewH;

    private final List<AttachmentType> types = new ArrayList<>();
    // which of the 8 hotspot zones each entry in `types` (same index) renders in — assigned
    // once from computeZoneAssignment() by matching each type's name against ZONE_HINTS, so
    // MUZZLE lands where a muzzle visually belongs (front/left of the model) instead of wherever
    // TACZ happened to list it. Falls back to sequential filling for anything unmatched.
    private final List<Integer> typeZone = new ArrayList<>();
    private AttachmentType activeType;
    private EditBox searchBox;
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<TaczStats.Line> gunStats = new ArrayList<>();
    private double scroll;
    private double scrollTarget;
    private long lastFrameMillis = System.currentTimeMillis();
    private int listTop, listBottom, listX, listRight;
    private int hoveredRow = -1;
    private int lastHoverSoundRow = -1;
    // Blueprint switcher (MW2022-style): compact dropdown listing this gun's platform + all its
    // sibling forms, opened from the Blueprints button next to Save Build.
    private final List<CatalogEntry> blueprintOptions = new ArrayList<>();
    private boolean blueprintPanelOpen;
    // Animated open progress (0..1), eased the same way every other hoverable/toggleable element
    // in this UI is (MWStyle.easeHover pattern) — fades/expands the panel in instead of an
    // instant pop, updated once per render() call.
    private float blueprintPanelAnim;
    private int blueprintButtonX, blueprintButtonY, blueprintButtonW;
    private int addBuildTabX = -1, addBuildTabW;
    private int blueprintScroll;
    private float blueprintScrollAnim; // eased visual position of the strip (cards), chases blueprintScroll
    private static boolean REOPEN_BLUEPRINT_PANEL;
    private static boolean SKIP_OPEN_ANIMATION; // switching blueprint platform: the new screen appears instantly, no fade
    private boolean buildDropdownOpen;   // cinematic: the builds live in ONE dropdown button instead of a row of tabs
    private boolean layoutMuzzleLeft = true;
    private boolean buildContextMenuOpen;
    private int buildContextMenuIndex = -1;
    private int buildContextMenuX, buildContextMenuY;
    // Fire mode (SEMI/BURST/AUTO): loaded from the same attachments map every other choice for
    // this build lives in (see TaczStackFactory.FIRE_MODE_KEY doc for why no schema change was
    // needed). Only populated/shown when the gun actually offers more than one mode to pick from.
    private java.util.List<com.tacz.guns.api.item.gun.FireMode> availableFireModes = java.util.List.of();
    private com.tacz.guns.api.item.gun.FireMode currentFireMode;
    private Button fireModeButton;
    private Button blueprintButton;
    private Button flipButton;
    /** PREVIEW: hides every HUD element until a key is pressed; the model can then be dragged / zoomed freely. */
    private Button previewButton;
    private boolean previewMode;
    private long previewModeAt;
    /** 0..1 animation of the PREVIEW transition (UI fades + slides away, the gun grows to fill the window; reversed on exit). */
    private float previewK;
    private long previewKAt;
    /** Smoothed x of each utility button so the row re-packs with a slide when one of them appears / disappears. */
    private final java.util.IdentityHashMap<Button, Float> utilityRowX = new java.util.IdentityHashMap<>();
    /** Extra header row (px) of the select popup that holds the RISER button: 0 when the slot has no risers. */
    private int riserRow;
    /** TACZ 1.2.0 optic riser picker (hidden on older TACZ or when the slot has no risers). */
    private Button riserButton;
    private java.util.List<net.minecraft.resources.ResourceLocation> riserOptions = java.util.List.of();
    private String riserKey = "";
    private int saveButtonX;

    public AttachmentEditorScreen(Screen parent, LoadoutPreset.Selection selection, CatalogEntry gunEntry) {
        super(Component.literal(gunEntry.displayName));
        this.parent = parent;
        this.selection = selection;
        this.gunEntry = gunEntry;
        // Works whether gunEntry IS the platform (hasBlueprint=true) or is one of its blueprint
        // children — either way the switcher needs to reach every sibling form, not just
        // whichever one happens to be open right now. blueprintIds holds raw TACZ gameIds, so
        // this resolves through entryByGameId (guns auto-seed their own catalog entry from every
        // loaded addon pack, so any installed gun's gameId already has one to find).
        dev.l41nx.mwloadout.registry.LoadoutRegistry registry = dev.l41nx.mwloadout.registry.LoadoutRegistry.get();
        CatalogEntry platform = gunEntry.hasBlueprint ? gunEntry : registry.blueprintOwnerByGameId(gunEntry.gameId);
        if (platform != null) {
            blueprintOptions.add(platform);
            for (String blueprintGameId : platform.blueprintIds) {
                CatalogEntry child = registry.entryByGameId(blueprintGameId);
                if (child != null && child.enabled) {
                    blueprintOptions.add(child);
                }
            }
        }
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
        openedAt = SKIP_OPEN_ANIMATION ? 0L : System.currentTimeMillis(); // 0 = long ago = fully open
        SKIP_OPEN_ANIMATION = false;
        if (builds == null) {
            PlayerProfile profile = ClientCache.profile();
            PlayerProfile.GunBuilds stored = profile != null ? profile.buildsFor(gunEntry.id) : new PlayerProfile.GunBuilds();
            builds = PlayerProfile.GunBuilds.fromJson(stored.toJson()); // deep copy - edits are local until DONE
            // The preset's current attachments seed the active build (covers legacy presets
            // saved before build memory existed).
            if (!selection.attachments.isEmpty()) {
                builds.activeBuild().clear();
                builds.activeBuild().putAll(selection.attachments);
            }
        }
        if (types.isEmpty()) {
            Set<AttachmentType> allowed = TaczStats.allowedTypes(gunEntry.gameId);
            if (allowed.isEmpty()) {
                for (AttachmentType type : AttachmentType.values()) {
                    if (type != AttachmentType.NONE) {
                        types.add(type);
                    }
                }
            } else {
                types.addAll(allowed);
            }
            activeType = types.get(0);
            gunStats.addAll(TaczStats.gunStatsFor(gunEntry.gameId, builds.activeBuild()));
            computeZoneAssignment();
        }

        cinematic = dev.l41nx.mwloadout.client.ClientCfg.cinematicLayout();
        if (cinematic) {
            computeHotspotZones(); // populate before the first possible click, not just first render()
            // no permanent list column anymore — listX/Right/Top/Bottom
            // only mean anything while a hotspot box's popup is open (see openTypePopup(), which
            // sets them fresh every time). These starting values are just harmless placeholders
            // until the first click; renderStatsPanel's weapon-stage math no longer depends on them.
            listX = width / 2 - 150;
            listRight = width / 2 + 150;
            listTop = height / 2 - 150;
            listBottom = height / 2 + 150;
        } else {
            listX = 10 + RAIL_WIDTH + 8;
            listRight = width - STATS_WIDTH - 18;
            listTop = 76;
            listBottom = height - 40;
        }

        searchBox = new EditBox(font, listX, 38, Math.max(80, listRight - listX), 14, Component.empty());
        searchBox.setBordered(false);
        searchBox.setTextColor(MWStyle.TEXT);
        searchBox.setHint(Component.translatable("screen.mwloadout.search"));
        searchBox.setResponder(text -> rebuild());
        addRenderableWidget(searchBox);
        listOpen = !cinematic; // non-cinematic: list is always on, exactly as before
        searchBox.setVisible(listOpen);

        Component doneLabel = Component.translatable("screen.mwloadout.done");
        Component clearLabel = Component.translatable("screen.mwloadout.clear_build");
        Component saveLabel = Component.translatable("screen.mwloadout.save_build");
        int doneW = Math.max(54, font.width(doneLabel) + 20);
        int clearW = Math.max(54, font.width(clearLabel) + 20);
        int saveW = Math.max(54, font.width(saveLabel) + 20);
        int footerY = height - 26;
        int doneX = width - 10 - doneW;
        int clearX = doneX - 4 - clearW;
        int saveX = clearX - 4 - saveW;
        saveButtonX = saveX;
        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(doneX, footerY, doneW, 18,
                doneLabel, button -> confirmOrBack())).accent());
        // CLEAR: strip every attachment off the active build.
        addRenderableWidget(MWStyle.flatButton(clearX, footerY, clearW, 18,
                clearLabel, button -> {
                    builds.activeBuild().clear();
                    markBuildDirty();
                    MWStyle.playClick();
                }));
        // SAVE BUILD: persist every build slot (default 4, up to GunBuilds.MAX_SLOTS if grown)
        // WITHOUT leaving - flash confirms.
        addRenderableWidget(MWStyle.flatButton(saveX, footerY, saveW, 18,
                saveLabel, button -> {
                    Net.CHANNEL.sendToServer(new Net.C2SSaveGunBuild(gunEntry.id, builds.active,
                            Json.GSON.toJson(builds.toJson())));
                    savedFlashUntil = System.currentTimeMillis() + 1400;
                }));
        // BLUEPRINTS (MW2022-style platform switcher) and FIRE MODE (SEMI/BURST/AUTO cycle):
        // non-cinematic docks them bottom-left under the type rail. Cinematic has no rail there
        // anymore — that corner is the stat-bar panel now — so they move to top-left instead,
        // clear of every hotspot zone and the stat panel both.
        int utilityX = 10;
        int utilityBtnH = cinematic ? 16 : 20;
        int utilityW = cinematic ? font.width(Component.translatable("screen.mwloadout.blueprints")) + 16 : RAIL_WIDTH;
        int modeW = cinematic ? font.width("MODE: BURST") + 16 : RAIL_WIDTH;
        int utilityY = cinematic ? 68 : height - 28;
        int utilityStep = cinematic ? 0 : -22; // cinematic: BLUEPRINTS / MODE / FLIP sit in one compact row; non-cinematic stacks upward
        int utilityGap = 6; // the buttons used to touch
        int utilityStepX = cinematic ? utilityW + utilityGap : 0;
        if (blueprintOptions.size() > 1) {
            blueprintButtonW = utilityW;
            blueprintButtonX = utilityX;
            blueprintButtonY = utilityY;
            blueprintButton = addRenderableWidget(MWStyle.flatButton(blueprintButtonX, blueprintButtonY, blueprintButtonW, utilityBtnH,
                    Component.translatable("screen.mwloadout.blueprints"), button -> {
                        blueprintPanelOpen = !blueprintPanelOpen;
                        scrollBlueprintToCurrent();
                        MWStyle.playClick();
                    }));
            // Coming back from switching platforms inside the carousel: stay open so browsing
            // several forms in a row doesn't need the BLUEPRINTS button re-clicked every time.
            if (REOPEN_BLUEPRINT_PANEL) {
                REOPEN_BLUEPRINT_PANEL = false;
                blueprintPanelOpen = true;
                scrollBlueprintToCurrent();
            }
        } else {
            REOPEN_BLUEPRINT_PANEL = false; // a gun with no siblings has no carousel to reopen
        }
        try {
            var indexOpt = TimelessAPI.getCommonGunIndex(new net.minecraft.resources.ResourceLocation(gunEntry.gameId));
            availableFireModes = indexOpt.map(index -> index.getGunData().getFireModeSet()).orElse(java.util.List.of());
            baseMagSize = indexOpt.map(index -> index.getGunData().getAmmoAmount()).orElse(0);
        } catch (Exception e) {
            availableFireModes = java.util.List.of();
        }
        if (availableFireModes.size() > 1) {
            currentFireMode = availableFireModes.get(0);
            String saved = builds.activeBuild().get(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY);
            if (saved != null) {
                try {
                    var requested = com.tacz.guns.api.item.gun.FireMode.valueOf(saved);
                    if (availableFireModes.contains(requested)) {
                        currentFireMode = requested;
                    }
                } catch (IllegalArgumentException ignored) {
                    dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentEditorScreen:301", ignored);
                }
            }
            int fireModeY = blueprintOptions.size() > 1 ? utilityY + utilityStep : utilityY;
            int fireModeX = blueprintOptions.size() > 1 ? utilityX + utilityStepX : utilityX;
            fireModeButton = MWStyle.flatButton(fireModeX, fireModeY, modeW, utilityBtnH,
                    Component.literal("MODE: " + currentFireMode.name()), button -> {
                        int next = (availableFireModes.indexOf(currentFireMode) + 1) % availableFireModes.size();
                        currentFireMode = availableFireModes.get(next);
                        selection.attachments.put(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY,
                                currentFireMode.name());
                        builds.activeBuild().put(dev.l41nx.mwloadout.server.TaczStackFactory.FIRE_MODE_KEY,
                                currentFireMode.name());
                        fireModeButton.setMessage(Component.literal("MODE: " + currentFireMode.name()));
                        markBuildDirty();
                        MWStyle.playClick();
                    });
            addRenderableWidget(fireModeButton);
        }
        if (cinematic) {
            // FLIP: which side the muzzle faces (and so which column the front attachments use). Remembered.
            int flipX = utilityX + (blueprintOptions.size() > 1 ? utilityStepX : 0) + (availableFireModes.size() > 1 ? modeW + utilityGap : 0);
            flipButton = addRenderableWidget(MWStyle.flatButton(flipX, utilityY, font.width("FLIP") + 16, utilityBtnH,
                    Component.literal("FLIP"), button -> {
                        muzzleLeft = !muzzleLeft;
                        MWStyle.playClick();
                    }));
        }
        // PREVIEW joins the utility row (cinematic). Its x is packed every frame (layoutUtilityRow), so it sits right next to whatever
        // button is visible before it - no gap where BLUEPRINTS / MODE / FLIP are absent or hidden.
        if (cinematic) {
            previewButton = addRenderableWidget(MWStyle.flatButton(utilityX, utilityY, font.width(MWStyle.tr("screen.mwloadout.preview")) + 16,
                    utilityBtnH, Component.literal(MWStyle.tr("screen.mwloadout.preview")), button -> enterPreviewMode()));
            riserButton = addRenderableWidget(MWStyle.flatButton(0, 0, 100, 16, Component.literal("RISER"), button -> cycleRiser()));
            riserButton.visible = false;
            riserKey = "";
            riserRow = 0;
        }

        // FIRING RANGE / LEAVE RANGE (bottom-left). Cinematic: beside the stat block's advanced-stats button.
        // Non-cinematic: next free slot of the bottom-left utility stack (BLUEPRINTS / MODE).
        {
            Component rangeLabel = Component.translatable(inRangeClient() ? "screen.mwloadout.leave_range" : "screen.mwloadout.firing_range");
            int rangeW = cinematic ? font.width(rangeLabel) + 16 : RAIL_WIDTH;
            int rangeH = cinematic ? 16 : 20;
            int rangeX;
            int rangeY;
            if (cinematic) {
                rangeX = STATS_X + STATS_W + 6 + ADV_BUTTON + 6;
                rangeY = height - 14 - ADV_BUTTON;
            } else {
                int slots = (blueprintOptions.size() > 1 ? 1 : 0) + (availableFireModes.size() > 1 ? 1 : 0);
                rangeX = utilityX;
                rangeY = utilityY + utilityStep * slots;
            }
            addRenderableWidget(MWStyle.flatButton(rangeX, rangeY, rangeW, rangeH, rangeLabel, button -> toggleFiringRange()));
        }

        rebuild();
    }

    private static boolean inRangeClient() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null && level.dimension().location().equals(dev.l41nx.mwloadout.server.FiringRange.DIM_ID);
    }

    /** Same save as DONE (the dimension change would drop the screen and any unsaved build), then ask the server. */
    private void toggleFiringRange() {
        selection.attachments.clear();
        selection.attachments.putAll(builds.activeBuild());
        Net.CHANNEL.sendToServer(new Net.C2SSaveGunBuild(gunEntry.id, builds.active,
                Json.GSON.toJson(builds.toJson())));
        Net.CHANNEL.sendToServer(new Net.C2SRangeToggle());
        MWStyle.playClick();
        net.minecraft.client.Minecraft.getInstance().setScreen(null);
    }

    private void rebuild() {
        candidates.clear();
        String query = searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        try {
            TimelessAPI.getAllCommonAttachmentIndex().forEach(pair -> {
                if (pair.getValue().getType() != activeType) {
                    return;
                }
                String id = pair.getKey().toString();
                if (!TaczStats.compatible(gunEntry.gameId, id)) {
                    return;
                }
                // honor an admin's per-attachment disable from the Catalog's new
                // ATTACHMENT branch. No matching catalog entry (not scanned yet, or server predates
                // this feature) means "allowed" — this is an opt-out list, not an opt-in allowlist,
                // so upgrading never silently hides every attachment on an unscanned server.
                dev.l41nx.mwloadout.data.CatalogEntry catalogEntry =
                        dev.l41nx.mwloadout.registry.LoadoutRegistry.get().entry("att_" + id.replace(':', '_').replace('/', '_'));
                if (catalogEntry != null && !catalogEntry.enabled) {
                    return;
                }
                String name = cleanName(id);
                if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)
                        && !id.toLowerCase(Locale.ROOT).contains(query)) {
                    return;
                }
                // weapon-level gating (see Rules.weaponProgressionEnabled) — reuses the
                // exact same unlockLevel field CatalogEntry already had for gun-level gating,
                // just applied to the ATTACHMENT-kind entry instead.
                int requiredLevel = catalogEntry != null ? catalogEntry.unlockLevel : 0;
                candidates.add(new Candidate(id, name, requiredLevel));
            });
        } catch (Exception ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/AttachmentEditorScreen:365", ignored);
        }
        sortCandidates();
        scroll = 0;
        scrollTarget = 0;
    }

    /** Equipped attachment of the section first, everything else alphabetical. Runs only when a section is OPENED (rebuild), never while the
     *  player is picking: the list stays exactly as it is under the cursor, and the pick shows up on top the next time the section is entered. */
    private void sortCandidates() {
        String selected = activeType == null ? "" : builds.activeBuild().getOrDefault(activeType.name(), "");
        candidates.sort((a, b) -> {
            boolean aSel = !selected.isEmpty() && a.id.equals(selected);
            boolean bSel = !selected.isEmpty() && b.id.equals(selected);
            if (aSel != bSel) {
                return aSel ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    /** Zone order: left, top-left, top-center, top-right, right, bottom-right, bottom-center,
     *  bottom-left (see computeHotspotZones). Keyword lists per zone, checked against
     *  type.name().toLowerCase() — first match wins. */
    private static final String[][] ZONE_HINTS = {
            {"muzzle"},
            {"barrel"},
            {"laser", "light"},
            {"scope", "optic", "sight"},
            {"stock"},
            {"grip", "rear"},
            {"mag", "ammo"},
            {"under", "foregrip"},
    };

    private void computeZoneAssignment() {
        typeZone.clear();
        int zoneCount = Math.max(8, types.size());
        boolean[] taken = new boolean[zoneCount];
        int[] assigned = new int[types.size()];
        java.util.Arrays.fill(assigned, -1);
        for (int i = 0; i < types.size(); i++) {
            String name = types.get(i).name().toLowerCase(Locale.ROOT);
            for (int z = 0; z < ZONE_HINTS.length; z++) {
                if (!taken[z] && java.util.Arrays.stream(ZONE_HINTS[z]).anyMatch(name::contains)) {
                    assigned[i] = z;
                    taken[z] = true;
                    break;
                }
            }
        }
        int nextFree = 0;
        for (int i = 0; i < types.size(); i++) {
            if (assigned[i] < 0) {
                while (nextFree < taken.length && taken[nextFree]) {
                    nextFree++;
                }
                // No modulo back into the 8 anchor zones — a 9th+ type gets its OWN zone index
                // (computeHotspotZones grows the rect pool to match) instead of sharing a
                // rectangle pixel-for-pixel with whichever type already claimed it, which used to
                // completely hide and disable one of the two silently (both fill()'d the exact
                // same box; the later one in the loop painted over the earlier one, and clicks
                // only ever reached whichever type the click hit-test checked first).
                assigned[i] = nextFree;
                taken[assigned[i]] = true;
            }
            typeZone.add(assigned[i]);
        }
    }

    private int contentHeight() {
        return candidates.size() * ROW_HEIGHT;
    }

    // ------------------------------------------------------------------ laser colour (TACZ's refit screen lets you tune it too)
    private static final int LASER_STRIP_H = 34;
    private boolean laserDragging;
    private long laserLastApply;

    private boolean laserStripVisible() {
        return activeType == AttachmentType.LASER && builds.activeBuild().containsKey("LASER") && !candidates.isEmpty();
    }

    /** The rows end above the colour strip (it has its own band at the bottom of the panel), so rows and their item icons never run under it. */
    private int rowsBottom() {
        return laserStripVisible() ? listBottom - LASER_STRIP_H - 4 : listBottom;
    }

    private int stripY() {
        return listBottom - LASER_STRIP_H;
    }

    // slider geometry: [ hue bar ........ ][ now ][ white ][ AUTO ]
    private int autoX() { return listRight - 2 - 32; }
    private int whiteX() { return autoX() - 4 - 14; }
    private int nowX() { return whiteX() - 4 - 14; }
    private int barX() { return listX + 4; }
    private int barW() { return Math.max(40, nowX() - 8 - barX()); }

    private static int hueToRgb(float hue) {
        float h6 = (hue - (float) Math.floor(hue)) * 6f;
        int i = (int) Math.floor(h6);
        float f = h6 - i, q = 1f - f;
        float r, g, b;
        switch (i) {
            case 0 -> { r = 1; g = f; b = 0; }
            case 1 -> { r = q; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = f; }
            case 3 -> { r = 0; g = q; b = 1; }
            case 4 -> { r = f; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** Hue 0..1 of a saturated colour, or -1 when the stored colour is auto / white / grey (no handle on the bar). */
    private static float rgbToHue(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max <= 0f || (max - min) < 0.2f * max) {
            return -1f;
        }
        float d = max - min, h;
        if (max == r) {
            h = ((g - b) / d) % 6f;
        } else if (max == g) {
            h = (b - r) / d + 2f;
        } else {
            h = (r - g) / d + 4f;
        }
        h /= 6f;
        return h < 0 ? h + 1f : h;
    }

    private int currentLaserRgb() {
        String hex = builds.activeBuild().get(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY);
        if (hex == null) {
            return -1;
        }
        try {
            return Integer.parseUnsignedInt(hex.substring(1), 16) & 0xFFFFFF;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void setLaserRgb(int rgb) {
        builds.activeBuild().put(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY, String.format("#%06X", rgb & 0xFFFFFF));
        markBuildDirty();
    }

    private void applyHueAt(double mouseX) {
        float hue = Mth.clamp((float) (mouseX - barX()) / barW(), 0f, 0.999f);
        setLaserRgb(hueToRgb(hue));
    }

    private void drawLaserStrip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!laserStripVisible()) {
            return;
        }
        int y = stripY();
        int panelW = listRight - listX + 4;
        MWStyle.plateFill(graphics, true, listX - 2, y, panelW, LASER_STRIP_H, MWStyle.cut(panelW, LASER_STRIP_H, 4), MWStyle.withAlpha(0xFF0B0C0E, 0.96f));
        MWStyle.strF(graphics, font, "LASER COLOR", listX + 4, y + 4, MWStyle.TEXT_DIM, false);
        int barY = y + 17, barH = 12, bx = barX(), bw = barW();
        // rainbow track: six two-colour gradients between the primary / secondary hues
        int[] stops = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000};
        for (int i = 0; i < 6; i++) {
            int x0 = bx + Math.round(bw * i / 6f), x1 = bx + Math.round(bw * (i + 1) / 6f);
            MWStyle.gradientH(graphics, x0, barY, x1 - x0, barH, 0xFF000000 | stops[i], 0xFF000000 | stops[i + 1]);
        }
        MWStyle.plateEdge(graphics, true, bx - 1, barY - 1, bw + 2, barH + 2, 0, MWStyle.border());
        int rgb = currentLaserRgb();
        float hue = rgb < 0 ? -1f : rgbToHue(rgb);
        if (hue >= 0f) {
            int hx = bx + Math.round(hue * bw);
            graphics.fill(hx - 2, barY - 3, hx + 2, barY + barH + 3, 0xFF0B0C0E);
            graphics.fill(hx - 1, barY - 2, hx + 1, barY + barH + 2, 0xFFFFFFFF);
        }
        // current colour / white / auto
        int cy = barY;
        MWStyle.plateFill(graphics, true, nowX(), cy, 14, barH, 3, 0xFF000000 | (rgb < 0 ? 0x3A3E44 : rgb));
        if (rgb < 0) {
            MWStyle.strF(graphics, font, "A", nowX() + 4, cy + 2, MWStyle.TEXT, false);
        }
        MWStyle.plateFill(graphics, true, whiteX(), cy, 14, barH, 3, 0xFFFFFFFF);
        boolean autoOn = rgb < 0;
        MWStyle.plateFill(graphics, true, autoX(), cy, 32, barH, 3, autoOn ? MWStyle.withAlpha(MWStyle.signal(), 0.35f) : MWStyle.PANEL_RAISED);
        MWStyle.plateEdge(graphics, true, autoX(), cy, 32, barH, 3, autoOn ? MWStyle.signal() : MWStyle.border());
        MWStyle.strF(graphics, font, "AUTO", autoX() + 5, cy + 2, autoOn ? MWStyle.signal() : MWStyle.TEXT_DIM, false);
        if (rgb == 0xFFFFFF) {
            MWStyle.plateEdge(graphics, true, whiteX() - 1, cy - 1, 16, barH + 2, 3, MWStyle.signal());
        }
    }

    /** @return true when the click landed on the laser colour strip (handled) */
    private boolean clickLaserStrip(double mouseX, double mouseY) {
        if (!laserStripVisible()) {
            return false;
        }
        int y = stripY();
        if (mouseY < y || mouseY >= listBottom || mouseX < listX - 2 || mouseX >= listRight + 2) {
            return false;
        }
        int barY = y + 17;
        if (mouseY >= barY - 3 && mouseY < barY + 15) {
            if (mouseX >= barX() - 2 && mouseX < barX() + barW() + 2) {
                laserDragging = true;
                applyHueAt(mouseX);
                MWStyle.playClick();
            } else if (mouseX >= autoX() && mouseX < autoX() + 32) {
                builds.activeBuild().remove(dev.l41nx.mwloadout.server.TaczStackFactory.LASER_COLOR_KEY);
                markBuildDirty();
                MWStyle.playClick();
            } else if (mouseX >= whiteX() && mouseX < whiteX() + 14) {
                setLaserRgb(0xFFFFFF);
                MWStyle.playClick();
            }
        }
        return true; // the strip is opaque: a click on it must never fall through to a row beneath
    }

    /** current weapon level for THIS gun (see Rules.weaponProgressionEnabled) — returns
     *  Integer.MAX_VALUE (nothing ever locked) when weapon progression is off or no client
     *  profile is available yet, so every level-gating check below degrades to "always allowed"
     *  exactly like the system did before this existed. */
    private int currentWeaponLevel() {
        dev.l41nx.mwloadout.data.Rules rules = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules();
        dev.l41nx.mwloadout.data.PlayerProfile profile = dev.l41nx.mwloadout.client.ClientCache.profile();
        if (!rules.weaponProgressionEnabled || profile == null) {
            return Integer.MAX_VALUE;
        }
        return profile.weaponLevel(gunEntry.gameId, rules.weaponXpPerLevel, rules.weaponXpCurveGrowth);
    }

    /** MW19-style ring of boxes around the weapon, one per attachment type this gun
     *  offers — left/right sides + top row + bottom row, matching the reference layout's rough
     *  positions (muzzle/barrel/optic/stock ring the upper half, underbarrel/mag/grip the lower).
     *  Deliberately fixed screen-relative zones rather than true per-gun anatomical anchor points
     *  (TACZ model geometry varies too much per weapon pack to hand-place those reliably without
     *  visual iteration against the actual render) — extra types beyond 8 stack under the last
     *  zone rather than overflowing off-screen. Recomputed every render() since width/height can
     *  change (window resize); cheap (a handful of int[] each call).
     *  Order: left, top-left, top-center, top-right, right, bottom-right, bottom-center, bottom-left —
     *  walking clockwise from 9 o'clock, same visual rhythm as the MW19 reference screenshot. */
    private static boolean muzzleLeft = true; // which side the gun's muzzle faces; the FLIP button toggles it (remembered)
    private int cardW = 150;                   // width of the attachment cards (set by computeHotspotZones)
    private int cardH = 34;
    private int stageX, stageY, stageW, stageH; // gun stage between the two card columns

    private static boolean isFrontZone(int zone) {
        return zone == 0 || zone == 1 || zone == 2 || zone == 7; // muzzle, barrel, laser/light, underbarrel
    }

    /** Deterministic two-column layout - NO card can ever overlap another card, the header, the stats block or the
     *  gun stage. Front-of-gun attachments (muzzle, barrel, laser, underbarrel) stack down the side the muzzle faces,
     *  everything else down the other side; if one column runs out of height the rest flows into the other one. Card
     *  size steps down (34/30/26px) on small screens. The gun stage is whatever is left between the columns. */
    private void computeHotspotZones() {
        int maxZone = 0;
        for (int z : typeZone) {
            maxZone = Math.max(maxZone, z);
        }
        hotspotZones.clear();
        for (int i = 0; i <= maxZone; i++) {
            hotspotZones.add(new int[]{-1000, -1000, 0, 0});
        }
        cardW = Mth.clamp((int) (width * 0.19f), 112, 150); // slim tags (MW4 gunsmith), not wide boxes
        int margin = 14;
        int leftTop = 96;                                   // under the build tabs + BLUEPRINTS/MODE/FLIP row
        int leftLimit = height - 14 - STATS_H - 44;         // above the weapon-level + ATTACHMENTS lines
        int rightTop = 60;
        int rightLimit = height - 34;                       // above the footer buttons
        int[][] tiers = {{30, 36}, {27, 31}, {24, 28}};
        int chosen = tiers.length - 1;
        for (int t = 0; t < tiers.length; t++) {
            int cap = Math.max(0, (leftLimit - leftTop + (tiers[t][1] - tiers[t][0])) / tiers[t][1])
                    + Math.max(0, (rightLimit - rightTop + (tiers[t][1] - tiers[t][0])) / tiers[t][1]);
            if (cap >= types.size()) {
                chosen = t;
                break;
            }
        }
        cardH = tiers[chosen][0];
        int step = tiers[chosen][1];
        int leftCap = Math.max(0, (leftLimit - leftTop + (step - cardH)) / step);
        int rightCap = Math.max(0, (rightLimit - rightTop + (step - cardH)) / step);

        // types in zone order, split front / rear
        List<Integer> front = new ArrayList<>();
        List<Integer> rear = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.comparingInt(typeZone::get));
        for (int ti : order) {
            (isFrontZone(typeZone.get(ti)) ? front : rear).add(ti);
        }
        int frontCap = layoutMuzzleLeft ? leftCap : rightCap;
        int rearCap = layoutMuzzleLeft ? rightCap : leftCap;
        List<Integer> frontCol = new ArrayList<>();
        List<Integer> rearCol = new ArrayList<>();
        for (int ti : front) {
            (frontCol.size() < frontCap ? frontCol : rearCol).add(ti);
        }
        for (int ti : rear) {
            (rearCol.size() < rearCap ? rearCol : frontCol).add(ti);
        }
        List<Integer> leftCol = layoutMuzzleLeft ? frontCol : rearCol;
        List<Integer> rightCol = layoutMuzzleLeft ? rearCol : frontCol;
        // Like the MW4 gunsmith the tags bow around the gun: the ones at the top and bottom of a column sit nearer the gun, the middle
        // ones further out; a column with room spreads its tags down the side instead of stacking them from the top.
        int maxIn = Math.max(0, Math.min(40, (width - 2 * (margin + cardW) - 150) / 2));
        for (int i = 0, n = leftCol.size(); i < n; i++) {
            int pitch = n > 1 ? Mth.clamp((leftLimit - leftTop - cardH) / (n - 1), step, step * 2) : step;
            int indent = Math.round(maxIn * (1f - (float) Math.sin(Math.PI * (i + 0.5) / n)));
            hotspotZones.set(typeZone.get(leftCol.get(i)), new int[]{margin + indent, leftTop + i * pitch, cardW, cardH});
        }
        for (int i = 0, n = rightCol.size(); i < n; i++) {
            int pitch = n > 1 ? Mth.clamp((rightLimit - rightTop - cardH) / (n - 1), step, step * 2) : step;
            int indent = Math.round(maxIn * (1f - (float) Math.sin(Math.PI * (i + 0.5) / n)));
            hotspotZones.set(typeZone.get(rightCol.get(i)), new int[]{width - margin - cardW - indent, rightTop + i * pitch, cardW, cardH});
        }
        // gun stage: between the columns, full height under the title block
        stageX = margin + cardW + 12;
        stageW = Math.max(120, width - 2 * stageX);
        stageY = 60;
        stageH = Math.max(80, height - stageY - 34);
    }

    /** Toggles the candidate popup for `type` open — reusing the exact same list/scroll/hover/
     *  click machinery the non-cinematic rail always used, just repositioned to a centered popup
     *  instead of a permanently-docked left column. Clicking the already-open type's own box
     *  closes it (MW muscle memory: click again to back out). */
    private void openTypePopup(AttachmentType type) {
        if (listOpen && activeType == type) {
            listOpen = false;
            searchBox.setVisible(false);
            preview.resetView();
            return;
        }
        activeType = type;
        preview.resetView(); // every slot starts from the default camera angle
        searchBox.setValue("");
        listOpen = true;
        // Select view (MW-style): the candidate list docks on the LEFT above the stats block; the big title and the
        // gun move right (see renderSelectInfo / renderStatsPanel).
        int popupW = Math.min(214, Math.max(150, width / 3));
        listX = 18;
        listRight = listX + popupW;
        refreshRiser(true);
        applyPopupLayout();
        searchBox.setVisible(true);
        MWStyle.playTab();
        listFlashStart = System.currentTimeMillis();
        rebuild();
    }

    private String buildLabel(int i) {
        int count = TaczStats.installedAttachments(gunEntry.gameId, builds.builds.get(i));
        return Component.translatable("screen.mwloadout.build", i + 1).getString().toUpperCase(Locale.ROOT)
                + (count > 0 ? "  (" + count + ")" : "");
    }

    private int dropdownWidth() {
        int w = font.width(buildLabel(builds.active)) + 28;
        for (int i = 0; i < builds.builds.size(); i++) {
            w = Math.max(w, font.width(buildLabel(i)) + 28);
        }
        return Math.max(w, font.width("+ NEW BUILD") + 28);
    }

    private void renderBuildDropdownHead(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = 10, y = tabsY(), w = dropdownWidth(), h = tabsH();
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        MWStyle.fillF(graphics, x, y, x + w, y + h, hovered || buildDropdownOpen ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
        MWStyle.hairline(graphics, x, y, w, h, buildDropdownOpen ? MWStyle.borderActive() : MWStyle.border());
        MWStyle.strF(graphics, font, buildLabel(builds.active), x + 7, y + (h - 8) / 2, MWStyle.accent(), false);
        MWStyle.strF(graphics, font, buildDropdownOpen ? "\u25B2" : "\u25BC", x + w - 14, y + (h - 8) / 2, MWStyle.TEXT_DIM, false);
    }

    private void renderBuildDropdownList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = 10, w = dropdownWidth(), rowH = 16;
        int y = tabsY() + tabsH() + 1;
        int rows = builds.builds.size() + (builds.builds.size() < PlayerProfile.GunBuilds.MAX_SLOTS ? 1 : 0);
        MWStyle.fillF(graphics, x, y, x + w, y + rows * rowH + 2, 0xFF0E0F12);
        MWStyle.hairline(graphics, x, y, w, rows * rowH + 2, MWStyle.borderActive());
        for (int i = 0; i < rows; i++) {
            int ry = y + 1 + i * rowH;
            boolean add = i >= builds.builds.size();
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH;
            boolean active = !add && i == builds.active;
            if (active) {
                MWStyle.fillF(graphics, x + 1, ry, x + w - 1, ry + rowH, MWStyle.withAlpha(MWStyle.signal(), 0.16f));
                MWStyle.fillF(graphics, x + 1, ry, x + 3, ry + rowH, MWStyle.signal());
            } else if (hovered) {
                MWStyle.fillF(graphics, x + 1, ry, x + w - 1, ry + rowH, MWStyle.withAlpha(MWStyle.accent(), 0.22f));
            }
            String label = add ? "+ NEW BUILD" : buildLabel(i);
            MWStyle.strF(graphics, font, label, x + 8, ry + (rowH - 8) / 2,
                    active ? MWStyle.signal() : add ? MWStyle.TEXT_DIM : MWStyle.TEXT, false);
        }
    }

    /** @return true when the click was consumed by the dropdown (head or list). */
    private boolean clickBuildDropdown(double mouseX, double mouseY, int button) {
        int x = 10, w = dropdownWidth(), rowH = 16;
        int headY = tabsY(), headH = tabsH();
        if (mouseX >= x && mouseX < x + w && mouseY >= headY && mouseY < headY + headH && button == 0) {
            buildDropdownOpen = !buildDropdownOpen;
            MWStyle.playClick();
            return true;
        }
        if (!buildDropdownOpen) {
            return false;
        }
        int y = headY + headH + 1;
        int rows = builds.builds.size() + (builds.builds.size() < PlayerProfile.GunBuilds.MAX_SLOTS ? 1 : 0);
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + rows * rowH + 2) {
            int i = (int) ((mouseY - y - 1) / rowH);
            if (i >= 0 && i < rows) {
                if (i >= builds.builds.size()) {
                    if (button == 0 && builds.addSlot()) {
                        builds.active = builds.builds.size() - 1;
                        markBuildDirty();
                        MWStyle.playClick();
                    }
                } else if (button == 1) {
                    buildContextMenuOpen = true;
                    buildContextMenuIndex = i;
                    buildContextMenuX = (int) mouseX;
                    buildContextMenuY = (int) mouseY;
                    MWStyle.playClick();
                    return true; // keep the list open behind the context menu
                } else if (builds.active != i) {
                    builds.active = i;
                    markBuildDirty();
                    MWStyle.playTab();
                    listFlashStart = System.currentTimeMillis();
                }
            }
            buildDropdownOpen = false;
            return true;
        }
        buildDropdownOpen = false; // click anywhere else just closes it (and is not eaten)
        return false;
    }

    /** Build-tab row: compact (16px, y=48) in the cinematic layout, the original 18px row at y=52 otherwise. */
    private int tabsY() {
        return cinematic ? 48 : 52;
    }

    private int tabsH() {
        return cinematic ? 16 : 18;
    }

    // ------------------------------------------------------------------ PREVIEW mode

    private void enterPreviewMode() {
        if (closingAt > 0 || previewMode) {
            return;
        }
        if (cinematic && listOpen) {
            closeListPopup();
        }
        blueprintPanelOpen = false;
        buildDropdownOpen = false;
        buildContextMenuOpen = false;
        previewMode = true;
        previewModeAt = System.currentTimeMillis();
        previewKAt = previewModeAt;
        preview.setHoldView(true);
        preview.setUnclipped(true);
        MWStyle.playClick();
    }

    private void exitPreviewMode() {
        previewMode = false; // previewK now eases back to 0: the gun shrinks home while the UI fades / slides back in
        previewKAt = System.currentTimeMillis();
        preview.setHoldView(false);
        preview.setUnclipped(false);
        preview.endDrag();
        preview.resetView(); // the model eases back to the default angle while the UI returns
        MWStyle.playClick();
    }

    /** Advances the PREVIEW transition (about a quarter of a second each way) and returns it eased. */
    private float stepPreview() {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, Math.max(0f, (now - previewKAt) / 1000f));
        previewKAt = now;
        float target = previewMode ? 1f : 0f;
        if (previewK < target) {
            previewK = Math.min(target, previewK + dt / 0.26f);
        } else if (previewK > target) {
            previewK = Math.max(target, previewK - dt / 0.22f);
        }
        return previewK * previewK * (3f - 2f * previewK);
    }

    /** Fully in preview: nothing but the gun (the hint shows for the first moments and then fades out completely). */
    private void renderPreviewHint(GuiGraphics graphics) {
        long since = System.currentTimeMillis() - previewModeAt;
        if (since < 3400L) {
            float alpha = since < 2400L ? 1f : 1f - (since - 2400L) / 1000f;
            String hint = MWStyle.tr("screen.mwloadout.preview_hint");
            MWStyle.strF(graphics, font, hint, (width - font.width(hint)) / 2, height - 22, MWStyle.withAlpha(MWStyle.TEXT_DIM, alpha), false);
        }
    }

    /** Packs the utility row (BLUEPRINTS / MODE / FLIP / PREVIEW) to the left every frame, so each visible button sits right behind the
     *  previous visible one with the normal gap - and slides there when one appears or disappears. */
    private void layoutUtilityRow(float dtTicks) {
        if (!cinematic) {
            return;
        }
        int x = 10;
        for (Button b : new Button[]{blueprintButton, fireModeButton, flipButton, previewButton}) {
            if (b == null || !b.visible) {
                continue;
            }
            Float shown = utilityRowX.get(b);
            float now = shown == null ? x : MWStyle.approach(shown, x, 0.4f, dtTicks);
            utilityRowX.put(b, now);
            b.setX(Math.round(now));
            x += b.getWidth() + 6;
        }
    }

    // ------------------------------------------------------------------ TACZ 1.2.0 optic risers

    /** Recomputes which risers this gun accepts on the open slot. Options depend on the slot and on the installed optic, so this only runs
     *  when either changed. Returns true when the popup header row appeared or disappeared (the popup must then be laid out again). */
    private boolean refreshRiser(boolean force) {
        int before = riserRow;
        if (!cinematic || activeType == null
                || !dev.l41nx.mwloadout.server.TaczCompat.enabled(dev.l41nx.mwloadout.server.TaczCompat.Feature.RISER)) {
            riserOptions = java.util.List.of();
        } else {
            String key = activeType.name() + builds.activeBuild().hashCode();
            if (force || !key.equals(riserKey)) {
                riserKey = key;
                try {
                    net.minecraft.world.item.ItemStack gun = dev.l41nx.mwloadout.server.TaczStackFactory.buildGun(gunEntry.gameId, builds.activeBuild());
                    riserOptions = dev.l41nx.mwloadout.server.TaczCompat.availableAdapters(gun, activeType);
                } catch (RuntimeException e) {
                    riserOptions = java.util.List.of();
                }
                if (riserButton != null) {
                    riserButton.setMessage(Component.literal("RISER: " + riserName(currentRiser())));
                }
            }
        }
        riserRow = riserOptions.isEmpty() ? 0 : RISER_ROW;
        return riserRow != before;
    }

    /** Per-frame: keeps the RISER row in sync while the select popup is open. */
    private void updateRiserButton() {
        if (riserButton == null) {
            return;
        }
        if (listOpen && refreshRiser(false)) {
            applyPopupLayout();
        }
        riserButton.visible = listOpen && riserRow > 0;
    }

    private static final int RISER_ROW = 18;
    /** The panel moves up into the empty strip under the build button, so the RISER header costs the list almost no height. */
    private static final int RISER_RISE = 16;

    /** Geometry of the select popup. With risers the panel grows a header row (the RISER button) at its top: the button sits INSIDE the panel
     *  above the search box, the panel top rises into the free strip above it, and the bottom stays where it was - so it can never run into
     *  the ATTACHMENTS row / stat bars below (a taller panel did, on small windows). */
    private void applyPopupLayout() {
        listTop = 104 + (riserRow > 0 ? riserRow - RISER_RISE : 0);
        int minRows = riserRow > 0 ? 2 : 3; // the list scrolls; overlapping the stat block is worse than a shorter list
        listBottom = Math.max(listTop + ROW_HEIGHT * minRows, height - 14 - STATS_H - 40);
        searchBox.setX(listX);
        searchBox.setY(listTop - 18);
        searchBox.setWidth(listRight - listX);
        if (riserButton != null) {
            riserButton.setX(listX);
            riserButton.setY(listTop - 18 - riserRow);
            riserButton.setWidth(listRight - listX);
        }
    }

    private net.minecraft.resources.ResourceLocation currentRiser() {
        String id = builds.activeBuild().get(dev.l41nx.mwloadout.server.TaczStackFactory.RISER_KEY_PREFIX + activeType.name());
        return id == null ? null : net.minecraft.resources.ResourceLocation.tryParse(id);
    }

    private static String riserName(net.minecraft.resources.ResourceLocation id) {
        if (id == null) {
            return "NONE";
        }
        String key = id.getNamespace() + ".slot_adapter." + id.getPath() + ".name";
        String translated = Component.translatable(key).getString();
        if (!translated.equals(key)) {
            return translated.toUpperCase(Locale.ROOT);
        }
        return id.getPath().replace('_', ' ').toUpperCase(Locale.ROOT); // another pack's adapter: its id, readable
    }

    private void cycleRiser() {
        if (activeType == null || riserOptions.isEmpty()) {
            return;
        }
        String key = dev.l41nx.mwloadout.server.TaczStackFactory.RISER_KEY_PREFIX + activeType.name();
        int next = riserOptions.indexOf(currentRiser()) + 1; // NONE (-1) -> first -> ... -> last -> NONE
        if (next >= riserOptions.size()) {
            builds.activeBuild().remove(key);
        } else {
            builds.activeBuild().put(key, riserOptions.get(next).toString());
        }
        refreshRiser(true);
        markBuildDirty();
        MWStyle.playClick();
    }

    private void closeListPopup() {
        listOpen = false;
        searchBox.setVisible(false);
        preview.resetView();
    }

    /** builds.activeBuild().size() minus the fire-mode entry if present — the fire mode choice
     *  shares the same Map<String,String> as real attachments (see TaczStackFactory.FIRE_MODE_KEY
     *  doc) purely for storage convenience, but it isn't a real attachment and must never count
     *  against the attachment-points budget. */
    private int realAttachmentCount() {
        int count = 0;
        for (String key : builds.activeBuild().keySet()) {
            if (!dev.l41nx.mwloadout.server.TaczStackFactory.isMeta(key)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Blueprint modal: while open, the gunsmith UI behind it must see NO mouse (otherwise
        // attachment rows / hotspot boxes / stat bars under the picker still hover and draw
        // their tooltips through it). The picker itself gets the real coordinates.
        final int realMouseX = mouseX, realMouseY = mouseY;
        final float pe = stepPreview(); // eased PREVIEW transition (0 = normal gunsmith, 1 = nothing but the gun)
        if (previewMode || previewK > 0f) {
            mouseX = -10000; // no hover / tooltips on a UI that is fading away
            mouseY = -10000;
        }
        if (blueprintPanelOpen || advanced.isOpen()) {
            mouseX = -10000;
            mouseY = -10000;
        }
        long nowMs = System.currentTimeMillis();
        float dtTicks = Math.min(4f, (nowMs - lastFrameMillis) / 50f);
        lastFrameMillis = nowMs;
        scroll = MWStyle.approach((float) scroll, (float) scrollTarget, 0.5f, dtTicks);
        MWStyle.screenChrome(graphics, width,
                DisplayNames.of(gunEntry) + " - " + MWStyle.tr("screen.mwloadout.gunsmith"), openedAt);
        if (pe > 0f) {
            MWStyle.chromeOpen *= 1f - pe; // every fill / text / widget below fades with it, and the buttons slide down as they go
        }
        String versionTag = LoadoutScreen.modVersionTag();
        if (!versionTag.isEmpty() && pe < 0.999f) { // same gold tag as the class screen
            String gunTitle = (DisplayNames.of(gunEntry) + " - " + MWStyle.tr("screen.mwloadout.gunsmith")).toUpperCase(java.util.Locale.ROOT);
            int tagX = 10 + font.width(gunTitle) + 8;
            MWStyle.fillF(graphics, tagX, 11, tagX + font.width(versionTag) + 8, 23, MWStyle.signal());
            MWStyle.strF(graphics, font, versionTag, tagX + 4, 13, 0xFF101114, false);
        }
        if (cinematic) {
            renderGunStage(pe); // behind every card / text; never clipped to a box
        }
        if (pe >= 0.999f) {
            renderPreviewHint(graphics); // fully in preview: only the gun is drawn
            return;
        }

        // Subtitle under the gun name (MW style: weapon class in accent colour); in the select view it names the slot.
        // BLUEPRINTS / MODE buttons live in the hub only; the select view uses that spot for the search box.
        boolean hubVisible = !(cinematic && listOpen) && !buildDropdownOpen; // the open build list covers the utility row
        if (blueprintButton != null) {
            blueprintButton.visible = hubVisible;
        }
        if (fireModeButton != null) {
            fireModeButton.visible = hubVisible;
        }
        if (flipButton != null) {
            flipButton.visible = hubVisible && !preview.isRefitActive(); // not needed when TACZ's refit camera drives the view
        }
        if (previewButton != null) {
            previewButton.visible = hubVisible;
        }
        layoutUtilityRow(dtTicks);
        updateRiserButton();
        float selDt = MWStyle.frameDt();
        // Hub <-> select view switches INSTANTLY: item icons cannot be alpha-faded, so a cross-fade made them pop in and out
        // (flicker) while the panels around them faded. Only the camera glides (TACZ refit transition).
        selectAnim = cinematic && listOpen ? 1f : 0f;
        String subtitle = cinematic && selectAnim > 0.5f
                ? "SELECT " + dev.l41nx.mwloadout.MWSlots.label(activeType)
                : SlotPickerScreen.categoryDisplayName(gunEntry.category);
        MWStyle.strF(graphics, font, subtitle, 10, 36, MWStyle.accent(), false);

        // Attachment budget readout (MW19's 5-attachment ceiling as a points meter).
        var rules = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules();
        // Count only (n / max): the old "points" meter is gone from the UI. Risers (TACZ 1.2.0) are mounts, not attachments, so they are
        // never part of this count (TaczStackFactory.isMeta skips them).
        int usedCount = realAttachmentCount();
        int maxCount = rules.attachmentPointLimit > 0 ? rules.maxAttachments() : 0;
        boolean atLimit = maxCount > 0 && usedCount >= maxCount;
        if (maxCount > 0) {
            String points = Component.translatable("screen.mwloadout.attach_count", usedCount, maxCount).getString();
            MWStyle.strF(graphics, font, points, width - font.width(points) - 12, 34,
                    atLimit ? MWStyle.NEGATIVE : MWStyle.TEXT_DIM, false);
            if (System.currentTimeMillis() < limitTipUntil) {
                String tip = MWStyle.tr("screen.mwloadout.points_full");
                MWStyle.strF(graphics, font, tip, width - font.width(tip) - 12, 44, MWStyle.NEGATIVE, false);
            }
        }
        if (System.currentTimeMillis() < levelLockTipUntil && !levelLockTipText.isEmpty()) {
            // weapon-level lock feedback — centred under wherever the popup list is
            // (works for both cinematic's centred popup and the non-cinematic docked list).
            int tipX = listX + (listRight - listX) / 2 - font.width(levelLockTipText) / 2;
            MWStyle.strF(graphics, font, levelLockTipText, tipX, listBottom + 6, MWStyle.LOCKED, false);
        }

        // Build slots (MW19: remembered configurations per gun, default 4, growable to
        // GunBuilds.MAX_SLOTS via the + tab) — tab shows its fill count. Right-click for options
        // (rename not needed since "Custom N" already reflects the slot number; delete, guarded
        // to never drop below 1 build).
        int buildX = 10;
        int tabsY = tabsY(), tabsH = tabsH();
        for (int i = 0; !cinematic && i < builds.builds.size(); i++) {
            boolean active = i == builds.active;
            boolean filled = !builds.builds.get(i).isEmpty();
            Component label = Component.literal(Component.translatable("screen.mwloadout.build", i + 1).getString()
                    + (TaczStats.installedAttachments(gunEntry.gameId, builds.builds.get(i)) > 0 ? " (" + TaczStats.installedAttachments(gunEntry.gameId, builds.builds.get(i)) + ")" : "")).withStyle(ChatFormatting.BOLD);
            int tabWidth = font.width(label) + 14;
            MWStyle.fillF(graphics, buildX, tabsY, buildX + tabWidth, tabsY + tabsH, active ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
            MWStyle.hairline(graphics, buildX, tabsY, tabWidth, tabsH, active ? MWStyle.borderActive() : MWStyle.border());
            MWStyle.strF(graphics, font, label, buildX + 7, tabsY + (tabsH - 8) / 2,
                    active ? MWStyle.accent() : filled ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
            buildX += tabWidth + 4;
        }
        if (cinematic) {
            addBuildTabX = -1;
            renderBuildDropdownHead(graphics, mouseX, mouseY);
        } else if (builds.builds.size() < PlayerProfile.GunBuilds.MAX_SLOTS) {
            addBuildTabX = buildX;
            int plusW = font.width("+") + 14;
            addBuildTabW = plusW;
            MWStyle.fillF(graphics, buildX, tabsY, buildX + plusW, tabsY + tabsH, MWStyle.PANEL_RAISED);
            MWStyle.hairline(graphics, buildX, tabsY, plusW, tabsH, MWStyle.border());
            MWStyle.strF(graphics, font, "+", buildX + 7, tabsY + (tabsH - 8) / 2, MWStyle.TEXT_DIM, false);
        } else {
            addBuildTabX = -1;
        }
        if (buildContextMenuOpen) {
            renderBuildContextMenu(graphics);
        }

        // Type rail (non-cinematic) OR hotspot boxes ringing the weapon (cinematic).
        if (cinematic) {
            computeHotspotZones();
            if (selectAnim < 0.98f) {
                MWStyle.localAlpha(1f - selectAnim); // hub cards melt away as the select view comes in
                try {
                    renderHotspotZones(graphics, listOpen ? -10000 : mouseX, listOpen ? -10000 : mouseY, atLimit);
                } finally {
                    MWStyle.localAlpha(1f);
                }
            }
        } else {
            int railY = listTop;
            for (AttachmentType type : types) {
                boolean active = type == activeType;
                boolean lockedByPoints = atLimit && !builds.activeBuild().containsKey(type.name());
                MWStyle.fillF(graphics, 10, railY, 10 + RAIL_WIDTH, railY + 20, active ? MWStyle.PANEL_HOVER : MWStyle.PANEL_RAISED);
                if (active) {
                    MWStyle.fillF(graphics, 10, railY, 12, railY + 20, MWStyle.signal());
                }
                MWStyle.strF(graphics, font, dev.l41nx.mwloadout.MWSlots.label(type), 18, railY + 6,
                        lockedByPoints ? MWStyle.LOCKED : active ? MWStyle.accent() : MWStyle.TEXT_DIM, false);
                String current = builds.activeBuild().get(type.name());
                if (current != null && !current.isEmpty()) {
                    MWStyle.fillF(graphics, 10 + RAIL_WIDTH - 8, railY + 8, 10 + RAIL_WIDTH - 4, railY + 12, MWStyle.signal());
                }
                railY += 24;
            }
        }

        // Candidate list — non-cinematic: always docked left. Cinematic: only while a hotspot
        // box's popup is open (see openTypePopup/closeListPopup).
        boolean showList = !cinematic || listOpen || selectAnim > 0.02f;
        if (showList) {
        if (cinematic) {
            MWStyle.localAlpha(selectAnim);
        }
        hoveredRow = rowAt(mouseX, mouseY);
        if (hoveredRow >= 0 && hoveredRow != lastHoverSoundRow) {
            MWStyle.playHover();
        }
        lastHoverSoundRow = hoveredRow;

        if (cinematic) {
            // Docked list card (the hub cards are hidden, so no full-screen dim is needed any more).
            MWStyle.panel(graphics, listX - 12, listTop - 22 - riserRow, (listRight - listX) + 24, (listBottom - listTop) + 34 + riserRow);
        }
        graphics.enableScissor(0, listTop, listRight, rowsBottom());
        String selectedId = builds.activeBuild().getOrDefault(activeType.name(), "");
        int weaponLevel = currentWeaponLevel();
        int rowY = listTop - (int) scroll;
        // (no "None" row any more: clicking the equipped attachment again takes it off)
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            drawRow(graphics, i, rowY, candidate.name, candidate.id, candidate.id.equals(selectedId),
                    candidate.requiredLevel(), weaponLevel);
            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();
        drawLaserStrip(graphics, mouseX, mouseY);

        int overflow = contentHeight() - (rowsBottom() - listTop);
        if (overflow > 0) {
            int trackHeight = rowsBottom() - listTop;
            int barHeight = Math.max(16, trackHeight * trackHeight / contentHeight());
            int barY = listTop + (int) (scroll / overflow * (trackHeight - barHeight));
            MWStyle.fillF(graphics, listRight + 2, listTop, listRight + 4, rowsBottom(), MWStyle.PANEL_RAISED);
            MWStyle.fillF(graphics, listRight + 2, barY, listRight + 4, barY + barHeight, MWStyle.border());
        }
        // Tab/build switch flash: a fast-decaying veil over the list masks the content pop-in.
        float flash = 1f - Math.min(1f, (System.currentTimeMillis() - listFlashStart) / 110f);
        if (MWStyle.sectionAnim()) {
            MWStyle.veil(graphics, listX, listTop, listRight - listX, listBottom - listTop, flash * 0.85f);
        }
        if (cinematic) {
            renderSelectInfo(graphics);
            MWStyle.localAlpha(1f);
        }
        } // end candidate-list block

        renderStatsPanel(graphics, mouseX, mouseY, partialTick);
        if (System.currentTimeMillis() < savedFlashUntil) {
            String saved = MWStyle.tr("screen.mwloadout.saved");
            MWStyle.strF(graphics, font, saved, saveButtonX + 4, height - 38, MWStyle.POSITIVE, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (cinematic && buildDropdownOpen) {
            renderBuildDropdownList(graphics, mouseX, mouseY);
        }
        if (!cinematic) {
            renderHoverTooltip(graphics, mouseX, mouseY);
        }
        // Same snappy easing the Loadout screen uses (0.5-0.6 per tick on real frame time), with a hard
        // snap at both ends so the fade finishes exactly instead of crawling asymptotically.
        float bpDt = MWStyle.frameDt();
        blueprintPanelAnim = MWStyle.approach(blueprintPanelAnim, blueprintPanelOpen ? 1f : 0f, 0.6f, bpDt);
        if (blueprintPanelOpen && blueprintPanelAnim > 0.99f) {
            blueprintPanelAnim = 1f;
        } else if (!blueprintPanelOpen && blueprintPanelAnim < 0.04f) {
            blueprintPanelAnim = 0f;
        }
        blueprintScrollAnim = MWStyle.approach(blueprintScrollAnim, blueprintScroll, 0.5f, bpDt);
        if (Math.abs(blueprintScrollAnim - blueprintScroll) < 0.01f) {
            blueprintScrollAnim = blueprintScroll;
        }
        if (blueprintPanelAnim > 0f && !blueprintOptions.isEmpty()) {
            renderBlueprintPanel(graphics, realMouseX, realMouseY, blueprintPanelAnim);
        }
        advanced.render(graphics, width, height, realMouseX, realMouseY);
        pumpBuildSync();
        transitionRender(graphics);
    }

    // Blueprint modal layout constants. The whole picker is ONE hard box (header / card strip /
    // compare footer) docked at the bottom-centre of the screen, and it is modal: while open, nothing behind it receives
    // hover or clicks (see render()/mouseClicked/mouseScrolled/keyPressed).
    private static final int BP_PAD = 12;
    private static final int BP_GUTTER = 14;   // room for the scroll arrows on each side of the strip
    private static final int BP_HEADER = 26;
    private static final int BP_FOOTER = 48;
    private static final int BP_MIN_BOX_W = 280;

    /** Geometry of the Blueprint modal, sized to how many variants this platform has: few
     *  variants get large cards; many shrink to a readable floor, then scroll. Returns
     *  {cardW, cardH, gap, stripW, stripX, stripY, visibleCards, boxX, boxY, boxW, boxH}.
     *  Recomputed on demand (render/click/scroll) — only depends on screen size + option count. */
    private int[] blueprintCardGeometry() {
        int gap = 8;
        int minCardW = 96, minCardH = 56;
        int idealCardW = 156, idealCardH = 78;
        int sideMargin = 2 * (BP_PAD + BP_GUTTER);
        int maxStripW = Math.max(minCardW, Math.min(760, width - sideMargin - 20));
        int n = Math.max(1, blueprintOptions.size());
        int fitAtIdeal = Math.max(1, (maxStripW + gap) / (idealCardW + gap));
        int cardW, cardH, visibleCards;
        if (n <= fitAtIdeal) {
            cardW = idealCardW;
            cardH = idealCardH;
            visibleCards = n;
        } else {
            int fitAtMin = Math.max(1, (maxStripW + gap) / (minCardW + gap));
            if (n <= fitAtMin) {
                cardW = Math.max(minCardW, (maxStripW + gap) / n - gap);
                cardH = Math.max(minCardH, cardW * idealCardH / idealCardW);
                visibleCards = n;
            } else {
                cardW = minCardW;
                cardH = minCardH;
                visibleCards = fitAtMin;
            }
        }
        int stripW = visibleCards * (cardW + gap) - gap;
        int boxW = Math.max(BP_MIN_BOX_W, stripW + sideMargin);
        int boxH = BP_HEADER + cardH + 6 + BP_FOOTER + 8;
        // Docked low and horizontally centred (the weapon stage keeps the upper/middle of the
        // screen), clear of the bottom button bar so it never covers DONE/CONFIRM.
        int boxX = width / 2 - boxW / 2;
        int boxY = Math.max(4, height - boxH - 40);
        int stripX = boxX + (boxW - stripW) / 2;
        int stripY = boxY + BP_HEADER;
        return new int[]{cardW, cardH, gap, stripW, stripX, stripY, visibleCards, boxX, boxY, boxW, boxH};
    }

    private boolean inBlueprintBox(double mx, double my) {
        int[] g = blueprintCardGeometry();
        return mx >= g[7] && mx < g[7] + g[9] && my >= g[8] && my < g[8] + g[10];
    }

    /** Fade-safe text for the blueprint modal. Vanilla's font renderer treats any colour whose alpha
     *  is below 4/255 as FULLY OPAQUE, so text "fading" to ~0 actually popped to solid for a frame
     *  at both ends of the animation — the text flicker. Below a visible threshold, skip drawing. */
    private void bpText(GuiGraphics graphics, float anim, String text, int x, int y, int color) {
        if (anim < 0.12f || ((color >>> 24) & 0xFF) < 12) {
            return;
        }
        MWStyle.strF(graphics, font, text, x, y, color, false);
    }

    /** MW2022-style Blueprint picker: a single centred hard box (title bar, horizontal card strip,
     *  stat-compare footer). Everything — including the compare readout — lives INSIDE the box,
     *  so there are no floating tooltips fighting with the gunsmith UI underneath. The screen
     *  behind is dimmed; mouse coordinates here are the REAL ones (render() masks them for
     *  everything else while the modal is open). */
    private void renderBlueprintPanel(GuiGraphics graphics, int mouseX, int mouseY, float anim) {
        int[] geo = blueprintCardGeometry();
        int cardW = geo[0], cardH = geo[1], gap = geo[2], stripW = geo[3], stripX = geo[4], visibleCards = geo[6];
        int boxW = geo[9], boxH = geo[10];
        int slide = (int) ((1f - anim) * 6); // slides up into place, not just fades
        int boxX = geo[7], boxY = geo[8] + slide;
        int stripY = geo[5] + slide;
        int overflow = Math.max(0, blueprintOptions.size() - visibleCards);
        blueprintScroll = Mth.clamp(blueprintScroll, 0, overflow);

        // Dim everything behind (MWStyle.veil skips full-screen rects, so fill directly). Z-levels:
        // the 3D gun preview is drawn at modelview z=200 and item icons carry their own offsets,
        // so the veil sits at 350 and the box at 400 — both strictly above all of it.
        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, 350f);
        MWStyle.fillF(graphics, 0, 0, width, height, MWStyle.withAlpha(0xFF08080A, 0.62f * anim));
        graphics.pose().translate(0f, 0f, 50f);

        // hard box: opaque backing so nothing behind bleeds through
        MWStyle.fillF(graphics, boxX, boxY, boxX + boxW, boxY + boxH, MWStyle.withAlpha(0xFF101114, anim));
        MWStyle.fillF(graphics, boxX, boxY, boxX + boxW, boxY + 2, MWStyle.withAlpha(MWStyle.accent(), anim));
        MWStyle.hairline(graphics, boxX, boxY, boxW, boxH, MWStyle.withAlpha(MWStyle.borderActive(), anim));

        // header
        String title = MWStyle.tr("screen.mwloadout.blueprints").toUpperCase(Locale.ROOT);
        bpText(graphics, anim, title, boxX + BP_PAD, boxY + 9, MWStyle.withAlpha(MWStyle.accent(), anim));
        String countText = blueprintOptions.size() + " \u2022 ESC";
        bpText(graphics, anim, countText, boxX + boxW - BP_PAD - font.width(countText), boxY + 9,
                MWStyle.withAlpha(MWStyle.TEXT_DIM, anim));
        MWStyle.fillF(graphics, boxX + BP_PAD, boxY + BP_HEADER - 3, boxX + boxW - BP_PAD, boxY + BP_HEADER - 2,
                MWStyle.withAlpha(MWStyle.border(), anim));

        if (overflow > 0) {
            if (blueprintScroll > 0) {
                bpText(graphics, anim, "\u25c0", stripX - 12, stripY + cardH / 2 - 4, MWStyle.withAlpha(MWStyle.TEXT, anim));
            }
            if (blueprintScroll < overflow) {
                bpText(graphics, anim, "\u25b6", stripX + stripW + 5, stripY + cardH / 2 - 4, MWStyle.withAlpha(MWStyle.TEXT, anim));
            }
        }

        // Each card shows the gun like TACZ's own weapon HUD (pack silhouette via GunHud, item-icon
        // fallback) with a name strip along the bottom.
        graphics.enableScissor(stripX, stripY, stripX + stripW, stripY + cardH);
        int cardX = stripX - Math.round(blueprintScrollAnim * (cardW + gap));
        int hudBoxW = cardW - 12;
        int hudBoxH = Math.max(12, cardH - 24);
        CatalogEntry hoveredOption = null;
        for (CatalogEntry option : blueprintOptions) {
            if (cardX + cardW >= stripX && cardX <= stripX + stripW) {
                boolean current = option.id.equals(gunEntry.id);
                boolean hovered = anim > 0.9f && mouseX >= Math.max(cardX, stripX) && mouseX < Math.min(cardX + cardW, stripX + stripW)
                        && mouseY >= stripY && mouseY < stripY + cardH;
                int bg = current ? MWStyle.withAlpha(MWStyle.PANEL_HOVER, anim)
                        : hovered ? MWStyle.withAlpha(MWStyle.PANEL_RAISED, anim)
                        : MWStyle.withAlpha(MWStyle.PANEL, anim);
                MWStyle.fillF(graphics, cardX, stripY, cardX + cardW, stripY + cardH, bg);
                MWStyle.hairline(graphics, cardX, stripY, cardW, cardH,
                        MWStyle.withAlpha(current || hovered ? MWStyle.accent() : MWStyle.border(), anim));
                if (hovered) {
                    hoveredOption = option;
                }
                GunHud.render(graphics, option, cardX + 6, stripY + 6, hudBoxW, hudBoxH, anim);
                String fullName = DisplayNames.of(option);
                if (font.width(fullName) <= cardW - 8 || anim < 0.9f) {
                    String name = MWStyle.ellipsize(font, fullName, cardW - 8);
                    bpText(graphics, anim, name, cardX + cardW / 2 - font.width(name) / 2, stripY + cardH - 11,
                            MWStyle.withAlpha(current ? MWStyle.accent() : MWStyle.TEXT, anim));
                } else {
                    // too long for the card: slide it while hovered instead of chopping it
                    MWStyle.scrollText(graphics, font, fullName, cardX + 4, stripY + cardH - 11, cardW - 8,
                            current ? MWStyle.accent() : MWStyle.TEXT, hovered);
                }
            }
            cardX += cardW + gap;
        }
        graphics.disableScissor();

        // footer: compare readout (inside the box, fixed position)
        int footY = stripY + cardH + 6;
        MWStyle.fillF(graphics, boxX + BP_PAD, footY - 3, boxX + boxW - BP_PAD, footY - 2, MWStyle.withAlpha(MWStyle.border(), anim));
        if (hoveredOption != null && !hoveredOption.id.equals(gunEntry.id)) {
            renderBlueprintCompare(graphics, hoveredOption, boxX + BP_PAD, footY, boxW - 2 * BP_PAD, anim);
        } else {
            String hint = MWStyle.tr("screen.mwloadout.blueprint_hint");
            bpText(graphics, anim, font.plainSubstrByWidth(hint, boxW - 2 * BP_PAD),
                    boxX + boxW / 2 - Math.min(font.width(hint), boxW - 2 * BP_PAD) / 2, footY + BP_FOOTER / 2 - 4,
                    MWStyle.withAlpha(MWStyle.TEXT_DIM, anim));
        }
        graphics.pose().popPose();
    }

    /** Stock-vs-stock comparison of a hovered Blueprint against the open gun, from the REAL TACZ
     *  numbers (TaczStats.rawStats: damage, RPM, effective range, bullet speed, recoil, ...) shown
     *  as a percentage change relative to the current gun. Colour follows "better/worse": for
     *  inaccuracy / weight / recoil a LOWER raw number is the improvement. Bars that neither gun
     *  has (explosive / incendiary) are skipped. Compact grid inside the modal footer. */
    private void renderBlueprintCompare(GuiGraphics graphics, CatalogEntry hovered, int x, int y, int w, float anim) {
        // two bare-gun stat builds: only when the hovered gun changes, not on every frame the modal is open
        if (compareFor == null || !compareFor.equals(hovered.gameId)) {
            compareFor = hovered.gameId;
            compareCurrent = TaczStats.rawStats(gunEntry.gameId, java.util.Map.of());
            compareOther = TaczStats.rawStats(hovered.gameId, java.util.Map.of());
        }
        float[] current = compareCurrent;
        float[] other = compareOther;
        bpText(graphics, anim, font.plainSubstrByWidth(DisplayNames.of(hovered), w),
                x, y + 2, MWStyle.withAlpha(MWStyle.accent(), anim));
        int cols = 3;
        int colW = w / cols;
        int lineH = 10;
        int shown = 0;
        for (int i : TaczStats.BAR_ORDER) {
            if (i >= 7 && !TaczStats.barRelevant(i, current, other)) {
                continue;
            }
            float before = current[i], after = other[i];
            String value;
            int color;
            if (before <= 0.0001f && after <= 0.0001f) {
                value = "-";
                color = MWStyle.TEXT_DIM;
            } else if (before <= 0.0001f) {
                value = "NEW";
                color = MWStyle.POSITIVE;
            } else {
                int pct = Math.round((after - before) / before * 100f);
                boolean better = TaczStats.LOWER_IS_BETTER[i] ? pct < 0 : pct > 0;
                value = pct == 0 ? "=" : (pct > 0 ? "+" : "") + pct + "%";
                color = pct == 0 ? MWStyle.TEXT_DIM : better ? MWStyle.POSITIVE : MWStyle.NEGATIVE;
            }
            int cx = x + (shown % cols) * colW;
            int cy = y + 13 + (shown / cols) * lineH;
            bpText(graphics, anim, TaczStats.BAR_LABELS[i], cx, cy, MWStyle.withAlpha(MWStyle.TEXT_DIM, anim));
            bpText(graphics, anim, value, cx + colW - 8 - font.width(value), cy, MWStyle.withAlpha(color, anim));
            shown++;
        }
    }

    /** Right-click context menu for a build tab. Just DELETE for now, guarded so the last
     *  remaining build can never be removed — a gun always needs at least one build slot to have
     *  an active one at all. */
    private void renderBuildContextMenu(GuiGraphics graphics) {
        boolean canDelete = builds.builds.size() > 1;
        int menuW = Math.max(90, font.width(Component.translatable("screen.mwloadout.delete_build")) + 16);
        int rowH = 18;
        int menuH = rowH + 6;
        int menuX = Math.min(buildContextMenuX, width - menuW - 6);
        int menuY = Math.min(buildContextMenuY, height - menuH - 6);
        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, 300f);
        MWStyle.fillF(graphics, menuX, menuY, menuX + menuW, menuY + menuH, MWStyle.PANEL_RAISED);
        MWStyle.hairline(graphics, menuX, menuY, menuW, menuH, MWStyle.borderActive());
        int color = canDelete ? MWStyle.NEGATIVE : MWStyle.TEXT_DIM;
        MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout.delete_build"),
                menuX + 8, menuY + 5, color, false);
        graphics.pose().popPose();
    }

    private void drawRow(GuiGraphics graphics, int rowIndex, int rowY, String name, String attachmentId, boolean selected,
                          int requiredLevel, int weaponLevel) {
        if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) {
            return;
        }
        boolean locked = requiredLevel > weaponLevel;
        boolean hovered = rowIndex == hoveredRow;
        // MW-style rows: hovered = accent wash, equipped = gold wash with a gold bar and an ACTIVE tag
        int rowFill = selected ? MWStyle.withAlpha(MWStyle.signal(), hovered ? 0.26f : 0.18f)
                : hovered ? MWStyle.withAlpha(MWStyle.accent(), 0.28f) : MWStyle.PANEL_RAISED;
        MWStyle.fillF(graphics, listX, rowY, listRight, rowY + ROW_HEIGHT - 2, rowFill);
        if (hovered && !selected) {
            MWStyle.hairline(graphics, listX, rowY, listRight - listX, ROW_HEIGHT - 2, MWStyle.withAlpha(MWStyle.accent(), 0.7f));
        }
        if (selected) {
            MWStyle.fillF(graphics, listX, rowY, listX + 2, rowY + ROW_HEIGHT - 2, MWStyle.signal());
            String tag = "ACTIVE";
            MWStyle.strF(graphics, font, tag, listRight - font.width(tag) - 6, rowY + 8, MWStyle.signal(), false);
        }
        int textX = listX + 8;
        if (attachmentId != null) {
            drawFadedIcon(graphics, attachmentId, listX + 5, rowY + 4, MWStyle.uiAlpha());
            textX = listX + 26;
        }
        // weapon-level gating (see Rules.weaponProgressionEnabled) — a locked candidate
        // still shows (so the player knows it EXISTS and what unlocks it), just greyed and
        // suffixed with the level requirement, same visual language the budget-limit lock
        // (MWStyle.LOCKED) already uses on the old type rail.
        int nameColor = locked ? MWStyle.LOCKED : selected ? MWStyle.signal() : MWStyle.TEXT;
        String suffix = locked ? "  LV " + requiredLevel : selected ? "  ACTIVE" : "";
        int suffixWidth = suffix.isEmpty() ? 0 : font.width(suffix) + 2;
        MWStyle.scrollText(graphics, font, name, textX, rowY + 8, listRight - textX - 6 - suffixWidth, nameColor, hovered || selected);
        if (locked && !suffix.isEmpty()) {
            MWStyle.strF(graphics, font, suffix, listRight - suffixWidth - 2, rowY + 8, MWStyle.LOCKED, false);
        }
    }

    /** Right column, MW19 order: rotating model with the live build mounted, four refit-style
     *  stat bars with hovered-attachment delta segments, then the raw modifier lines. */
    /** MW19 hotspot boxes — one per attachment type, positioned in the ring computed by
     *  computeHotspotZones(). Each shows the type name (dim) + current attachment (bright, or
     *  "None"), a filled/empty selector dot, and a thin line pointing toward the weapon stage to
     *  read as "this box controls that part of the gun" even though the anchor isn't a true
     *  per-model anatomical point (see computeHotspotZones' doc for why). */
    private void renderHotspotZones(GuiGraphics graphics, int mouseX, int mouseY, boolean atLimit) {
        float opened = MWStyle.openProgress(openedAt);
        float hub = MWStyle.uiAlphaLocal();
        for (int i = 0; i < types.size(); i++) {
            AttachmentType type = types.get(i);
            int[] zone = hotspotZones.get(typeZone.get(i));
            // staggered entrance: cards fade in one after another, rising a few px
            float enter = 1f; // all cards appear together (no staggered fade: their icons could not follow it)
            MWStyle.localAlpha(hub * enter);
            int zx = zone[0], zy = zone[1] + Math.round((1f - enter) * 8f), zw = zone[2], zh = zone[3];
            boolean hovered = mouseX >= zx && mouseX < zx + zw && mouseY >= zy && mouseY < zy + zh;
            boolean lockedByPoints = atLimit && !builds.activeBuild().containsKey(type.name());
            String equippedId = builds.activeBuild().get(type.name());
            boolean filled = equippedId != null && !equippedId.isEmpty();
            String typeLabel = dev.l41nx.mwloadout.MWSlots.label(type);

            if (MWStyle.angular()) {
                // slim cut-corner tag: filled = signal-colour outline + check badge, empty = grey outline with a plus
                int c = MWStyle.cut(zw, zh, 5);
                int fill = filled ? MWStyle.withAlpha(MWStyle.signal(), hovered ? 0.20f : 0.10f)
                        : MWStyle.withAlpha(0xFF0E0F12, hovered ? 0.82f : 0.58f);
                int frame = hovered ? (filled ? MWStyle.signal() : MWStyle.borderActive()) : filled ? MWStyle.signal() : MWStyle.border();
                MWStyle.plateFill(graphics, true, zx, zy, zw, zh, c, fill);
                MWStyle.plateEdge(graphics, true, zx, zy, zw, zh, c, frame);
                if (filled) {
                    int bx = zx - 3, by = zy - 4;
                    MWStyle.fillF(graphics, bx, by, bx + 9, by + 9, MWStyle.signal());
                    int[][] tick = {{1, 4}, {2, 5}, {3, 6}, {4, 5}, {5, 4}, {6, 3}, {7, 2}};
                    for (int[] t : tick) {
                        MWStyle.fillF(graphics, bx + t[0], by + t[1], bx + t[0] + 1, by + t[1] + 1, 0xFF101114);
                    }
                }
            } else {
                // classic: filled = warm gold tint, empty = quiet translucent; hover adds an accent edge + soft glow
                int body = filled ? MWStyle.withAlpha(MWStyle.signal(), hovered ? 0.20f : 0.12f)
                        : MWStyle.withAlpha(0xFF0E0F12, hovered ? 0.80f : 0.55f);
                MWStyle.fillF(graphics, zx, zy, zx + zw, zy + zh, body);
                if (hovered) {
                    for (int k = 1; k <= 3; k++) {
                        int ring = MWStyle.withAlpha(filled ? MWStyle.signal() : MWStyle.accent(), 0.16f * (4 - k) / 3f);
                        MWStyle.fillF(graphics, zx - k, zy - k, zx + zw + k, zy - k + 1, ring);
                        MWStyle.fillF(graphics, zx - k, zy + zh + k - 1, zx + zw + k, zy + zh + k, ring);
                        MWStyle.fillF(graphics, zx - k, zy - k + 1, zx - k + 1, zy + zh + k - 1, ring);
                        MWStyle.fillF(graphics, zx + zw + k - 1, zy - k + 1, zx + zw + k, zy + zh + k - 1, ring);
                    }
                }
                MWStyle.hairline(graphics, zx, zy, zw, zh,
                        hovered ? (filled ? MWStyle.signal() : MWStyle.borderActive())
                                : filled ? MWStyle.withAlpha(MWStyle.signal(), 0.55f) : MWStyle.border());
                MWStyle.fillF(graphics, zx, zy, zx + 2, zy + zh, filled ? MWStyle.signal() : MWStyle.withAlpha(MWStyle.border(), 0.8f));
            }

            int textX = zx + 8;
            if (filled) {
                // thumbnail slot, name in gold, slot name beneath it (no check badge any more)
                MWStyle.fillF(graphics, zx + 6, zy + (zh - 20) / 2, zx + 26, zy + (zh + 20) / 2, MWStyle.withAlpha(0xFF000000, 0.30f));
                drawFadedIcon(graphics, equippedId, zx + 8, zy + (zh - 16) / 2, hub * enter * MWStyle.uiAlpha());
                textX = zx + 32;
                MWStyle.strF(graphics, font, font.plainSubstrByWidth(cleanName(equippedId), zw - (textX - zx) - 6),
                        textX, zy + Math.max(3, (zh - 18) / 2), MWStyle.signal(), false);
                MWStyle.strF(graphics, font, typeLabel, textX, zy + Math.max(3, (zh - 18) / 2) + 10, lockedByPoints ? MWStyle.LOCKED : MWStyle.TEXT, false);
            } else {
                MWStyle.strF(graphics, font, "+", zx + 10, zy + (zh - 8) / 2, lockedByPoints ? MWStyle.LOCKED : MWStyle.TEXT_DIM, false);
                MWStyle.strF(graphics, font, typeLabel, zx + 24, zy + (zh - 8) / 2,
                        lockedByPoints ? MWStyle.LOCKED : hovered ? MWStyle.TEXT : MWStyle.TEXT_DIM, false);
            }
        }
        MWStyle.localAlpha(hub);
    }

    private static final java.util.Set<String> BAD_ICONS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Attachment name without colour/format codes (some packs ship names like "§cIncendiary Upgrade", which drew red). */
    private static String cleanName(String attachmentId) {
        String name = ChatFormatting.stripFormatting(DisplayNames.ofAttachment(attachmentId));
        return name == null ? "" : name;
    }

    /** Item icon that can never take the whole game down: unknown / failing attachments are skipped (and remembered),
     *  and if the item renderer throws halfway the leaked pose push is unwound. */
    private void drawAttachmentIcon(GuiGraphics graphics, String attachmentId, int x, int y) {
        if (attachmentId == null || attachmentId.isEmpty() || BAD_ICONS.contains(attachmentId)) {
            return;
        }
        com.mojang.blaze3d.vertex.PoseStack pose = graphics.pose();
        org.joml.Matrix4f before = new org.joml.Matrix4f(pose.last().pose());
        try {
            if (TimelessAPI.getCommonAttachmentIndex(new net.minecraft.resources.ResourceLocation(attachmentId)).isEmpty()) {
                BAD_ICONS.add(attachmentId);
                return;
            }
            ItemStack icon = IconCache.attachmentIcon(attachmentId);
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x, y);
            }
        } catch (Throwable t) {
            BAD_ICONS.add(attachmentId);
            for (int i = 0; i < 4 && !pose.last().pose().equals(before); i++) {
                pose.popPose();
            }
        }
    }

    /** Item icons cannot be alpha-tinted, so fade them with a veil over the icon instead of switching them on/off at a threshold. */
    private void drawFadedIcon(GuiGraphics graphics, String attachmentId, int x, int y, float alpha) {
        if (alpha < 0.04f) {
            return;
        }
        drawAttachmentIcon(graphics, attachmentId, x, y);
        if (alpha < 0.98f) {
            graphics.fill(x - 1, y - 1, x + 17, y + 17, MWStyle.withAlpha(0xFF0E0F12, (1f - alpha) * 0.92f));
        }
    }

    /** Select view, right side: big name of the hovered (else equipped) attachment, then PROS / CONS in two columns. */
    private void renderSelectInfo(GuiGraphics graphics) {
        if (selectAnim < 0.02f) {
            return;
        }
        String id = null;
        if (hoveredRow >= 0 && hoveredRow < candidates.size()) {
            id = candidates.get(hoveredRow).id;
        } else {
            String equipped = builds.activeBuild().get(activeType.name());
            if (equipped != null && !equipped.isEmpty()) {
                id = equipped;
            }
        }
        int x0 = listRight + 44;
        int y0 = 74 + Math.round((1f - selectAnim) * 10f);
        String title = id != null ? cleanName(id).toUpperCase(Locale.ROOT)
                : dev.l41nx.mwloadout.MWSlots.label(activeType);
        graphics.pose().pushPose();
        graphics.pose().translate(x0, y0, 0);
        graphics.pose().scale(2f, 2f, 1f);
        MWStyle.strF(graphics, font, font.plainSubstrByWidth(title, Math.max(40, (width - x0 - 20) / 2)), 0, 0, MWStyle.TEXT, false);
        graphics.pose().popPose();
        if (id == null) {
            MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.none"), x0, y0 + 24, MWStyle.TEXT_DIM, false);
            return;
        }
        // the attachment's own description (TACZ index "tooltip"), wrapped under the name
        int descY = y0 + 26;
        String tipKey = TaczStats.attachmentTooltipKey(id);
        if (!tipKey.isEmpty() && net.minecraft.client.resources.language.I18n.exists(tipKey)) {
            int shown = 0;
            for (var wrapped : font.split(Component.literal(net.minecraft.client.resources.language.I18n.get(tipKey))
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY), Math.max(120, width - x0 - 24))) {
                if (shown++ >= 4) {
                    break;
                }
                MWStyle.strF(graphics, font, wrapped, x0, descY, MWStyle.TEXT_DIM, false);
                descY += 10;
            }
            descY += 4;
        }
        List<String> pros = new ArrayList<>();
        List<String> cons = new ArrayList<>();
        for (TaczStats.Line line : TaczStats.attachmentProsCons(id)) {
            String text = line.text();
            if (text.length() < 2) {
                continue;
            }
            (text.startsWith("+") ? pros : cons).add(text.substring(1).trim());
        }
        int colW = Math.max(90, Math.min(150, (width - x0 - 24) / 2));
        int py = descY + 2;
        MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.pros"), x0, py, MWStyle.TEXT, false);
        MWStyle.fillF(graphics, x0, py + 10, x0 + colW - 8, py + 11, MWStyle.POSITIVE);
        MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.cons"), x0 + colW, py, MWStyle.TEXT, false);
        MWStyle.fillF(graphics, x0 + colW, py + 10, x0 + colW * 2 - 8, py + 11, MWStyle.NEGATIVE);
        int ly = py + 16;
        for (String pro : pros) {
            MWStyle.strF(graphics, font, "+ " + font.plainSubstrByWidth(pro, colW - 20), x0, ly, MWStyle.POSITIVE, false);
            ly += 10;
        }
        int ry = py + 16;
        for (String con : cons) {
            MWStyle.strF(graphics, font, "- " + font.plainSubstrByWidth(con, colW - 20), x0 + colW, ry, MWStyle.NEGATIVE, false);
            ry += 10;
        }
        if (pros.isEmpty() && cons.isEmpty()) {
            MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.no_stats"), x0, ly, MWStyle.TEXT_DIM, false);
        }
    }

    /** Rough gun length in metres by class - only used to size the horizontal model so the WHOLE gun fits. */
    static float lengthFactor(String category) {
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (c.contains("pistol") || c.contains("revolver")) {
            return 0.30f;
        }
        if (c.contains("smg")) {
            return 0.55f;
        }
        if (c.contains("shotgun")) {
            return 0.95f;
        }
        if (c.contains("lmg") || c.contains("mg")) {
            return 1.0f;
        }
        if (c.contains("snipe")) {
            return 1.3f;
        }
        if (c.contains("rpg") || c.contains("launcher")) {
            return 1.05f;
        }
        if (c.contains("dmr") || c.contains("marksman")) {
            return 1.1f;
        }
        return 0.9f; // rifles
    }

    /** Draws the horizontal, drag-rotatable gun and (in the select view) eases the camera to the attachment position. */
    private final java.util.Map<String, float[]> refitCache = new java.util.HashMap<>();

    /** Position of an attachment slot on the gun, read from the model's REFIT VIEW nodes (the nodes TACZ's GunRefitScreen
     *  zooms its camera to): {fraction along the gun toward the muzzle, fraction down, zoom}. Null when the model does not
     *  define the node - the caller then falls back to the fixed per-type guess. */
    private float[] refitFocus(AttachmentType type, float len) {
        String key = gunEntry.gameId + "|" + type.name() + "|" + len;
        if (refitCache.containsKey(key)) {
            return refitCache.get(key);
        }
        float[] result = null;
        try {
            var model = TimelessAPI.getClientGunIndex(new net.minecraft.resources.ResourceLocation(gunEntry.gameId))
                    .map(index -> index.getDefaultDisplay()).map(display -> display.getGunModel()).orElse(null);
            if (model != null) {
                List<?> path = model.getRefitAttachmentViewPath(type);
                if (path != null && !path.isEmpty()) {
                    org.joml.Vector3f origin = nodePos(model.getFixedOriginPath());
                    org.joml.Vector3f muzzle = nodePos(model.getMuzzleFlashPosPath());
                    org.joml.Vector3f node = nodePos(path);
                    org.joml.Vector3f forward = new org.joml.Vector3f(muzzle).sub(origin);
                    float unit = forward.length() > 3f ? 1f / 16f : 1f; // part coordinates in pixels vs blocks
                    if (forward.lengthSquared() < 1.0E-4f) {
                        forward.set(0f, 0f, -1f);
                    }
                    forward.normalize();
                    org.joml.Vector3f rel = new org.joml.Vector3f(node).sub(origin).mul(unit);
                    // which way is "up" in this model's coordinates: the scope must come out ABOVE the barrel line
                    float ySign = 1f;
                    List<?> scopePath = model.getRefitAttachmentViewPath(AttachmentType.SCOPE);
                    if (scopePath != null && !scopePath.isEmpty() && (nodePos(scopePath).y - origin.y) > 0f) {
                        ySign = -1f;
                    }
                    float fx = Mth.clamp(rel.dot(forward) / len, -0.6f, 0.6f);
                    float fy = Mth.clamp(rel.y * ySign / len, -0.4f, 0.4f);
                    result = new float[]{fx, fy, 1.7f};
                }
            }
        } catch (Throwable ignored) {
            result = null;
        }
        refitCache.put(key, result);
        return result;
    }

    private static org.joml.Vector3f nodePos(List<?> path) {
        com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
        if (path != null) {
            for (Object part : path) {
                if (part instanceof com.tacz.guns.client.model.bedrock.BedrockPart bedrockPart) {
                    bedrockPart.translateAndRotateAndScale(pose);
                }
            }
        }
        return pose.last().pose().transformPosition(new org.joml.Vector3f());
    }

    private void renderGunStage(float previewEase) {
        layoutMuzzleLeft = preview.isRefitActive() ? preview.refitMuzzleLeft() : muzzleLeft;
        computeHotspotZones();
        java.util.Map<String, String> shownBuild = builds.activeBuild();
        if (cinematic && listOpen && activeType != null && hoveredRow >= 0 && hoveredRow < candidates.size()
                && !candidates.get(hoveredRow).locked(currentWeaponLevel())) {
            // hovering a candidate previews it ATTACHED on the model (the real build is untouched until it is clicked)
            shownBuild = new java.util.HashMap<>(shownBuild);
            shownBuild.put(activeType.name(), candidates.get(hoveredRow).id);
        }
        preview.update(gunEntry.gameId, shownBuild);
        preview.setSideView(muzzleLeft ? 180f : 0f);            // only used when the model has no refit views
        preview.setRefitView(cinematic && listOpen && activeType != null ? activeType : AttachmentType.NONE);
        preview.setLengthHint(lengthFactor(gunEntry.category));
        int hubX = stageX, hubY = stageY, hubW = stageW, hubH = stageH;
        int selX = Math.min(width - 160, listRight + 30), selY = 120, selW = Math.max(160, width - selX - 10), selH = Math.max(100, height - selY - 20);
        float k = MWStyle.easeOutCubic(selectAnim);
        previewX = Math.round(hubX + (selX - hubX) * k);
        previewY = Math.round(hubY + (selY - hubY) * k);
        previewW = Math.round(hubW + (selW - hubW) * k);
        previewH = Math.round(hubH + (selH - hubH) * k);
        if (previewEase > 0f) { // PREVIEW: the stage grows from its hub box to the whole window (and shrinks back on exit)
            previewX = Math.round(previewX * (1f - previewEase));
            previewY = Math.round(previewY * (1f - previewEase));
            previewW = Math.round(previewW + (width - previewW) * previewEase);
            previewH = Math.round(previewH + (height - previewH) * previewEase);
        }
        float len = lengthFactor(gunEntry.category);
        float scale = Mth.clamp(Math.min(previewW * 0.90f / (3.0f * len), previewH * 1.0f), 36f, 170f + 60f * previewEase);
        float dir = layoutMuzzleLeft ? -1f : 1f; // screen direction of the muzzle
        float fx = 0f, fy = 0f, fz = 1f;
        float[] refit = !preview.isRefitActive() && cinematic && listOpen && activeType != null ? refitFocus(activeType, len) : null;
        if (refit != null) {
            // real attachment position taken from the gun model's own refit view nodes (what TACZ's refit screen zooms to)
            fx = refit[0] * dir;
            fy = refit[1];
            fz = refit[2];
        } else if (!preview.isRefitActive() && cinematic && listOpen && activeType != null) {
            switch (activeType) {
                case MUZZLE -> { fx = 0.40f * dir; fy = -0.02f; fz = 1.9f; }
                case STOCK -> { fx = -0.38f * dir; fy = 0.02f; fz = 1.8f; }
                case SCOPE -> { fx = 0.02f * dir; fy = -0.12f; fz = 1.8f; }
                case GRIP -> { fx = 0.18f * dir; fy = 0.14f; fz = 1.9f; }
                case LASER -> { fx = 0.26f * dir; fy = 0.07f; fz = 1.9f; }
                case EXTENDED_MAG -> { fx = -0.03f * dir; fy = 0.20f; fz = 1.7f; }
                default -> { }
            }
        }
        preview.setFocus(fx, fy, fz);
        preview.render(previewX, previewY, previewW, previewH, scale, 3.0f * len * scale);
    }

    private static final int ADV_BUTTON = 16;
    private int advButtonX, advButtonY;

    /** Small square "Advanced stats" button: three bars and a line = the chart box it opens. Sits at the right edge of the stat block. */
    private long advShownAt = System.currentTimeMillis();

    private void renderAdvancedButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y) {
        advButtonX = x;
        advButtonY = y;
        AdvancedStatsPanel.drawButton(graphics, x, y, ADV_BUTTON, mouseX, mouseY, advShownAt);
    }

    private long buildDirtyAt;

    /** Every change to the build is pushed to the server shortly after (debounced, so rapid clicks are one packet): the server saves it
     *  AND re-installs it on the gun the player is carrying, so the gun changes the moment the attachment does. */
    private void markBuildDirty() {
        buildDirtyAt = System.currentTimeMillis();
    }

    private void pumpBuildSync() {
        if (buildDirtyAt != 0 && System.currentTimeMillis() - buildDirtyAt > 350) {
            buildDirtyAt = 0;
            selection.attachments.clear();
            selection.attachments.putAll(builds.activeBuild());
            Net.CHANNEL.sendToServer(new Net.C2SSaveGunBuild(gunEntry.id, builds.active, Json.GSON.toJson(builds.toJson())));
        }
    }

    private TaczStats.AdvancedData advCache;
    private String advCacheKey = "";

    /** advancedData builds a gun stack and walks the attachment pipeline: once per build, not once per frame under the cursor. */
    private TaczStats.AdvancedData advancedCached() {
        String key = String.valueOf(builds.activeBuild());
        if (advCache == null || !key.equals(advCacheKey)) {
            advCache = TaczStats.advancedData(gunEntry.gameId, builds.activeBuild());
            advCacheKey = key;
        }
        return advCache;
    }

    /** Hover tooltip for a stat bar: the exact figure, the bare weapon's figure, the change, plus the stat's own breakdown. */
    private List<Component> barDetail(int index, float[] raw, float[] stockRaw) {
        return AdvancedStatsPanel.statTooltip(index, raw, stockRaw, "Stock", advancedCached());
    }

    private void renderStatsPanel(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderAdvancedButton(graphics, mouseX, mouseY, STATS_X + STATS_W + 6, height - 14 - ADV_BUTTON);
        if (!cinematic) {
            preview.update(gunEntry.gameId, builds.activeBuild()); // (cinematic feeds the preview itself, hover preview included)
        }
        if (cinematic) {
            // model stage is the WHOLE screen now (hotspot boxes float on top of it, not
            // beside a list column) — centred, leaving margin top/bottom for the title/tabs and
            // the bottom-left stat block + Equipped indicator (image-1 layout: those two stack in
            // the bottom-left corner, gun fills the rest).
            int panelW = STATS_W;
            int panelH = STATS_H;
            int panelX = STATS_X;
            int panelY = height - panelH - 14;
            // weapon-level readout (MW's real leveling mechanic) — only shown when the
            // admin has actually turned it on; sits directly above "Equipped" so both progress
            // readouts (attachment budget, weapon level) stack together bottom-left.
            var weaponRules = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules();
            if (weaponRules.weaponProgressionEnabled) {
                dev.l41nx.mwloadout.data.PlayerProfile wProfile = dev.l41nx.mwloadout.client.ClientCache.profile();
                if (wProfile != null) {
                    int wLevel = wProfile.weaponLevel(gunEntry.gameId, weaponRules.weaponXpPerLevel, weaponRules.weaponXpCurveGrowth);
                    float wProgress = wProfile.weaponLevelProgress(gunEntry.gameId, weaponRules.weaponXpPerLevel, weaponRules.weaponXpCurveGrowth);
                    String wLabel = Component.translatable("screen.mwloadout.weapon_level", wLevel).getString();
                    MWStyle.strF(graphics, font, wLabel, panelX, panelY - 28, MWStyle.accent(), false);
                    int barW = 140;
                    int barX = panelX + font.width(wLabel) + 8;
                    MWStyle.fillF(graphics, barX, panelY - 27, barX + barW, panelY - 24, MWStyle.PANEL_RAISED);
                    MWStyle.fillF(graphics, barX, panelY - 27, barX + (int) (barW * wProgress), panelY - 24, MWStyle.signal());
                }
            }

            // "Equipped N/5" + dot row (MW19 reference, bottom-left above the stat bars).
            int used = realAttachmentCount();
            var eqRules = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules();
            int cap = eqRules.attachmentPointLimit > 0 ? eqRules.maxAttachments() : 5;
            String eqLabel = "ATTACHMENTS " + used + "/" + cap;
            MWStyle.strF(graphics, font, eqLabel, panelX, panelY - 16, MWStyle.TEXT_DIM, false);
            int dotX = panelX + font.width(eqLabel) + 10;
            for (int i = 0; i < cap; i++) {
                if (i < used) {
                    MWStyle.fillF(graphics, dotX, panelY - 15, dotX + 7, panelY - 8, MWStyle.signal());
                } else {
                    MWStyle.hairline(graphics, dotX, panelY - 15, 7, 7, MWStyle.withAlpha(MWStyle.signal(), 0.45f));
                }
                dotX += 10;
            }

            // no frame: the stat rows sit straight on the stage like the MW reference
            graphics.enableScissor(panelX, panelY - 2, panelX + panelW + 4, panelY + panelH + 4);
            int rowsEnd = drawStatBars(graphics, panelX, panelY, 196, mouseX, mouseY, partialTick, true);
            // second column: FIRE RATE / MAGAZINE
            int colX = panelX + 206;
            int rpm = Math.round(cachedRawStats[TaczStats.IDX_RPM]);
            MWStyle.strF(graphics, font, "FIRE RATE", colX, panelY, MWStyle.TEXT_DIM, false);
            MWStyle.strF(graphics, font, rpm + " RPM", colX, panelY + 10, MWStyle.TEXT, false);
            int mag = cachedMagSize > 0 ? cachedMagSize : baseMagSize;
            if (mag > 0) {
                MWStyle.strF(graphics, font, "MAGAZINE", colX, panelY + 28, MWStyle.TEXT_DIM, false);
                MWStyle.strF(graphics, font, String.valueOf(mag), colX, panelY + 38, mag > baseMagSize ? MWStyle.POSITIVE : MWStyle.TEXT, false);
                if (cachedReserve > 0) {
                    MWStyle.strF(graphics, font, "RESERVE", colX, panelY + 56, MWStyle.TEXT_DIM, false);
                    MWStyle.strF(graphics, font, String.valueOf(cachedReserve), colX, panelY + 66, MWStyle.TEXT, false);
                }
            }
            graphics.disableScissor();
            return;
        }
        int panelX = width - STATS_WIDTH - 10;
        MWStyle.panel(graphics, panelX, listTop, STATS_WIDTH, listBottom - listTop);
        int previewHeight = Math.min(84, (listBottom - listTop) * 2 / 5);
        previewX = panelX + 2;
        previewY = listTop + 2;
        previewW = STATS_WIDTH - 4;
        previewH = previewHeight;
        preview.render(previewX, previewY, previewW, previewH, 52f);
        MWStyle.fillF(graphics, panelX + 4, listTop + previewHeight + 2, panelX + STATS_WIDTH - 4, listTop + previewHeight + 3, MWStyle.border());
        int textY = listTop + previewHeight + 7;
        textY = drawStatBars(graphics, panelX + 6, textY, STATS_WIDTH - 12, mouseX, mouseY, partialTick);
    }

    /** The six MW19 bars, refit-diagram style: current-build value as the base fill, and while
     *  hovering a candidate the WOULD-BE value's difference rides on top as a green (gain) or
     *  red (loss) segment — computed by actually re-running the stat vector with the hovered
     *  attachment swapped into its type slot, so cross-effects (weight from a heavy scope
     *  dropping MOBILITY) show on the right bars automatically. All vectors cached by key.
     *  the base/delta fills now EASE toward their target instead of snapping — swapping
     *  an attachment or moving the mouse onto a new candidate reads as the bar actually reacting,
     *  not a static number table redrawing itself. */
    private int drawStatBars(GuiGraphics graphics, int x, int y, int barWidth, int mouseX, int mouseY, float partialTick) {
        return drawStatBars(graphics, x, y, barWidth, mouseX, mouseY, partialTick, false);
    }

    private int drawStatBars(GuiGraphics graphics, int x, int y, int barWidth, int mouseX, int mouseY, float partialTick, boolean inline) {
        int buildKey = builds.activeBuild().hashCode();
        if (buildKey != cachedBarsKey) {
            cachedBarsKey = buildKey;
            cachedRawStats = TaczStats.rawStats(gunEntry.gameId, builds.activeBuild());
            cachedBars = TaczStats.barsFromRaw(cachedRawStats);
            // MAGAZINE / RESERVE of THIS build - the panel used to print the stock magazine whatever was fitted
            cachedMagSize = dev.l41nx.mwloadout.server.TaczStackFactory.getMagazineCapacity(gunEntry.gameId, builds.activeBuild());
            var rules = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules();
            cachedReserve = rules.dynamicAmmoEnabled && cachedMagSize > 0
                    ? Math.max(1, (int) Math.round(cachedMagSize
                            * rules.effectiveAmmoMultiplierFor(cachedMagSize, gunEntry.category, gunEntry.ammoMultiplier)))
                    : rules.reserveAmmoFor(gunEntry.gameId, gunEntry.category);
        }
        if (baseBars == null) {
            baseRaw = TaczStats.rawStats(gunEntry.gameId, java.util.Map.of()); // stock weapon
            baseBars = TaczStats.barsFromRaw(baseRaw);
        }
        float[] hoverBars = null;
        float[] hoverRaw = null;
        String hoveredId = hoveredRow >= 0 && hoveredRow < candidates.size() ? candidates.get(hoveredRow).id : null;
        if (hoveredId != null) {
            int hoverKey = buildKey * 31 + hoveredId.hashCode();
            if (hoverKey != cachedHoverKey) {
                cachedHoverKey = hoverKey;
                java.util.Map<String, String> preview = new java.util.LinkedHashMap<>(builds.activeBuild());
                preview.put(activeType.name(), hoveredId);
                cachedHoverRaw = TaczStats.rawStats(gunEntry.gameId, preview);
                cachedHoverBars = TaczStats.barsFromRaw(cachedHoverRaw);
            }
            hoverBars = cachedHoverBars;
            hoverRaw = cachedHoverRaw;
        }
        if (animBars == null) {
            // First draw this screen instance: seed instantly, nothing to animate FROM yet.
            animBars = cachedBars.clone();
            animHoverBars = cachedBars.clone();
        }
        float dt = MWStyle.frameDt(); // real wall-clock frame time (partialTick wobbles per tick)
        int hoveredBarIndex = -1;
        for (int i : TaczStats.BAR_ORDER) {
            // Optional bars (explosive / incendiary) only exist for guns/attachments that have them.
            if (!TaczStats.barRelevant(i, cachedRawStats, baseRaw, hoverRaw)) {
                continue;
            }
            float rawCurrent = cachedBars[i];                                   // current build
            float rawTarget = hoverBars != null ? hoverBars[i] : rawCurrent;    // would-be after hover
            float stock = baseBars != null ? baseBars[i] : rawCurrent;          // bare weapon reference
            animBars[i] = MWStyle.approach(animBars[i], rawCurrent, 0.4f, dt);
            animHoverBars[i] = MWStyle.approach(animHoverBars[i], rawTarget, 0.4f, dt);
            float current = animBars[i];
            float target = animHoverBars[i];
            if (inline) {
                // one compact row: LABEL | bar | value (+/- delta while hovering a candidate)
                int labelW = 62;
                int bx = x + labelW + 4;
                int bw = barWidth - labelW - 4 - 44;
                MWStyle.strF(graphics, font, font.plainSubstrByWidth(TaczStats.BAR_LABELS[i], labelW), x, y, MWStyle.TEXT_DIM, false);
                if (mouseX >= x && mouseX < x + barWidth && mouseY >= y - 1 && mouseY < y + 10) {
                    hoveredBarIndex = i;
                }
                MWStyle.fillF(graphics, bx, y + 3, bx + bw, y + 6, MWStyle.PANEL_HOVER);
                float low = Math.min(current, target);
                MWStyle.fillF(graphics, bx, y + 3, bx + (int) (bw * Math.max(0.02f, low)), y + 6, 0xFFE8EAEC);
                if (Math.abs(target - current) > 0.004f) {
                    int fromX = bx + (int) (bw * low);
                    int toX = bx + (int) (bw * Math.max(current, target));
                    MWStyle.fillF(graphics, fromX, y + 3, Math.max(fromX + 1, toX), y + 6,
                            target > current ? MWStyle.POSITIVE : MWStyle.NEGATIVE);
                }
                // the number is TACZ's own figure (a 64-damage gun reads 64), not a 0-100 score; the bar is just the visual
                String exact = TaczStats.formatStat(i, cachedRawStats[i]);
                MWStyle.strF(graphics, font, exact, bx + bw + 6, y, MWStyle.TEXT, false);
                if (hoverRaw != null && Math.abs(hoverRaw[i] - cachedRawStats[i]) > 0.0001f) {
                    float rawDelta = hoverRaw[i] - cachedRawStats[i];
                    boolean better = TaczStats.LOWER_IS_BETTER[i] ? rawDelta < 0 : rawDelta > 0;
                    MWStyle.strF(graphics, font, (rawDelta > 0 ? "+" : "") + TaczStats.formatStat(i, rawDelta),
                            bx + bw + 10 + font.width(exact), y, better ? MWStyle.POSITIVE : MWStyle.NEGATIVE, false);
                }
                y += 11;
                continue;
            }
            MWStyle.strF(graphics, font, TaczStats.BAR_LABELS[i], x, y, MWStyle.TEXT_DIM, false);
            int barY = y + 9;
            if (mouseX >= x && mouseX < x + barWidth && mouseY >= y && mouseY < barY + 5) {
                hoveredBarIndex = i;
            }
            MWStyle.fillF(graphics, x, barY, x + barWidth, barY + 3, MWStyle.PANEL_HOVER);
            // Solid fill up to the lower of current/target; delta segment in green/red above.
            float base = Math.min(current, target);
            MWStyle.fillF(graphics, x, barY, x + (int) (barWidth * Math.max(0.02f, base)), barY + 3, MWStyle.borderActive());
            if (Math.abs(target - current) > 0.004f) {
                int fromX = x + (int) (barWidth * base);
                int toX = x + (int) (barWidth * Math.max(current, target));
                MWStyle.fillF(graphics, fromX, barY, Math.max(fromX + 1, toX), barY + 3,
                        target > current ? MWStyle.POSITIVE : MWStyle.NEGATIVE);
            }
            // Stock reference tick: a bright vertical mark showing where the BARE gun sits, so
            // any attachment gain/loss reads against a fixed origin (the core ask).
            int stockX = x + (int) (barWidth * Math.max(0.02f, Math.min(1f, stock)));
            MWStyle.fillF(graphics, stockX, barY - 2, stockX + 1, barY + 5, 0xFFEDEFF2);
            y = barY + 6;
        }
        // Tiny legend so the tick isn't a mystery (compact rows have no tick, so no legend).
        if (!inline) {
            MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.stock_marker"),
                    x, y + 1, MWStyle.TEXT_DIM, false);
        }
        int endY = inline ? y : y + 11;
        // Hover a bar -> the real TACZ number behind it, not just the relative fill (the bar
        // itself is deliberately unitless/normalized so wildly different weapon types compare on
        // one scale; this is where the actual figure lives for someone who wants it).
        if (hoveredBarIndex >= 0) {
            MwScreen.deferTooltip(font, barDetail(hoveredBarIndex, cachedRawStats, baseRaw), mouseX, mouseY);
        }
        return endY;
    }



    private int rowAt(double mouseX, double mouseY) {
        if (cinematic && !listOpen) {
            return -1;
        }
        if (mouseX < listX || mouseX >= listRight || mouseY < listTop || mouseY >= rowsBottom()) {
            return -1;
        }
        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        return index >= 0 && index < candidates.size() ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (previewMode) {
            preview.zoom(delta);
            return true;
        }
        if (advanced.isOpen()) {
            return advanced.mouseScrolled(mouseX, mouseY, delta);
        }
        if (blueprintPanelOpen && !blueprintOptions.isEmpty()) {
            // modal: the wheel only ever scrolls the blueprint strip, never the list/model behind
            if (inBlueprintBox(mouseX, mouseY)) {
                int[] geo = blueprintCardGeometry();
                int overflow = Math.max(0, blueprintOptions.size() - geo[6]);
                blueprintScroll = Mth.clamp(blueprintScroll - (int) Math.signum(delta), 0, overflow);
            }
            return true;
        }
        // in cinematic mode the weapon stage now spans almost the entire screen (no more
        // fixed list column beside it), so it can't be checked first anymore without hijacking
        // scroll input meant for an open popup list sitting on top of it — check "is the popup
        // open AND is the mouse over it" first, and only fall through to the model-zoom check
        // when that's not the case. Non-cinematic mode is completely unaffected (popupShowing is
        // always false there, so this reduces to exactly the original two-check order).
        boolean popupShowing = cinematic && listOpen;
        boolean overList = popupShowing && mouseX >= listX && mouseX < listRight && mouseY >= listTop && mouseY < listBottom;
        if (!overList && mouseX >= previewX && mouseX < previewX + previewW
                && mouseY >= previewY && mouseY < previewY + previewH) {
            preview.zoom(delta);
            return true;
        }
        if (!cinematic || popupShowing) {
            int overflow = Math.max(0, contentHeight() - (rowsBottom() - listTop));
            scrollTarget = Mth.clamp(scrollTarget - delta * ROW_HEIGHT, 0, overflow);
            return true;
        }
        return true; // cinematic, no popup open, not over the model either: just eat the scroll
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAt > 0) {
            return true;
        }
        if (previewMode) { // any mouse button grabs the model; only a KEY leaves preview mode
            preview.beginDrag();
            return true;
        }
        if (previewK > 0.02f) {
            return true; // the UI is still fading back in
        }
        if (advanced.isOpen()) {
            return advanced.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && !blueprintPanelOpen && mouseX >= advButtonX && mouseX < advButtonX + ADV_BUTTON && mouseY >= advButtonY
                && mouseY < advButtonY + ADV_BUTTON) {
            MWStyle.playClick();
            advanced.open(gunEntry.gameId, DisplayNames.of(gunEntry), builds.activeBuild());
            return true;
        }
        if (buildContextMenuOpen) {
            boolean canDelete = builds.builds.size() > 1;
            int menuW = Math.max(90, font.width(Component.translatable("screen.mwloadout.delete_build")) + 16);
            int rowH = 18;
            int menuH = rowH + 6;
            int menuX = Math.min(buildContextMenuX, width - menuW - 6);
            int menuY = Math.min(buildContextMenuY, height - menuH - 6);
            if (button == 0 && mouseX >= menuX && mouseX < menuX + menuW && mouseY >= menuY + 3 && mouseY < menuY + 3 + rowH) {
                if (canDelete) {
                    builds.builds.remove(buildContextMenuIndex);
                    if (builds.active >= builds.builds.size()) {
                        builds.active = builds.builds.size() - 1;
                        markBuildDirty();
                    } else if (builds.active > buildContextMenuIndex) {
                        builds.active--;
                    }
                    MWStyle.playClick();
                }
                buildContextMenuOpen = false;
                return true;
            }
            buildContextMenuOpen = false;
            if (button != 1) {
                return true;
            }
            // fall through on a right-click so it can immediately open a menu for whatever tab
            // (or nothing) is under the cursor now, instead of eating the click and requiring a
            // second right-click.
        }
        if (blueprintPanelOpen && !blueprintOptions.isEmpty()) {
            // modal: every click is consumed here — inside the box it picks/scrolls, outside it
            // just closes the picker. Nothing underneath ever sees it.
            if (!inBlueprintBox(mouseX, mouseY)) {
                blueprintPanelOpen = false;
                MWStyle.playClick();
                return true;
            }
            if (button == 0) {
                int[] geo = blueprintCardGeometry();
                int cardW = geo[0], cardH = geo[1], gap = geo[2], stripW = geo[3], stripX = geo[4], stripY = geo[5], visibleCards = geo[6];
                int overflow = Math.max(0, blueprintOptions.size() - visibleCards);
                if (overflow > 0 && mouseY >= stripY && mouseY < stripY + cardH) {
                    if (blueprintScroll > 0 && mouseX >= stripX - BP_GUTTER - 2 && mouseX < stripX - 2) {
                        blueprintScroll--;
                        MWStyle.playTab();
                        return true;
                    }
                    if (blueprintScroll < overflow && mouseX >= stripX + stripW + 2 && mouseX < stripX + stripW + BP_GUTTER + 2) {
                        blueprintScroll++;
                        MWStyle.playTab();
                        return true;
                    }
                }
                if (mouseX >= stripX && mouseX < stripX + stripW && mouseY >= stripY && mouseY < stripY + cardH) {
                    int offsetPx = Math.round(blueprintScrollAnim * (cardW + gap));
                    int index = (int) ((mouseX - stripX + offsetPx) / (cardW + gap));
                    int cardLeft = stripX - offsetPx + index * (cardW + gap);
                    if (index >= 0 && index < blueprintOptions.size() && mouseX < cardLeft + cardW) {
                        switchBlueprint(blueprintOptions.get(index));
                    }
                }
            }
            return true;
        }
        if (cinematic && !buildContextMenuOpen && clickBuildDropdown(mouseX, mouseY, button)) {
            return true;
        }
        if (!cinematic && (button == 0 || button == 1)) {
            int buildX = 10;
            for (int i = 0; i < builds.builds.size(); i++) {
                boolean filled = !builds.builds.get(i).isEmpty();
                Component label = Component.literal(Component.translatable("screen.mwloadout.build", i + 1).getString()
                        + (TaczStats.installedAttachments(gunEntry.gameId, builds.builds.get(i)) > 0 ? " (" + TaczStats.installedAttachments(gunEntry.gameId, builds.builds.get(i)) + ")" : "")).withStyle(ChatFormatting.BOLD);
                int tabWidth = font.width(label) + 14;
                if (mouseX >= buildX && mouseX < buildX + tabWidth && mouseY >= tabsY() && mouseY < tabsY() + tabsH()) {
                    if (button == 1) {
                        buildContextMenuOpen = true;
                        buildContextMenuIndex = i;
                        buildContextMenuX = (int) mouseX;
                        buildContextMenuY = (int) mouseY;
                        MWStyle.playClick();
                    } else if (builds.active != i) {
                        builds.active = i;
                        markBuildDirty();
                        MWStyle.playTab();
                        listFlashStart = System.currentTimeMillis();
                    }
                    return true;
                }
                buildX += tabWidth + 4;
            }
        }
        // RIGHT mouse button also grabs the gun to rotate it (anywhere on the stage that is not a card / the open list)
        if (button == 1 && cinematic && !buildContextMenuOpen && !buildDropdownOpen) {
            boolean onCard = false;
            for (int t = 0; t < types.size() && !listOpen; t++) {
                int[] zone = hotspotZones.get(typeZone.get(t));
                if (mouseX >= zone[0] && mouseX < zone[0] + zone[2] && mouseY >= zone[1] && mouseY < zone[1] + zone[3]) {
                    onCard = true;
                }
            }
            boolean inStage = listOpen
                    ? mouseX >= listRight + 20 && mouseY >= 100 && mouseY < height - 30
                    : mouseX >= previewX && mouseX < previewX + previewW && mouseY >= previewY && mouseY < previewY + previewH;
            if (inStage && !onCard) {
                preview.beginDrag();
                return true;
            }
        }
        if (button == 0) {
            if (addBuildTabX >= 0 && mouseX >= addBuildTabX && mouseX < addBuildTabX + addBuildTabW
                    && mouseY >= tabsY() && mouseY < tabsY() + tabsH()) {
                // New slot starts empty and becomes active immediately — matches switching to any
                // other tab. Still local until SAVE BUILD/DONE, same as every other edit here.
                if (builds.addSlot()) {
                    builds.active = builds.builds.size() - 1;
                    markBuildDirty();
                    MWStyle.playClick();
                }
                return true;
            }
            if (cinematic) {
                // Hotspot boxes ringing the weapon (see computeHotspotZones) instead of the
                // always-open left rail.
                for (int i = 0; !listOpen && i < types.size(); i++) {
                    int[] zone = hotspotZones.get(typeZone.get(i));
                    if (mouseX >= zone[0] && mouseX < zone[0] + zone[2] && mouseY >= zone[1] && mouseY < zone[1] + zone[3]) {
                        openTypePopup(types.get(i));
                        return true;
                    }
                }
                if (listOpen && !(mouseX >= listX - 12 && mouseX < listRight + 12 && mouseY >= listTop - 24 - riserRow && mouseY < listBottom + 12)) {
                    // Buttons (CONFIRM / SAVE / CLEAR) and the search box take the click first - used to cost two clicks.
                    if (super.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                    // The gun is the right half of the select view: a click (left OR right button) there grabs it to rotate -
                    // it used to close the list instead, so the model could only be dragged in the hub.
                    if (mouseX >= listRight + 20 && mouseY >= 100 && mouseY < height - 30) {
                        preview.beginDrag();
                        return true;
                    }
                    // Otherwise: click outside the open popup = back out without changing anything.
                    closeListPopup();
                    return true;
                }
            } else {
                int railY = listTop;
                for (AttachmentType type : types) {
                    if (mouseX >= 10 && mouseX < 10 + RAIL_WIDTH && mouseY >= railY && mouseY < railY + 20) {
                        if (activeType != type) {
                            activeType = type;
                            searchBox.setValue("");
                            MWStyle.playTab();
                            listFlashStart = System.currentTimeMillis();
                            rebuild();
                        }
                        return true;
                    }
                    railY += 24;
                }
            }
            if (clickLaserStrip(mouseX, mouseY)) {
                return true;
            }
            int row = rowAt(mouseX, mouseY);
            if (row >= 0 && row < candidates.size()) {
                Candidate candidate = candidates.get(row);
                int weaponLevel = currentWeaponLevel();
                if (candidate.locked(weaponLevel)) {
                    // weapon-level gate — checked BEFORE the budget check below, since not
                    // having unlocked it yet is the more fundamental reason it can't be equipped.
                    levelLockTipText = Component.translatable("screen.mwloadout.level_locked", candidate.requiredLevel()).getString();
                    levelLockTipUntil = System.currentTimeMillis() + 2200;
                    MWStyle.playClick();
                    return true;
                }
                var pointRules = dev.l41nx.mwloadout.registry.LoadoutRegistry.get().rules();
                boolean occupied = builds.activeBuild().containsKey(activeType.name());
                boolean wouldExceed = pointRules.attachmentPointLimit > 0 && !occupied
                        && (realAttachmentCount() + 1) > pointRules.maxAttachments();
                if (wouldExceed) {
                    limitTipUntil = System.currentTimeMillis() + 2200;
                    return true; // locked: budget spent — replace or remove something first
                }
                String clickedId = candidate.id;
                if (clickedId.equals(builds.activeBuild().get(activeType.name()))) {
                    // Click the equipped one again = take it off (MW muscle memory).
                    builds.activeBuild().remove(activeType.name());
                } else {
                    builds.activeBuild().put(activeType.name(), clickedId);
                }
                markBuildDirty();
                MWStyle.playClick();
                // (stays in the select view: keep browsing / swapping attachments, hover previews them on the gun)
                return true;
            }
            // preview-drag-start check moved to LAST, after every hotspot box / build tab /
            // popup-list hit-test above has had a chance to claim the click first. In cinematic
            // mode the weapon stage now spans nearly the whole screen, so this used to run FIRST
            // and swallow every click before a hotspot box (which visually sits on top of that
            // same stage) ever got to see it — clicking a box did nothing but start a drag. Also
            // skipped outright while a popup is open (any click that reaches this far while
            // listOpen is still true landed somewhere in the popup's own chrome/padding, not on a
            // real row — it should be a harmless no-op, not a drag on the gun sitting behind it).
            // Widgets (BLUEPRINTS / MODE / FLIP / CLEAR / SAVE / DONE) get their click BEFORE the drag test - the stage
            // used to cover them and silently swallow every click (that is why BLUEPRINTS never opened).
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (!(cinematic && listOpen) && mouseX >= previewX && mouseX < previewX + previewW
                    && mouseY >= previewY && mouseY < previewY + previewH) {
                preview.beginDrag();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Switches this slot to a different form of the same platform. Attachments carry over
     *  as-is — TACZ's own installAttachment silently skips anything that doesn't fit the new
     *  gun's rails when the item actually gets built, so this is the closest approximation of
     *  "keep my attachments" the underlying gun-identity system allows (TACZ's gunId NBT tag IS
     *  the gun's full identity — model, stats, attachment compatibility all come from it
     *  together; there's no separate skin-only layer to hook into). Reopens a fresh screen
     *  instance so every per-gun cached value (types, gunStats, builds) recomputes for the new
     *  gun; PlayerProfile.GunBuilds is keyed per catalog entry id, so each form still remembers
     *  its own last-saved build slots independently going forward. */
    /** Scrolls the carousel so the platform currently open sits inside the visible window,
     *  roughly centered — otherwise a platform beyond the first page of cards opens with the
     *  highlighted card off-screen and no hint where it went. */
    private void scrollBlueprintToCurrent() {
        int[] geo = blueprintCardGeometry();
        int visibleCards = geo[6];
        int overflow = Math.max(0, blueprintOptions.size() - visibleCards);
        int current = 0;
        for (int i = 0; i < blueprintOptions.size(); i++) {
            if (blueprintOptions.get(i).id.equals(gunEntry.id)) {
                current = i;
                break;
            }
        }
        blueprintScroll = Mth.clamp(current - visibleCards / 2, 0, overflow);
        blueprintScrollAnim = blueprintScroll;
    }

    private void switchBlueprint(CatalogEntry chosen) {
        blueprintPanelOpen = false;
        if (chosen.id.equals(gunEntry.id)) {
            return;
        }
        REOPEN_BLUEPRINT_PANEL = true;
        selection.entryId = chosen.id;
        // do NOT carry the old gun's attachments over on the selection. The new
        // screen's init() seeds its build from `selection.attachments` when non-empty (the
        // legacy-preset path, for a preset saved before per-gun build memory existed) — it has
        // no way to tell that apart from "just switched forms", so leaving the old attachments
        // here meant confirmAndExit() (fired even on plain ESC) saved THIS gun's build into the
        // NEW gun's independent slot, stomping whatever the new form already had. Clearing here
        // forces the new screen to load its own stored build from PlayerProfile.buildsFor(chosen.id)
        // untouched — every form keeps a genuinely independent build again.
        selection.attachments.clear();
        MWStyle.playClick();
        // Same screen, different gun: swap instantly - replaying the close + open fades here was just noise.
        SKIP_OPEN_ANIMATION = true;
        MWStyle.handoff();
        minecraft.setScreen(new AttachmentEditorScreen(parent, selection, chosen));
    }

    /** ESC closes the Blueprint picker first (instead of confirming + leaving the whole gunsmith). */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (previewMode) {
            // any key leaves preview mode (modifier keys alone do not, and the key that was held while pressing the button is ignored briefly)
            boolean modifierOnly = keyCode >= 340 && keyCode <= 347;
            if (!modifierOnly && System.currentTimeMillis() - previewModeAt > 250L) {
                exitPreviewMode();
            }
            return true;
        }
        if (advanced.isOpen()) {
            return advanced.keyPressed(keyCode, scanCode, modifiers); // ESC closes the box, never the gunsmith under it
        }
        if (keyCode == 86 && !searchBox.isFocused() && closingAt <= 0) { // V: keep the view you dragged the gun to as the home view
            if (preview.saveCurrentView()) {
                MWStyle.playClick();
                dev.l41nx.mwloadout.client.ClientCache.toast("message.mwloadout.view_saved", "");
            }
            return true;
        }
        if (keyCode == 256 && !blueprintPanelOpen && closingAt <= 0) { // GLFW_KEY_ESCAPE
            // ESC backs out ONE level at a time: open menus first, then the attachment list, and only then the gunsmith itself.
            if (buildContextMenuOpen || buildDropdownOpen) {
                buildContextMenuOpen = false;
                buildDropdownOpen = false;
                MWStyle.playClick();
                return true;
            }
            if (cinematic && listOpen) {
                closeListPopup();
                MWStyle.playClick();
                return true;
            }
        }
        if (blueprintPanelOpen) {
            if (keyCode == 256) { // GLFW_KEY_ESCAPE
                blueprintPanelOpen = false;
                MWStyle.playClick();
            } else if (keyCode == 263 || keyCode == 262) { // left / right arrows scroll the strip
                int overflow = Math.max(0, blueprintOptions.size() - blueprintCardGeometry()[6]);
                blueprintScroll = Mth.clamp(blueprintScroll + (keyCode == 262 ? 1 : -1), 0, overflow);
            }
            return true; // swallow typing/shortcuts meant for widgets hidden behind the modal
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (previewMode) {
            return true;
        }
        if (advanced.isOpen()) {
            return advanced.charTyped(codePoint, modifiers);
        }
        return blueprintPanelOpen || super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Candidate(String id, String name, int requiredLevel) {
        boolean locked(int currentWeaponLevel) {
            return requiredLevel > currentWeaponLevel;
        }
    }
    /** MW19 attachment hover card, drawn at the cursor: name, then PROS in green and CONS in
     *  red — the qualitative readout; the six bars on the right carry the quantitative one. */
    private void renderHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredRow < 0 || hoveredRow >= candidates.size()) {
            return;
        }
        String id = candidates.get(hoveredRow).id;
        List<TaczStats.Line> all = TaczStats.attachmentProsCons(id);
        List<String> pros = new ArrayList<>();
        List<String> cons = new ArrayList<>();
        for (TaczStats.Line line : all) {
            if (line.text().startsWith("+")) {
                pros.add(line.text().substring(1).trim());
            } else {
                cons.add(line.text().substring(1).trim());
            }
        }
        List<TaczStats.Line> rendered = new ArrayList<>();
        rendered.add(new TaczStats.Line(DisplayNames.ofAttachment(id).toUpperCase(), MWStyle.TEXT));
        if (!pros.isEmpty()) {
            rendered.add(new TaczStats.Line(MWStyle.tr("screen.mwloadout.pros"), MWStyle.TEXT_DIM));
            for (String pro : pros) {
                rendered.add(new TaczStats.Line("+ " + pro, MWStyle.POSITIVE));
            }
        }
        if (!cons.isEmpty()) {
            rendered.add(new TaczStats.Line(MWStyle.tr("screen.mwloadout.cons"), MWStyle.TEXT_DIM));
            for (String con : cons) {
                rendered.add(new TaczStats.Line("- " + con, MWStyle.NEGATIVE));
            }
        }
        if (pros.isEmpty() && cons.isEmpty()) {
            rendered.add(new TaczStats.Line(MWStyle.tr("screen.mwloadout.no_stats"), MWStyle.TEXT_DIM));
        }
        int boxWidth = 0;
        for (TaczStats.Line line : rendered) {
            boxWidth = Math.max(boxWidth, font.width(line.text()));
        }
        boxWidth += 14;
        int boxHeight = rendered.size() * 10 + 8;
        int x = Math.min(mouseX + 10, width - boxWidth - 4);
        int y = Math.min(mouseY + 8, height - boxHeight - 4);
        MWStyle.fillF(graphics, x, y, x + boxWidth, y + boxHeight, 0xF50E0F12);
        MWStyle.hairline(graphics, x, y, boxWidth, boxHeight, MWStyle.border());
        MWStyle.fillF(graphics, x, y, x + 2, y + boxHeight, MWStyle.signal());
        int textY = y + 4;
        for (TaczStats.Line line : rendered) {
            MWStyle.strF(graphics, font, line.text(), x + 7, textY, line.color(), false);
            textY += 10;
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

    /** ESC behaves exactly like CONFIRM — losing a build to a reflex keypress is the kind of
     *  thing players rage about; there is nothing here worth discarding silently. */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (advanced.isOpen()) {
            return advanced.mouseDragged(mouseX, mouseY);
        }
        if (laserDragging) {
            applyHueAt(mouseX);
            return true;
        }
        preview.drag(dragX, dragY);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (advanced.isOpen()) {
            return advanced.mouseReleased();
        }
        laserDragging = false;
        preview.endDrag();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        confirmAndExit();
    }

    /** CONFIRM while an attachment list is open only closes that list (back to the gunsmith view); leaving the gunsmith is a
     *  second press. Same rule as ESC. */
    private void confirmOrBack() {
        if (cinematic && listOpen && closingAt <= 0) {
            closeListPopup();
            MWStyle.playClick();
            return;
        }
        confirmAndExit();
    }

    private void confirmAndExit() {
        selection.attachments.clear();
        selection.attachments.putAll(builds.activeBuild());
        Net.CHANNEL.sendToServer(new Net.C2SSaveGunBuild(gunEntry.id, builds.active,
                Json.GSON.toJson(builds.toJson())));
        transitionTo(parent);
    }

}
