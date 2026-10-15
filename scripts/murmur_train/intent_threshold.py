#!/usr/bin/env python3
"""Intent as a score: 1 - P(first token = 记录). Sweep thresholds for recall."""
import argparse, json, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import schema as S
import mlx.core as mx
from mlx_lm import load

ap = argparse.ArgumentParser()
ap.add_argument("data"); ap.add_argument("--model", required=True); ap.add_argument("--adapter")
a = ap.parse_args()
model, tok = load(a.model, adapter_path=a.adapter)
rec_id = tok.encode("记录", add_special_tokens=False)[0]
items = [json.loads(l) for l in open(a.data)]
scores = []
for x in items:
    prompt = tok.apply_chat_template([{"role": "system", "content": S.SYSTEM}, {"role": "user", "content": x["text"]}],
                                     tokenize=False, add_generation_prompt=True, enable_thinking=False)
    ids = mx.array(tok.encode(prompt, add_special_tokens=False))[None]
    p = mx.softmax(model(ids)[0, -1].astype(mx.float32))
    scores.append(1 - p[rec_id].item())
gold = [S.parse(x["label"])["intent"] != "记录" for x in items]
pos = [s for s, g in zip(scores, gold) if g]; neg = [s for s, g in zip(scores, gold) if not g]
auc = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg) / (len(pos) * len(neg))
print(f"AUC {auc:.3f}  要办 {len(pos)} / {len(items)}")
for th in (0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5):
    tp = sum(s >= th for s in pos); fp = sum(s >= th for s in neg)
    print(f"  阈值 {th:<5}  召回 {tp/len(pos):.0%}  送云端 {tp+fp}/{len(items)} ({(tp+fp)/len(items):.0%})  精确 {tp/max(1,tp+fp):.0%}")
print("最难抓的要办：")
for s, x in sorted([(s, x) for s, x, g in zip(scores, items, gold) if g], key=lambda t: t[0])[:6]:
    print(f"  {s:.4f}  {x['text'][:50]!r}")
