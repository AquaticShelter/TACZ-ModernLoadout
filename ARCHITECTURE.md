# Architecture (Forge 1.20.1)

## Design principles

**The server is the only authority.** The client only sends *intent* (put entry X in slot Y, perk Z, use preset N); the server
validates all of it again against the current rules before saving or applying anything. The client never sends an `ItemStack`, only
string ids, and the server builds the stack from the catalog. A forged or invalid id for a slot is dropped on its own (the valid parts
are kept and a toast lists what was removed); nothing crashes.

**Everything is data.** No category, slot or perk is hard-coded:
- `data/SlotDefinition`: a hotbar slot. Id, hotbarIndex 0-8, name, description, icon, allowed categories, whitelist / blacklist,
  maxCount, defaultEntry, required / locked / hidden / enabled.
- `data/CatalogEntry`: the unified item store. Kind GUN (TACZ gun id) or ITEM (item id + SNBT + count), with a free-text category;
  an admin creates a new category just by typing the same string in two places.
- `data/PerkDefinition`: a perk. Column (perkSlot), icon, effects JSON (`attribute` / `mob_effect` / `special:overkill|scavenger|...`).
- `data/Rules`: edit windows, the Peaceful rule, edit zones, perk columns, `dynamicAmmoEnabled` + `dynamicAmmoTiers` (on by default:
  reserve is worked out from the gun's real magazine, see below; `ammoByCategory` is only the fallback when dynamic ammo is off or the
  TACZ lookup fails), maxPresets, restockOnRespawn, killstreak and Specialist rules.

**Data lives in the `mwloadout/` folder** next to `config/` (`data/`, `players/`, `cloud/`, `client/`; `-Dmwloadout.dir=` moves it):
`slots.json`, `catalog.json`, `perks.json`, `rules.json`, `players/<uuid>.json`. The first start seeds the defaults
(`registry/Defaults`). Guns are scanned from every loaded TACZ gunpack (`registry/WeaponCatalog`), which only *adds* ids that are not
there yet and never overwrites an admin's edits. Edit through the admin GUI (permission level 2), or edit the files and run
`/mwloadout reload`.

## Data flow

```
 [mwloadout/data/]             [Server]                        [Client]
  slots/catalog/perks/rules ─► LoadoutRegistry ──S2CSyncRegistry──► LoadoutRegistry (copy)
  players/<uuid>.json ──────► ProfileManager  ──S2CSyncProfile───► ClientCache.profile

 GUI ──C2SOpenLoadout──► EditRuleEngine.check ──S2COpenLoadout(editable, reason)──► LoadoutScreen
 GUI ──C2SSaveLoadout(JSON preset)──► sanitize ──write profile──► sync + toast for removed parts
 GUI ──C2SSelectLoadout(index)──► check edit window ──apply now, or "equip on respawn"
 Admin ──C2SAdminUpdate(kind, action, JSON)──► permission 2 ──AdminActions──► save + resync ALL players
```

## Apply pipeline (no duplication path)

`LoadoutApplier.apply`:
1. sanitize the preset;
2. clear the hotbar, main inventory and off hand (armor is never touched, it is out of scope);
3. build each slot's stack from its definition (locked slot: always the admin's `defaultEntry`);
4. reserve ammo: the ammo id comes from the gun's own TACZ index; the amount (`dynamicAmmoEnabled`, on by default) is the real magazine
   capacity (Extended Mag included, read through `TaczStackFactory.getMagazineCapacity`) times `dynamicAmmoTiers` (category-aware,
   shotguns have their own tiers), with Fully Loaded multiplying the matching tier; with dynamic ammo off, `ammoByCategory` is used as
   is. Stacks are split at the item's max size and put in main inventory slots 9-35, never the hotbar;
5. `PerkEffects.applyPerks` (old effects removed first);
6. one `broadcastChanges`.

A restock runs the same pipeline (on respawn when `restockOnRespawn` is on; on a class change when allowed). Clear first, build after,
so there is no duplication path.

## Edit window (EditRuleEngine)

One place answers "may this player change loadout now?": creative / spectator → always; Peaceful with `peacefulAlwaysEditable` → always
(the custom-game switch); within `editSecondsAfterRespawn` after a respawn; after `noDamageSeconds` without taking damage; inside an edit
zone. Logging in counts as a respawn (otherwise "no record yet" would mean "free to change"). The verdict carries the reason and the
seconds left, so the read-only GUI shows exactly the rule the server will enforce. **Saving is always allowed** (only equipping is gated):
a class picked mid-fight is remembered and put on at the next respawn. A use lock blocks swapping right after loadout gear was used.

## Perk engine (PerkEffects)

- `attribute`: an `AttributeModifier` (ADDITION) with a UUID derived from (perk id + attribute id), so removal needs no bookkeeping and
  never overwrites another mod's base value. The mod's own `mw:reload_speed`, `mw:draw_speed` and `mw:ads_speed` attributes (since 1.0.2)
  cover the speed perks without an attribute library.
- `mob_effect`: infinite ambient effects (Quick Fix regeneration, Fragiles strength); only our own infinite + ambient instances are
  removed, so a potion the player drank is never touched.
- `special:overkill` / `special:underkill`: the validator and the GUI widen the secondary (or primary) slot to the other slot's categories
  and forbid the same gun id twice, in one place (`LoadoutApplier.effectiveCategories`) so the two sides can never disagree.
- `special:scavenger`: a player kill refills part of the held gun's ammo allowance (not dropped on the ground when the inventory is full,
  so it cannot be farmed); lethal / tactical refill on a timer, capped at the slot's maxCount. The timer is one modulo per tick and the
  player scan runs every 600 ticks; inventories are never scanned every tick (budget: 100 players).

## Client and GUI (client/gui)

- `MwScreen` is the base of every loadout screen: its own UI scale (`uiScale`), the shared open / close fade, deferred tooltips.
  `MwScaleEvents` switches the window's GUI scale per frame phase, so the HUD is drawn at the game's scale and the screen at the
  loadout scale (see `docs/UI_MW4_CONCEPT.md` §5).
- `MWStyle`: tokens and drawing helpers (near-black background, charcoal panels, 1 px hairlines, caps text, signal accent); hover fades
  ease on frame delta, with no tick handler and no per-frame allocation.
- `LoadoutScreen` (class rail, slot cards, perks, killstreaks, SAVE / EQUIP, read-only banner with the reason) → `SlotPickerScreen`
  (category tabs, search, list and detail) → `AttachmentEditorScreen` (gunsmith: slot tags around the model, picker, stats, Advanced
  Stats) and `PerkPickerScreen` / `KillstreakSelectScreen`.
- `GunPreview` draws the TACZ model (refit camera or a FIXED-context render) under its own wide-depth projection.
- `admin/AdminScreen`: SLOTS / CATALOG / PERKS / RULES / ADD / STATS tabs, list + form, plus the killstreak editor.
- `IconCache`: one icon stack per entry, so a 60 fps grid does not churn NBT.
- Entry points: LOADOUT button on the pause menu, the <kbd>L</kbd> key, `/mwloadout`.

## Compat (compat/)

Every other mod is optional and reached by reflection or `MethodHandle`, looked up once, behind a "mod loaded" check. A failure turns the
feature off for the session with one log line. `HudAvoid` (ModernHUD layout boxes), `KillFeedCompat`, `ModernTweakBridge`, and the
public client API for other HUDs in `api/StreakApi`.
