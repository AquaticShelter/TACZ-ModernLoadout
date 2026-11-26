# MW4-style UI: concept and layout spec

Companion to [`UI_MW4_PREP.md`](UI_MW4_PREP.md) (the work order) and [`UI_DESIGN.md`](UI_DESIGN.md) (the style rules).
This file is the reference: what Call of Duty: Modern Warfare 4 (Infinity Ward, 2026) does on its Create-a-Class, Gunsmith and
selection screens, measured from beta captures, and how each part maps onto Modern Loadout's screens and Minecraft's limits.

It is a design target, not a promise of features. Where MW4 has a system this mod does not (Apex Attachments, Gunny, operators,
camos), the layout keeps the space honest instead of faking the system.

## 1. Sources

Official:
- Call of Duty blog, *Ready for Launch: Weapons Detail* (Oct 2026): Create-a-Class fields, Gunsmith attachment types, Apex
  Attachments, Gunny, stats shown, firing range.
  <https://www.callofduty.com/blog/2026/10/call-of-duty-modern-warfare-4-launch-multiplayer-operators-weapons-equipment-create-a-class>
- Call of Duty guide, *Open Beta Guide: Weapons*: Primary Weapon menu, Gunsmith layout, Gunny, weapon levels.
  <https://www.callofduty.com/guides/mw4/open-beta-weapons>
- Call of Duty blog, *Modern Warfare 4 Multiplayer: Movement, Gunplay, Modes & Maps*: the expanded Create-a-Class brings Operator,
  Killstreaks, Perks and weapons into each loadout.
  <https://www.callofduty.com/blog/2026/08/call-of-duty-modern-warfare-4-next-highlights-multiplayer-gameplay-systems>
- Call of Duty guide, *Open Beta Guide: Progression* (default loadouts and unlock order).
  <https://www.callofduty.com/guides/mw4/open-beta-player-progression>

Captures: eight beta screenshots (Gunsmith on an SMG, LMG and AR; Edit Custom Class; Select Barrel twice; Select Primary Weapon twice),
supplied by L41nx. They are not stored in the repository; the numbers below were read off them at 16:9.

## 2. What the official sources say

- **One loadout = one complete role.** Operator, primary, secondary, melee, tactical, lethal, field upgrade, Perks and Killstreaks
  are all set per loadout. Operator sits at the top of the loadout.
- **Perks are Pick 3**, one from each of three categories.
- **Gunsmith** attachment types: optics, lasers, barrels, muzzles, underbarrels, stocks, rear grips, combs, ammunition and magazines
  (some weapons add bolts, guards, trigger actions, cylinders, fire mods). The categories are arranged *around the weapon model*.
- **Stats** shown everywhere: Damage, Range, Accuracy, Mobility, Handling, with a details toggle for deeper numbers. Attachments list
  **pros and cons**.
- **Primary Weapon menu**: the selected weapon's stats plus the other weapons of its class.
- **Firing range** reachable from the class and gunsmith screens; loadouts can be cycled without leaving it.

All of these exist in Modern Loadout already except operators and per-loadout killstreak *count* differences; the mod already has
killstreaks per class.

## 3. Measured layout (percent of screen, 16:9)

### 3.1 Shared frame
| Element | Position / size | Notes |
|---|---|---|
| Breadcrumb | x 4 %, y 2.5 %, tiny caps, dim | `CREATE A CLASS / EDIT CUSTOM CLASS /` |
| Title | x 4 %, y 4-7 %, condensed caps, ~4.5 % tall | back chevron left of it; a small yellow tag after it (`BETA`) |
| Sub-title | under the title, accent colour, ~1.6 % | weapon class, then `LVL n / max` with a thin progress line |
| Top tabs | centred, y 5 % | `GUNSMITH / APPEARANCE / PROGRESSION`; active tab = light filled plate, others plain text |
| Top-right cluster | x 78-97 %, y 4 % | rounded pill (party, notifications, settings) |
| Footer left | x 4 %, y 96 % | `FIRING RANGE`, `IMPORT`, `SAVE BUILD`: key plate + label |
| Footer right | right-aligned, y 96 % | `ROTATE / SELECT / BACK` key hints |
| Background | near-black charcoal with film grain and a soft vignette; the 3D model is lit, everything else is flat |

