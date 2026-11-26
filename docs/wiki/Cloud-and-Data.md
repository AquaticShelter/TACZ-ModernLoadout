# Cloud and data

## The data folder

Everything is stored in one folder outside `config/`:

```
mwloadout/
  data/      slots, catalog, perks, rules, stats          (server: the whole game setup)
  players/   one json per player: classes and gun builds   (server, and the single-player world)
  cloud/     your own copy of your classes                  (client; backups/ keeps the last 5)
  client/    client-only data
```

On a dedicated server it sits next to `config/` in the server folder; on the client, in the game folder.
Start with `-Dmwloadout.dir=/some/path` to put it somewhere else.

The first start after upgrading from a build that kept data in `config/mwloadout/` copies it over. It never overwrites
a file that already exists in the new place, and it leaves the old files where they were as a backup.

## Cloud copy

Your client keeps a copy of your own classes and gun builds in `cloud/`, one file per Minecraft account.

- **Importing into a server**: when you join a server on which your loadout is still untouched, the mod offers to fill it from your
  copy. The **CLOUD** button (top right of the class screen) sends it again on purpose. That replaces the classes you
  have on that server, so it asks you to press twice.
- **Saving back**: on a remote server the **TO DEVICE** button saves what the server holds for you into your cloud copy. The
  previous copy is backed up first. Use CLOUD in a single-player world afterwards to load it there.
- The server checks everything it receives (size, structure, what the server actually has) and drops what it cannot use.
  After an import the toast says how many classes lost items the server does not have, and how many were skipped.

Heads up: a server may offer fewer weapons than your single-player world. Saving a server's classes over your full
copy loses the ones that used missing guns. The backup is there for that case.

Auto-refreshing the copy from servers is off by default (`cloudWriteFromServers`) for the same reason.
