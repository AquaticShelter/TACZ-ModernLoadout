# Release test checklist

The manual pass run before every release. A pull request only needs the sections it touches; say which in the PR.

Setup: TACZ with a few gunpacks, LRTactical, the mod on both sides. On the first start check that `mwloadout/data/` holds the four
JSON files and the log says `Auto-added N guns`.

## Basics
1. <kbd>L</kbd> opens the loadout; Esc → LOADOUT button top-left of the pause menu; `/mwloadout`. All three open the same screen.
2. Class 1: click PRIMARY → category tabs (ar / smg / lmg / sr / dmr / sg / br) and search work → pick a gun → CUSTOMIZE → fit a scope
   and a muzzle → back → SAVE → EQUIP. Hotbar slot 1 holds the gun with exactly those attachments; reserve ammo of the right type is in
   the main inventory (not the hotbar).
3. Ammo with dynamic ammo off: AR / SMG 120 rounds; LMG 200; BR 100; DMR 80; shotgun 50; sniper 25; `tacz:50bgm` 15; pistol in slot 2
   60; launcher 3.
4. Melee / consumable / lethal / tactical: pick knife / stim / frag / flash → equip → use each.
5. Rename a class, switch to another class and back: name and build kept. Quit and rejoin: still there.

## Edit rules
6. Survival, take one hit → open the loadout: read-only "can edit in Xs" counts down correctly; EQUIP → toast "will be equipped on respawn".
7. Die → respawn → the chosen class is put on; within the first 10 s the loadout can be changed at once.
8. Wait 45 s without damage → can change.
9. `/difficulty peaceful` → change any time; `/difficulty easy` → rules back.
10. Admin adds an edit zone around spawn → inside it you can change even right after combat.

## Perks
11. Overkill: slot 2 opens the primary categories (pistols gone); picking the same gun as slot 1 is removed on save (toast). Remove
    Overkill → slot 2 back to pistol / launcher.
12. Quick Fix / Fragiles: equip → infinite regeneration / strength; switch perk → the effect is gone.
13. Battle Hardened: 10 armor icons (20 armor points) with nothing worn.
14. Double Time: clearly faster sprint; remove the perk → normal.
15. Fast Hands: reload and draw are 1.35× faster (`mw:reload_speed`, `mw:draw_speed`); quick throw / stim / plate clips of other mods keep
    their timing.
16. Scavenger: kill a player → extra ammo; wait for the refill → lethal / tactical +1 (never above max).

## Admin
17. `/mwloadout admin` without op → refused; with op → opens. Rename slot 1 → another player with the GUI open sees it at once.
18. CATALOG: add `minecraft:golden_apple`, category `extra1`, count 3; enable `extra1` in SLOTS → players can pick it and get 3 in hotbar 7.
19. Blacklist a gun in the primary slot → gone from the picker; an old preset holding it loses only that part on equip (toast).
20. Locked slot + defaultEntry → players cannot click it and always get the admin's item.
21. RULES ammo `ar=999` → re-equip gives 999. `/mwloadout reload` after editing a file by hand.

## Duplication
22. EQUIP ten times in a row → always exactly one set. Die and respawn quickly several times → nothing doubles. Put ordinary items in
    the inventory and equip → cleared, only the loadout remains (armor kept).

## Dynamic ammo (on by default)
23. New world, RULES untouched → EQUIP a Glock 17 (18 rounds) → reserve 54 (18 × 3). A shotgun with 8 → reserve 64 (8 × 8, shotgun tier).
24. Fit an Extended Mag (30 → 50), SAVE, EQUIP → reserve 100 (50 × 2), not the tier of the old 30.
25. Fully Loaded → EQUIP the 8-round shotgun (tier × 8, normal reserve 64) → reserve 8 × 8 × 4 = 256. If the reserve *drops* compared to no
    perk, it is a bug.
26. Change `dynamicAmmoTiers` → re-equip → the new tier applies without a restart.
27. A gun whose TACZ lookup fails (wrong id, pack removed) → no crash, falls back to `ammoByCategory`.
28. `dynamicAmmoEnabled` off → reserve follows `ammoByCategory` directly.
29. `categoryMultipliers` = `sg=10` → any shotgun (even 20 rounds) → reserve = magazine × 10, tiers ignored.
30. `ammoMultiplier` = 6 on one gun in CATALOG (its category also in `categoryMultipliers`) → that gun: magazine × 6. Another gun of the same
    category without its own value → still `categoryMultipliers`.

## Killstreaks
31. New world → `killstreakEnabled` is false by default. An existing server with a saved `rules.json` keeps its value.
32. Hardline, reach every rung (one kill cheaper) → die → respawn: streak 0, streak items removed (also with keepInventory), HUD bar at 0.
    `streakResetOnDeath=false` keeps the old behaviour.
33. Killchain: first death keeps the streak and items while a streak item is still unearned; a second death in a row resets.
34. HUD: the bar rises smoothly per kill, earned rungs turn green with a check, "MAX" when full; `streakHudEnabled=false` hides it; scale /
    offset / opacity / colour / auto-hide work.
