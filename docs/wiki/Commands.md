# Commands

Players open their loadout with `/mwloadout` (or <kbd>L</kbd>). The admin subcommands are for operators (permission level 2).
`/mwloadout help` lists what you can run and the arguments of each.

| Subcommand | What it is for |
|---|---|
| `admin` | Open the admin panel |
| `reload` | Re-read the data folder without restarting |
| `equip` | Put a player's saved class on them |
| `zone` | Edit zones: `add`, `remove`, `list`, `here`, `clear` |
| `weaponscan` | Rescan the server's guns and items into the catalog |
| `resetperks` | Reset a player's perk picks |
| `diagnose` | Print a report about the setup and a player's loadout |
| `cleanstale` | Remove stored data for players who no longer exist |
| `help` | Show the list above with arguments |

The admin packets from the panel use the same permission check. A client that is not an operator cannot change
the setup, whatever it sends.
