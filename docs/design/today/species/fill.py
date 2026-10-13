"""Embed China-list species that BioCLIP's published table lacks (absent, or one of its all-zero rows)."""
import json, time, numpy as np, torch, torch.nn.functional as F, open_clip
from bioclip.predict import OPENA_AI_IMAGENET_TEMPLATE as T_ALL
T = T_ALL[::10]  # 8 of the 80 templates: ~0.99 cosine to the full mean, 10x faster
names = json.load(open('/tmp/bc-data/txt_emb_species.json'))
E = np.load('/tmp/bc-data/txt_emb_species.npy', mmap_mode='r')
norms = np.concatenate([np.linalg.norm(np.asarray(E[:, a:a + 50000], np.float32), axis=0) for a in range(0, E.shape[1], 50000)])
have, genus_tax = set(), {}
for j, n in enumerate(names):
    tax = n[0]; b = ' '.join(tax[5:7])
    if norms[j] > 1e-6: have.add(b)
    if all(tax[:5]) and tax[5]: genus_tax.setdefault(tax[5], tax[:5])
syn = json.load(open('/tmp/bc-data/syn.json'))
cn = [x for x in json.load(open('/tmp/bc-data/cn_all.json')) if x['c'] >= 3]
todo, nogenus = [], 0
for x in cn:
    if x['n'] in have or syn.get(x['n']) in have: continue
    p = x['n'].split()
    if len(p) != 2: continue
    up = genus_tax.get(p[0])
    if not up: nogenus += 1; continue
    todo.append((x['n'], ' '.join(up + p)))
print('to embed', len(todo), 'no genus taxonomy', nogenus, flush=True)
dev = 'cpu'; torch.set_num_threads(8)
m, _, _ = open_clip.create_model_and_transforms('hf-hub:imageomics/bioclip-2'); m.eval().to(dev)
tok = open_clip.get_tokenizer('hf-hub:imageomics/bioclip-2')
out = np.zeros((len(todo), 768), np.float32); t0 = time.time()
with torch.no_grad():
    B = 8
    for i in range(0, len(todo), B):
        chunk = todo[i:i + B]
        f = F.normalize(m.encode_text(tok([t(c) for _, c in chunk for t in T]).to(dev)), dim=-1).view(len(chunk), len(T), -1).mean(1)
        out[i:i + len(chunk)] = F.normalize(f, dim=-1).cpu().numpy()
        if i % 200 == 0: print(i, f'{time.time() - t0:.0f}s', flush=True)
np.save('/tmp/bc-data/fill_emb.npy', out)
json.dump([n for n, _ in todo], open('/tmp/bc-data/fill_names.json', 'w'))
print('done', len(todo))
