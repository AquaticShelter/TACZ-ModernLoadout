# Project history

Modern Loadout started as a practical problem on a TACZ server: players wanted to pick a class the way they do in Modern Warfare, and
nothing on Forge 1.20.1 did it without also bringing a game mode, teams or an armor system along. This page is the short story of how
the mod got from there to a public release. The per-version detail is in [`CHANGELOG.md`](../CHANGELOG.md) and, for the internal builds,
in [`history/CHANGELOG-dev-full.md`](history/CHANGELOG-dev-full.md). The original Vietnamese README of the internal builds is kept in
[`history/README-legacy-vi.md`](history/README-legacy-vi.md).

## The rule that shaped everything

From the first build the scope was fixed: **only the loadout system**. No matchmaking, no scoreboard, no team logic, no armor. A server
brings its own game mode; Modern Loadout decides what people carry into it and when they may change it. Most later decisions (data-driven
perks, admin panel instead of config files, soft compat with every other mod) follow from keeping that line.

## Internal builds (1.0 - 5.2)

**Foundation (1.0 - 1.2).** A class screen modelled on Modern Warfare 2019, nine slots that the admin controls completely, a first
gunsmith, two perk columns, and reserve ammo worked out from each gun's real magazine instead of a hand-written table, so any gunpack
works out of the box.

**Rules (1.3 - 1.5).** "Change and play": you may always save, but equipping is only allowed after a respawn, after a quiet stretch,
inside edit zones or in creative. A respawn rebuilds your gear from nothing, which closed the obvious duplication paths early.

**Balance (1.6 - 1.9).** The first full perk pass, the Shrapnel rework, optional attribute-library support and the range stat.

**Streaks (2.0 - 2.12).** Killstreaks, Specialist, High Alert, the streak editor and the tracker HUD. This is where the mod stopped being a
menu and started feeling like a shooter.

**Hardening (3.0 - 3.2).** A deliberate round of attacking our own mod: every client packet checked again on the server, edge cases in
death and respawn, Blueprints.

**Admin (4.0 - 4.3).** The stats dashboard (pick rate, kills, headshot rate, time to kill), cleanup of weapons that vanished from a pack,
fire mode memory.

**Levels and compat (5.0 - 5.2).** Player and weapon levels with attachments unlocked by level, the gunsmith redesign, and the first
compatibility work with KillFeed.

## Public releases

**1.0.0** consolidated all of that into a release meant for other people's servers: a data folder outside `config/`, a cloud copy of
your classes that travels between worlds, the in-game admin panel, YACL / Cloth Config screens, and support for TACZ 1.1.6 to 1.2.0
from one jar.

**1.0.1 - 1.0.3** were driven by the mods people run next to it: a bridge to TACZ-ModernTweak for sprint-to-fire stats, built-in
`mw:` speed attributes so Fast Hands no longer needs an attribute library, a firing range per player, and TaCZ Additions support.

**1.0.4** cleaned up the codebase (package `dev.l41nx.mwloadout`), rewrote the KillFeed integration from scratch, worked out a shared
layout API with ModernHUD so the two HUDs stay out of each other's way, and taught the streak counter to ignore vehicles and air strikes.

**1.1.0** is the start of the MW4-style UI: the class and gunsmith screens re-measured against Modern Warfare 4's beta, the loadout
screens given their own UI scale that no longer leaks into anyone's HUD, and a streak counter that finally ignores everything a streak
itself spawned.

## How it is made

The mod is designed, tested and maintained by L41nx. A large part of the code and documentation was written with AI assistance working
from those designs, bug reports and test sessions; everything that ships is built and played on a server first. The changelog is written
for the people running it, including the parts that are still rough.
