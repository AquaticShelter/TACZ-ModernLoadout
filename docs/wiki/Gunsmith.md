# Gunsmith

Pick a weapon in a class, then CUSTOMIZE.

## Attachments

Scope, muzzle, stock, grip, laser and extended mag, depending on what the gun allows. The top right shows how many you have
fitted against the limit (for example `5/5`); the server's attachment budget decides that limit. The section you opened lists the
attachment you have equipped first, and the rest in alphabetical order.

**Ammo type and magazine (TACZ 1.2.0 and newer).** TACZ 1.2.0's ammo mods (FMJ, HE, HP, incendiary, overpressure, slug) are normally
attachments of the *extended mag* slot, so they compete with extended magazines. With the mod's mixin active the gunsmith splits them: **MAGAZINE**
(extended mags) and **AMMO TYPE** (ammo mods) are two slots, both can be fitted at once, and each counts as one attachment against the limit. Damage,
pierce, explosion and the like of the ammo mod apply through TACZ itself; magazine size only ever comes from the MAGAZINE slot, so an extended mag
always decides the capacity (set the ammo mods' `extended_mag_level` to 0 to keep them from adding rounds on their own). Admin rule: **Ammo Type Slot**
(AMMO section). The **slug** ammo mod keeps its TACZ behaviour in the AMMO TYPE slot: a shotgun fires one slug instead of its pellets (the stats show 1 pellet). If the mixin cannot apply, the slot is simply not offered and everything works as in plain TACZ.

**Risers (TACZ 1.2.0 and newer).** When a gun's optic slot has risers, the open slot shows a **RISER** row above the search box. Click it
to cycle through the risers the gun accepts (and back to none). A riser is a mount, not an attachment, so it never counts against the
limit. On older TACZ versions the row simply is not there.

Some attachments need a weapon level, if the server uses levels.

## Builds and blueprints

- Each gun has build slots (4 by default, up to 8). A build is a full set of attachments you can switch between.
- Blueprints are variants of a gun (for example a different base model) the server defines.
- The fire mode you choose is remembered with the build.

## Stats

The bars on the left show accuracy, damage, range, muzzle velocity, fire rate, mobility and control for the build you
have now, and the magazine and reserve ammo under them. Hover another attachment to compare.

**Reading them:** a longer bar is always better, and a change that makes a stat worse is shown red. The number next to a bar is TACZ's own
figure, and for three bars it runs the other way round from the bar:

| Bar | The number is | Better is |
| --- | --- | --- |
| ACCURACY | standing spread, in degrees | a lower number (tighter cone) |
| MOBILITY | weight of the build | a lower number (lighter gun) |
| CONTROL | recoil kick per shot, in degrees (vertical + horizontal) | a lower number (less kick) |

All other bars (damage, range = distance of full damage, muzzle velocity, fire rate) are higher = better. Hover a bar for the exact figure, the
comparison to the stock gun (marked better / worse) and what the number means.

The **advanced stats panel** goes further:

- damage by distance, with the server's damage reduction applied. With Limb Damage on the server it is split by body zone (head, chest,
  abdomen, arms, legs, feet); without it only head (TACZ's own headshot multiplier) and chest are listed,
- time to kill and shots to kill against a target you can set (health, armor),
- spread and a recoil pattern preview,
- for shotguns, damage per pellet, the number of pellets expected to land at a distance, and the total.

Limb Damage (mod id `limbdamage`) zone multipliers are read automatically when that mod is on the server. Without it, no per-part
multiplier is shown or used.

## Reserve ammo

The reserve is the magazine size times a multiplier. Larger magazines get a smaller multiplier, so the reserve grows less than
the magazine does. The gunsmith shows both numbers for the build you have equipped. The tiers are in the rules.

## Laser colour

When a laser is equipped, a hue slider appears under the list. AUTO uses the attachment's own colour, the white box sets white.
The colour is stored with the build. It only has an effect on lasers that allow a custom colour.

## Preview

The **PREVIEW** button (next to BLUEPRINTS / MODE / FLIP; the row packs itself, so there are no gaps) fades the whole gunsmith away and lets
the gun grow to fill the window. Drag to rotate, scroll to zoom, as long as you like: the view does not drift back by itself. Press any key
to return; the interface fades back in and the gun settles home.

## The gun model

Drag to rotate, scroll to zoom. Press <kbd>V</kbd> to keep the angle you are looking from as the home view. The size is
framed from the gun model's length; `gunsmithModelZoom` in the config scales it.