35. Specialist: four rungs 2 / 4 / 6 / 8; switching modes leaves no old bar behind.
36. Pool: up to 50 rewards in the editor (same kill cost allowed); the loadout picks 3 with different kill costs; a same-cost pick replaces
    the old one with a warning; each class keeps its own set; the server grants only the active class's streaks.
37. Kills with a streak item, or anything it spawned (missiles, drones, turrets, bomblets), do not add to the streak; Quick Fix,
    Scavenger and XP still trigger. `streakIgnoreStrikeMods`: SuperbWarfare / Airstrike / AC-130 kills never count.
38. Radial (hold <kbd>X</kbd>): release on a lit slot takes the item; a TACZ gun is only taken in hand, any other item is also used; after it
    is spent you are back on the previous slot; the X wedge returns without using anything.
39. Using any streak item shows the callout banner to everyone (ally / enemy colours); dropping it, dying or switching class does not.

## Use lock
40. A non-op fires one shot with a loadout gun → class swap blocked, banner "Gear used - swap in Ns".
41. Throw every grenade, stand in a spawn zone, try to swap → still blocked.
42. Respawn → swap allowed at once. `useLockSeconds=0` → only a respawn unlocks.
43. Reloading or sorting the inventory does not trigger the lock. Ops / creative are not locked (unless `restrictOpAndCreative`).

## Field upgrades
44. A fresh equip starts an 80 s cooldown (vanilla cooldown overlay); after it the item can be used; switching class while keeping the item
    does not reset it; using it recharges for 80 s; death restarts it. Per-item override in CATALOG > "Field Cooldown (s)".
45. Placed / thrown field upgrades only start the cooldown when the item is really used (stack goes down). "Recharge On Click" for reusable tools.
46. Tune Up: field upgrades recharge 40 % faster.

## Admin panel and Creative Browser
47. Typing in a field: Ctrl+A / C / V / X act on the text; Tab / Shift+Tab move between fields.
48. CATALOG: Ctrl+click / Shift+click / Ctrl+A (not while typing) multi-select → BULK EDIT (enable / disable / move category / unlock /
    two-step delete).
49. ADD tab: BROWSE CREATIVE ITEMS (or Ctrl+B) opens the Creative Browser; drag-select, Ctrl+wheel zoom; search `@tacz is:gun -ak`,
    `cat:sr`, `#weapons`, quoted phrases; ADD n ITEMS adds n entries with the right category.
50. STATS: KPI cards, charts and insights; RESET STATS works.
51. Killstreak editor: + SLOT / - SLOT keep unsaved edits; a non-gun reward previews with its real icon and name and is granted intact.

## UI scale, HUD and preview (1.1.0)
52. `uiScale = 0` at 1080p, 1440p and 4K: the loadout screens come out at 200 / 250 / 400 %. A fixed value (e.g. 150) applies; changing it
    with a screen open re-lays the screen out on the next frame.
53. With a loadout screen open, the HUD (hotbar, chat, ModernHUD widgets, KillFeed) is exactly the size it is with the screen closed, at
    GUI scale 2, 3 and 4. Toasts are not resized either.
54. Mouse clicks, drag-rotate, scroll and hover land where the cursor is at every `uiScale`.
55. Close the screen: the game's GUI scale is back for every other screen.
56. Gunsmith and weapon picker: zoom the gun in as far as it goes and rotate it: the model never disappears (also with TaCZ Accelerated).
57. Hover stat bars, perk / killstreak entries and weapon rows next to the gun preview and under pop-up menus: the tooltip is always on top
    and fully visible.
58. ModernHUD 2.0.1+: its killstreak widget hides ours; with it off, our tracker, toasts and callouts step out of its boxes.

## Data, cloud and config
59. **Data folder:** start with an old `config/mwloadout/` present. After the first start `mwloadout/data`, `players` and `cloud` exist,
    the old files are untouched and `MOVED.txt` is in the old folder. A second start copies nothing.
60. **Cloud, both ways:** join a dedicated server with an untouched profile and accept the import. Press CLOUD (twice) and check the classes
    are replaced. Press TO DEVICE and check `cloud/backups/` got the previous copy.
61. **Underkill:** with Underkill the primary slot accepts a pistol; with Overkill the secondary accepts a rifle; with both, Overkill wins.
62. **Specialist:** the stage lists scroll, Shield Up is first in stage 3, Killchain / Hardline / Overkill / Underkill are never offered and
    are excluded at 8 kills; with Specialist on, Killchain and Hardline are dimmed and locked.
63. **Hit marks:** hit a player carrying a listed perk and the icon shows under the marker, with and without TACZ Hitmarker.
64. **Gunsmith:** an extended mag changes both the magazine and the reserve (fitted here and in TACZ's own screen); the laser colour slider
    changes the laser; V saves the model angle; BLUEPRINTS and MODE do not overlap.
65. **Config screen:** opens from the mod list with YACL and with Cloth Config; with neither the in-game settings open.
66. **Log:** after a normal session, search `latest.log` for `Ignored an exception at` and open an issue for any that repeat.
