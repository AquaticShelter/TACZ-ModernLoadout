# Changelog

All notable changes to L41NX's Modern Loadout. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
mod uses [Semantic Versioning](https://semver.org/) from 1.0.0 on:

- **major**: saved data or the network protocol changes in a way older clients / servers cannot read;
- **minor**: new features, new config keys, a protocol bump that both sides pick up together;
- **patch**: fixes only.

Before the first public release the mod went through a long run of internal versions (1.x up to 5.2.x). Those are summarised in
[Development history](#development-history-before-100) at the bottom; the full, unedited log is in
[`docs/history/CHANGELOG-dev-full.md`](docs/history/CHANGELOG-dev-full.md), and the story behind it in [`docs/HISTORY.md`](docs/HISTORY.md).

## [Unreleased]

Nothing yet.

## [1.1.0]

The release after the MW4 class / gunsmith reference: the loadout screens get their own UI scale, the HUD is left alone, and the
killstreak counter finally ignores everything a streak spawned.

### Upgrade notes
- `uiScaleCap` is replaced by **`uiScale`** (percent, `0` = auto). The old key is ignored; nothing needs to be done unless you had
  turned the cap off (`0`), in which case set `uiScale` to the size you want.
- Network protocol is **8**. Client and server must both run 1.1.0.

### Added
- **Independent loadout UI scale** (`uiScale`, client config, Cloth Config / YACL slider). The loadout screens no longer follow the
  game's GUI scale: `0` picks a size from the window height (1080p → 200 %, 1440p → 250 %, 2160p → 400 %), `50`-`400` fixes it. It is
  always held so the layout keeps at least 640 × 360 units of room.
- **Ignore Streak Sources** (`streakIgnoreListedSources`, default on): every item, TACZ gun and entity id in the streak list counts as a
  source. Turn it off to ignore only the stacks handed out as rewards, if the same weapon is also in your normal catalog.
- **Extra Excluded Ids** (`streakExcludedIds`): extra item / gun / entity ids, with `ns:*` prefixes. Both settings live in the admin
  panel, KILLSTREAKS section.
- Deferred tooltip layer for every loadout screen (`MwScreen.deferTooltip`): tooltips are queued while the screen draws and painted
  last, on a fresh depth buffer.

### Changed
- **Killstreak sources never feed the streak.** A kill made with a streak item, or by anything it spawned, no longer adds to the streak,
  for players and mobs. The old check only looked at the item in hand at the moment of the kill, so it missed weapon swaps after firing,
  a streak gun removed while its rocket was still in the air, and drones, missiles or grenades that landed the kill later. Now:
  - the entity that did the damage is checked (entity type id and a tag set at spawn), as well as the held stack (streak tag, item id,
    TACZ gun id);
  - entities are tagged at spawn when their owner holds a streak source, when their owner is already tagged (cluster bomblets), when
    they are a listed type, or, for ownerless entities, when they appear within a few ticks and blocks of a player who just used a
    streak item;
  - explosions with no direct entity look for the killer's own tagged ordnance next to the victim;
  - turrets, drones and vehicles that fire in their owner's name (SuperbWarfare auto-aim turrets and add-ons): once the turret is
    tagged, every shot leaving its barrel is tagged too. Tagged turrets are remembered per owner and survive a restart.

  Matching is by registry id, so SuperbWarfare, LRTactical, nmw2 and other packs work without a dependency. Quick Fix, Scavenger and XP
  still trigger on those kills; only the streak counter ignores them.
- **Class screen sized after the MW4 class screen.** Card heights come from the screen height (weapons 15 %, melee 9.5 %, equipment
  7.6 %, perks 9.5 %), so the column fills about 79 % of the screen. The column is capped at 244 px, class rows are numbered (01, 02…),
  FIRING RANGE sits bottom-left next to the column and a version tag follows the title. Category names such as MARKSMAN RIFLES no
  longer end in an ellipsis.
- **Buttons.** Three tiers: EQUIP is the single primary (solid signal colour, dark text); the rest keep a 1 px cut-corner edge and invert
  on hover; a disabled button gets a hatched face (`MWStyle.hatch`, `FlatButton.primary()`, `FlatButton.key(...)`).
- **BUILD dock** beside a weapon card slides out while the card is hovered and tucks back afterwards; it is no longer drawn on empty or
  non-gun slots.
- **Gunsmith slot tags** (after the MW4 gunsmith): slim cut-corner tags (30 px, at most 150 px wide) that bow around the gun. Equipped:
  signal outline and check badge; empty: grey outline and a plus. Hit boxes follow. The classic style keeps the old boxes.
- ModernHUD layout lookups (`compat/HudAvoid`) use a bound `MethodHandle` instead of reflective `Method.invoke`, and share one empty
  result when ModernHUD is absent.

### Fixed
- **The HUD changed size while a loadout screen was open.** The window has one GUI scale, and the old cap lowered it for everything
  drawn that frame, vanilla HUD and ModernHUD included. The scale is now switched per frame phase: the HUD is drawn at your own scale,
  the loadout screen at the loadout scale (with its own projection and mouse mapping), toasts at your own scale again.
- **ModernHUD avoidance measured in the wrong units** while a loadout screen was open (ModernHUD reported boxes at one scale, our tracker
  and toasts were placed at another). Both now run at the game's scale.
- **The gun model vanished when zoomed in close** in the gunsmith and weapon picker. At large zoom the model is thousands of units deep
  and its near half fell outside the GUI projection's depth range; TaCZ Accelerated also culled it against that projection. The model is
  now drawn under a projection with the same screen mapping and a much wider depth range.
- **An invisible box hid tooltips.** Tooltips were drawn in the middle of the screen's render pass, so whatever came after them (the
  gun preview's depth wipe, panels, pop-up menus, the fade veil) painted over them. They are now drawn last.

### Removed
- `uiScaleCap` (replaced by `uiScale`).
- The right-hand weapon / stat readout on the class screen (tried during the overhaul, taken out again).
- Key chips on EQUIP / CLOSE (the framed box collided with the pixel font). `MWStyle.keyChip` remains as a quiet filled plate.

## [1.0.4]

### Added
- **Vehicle / strike mods never feed the streak** (`streakIgnoreStrikeMods`, on by default, Admin > Killstreaks). Everything from
  `superbwarfare:` (vehicles, turrets, `superbwarfare:projectile`, its items), `airstrike:` and `ac130:` is ignored. This also covers
  SuperbWarfare hand-held guns; turn it off if those should count.
- **`mwloadout_ignore_kill` flag** for other mods: set it on the killer's persistent data and that kill does not count. No class names
  needed. Airstrike / AC-130 1.67.3 use it.
- `MwScreen` shared open / close transition (`mwOpen` / `mwLeave` / `mwFade`) and `docs/UI_MW4_PREP.md`, the groundwork for the MW4-style UI.

### Changed
- Package is now `dev.l41nx.mwloadout`; credits list only L41nx.
- KillFeed / kill card compat rewritten from scratch (`compat/KillFeedCompat`, `KillFeedSettingsScreen`) with the same features: KillFeed
  switches in the loadout settings, card / emblem picker, the choice saved in the profile and restored on login, ToastHud keeps clear of
  KillFeed's banner corner. Reflection only, lookups cached, nothing required.
- Sprint-to-fire / ADS stats bridge (`compat/ModernTweakBridge`) targets TACZ-ModernTweak 1.7+ (`tacz_moderntweak`).

### Fixed
- **ModernHUD overlap.** ModernHUD could no longer find `StreakApi` after the package move, so both streak trackers drew on top of each
  other. ModernHUD 2.0.1 looks up both package names. The streak tracker, toasts and streak callouts also step out of any box ModernHUD
  reports (`compat/HudAvoid`, reads ModernHUD's `HudLayout`; eased, no jump), even with ModernHUD's own tracker off.
- Sights no longer show a sprint-to-fire stat on attachment cards, pros / cons and the Advanced Stats build comparison, matching
  TACZ-ModernTweak in game.
- A kill with a normal gun no longer drops out of the streak because a streak item sits in the off hand: the held-item check only applies
  when the killer hit directly.

## [1.0.3]

### Fixed
- **Quick throw froze for a beat with Fast Hands** (TACZ-ModernTweak). `mw:draw_speed` sped up every animation clip whose name starts with
  `draw`, including the quick-throw clip `draw_up`, which then ended early. Draw / reload clips now only change speed while the item in hand
  is really being drawn / reloaded; other mods' overlay clips (quick throw, stim, armor plate) keep their own timing.

## [1.0.2]

### Added
- **Built-in MW attributes**, so no attribute library is needed any more (ApothicAttributes / tacz_attributes / gunsmithlib are no longer
  used by any default perk). All are player speed multipliers, base 1.0:
  - `mw:reload_speed`: TACZ reloads, server timing and the reload animation;
  - `mw:draw_speed`: drawing / putting away TACZ guns and every LRTactical item (melee, throwables, consumables), timing and animation;
  - `mw:ads_speed`: aim down sights (server and client).
- **Firing range per player**: everyone gets their own copy of the range (1024 blocks apart in the range dimension), kept for good.
  Worlds that already had the range keep it as the first plot.
- **FIRING RANGE / LEAVE RANGE** button on the class screen, under CLOUD / TO DEVICE.
- **TaCZ Additions compat**: with **Tracker** the scope does not sway while aiming and you never need to hold your breath.

### Changed
- **Fast Hands** uses `mw:reload_speed` +0.35 and `mw:draw_speed` +0.35 (1.35×). Existing configs are migrated once (old attribute ids
  rewritten, the old default +0.3 becomes +0.35, an admin's own amount is kept).

## [1.0.1]

### Added
- **TACZ-ModernTweak bridge** (soft, no dependency): Advanced Stats shows a **Sprint to fire** row and the real (quicker) aim time, and
  attachments that change ADS also list **SPRINT TO FIRE**. Without the mod nothing changes.

### Changed
- **Ghost** also keeps you off enemy **UAV** sweeps and stops your gunfire from pinging you on enemy minimaps (UAV Killstreaks 2.1.0+).
  Advanced UAV still shows you. The perk text updates itself if it is still the old default.

## [1.0.0] - first public release

### Added

**Classes and loadouts**
- Class screen with up to 20 saved classes per player, nine configurable slots, rename, pin, duplicate, reset, randomize, a right-click
  menu and keyboard control (up / down to change class, Enter to equip, Tab to pin the class rail).
- Server-side edit rules: after respawn, after a stretch without damage, inside edit zones, creative / spectator and Peaceful. A respawn
  rebuilds gear from scratch, and a use lock stops swapping classes right after using loadout gear.
- Player level and per-weapon levels, with attachments unlocked by weapon level.

**Gunsmith**
- Attachment budget (shown as `n/max`), build slots (4 by default, up to 8) per gun, Blueprint variants, fire mode memory.
- Live stat bars, compare view and an Advanced Stats panel (damage by distance and zone, time to kill, spread, recoil pattern). Shotgun
  damage is worked out per pellet. Stat tooltips say which way is better and what the number means.
- Reserve ammo follows the real magazine size, extended mags included, with editable multiplier tiers.
- Laser colour slider, home view for the gun model (press V to save the angle), `gunsmithModelZoom`.
- PREVIEW mode: the UI slides away and the gun fills the window; drag to rotate, wheel to zoom.
- TACZ 1.2.0 optic risers (RISER row in the select popup; a mount, never counted as an attachment).
- Separate AMMO TYPE and MAGAZINE slots (`dualAmmoSlot`, optional Mixin that appends an `AMMO_MOD` attachment type).
- The equipped attachment is listed first when a section is reopened.

**Perks, killstreaks, Specialist**
- 18 perks defined as data, including Underkill (the mirror of Overkill: two secondaries).
- Killstreak rewards with a tracker HUD, kill banners and a radial menu on <kbd>X</kbd> (hold, move, release). TACZ guns are taken in
  hand; other streak items are taken in hand and used for you; once spent you are put back on the previous weapon (`radialNoReturn`).
- Killstreak guns carry editable ammo (BULLETS / RESERVE in the editor, `5=tacz:rpg7|1/2` in text form). The reserve rides inside the
  gun, so it never touches the player's own ammo.
- Specialist: one perk at 2, 4 and 6 kills, every available perk at 8.
- Killchain keeps your chain through a death only while there is still a streak item left to earn.

**Admin**
- In-game panel for slots, catalog, perks, rules and streak rewards, plus a stats dashboard. Live sync to all players.
- Killstreak pool of up to 50 rewards with a scrolling slot ladder.
- Edit zones, `/mwloadout` command set, op-level permission checks on every admin packet and command.

**Data and cloud**
- All data lives in a `mwloadout/` folder outside `config/` (`data/`, `players/`, `cloud/`, `client/`). Old `config/mwloadout` data is
  copied over on first start and left as a backup. `-Dmwloadout.dir=` moves it.
- Cloud copy of your classes on your own machine: CLOUD sends it to a server, TO DEVICE saves what a server holds back to your machine
  (previous copy backed up first). Import is chunked, bounded and checked on the server.

**Client**
- Perk icons under your hit marker when your TACZ bullet hits another player.
- Angular plate UI style (`angularUi`), toasts with a position per kind, killstreak HUD and banner positions, High Alert overlay.
- `gunPreview3d`: the weapon picker can show TACZ's flat icon instead of the 3D model (for VBO-accelerated or accelerated-rendering setups).
- `hideHudBehindMenus` (default on): the HUD is not drawn behind the blurred loadout menus; the blur runs at most once per frame.
- Config screen through YACL 3.6 or Cloth Config (both optional), falling back to the in-game settings.
- Boot log banner with the mod version, the TACZ version and which version-dependent features are active.

### Changed
- Network protocol is 7. Client and server must run the same version.
- Specialist offers every enabled perk by default (`specialistPoolAll`).
- Killstreak radial: an item taken from the main inventory is swapped back with the previous weapon; death keeps a half-used streak gun
  half used.

### Fixed
- TACZ 1.2.0 removed `RenderDistance.markGuiRenderTimestamp()`; it is looked up by reflection and skipped there.
- Version-dependent features switch themselves off for the session after a failure (one log line) instead of breaking the screen.
- Attachment selection, extended-mag ammo, class list reset to 10, cloud import on large profiles, a render-thread crash in Advanced
  Stats, shotgun falloff scaling, background flashing between loadout screens.
- Empty `catch` blocks log once per call site through `MWLog`.

### Known issues
- One unconfirmed crash report on Forge 47.4.28; the mod is tested on 47.4.10.

## Development history (before 1.0.0)

| Stage | Internal versions | What landed |
|---|---|---|
| Foundation | 1.0 - 1.2 | Loadout screen, nine slots, perk columns, dynamic reserve ammo, gunsmith memory and model preview, NBT ammo handling. |
| Rules | 1.3 - 1.5 | Edit rules ("change and play"), deploy rules, customization screen, regeneration and restock behaviour. |
| Balance | 1.6 - 1.9 | Perk balance pass, Shrapnel rework, optional tacz_attributes support, range stat, admin quality-of-life. |
| Streaks | 2.0 - 2.12 | Killstreaks, Specialist, High Alert, streak editor, killstreak weapon selection, layouts, blur, smoother UI. |
| Hardening | 3.0 - 3.2 | Self-audit of exploits and edge cases, Blueprints, further server-side validation. |
| Admin | 4.0 - 4.3 | Stats dashboard, ghost-id reconciliation, fire mode persistence, exclusive test weapons, balance passes. |
| Levels and compat | 5.0 - 5.2 | KillFeed compatibility, gunsmith redesign, player and weapon leveling, polish. |
| Stabilization | pre-1.0.0 | Streak tracker HUD, use lock, explosive magazine detection, bullet speed stat, build slots, data folder, cloud both ways. |

[Unreleased]: ../../compare/v1.1.0...HEAD
[1.1.0]: ../../compare/v1.0.4...v1.1.0
[1.0.4]: ../../compare/v1.0.3...v1.0.4
[1.0.3]: ../../compare/v1.0.2...v1.0.3
[1.0.2]: ../../compare/v1.0.1...v1.0.2
[1.0.1]: ../../compare/v1.0.0...v1.0.1
[1.0.0]: ../../releases/tag/v1.0.0
