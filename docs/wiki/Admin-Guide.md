# Admin Guide

Two separate config surfaces:
- **Server rules** — gameplay behavior, shared by everyone on the server. Edit in-game via the
  Admin Panel (`RULES` tab), or the registry file directly. Changes apply live.
- **Client config** — per-player visual/UX preferences. Each player's own `.toml` file
  (`config/mwloadout-client.toml`), or the in-game Settings screen where exposed. Has no effect on
  gameplay or other players.

Every setting below is optional — the shipped defaults are a complete, working configuration.

## Killstreaks & Specialist

| Setting | Default | What it does |
|---|---|---|
| `killstreakEnabled` | off | Master switch for the whole killstreak system. |
| `specialistEnabled` | on | Master switch for Specialist (bonus perks after a long streak). |
| `killstreakSourceRestricted` | on | Only hostile-mob or player kills count. Turn off to let passive/neutral mob kills count too. |
| `killstreakPvpOnly` | off | Stricter than the above — only player kills count at all, no mobs regardless of hostility. |
| `hostileMobMultiplier` | 1 | How many mob kills equal one player kill's worth of streak progress. At 10, thresholds of 3/5/7/9 become 30/50/70/90 mob kills. Only matters when mobs can count at all. |
| `streakIgnoreListedSources` | on | Kills made with a streak source, or by something it spawned, never advance the streak. On: every item, TACZ gun and entity id in the streak list is a source, even when the same weapon is also in the normal catalog. Off: only the stacks the mod handed out as rewards (and what they spawn) are ignored. |
| `streakExcludedIds` | empty | Extra ids that never feed the streak, on top of the streak list: items, TACZ gun ids or entity ids, e.g. `superbwarfare:rpg`. A trailing `*` is a prefix (`superbwarfare:*` is the whole namespace). An item id also covers entities named the same, so `superbwarfare:rpg` covers `superbwarfare:rpg_rocket`. For a turret or vehicle that is not deployed from a streak item (placed by a deployer block, say), add its ENTITY id here (`superbwarfare:hpj_11`); its shots are then ignored too. Do not add generic projectile ids such as `superbwarfare:projectile`: that would ignore every kill made with any SuperbWarfare gun. |
| `streakRewards` | ladder of guns | Kill-count -> TACZ gun id list. Edit in the admin panel's dedicated editor. |
| `specialistPerkPool` | perk id list | Which perks can be picked once Specialist is active. |

A killstreak reward is granted directly into the player's inventory the moment the threshold is
hit — there is no separate "claim" keybind.

## Progression (player level)

Off by default — zero effect until turned on, and level-gating only does anything once a Catalog
entry actually has an `unlockLevel` set.

