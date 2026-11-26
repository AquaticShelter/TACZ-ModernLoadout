# MWLoadout UI language (v39+)

The goal: a loadout UI that cannot be mistaken for the generic "dark card, thin border, glowing accent" kit that auto-generated mods ship.
Every rule below lives in `MWStyle` so a screen gets it by calling `panel / tile / header / glowOutline`, not by hand-drawing.

## The silhouette
- **Cut plates.** Panels, tiles and toasts are rectangles with the TOP-RIGHT and BOTTOM-LEFT corners cut at 45 degrees
  (`MWStyle.plateFill / plateEdge`, stair-stepped pixel exact). Two opposite corners, never all four, never rounded.
- **Hard shadow, no glow.** Depth = a solid offset copy of the plate (`plateShadow`). `glowOutline` no longer draws a halo.
- **Brackets for focus.** Selection / focus = corner brackets (`brackets`) whose LENGTH breathes, plus a solid signal strip. Never a blur.
- **Hover leans, it does not tint.** The old light-blue wash is gone: a solid signal strip grows down the left edge of the tile.

## Detail language
- Section headers end in a stencil tick (a ruler end), the panel carries a short signal tick on its top-left.
- Toasts are *dispatch tags*: an accent block with a one-letter code, the title in caps, a stepped timer rail that ticks down.
- Text stays ALL-CAPS for labels, sentence case for values / descriptions.

## What to avoid (the tells)
1. Soft glows, gradient panels, drop shadows with blur.
2. A thin coloured stripe on the left of a card as the only identity.
3. Every element in the same rounded rectangle at the same weight.
4. Emoji / sparkle icons, centred-everything layouts.
5. One accent colour sprayed on every state (hover, selected, success all the same gold).

## Switch
Client config `angularUi` (default on). Off = the old flat rectangles, so the two can be compared side by side.

## Motion
- Screens fade and slide in (`chromeOpen`); every `fillF` / `strF` and the flat buttons follow it, so a screen can fade a whole group by lowering it.
- Gunsmith PREVIEW: the UI fades out while buttons slide down 8px and the gun stage grows to the full window (about 0.25s, smoothstep); leaving
  reverses it, and the model eases back to the home angle. While fully in preview only the gun is drawn.
- The utility row (BLUEPRINTS / MODE / FLIP / PREVIEW) packs left every frame and slides when a button appears or hides (no fixed slots).

## Phase 2 (in progress)
Done: buttons (three tiers, light-face hover, hatched disabled, key chips), thinner Edit Loadouts column that fits at GUI scale 3.
Todo, in this order: gunsmith slot tags around the gun (empty `+`, equipped gold with check, preview green); attachment picker with the
equipped item pinned first, pros/cons columns and stat deltas; right-hand Advanced Stats column on wide windows; the attachment dots
become small notched squares; perk icons on cut plates; sound set (current clicks are vanilla UI pitches).
Reference boards: the UI concept canvas (Edit loadouts, Gunsmith, Attachment picker, Buttons).
