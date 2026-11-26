# Changelog

## Unreleased
- v64: stat bar descriptions. Audit of ACCURACY / DAMAGE / RANGE / FIRE RATE / MOBILITY / CONTROL / MUZZLE VELOCITY: the bars and the good/bad colouring
  are right (LOWER_IS_BETTER = {accuracy, mobility, control}, bars = 1 - raw/scale for those). What was confusing is the text: the tooltip said "Lower is
  better" for ACCURACY / MOBILITY / CONTROL, i.e. about the NUMBER (standing spread deg, build weight, recoil kick deg), while the bar is inverted - and the
  number gave no hint of what it was. TaczStats.BAR_MEANING + new BAR_UNITS (deg spread / weight / deg kick), tooltip lines "Longer bar = better (the number:
  lower = better)", comparison line marked better / worse. RANGE text ("full damage up to X m") checked: X is damage_adjust[0].distance scaled by the build,
  so it is correct. Not changed: attachment pros/cons logic (checked, direction handling is right; `knockback` is treated as a con when it rises - arguable,
  left as is).
- v63: slug ammo did not turn a shotgun into slug rounds once it sat in the AMMO TYPE slot. Root cause (verified in the 1.2.0 jar): TACZ does the slug
  conversion itself - ModernKineticGunItem$DefaultPropertyModification.modify answers "bullet_amount" with 1 when AllowAttachmentTagMatcher.matchTag
  (tacz:intrinsic/slug, the attachment in the EXTENDED_MAG slot) - and ONLY reads EXTENDED_MAG, which the slug no longer occupies. New
  MixinDefaultPropertyModification (@Inject HEAD) applies the same rule to the AMMO TYPE slot (MWSlots.slugFitted). The gunsmith's advanced stats now show
  1 pellet for a slug in either slot. Note: TACZ's slug mod itself only scales damage x0.75 / spread, the single projectile is this conversion.
  Not verified in game; the target class is an inner class of ModernKineticGunItem matched by name.
- v62: log of the v61 test: "mixin config registered=false, enum constant present=false" - Forge did not register mwloadout.mixins.json from the
  mods.toml [[mixins]] block. Fix attempt (unverified): jar manifest attribute MixinConfigs (the route MixinGradle uses) + MWSlots.ensureMixinConfig() as the
  first call of the mod constructor (Mixins.addConfiguration if the config is not known yet - only effective while AttachmentType is not loaded).
  logStatus now prints how the config got registered, or why the Mixin API could not be used.
- v61: AMMO TYPE slot did not show up in the first test (hub / rail still said EXTENDED MAG, i.e. MWSlots.available() was false = the enum constant was
  never added). Root cause NOT identified yet (no log, nothing runnable here). Added MWSlots.logStatus (FMLCommonSetupEvent): one latest.log line with
  'mixin config registered / enum constant present / failure' so the failing step is visible, plus a self-heal if the constant exists but the static is null.
- v60: AMMO TYPE slot (first Mixin in the project) - NOT built or run here
  - NEW MWSlots + mixin/MixinAttachmentType (appends AMMO_MOD to AttachmentType.$VALUES at the end of the enum's <clinit>, via an @Invoker on the private
    constructor - the pattern TACZ-Arcana's MixinAttachmentType.addVariant uses), MixinCommonAttachmentIndex (getType() of an extended_mag attachment whose
    data id starts with ammo_mod returns AMMO_MOD), MixinAbstractGunItem (allowAttachmentType(AMMO_MOD) = allowAttachmentType(EXTENDED_MAG)).
    Only @Inject, no @Redirect (Arcana redirects IGun.getAttachmentId inside AttachmentDataUtils; two redirects on one target throw). remap=false everywhere
    (TACZ names are not obfuscated), no refmap. mods.toml [[mixins]] + mwloadout.mixins.json (required false, defaultRequire 0); build.gradle compileOnly
    org.spongepowered:mixin:0.8.5 from the Forge maven. TACZ reads modifiers by walking AttachmentType.values() + NBT key "Attachment"+type.name(), so
    the new slot applies damage / pierce / explosion / weight natively; getMagExtendLevel only reads EXTENDED_MAG, so the extended mag decides capacity.
  - Rules.dualAmmoSlot (default true) + admin toggle (AMMO section). Gunsmith: the slot is listed only when TaczStats.ammoSlotWorks (the gun really
    accepts the injected type - checked once per session), labels MAGAZINE / AMMO TYPE (MWSlots.label), TaczStackFactory skips an AMMO_MOD pick while the slot is off.
    Budget: the AMMO_MOD key counts like any other attachment key.
    UNVERIFIED (no compiler / game here): (1) the mixin apply order and that the Mixin annotations resolve on the compile classpath, (2) TACZ's CLIENT
    renderer / refit screen with a gun that has an AMMO_MOD attachment (BedrockGunModel iterates AttachmentType.values(); Arcana ships client mixins for its own
    types, this mod ships none), (3) TACZ's own refit screen showing the extra slot. If a client crash shows up, set the rule off and tell me the log.
- v59: RISER header no longer pushes the select popup into the stat block
  - FIX the popup overlapped the ATTACHMENTS n/5 row and the stat bars on TACZ 1.2.0 guns with risers: the 20 px header row moved listTop down, and on small
    windows the 3-row minimum then pushed listBottom past the stat block. The header is now 18 px and the panel rises 16 px into the empty strip under the build
    button (listTop +2 px only); with risers the minimum is 2 rows. Not verified in game on small GUI heights.
  - Investigated, NOT built: separate AMMO TYPE and MAGAZINE slots. In TACZ 1.2.0 the six ammo mods (fmj, he, hp, i, overpressure_recoil, slug) are
    ordinary attachments of type extended_mag with their own extended_mag_level (fmj = 3, he = 1), so they compete with extended mags for the single
    EXTENDED_MAG slot; capacity comes from AttachmentDataUtils.getMagExtendLevel(gun, gunData) of the installed attachment and has no override hook.
    A real second slot needs a Mixin (the project has none) - see the notes in the reply for the options.
- v58: gunsmith PREVIEW animation, utility row packing, RISER row, Limb Damage gating
  - PREVIEW (cinematic gunsmith only) now animates: previewK eases 0 -> 1 in about 0.26 s and back in 0.22 s (smoothstep). While it runs MWStyle.chromeOpen is
    multiplied by (1 - ease) after screenChrome, so every fillF / strF and the flat buttons fade together and the buttons slide down 8 px (their existing
    open-slide). renderGunStage(previewEase) grows the stage from its hub box to the whole window and raises the scale cap by up to 60. At ease >= 0.999 only the
    gun and a hint (3.4 s, fades out) are drawn; the rest of render() is skipped. Mouse is masked (-10000) and clicks are swallowed while the UI is not fully
    back. Exit is any key (modifier-only keys and the first 250 ms ignored); the model then eases home (resetView).
    Not verified in game: the 'slots push in' idea was NOT built (hub cards only fade); only the fade, the 8 px slide and the stage growth exist.
  - FIX gap between BLUEPRINTS and PREVIEW: the buttons no longer have fixed slots. layoutUtilityRow packs BLUEPRINTS / MODE / FLIP / PREVIEW to the left every
    frame (6 px gap, only visible buttons) and each x eases to its target, so FLIP being hidden by TACZ's refit camera no longer leaves a hole before PREVIEW.
    PREVIEW is created in cinematic only.
  - FIX RISER button sat on the popup's top border (and the border read as a stray line under it): the select popup now has a 20 px header row when the slot has
    risers (riserRow). Panel, hit test and layout (applyPopupLayout, shared with openTypePopup) include it; the button sits inside the panel above the search box.
    Options are recomputed only when the slot or the build changes (refreshRiser) and a change of the row re-lays the popup out.
  - FIX Advanced stats showed per-part multipliers without Limb Damage. DamageModel.limbActive() = rules.limbDetected; limb() returns 1.0 for every zone when it is
    off (the stored multipliers could be stale from an earlier detection), activeZones() lists only Head and Chest then. The zone table, both charts, the target
    labels and the damage tooltip follow it; the table height follows the row count. The header status text says only Head and Body are listed.
    LimbCompat was checked against Limb Damage 2.0.0 (LimbConfig / CategoryConfig / Val / BodyPart still match its reflection targets): no change needed.
  - Docs: wiki Gunsmith / Compatibility, UI_DESIGN (Motion), CHANGELOG.
- v57: killstreak ammo, radial swap-back, TACZ 1.2.0 round
  - NEW killstreak guns carry editable ammo. Rules.StreakReward has mag / reserve (-1 = not set = the old one-round gun; written to json only when set, so old
    configs and clients are unchanged); the streakRewards text form takes ITEM|bullets/reserve. KillstreakWeaponScreen has BULLETS / RESERVE boxes (gun rewards only).
    The reserve is TACZ dummy ammo inside the gun, so a streak weapon never uses the player's ammo; PerkEffects.refillDummyAmmo skips streak items.
    KillstreakItems: the gun is removed when magazine, chambered round (closed-bolt / manual-action only) and reserve are all spent; legacy rewards keep 'magazine empty'.
    The 'player used X' banner fires ONCE, on the first round fired (magazine below the granted count, polled every 2 ticks - no TACZ event dependency).
    A half-used gun keeps its state across death (the stack is stashed, not just its reward id).
    Assumption, not verified: TACZ moves reserve into the magazine in one step at the end of a reload; if it does not, a gun could be removed mid-reload.
  - FIX radial swap-back when the streak item came from the main inventory: the previous weapon was left in that inventory slot and 'back to the previous slot'
    selected the now-empty hotbar slot. StreakRadial remembers swappedFrom and swaps back; the X wedge does the same.
  - TACZ 1.2.0 compat. New TaczCompat (reflection; a feature that throws switches itself off for the session with one log line). Optic risers are TACZ slot adapters:
    stored as __riser.<SLOT> in the build map, never counted against the attachment limit (isMeta), kept by both sanitizers, applied after the attachments in both gun
    builders, RISER row in the gunsmith. GunPreview: RenderDistance.markGuiRenderTimestamp() does not exist in 1.2.0 (NoSuchMethodError in every 3D preview) - now optional.
    Audited by checking every imported TACZ class and the called methods against the 1.2.0 jar; 1.1.6 was NOT audited (no jar), the project is built against 1.1.8.
  - Gunsmith: the attachment readout is a count (n/max) instead of points; rules attachmentPointLimit / attachmentPointCost are unchanged.
  - NEW PREVIEW button (hides the HUD, drag / zoom, view never auto-resets, any key returns). NEW boot log banner (BootLog) with mod / TACZ versions and active compat features.
    Not verified in game: riser list from SlotAdapterHelper.getAvailableSlotAdapters (signature checked in the jar, behaviour not).
- v56: killstreak editor holds 50 slots
  - KillstreakWeaponScreen: MAX_SLOTS 10 -> 50. The ladder shows two rows and scrolls (wheel or scrollbar, selected slot kept in view), columns adapt to the width (up to
    8), off-screen cards are not drawn, Shift + '+ Slot' adds five, the stage shows SLOT n / total. The player-side pool grid already scrolled and the server only grants
    what a class equips (3), so neither changed.
- v54, v55: not recorded here (made before this log was brought up to date).
- v53: no more silent catches
  - NEW MWLog: every catch block that used to be completely empty (60 of them: 53 on Throwable / Exception, 7 on narrower types) now reports to MWLog. Each
    site logs ONCE with its stack trace - WARN on server / data paths (network, server, data, registry), DEBUG on client probes and render helpers - and
    afterwards only counts (a note at 100 and 10 000 repeats), so a per-frame path can never flood the log. Behaviour is unchanged: the exception is still
    not propagated. The sites are named "<file>:<line>" so a log line points straight at the code. Worth reading in a real server log: the import of
    classes / gun builds (Net), profile loading (PlayerProfile) and usage stats - those drop one bad entry and keep going.
- v52:
  - Killstreak radial is HOLD and RELEASE only (the tap-to-keep-open mode is gone): release X on a wedge to choose it, release on nothing to just close.
  - Streak item chosen through the radial: once it is SPENT (gone from the inventory while still in your hand - a one-use gun after its round, a consumable
    after use) you are put back on the hotbar slot you came from. If you switched away yourself before it was spent nothing is changed. Items that should NOT
    hand you back: client config radialNoReturn (item ids, reward ids, or a prefix ending in * such as "mymod:*"; empty by default).
  - UI mesh: panels, tiles and buttons used to flush the gui buffer once per layer (shadow, fill, edge, strip, halo = 3-5 draw calls each). Each component now
    emits all its geometry into the gui buffer and flushes ONCE. MWStyle.angular() is cached for 250 ms (it was a config read on every call, dozens per frame).
    The perk list skips rows that are completely outside the scrolled area.
