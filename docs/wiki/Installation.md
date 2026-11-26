# Installation

## Requirements

- Minecraft 1.20.1, Forge 47.4.10 or newer, Java 17.
- [TACZ](https://github.com/MCModderAnchor/TACZ) and at least one gunpack. Without guns there is nothing to equip.
- The mod must be on the **client and the server**, same version (the network protocol is versioned and a mismatch
  is refused at login).

Optional, nothing breaks without them:

| Mod | What you get |
|---|---|
| YACL 3.6 or Cloth Config | A full config screen from the mod list (Mods, Config) |
| LRTactical | Melee weapons, throwables and consumables for the lethal, tactical and consumable slots |
| TACZ Hitmarker | Perk icons shown with the hit marker (the icons also work with TACZ's own marker) |
| Limb Damage | Body-zone multipliers picked up automatically in the advanced stats panel |
| gunsmithlib | Needed by the Fast Hands perk to speed up reloads |

## Server

1. Put the jar, TACZ and your gunpacks in `mods/`.
2. Start the server once. A `mwloadout/` folder appears next to `config/`.
3. Join, press <kbd>L</kbd>, and check the default class works. As an operator, `/mwloadout admin` opens the panel.

The default setup is complete: slots, catalog, perks and rules are all populated, and the catalog is filled from the
guns and items the server actually has.

## Client

1. Put the jar and TACZ (same gunpacks as the server) in `mods/`.
2. Open the loadout with <kbd>L</kbd>, the LOADOUT button on the pause menu, or `/mwloadout`.

## Updating

Replace the jar on the server and the clients together. Your data is in `mwloadout/`, not inside the jar, so it
carries over. If you were running a development build that kept data in `config/mwloadout/`, the first start copies it
into the new folder and leaves the old files in place as a backup.

## Uninstalling

Remove the jar. The `mwloadout/` folder stays, so reinstalling later picks up where you left off. Delete it to start clean.
