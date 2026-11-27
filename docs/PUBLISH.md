# Publishing notes

Working notes for the first release on CurseForge, Modrinth and GitHub. Not part of the wiki.

## Names

| | |
|---|---|
| Title | L41NX's Modern Loadout |
| Mod id | `mwloadout` (kept: changing it would orphan every existing player's data and config) |
| Suggested slugs | `l41nx-modern-loadout`, or `modern-loadout` if it is free |
| Version | 1.0.0 |
| Jar | `mwloadout-1.20.1-1.0.0.jar` (check what `./gradlew build` actually names it) |

## One-line summary (store listings allow about 250 characters)

> Class loadouts for TACZ servers: a gunsmith with builds, perks, killstreaks and Specialist, server-side edit rules, an in-game admin panel and cloud-synced classes.

## Listing fields

| Field | Value |
|---|---|
| Loader / version | Forge, 1.20.1 |
| Environment | Required on client and server |
| License | GPL-3.0-only |
| CurseForge categories | Server Utility, Armor, Tools, and Weapons (check the current category names when you create the project) |
| Modrinth categories | Equipment, Game mechanics, Utility |
| Tags / keywords | tacz, loadout, classes, gunsmith, perks, killstreak, pvp, shooter, server, admin panel |
| Required dependency | TACZ |
| Optional dependencies | YACL, Cloth Config, LRTactical, TACZ Hitmarker, TaCZ Additions |
| Source | your GitHub repository |
| Issues | the repository's issue tracker (templates are in `.github/ISSUE_TEMPLATE`) |
| Wiki | `docs/wiki` in the repository (or copy it to the GitHub wiki) |

## Store description (Markdown, works as is on Modrinth; paste into the CurseForge editor and fix headings)

```markdown
# L41NX's Modern Loadout

Class loadouts for TACZ servers. Players build classes (guns, attachments, equipment, perks); the server decides when they
may change them. On top of that: a gunsmith with builds and blueprints, killstreaks, Specialist, per-gun levels and an
in-game admin panel.

It is only the loadout system. You bring the game mode.

**Needs TACZ. Install on the client and the server.**

## What you get

- Up to 20 saved classes per player, nine configurable slots, rename / pin / duplicate / randomize
- Server-side edit rules: after respawn, after a quiet stretch, inside edit zones, creative / spectator, Peaceful
- A gunsmith: attachment points, build slots, blueprints, fire mode memory, live stats, an advanced panel with damage
  by distance and body zone, time to kill, spread and recoil (shotguns worked out per pellet), a laser colour slider
- Reserve ammo that follows the real magazine size, extended mags included
- 18 perks defined as data, Overkill and Underkill, killstreak rewards with a tracker HUD and an X radial, Specialist
- An admin panel for slots, catalog, perks, rules and streak rewards, plus a stats dashboard
- Your classes can be saved on your own machine and imported into any server, or pulled back from one
- Perk icons under your hit marker when you hit a player

## Install

Same jar on the client and the server, with TACZ and your gunpacks. Start once; the default setup is playable.
Operators run `/mwloadout admin`. Press L in game to open the loadout.

## Optional

YACL or Cloth Config for a full config screen, LRTactical for melee / throwables / consumables, TACZ Hitmarker,
Limb Damage. Fast Hands uses the built-in mw:reload_speed / mw:draw_speed attributes (no attribute library needed).

## Honest notes

- First public release. One unconfirmed crash report on Forge 47.4.28; tested on 47.4.10.
- Most of the code and docs are mine. Claude (by Anthropic) helped with a smaller part, mainly fixing critical bugs from my bug reports and testing.
  It is GPL-3.0, read it if you like.

Docs, issues and source: <your GitHub link>
```

## Gallery (take these in game, GUI scale 3, 1920x1080)

1. Class screen with a class selected (shows the plate UI and the slots)
2. Gunsmith, cinematic or compact layout, with the stat bars
3. Advanced stats panel on the Damage tab, ideally a shotgun with the per-pellet rows
4. Perk screen with the Specialist stage tabs
5. Killstreak radial open (hold X) with two streaks equipped
6. Admin panel, rules tab
7. A hit marker with perk icons under it (a short gif is better than a still)

The generated banner and icons are in `docs/img/` (`banner.png` 1200x300, `banner-1920x480.png`, `icon-512.png`, `icon-256.png`).
They are placeholders built from the supplied logo and a stand-in monospace font: send the real font file and they can be redone.

## Before you press publish

- [ ] **Assets**: confirm where every texture came from, especially `assets/mwloadout/textures/perks/*.png` and the killstreak icons.
      Anything taken from a commercial game is a takedown risk on both platforms. Redraw or replace what you cannot document.
- [ ] **Names**: the title does not use a game publisher's trademark. Some perk and field names echo well-known shooters. Generic names
      are fine, but rename anything distinctive if you are unsure. Search the code and lang files for `MW19`, `MW2019` and `mw19:`
      (the `mw19:` ids are LRTactical items and must stay).
- [x] **Metadata**: `mods.toml` author is `L41nx`, package `dev.l41nx.mwloadout`.
- [ ] **Credits line**: it currently reads "all thanks to Fable 5 <3". Keep the AI credit, but a plainer line reads better, for example:
      `Created by L41nx. Developed with AI assistance (Claude, Anthropic).`
- [ ] **Screenshots and gif** in the README and the gallery are real, from a current build.
- [ ] **Test**: run `TESTING.md` end to end on a dedicated server, with a second client, and read the log for `Ignored an exception at`.
- [ ] **Repository**: LICENSE, README, CHANGELOG, `docs/wiki`, issue templates, a release tag `1.0.0`, the jar attached.
- [ ] **Platform rules**: read the current rules of CurseForge and Modrinth on AI-assisted projects and disclosure before submitting. They
      change, and some require a statement.

## About being credible

Whether a project reads as made by a person depends on what a visitor can check, not on how the text sounds:

- real screenshots and clips from your own game, not renders
- a changelog with dates, specific bugs and what was fixed (the history behind this release is exactly that)
- a short list of known issues and answers to the issues people open
- a documented test checklist (`TESTING.md`)
- plain statements about who did what, including the AI part

The README has a "How this was made" section that says it straight. Change the wording to your own voice, but keep it true.