### 3.2 Edit Custom Class
| Element | Position / size |
|---|---|
| Class number rail | x 1-5 %, numbers `01`-`05` stacked, ~5.5 % apart; the current one larger with a star, `+` below |
| Card column | x 11-34 % (about 23 % wide), y 13-93 % |
| Operator card | ~7 % tall, portrait at the right end |
| Primary card | ~13 %; Secondary ~12 %; Melee ~9 % | weapon image right-aligned, class above name, `LVL a / b` bottom-right, attachment pips top-right |
| Tactical, Lethal, Field Upgrade | ~7 % each, icon at right |
| Perks | ~12 %, three icons in a row |
| Killstreak strip | x 34-38 %, beside the equipment cards, three icons with kill counts |
| Context fly-out | right of the hovered weapon card: `GUNSMITH` (selected, yellow plate) and `APPEARANCE` |
| Operator render | right 50 % of the screen, full height |

### 3.3 Gunsmith
| Element | Position / size |
|---|---|
| Weapon model | centre, about 45 % of the width, slightly above the middle |
| Slot tags | around the model on an ellipse: top row (Laser, Optic), sides (Barrel, Muzzle / Stock, Rear Grip), bottom (Underbarrel, Ammunition, Magazine). Empty: grey outline, `+`, caps label. Equipped: yellow check badge, icon tile, two lines (name / type) |
| Attachments counter | x 7 %, y 68 %: `ATTACHMENTS n/5` with five pips (filled yellow when used) |
| Stat block | x 7-30 %, y 72-92 %: label, bar, value, then delta (`+28` green, `-7` red); a second column x 27-32 % with Fire Rate, Rounds, Reserve |
| Apex | centre bottom, diamond plate (empty: dim; equipped: orange) |
| Helper (Gunny) | bottom right, wordmark and one line of text |

### 3.4 Select attachment (Select Barrel)
| Element | Position / size |
|---|---|
| Option list | x 7-29 %, y 22-75 %, rows ~6.5 % tall, 0.5 % gap; `NONE` first; count `4/4` above the list's right end |
| Equipped row | olive-green fill with a two-line label (category above, name below) |
| Focused row | amber fill with a check badge |
| Title | x 32 %, y 18 %, ~5 % tall, bold caps, with a short bracket to its left |
| Description | under the title, italic, two lines, dim |
| Pros / Cons | two columns under the description: `PROS` with green `+` / chevrons, `CONS` with red `-` |
| Stat block | x 35-58 %, y 80-92 %, deltas against the current build |
| Model | right half, zoomed onto the slot, bleeding off the right edge; the selected part highlighted (yellow wireframe) |

### 3.5 Select Primary Weapon
| Element | Position / size |
|---|---|
| Category tabs | y 9 %, from x 7 %: `ASSAULT RIFLES / SMGS / SHOTGUNS / LMGS / MARKSMAN RIFLES / SNIPER RIFLES`; active = light plate |
| Weapon cards | x 7-29 %, ~13.5 % tall, image centred, name bottom-left, `LVL a / b` bottom-right, pips top-right |
| Selected card | olive-green gradient fill; `BUILDS` button docked to its right |
| Model | centre-right, large, idle turn |
| Detail | bottom-left of the model: `EQUIPPED` tag, name (~5 %), description, `Acquired by: unlock at level n` |
| Stats | bottom right: `Compared to <weapon>` checkbox, bars with deltas, Fire Rate / Rounds / Reserve column |

