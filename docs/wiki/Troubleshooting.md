# Troubleshooting

## I cannot equip

Read the line under the class: it names the rule that blocked it (not in an edit window, used loadout gear recently, class empty
for a required slot, and so on). The class is saved anyway and is applied at your next respawn.

## Joining a server says the mod versions do not match

The network protocol is versioned. Client and server must run the same version of the mod. Update both.

## My imported classes lost items

The server did not have the gun or item (or it is not allowed in that slot). The toast after an import says how many classes
lost items and how many were skipped. Your own copy in `mwloadout/cloud/` is untouched until you press TO DEVICE.

## The config button does nothing

It should open the YACL or Cloth Config screen if one is installed. If that screen fails to build, the in-game settings open
instead and the full error is written to the log under `[MWLoadout]`. Send me that stack trace.

## The log mentions "Ignored an exception at ..."

Some errors are deliberately not fatal (a damaged entry in a data file, an optional TACZ call that does not exist in your
version). Each place now reports itself once, with a stack trace, and then only counts. On a server these lines are warnings;
on a client they are debug lines. Most of them are harmless, but if one repeats with a gun or item name in it, that is useful
for a bug report.

## The weapon model does not render in the picker

TACZ 1.2.0 added VBO acceleration for third-person, dropped and displayed guns. The picker draws its 3D model as a displayed gun, so the
two can disagree on some setups. Set `gunPreview3d` to false (client config, or the Config screen) to use the flat icon. To confirm the
cause, look for a VBO or acceleration option in TACZ's own client config and turn it off. Tell me what you find: it decides how the
preview should be drawn on 1.2.0.

## The HUD looks doubled behind the loadout

The menus blur whatever is already on screen, HUD included. `hideHudBehindMenus` (on by default) stops the HUD being drawn behind them.
If you still see two copies, a screenshot and the list of HUD mods you run will help.

## The UI looks wrong

The loadout screens have their own scale: set `uiScale` (0 = auto from the window height, or 50-400 %) instead of changing the game's
GUI scale, which only affects the HUD and other screens. Check `angularUi` (false = the plain rectangles). If the blur is slow on a weak
GPU, turn off `blurEnabled`. Tell me the screen, your `uiScale` and the window size when you report it.

## The gun model disappears in the gunsmith

Fixed in 1.1.0 for zooming in close (the model left the GUI's depth range; TaCZ Accelerated also culled it). If it still happens,
set `gunPreview3d = false` to use the flat icon and report your TACZ version and any rendering mods.

## Crash on Forge 47.4.28

One report so far, and I could not reproduce it. The mod is tested on 47.4.10. If you hit a crash, attach the report from
`crash-reports/` (or `logs/latest.log`).

## Resetting

- One player: delete their file in `mwloadout/players/`.
- The whole setup: delete the contents of `mwloadout/data/` and restart; defaults are rebuilt from your guns and items.
- Your cloud copy: delete the file in `mwloadout/cloud/` (backups are in `mwloadout/cloud/backups/`).

## Reporting a bug

Include: the mod version, Forge version, TACZ version and gunpack, the log or crash report, and what you pressed. A screenshot helps for UI problems.
