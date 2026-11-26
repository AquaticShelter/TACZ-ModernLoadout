# Security policy

Modern Loadout runs on multiplayer servers and hands out gear, so some bugs are exploits: item or ammo duplication, free refills,
bypassing the edit rules, reaching admin actions without operator level, or crashing a server from a client packet.

## Reporting

Please **do not** open a public issue for these. Use GitHub's private vulnerability reporting on this repository
(**Security → Report a vulnerability**). Include:

- the mod version, TACZ version and Forge version;
- the steps to reproduce, and whether it needs a modified client;
- what the player gains (duplicate items, bypass, crash).

You will get an answer once the report has been read. A fix goes into a patch release; the advisory is published after the release is
out, with credit to you unless you prefer not.

## Supported versions

Only the latest release line gets fixes. Server owners should update rather than patch an old jar.