- v51: killstreak radial (like Call of Duty)
  - NEW keybind X (rebind it under MW Loadout in Controls): a radial menu of the killstreaks the class has equipped. HOLD X and release on a wedge, or TAP X
    and click. A wedge is lit when the streak item is in your inventory and dim when it is not earned yet; the bottom X wedge puts your previous weapon
    back in hand ("hủy chọn"). Streak items are found by the tags the server already puts on them, so items from any mod work.
  - TACZ gun: it is taken in hand (a main-inventory item is swapped into the selected hotbar slot, like pressing the number key). Anything else is taken
    in hand and then RIGHT-CLICKED automatically (a real use press: block, entity and plain use all behave as if you had clicked), because streak items come
    from many mods. Client config radialAutoUse (default on) and radialUseHoldTicks (3, for items you must hold). Both are in the YACL screen.
  - You keep walking while the radial is open (the movement keys follow the real keyboard, same idea as the Radial Hotbar sample). New MWStyle.ringSector
    draws the wedges. Not verified in game: the swap uses vanilla's container click, and the automatic click is a KeyMapping press 2 ticks after the swap.
- v50:
  - Killchain and Hardline are LOCKED while Specialist is on: PerkEffects.playerHasSpecial returns false for them (equipped or granted), and in the perk
    screen their rows are dimmed with a LOCKED tag and cannot be picked. Together with v49 they no longer count for Specialist at all.
  - FIX Killchain after a completed chain: it saved the streak on the first death even when every equipped streak item had already been earned, so
    the kept streak stayed above all thresholds and several more deaths were needed before a new chain started. With nothing left to earn, the death
    now resets the chain immediately (Killchain still saves a chain that has items left to earn).
  - FIX the 8 KILL caption sat on top of the perk list / tabs: it is now in the right column under the detail card.
  - FIX flickering / lingering hover box in the Specialist perk tabs: the row hit area now includes the 3 px gap under each row (the cursor crossing the gap
    flipped the detail card between two perks every frame), the card holds 160 ms after the cursor leaves a row and then disappears on Specialist tabs
    instead of staying on the stage's pick. (Cause inferred from the code - tell me if the box that flickers for you is somewhere else, e.g. the loadout
    screen's killstreak card.)
- v49:
  - Killstreak banners: the cut is on the corner that faces the screen centre (top-left for a banner on the right side, top-right for one on the
    left) - v47 cut the wrong one.
  - BLUEPRINTS / MODE / FLIP buttons in the gunsmith no longer touch (6 px gap).
  - FIX hit-perk icons only showing now and then: they were drawn on the crosshair layer's Post event, which Forge never fires when the layer is
    cancelled - and TACZ cancels the vanilla crosshair while a gun is held. They now draw on Pre (receiveCanceled), like TACZ Hitmarker does. The icons are
    fully transparent (no plate behind them) and fade by real alpha.
  - Hit-perk marks are re-sent every 5 s even when unchanged (a watcher that missed the first packet no longer stays blind) and are cleared on disconnect.
  - Specialist: Killchain, Hardline, Overkill and Underkill are removed from the pool (Rules.SPECIALIST_EXCLUDED) - they cannot be picked and the 8-kill
    full unlock skips them too.
  - NEW "8 KILL" tab next to 2 / 4 / 6 KILL in the perk screen: a read-only list of every perk the full unlock gives, with a caption.
  - FIX the perk list ran off the bottom of the screen (Shield Up, last in the list, was unreachable): the list now scrolls (mouse wheel, thin scroll track),
    is clipped to the area under the tabs, and the perk that exists only in a Specialist column (Shield Up in the third) is listed FIRST there.
- v48:
  - NEW hitmarker cross-compat (works with TACZ Hitmarker, or the default marker): when YOUR TACZ bullet hits another player, the perks that
    player carries - Quick Fix, Battle Hardened, Shield Up, Tracker, Fragiles, Ghost, Shrapnel, Blood Rush - appear as small icons 35 px below the
    crosshair centre and fade out with the hit. Trigger = TACZ's client-side EntityHurtByGunEvent.Post, found by class name at client setup (same
    approach as TACZ Hitmarker, no hard TACZ API dependency; logs "[MWLoadout] Hitmarker perk marks listening to ..."). The server tells clients which of
    those eight perks a nearby player has (Net.S2CPerkMarks, sent when the set changes, once a second at most, and when a player comes into view)
    - Specialist picks count too. NETWORK PROTOCOL 6 -> 7. Client config: hitPerkMarks, hitPerkOffsetY (35), hitPerkSize (14),
    hitPerkDurationMs (450); also in the YACL screen ("Hit perk marks").
  - FIX Specialist showed only the admin's small perk pool (8 perks minus the ones already equipped, minus the column restriction = 2-3 rows). It now
    offers EVERY enabled perk that is not already equipped or picked (new rule specialistPoolAll, default true; set it to false in rules to
    keep the curated specialistPerkPool). The server validates picks against the same list (LoadoutRegistry.specialistPool()).
  - FIX config screen: a failure while building the YACL / Cloth Config screen used to log "There was a critical issue trying to build the config GUI"
    and leave a dead button. It now logs the full stack trace under [MWLoadout] and opens the in-game SETTINGS screen instead; slider values outside the
    slider's range and unknown selector values are clamped / defaulted rather than throwing.
- v47:
  - Laser colour is now a HUE SLIDER (rainbow track, drag the handle) plus a current-colour box, a WHITE box and AUTO, like the refit tuning in
    TACZ, instead of nine swatches. The strip has its own band at the bottom of the attachment panel: the list stops above it (scissor, scroll
    and hit-testing use rowsBottom()), so rows and their item icons can no longer run under it or draw over it.
  - Killstreak banners (the "X got a Predator" callouts) and the killstreak tracker tiles lose their 4-corner brackets / plain rectangle in the
    angular style: ONE cut corner on the outer top (right for right-anchored banners / the bottom-right tracker, left for left-anchored banners),
    1 px edge following it. New MWStyle.cutFill / cutEdge / gradientH.
  - NEW optional dependency: YACL (Yet Another Config Lib) 3.6.5 for Forge 1.20.1 (dev.isxander maven, compileOnly like the others, mods.toml
    mandatory=false). With it, Mods > MW Loadout > Config is the detailed editor: look and colours, gunsmith view, toasts (shared position AND a
    separate position for each kind - killstreak, level-up, equip, system notice), the killstreak tracker (position, size, opacity, colour),
    the banners (anchor, offsets, scale, duration, who is shown, colours), High Alert, cloud. YACL is preferred over Cloth Config when both are
    installed; the Cloth screen keeps its smaller option set.
  - NEW client config: toast<Kind>CustomPosition / Anchor / XOffset / YOffset for the four toast kinds (the kind is picked from the server
    message key in ClientCache.toast), so each kind can sit in its own place.
