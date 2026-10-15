#!/usr/bin/env python3
"""Score = 1 - P(first token is 记录). Sweep the threshold instead of trusting argmax."""
import argparse, json, math, pathlib, sys, urllib.request
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import eval as E

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=8089)
ap.add_argument("--data", default=str(E.HERE / "dataset.tsv"))
ap.add_argument("--prompt", default="catchat", choices=list(E.VARIANTS))
a = ap.parse_args()
system, shots, _ = E.VARIANTS[a.prompt]()
rows = E.load(pathlib.Path(a.data))

def p_record(text):
    body = {"prompt": E.build_prompt(system, text, shots), "n_predict": 1, "temperature": 0,
            "n_probs": 20, "cache_prompt": True}
    r = json.load(urllib.request.urlopen(urllib.request.Request(
        f"http://127.0.0.1:{a.port}/completion", data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"})))
    top = r["completion_probabilities"][0]["top_logprobs"]
    pr = {t["token"]: math.exp(t["logprob"]) for t in top}
    rec = sum(v for k, v in pr.items() if k.startswith("记"))
    act = sum(v for k, v in pr.items() if k.startswith(("待", "查")))
    return act / (rec + act) if rec + act else 0.0

for r in rows:
    r["s"] = p_record(r["text"])

pos = [r["s"] for r in rows if r["y"] == "是"]; neg = [r["s"] for r in rows if r["y"] == "否"]
auc = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg) / (len(pos) * len(neg))
print(f"== prompt={a.prompt}  AUC {auc:.3f}")
for th in (0.02, 0.05, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5):
    tp = sum(s >= th for s in pos); fp = sum(s >= th for s in neg)
    P = tp / (tp + fp) if tp + fp else 0; R = tp / len(pos)
    print(f"  阈值 {th:<4}  精确率 {P:.0%}  召回率 {R:.0%}  误报 {fp}  漏判 {len(pos) - tp}")
print("\n分数最低的'是'：")
for r in sorted([r for r in rows if r["y"] == "是"], key=lambda r: r["s"])[:12]:
    print(f"  {r['s']:.3f}  {r['text']}")
print("分数最高的'否'：")
for r in sorted([r for r in rows if r["y"] == "否"], key=lambda r: -r["s"])[:10]:
    print(f"  {r['s']:.3f}  {r['text']}{'  (争议)' if r['hard'] else ''}")
