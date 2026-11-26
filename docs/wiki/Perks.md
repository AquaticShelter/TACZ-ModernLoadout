# Perks

Perks are data, not code: names, icons, effects, which column they sit in and whether they are enabled are all in the
perk list the admin can edit. This page covers how they work. The exact numbers can change between versions, so the
description shown in the perk screen is the source of truth.

## Columns

A class has three perk columns, one perk in each. Every perk belongs to one column.

The 18 perks: Quick Fix, Overkill, Underkill, High Alert, Ghost, Tune Up, Killchain, Hardline, Blood Rush, Fast Hands,
Ninja, Shrapnel, Battle Hardened, Scavenger, Tracker, Fragiles, Fully Loaded and Shield Up.

Shield Up exists only inside Specialist (see [Killstreaks and Specialist](Killstreaks-and-Specialist.md)); it is never
offered in a normal class column.

## Perks that change a class

- **Overkill**: your secondary slot takes primary weapons, so you can carry two primaries.
- **Underkill**: the mirror of Overkill. The primary slot takes secondary weapons, so you carry two secondaries. If a class
  somehow has both, Overkill wins.
- **Fully Loaded**: a bigger starting reserve for your guns.
- **Fast Hands** speeds up reloads and needs the gunsmithlib mod.

## Perks that change streaks

- **Killchain** keeps your chain through a death, once. It does nothing when every equipped streak item has already been earned.
- **Hardline** makes every streak item cost one kill fewer.

Both are switched off while Specialist is on.

## Awareness

- **High Alert** adds a warning overlay (intensity and colour are in the client config).
- **Tracker** marks an enemy you damage for a short time.
- **Ghost** hides you from enemy Tracker and High Alert.

## Hit marker

With a hit marker (TACZ Hitmarker or TACZ's own), hitting a player who carries Quick Fix, Battle Hardened, Shield Up,
Tracker, Fragiles, Ghost, Shrapnel or Blood Rush shows that perk's icon under the marker for a moment. Turn it off with
`hitPerkMarks`, or move and resize it with `hitPerkOffsetY`, `hitPerkSize` and `hitPerkDurationMs`.
