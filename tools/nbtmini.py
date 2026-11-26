import gzip, struct, io

# Minimal NBT reader/writer (big-endian, Java edition). Tags are (type_id, value); compounds are dict name->(type,value).
END,BYTE,SHORT,INT,LONG,FLOAT,DOUBLE,BYTEARR,STRING,LIST,COMPOUND,INTARR,LONGARR = range(13)

def _rs(f):
    n = struct.unpack('>H', f.read(2))[0]
    return f.read(n).decode('utf-8', 'surrogatepass')

def _read(f, t):
    if t == BYTE: return struct.unpack('>b', f.read(1))[0]
    if t == SHORT: return struct.unpack('>h', f.read(2))[0]
    if t == INT: return struct.unpack('>i', f.read(4))[0]
    if t == LONG: return struct.unpack('>q', f.read(8))[0]
    if t == FLOAT: return struct.unpack('>f', f.read(4))[0]
    if t == DOUBLE: return struct.unpack('>d', f.read(8))[0]
    if t == BYTEARR:
        n = struct.unpack('>i', f.read(4))[0]; return f.read(n)
    if t == STRING: return _rs(f)
    if t == LIST:
        it = struct.unpack('>b', f.read(1))[0]; n = struct.unpack('>i', f.read(4))[0]
        return (it, [_read(f, it) for _ in range(n)])
    if t == COMPOUND:
        d = {}
        while True:
            tt = struct.unpack('>b', f.read(1))[0]
            if tt == END: return d
            name = _rs(f); d[name] = (tt, _read(f, tt))
    if t == INTARR:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%di' % n, f.read(4*n)))
    if t == LONGARR:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%dq' % n, f.read(8*n)))
    raise ValueError(t)

def load(path):
    raw = gzip.open(path, 'rb').read()
    f = io.BytesIO(raw)
    t = struct.unpack('>b', f.read(1))[0]
    name = _rs(f)
    return name, (t, _read(f, t))

def _ws(o, s):
    b = s.encode('utf-8'); o.write(struct.pack('>H', len(b))); o.write(b)

def _write(o, t, v):
    if t == BYTE: o.write(struct.pack('>b', v))
    elif t == SHORT: o.write(struct.pack('>h', v))
    elif t == INT: o.write(struct.pack('>i', v))
    elif t == LONG: o.write(struct.pack('>q', v))
    elif t == FLOAT: o.write(struct.pack('>f', v))
    elif t == DOUBLE: o.write(struct.pack('>d', v))
    elif t == BYTEARR: o.write(struct.pack('>i', len(v))); o.write(v)
    elif t == STRING: _ws(o, v)
    elif t == LIST:
        it, items = v; o.write(struct.pack('>b', it)); o.write(struct.pack('>i', len(items)))
        for x in items: _write(o, it, x)
    elif t == COMPOUND:
        for k, (tt, vv) in v.items():
            o.write(struct.pack('>b', tt)); _ws(o, k); _write(o, tt, vv)
        o.write(b'\x00')
    elif t == INTARR: o.write(struct.pack('>i', len(v))); o.write(struct.pack('>%di' % len(v), *v))
    elif t == LONGARR: o.write(struct.pack('>i', len(v))); o.write(struct.pack('>%dq' % len(v), *v))
    else: raise ValueError(t)

def save(path, name, tag):
    o = io.BytesIO(); o.write(struct.pack('>b', tag[0])); _ws(o, name); _write(o, tag[0], tag[1])
    with gzip.open(path, 'wb') as g: g.write(o.getvalue())
