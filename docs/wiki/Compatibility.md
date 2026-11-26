# Compatibility

| Mod | Status | How it is used |
|---|---|---|
| TACZ | Required | Guns, attachments, ammo, the hit events. Everything else sits on top of it. 1.1.6 to 1.2.0, see below. |
| LRTactical | Optional | Melee weapons, throwables and consumables for the melee, lethal, tactical and consumable slots. The default catalog expects its items. |
| YACL 3.6 | Optional | Full config screen from the mod list. Preferred over Cloth Config if both are installed. |
| Cloth Config | Optional | A smaller config screen. |
| ModernHUD 2.0.1+ | Optional | Can draw the killstreak tracker and perk toast in its own style (reads `StreakApi`). Our tracker, toasts and callouts step out of any box ModernHUD reports through its `HudLayout` API. See below. |
| KillFeed (with kill cards) | Optional | Kill card / emblem picker in the loadout settings, choice saved in the profile; toasts keep clear of its banner corner. |
| TACZ-ModernTweak 1.7+ | Optional | Advanced Stats shows Sprint to fire and the real aim time; Fast Hands does not speed up its overlay clips. |
| TaCZ Additions | Optional | With the Tracker perk the scope does not sway and hold breath is not needed. |
| TACZ Hitmarker | Optional | Perk icons appear under its marker. They also work with TACZ's own marker. |
| Limb Damage | Optional | Body-zone multipliers are read from it automatically in the Advanced Stats panel. |
| TaCZ Accelerated | Works | Its FIXED-model culling used to hide the gun preview when zoomed in close; fixed in 1.1.0. |
| SuperbWarfare, Airstrike, AC-130 | Works | Their vehicles, turrets and strikes never feed the killstreak counter (`streakIgnoreStrikeMods`). Other mods can opt out a kill with the `mwloadout_ignore_kill` flag. |
| tacz_attributes, ApothicAttributes, gunsmithlib | Not needed | Older builds used them for Fast Hands; since 1.0.2 the mod has its own `mw:reload_speed`, `mw:draw_speed` and `mw:ads_speed` attributes. |

## ModernHUD

- **Scale.** Since 1.1.0 the loadout screens have their own scale (`uiScale`) and the HUD is always drawn at the game's own GUI scale,
  so ModernHUD (which has its own independent scale too) looks the same with a loadout screen open or closed.
- **Avoidance.** ModernHUD publishes the live boxes of its elements in GUI units (`cc.xypp.modernhud.api.HudLayout.rects()`). The streak
  tracker, toasts and streak callouts move out of those boxes with an eased offset. The lookup is a bound `MethodHandle`, refreshed at
  most every 50 ms, and does nothing when ModernHUD is absent.
- **Tracker hand-off.** When ModernHUD's killstreak widget is on it calls `StreakApi.setExternalHud(true)` and our own tracker stays hidden,
  so the two never draw on top of each other.

## Gunpacks and other mods' items

The catalog is built from what the server really has. Attachments and items from other mods are read through TACZ's data and
through LRTactical, so a pack that uses unusual data can show wrong stats or a missing icon. If you see one, open an issue with
the pack's name and version. Admins can hide or whitelist anything in the catalog tab, see the [admin guide](Admin-Guide.md).

## What it does not touch

It does not replace armor, health or damage rules, and it does not add its own guns or items. Perks that change damage or
effects work through normal Minecraft effects and events, so other mods that change the same things can interact with them.

## TACZ 1.1.6 to 1.2.0

One build is meant to run on TACZ 1.1.6 up to 1.2.0 (built against 1.1.8; checked against the 1.2.0 jar). Everything that exists only in newer
TACZ is looked up at runtime: when it is missing the feature is hidden, and when it fails the feature switches itself off for the session
with one log line, so the rest keeps working.

- **Optic risers** (1.2.0 slot adapters): RISER row in the gunsmith; off on older TACZ.
- **3D preview**: 1.2.0 removed `RenderDistance.markGuiRenderTimestamp()` (the menus' gun models call it on older versions). It is now optional,
  so the weapon picker and gunsmith previews work on both. Use `gunPreview3d = false` if a model is still missing.
- The hit-mark listener looks for TACZ's hit event by name at startup and logs `Hitmarker perk marks listening to ...` when it finds it.
- The boot log prints a banner with the mod version, the TACZ version and which of these features are active.

## Mixin (AMMO TYPE slot) and TACZ-Arcana

The mod ships one optional Mixin config (`mwloadout.mixins.json`, `"required": false`, `defaultRequire 0`): it appends an `AMMO_MOD` constant to TACZ's
`AttachmentType`, reports ammo-mod attachments as that type and lets guns that take an extended mag take it. It uses `@Inject` only (no `@Redirect`), so it
does not fight TACZ-Arcana's redirects of the same TACZ methods; both mods add enum constants the same way. If it does not apply, the gunsmith keeps one
shared slot. First thing to check if the game misbehaves with a gun that has an ammo mod fitted: turn the admin rule *Ammo Type Slot* off.

## Limb Damage

Optional (mod id `limbdamage`). When the server has it, the gunsmith's advanced stats show the per-part multipliers and zone damage, read from its
config. Without it those numbers are not shown, and no per-part multiplier is applied to the estimates.

## Versions

Developed and tested on Minecraft 1.20.1 with Forge 47.4.10. Newer Forge 47.4.x builds should work; see
[Troubleshooting](Troubleshooting.md) for the one crash report I could not reproduce.