| Setting | Default | What it does |
|---|---|---|
| `progressionEnabled` | off | Master switch. |
| `xpPerKill` | 100 | XP for a normal player kill. |
| `xpHeadshotKill` | 200 | XP for a headshot kill (replaces, doesn't add to, `xpPerKill`). |
| `xpPerLevel` | 1000 | Base XP cost per level. |
| `xpMultiplier` | 1.0 | Multiplies every XP grant — e.g. 2.0 for a "double XP" event. |
| `xpCurveGrowth` | 1.0 | 1.0 = every level costs the same (`xpPerLevel`). Above 1.0, each level costs a bit more than the last. Try 1.03-1.08 for a gentle ramp — much higher blows up fast (level 50 could need tens of millions of XP). |

A level-gated Catalog entry (gun or item) is hidden from loadout selection until the player
reaches its `unlockLevel`, set per-entry in the Catalog admin tab.

## Weapon progression (per-gun leveling)

Independent of player progression above — a server can run one, the other, both, or neither.
This is the mechanic that gates **attachments** by usage of that specific gun, not overall level.

| Setting | Default | What it does |
|---|---|---|
| `weaponProgressionEnabled` | off | Master switch. |
| `weaponXpPerKill` | 50 | Weapon XP for a kill made with that gun. |
| `weaponXpHeadshotKill` | 100 | Weapon XP for a headshot kill with that gun. |
| `weaponXpPerLevel` | 300 | Base weapon-XP cost per level. |
| `weaponXpCurveGrowth` | 1.04 | Same curve concept as `xpCurveGrowth`, applied to weapon levels. |

To gate an attachment behind a weapon level: open the Catalog admin tab, select the attachment
entry, set `unlockLevel`. It stays visible in the Gunsmith attachment list even when locked (so
players know it exists and what it takes), just greyed out with the required level shown.

## Ammo

| Setting | Default | What it does |
|---|---|---|
| `ammoByCategory` | ar/smg 120, lmg 200, br 100, dmr 80, sg 50, sr 25, pistol 60, launcher 3 | Reserve rounds per weapon category. |
| `dynamicAmmoEnabled` | **on** | Reserve is computed from the gun's own TACZ magazine capacity (honoring Extended Mag and any other capacity-changing attachment) times a tier multiplier — see `dynamicAmmoTiers` below. This is the standard reserve system now; turn off to use the flat `ammoByCategory` table for every gun instead. No per-gun-id or per-entry override layer exists anymore — dynamic ammo (or the flat table when off) is always the final word. |
| `dynamicAmmoTiers` | shotguns <10 rounds x8; <5 rounds x5; <50 x3; 50+ x2 | Magazine-capacity tiers used when `dynamicAmmoEnabled` is on. Each tier has a floor (`minCapacity`), an optional ceiling (`maxCapacity`, blank/-1 = open-ended), an optional `category` filter (blank = every category), and a `multiplier`. A category-specific tier whose range contains the magazine's capacity always wins over a universal one; among same-specificity matches, the highest floor wins. Examples: a Glock 17 (18 rounds, not a shotgun) reserves 18x3 = 54; that gun with an Extended Mag taking it to 50 rounds reserves 50x2 = 100; an 8-round shotgun reserves 8x8 = 64 (its own rule, not the universal <5/<50 tiers). Edited as `minCapacity,maxCapacity,category,multiplier` entries, semicolon-separated, one tier per entry. |
| `fullyLoadedMultiplier` | 4.0 | The Fully Loaded perk STACKS this onto whatever tier multiplier the gun already matched — it never replaces it. An 8-round shotgun (its own x8 tier, reserve 64) with Fully Loaded becomes x8*4=x32 (reserve 256), not a flat x4 that would have cut a high-tier gun's reserve down. |
| `categoryMultipliers` | (empty) | Per-category multiplier override, e.g. `sg=10` forces every shotgun to x10 regardless of magazine size, ignoring `dynamicAmmoTiers`' capacity ranges entirely for that category. A category not listed here still falls through to the tier table as normal. Edited as `category=multiplier` entries in the RULES tab, comma-separated. |
| CATALOG entry `ammoMultiplier` | 0 (unset) | GUN-only field on each Catalog entry — that ONE weapon's own multiplier, overriding both `categoryMultipliers` and the tier table for just that gun. The most specific level of the ammo-multiplier chain: weapon > category > tier. |

## Classes & attachments

| Setting | Default | What it does |
|---|---|---|
| `maxPresets` | 20 | Max saved classes per player. Hard-capped at 20 regardless of this value — raising it above 20 has no effect. |
| `attachmentPointLimit` | 100 | Total attachment "budget" per gun. Set to 0 to disable the budget entirely (any number of attachments allowed). |
| `attachmentPointCost` | 20 | Cost per installed attachment. Default 100/20 = the classic 5-attachment cap. |
| `minPerksRequired` | 1 | Perk columns that must be filled before a class can be saved. |

## Equip rules & loadout zones

| Setting | Default | What it does |
|---|---|---|
| `editSecondsAfterRespawn` | 10 | Grace period after respawn where the loadout can still be edited freely. |
| `noDamageSeconds` | 45 | "Out of combat" threshold — a player counts as being in combat (edit locked outside a zone/grace period) if they've taken damage within this many seconds. |
| `peacefulAlwaysEditable` | on | Peaceful-difficulty worlds skip the above restrictions entirely. |
| `spawnZoneRadius` | 6 | Blocks around the player's own spawn point where loadout swapping is always free. 0 disables the bubble. |
| `requireZoneToEquip` | off | When on, a new loadout can only be *loaded* inside an edit zone or the spawn bubble; choosing one elsewhere applies on next respawn instead. |
| `restrictOpAndCreative` | off | When on, OP and Creative-mode players are no longer exempt from equip rules (useful for testing as admin without special treatment). |
| `antiSpamRefillEnabled` | off | Adds a short cooldown between equips even inside a normally-free zone, closing an infinite-refill exploit. |
| `restockOnRespawn` | on | Automatically restocks the active loadout's ammo/items on respawn. |
| `preventDropManaged` | on | Loadout-given items can't be manually dropped and don't drop on death. |
| `ammoInGunNbt` | on | Reserve ammo lives inside the gun item itself instead of as separate ammo stacks in the inventory. |

## Client config (per-player, `mwloadout-client.toml`)

These have no gameplay effect — visual/UX only, each player sets their own.

| Setting | Default | What it does |
|---|---|---|
| `gunsmithLayout` | `compact` | `compact` = list-based Gunsmith (original). `cinematic` = MW19-style hotspot layout around a large centered weapon. |
| `toastAnchor` | `bottom_center` | Where killstreak/level/system toast cards appear. One of the 9 screen positions (`top_left` ... `bottom_right`, plus the 3 middle/center ones). |
| `toastXOffset` / `toastYOffset` | 0 / 78 | Fine-tune pixel offset from the chosen anchor, always inward. |
| `blurEnabled` | on | Background blur behind loadout menus. |
| `backdropOpacity` | 72 | Menu backdrop darkness, 0-100. |
| `highAlertIntensity` / `highAlertColor` | 35 / amber | The edge-flash warning when an unseen enemy aims at you. |
| `useGunHudTexture` | on | Use TACZ's own HUD silhouette for weapon icons where available. |
| `showHoverTooltip` | off | Show the vanilla item tooltip on hover in the weapon picker (the stat panel already shows everything). |
| `debugMode` | off | Developer overlay: live equip verdict, cooldown timers, selected/dirty index. |

If Killfeed is also installed and its own kill/death banner is set to the same corner as
`toastAnchor`, MWLoadout automatically adds extra spacing to reduce overlap — no manual
coordination needed in the common case.

## Migration notes

A few internal one-shot flags (`presetCeilingMigrated`, `extra3OptionalMigrated`,
`pufferfishMigrated`, `shrapnelMigrated`, `streakRevision`, `perkRevision`) exist purely so that
default-value updates get merged into existing configs exactly once, without silently overwriting
an admin's deliberate choice on every restart. They don't need to be touched manually.

## Admin panel shortcuts & tools

| Key | Action |
|---|---|
| `Ctrl+S` | Save the current record / rules (works while typing) |
| `Ctrl+N` / `Ctrl+D` / `Ctrl+R` | New / Duplicate / Revert (when not typing in a field) |
| `Ctrl+F` | Focus the list search |
| `Ctrl+1` ... `Ctrl+6` | Switch tab |
| `Ctrl+B` | Open the Creative Browser |
| `Tab` / `Shift+Tab` | Next / previous form field |
| `Enter` | Commit the field (Save), or add the typed item in the ADD tab |
| `Up` / `Down` | Browse the list |
| `Ctrl+click`, `Shift+click`, `Ctrl+A` | Multi-select in CATALOG for bulk edit |
| `Esc` | Clears search, then multi-select, then closes (with an unsaved-changes guard) |

While a text box is focused, `Ctrl+A / C / V / X` edit the text - only `Ctrl+S`, `Ctrl+F` and `Ctrl+1-6` stay global.

**Creative Browser search syntax:** plain words (all must match), `-word` to exclude, `@mod`, `#tab`, `cat:sr`,
`id:text`, `is:gun | is:item | is:new | is:added | is:selected`, and `"exact phrase"`.

**Use lock** (RULES > ANTI-ABUSE): after a non-OP player fires a loadout gun or throws / eats / spends a loadout item
they cannot re-equip a loadout until they respawn (`useLockSeconds = 0`) or the cooldown passes.

**Killstreak rewards** can be any item: a catalog id, a TACZ gun id, or any `namespace:item` (optionally with SNBT).
Use the KILLSTREAK EDITOR button (RULES tab) - BROWSE picks from the whole creative inventory.

**Killstreak pool (Modern Warfare style):** the KILLSTREAK EDITOR defines the pool of available killstreaks (up to 50, the editor scrolls; Shift + "+ Slot" adds 5;
several may share a kill cost). Players equip at most 3 per class, one per kill cost, from LOADOUT > KILLSTREAK.
A TACZ gun in the pool has BULLETS / RESERVE boxes in the editor (blank = one round, no reserve); the gun is removed once magazine, chamber and reserve are spent, and the use is announced on its first shot. Text form: `5=tacz:rpg7|1/2`.
Classes that never customised it use the three cheapest distinct entries. Hardline reduces every equipped cost by 1.

## Where the data lives

Everything the admin panel saves is in the `mwloadout/` folder next to `config/` on the server:

```
mwloadout/
  data/      slots, catalog, perks, rules, stats   (your whole game setup; copy this folder to move it)
  players/   one json per player: classes and gun builds
```

Copy `data/` to a new server and the setup follows. `/mwloadout reload` re-reads it without a restart. If you host
somewhere that mounts data separately, start the server with `-Dmwloadout.dir=/path/to/folder`.

## Specialist pool

By default Specialist offers every enabled perk that is not already picked, except Killchain, Hardline, Overkill and
Underkill (they change how a class or the streak works, so they are never offered and never part of the 8-kill unlock).
Set `specialistPoolAll` to `false` in the rules to use your own list in `specialistPerkPool` instead.