- v46: laser colour (gap vs TACZ's refit screen)
  - TACZ 1.1.5 laser attachments can have an editable colour (display: laser can_edit / default_color) and its refit screen lets you tune it; the
    gunsmith here had no such control and builds dropped the colour. NEW: a LASER COLOR strip under the laser list (auto + 8 swatches). The colour is
    stored as a pseudo-entry "__laserColor" (#RRGGBB) in the build's attachment map, exactly like the existing "__fireMode", so saves, the network,
    cloud copies and every build slot carry it with no schema change. TaczStackFactory.isMeta() now identifies both pseudo-keys everywhere (install
    loops, point budget, stats, probe builds) instead of comparing against the fire-mode key only. The sanitizers keep a valid colour only while a
    laser is still fitted.
  - The colour is written onto the laser attachment stack before it is installed: through an IAttachment setLaserColor / setColor setter when TACZ
    exposes one (reflection), otherwise as the int NBT tag "LaserColor". NOT VERIFIED against TACZ's source (not reachable from the dev environment):
    the first application logs "[MWLoadout] Laser colour #XXXXXX applied via ...; attachment tag now {...}" - compare that tag with what TACZ's own
    refit screen writes (/data get entity @s SelectedItem.tag) and adjust the key in TaczStackFactory.applyLaserColor if it differs.
- v45:
  - Gunsmith model closer: the whole-gun home view is framed ~35 % larger (HOME_CLOSER 0.20 -> 0.62) and the cap on pixels per block is raised, so
    short guns grow the full amount. Sizing still comes from the GEO model (refitLength = measured muzzle-to-origin length), and a long gun is
    held at 1.25 x the layout width so its ends stay on screen. New client config gunsmithModelZoom (50-200 %, default 100, also a slider in the
    Cloth Config screen) to tune it per player without a rebuild.
- v44: data folder outside config, two-way cloud
  - NEW MWPaths: all loadout data now lives in ONE folder outside config/ - <game or server folder>/mwloadout/ with data/ (slots, catalog, perks,
    rules, stats), players/ (per-player profiles), cloud/ (this device's copy + backups/) and client/ (advanced-stats settings). Both the dedicated
    server and the client read and write there; -Dmwloadout.dir=<path> moves it (panel hosts with a separate data mount).
  - The first start after the update COPIES config/mwloadout/ (never moves, never overwrites a file that already exists in the new place) and
    config/mwloadout-advstats.json into the new layout, logs how many files, and leaves MOVED.txt in the old folder. Old files stay as a backup;
    delete them once you have checked.
  - NEW "TO DEVICE" button (class screen, under CLOUD; only on a remote server): saves the classes and gun builds the server holds for you into this
    device's cloud copy so single player (and other servers) can use them - press CLOUD in the single-player world to apply. Two clicks (it replaces
    the copy); the previous copy is backed up to cloud/backups/ first (5 kept). Toast reports how many classes were saved.
  - Docs: README gets a "Thư mục dữ liệu" section.
- v43:
  - FIX background flashing between loadout screens: screens opened WITHOUT an explicit MWStyle.handoff() (gunsmith tab / slot switch re-creates the
    screen, back buttons, settings sub-pages, admin) faded the backdrop in from 0 every time. A hand-off is now detected automatically in
    screenOpened() (another loadout screen drew a frame <400 ms ago), so the dim + blur stay put and only fade when the loadout is opened from /
    closed to the game. The UI chrome no longer drops to 0 either: leaving towards another loadout screen dims to 60 %, the next screen arrives
    at 60 %, and the swap waits 90 ms instead of 200 ms.
  - FIX shotgun damage model (Advanced stats): the falloff figures in the gun data are what a WHOLE trigger pull is worth, TACZ splits them over
    bullet_amount - so a 12-pellet gun listing 60 deals 5 per pellet, not "60 per pellet / 720 all pellets" as v37-v42 showed. DamageModel.taczAt is
    now per bullet entity (listed / pellets), listedAt() keeps the data figure. Zone multipliers, armor ignore, Minecraft armor absorption and the
    server damage cut are applied per pellet and then summed over the pellets that land - armor eats small pellets proportionally more, which the
    old total-then-armor order got wrong. Rows: Damage per pellet / Pellets / All pellets hit (= the listed figure) / Pellets on target / DPS.
  - Gunsmith home view of the gun: isometric from the right, camera slightly BELOW the gun looking up (default turn 28, elevation -22 degrees).
    Drag the gun to any angle and press V to keep it as the new default (stored in client config gunsmithViewYaw / gunsmithViewPitch; the
    sign of the elevation is the one thing to flip in the toml if it ends up the wrong way round on your build).
- v42: selection / outline rework, performance pass
  - FIX layered outlines on selected rows (class list, perk list, weapon cards): the wash was a plain rectangle, the strip a straight bar poking out
    of the cut corner, the glow three stacked outlines, and SlotPicker drew the selection twice. There is now ONE selection treatment
    (MWStyle.selection): a real gradient halo (per-vertex alpha, mitred around the cut corners), a single 1 px edge and an accent strip whose
    end rides the cut. Washes / flashes over tiles, rows and buttons use MWStyle.wash so they fit the plate instead of a rectangle.
  - Pointer arrows (active class, collapsed class rail) are drawn triangles (MWStyle.arrowRight) instead of the bitmap glyph.
  - FlatButton follows the same plate language in the angular style (cut plate, 1 px edge, strip on accent buttons); the hover "float" that made
    plates drift out of alignment is classic-style only. Inline controls (sliders, text fields) intentionally stay rectangular.
  - Rendering: plate geometry is emitted into vanilla's own gui buffer (same RenderType as GuiGraphics.fill) with one flush per plate - correct
    ordering / scissor / pose scale and no extra GL state changes; the previous direct Tesselator path is gone.
  - PERFORMANCE: the weapon picker rebuilt a probe gun with every attachment each frame to print the reserve (introduced in v37) - now cached per
    gun+build; the gunsmith blueprint compare computed two bare-gun stat sets per frame - cached per hovered gun; the Advanced stats spray
    simulation re-ran every frame - cached on its inputs (distance, stance, build, target settings).
  - FIX Cloth Config screen: gunsmithLayout offers compact / cinematic (was compact / classic) and the hover tooltip default matches the real
    default (off).
- v41: UI cleanup before release
  - FIX jagged cut corners: the 45 degree cuts were built from 1 px stair steps (visible teeth at GUI scale 3-4, see the class / perk lists).
    Plates are now real polygons drawn as triangles at screen resolution (MWStyle.poly); the diagonal outline is a thin quad, straight sides
    stay pixel-aligned.
  - Selection is no longer a stack of effects: the corner brackets are gone. A selected tile = 1 px edge + solid strip + one soft glow that follows
    the cut shape. Panels lose the extra top-left tick.
  - Headers: the 2 px accent bar sitting on a 1 px rule (a visible pixel step) is replaced by one 1 px rule with the accent segment ON it; the screen
    title rule no longer sweeps in.
  - Section animation off: tab / section switch flashes and the staggered row / card reveal are disabled by default (client config
    sectionAnimations, also a switch in the Cloth Config screen). Screen open / close fade is unchanged.
  - Toast: accent block uses the same smooth cut; the stepped timer rail is now a thin continuous line.
- v40:
  - NEW optional dependency: Cloth Config API 11.1.136 (Forge 1.20.1), compileOnly via Curse Maven like TACZ, mods.toml mandatory=false.
    With it installed, Mods > MW Loadout > Config opens a proper editor (Look & feel / Toasts / Cloud: switches, sliders, selectors, colour
    pickers) bound to the same ForgeConfigSpec values as the toml and the in-game SETTINGS screen. Without it nothing changes and no class is
    loaded (client/ClothConfigCompat is only touched behind ModList.isLoaded("cloth_config")).
  - Angular style gets a smooth glow back, shaped by the design: plateGlow = four fading rings of the plate's own cut-corner outline (no round
    blur). Selected tiles breathe on a slow eased sine (softPulse); glowOutline keeps the bracket-length pulse and adds the halo.
- v39: UI language pass 1 (see docs/UI_DESIGN.md)
  - NEW angular plate kit in MWStyle (plateFill / plateEdge / plateShadow / brackets): panels, tiles and the selection highlight now use
    cut-corner plates, hard offset shadows and corner brackets. The soft glow halo and the light-blue hover wash are gone in this style:
    hover grows a solid strip down the tile's left edge, selection = signal strip + breathing brackets.
  - Toasts are redrawn as dispatch tags: cut-corner plate, solid accent block with a one-letter code, caps title, dim detail lines and a
    stepped timer rail counting down the hold.
  - New client config angularUi (default true). Off restores the previous flat rectangles everywhere.
- v38:
  - FIX gunsmith MAGAZINE readout always printed the stock magazine (30 with a 90-round Extended Mag fitted). It now shows the magazine of the
    ACTIVE build (green when above stock) and a new RESERVE line with what the server hands out for it (capacity x multiplier), refreshed whenever
    the build changes.
  - Diagnostics: live apply / the capacity watcher now log "[MWLoadout] <gun> magazine A -> B (loaded before, reserve target X -> Y)" and
    "no managed <gun> found" - send those lines when the reserve still looks wrong.
  - Note: with the default tiers (x3 up to 49 rounds, x2 from 50) a 30 -> 90 magazine moves the reserve 90 -> 180, not x3; adjust
    dynamicAmmoTiers / categoryMultipliers if a bigger magazine should earn a bigger multiple.
- v37:
  - FIX Extended Mag did not update the magazine / reserve: the gunsmith live apply only clamped rounds DOWN. A magazine that was full is now
    topped up to the new size, a smaller one cuts the rounds, and the reserve follows (capacity x multiplier). NEW server-side watcher
    (every 10 ticks) remembers each loadout gun's magazine size (tag mwloadout_cap) and applies the same sync whenever it changes - so
    attachments swapped in TACZ's own refit screen are covered too, not only the gunsmith packet.
  - FIX Advanced stats, shotguns: the damage falloff was scaled by (build damage / damage_adjust[0]) instead of (build damage / stock damage), so a
    shotgun whose "damage" differs from its first step (30 vs 50 on the Origin 12, 28 vs 60 on the 686) showed every step 25-50% too low. The
    ratio is now build / stock. DPS uses the distance-0 per-pellet figure.
  - Advanced stats, shotguns: every table, chart line, hero number and tooltip is now per pellet first. Zone table "Per pellet" column; chart has
    "Per pellet" (TACZ figure) and "All pellets" (ceiling) lines, zone lines and STK / TTK use the pellets that actually land at that range. The
    hit chance now uses a vanilla-style triangular spread (independent sideways / vertical roll per pellet) against the 0.6 x 1.8 m hitbox aimed at
    the chest. The spray simulation fires every pellet separately (12 dots per pull, "On target 83/96 pellets"), kills are counted from per-pellet hits.
  - Gunsmith attachment list: the equipped attachment is pushed to the top only when the section is OPENED again (or the type tab / search
    changes). Picking inside the open section no longer reshuffles the list under the cursor.
  - FIX right-click class menu drew under the footer EQUIP button when opened low on the screen (button text showed through). The menu is
    now on its own top layer.
  - Class screen usability: the class rail no longer vanishes on you - Tab pins it open / shut (it stays where you put it, also next time the screen
    opens), the hover strip to bring it back is 24 px wide instead of 12, and a "Tab" hint sits on the collapsed handle. Up / Down switch class,
    Enter equips the class on screen, ESC closes the right-click menu before it closes the screen (none of these fire while typing a name).
- v36:
  - NEW perk Underkill (perk column 1, Black Ops 4 style): your PRIMARY slot becomes a second SECONDARY - carry two secondary weapons. Mirror of
    Overkill (which turns the secondary into a second primary); if a class somehow has both, Overkill wins. Validator, class screen, weapon
    picker, random fill and /diagnose all share LoadoutApplier.effectiveCategories(slot, overkill, underkill). Added to existing servers
    automatically (upsertPerkIfAbsent), new texture perks/underkill.png.
  - FIX reserve ammo shown in the weapon picker ignored the equipped Extended Mag (it always used the stock magazine). It now reads the
    gun's saved build, matching what the server actually hands out.
  - Advanced stats, shotguns (more than one pellet): the damage rows are now per pellet - "Damage per pellet", "Pellets", "All pellets hit"
    (the ceiling), "Pellets on target @ N m" and "DPS @ N m (expected)". Pellets on target estimate an even pellet cloud (hip spread, radius
    grows with distance) against a 0.6 x 1.8 m player hitbox, so a 12-pellet shotgun no longer reads like an assault rifle with 4500 DPS.
    The damage chart gets an "Expected hit" line next to the all-pellets line; the zone table header spells out "x per pellet x N = total".
  - Cloud: new CLOUD button (top right of the class screen, under Settings) re-sends this device's classes on demand - press twice, it
    replaces the classes you have on that server. The import toast now reports classes that lost items the server does not have (and how many
    slots) and classes skipped (unreadable / over the server's class limit). A half-received cloud transfer is dropped when the player logs out.
    Network packet C2SImportCloud gained a "force" flag (protocol stays 6).
- Hotfix v35:
  - FIX classes reset to ten: ProfileManager padded every loaded profile back up to 10 classes, so deleted classes came back and cloud-imported
    profiles got default classes appended after a relog. The starter deck is now seeded once (fresh profile / empty file) and never topped up.
  - FIX cloud import crash / disconnect: the whole cloud profile went out as ONE packet, but a 1.20.1 client->server payload is capped at
    32767 bytes (20 classes + gun builds exceed it). C2SImportCloud is now chunked (8000 chars per packet, reassembled in order and bounded
    server-side, whole transfer dropped on any gap). NETWORK PROTOCOL 5 -> 6: client and server must both run this version.
  - FIX crash "ConcurrentModificationException" in DamageModel.limbSignature (Gunsmith > Advanced stats): LimbCompat refilled
    rules.limbCategories in place while the render thread iterated it (single player shares the Rules object). The map is now built
    locally and published with one assignment (volatile), and the signature iterates a copy with a retry.
  - FIX reserve ammo after Extended Mag: the gunsmith's live apply changed the magazine but left the reserve alone. Reserve now moves by the
    difference between the old and new full reserve (capacity x multiplier, Fully Loaded included), for DummyAmmo guns and loose ammo alike.
    Rounds already fired stay fired.
  - Gunsmith: the equipped attachment is always the first row of its section (rest stays alphabetical); the list re-sorts when you equip,
    unequip, clear or switch build tab.
  - Small: "Reset" / "Random" class perks use the current column layout (Quick Fix / Blood Rush / Scavenger) instead of the old order.

- Hotfix v34:
  - NEW "cloud" profile (single player -> dedicated server): the client keeps a copy of your classes + gun builds in
    config/mwloadout/cloud/<account uuid>.json (written from single player; dedicated servers only with "cloudWriteFromServers"). When you join a server
    (or a new world) where your loadout is still untouched, the copy is offered once (C2SImportCloud); the server runs every class through its normal
    sanitize() (catalog entry exists / enabled, unlock level, slot rules, attachments, perk columns) and drops whatever it does not have, then saves,
    syncs and toasts "Imported your saved classes". XP is never imported. Guarded server-side: only an untouched profile (PlayerProfile.customized, legacy
    profiles load as customised so nobody's classes can be replaced), Rules.allowCloudImport (admin switch, default on), loadout gamerule on. Client options:
    cloudSync, cloudWriteFromServers. NETWORK PROTOCOL 4 -> 5: client and server must both run this version.
  - Sights and scopes (pros list, COD style): a scope with more than one zoom level reads "+ VARIABLE ZOOM"; a single level under 4x reads
    "+ CLEAN SIGHT PICTURE"; 4x and up reads "+ HIGH MAGNIFICATION". The numbers card also shows the zoom ("ZOOM 3x-6x"). Zoom comes from the client attachment
    index (reflection); attachments without it simply get no line.
  - Lasers: when a laser reduces vertical and horizontal recoil by exactly the same amount the pros list shows one "+ RECOIL SMOOTHNESS" instead of two
    separate control lines (unequal axes keep the existing steadiness / stabilization naming).
  - Small patches: attachment pros / cons are cached per attachment (they are read every frame by the list and tooltip and now touch client data);
    the default class given to new players follows the new perk columns (Quick Fix / Blood Rush / Scavenger, one per column); README perk section rewritten.
  - Gold: the signature colour is now a true gold (#E3B341) instead of orange, and it is configurable - client config "signalColor" (theme section).
    Everything that used MWStyle.GOLD / SIGNAL (header bars, tab underlines, selection outlines, progress fills, attachment dots, EQUIPPED) reads it.
  - Perks re-laid out after Modern Warfare 4's three-slot pick-3 (researched from Activision's launch roster): Perk 1 = Quick Fix, Overkill, High Alert,
    Ghost, Tune Up, Killchain; Perk 2 = Hardline, Blood Rush, Fast Hands, Ninja, Shrapnel; Perk 3 = Battle Hardened, Scavenger, Tracker, Fragiles,
    Fully Loaded. Renames: Double Time -> Blood Rush, Talk To Hand -> Fast Hands. Descriptions rewritten in plain MW wording (Battle Hardened and
    Ninja included). Battle Hardened armor +10 -> +4 (the perk is about status effects), Fully Loaded x4 -> x2 reserve.
  - Shield Up is Specialist-only: perkSlot "specialist3" - never offered in Perk 3, only in the THIRD Specialist column (picker, server validation and
    the random fill all enforce it). Classes that had it in Perk 3 lose it; saved classes are re-sorted into the new columns when the profile syncs.
  - Fragiles: a hit on another player now blocks that player's health regeneration for 2 s (admin: durationSeconds on "fragiles_regen_block") instead of
    applying Wither. Cancels every heal (natural regen, Regeneration, food) via LivingHealEvent; Quick Fix wearers resist it, death clears it.
  - Existing configs are migrated once (Rules.perkBalanceRevision): slot / name / text / numbers change only while they still hold the old default.
  - One point of emphasis across the UI: MWStyle.SIGNAL, a fixed gold-orange (0xF2A431) reserved for the LINES that carry the eye - header bars, tab
    underlines, selection bars, progress fills. GOLD (attachment dots, EQUIPPED) now has the same value. New header design (MWStyle.header /
    sectionRule): bold label, a short gold-orange bar under it, a faint rule continuing to the edge of the block. Applied to the shared screen
    title bar (every screen), the class rail header and selection, XP bar, perk cells, weapon-list tabs / selection, gunsmith slot rail + weight bar +
    tooltip edge, Advanced stats headings / tabs / slider / checkbox, admin title + tabs. accent() stays the player's theme colour for everything else.
  - UI overhaul, pass 1 - Advanced stats rebuilt to look designed rather than generated: flat charcoal surface, one accent, hierarchy from type size
    (the gun name and the key numbers are set large, labels are small sentence-case, only headings are bold), no corner brackets / glows / fills under
    lines / coloured chips / pulsing. Plain text tabs with one underline. Each tab opens with the three numbers that matter at the distance you are
    looking at (chest hit, shots to kill, head hit - kill times - spread radii - spray result). Charts are single anti-aliased strokes with the zone
    name at the END of its line (click a name to hide / show it) instead of a legend. The zone table is a plain right-aligned table. Defense block is
    now "Incoming damage": server cut as a typed value + thin slider, a text link to snap back to the server's number, a real checkbox. All wording
    rewritten as plain sentences (footer explains where the zone numbers come from).
  - UI overhaul, pass 1 - Admin pane: one accent colour instead of a different colour per tab, plain text tabs with a gliding underline instead of
    coloured tiles + squares, a large "Loadout admin" title, sentence-case tab / filter / heading / status text ("Unsaved changes", "Saved") instead of
    upper-case glyph chips.
  - UI frame redesign REVERTED: MWStyle.hairline is the original plain 1 px outline again (the faded edges + corner brackets stacked on top of the
    outlines callers already draw and read as a pile of overlapping frames). Selection highlights now go through ONE helper, MWStyle.glowOutline
    (soft 3 px halo + a single crisp edge); the weapon-list row no longer stacks a wash + four inner outlines + a second border, and the class rail
    uses the same helper instead of its own copy.
  - Advanced stats target: pure white, one separate block per Limb Damage zone with a 1 px gap (head, chest, abdomen, each arm, each leg, each foot)
    in Steve proportions, each zone labelled with its colour and live multiplier; rings / dots got dark under-strokes and a green-to-red heat
    scale so they read on white.
  - Stability: S2C packet handlers that touch client classes now go through DistExecutor.unsafeRunWhenOn(CLIENT) (a dedicated server never loads them);
    the Advanced stats draw runs in try/finally with buffer-builder recovery, so a draw error closes the box instead of crashing; limbdamage /
    presencefootsteps / tacticalfootsteps declared as optional dependencies (load order only - nothing is required). Source audited: no API or
    syntax newer than Java 17; build toolchain is Java 17 on Forge 47.2.20 (1.20.1).
  - Advanced stats, round 3: charts are drawn with feathered quads (a one-pixel alpha ramp on every edge = anti-aliasing) instead of hard-edged
    quads; the whole box is laid out in a virtual space whose unit is a WHOLE number of screen pixels (picked per GUI scale so the box always fits -
    that is what fixed RECOIL overlapping DEFENSE at GUI scale 4), so text and block art stay crisp. The weapon readout scrolls (mouse wheel).
  - Open and close now run on one linear clock with the same curve forwards / backwards (160 ms each way).
  - SPREAD view: Minecraft-proportioned blocky target in its own clipped box, square spread rings per stance (no more circles spilling out
    of the box). New PATTERN tab: predicted spray as numbered firing dots on the player model (recoil climb + drift per shot, stance spread cone,
    decay between shots; deterministic estimate, no compensation), per-zone hit counts, shots-to-kill of the pattern, recoil climb / drift chart,
    stance picker and -/+ distance steppers.
  - Extended Mag and other attachments now show in Advanced stats: magazine is TACZ's figure for the built stack (includes the extended-mag
    level), ADS time includes every attachment's ads addend, recoil is split into V / H kick.
  - Damage reduction can be typed (any decimal, 0-99.9) as well as dragged (0.1 steps); the two stay in sync.
  - Limb Damage overrides (headshotOverride, gunTypeOverrides, category table) are re-read by the server every 5 s and the box reacts live: the
    category is resolved every frame and the header flashes "CONFIG CHANGED" when a resync changes anything.
  - Limb Damage compat updated for the current mod: 6 zones (HEAD / CHEST / ABDOMEN / ARMS / LEGS / FEET), the per-weapon-category table
    (pistol / rifle / sniper / rpg / smg / shotgun / mg, -1 = inherit global), headshotOverride (TACZ headshot cancelled, only the limb head
    multiplier counts) and gunTypeOverrides ("tacz:ak47=rifle"). The server detects the mod (ModList), reads its LIVE config by reflection every
    5 s and re-syncs every client when anything changes (Rules.limb*, limbDetected, limbSynced). The Advanced stats header shows the link status:
    SYNCED / DETECTED but config unreadable / NOT DETECTED; the zone table shows the gun's category.
  - CRASH FIX (server start, UnsupportedOperationException in CreativeTabScanner.healLegacyAmmoBonus): PerkDefinition.of() stored the caller's
    immutable List.of(...) as the perk's effects list, and the heal passes + admin editor mutate it. It now copies into an ArrayList. This also
    covers the Battle Hardened / Ninja effect entries added by earlier v34 builds.
  - Gunsmith live apply: installing / removing an attachment (or changing fire mode / build) is pushed to the server ~0.35 s later; the server
    saves it AND re-installs it on the loadout gun in the player's inventory (ammo state kept, magazine clamped), so the gun changes immediately
    instead of after the next class apply.
  - Advanced stats rebuilt: GPU-drawn charts (smooth at any GUI scale), pop-in/out animation, values animate toward new numbers in real time,
    chart draw-in, click-to-pin distance, legend toggles. Per-zone damage uses Limb Damage's zones (HEAD / CHEST / ABDOMEN / ARMS / LEGS), synced
    from the server's limbdamage config (Rules.limb*Mul, limbAutoSync). Damage pipeline: TACZ figure -> TACZ headshot (head) -> zone multiplier ->
    vanilla armor on the non-ignored part -> server damage reduction. DEFENSE controls: reduction slider (default = server's
    Rules.damageReductionPercent, 55), typed target HP / armor points / toughness, FRAGILE toggle. Spread tab shows per-stance cones on a target
    silhouette that animate with distance. Settings persist in config/mwloadout-advstats.json.
  - Stat numbers are TACZ's own: bars in gunsmith / weapon selection print the exact figure (64 reads 64, not a 0-100 score), DMG uses TACZ's
    distance-0 damage, the damage bar ceiling is 100 instead of 40, hovering a bar in weapon selection now has a tooltip (same as gunsmith).
  - Advanced stats button: pops in with the screen, grows on hover, own label (vanilla tooltip drew an empty box).
  - Ninja reworked after a client crash (NPE reading the volume of a not-yet-resolved sound at PlaySoundEvent time). The sound is now WRAPPED, not
    copied, so Tactical Footsteps' / Presence Footsteps' / sound-physics instances keep working underneath; the handler runs last and never throws.
    Movement sounds now include Presence Footsteps' own "presencefootsteps:pf_presence.*" ids (the old token list missed most of them), vanilla
    steps/falls/swim/splash, plus the perk's own "quietSounds"; the emitter is the nearest player to the sound (same 1.5-block rule as Tactical Footsteps).
  - Fully Loaded via Specialist: its x-ammo only ever ran while building the class from the slotted perks. Granting it mid-life now tops every loadout
    gun's reserve up to the Fully Loaded amount (NBT ammo or inventory ammo), and a class re-apply keeps it while the Specialist perk is active.
  - Gunsmith + weapon selection: small square "Advanced stats" button opens a box with the full raw readout (vs the bare weapon), kill table (STK / TTK
    against a 20 HP target at 10/25/50 m) and step-line charts of damage falloff, time-to-kill and spread with a hover read-out at any distance.
  - Stat bars: hovering one now shows the exact figure, the bare weapon's figure, the change, and that stat's breakdown (falloff steps, shots/s, ...).
  - Class hover: the compact text panel is gone - one display only (the full-card peek); the typed-name box is hidden while a peek is shown.
  - Perk numbers now take effect after an admin edit: attribute / mob-effect perks are re-applied to every online player (old values stripped
    with the OLD definition first) instead of waiting for a respawn. The perk quick-edit fields accept fractions (new: "Duration x" for
    Battle Hardened's 0.1, "Volume x" for Ninja) and no longer overwrite hand-edited "effects" JSON with a stale value - only a field you
    actually changed is written.
  - New perk NINJA (PERK 2): movement sounds (step / foot / swim / splash / fall / land / jump / wander in the sound id) of a ninja play at
    10 % volume ("volumeMultiplier", "quietSounds" in its effects JSON). Client-side at the sound engine, so vanilla steps and Presence
    Footsteps' own sounds are both covered; ninjas are recognised by a zero-value marker modifier on LUCK. Icon: textures/perks/ninja.png.
  - Gunsmith: the whole-gun home view is framed ~20 % closer and seen from an isometric right, low-to-medium angle (28 deg turn, 14 deg
    elevation, muzzle toward the viewer); attachment close-ups keep the exact TACZ refit camera.
  - Class rail: hovering an unselected class for ~0.2 s now shows that class in the real card column (weapons + attachments, perks,
    killstreaks) with a PREVIEW tag - read-only, nothing is selected; the compact panel shows until it kicks in.
  - Blueprints: a weapon picked in the gunsmith's Blueprints panel now stays equipped when the gunsmith was opened from weapon selection
    too. The picker's stale preview (the platform) used to overwrite it on the next EQUIP; the preview now follows the equipped blueprint,
    and its platform row is highlighted as selected.
  - Perk icons: new 701x701 textures (resolution kept). New PerkIcons renderer area-averages the master to the exact on-screen pixel size
    (logical size x GUI scale) once and blits it 1:1 - no nearest-neighbour aliasing at any GUI scale. Loadout perk card is taller and its
    icons fill the cell (was 13-22 px); perk picker and streak HUD use the same renderer.
  - Battle Hardened: immune to Glowing from every source ("immune_effects":["minecraft:glowing"] in the perk's data, same selectors as
    "reduces_effects"); the Tracker perk's own hit mark is the only exception. Existing worlds get the field added once.
  - Stats: armor_ignore + pierce are ONE stat, "BULLET PENETRATION" (gun readout, attachment numbers and pros/cons). "function" (lua) modifiers
    are now read (evaluated through TACZ's AttachmentPropertyManager on a unit value), so speed mags etc. show real pros/cons instead of nothing.
    Debuffs a round inflicts (gunsmithlib_extension.potion_effects, e.g. slowness / burning) are read from the attachment's raw pack data
    (AttachmentRaw: <game>/tacz zips + folders, then the owning mod jar) and listed as "INFLICTS ..." lines.
  - Loadout weapon cards show only the gold attachment squares - the "n/max" text is gone.
  - Class rail: hovering a class that is not selected shows a panel with its whole kit (every slot + attachment squares, perks, streaks).
  - Weapon select: the UNEQUIP button is gone; EQUIP (accent button, bottom-left where UNEQUIP was) is the only confirm action.
  - Performance: background blur shader takes 7 texture fetches per pass instead of 13 (same extent, ~45% cheaper on the two full-screen
    passes); per-frame labels use a cached translation lookup (MWStyle.tr, flushed on screen open); the loadout XP bar maths and strings are
    memoized instead of re-running the level search / curve sums every frame; easeOutCubic no longer calls Math.pow.
  - Battle Hardened: now declared in perk data as {"type":"special","key":"reduces_effects","reduces_effects":["@harmful"],"durationMultiplier":0.1}
    (selectors: @harmful / @beneficial / @neutral / @all / effect id / "namespace:*"). The shortening is applied at the end of the server tick that
    the effect landed in, not from inside Forge's MobEffectEvent.Added (the original addEffect() could overwrite the swap). Existing worlds get the
    entry added once (Rules.battleHardenedReducesMigrated); /mwloadout resetperks also restores it.
  - Gunsmith: ESC / CONFIRM while an attachment list is open now only closes that list (back to the gunsmith view); open build menus close first.
    A second press confirms and leaves. Applies when the gunsmith was opened from the loadout screen or from weapon selection.
  - Loadout rail: push-OUT now moves at the same speed as push-IN (the easeOutCubic mapping made the way back start slowly).
- Hotfix v33: dying (and relogging) no longer resets field-upgrade cooldowns. Death only drops the per-slot bookkeeping; the per-item recharge
  timers keep running in game time (also while dead) and are picked up again when the class is re-equipped on respawn.
- Round v32:
  - Loadout push-in: the class rail now leaves COMPLETELY (no leftover border / sliver, only a small arrow handle), and the loadout panel follows
    it to a 12px left margin by its own offset.
  - Battle Hardened: negative effects now last 90% SHORTER (x0.1, minimum 1 tick) - clarified from "10% shorter".
  - Field upgrades: the recharge timer now belongs to the ITEM, not the slot/class - swapping to a class that carries the same upgrade (any slot)
    neither resets nor refreshes it (cleared on death only). Also: when our timer says "ready" but a leftover vanilla cooldown overlay still blocks
    the click (it can outlive the polled timer by a few ticks) it is removed, fixing "cooldown over but cannot use".
  - Gold attachment squares outside the gunsmith (loadout weapon cards + weapon picker rows) show exactly the INSTALLED attachments - no empty boxes.
    The picker squares come from the gun's saved build.
  - Weapon picker layout: the search bar moved above the weapon list (left); the right side is one column - model on top, then an info block whose
    height follows the content (long descriptions get room, short guns leave no dead space; ellipsis if it still does not fit), stats lines cached.
  - Gunsmith CONFIRM now returns to the weapon picker it was opened from (parent = the picker; blueprint switches keep that parent) instead of
    jumping out to the loadout screen.
  - Loadout: the CARDS and KILLFEED buttons moved into the Settings screen (rows "Card customization" / "Killfeed settings"); only SETTINGS remains.
- Round v31:
  - Battle Hardened: all NEGATIVE potion effects now last 10% shorter (x0.9) - checked by perk id, so it also works on servers that
    saved an older perk definition. (Instant / infinite effects are untouched.) Description updated.
  - Gunsmith attachment models: the refit view now renders with the FIRST_PERSON item context exactly like TACZ's own refit screen. In any
    other context TACZ draws attachments with their LOD json unless the camera is "close" (RenderDistance#inRenderHighPolyModelDistance),
    which our matrices never satisfied - that was the LOD look. Full GEO now.
  - Selecting an attachment no longer leaves the select view (keep browsing / swapping); clicking the equipped one still removes it.
  - Hovering a candidate previews it ATTACHED on the gun model (the real build is untouched until you click).
  - The camera is reset to its default angle / zoom whenever a slot is opened or closed.
  - Drag rewrite: rotation is now about a pivot - the gun's own centre in the whole-gun view (turns in place like the weapon picker), sliding
    to the camera axis in a slot close-up - instead of about the camera origin.
  - Loadout push in / out: the WHOLE loadout panel (class name, cards, killstreak column) now follows the class rail to the left edge
    instead of leaving the panel where the rail used to be; clicks/hover are offset accordingly.
  - Slimmer loadout: 3-row weapon card (slot label + gold attachment squares + n/max on one line, class, name), smaller card heights (weapon 48,
    melee/equipment 28, perks 40), narrower column (max 246px) and killstreak column.
- Round v30:
  - Gunsmith model: back to the raw GEO model (what the guns use) - the LOD experiment from v29 is reverted.
  - FIXED the model being impossible to rotate / "stuck in an item frame": the depth squeeze added in v29 was applied BEFORE the
    rotation, so turning the gun squashed its length into the flattened axis. It is now applied after the rotation. The select view also
    used to close the list when you clicked the gun side - clicking (left OR right button) on the gun half now grabs it to rotate; right
    mouse drags in the hub as well.
  - Icon flicker: hub <-> select view switches instantly and the hub cards appear together (item icons cannot be alpha-faded, so any
    cross-fade made them pop). Only the TACZ camera glides.
  - Weapon picker cards: fixed 3-row rhythm with NO scaled fonts (row 1 EQUIPPED tag + slot squares, row 2 name, row 3 class/ammo ellipsised
    with "..." instead of hard-cut), slightly wider cards (36% of the screen, 190-250px) and a narrower silhouette so nothing overlaps.
    The slide-out button and the loadout weapon dock now read "BUILD" instead of "GUNSMITH".
  - Loadout class rail: flat slim rows (hover wash + hairline divider instead of boxed tiles) and the push in / push out animation from the
    reference video: after you pick a class the rail slides away to the left (a thin handle with an arrow stays), hover the left edge to push it
    out again, it tucks away when the mouse leaves. The EQUIP button stays reachable. Rows are not clickable while pushed in.
  - /mwloadout zone: added `zone add <from> <to>` (exact box, works from the console), `zone here` (are you inside one - also explains the op
    bypass), `zone clear`, and `zone remove` now reports an unknown index instead of silently succeeding. Note: ops / creative bypass all edit
    rules (so zones look "inert" to an admin) unless restrictOpAndCreative is on.
- Performance + polish round (v29):
  - PERFORMANCE (the freeze on every open): the server re-sent the WHOLE registry (every gun / attachment / perk, up to ~1 MB of JSON) each
    time the loadout was opened, the client re-parsed all of it and then wiped the icon, gun-HUD and display-name caches, so every frame after
    that rebuilt hundreds of item stacks. Now: the server only sends the registry when it differs from what that client already holds
    (per-player hash; join and the admin screen still force a send), and the client skips an identical registry entirely (no parse, no cache
    wipe). Also cached per frame work: TaczStats#installedAttachments (allowed-type set per gun), the weapon description (tooltip lookup +
    word wrap) in the weapon picker. New Settings option "Background blur" (the full-screen gaussian blur runs every frame - turn it off on weak GPUs).
  - Blueprint switch: no close/open fade any more - the screen is swapped instantly (the new instance starts "fully open").
  - Gunsmith model: uses the pack's LOD model (what normal item display uses) instead of the raw GEO model whenever the LOD model carries the
    refit nodes. Fixed the model vanishing when zooming in / installing attachments: depth is squeezed (the gun sat beyond the GUI far plane
    at big zoom), pixel scale capped, user zoom limited to 0.4-2.5x in the refit view, NaN matrices rejected, a model without a muzzle node uses
    a class-based length instead of zooming absurdly far in, and a failing frame falls back for that frame only (logged once; 5 failures in a row
    switch the refit camera off for that screen).
  - Gunsmith UI: the build dropdown list is opaque and hides the BLUEPRINTS / MODE / FLIP row while open (they were drawn through it).
  - Weapon picker cards: name moved up; the class line stops before the EQUIPPED tag so they can never overlap.
  - Killstreak callouts: YOUR OWN use is gold (config streakCalloutSelfColor, default #B58A1E) instead of ally blue.
  - Toasts: master switch + per-kind switches (killstreak/specialist, level-ups, equip, other notices), duration, size, opacity and position - all
    in the Settings screen (now a scrolling list) and in the client config. Callouts: on/off plus separate switches for self / allies / enemies.
- Big fix round (v28):
  - REFIT CAMERA - ported from TACZ itself (read from the 1.1.8 bytecode of FirstPersonRenderGunEvent#applyFirstPersonPositioningTransform /
    #getPositioningNodeInverse and GunItemRendererWrapper): the gunsmith draws the gun through the INVERSE of the model's refit-view node
    chain (the exact math, incl. the root-part case), with TACZ's own camera chain (translate 1.5 / rotateZ 180 / translate 1.5 / refit matrix),
    rendered with BedrockGunModel#render directly. Hub = the pack's NONE (whole-gun) view, select view = the view node of the slot being edited,
    transitions interpolated with TACZ's MathUtil#applyMatrixLerp. Muzzle side and gun length are measured from the model (no FLIP guess; FLIP is
    only shown for packs whose model has no refit nodes, which fall back to the plain side view). Dragging orbits around the point the view looks
    at; 5 s idle returns to default. Everything is wrapped so a failure falls back instead of crashing, and GL/pose state is restored in finally.
  - Cinematic builds: the row of "Custom n" tabs is ONE dropdown button (current build + count) that opens a list (select / + NEW BUILD,
    right-click = the existing rename/duplicate/delete menu). Select-view title moved under it (it overlapped the tabs).
  - Flicker when leaving the attachment picker: item icons (cards + list rows) are now faded with a veil instead of being switched on/off at a
    threshold.
  - Slot pickers (all of them): one click only PREVIEWS (big 3D model, drag/zoom, BUILDS slides out); double click, Enter or the new EQUIP button
    equips. The row you previewed gets an accent border, the equipped one a gold "EQUIPPED" tag (tick glyph removed).
  - Overkill / duplicate weapons: a gun already held in another weapon slot is hidden in BOTH directions (primary <-> secondary), by game id and
    blueprint platform - it used to hide only primary->secondary, so the server silently dropped the duplicate.
  - Killstreaks: dying no longer deletes earned-but-unused rewards. The streak counter still resets; carried streak items are stashed at death
    and handed back on respawn, queued ones stay queued. Hardcore option: streakItemsLostOnDeath=true in the config.
  - Loadout zones: the implicit "spawn bubble" (radius 6 around every respawn point / world spawn) was acting as an edit zone everywhere; it is now OFF by
    default (spawnZoneRadius=0). Admin-defined zones are unchanged. (Existing configs that saved 6 keep it - set it to 0.)
  - Weapon descriptions: if the index "tooltip" field is not a lang key but plain text, that text is shown directly.
- Build fix (v27): CommonAttachmentIndex has no getTooltip(); the tooltip lives on its pojo (index.getPojo().getTooltip()), checked against the TACZ jar.
- Fixes + TACZ hook round (v26, using the supplied tacz-1.20.1-1.1.8-hotfix2.jar):
  - ROOT CAUSE of "invisible boxes hiding stats" and "zoom swallows the screen, nothing clickable": GunPreview leaves its depth (z=200)
    in the depth buffer, so everything the screen drew afterwards (z~0) was hidden behind that slab. GunPreview now clears the depth
    buffer after drawing.
  - Refit hook: the select-view zoom now reads the gun model's own REFIT VIEW nodes (BedrockGunModel#getRefitAttachmentViewPath, the nodes
    TACZ's GunRefitScreen zooms to) plus the fixed-origin / muzzle nodes to place and aim the camera; guns whose model defines no node
    fall back to the fixed per-type guess. Zoom is gentler (1.7x). TACZ's RefitTransform itself drives the first-person hand model, so it
    cannot be reused inside a plain screen - the node data is what is shared.
  - Descriptions: TACZ gun "tooltip" (CommonGunIndex#getPojo().getTooltip(), a lang key) is shown italic under the name in the weapon
    picker, wrapped to the free space; attachment descriptions (same field on attachments) show in the gunsmith select view. DMG/HS/PIERCE
    lines moved into the left block, one per row (they ran off the right edge).
  - Weapon selection: gold dots now show each gun's SAVED build (not just the selected row); the model uses that build too. Non-gun items
    (LR Tactical grenades, field upgrades ...) show as drag/zoom-able 3D models instead of a flat icon (GunPreview#updateItem).
  - Model interaction: free zoom (0.35x-3.6x) and wide pitch; after 5 s without touching, angle and zoom glide back to the default view.
  - Cinematic gunsmith: removed the check-mark badge; BLUEPRINTS / MODE / FLIP are one joined strip.
- Gunsmith + weapon selection rework (v25):
  - GUN MODEL (gunsmith): shown fully horizontal (side view), no spin, drag to rotate (springs back), scroll to zoom. The model is no
    longer clipped to an invisible box - long rifles keep both ends - and it is drawn BEHIND every card/text. Size is fitted per
    weapon class (pistol ... sniper). FLIP swaps the muzzle side (yaw 0 / 180) if a model faces the other way; remembered.
  - Weapon selection / killstreak weapon screens keep the original angled idle-spin preview (the gunsmith side view is a separate
    mode of GunPreview now - it was leaking into them and showing guns end-on).
  - Select view: the gun eases (zoom + pan) towards the attachment position of the slot being edited (muzzle -> front, stock -> rear,
    scope -> top, grip/laser -> underside, mag -> bottom). This is our own approximation of TACZ's refit zoom - no TACZ jar is bundled
    here, so TACZ's RefitTransform could not be hooked or verified.
  - Cinematic hub header: build tabs (16px) and BLUEPRINTS / MODE / FLIP now form two compact rows under the title, text-sized, no
    longer stacking over the card columns (left column starts at y=96, right at y=60). Footer buttons are text-sized (DESELECT ALL no
    longer overflows its box) in both layouts.
  - Attachment list: the "None" row is gone (clicking the equipped attachment again takes it off); rows are indexed from 0 and the
    hover/pros-cons/click paths were updated. CONFIRM / SAVE / CLEAR / search box work with ONE click while the select view is open
    (they used to be eaten by "click outside closes the list").
  - Weapon selection (MW layout): narrow card list on the left, the BUILDS button slides out of the SELECTED card, big rotatable model on
    the right, bold name + class/ammo line + the item's own tooltip as description under it, stat bars (value + delta vs equipped) in the
    bottom right. Gold slot squares sit in the card's top-right corner (no overlap with text). Click = select, second quick click or
    Enter / BACK = confirm; right-click menu unchanged. Search box moved to the top-right (160px), footer buttons text-sized.
- Gunsmith fixes (v24):
  - Layout: replaced the scattered ring of cards with a deterministic two-column layout - cards can no longer overlap each other,
    the build tabs, the stats block or the footer. Front attachments (muzzle, barrel, laser, underbarrel) stack on the side the
    muzzle faces, the rest on the other side; a full column overflows into the other one; card height steps 34/30/26px on small
    screens. The gun stage is the space between the columns.
  - Gun preview: no more idle spin. Shown side-on (horizontal), scaled to fit the stage, only moves while dragged (springs back
    on release). New FLIP button (hub row next to MODE) swaps which side the muzzle faces and mirrors the columns - remembered.
    If the model faces the wrong way for you, press FLIP once.
  - FIXED BLUEPRINTS (and MODE / any button under the stage): the gun stage swallowed clicks before widgets saw them. Widgets now
    get the click first.
  - FIXED crash in renderHotspotZones (renderItem on an attachment icon): icons are validated against TACZ's attachment index,
    wrapped in try/catch (bad ones are skipped and remembered) and a leaked pose push is unwound. A throw inside a faded group can
    no longer leave the next frame faded (localAlpha resets every frame + try/finally).
  - Attachment names strip colour codes (an addon's "Incendiary Upgrade" was drawn red instead of gold).
  - Weapon selection: gold slot dots removed (they overlapped the text); rows keep the selected glow.
- Build fix (v23): FlatButton click flash called accent() which resolved to FlatButton.accent() (the style setter) instead of MWStyle.accent() -> compile error. Now MWStyle.accent().
- Gunsmith rework, cinematic layout (v22, modelled on the MW reference screenshots/video):
  - HUB: slot cards ringing the gun restyled - filled card = gold tint + gold edge + attachment icon + gold name with the slot
    type underneath + gold check badge on the corner; empty card = quiet "+ TYPE". Hover glow, staggered fade/rise entrance.
    Card size adapts to screen width (120-176px); bottom cards clear the stats block.
  - Stats moved onto the stage with no frame: one compact row per stat (LABEL | bar | value | +/- delta while hovering a
    candidate) plus FIRE RATE and MAGAZINE column; "ATTACHMENTS n/max" with gold squares above it. Weapon class shown as a
    subtitle under the gun name.
  - SELECT VIEW: clicking a card hides the hub (cards fade out), docks the candidate list on the left (hover = accent wash,
    equipped = gold wash + gold bar + ACTIVE tag), shows the big attachment name with PROS / CONS in two columns on the right, and
    the gun glides to the lower right. Click outside / ESC goes back. Replaces the old centred popup + floating tooltip.
  - Buttons compacted: DONE / CLEAR / SAVE BUILD smaller, BLUEPRINTS + MODE side by side (hidden in the select view).
  - New MWStyle.localAlpha()/uiAlphaLocal() for fading a single group of elements independently of the screen fade.
- QoL + polish round (v21):
  - FIXED wrong attachment count: the saved fire mode lives in the same map as the attachments (key `__fireMode`) and was counted
    as a 6th mod. New `TaczStats.installedAttachments()` ignores it, blank values and types the gun does not allow. Also fixes the
    "(n)" in the gunsmith build tabs.
  - Weapon cards (primary / secondary): one GOLD dot per attachment slot the gun really allows (not a fixed 5), filled = installed,
    plus a compact "n/max" counter. New `TaczStats.attachmentSlotCount()` (cached).
  - Weapon cards: the selected gun's category now shows on its own line above the gun name.
  - Click flash: soft accent flash (~280ms) on cards, gunsmith dock, class rows, weapon-picker rows and every FlatButton.
  - Selected class: accent wash, bright edge, pulsing outer glow and a left bar (rail scissor widened so the glow is not clipped).
  - GLOBAL FADE: every fill / gradient / text / icon-blit in the GUI package now goes through MWStyle.fillF / gradF / strF / centerF /
    blitF, which multiply alpha by the screen open/close fade (uiAlpha()). Nothing pops in or out any more on ANY screen (loadout,
    perk picker, picker, gunsmith, settings, killstreak, customizations, admin...). uiAlpha() reverts to 1 when no screen refreshed it
    recently, so HUD code is never affected. Item icons (renderItem) and the 3D gun preview cannot be alpha-tinted and still
    appear with the cascade veil only.
  - FlatButton no longer applies its fade twice.
  - Weapon picker (phase B of the gunsmith rework, see GUNSMITH-REWORK-PLAN.md): rows 34 -> 38px with a gold slot-dot line, selected-row
    glow.
- Fade sync fix (v20): the screen-chrome close fade was ease-in (t*t), so title, header, class-name text, lines, scroll gradients,
  scrollbar, level bar and button text stayed near full brightness until the very end - they were the last things left after
  the cards had retracted. Close now uses the same ease-out curve as the cards (MWStyle.closeProgress), so everything fades
  together; the dim/blur backdrop keeps its own linear fade. Open ramp 260 -> 320ms to blend better with the card cascade.
  Class-rail accent bar now fades too.
- UI polish round (v19):
  - Padding: boxes taller so text no longer hugs the outline at GUI scale 3/4. Card base heights up (weapon 58, melee/equipment 31,
    perks 44) with hard floors (52/29/29/40) - below that the column scrolls instead of squashing. Equipment cards centre their
    two text lines, weapon cards keep a fixed top/bottom pad, perk/killstreak cards and class-rail rows (15-20px) got extra
    padding, class-name box 12 -> 14px. Perk picker tabs 54 -> 58px, rows 26 -> 28px, toggle 22 -> 24px.
  - Animation sync: close duration is now ONE clock (MWStyle.ANIM_SCREEN_CLOSE = 200ms, open 260ms); LoadoutScreen retract used 220ms
    while chrome faded over 120ms, which made lines/buttons vanish before the cards.
  - Everything now fades with the screen instead of popping: "CLASSES" header, class-name label, class-name text, the line under
    it (draws on / retracts), level + XP bar, scrollbar, scroll-edge gradients, swap banner. Card content (text, icons, gun
    silhouettes) fades through a veil instead of appearing at a 60% threshold; class-rail rows fade as they glide.
  - Perk picker: tabs, toggle, rows (staggered glide + fade) and the detail card follow the same open/close fade.
  - New MWStyle helpers: fa(), mulAlpha(), text(), textCentered().
- Fix + polish round (v18):
  - FIXED field upgrade slot could not be emptied / stayed stuck (even with the mod switched off):
    - FieldUpgrades now counts the stack on the mouse cursor as "carried" - picking the item up used to look like it was
      spent, so the slot was refilled instantly and the recharge restarted.
    - Nothing is treated as a use or restocked while the player is rearranging (cursor stack / any open container).
    - Restock only happens when a stack that existed really got spent; it never overwrites another item in the slot any
      more (goes to the bag instead).
    - With the loadout gamerule OFF: field-upgrade state is released (recharge overlay removed, no restock, no use
      block), and the Q-drop guard / death-drop guard for loadout items no longer apply.
  - Perk picker: Specialist strip redesigned. The 2/4/6 KILL tabs no longer get squashed flat; the toggle stays fixed
    and the tabs slide out of its right edge (staggered, fading in, clipped at the toggle) and slide back in. Tab size
    adapts to the screen width so everything always fits.
  - Loadout UI slimmer: narrower class rail and card column, smaller card heights/gaps, tighter margins.
  - New command `/mwloadout equip <player|@a>` (op level 2): re-applies the class each target currently has selected.
    Only the gear is rebuilt - killstreak progress, earned streak items and Specialist toggle/picks are kept, and
    edit-window rules are bypassed. Reports how many players were equipped / skipped (loadout gamerule off or no class).
    Added to `/mwloadout help` (lang en_us + vi_vn).
- Polish round (v16):
  - Killstreak use banners now track ANY killstreak item (thrown / placed / deployed / consumed): consumption tracking per
    player (KillstreakItems.tickConsumption) + use-start + air right-click + dry guns; tossing, death and class swaps are
    suppressed. Banner config: anchor (default top_left), offsets, scale, duration, max stack, colours, icon.
  - Field upgrades no longer start recharging on a bare click: recharge starts when the stack actually shrinks (placed /
    thrown / consumed); moving the item between slots is not a use; reusable tools opt in with catalog "Recharge On Click".
  - Loadout UI slimmed: thin drawn-on frames (MWStyle.thinTile), thinner cards, silhouette takes ~64% of the weapon card,
    compact class rail that always shows "+ NEW CLASS", all fits without scrolling at normal GUI sizes. Retract animation on
    close / child screens, cascade replays on class switch, sliding accent bar between classes, hover brackets + growing underline.
  - Selection feedback is now a soft sweeping fill instead of a bouncing pop (perks, killstreaks, rows, slots).
  - Perk picker: Specialist kill-tabs push out of / retract into the toggle.
  - Perf: effective-killstreak list cached in the loadout, admin filter counts + stat rows cached (400 ms), consumption tracking
    only walks inventories, gun silhouettes smoothed once per texture (config gunHudSmoothing).
- Loadout redesign + systems (v15):
  - Loadout screen re-laid out after the MW reference: tall full-width weapon cards (big silhouette, name below, attachment
    squares), melee full-width, left stack TACTICAL / LETHAL / FIELD UPGRADE / PERKS with the vertical KILLSTREAK column
    aligned on the right; card heights shrink together to fit. Opening cascade animation (rail and cards slide in staggered,
    killstreaks from the right; not replayed when returning from a child screen).
  - Guns with no attachment slots get no GUNSMITH button (loadout and gun picker); EXPLOSIVE / INCENDIARY bars removed from
    gun info (attachment lines stay).
  - Field upgrades (extra* slots) recharge like MW (FieldUpgrades): new equip charges 80s first, ready stays ready across
    class swaps while the same item is equipped, use restarts the recharge (consumed item is handed back, charging), death
    resets; shown with Minecraft's item-cooldown overlay and blocks use. Rule `fieldUpgradeCooldownSeconds`, per-item
    `cooldownSeconds` on catalog entries (admin: Field Cooldown).
  - New perk TUNE UP (perk 1, icon included): -40% field upgrade recharge (`reductionPercent`). Scavenger and Shrapnel no
    longer touch field upgrade slots.
  - Killstreak use callouts (StreakCallouts): top-left banner when a player uses a killstreak item - blue for allies (same
    team + self), red for enemies; icon, player name, killstreak name. S2CStreakUse packet; config [hud] streakCallout*.
- Loadout round (v14):
  - Empty killstreak slots now auto-fill with RANDOM legal picks (distinct kill costs, max 3), on the client when a class
    loads / resets and on the server at equip time (like empty perk columns); written into the class.
  - Specialist with empty / clashing staged perks rolls random pool perks (on toggle, on class swap, lazily on kills,
    and when the perk picker opens).
  - Picking a killstreak when all 3 slots hold different costs now REPLACES the oldest pick automatically (toast), no
    need to unequip first; same-cost picks still swap. StreakPool.select tracks pick order.
  - Class swap can no longer lose earned killstreak items: remembered before the loadout is rebuilt and restored
    (inventory, else dropped at the feet - never deleted) if displaced.
  - Selection animations: perk rows, killstreak tiles/slots, and the loadout screen's perk cells / killstreak entries
    bounce and flash when a pick changes (MWStyle.pop / flash).
  - Loadout layout after the MW2022 reference: taller equipment cards (small label, name, divider, bigger icon), PERKS
    card with evenly divided icon cells, vertical KILLSTREAK panel (highest cost on top, cost under each icon) beside the
    equipment cards.
- Added: `api/StreakApi` - read-only client API over the killstreak/Specialist tracker (version counter, thresholds,
  reward ids, earned flags, "still carried" flags, icons/labels, progress). Plain types only so another HUD can bind
  to it reflectively. `setExternalHud(true)` makes the built-in tiles/meter stop drawing (ModernHud does this when it
  takes over the tracker); tracking keeps updating either way. Nothing changes for installs without such a HUD.
- Added: `StreakApi.selectedPerksVersion()` / `selectedPerkIds()` / `selectedPerkLabels()` / `selectedPerkTextures()` - the
  perks the player currently has SELECTED (active class's perk picks in slot order, or the chosen Specialist perks), with
  a change counter, so an external HUD can show a "selected perks" toast whenever the loadout changes them.

## 1.0.0 - Release 1 (Stable)
- Killstreaks, Modern Warfare style:
  - `Rules.streakRewards` is now a POOL (admin, up to 10, same kill cost allowed). Each class equips at most 3, never
    two with the same kill cost (StreakPool). Stored per class (`LoadoutPreset.streaks`, null = default: the cheapest
    distinct three), sanitised server-side on save, and the server grants only the equipped ones (Hardline -1 applied).
  - New KillstreakSelectScreen (loadout > KILLSTREAK): 3 equipped slots + the pool as a grid grouped by kill tier,
    lowest first; same-tier pick swaps with a warning, a 4th distinct pick is refused with a warning, click to remove.
    The loadout card shows the equipped icons.
  - HUD redesigned after the MW reference: bracketed icon tiles (max 3, cheapest at the bottom) + a segmented vertical
    meter (one segment per kill needed for the next reward). Numbers and the big bottom-corner icon were removed;
    Specialist uses the same layout with its perk icons and 2/4/6/8. Config `streakHudTickIcons` removed.
  - Admin killstreak editor: wrapped pool grid (5 per row), max 10, duplicate items ignored on save.
- Fix: killstreak did not reset after respawn. Two causes: (1) streak items still carried (and unclaimed queue) were
  deliberately kept across death - with keepInventory they never left; new rule `streakResetOnDeath` (default on,
  RULES > KILLSTREAKS) wipes them on death. (2) Killchain's "second consecutive death resets" never happened because
  respawn cleared its flag right after the first (saved) death, so Killchain kept the streak forever; the flag now
  survives the respawn and re-arms only after a kill. Hardline's description now matches the code (item rewards only).
- Added: CoD-style killstreak tracker HUD (client/StreakHud): vertical bar bottom-right, one segment per reward, fills
  smoothly each kill, earned rungs go green with a check, big next-reward icon + "N KILLS" at the bottom, small icons per
  rung. New S2CStreakProgress packet (Hardline already applied to the thresholds), synced on kill/death/respawn/login.
  Config [hud]: streakHudEnabled, Scale, XOffset, YOffset, Opacity, Color, TickIcons, AutoHide.
- Fix: text editing in the admin panel - Ctrl+A / C / V / X now act on the focused text box instead of triggering panel
  shortcuts (Ctrl+A used to select every catalog row). Added Tab / Shift+Tab field navigation, debounced list search
  (no rebuild per keystroke), Enter in the slot box also adds.
- Fix: ADD tab text was drawn behind the BROWSE button - stacked, non-overlapping QUICK ADD / CREATIVE INVENTORY groups.
- Hardening: BULK admin action caps (5000 ids, 400 entries / id length 128 per ADDMANY); stats KPI totals cached
  (refresh 2x/second instead of every frame). Docs: ADMIN_GUIDE shortcuts + TESTING checklist items 32-46.
- Fix: ghost admin/browser buttons - drawn almost invisible but still hoverable and clickable. Cause: those screens
  never call screenChrome(), so MWStyle.chromeOpen kept the ~0 value left by the previous screen's fade-out
  (introduced with the flicker fix). New MWStyle.plainChrome() pins it to 1 each frame.
- Creative Browser layout: three boxed panels with fixed 8px gutters (filters | search + grid | controls); the
  controls moved into a stacked, captioned right panel so nothing can overlap the grid at any GUI width; chips
  never run past the panel; pick mode (used by the killstreak editor).
- Killstreak rewards can be ANY item (new StreakItemFactory): catalog id, TACZ gun id, item id (+SNBT) or lang key.
  Only real guns get the one-round magazine / auto-remove-when-empty; other items behave normally. Names, icons
  and toasts resolve to the real item name (DisplayNames.ofAny / IconCache.anyIcon).
- Killstreak editor rewritten: fixed rows (stage / ladder / editor panel / footer) so nothing overlaps; slot cards
  with explicit -/+ steppers (Shift = 5) and icon + sliding name; non-gun rewards shown as a big floating icon;
  editor panel with id box, BROWSE (whole creative inventory), searchable catalog list with draggable scrollbar.
  Fixed: +SLOT / -SLOT re-ran init() without clearing widgets (stacked duplicates) and reloaded the ladder from the
  server (discarding edits); mouse drag rotated the model even when dragging elsewhere.
- UI polish pass:
  - Admin panel: list + form now sit in bordered panels with a consistent gutter; a toolbar row inside the form
    panel (tab title / CATALOG filter); filter and STATS view pills became ONE bordered segmented control with a
    label (they used to look like a second row of tabs); bottom dock has fixed rows (panels, hint/tools row,
    action row) so RECONCILE / CLEAN STALE no longer land on the filter pills or footer buttons.
  - All admin toasts are short MW-style caps lines. In-game toasts: short headline + detail (hud.mwloadout.t.*),
    long details wrap to two lines instead of being cut; message strings (en/vi) shortened.
  - Long names (weapons, perks, attachments, blueprints, admin rows, browser lists) slide sideways while
    hovered/selected instead of being chopped (MWStyle.scrollText); idle ones end in an ellipsis.
  - Creative Browser: draggable scrollbar (grab the thumb or click the track), drag-to-paint selection with edge
    auto-scroll, Ctrl+wheel zoom (4 sizes), type-to-search, Page/Home/End, Ctrl+I invert, Esc layers.
    Smarter search: AND of words, accent/case-insensitive, relevance-ranked, in-order fuzzy ("m4a"),
    operators @mod #tab cat:xx id:xx is:gun|item|new|added|selected -exclude "phrase". Left column switches
    between TABS / MODS / CATS; active filters show as removable chips; SHOW (all/guns/items) and SORT buttons.
- Added: USE LOCK (anti loadout-swap spam). Once a non-OP player fires a loadout gun or throws / eats / spends
  a loadout item, they cannot re-equip a loadout until they respawn or `useLockSeconds` (default 60; 0 = respawn
  only) passes. Detected from inventory totals (no TACZ / throwable-mod events needed), ahead of the empty-inventory
  and zone shortcuts so "throw everything, swap in spawn, repeat" no longer works. OP/creative stay exempt unless
  restrictOpAndCreative. Toggle + seconds under RULES > ANTI-ABUSE; the loadout banner and toast explain the lock.
- Fix: admin CATALOG footer - RECONCILE / CLEAN STALE (and the RULES killstreak editor) overlapped FROM HAND /
  DELETE text; they now drop to a second row when the footer is too narrow.
- Admin panel v2 (phase 2):
  - STATS is now a dashboard: KPI cards (picks / kills / headshot % / players, with count-up), ranked animated
    bars with real names, an insight panel for the hovered row (picks + share, kills, hits, headshot, avg TTK)
    and a pick-share strip.
  - CATALOG bulk edit: Ctrl+click / Shift+click (range) / Ctrl+A multi-select, then enable / disable, move
    category, set unlock level, or delete (2-step) for the whole selection in ONE packet (new BULK admin action).
  - Creative Browser (ADD tab or Ctrl+B): every creative-tab item, searchable, filter by tab, "NEW ONLY"
    hides what is already in the catalog; auto-suggested category (TACZ gun type, LRTactical NBT, vanilla item
    class, keyword match), per-item override (right-click) or force one category, batch add in one packet.
  - Fix: build error from phase 1 (header-click block landed in hoveredHint instead of mouseClicked).
- Admin panel v2 (phase 1):
  - Forms are now grouped into titled, collapsible sections (e.g. RULES: GAMEPLAY / CLASSES / ZONES / AMMO /
    PLAYER LEVELS / WEAPON LEVELS / KILLSTREAKS) with friendly labels instead of raw field names.
  - RULES tab: the one-row list is now a section navigator with scroll-spy; click to jump.
  - Each tab has its own colour and entrance animation (SLOTS slides in, RULES unfolds, PERKS drops, CATALOG
    rises with a cascading list, ADD pops up); gliding tab underline; eased form scrolling; animated toggles.
  - Context bar under the form: hover/focus any field for a hint + format example (ammo tiers, streak rewards,
    blueprint ids...), otherwise the shortcut legend. Status chip: NEW / UNSAVED / SAVED.
  - QoL: Ctrl+S save, Ctrl+N new, Ctrl+D duplicate, Ctrl+R revert, Ctrl+F search, Up/Down browse, Ctrl+1-6 tabs,
    Enter in a field saves, Esc clears search, two-step delete, unsaved-changes guard on close, saving a new record
    now selects it (no accidental re-create) and an empty ID now says so instead of failing silently.
  - Fix: the killstreak-editor / RECONCILE / CLEAN STALE buttons only existed if the panel was re-initialised
    on that tab (resize); they now appear whenever you open the tab.
  - Fix: admin hover animations ran on a stale frame clock; the panel now ticks MWStyle.frameDt itself.
- Changed: velocity naming split by context - the gun's stat bar and barrel/muzzle-type attachments say
  MUZZLE VELOCITY, while magazine attachments (extended mags) say BULLET VELOCITY.
- Fix: Blueprint picker animation felt slow/laggy — now uses the same snappy easing as the Loadout screen
  (fade + eased strip scrolling that glides instead of jumping, clean snap at both ends).
- Fix: Blueprint text/HUD flicker. Vanilla draws text with alpha < 4/255 as fully OPAQUE (so fading text
  popped solid at both ends of the fade) and the gun HUD ignored the fade alpha entirely. Text is now skipped
  when too faint and the HUD silhouette is alpha-tinted (item-icon fallback only when nearly opaque).
- Added: explosive / incendiary magazine detection via attachment flags (`explosion: true`,
  `ignite_entity: true`) — shown as "+ EXPLOSIVE ROUNDS" / "+ INCENDIARY ROUNDS" on the attachment card
  and in the gun stat lines.
- Fix: **UI flicker on close.** Closing one loadout screen into another used to drop the backdrop
  dim to zero and fade it back in (the once-per-close flash). The backdrop is now continuous across
  screen hand-offs, widgets fade out together on close, and the backdrop fades to the world only
  when actually leaving to the game.
- Improved: animations now run on real frame time (`MWStyle.frameDt`) instead of `partialTick`, which
  snapped back every game tick and made hover/scroll/bar easing uneven.
- Changed: Blueprint picker docked bottom-centre above the button bar; stat compare reads the real
  TACZ numbers and shows percentage change (green = better; lower recoil/weight/inaccuracy counts as
  better); left/right arrow keys scroll the strip.
- Added: gunsmith stat bars BULLET SPEED (its own stat, no longer part of RANGE), EXPLOSIVE DMG and
  INCENDIARY DMG (shown only when the gun/attachment has them); attachment cards list explosive /
  incendiary damage and effective range / bullet speed as separate stats.
- Changed: attachment recoil readout - pitch cut stronger than yaw = RECOIL STEADINESS, yaw stronger =
  RECOIL STABILIZATION, equal or only one axis = VERTICAL / HORIZONTAL RECOIL CONTROL.
- Fix: weapon pick lists show only ONE variant per Blueprint family per category (e.g. one M4 variant in
  DMR instead of every one).
- Fix: **Blueprint picker is now a true modal.** Previously the attachment rows / hotspot boxes /
  stat bars behind the carousel kept receiving hover (their pros/cons tooltips drew through the
  picker and clicks could leak). While the picker is open nothing behind it gets hover, clicks,
  scroll or keyboard input; ESC closes just the picker; click outside closes it.
- Changed: Blueprint picker layout — one centred hard box (title bar / card strip / compare footer),
  the stat-compare readout lives inside the box instead of a floating tooltip, background dimmed,
  drawn above the 3D gun preview.
- Changed: `killstreakEnabled` now defaults to **off** on a fresh world (existing servers keep
  whatever they already had saved — this only affects worlds that have never written `rules.json`).
- New: **Dynamic Ammo Reserve** (`dynamicAmmoEnabled`, **on by default** — this is the standard
  reserve system now, not an opt-in extra). Reserve is computed from the gun's own TACZ magazine
  capacity (including Extended Mag and any other capacity-changing attachment) times an
  admin-editable tier multiplier (`dynamicAmmoTiers`, RULES tab): shotguns under 10 rounds x8
  (their own rule), under 5 rounds x5, under 50 x3, 50+ x2. Fully Loaded stacks an admin-editable
  `fullyLoadedMultiplier` (default x4) onto whatever tier the gun already matched — it never
  replaces it, so a high-tier gun's reserve only ever goes up, never down. Exact
  per-gun and per-entry ammo overrides (`ammoOverrides`, `entry.ammoOverride`) are removed
  entirely — dynamic ammo, or the flat `ammoByCategory` table when dynamic is off, is always the
  final word, no override layer to second-guess it. Existing servers get a one-time migration to
  the new tier set and dynamic ammo turned on when they next `/mwloadout reload`.
- Fixed: two ammo bugs found while building the above — `ammoOverrides` was silently bypassed
  whenever dynamic ammo was on, and Fully Loaded was double-applying (a hardcoded +60% multiplier
  stacked on top of its own JSON "ammo_bonus" flat bonus). Both are moot now that overrides and
  the old bonus mechanism are gone, but worth noting since either could have produced very
  confusing reserve numbers before this rework.
- Fixed: Fully Loaded's very first implementation replaced the tier multiplier with a flat x4
  instead of stacking onto it — a gun whose own tier was already above x4 (the shotgun x8 rule)
  actually lost reserve when Fully Loaded came in (an 8-round shotgun: 64 rounds normally, dropped
  to 32 with the perk equipped). Now correctly multiplies the tier's own multiplier by
  `fullyLoadedMultiplier` instead of overriding it, so equipping the perk only ever raises reserve.
- Fixed: existing servers still had the retired "ammo_bonus" JSON effect sitting on their
  `fully_loaded` perk from before it was removed from the default seed — inert (nothing reads it
  anymore) but looked like live config in the PERKS tab. One-shot migration strips it on next
  `/mwloadout reload`.
- Fixed: several admin-panel text fields (`dynamicAmmoTiers`, `streakRewards`, `editZones`) built
  their multi-entry value with real newline characters for a single-line `EditBox`, which can't
  render a line break — it showed the raw control character as a glyph instead ("LF" stamped
  inline), making the field look garbled. `dynamicAmmoTiers`/`streakRewards` now join entries with
  `;` instead of `\n`; `editZones` now serializes without pretty-printing for the field specifically
  (the on-disk `rules.json` is untouched — still pretty-printed there).
- Fixed: NEW/SAVE/DELETE/FROM HAND showed on every Admin Panel tab regardless of whether they did
  anything there — DELETE and FROM HAND in particular silently no-op'd outside their actual tabs
  (RULES for DELETE, everywhere but CATALOG for FROM HAND), with nothing in the UI hinting they
  were inert. Now shown only where they're functional: NEW/DELETE on the three browsable/editable
  list tabs (SLOTS/CATALOG/PERKS), SAVE on those plus RULES, FROM HAND on CATALOG only.
- New: Dynamic Ammo multiplier override chain — `categoryMultipliers` (RULES tab, per-category,
  e.g. force every shotgun to x10 regardless of magazine size) and a per-CATALOG-entry
  `ammoMultiplier` (CATALOG tab, GUN only — that one weapon's own multiplier). Precedence, most to
  least specific: the weapon's own multiplier > its category's multiplier > the magazine-capacity
  tier table (`dynamicAmmoTiers`). Fully Loaded still stacks `fullyLoadedMultiplier` on top of
  whichever of the three answered.
- Fixed: the Gunsmith's Blueprints panel had no height cap — a platform with enough sibling forms
  (a base gun plus several blueprint variants) rendered a list taller than the screen, with the
  top and bottom rows literally off-screen and unreachable. Capped to 8 visible rows with mouse-
  wheel scroll and up/down indicator arrows when there's more than that.
- New: per-gun attachment build slots (Gunsmith's build tabs) are no longer hard-locked to exactly
  4 — still 4 by default, but a "+" tab now lets a player grow up to 8 per weapon. Existing saves
  with 4 builds are unaffected; nothing is lost or reset.
- New: right-click a build tab for a context menu (Delete, more to come) — guarded so the last
  remaining build can never be deleted, a gun always keeps at least one. Build tabs renamed
  "Build N" -> "Custom N" and rendered bold.
- Fixed: hovering an attachment that shows a "weight" modifier used to draw it as its own pro/con
  line (e.g. "- WEIGHT") on top of the MOBILITY line already covering the same thing (computed
  from the gun's real aggregate weight, not the raw per-attachment modifier) — same concept shown
  twice under two different names. The redundant line is gone; MOBILITY still reflects it.
- New: hovering one of the six Gunsmith stat bars (ACCURACY/DAMAGE/RANGE/FIRE RATE/MOBILITY/
  CONTROL) now shows a tooltip with the real underlying TACZ number behind that bar's fill (e.g.
  actual RPM for FIRE RATE), not just the normalized bar position.
- Fixed: the cinematic Gunsmith's hotspot boxes (the ones ringing the weapon) only ever generated
  exactly 8 positions, but a gun with a 9th+ attachment type (a gunpack adding its own category
  beyond TACZ's standard set) got assigned to the SAME zone rectangle as one of the first 8 —
  both types rendered at the identical pixel position, one painted directly over the other, and
  clicks only ever reached whichever type the hit-test happened to check first. The other type
  was completely invisible and unreachable in cinematic mode (still fine in the classic layout,
  which doesn't share this zone system). Overflow types now get their own additional zone instead
  of ever sharing one.
- Changed: the Blueprint picker is now a horizontal, MW2022-style platform carousel docked
  bottom-center instead of a small vertical dropdown list. Each card shows the gun's HUD
  silhouette (the same texture TACZ's own weapon HUD uses, via GunHud — item-icon fallback when a
  pack ships none) plus its name, current platform highlighted. Card size adapts to how many
  variants the platform has: 2-3 get large cards, more shrink to a readable floor to fit all at
  once, and only beyond that does it scroll (mouse wheel or the edge arrows). Opening it scrolls
  the current platform into view, and switching platforms keeps the carousel open so several
  forms can be browsed in a row. Hover-to-compare-stats and the BLUEPRINTS toggle are unchanged.
- Fixed: Specialist's staged-perk unlock (kills 2/4/6) tracked "how many stages granted so far" as
  a plain counter compared with <= — the same shape of bug advanceItemStreaks (the item-killstreak
  reward ladder) already had a comment warning about and was written to avoid. A counter only ever
  needs to end up lower than it should be once for every stage from that point on to look
  "not yet granted" again and re-fire, announcing (and re-applying) that stage's perk repeatedly.
  Switched to the same set-based "already granted" tracking advanceItemStreaks uses, which can't
  un-remember a stage once it fires regardless of what else changes. Also fixed the toast itself
  showing the perk's raw internal id (e.g. "shrapnel") instead of its display name.
- Fixed: Sleight of Hand's `reload_speed` attribute was seeded under a namespace that doesn't
  exist (`tacz_attributes:reload_speed` — the real one is `gunsmithlib:reload_speed`; `draw_speed`
  was always correct), so the perk's reload-speed bonus silently no-op'd on every server. Existing
  configs self-heal on next `/mwloadout reload`.
- Changed: default `perks.json` retuned (movement speed, Quick Fix's on-kill burst level, Fragiles'
  Wither duration/level, Tracker's Glowing duration, Battle Hardened's armor amount, Sleight of
  Hand renamed to "Talk To Hand" and retuned) to match the balance pass being shipped for Release 1.
- Fixed: LRTactical melee/consumable/lethal/tactical default entries are hand-seeded with guessed
  virtual-item ids and were completely invisible to the stale-item scanner (only scanner-created
  "lrt_"-prefixed entries were ever checked) — a guessed id that never matched anything real on a
  given LRTactical build just sat enabled forever, showing as a broken/missing item and always
  getting picked as the default (see next item). The scanner now recognizes any LRT virtual item
  by its actual NBT content, hand-seeded or not, and disables it after two scans confirm it's
  really not there — players who already had a broken pick fall back to the slot's default entry
  automatically (existing behavior, just finally reachable for these entries).
- Fixed: `DefaultLoadouts` claimed to randomize melee/consumable/lethal/tactical/equipment picks
  but actually just grabbed whichever catalog entry came first — every player got the exact same
  (and, per the item above, possibly broken) default gear. Now genuinely randomized among enabled
  candidates, same as guns already were.
- Fixed: uninstalled attachment packs left their catalog entries enabled forever — nothing ever
  disabled a vanished attachment (unlike guns and LRT items, which already had stale-detection).
  Attachments now go through the same recovery/stale pass, so a removed pack's entries get flagged
  and cleared out after two scans instead of showing up as untranslated `item.tacz.attachment` ghosts.
- Fixed: the Admin Panel's SLOTS list wasn't sorted by `hotbarIndex` — it showed slots in whatever
  order they were created, so editing a slot's hotbar position didn't visibly reorder it, reading
  as "the number doesn't update". Now sorted by hotbar position, and saving a slot whose
  `hotbarIndex` collides with another enabled slot's is rejected with a warning instead of
  silently letting the loadout applier drop one item into the other's spot at equip time.
- Fixed: README/TESTING/code-comment ammo numbers still described the pre-2.2.0 balance (rifle
  210 / shotgun 100 / sniper 30 / pistol 120) while the actual seeded values had already moved to
  the 2.2.0 pass (ar/smg 120, lmg 200, br 100, dmr 80, sg 50, sr 25, pistol 60). Docs now match
  code (and now match the Dynamic Ammo rework above, which supersedes the flat table by default).
- Fixed: a dead `ammoByCategory.put("launcher", 2)` line in `Defaults.seedRules()` was immediately
  overwritten by a later `put("launcher", 3)` — harmless (3 always won) but confusing. Removed the
  duplicate and grouped all category seeds together.
- Cleanup: `mods.toml`'s `authors` field now reads from `${mod_authors}` (gradle.properties)
  instead of a separately hardcoded name, so the two can't drift apart again; standardized on
  L41nx. TACZ dependency stays on an unbounded version range (`[0,)`) — deliberately left open
  since TACZ updates frequently and pinning would force players to stay on an old build.
- Repo: added `.gitignore`, GitHub issue templates (bug report / feature request) and a PR
  template ahead of the public release.

## 5.2.4
- Fixed: a blueprint variant listed under a different weapon category (e.g. a DMR bundled into an
  AR's blueprint family) no longer gets hidden from its own category's slot picker — only children
  sharing the platform's own category are hidden.
- Fixed: Overkill's "no duplicate gun in both weapon slots" check now resolves a Blueprint-variant
  primary back to its platform before comparing, so it actually excludes the right entry.
- Fixed: a carried killstreak weapon could get silently overwritten when switching classes, if it
  happened to be sitting in the exact hotbar slot the new loadout was about to use. It's now moved
  aside first.
- Fixed: switching classes while editing a different (non-equipped) class no longer snaps back to
  the equipped one — a side effect of the 5.2.2 preset-index fix that over-corrected.

## 5.2.3
- Fixed: the fire-mode/blueprint buttons overlapped the new cinematic stat panel (visible overlap
  with "MODE: AUTO" cutting into the stat bars). They now dock top-left in cinematic layout.
- Fixed: hotspot boxes in cinematic Gunsmith were placed in whatever order TACZ listed attachment
  types, not by where they visually belong. Boxes now match by name (muzzle -> left, stock ->
  right, scope -> top-right, etc.) with sequential fallback for anything unmatched.

## 5.2.2
- Fixed: clicking a Build tab (1-4) could silently fail to switch once an earlier tab had a
  attachment count suffix ("Build 1 (5)") — the click hit-box was sized from the label without
  that suffix, so every tab after a filled one was offset.
- Fixed: Admin Panel list scroll got stuck after switching tabs or typing in search — the scroll
  target wasn't reset when the row count changed, so it kept chasing an out-of-range position.
- Gunsmith: removed the hotspot-to-model connector lines (visual clutter with 8 boxes on screen).
- Gunsmith: restored a slow idle turn on the weapon model (full rotation every 90s, paused while
  dragging) after user feedback that a fully static model felt lifeless.

## 5.2.1
- Animation timings consolidated into named constants (`MWStyle.ANIM_FAST/NORMAL/SCREEN_OPEN/
  SCREEN_CLOSE`) instead of duplicated magic numbers across 8+ screens.

## 5.2.0 - Leveling system
- New: per-weapon leveling, independent of player level — kills with a specific gun grant that
  gun's own XP, gating its attachments via `unlockLevel` (same field guns already used).
- New: XP curve support (`xpCurveGrowth` / `weaponXpCurveGrowth`) — each level can cost
  progressively more XP instead of a flat amount. Fully backward compatible at growth = 1.0.
- New: dedicated "LEVEL UP" / "WEAPON LEVEL UP" toast styling instead of the generic notice card.
- New: weapon-level progress bar in the cinematic Gunsmith stat panel; locked attachments show
  their required level and can't be selected until reached.

## 5.1.1 - Gunsmith redesign
- New: MW19-style "hotspot" Gunsmith layout (opt-in via `gunsmithLayout: cinematic`) — attachment
  types render as boxes around a large centered weapon model instead of a side list; clicking a
  box opens that type's picker as a popup.
- Changed: the weapon preview no longer auto-spins continuously; it sits at rest and turns only
  while dragged (later revised in 5.2.2 to a slow idle turn).

## 5.1.0 - Killfeed compatibility
- Fixed: the Customize screen's "Mode" button (Fixed/Random/Random-from-favorites) called methods
  that don't exist on the actual Killfeed mod and never did anything. Removed.
- Fixed: card favoriting called another non-existent Killfeed method. Favorites are now MWLoadout's
  own feature (stored per-player, synced), with a working "Randomize" that prefers favorites.
- Fixed: a `TEAM_COLORS` toggle pointed at a config field Killfeed doesn't have.
- New: best-effort toast placement nudges away from Killfeed's own banner if both are configured
  to the same screen corner.

## 5.0.0
- Fixed: opening the Catalog admin tab could crash the client (`ConcurrentHashMap` recursive
  update from resolving an attachment's display name from inside another cache lookup).
- New: attachments are now their own Catalog entries (enable/disable per attachment, own category
  so they never collide with gun/item slot categories).

## 4.3.6
- Fixed: switching a gun's Blueprint form could overwrite a different gun's saved attachment build.
- Fixed: renaming one class could overwrite a different class's name after switching.
- Fixed: kills made with a killstreak reward weapon incorrectly counted toward the next streak.
- Fixed: a stale killstreak/specialist notification kept a leftover "press G" hint with no
  matching keybind; rewords are granted directly now.
- Fixed: Customize screen required a separate Save; picks apply immediately now, with a working
  Randomize.
- Fixed: attachment names showed raw resource-location ids instead of their real display name.
- Fixed: LRTactical items removed from a pack never got cleaned up from the catalog like ghost
  guns already were.
- Fixed: inventory-tab item classification (melee/consumable/throwable) picked the wrong category
  when an item's NBT carried more than one type marker.
- New: admin-configurable killstreak source rules — restrict to hostile-mob-or-player kills,
  PvP-only mode, and a hostile-mob kill multiplier.

---
Versions before 4.3.6 are not covered by this changelog.
