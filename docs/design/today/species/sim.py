"""Replay SpeciesId.kt's ranking and thresholds on the Mac, from species.bin/.tsv."""
import sys; import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glob, struct, numpy as np, onnxruntime as ort
from prep import prep
D = '/tmp/bc-data/species'
raw = open(f'{D}/species.bin', 'rb').read()
magic, n, dim = struct.unpack('<4sii', raw[:12])
scale = np.frombuffer(raw[12:12 + 4 * n], '<f4'); Q = np.frombuffer(raw[12 + 4 * n:], np.int8).reshape(n, dim).astype(np.float32)
rows = [l.rstrip('\n').split('\t') for l in open(f'{D}/species.tsv')]
genus = [r[2] for r in rows]; gidx = {g: i for i, g in enumerate(dict.fromkeys(genus))}; gof = np.array([gidx[g] for g in genus])
s = ort.InferenceSession('/tmp/bc-data/bioclip2_img_int8.onnx')
for p in sorted(glob.glob('/tmp/wm/img/*.jpg')) + sorted(glob.glob('/Users/zilin/Documents/ChatGPT/OpenMinis/docs/design/today/promo/photos/*.jpg')):
    f = s.run(None, {'image': prep(p)})[0][0]
    cos = (Q @ f) * scale; best = cos.max(); pr = np.exp((cos - best) * 100); pr /= pr.sum()
    gp = np.bincount(gof, pr, minlength=len(gidx)); i = int(pr.argmax()); g = int(gp.argmax())
    sure = ''
    if best >= 0.68:
        if pr[i] >= 0.5: sure = f'→ {rows[i][1] or rows[i][0]}'
        elif gp[g] >= 0.7: sure = f'→ {list(gidx)[g]} 属的一种 ({rows[[k for k in range(n) if gof[k]==g][0]][3]})'
    print(f"{p.split('/')[-1]:<14} match {best:.2f}  top {rows[i][1] or rows[i][0]} {pr[i]:.0%}  {sure}")
