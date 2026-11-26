# Building

```bash
./gradlew build          # jar in build/libs/
./gradlew runClient      # development client
./gradlew runServer      # development server
```

Java 17 and Forge 1.20.1 (the version is in `gradle.properties`).

## Dependencies

| Dependency | How | Why |
|---|---|---|
| TACZ | `compileOnly` from Curse Maven | Guns, attachments, events |
| LRTactical | `compileOnly` | Item ids and tags |
| Cloth Config 11.1.136 | `compileOnly`, optional at runtime | Config screen |
| YACL 3.6.5 | `compileOnly` from maven.isxander.dev, optional at runtime | Config screen |

These are compiled against without `fg.deobf`. Remapping them failed in this setup, and the code only calls their public
API, which keeps its names. The optional ones are only ever loaded behind a "is this mod present" check, so a missing library
cannot cause a class-loading error.

## Layout

```
dev/l41nx/mwloadout/
  MWLoadoutMod, MWPaths, MWLog     entry point, data folders, quiet-error logging
  data/       Rules, PlayerProfile, perks, slots (plain data, json in and out)
  registry/   the server's slots, catalog, perks, rules and their defaults
  server/     applying loadouts, edit rules, streaks, restock, commands
  network/    every packet (Net), versioned protocol
  client/     config, HUD, toasts, radial, hit marks
  client/gui/ screens and the drawing helpers (MWStyle)
```

`ARCHITECTURE.md` has more detail, and `TESTING.md` is the manual test checklist I run before a release.

## Conventions

- Anything the client sends is checked on the server; the client is never trusted.
- User-facing text goes through the lang files (`en_us`, `vi_vn`).
- Catch blocks that swallow an error call `MWLog`, they are never empty.
