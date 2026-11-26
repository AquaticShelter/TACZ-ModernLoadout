# Configuration

Client options live in `config/mwloadout-client.toml`. Most of them are also in the config screen (Mods, Config) when YACL
3.6 or Cloth Config is installed, and the basics are in the in-game SETTINGS screen. Server-side rules are not here:
those are edited in the admin panel, see the [admin guide](Admin-Guide.md).

| Option | Default | What it does |
|---|---|---|
| `borderColor` | `#3A3E44` | Idle panel/tile border. |
| `borderActiveColor` | `#C8CDD2` | Selected/active border. |
| `signalColor` | `#E3B341` | Header bars, tab underlines, selection outlines, progress fills and gold labels. Gold by default (#E3B341). |
| `hoverColor` | `#6FB7E8` | Hover grid tint. |
| `transitionStyle` | `fade` | Screen transition animation between menus: fade \| wipe \| instant. |
| `showHoverTooltip` | `false` | Show the floating vanilla item tooltip when hovering a weapon in the picker. The right-hand stat panel already shows everything, so this is off by default to keep the list uncluttered. |
| `gunsmithLayout` | `compact` | Gunsmith / weapon-select layout: compact - current layout: attachment list center, stat panel right (default) cinematic - big centered weapon, stats bottom-left, list narrowed |
| `backdropOpacity` | `72 (range 0 to 100` | Menu backdrop darkness, 0-100. Lower = see more of the game behind the menus (0 = fully transparent, 100 = solid black). Default 72. |
| `hoverStyle` | `fill` | Row/tile hover effect: fill - light-blue wash that flows across the row (default) grid - (legacy, no longer used) falls back to the wash |
| `highAlertIntensity` | `35 (range 0 to 100` | High Alert edge-flash opacity, 0-100. Higher = darker/more visible when an unseen enemy is aiming at you. Default 35. |
| `highAlertColor` | `#E8C24A` | High Alert edge-flash colour as #RRGGBB. Default a warm amber. |
| `cloudSync` | `true` | Keep a copy of your own classes and gun builds on this device, and offer it to any server / world where your loadout is still untouched (server rules and unlock levels still apply). Nothing is uploaded anywhere else. |
| `cloudWriteFromServers` | `false` | Also refresh the local copy from classes you edit on a dedicated server. Off by default: a server may offer fewer weapons / perks than your single-player world, and its limited view would overwrite your full build. |
| `angularUi` | `true` | Angular plate style: chamfered (cut-corner) panels, corner-bracket selection, hard drop shadows and dispatch-tag toasts. Off = the classic flat rectangles with a soft glow. Applies to every loadout screen and the toasts. |
| `uiScale` | `0 (range 0 to 400)` | Size of the loadout screens in percent, separate from the game's GUI scale (the HUD and other screens keep yours). 0 = auto from the window height (1080p 200, 1440p 250, 2160p 400); 50-400 = fixed. Always held so the layout keeps at least 640 x 360 units. Replaces `uiScaleCap` (1.1.0). |
| `gunsmithModelZoom` | `100 (range 50 to 200` | Size of the gun in the gunsmith, percent of the automatic framing (100 = default, which already frames each gun from its model length). |
| `gunsmithViewYaw` | `28.0 (range -180.0 to 180.0` | Gunsmith home view of the gun, turn in degrees (isometric from the right). Press V in the gunsmith to save the view you dragged to. |
| `gunsmithViewPitch` | `-22.0 (range -80.0 to 80.0` | Gunsmith home view elevation in degrees: negative = camera below the gun looking up (default), positive = from above. |
| `radialAutoUse` | `true` | Killstreak radial (hold X): picking a streak item that is NOT a TACZ gun also right-clicks it for you, because streak items come from many mods and each activates on use. TACZ guns are only taken in hand. Off = every item is just taken in hand. |
| `radialNoReturn` | `[]` | Streak items that do NOT hand you back to your previous weapon once they are spent. Item ids (mod:item), reward ids, or a prefix ending in * (e.g. "mymod:*"). Everything else returns to the slot you were on. |
| `hitPerkMarks` | `true` | When your TACZ bullet hits another player, show the perks they carry (Quick Fix, Battle Hardened, Shield Up, Tracker, Fragiles, Ghost, Shrapnel, Blood Rush) as small icons under the hitmarker. Works with TACZ Hitmarker or the default marker. |
| `hitPerkOffsetY` | `35 (range 0 to 300` | How far below the crosshair centre the perk icons sit, in GUI pixels. |
| `hitPerkSize` | `14 (range 8 to 48` | Size of each perk icon, in GUI pixels. |
| `hitPerkDurationMs` | `450 (range 150 to 3000` | How long the perk icons stay (they fade out over the last 40 %). Match it to your hitmarker's duration. |
| `sectionAnimations` | `false` | Flash / staggered reveal when switching tabs and sections inside a menu. Off = content simply changes. |
| `blurEnabled` | `true` | Gaussian-blur the game behind the loadout menus (softens the background so panels stand out). Turn off if you prefer just the dim, or on weak GPUs. |
| `gunHudSmoothing` | `true` | Smooth (bilinear) filtering for the gun silhouettes drawn in the loadout screens. The pack textures are tiny pixel art (39x13), so scaling them up with nearest-neighbour looks chunky. Note: this also smooths TACZ's own HUD silhouettes (same texture). Turn off to keep them blocky. |
| `toastsEnabled` | `true` | Master switch for every toast card this mod shows (equip notices, killstreak / specialist moments, level-ups, errors). Off = none at all. The per-kind switches below refine it. |
| `toastStreak` | `true` | Toasts for killstreak ready / specialist perk / specialist complete. |
| `toastLevel` | `true` | Toasts for player and weapon level-ups. |
| `toastEquip` | `true` | Toasts about equipping / deploying a class. |
| `toastNotice` | `true` | Every other toast: edit-lock reasons, denied actions, general notices. |
| `toastHoldMs` | `2000 (range 500 to 15000` | How long a toast stays fully shown, in milliseconds (slide in / out not included). |
| `toastScale` | `100 (range 50 to 250` | Toast size in percent (100 = normal). |
| `toastOpacity` | `100 (range 20 to 100` | Toast opacity in percent. |
| `toastAnchor` | `bottom_center` | Where killstreak/specialist/system toast cards appear on screen. One of: top_left, top_center, top_right, middle_left, center, middle_right, bottom_left, bottom_center, bottom_right. Default bottom_center. |
| `toastXOffset` | `0 (range -2000 to 2000` | Toast horizontal offset in pixels from the chosen anchor (positive = right). |
| `toastYOffset` | `78 (range -2000 to 2000` | Toast vertical offset in pixels from the chosen anchor, always inward (positive pushes a top-anchored toast down, or a bottom-anchored one up). Default 78 clears the hotbar/XP bar AND Killfeed's own default bottom-center kill/death banner if that mod is installed alongside this one — raise it further if you still see the two overlap after moving either mod's banner position. |
| `streakHudEnabled` | `true` | Show the killstreak tracker. Set false to turn it off completely. |
| `streakHudScale` | `100 (range 50 to 250` | Size in percent (100 = normal). |
| `streakHudXOffset` | `10 (range -500 to 2000` | Distance in pixels from the RIGHT edge of the screen. |
| `streakHudYOffset` | `12 (range -500 to 2000` | Distance in pixels from the BOTTOM edge of the screen. |
| `streakHudOpacity` | `100 (range 10 to 100` | Overall opacity in percent. |
| `streakHudColor` | `#E8C24A` | Bar fill colour (#RRGGBB). Earned rungs are always green. |
| `streakCalloutEnabled` | `true` | Show a banner when a player uses a killstreak item (blue = ally team, red = enemy). |
| `streakCalloutAnchor` | `top_left` | Where the killstreak-use banners sit. One of: top_left, top_center, top_right, middle_left, center, middle_right, bottom_left, bottom_center, bottom_right. |
| `streakCalloutXOffset` | `10 (range -500 to 2000` | Horizontal distance in pixels from the anchored edge (inward). |
| `streakCalloutYOffset` | `28 (range -500 to 2000` | Vertical distance in pixels from the anchored edge (inward). |
| `streakCalloutScale` | `100 (range 50 to 250` | Banner size in percent (100 = normal). |
| `streakCalloutDurationMs` | `4200 (range 1000 to 20000` | How long each banner stays, in milliseconds (slide in/out not included). |
| `streakCalloutMax` | `3 (range 1 to 8` | Most banners shown at once. |
| `streakCalloutAllyColor` | `#2D5C9C` | Ally banner colour (#RRGGBB). |
| `streakCalloutEnemyColor` | `#8A3030` | Enemy banner colour (#RRGGBB). |
| `streakCalloutShowSelf` | `true` | Show the banner when YOU use a killstreak (gold). |
| `streakCalloutShowAlly` | `true` | Show banners for allies using killstreaks. |
| `streakCalloutShowEnemy` | `true` | Show banners for enemies using killstreaks. |
| `streakCalloutSelfColor` | `#B58A1E` | Banner colour for your OWN killstreak use (#RRGGBB). Default gold. |
| `streakCalloutShowIcon` | `true` | Show the killstreak item icon on the banner. |
| `streakHudAutoHide` | `false` | Fade the tracker out after 5 seconds without a kill, wake it on the next one. false = always visible while killstreaks are enabled. |
| `tutorialSeen` | `false` | Set true once the first-run tutorial has shown. Flip to false to see it again. |
| `debugMode` | `false` | Developer overlay in the loadout screen: live equip verdict (YES/NO), deny reason, cooldown/combat timer, selected + dirty index and preset count. Handy for testing the difficulty/cooldown rules. Off by default. |

Positions are in GUI pixels, measured inward from the chosen screen anchor. Colours are `#RRGGBB`.

Anchors: top_left, top_center, top_right, middle_left, middle_center, middle_right, bottom_left, bottom_center, bottom_right.
