#!/usr/bin/env python3
"""Check a bundle's Import-Package against a stopped/running AEM's bundle cache, offline.

1. Resolution: every import must have an exporter in range (java.* and system packages
   from sling.properties count as provided; org.osgi.framework comes from the system bundle).
2. AEM 6.5 LTS API controller: flags imports whose exporting bundle restricts importers
   (com.adobe.granite.apicontroller.FilterResolverHookFactory config).

usage: check-imports.py <crx-quickstart/launchpad/felix> <imports.txt> <conf/sling.properties> \
                        [<FilterResolverHookFactory.config> [<bundle-symbolic-name>]]
imports.txt: one Import-Package clause per line.
"""
import os, re, sys, zipfile, glob
felix = sys.argv[1]; imports_file = sys.argv[2]; sling_props = sys.argv[3]
def manifest(jar):
    try:
        with zipfile.ZipFile(jar) as z: raw = z.read('META-INF/MANIFEST.MF').decode('utf-8','replace')
    except Exception: return {}
    raw = raw.replace('\r\n','\n').replace('\n ','')
    h = {}
    for line in raw.split('\n'):
        if ':' in line:
            k, v = line.split(':',1); h[k.strip()] = v.strip()
    return h
def split_clauses(s):
    out, depth, cur, q = [], 0, '', False
    for ch in s:
        if ch == '"': q = not q
        if ch == ',' and not q: out.append(cur); cur = ''
        else: cur += ch
    if cur: out.append(cur)
    return [c.strip() for c in out if c.strip()]
def parse_ver(v):
    p = (v.split('.') + ['0','0','0'])[:3]
    try: return tuple(int(re.sub(r'\D.*','',x) or 0) for x in p)
    except: return (0,0,0)
def in_range(ver, rng):
    if rng is None: return True
    m = re.match(r'([\[\(])\s*([^,]+)\s*,\s*([^\]\)]+)\s*([\]\)])', rng)
    if not m: return parse_ver(ver) >= parse_ver(rng)
    lo, hi = parse_ver(m.group(2)), parse_ver(m.group(3)); v = parse_ver(ver)
    ok_lo = v >= lo if m.group(1) == '[' else v > lo
    ok_hi = v <= hi if m.group(4) == ']' else v < hi
    return ok_lo and ok_hi
exports = {}
for d in sorted(glob.glob(os.path.join(felix,'bundle*'))):
    vs = sorted(glob.glob(os.path.join(d,'version*')), key=lambda p: [int(x) for x in re.findall(r'\d+', os.path.basename(p))])
    if not vs: continue
    jar = os.path.join(vs[-1],'bundle.jar')
    h = manifest(jar); bsn = h.get('Bundle-SymbolicName','?').split(';')[0]
    for c in split_clauses(h.get('Export-Package','')):
        parts = c.split(';'); names = [p for p in parts if '=' not in p]
        ver = '0.0.0'
        for p in parts:
            m = re.match(r'version\s*=\s*"?([^";]+)"?', p.strip())
            if m: ver = m.group(1)
        for n in names: exports.setdefault(n.strip(), []).append((ver, bsn))
sysp = open(sling_props).read()
missing = []; ok = 0
for line in open(imports_file):
    line = line.strip()
    if not line: continue
    parts = line.split(';'); name = parts[0].strip()
    rng = None; optional = 'resolution:=optional' in line
    m = re.search(r'version="([^"]+)"', line)
    if m: rng = m.group(1)
    if name.startswith('java.'): ok += 1; continue
    # Exported by the OSGi framework's system bundle (not in the bundle cache).
    if name.startswith('org.osgi.framework') or name.startswith('org.osgi.resource') or name.startswith('org.osgi.service.startlevel'): ok += 1; continue
    cands = exports.get(name, [])
    hit = [c for c in cands if in_range(c[0], rng)]
    if hit: ok += 1; continue
    if not rng and re.search(r'(^|[ ,=\\])' + re.escape(name) + r'(?=[;, \\]|$)', sysp): ok += 1; continue
    missing.append((name, rng, optional, cands[:3]))
print(f"resolved {ok}, unresolved {len(missing)}")
for m in missing: print("UNRESOLVED", m)

# --- apicontroller analysis ---
import fnmatch
cfgp = sys.argv[4] if len(sys.argv) > 4 else None
mybsn = sys.argv[5] if len(sys.argv) > 5 else 'com.oakchain.connector.mount'
if cfgp:
    rules = {}
    for line in open(cfgp):
        m = re.match(r'([^=:]+)="(.*)"\s*$', line.strip())
        if m: rules[m.group(1)] = [p.strip() for p in m.group(2).split(',')]
    print("\n--- apicontroller: imports whose exporter restricts importers ---")
    for line in open(imports_file):
        line=line.strip(); name=line.split(';')[0]
        if name.startswith('java.') or not line: continue
        rng=None; m=re.search(r'version="([^"]+)"', line); rng=m.group(1) if m else None
        for ver,bsn in exports.get(name, []):
            if not in_range(ver, rng): continue
            if bsn in rules:
                allowed = any(fnmatch.fnmatch(mybsn, p) for p in rules[bsn])
                print(("ALLOWED " if allowed else "BLOCKED ") + f"{name} <- {bsn} rule={rules[bsn]}")
