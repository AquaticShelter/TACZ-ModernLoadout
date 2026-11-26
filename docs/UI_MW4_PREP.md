# MW4-style UI: work order

The aim is to bring the loadout screens closer to the Call of Duty: Modern Warfare 4 (2026) class / gunsmith screens without
re-doing them all at once. This file records where things stand and the order to work in. Exact sizes and colours
are **taken from reference captures**, never guessed - fill in section 2 before changing a token.

## 1. Where it stands
| Screen | Lines | Notes |
|---|---|---|
| AdminScreen | ~2810 | own layout code; last to move |
| AttachmentEditorScreen (gunsmith) | ~2700 | slot tags done (Phase 2); picker next |
| LoadoutScreen (Edit Loadouts) | ~1540 | column sized from the MW4 class screen |
| AdvancedStatsPanel | ~1510 | sprint-to-fire now ignores sights |
| SlotPickerScreen | ~1300 | list + detail, closest to the MW4 picker |
| KillFeedSettingsScreen | small | first screen on the shared transition (below) |

- All loadout screens extend `MwScreen`: own UI scale (`uiScale`, 1.1.0), shared fade, deferred tooltips.
- **New in 1.0.4:** `MwScreen.mwOpen / mwLeave / mwFade / mwClosing` hold the open/close fade and screen hand-off once.
  Seven screens still carry their own copy (`transitionTo` / `transitionRender` / `leaveTo`); move them one at a time,
  test each (open, back, ESC, open another screen mid-fade), then delete the copy.
- Style rules and Phase 2 list: `docs/UI_DESIGN.md`. Tokens and drawing helpers: `MWStyle`.

## 2. Reference capture
Done: the measurements, colours and motion targets are in [`UI_MW4_CONCEPT.md`](UI_MW4_CONCEPT.md) §3-6. The checklist below is kept
for re-measuring against newer captures.
From 1080p footage / screenshots of the MW4 class and gunsmith screens, write down in a table here:
- [ ] header band height, title size, tab spacing, the underline of the active tab
- [ ] left rail width as % of screen, row height, gap, how the selected row is marked
- [ ] detail panel width %, stat row height, the bar style (base vs delta colours, +/- text)
- [ ] footer key-hint row: height, key plate shape, spacing
- [ ] the few colours used: background, panel, text, dim text, positive, negative, selected
- [ ] motion: how long the screen in/out takes, how a selection moves (slide vs snap)
Use percentages of screen width/height so the result holds at every GUI scale.

## 3. Token layer
Keep every number from section 2 in one place (`MWStyle` constants, or a small `MwTokens` class if it grows):
spacing steps, row heights, rail/panel ratios, the colour set, animation times. Screens read tokens, never literals.
`angularUi` off must still give the old flat look.

## 4. Component kit (build in `client/gui/kit`, each one usable alone)
1. **TopTabs** - header tabs with the active underline, Q/E cycling.
2. **Rail** - numbered vertical list (classes, attachment list), scroll, hover lean, selected strip.
3. **DetailPanel** - title, sub-title, stat rows with `StatDeltaBar` (base / gain / loss segments).
4. **KeyHints** - footer row of key plates + labels (ESC back, ENTER equip, ...), right-aligned.
5. **Modal** - confirm / rename dialog on a veil, using the shared fade.
Each part takes a rect and the tokens, draws itself, and reports hit boxes - no screen-specific code inside.

## 5. Screen layout target
Header (TopTabs) / left Rail / centre content (gun stage or card column) / right DetailPanel / footer KeyHints.
Narrow windows drop the DetailPanel into a toggle instead of squeezing it.

## 6. Order of work
1. Fill section 2. 2. Tokens. 3. Move the seven screens to the shared transition.
4. KeyHints + StatDeltaBar (small, used everywhere). 5. SlotPicker on Rail + DetailPanel (closest match, good test).
6. Gunsmith picker (pinned equipped item, pros/cons, deltas). 7. Edit Loadouts header/footer. 8. Admin last.
Each step ships on its own and keeps the old look behind `angularUi`.

## 7. Compat to keep in mind
- ModernHUD 2.0.1+ reports its HUD boxes (`HudLayout`); in-game overlays (streak tracker, toasts, callouts) step around them
  via `compat/HudAvoid`. New in-game overlays should do the same.
- Screens are not affected by the HUD; only overlays are. Since 1.1.0 the HUD is always drawn at the game's own GUI scale.
