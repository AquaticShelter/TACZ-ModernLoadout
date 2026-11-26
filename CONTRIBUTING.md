# Contributing to L41NX's Modern Loadout

Thanks for wanting to help. This is a one-maintainer project that runs on real servers, so the bar for changes is "does it keep
working for the people already playing on it". Bug reports, test results, translations and focused fixes are all welcome.

## Before you start

- **Bugs:** open an issue with the bug template. The mod version, TACZ version and the `[MWLoadout]` log lines matter more than anything
  else. A crash report or a short clip saves a lot of back and forth.
- **Features:** open a feature request first and wait for a reply before writing code. The mod is deliberately only a loadout system
  (no matchmaking, scoreboard, teams or armor), and a lot of ideas are better as a separate mod that uses the API.
- **Balance:** perk numbers and rules are data (`mwloadout/data/`), so most balance changes do not need code. Say what you changed and
  why in the issue.
- **Security / exploits** (dupes, free refills, admin checks): do not open a public issue. See [SECURITY.md](SECURITY.md).

## Setting up

```bash
git clone <your fork>
cd mwloadout
./gradlew build        # jar in build/libs/
./gradlew runClient    # dev client
./gradlew runServer    # dev server
```

Java 17, Forge 1.20.1 (exact versions in `gradle.properties`). Dependencies and the source layout are described in
[docs/wiki/Building.md](docs/wiki/Building.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

## Ground rules for code

- **The server decides.** Anything a client sends is validated on the server. A screen may hide an option; the server must still refuse it.
- **Soft compat only.** Other mods (ModernHUD, KillFeed, TACZ-ModernTweak, TaCZ Additions, LRTactical...) are reached by reflection or
  `MethodHandle`, behind a "mod loaded" check, looked up once and cached. A missing or changed mod must never crash the game: the
  feature turns itself off for the session and logs one line.
- **New features default to off** for servers unless there is a clear reason (a fix that changes behaviour counts as a feature here).
- **No empty `catch`.** Swallowed errors go through `MWLog` so they leave one line per call site.
- **Text goes through the lang files** (`en_us.json`, and `vi_vn.json` if you can; leave it out otherwise and say so in the PR).
- **Per-frame code allocates nothing it does not have to.** HUD and screen code runs every frame on every client.
- **Keep the look switchable.** UI work keeps the classic style behind `angularUi = false`. Layout numbers come from
  [docs/UI_MW4_CONCEPT.md](docs/UI_MW4_CONCEPT.md), not from guesses.
- Network protocol changes bump the protocol number in `network/Net` and get an **Upgrade notes** line in the changelog.

## Commits and pull requests

- One topic per pull request. A refactor and a fix in the same PR are hard to review and hard to revert.
- Commit messages in English, imperative mood, a short subject line (`Fix tooltip hidden behind gun preview`), and a body that says
  *why* when it is not obvious.
- Add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), in the right section (Added / Changed / Fixed / Removed). Write
  it for players and server owners: what they will notice, not which class changed.
- Run through the parts of [TESTING.md](TESTING.md) your change touches, and say in the PR what you tested (single player, dedicated
  server, which TACZ version, which other mods).

## Releases

Releases are cut by the maintainer: version in `gradle.properties`, changelog section renamed from Unreleased, tag `vX.Y.Z`. The
GitHub workflow builds the jar for every push and attaches it to the release for a tag. Publishing to CurseForge / Modrinth is
described in [docs/PUBLISH.md](docs/PUBLISH.md).

## Translations

Copy `src/main/resources/assets/mwloadout/lang/en_us.json` to your locale (`de_de.json`, `pt_br.json`, ...), translate the values, keep
the keys and the `%s` / `%d` placeholders as they are, and open a PR. Partial translations are fine; missing keys fall back to English.

## Code of conduct

Be decent. The short version and how to report a problem are in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
