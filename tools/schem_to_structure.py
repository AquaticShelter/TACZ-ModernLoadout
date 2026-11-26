#!/usr/bin/env python3
"""Convert a Sponge v2 .schem (WorldEdit) into a vanilla structure .nbt for MWLoadout's firing range.

Beyond the plain conversion it does three things the raw schematic needs before it can live in a mod:
  * command blocks hold ABSOLUTE coordinates of the original build world; they are rewritten to relative (~ ~ ~) ones,
  * command blocks that change player game modes (/gamemode ... @a) are removed outright (admin toggles from the build world),
  * the two WorldEdit-style corner markers (diamond block / gold block on opposite bounding-box corners) are dropped.
Air is not written (the dimension is void), the structure keeps the schematic's full size.
Usage: schem_to_structure.py in.schem out.nbt
"""
import sys, re, os
sys.path.insert(0, os.path.dirname(__file__))
import nbtmini as N

def varints(data):
    vals = []; i = 0
    while i < len(data):
        v = 0; sh = 0
        while True:
            b = data[i]; i += 1
            v |= (b & 0x7f) << sh; sh += 7
            if not b & 0x80: break
        vals.append(v)
    return vals

def parse_state(s):
    m = re.match(r'^([^\[]+)(?:\[(.*)\])?$', s)
    name, props = m.group(1), m.group(2)
    d = {'Name': (N.STRING, name)}
    if props:
        d['Properties'] = (N.COMPOUND, {k: (N.STRING, v) for k, v in (p.split('=') for p in props.split(','))})
    return d

def is_int(tok):
    return re.fullmatch(r'-?\d+', tok) is not None

def rel(value, lo, cb):
    d = (value - lo) - cb
    return '~' if d == 0 else f'~{d}'

def rewrite_command(cmd, off, cb):
    """Absolute fill/setblock coordinates -> relative to the command block at local position cb."""
    c = cmd[1:] if cmd.startswith('/') else cmd
    t = c.split(' ')
    if t[0] in ('fill', 'setblock'):
        n = 6 if t[0] == 'fill' else 3
        if len(t) > n and all(is_int(x) for x in t[1:n + 1]):
            for k in range(n):
                axis = k % 3
                t[1 + k] = rel(int(t[1 + k]), off[axis], cb[axis])
            return ' '.join(t)
    return None

def main(src, dst):
    _, (_, root) = N.load(src)
    W, H, L = root['Width'][1], root['Height'][1], root['Length'][1]
    off = root['Offset'][1]
    pal = {v[1]: k for k, v in root['Palette'][1].items()}
    vals = varints(root['BlockData'][1])
    assert len(vals) == W * H * L
    data_version = root['DataVersion'][1]

    bes = {}
    for be in root['BlockEntities'][1][1]:
        x, y, z = be['Pos'][1]
        bes[(x, y, z)] = be

    stats = {'cmd_rewritten': 0, 'cmd_removed': 0, 'markers_removed': 0, 'cmd_unconverted': []}
    out_palette = []; pal_index = {}
    blocks = []
    for y in range(H):
        for z in range(L):
            for x in range(W):
                state = pal[vals[(y * L + z) * W + x]]
                name = state.split('[')[0]
                if name == 'minecraft:air':
                    continue
                be = bes.get((x, y, z))
                # corner markers
                if (x, y, z) in ((W - 1, 0, 0), (0, H - 1, L - 1)) and name in ('minecraft:diamond_block', 'minecraft:gold_block'):
                    stats['markers_removed'] += 1
                    continue
                nbt = None
                if be is not None:
                    tag = {k: v for k, v in be.items() if k not in ('Id', 'Pos')}
                    tag['id'] = (N.STRING, be['Id'][1])
                    if name == 'minecraft:command_block':
                        cmd = tag['Command'][1] if 'Command' in tag else ''
                        if 'gamemode' in cmd:
                            stats['cmd_removed'] += 1
                            continue  # block removed entirely
                        new = rewrite_command(cmd, off, (x, y, z))
                        if new is None:
                            stats['cmd_unconverted'].append(((x, y, z), cmd))
                        else:
                            tag['Command'] = (N.STRING, new)
                            stats['cmd_rewritten'] += 1
                    nbt = tag
                if state not in pal_index:
                    pal_index[state] = len(out_palette)
                    out_palette.append(parse_state(state))
                blk = {'pos': (N.LIST, (N.INT, [x, y, z])), 'state': (N.INT, pal_index[state])}
                if nbt is not None:
                    blk['nbt'] = (N.COMPOUND, nbt)
                blocks.append(blk)

    root_out = {
        'DataVersion': (N.INT, data_version),
        'size': (N.LIST, (N.INT, [W, H, L])),
        'palette': (N.LIST, (N.COMPOUND, out_palette)),
        'blocks': (N.LIST, (N.COMPOUND, blocks)),
        'entities': (N.LIST, (N.COMPOUND, [])),
    }
    N.save(dst, '', (N.COMPOUND, root_out))
    print('size', (W, H, L), 'DataVersion', data_version, 'offset', off)
    print('blocks written', len(blocks), 'palette', len(out_palette))
    print({k: v for k, v in stats.items() if k != 'cmd_unconverted'})
    print('unconverted commands:', stats['cmd_unconverted'])

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