### 3.6 Colours (read off the captures, to be tuned in game)
| Token | Value | Use |
|---|---|---|
| `bg` | `#121314` → `#1C1E20` vignette | screen background |
| `panel` | `#1A1C1F` at 85 % | cards, list rows |
| `text` | `#E6E6E0` | names, values |
| `dim` | `#8A8D90` | labels, breadcrumbs |
| `signal` | `#E3B341` (mod default) | focus, check badges, pips, BETA-style tags |
| `equipped` | `#55702A` → `#7F9A3C` gradient | the equipped row / card |
| `positive` | `#7BC043` | `+` deltas, pros |
| `negative` | `#D9534F` | `-` deltas, cons |
| `apex` | `#E07B2A` | reserved; the mod has no Apex system |

## 4. Mapping onto Modern Loadout

| MW4 element | Modern Loadout today | Plan |
|---|---|---|
| Card column + class rail | `LoadoutScreen`, sized from screen height (1.1.0) | keep; add the numbered rail style (done), move the killstreak strip next to the equipment cards |
| Context fly-out (GUNSMITH / APPEARANCE) | BUILD dock on hover (1.1.0) | keep; second entry only if a real feature exists |
| Slot tags around the model | done in 1.1.0 (angular style) | ellipse spacing from §3.3; empty / equipped states as measured |
| Attachments counter + pips | `n/max` text | add pips beside it (budget unchanged) |
| Stat block with deltas | `AttachmentEditorScreen` bars | move to the §3.3 rect; deltas as numbers after the bar |
| Pros / cons picker | pros / cons exist in the attachment cards | rebuild the picker as §3.4: list left, title + pros / cons centre, stats bottom, model right |
| Primary weapon picker | `SlotPickerScreen` | category tabs top, cards left, `BUILDS` dock on the selected card, compare checkbox |
| Perks Pick 3 | three perk columns | perk picker as a three-category grid, one pick per column (already the rule) |
| Killstreaks per class | killstreak selection screen | same list + detail layout as §3.4, with the kill count as the sub-title |
| Firing range in the footer | FIRING RANGE button | move into the footer key-hint row |
| Apex, Gunny, operators, appearance / camos | not in the mod | not planned; leave no placeholder controls |

## 5. Scale model

The captures are 1920 × 1080. Minecraft draws text with a pixel font, so the layout has to land on whole GUI units.

- **Reference grid: 960 × 540 GUI units** (1080p at scale 2). Every rectangle above is a percentage of the screen, so it scales.
- **`uiScale`** (client config, percent, 0 = auto, since 1.1.0) sets the loadout scale on its own, separate from the game's GUI
  scale. Auto picks `round(height / 540 × 2) / 2`: 1080p → 2, 1440p → 2.5, 2160p → 4.
- **Floor:** the scale is lowered until the layout has at least 640 × 360 units. Below that, panels collapse (detail panel becomes a
  toggle, the model shrinks) instead of overlapping.
- **The HUD keeps the game's scale.** `MwScaleEvents` switches the window scale per frame phase, so HUD mods (ModernHUD, KillFeed)
  never see the loadout scale.
- **Text sizes:** title 2× font, sub-title 1×, labels 1× dim caps. No fractional text scales below 1×.
- **Hit boxes come from the same rects** as the drawing, never from separate constants.

## 6. Motion

| Moment | Timing | Curve |
|---|---|---|
| Screen in / out | 180 ms | ease-out cubic; veil fades, content slides 6 units |
| Focus moves between rows | 120 ms | the fill slides to the new row instead of snapping |
| Slot tag hover | 90 ms | outline brightens, tag leans 2 units outward |
| Model refocus on a slot | 250 ms | camera lerp (already in `GunPreview`) |
| Stat delta | 160 ms | bar segment grows from the base value |

## 7. Order of work

Follows `UI_MW4_PREP.md` §6: tokens from §3.6 into `MWStyle`, then KeyHints and the stat block, then the picker (§3.4) on
`SlotPickerScreen`, then the gunsmith picker, then the class screen details. Each step keeps the old look behind `angularUi = false`.
