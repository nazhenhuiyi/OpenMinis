"""Build species.tsv + species.bin for SpeciesId.kt from the China list (iNat) and BioCLIP 2's embeddings."""
import json, os, struct, numpy as np
OUT = '/tmp/bc-data/species'; os.makedirs(OUT, exist_ok=True)
names = json.load(open('/tmp/bc-data/txt_emb_species.json'))
# A name can appear several times, some with an all-zero embedding: take a non-empty one.
E = np.load('/tmp/bc-data/txt_emb_species.npy', mmap_mode='r')
norms = np.concatenate([np.linalg.norm(np.asarray(E[:, a:a + 50000], np.float32), axis=0) for a in range(0, E.shape[1], 50000)])
row = {}
for j, n in enumerate(names):
    if norms[j] > 1e-6: row.setdefault(' '.join(n[0][5:7]), j)
cn = [x for x in json.load(open('/tmp/bc-data/cn_inat.json')) if x['c'] >= 3]
syn = json.load(open('/tmp/bc-data/syn.json')) if os.path.exists('/tmp/bc-data/syn.json') else {}
genus = json.load(open('/tmp/bc-data/cn_genus.json')) if os.path.exists('/tmp/bc-data/cn_genus.json') else {}
# Pets and garden plants are mostly "casual" on iNat, so missing from the list above.
# BioCLIP files dogs under Canis lupus; in a city photo that is far more often a dog.
OVERRIDE_ZH = {'Canis lupus': '狗 / 狼'}
EXTRA = [('Felis catus', '猫', 'Mammalia'), ('Oryctolagus cuniculus', '家兔', 'Mammalia'),
         ('Mesocricetus auratus', '金仓鼠', 'Mammalia'), ('Carassius auratus', '金鱼', 'Actinopterygii'), ('Melopsittacus undulatus', '虎皮鹦鹉', 'Aves'),
         ('Columba livia', '原鸽', 'Aves'), ('Anas platyrhynchos', '绿头鸭', 'Aves'), ('Gallus gallus', '原鸡', 'Aves')]
fill = {}
if os.path.exists('/tmp/bc-data/fill_emb.npy'):
    fe = np.load('/tmp/bc-data/fill_emb.npy')
    fill = {n: fe[i] for i, n in enumerate(json.load(open('/tmp/bc-data/fill_names.json')))}
out, used = [], set()
for x in sorted(cn, key=lambda x: -x['c']):
    j = row.get(x['n'])
    if j is None and x['n'] in syn: j = row.get(syn[x['n']])
    if j is None and x['n'] in fill: j = ('fill', x['n'])
    if j is None or j in used: continue
    used.add(j)
    g = genus.get(str(x['anc'][-2])) if x.get('anc') and len(x['anc']) >= 2 else None
    gl = g['n'] if g and g['rank'] == 'genus' else x['n'].split()[0]
    gz = g['zh'] if g and g['rank'] == 'genus' else None
    out.append((j, x['n'], OVERRIDE_ZH.get(x['n'], x['zh'] or ''), gl, gz or '', x['g'] or ''))
for latin, zh, grp in EXTRA:
    j = row.get(latin)
    if j is None or j in used: print('extra missing/dup', latin); continue
    used.add(j); out.append((j, latin, zh, latin.split()[0], '', grp))
M = np.stack([fill[o[0][1]] if isinstance(o[0], tuple) else np.asarray(E[:, o[0]], np.float32) for o in out])  # n x 768
norm = np.linalg.norm(M, axis=1)
ok = norm > 1e-6
print('dropping empty embeddings:', [o[1] for o, k in zip(out, ok) if not k][:10], int((~ok).sum()))
out = [o for o, k in zip(out, ok) if k]; M = M[ok] / norm[ok][:, None]
scale = np.abs(M).max(axis=1) / 127.0
Q = np.clip(np.round(M / scale[:, None]), -127, 127).astype(np.int8)
err = np.abs((Q * scale[:, None]) - M).max()
with open(f'{OUT}/species.bin', 'wb') as f:
    f.write(struct.pack('<4sii', b'SPC1', len(out), 768)); f.write(scale.astype('<f4').tobytes()); f.write(Q.tobytes())
with open(f'{OUT}/species.tsv', 'w') as f:
    for o in out: f.write('\t'.join(s.replace('\t', ' ') for s in o[1:]) + '\n')
print(len(out), 'species;', sum(1 for o in out if o[2]), 'with zh;', sum(1 for o in out if o[4]), 'with genus zh; max quant err', round(float(err), 4))
print(os.path.getsize(f'{OUT}/species.bin') / 1e6, 'MB')
