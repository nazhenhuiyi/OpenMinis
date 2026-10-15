#!/usr/bin/env python3
"""Score every checkpoint of a run on the dev set (never the real test set) and
optionally average the best one with its neighbours.

    ~/venvs/mlx/bin/python scripts/murmur_train/select_ckpt.py scripts/murmur_train/out/run5 \
        --dev scripts/murmur_train/dev/dev_long.jsonl --average 3
"""
import argparse, glob, json, pathlib, shutil, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import schema as S
import mlx.core as mx
from mlx_lm import load, generate
from mlx_lm.sample_utils import make_sampler

ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("--dev", required=True)
ap.add_argument("--model", default=str(pathlib.Path.home() / "models/Qwen3-0.6B-hf"))
ap.add_argument("--average", type=int, default=0, help="average best with neighbours, k checkpoints total")
a = ap.parse_args()
run = pathlib.Path(a.run)
items = [json.loads(l) for l in open(a.dev)]
sampler = make_sampler(temp=0.0)


def stage(path, name):
    d = run / f"_sel_{name}"; d.mkdir(exist_ok=True)
    shutil.copy(run / "adapter_config.json", d)
    shutil.copy(path, d / "adapters.safetensors")
    return d


def score(adapter_dir):
    model, tok = load(a.model, adapter_path=str(adapter_dir))
    rec = tok.encode("记录", add_special_tokens=False)[0]
    sc, rs = [], []
    for x in items:
        prompt = tok.apply_chat_template([{"role": "system", "content": S.SYSTEM}, {"role": "user", "content": x["text"]}],
                                         tokenize=False, add_generation_prompt=True, enable_thinking=False)
        ids = mx.array(tok.encode(prompt, add_special_tokens=False))[None]
        sc.append(1 - mx.softmax(model(ids)[0, -1].astype(mx.float32))[rec].item())
        out = generate(model, tok, prompt, max_tokens=80, sampler=sampler).strip().split("\n")[0]
        try:
            p = S.drop_unfaithful(x["text"], S.parse(out))
        except ValueError:
            p = None
        rs.append((S.parse(x["label"]), p))
    gold = [g["intent"] != "记录" for g, _ in rs]
    pos = [s for s, g in zip(sc, gold) if g]; neg = [s for s, g in zip(sc, gold) if not g]
    auc = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg) / (len(pos) * len(neg))
    ok = [(g, p) for g, p in rs if p]
    val = sum(S.valence(g["mood"]) == S.valence(p["mood"]) for g, p in ok) / len(rs)

    def f1(get):
        tp = fp = fn = 0
        for g, p in rs:
            G = get(g); P = get(p) if p else set()
            tp += len(G & P); fp += len(P - G); fn += len(G - P)
        return 2 * tp / max(1, 2 * tp + fp + fn)
    asp = f1(lambda d: set(d["aspects"]))
    men = f1(lambda d: {(t, S._squash(v)) for t, v in d["mentions"]})
    return dict(auc=auc, valence=val, aspects=asp, mentions=men, fmt=len(ok) / len(rs),
                total=(auc + val + asp + men) / 4)


cks = sorted(glob.glob(str(run / "0*_adapters.safetensors")))
results = []
for c in cks:
    r = score(stage(c, "tmp")); r["ckpt"] = pathlib.Path(c).name[:7]
    results.append(r)
    print(f"{r['ckpt']}  总分 {r['total']:.3f}  意图AUC {r['auc']:.3f}  情绪三档 {r['valence']:.0%}  方面 {r['aspects']:.2f}  提及 {r['mentions']:.2f}  格式 {r['fmt']:.0%}", flush=True)
best = max(range(len(results)), key=lambda i: results[i]["total"])
print("最好:", results[best]["ckpt"])
if a.average > 1:
    lo = max(0, best - a.average // 2); sel = cks[lo:lo + a.average]
    ws = [mx.load(c) for c in sel]
    avg = {k: sum(w[k] for w in ws) / len(ws) for k in ws[0]}
    d = run / "avg"; d.mkdir(exist_ok=True)
    shutil.copy(run / "adapter_config.json", d)
    mx.save_safetensors(str(d / "adapters.safetensors"), avg)
    r = score(d)
    print(f"平均 {[pathlib.Path(c).name[:7] for c in sel]}  总分 {r['total']:.3f}  意图AUC {r['auc']:.3f}  情绪三档 {r['valence']:.0%}  方面 {r['aspects']:.2f}  提及 {r['mentions']:.2f}")
