#!/usr/bin/env python3
"""Score a (fine-tuned) model on a jsonl of {"text","label"}; per-field metrics.

    ~/venvs/mlx/bin/python scripts/murmur_train/eval_ft.py /tmp/murmur_real/test.jsonl \
        --model ~/models/Qwen3-0.6B-hf --adapter scripts/murmur_train/out/adapters
"""
import argparse, collections, json, pathlib, sys, time
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import schema as S

ap = argparse.ArgumentParser()
ap.add_argument("data"); ap.add_argument("--model"); ap.add_argument("--adapter")
ap.add_argument("--server", type=int, help="llama-server port instead of MLX (uses the GBNF grammar)")
ap.add_argument("--out"); ap.add_argument("--show", type=int, default=0)
a = ap.parse_args()
items = [json.loads(l) for l in open(a.data)]
if a.server:
    import urllib.request
    grammar = S.gbnf()

    def run(text):
        prompt = (f"<|im_start|>system\n{S.SYSTEM}<|im_end|>\n<|im_start|>user\n{text}<|im_end|>\n"
                  "<|im_start|>assistant\n<think>\n\n</think>\n\n")
        body = {"prompt": prompt, "n_predict": 80, "temperature": 0, "cache_prompt": True, "grammar": grammar}
        r = json.load(urllib.request.urlopen(urllib.request.Request(
            f"http://127.0.0.1:{a.server}/completion", data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})))
        t = r["timings"]; lat.append(t["prompt_ms"] + t["predicted_ms"])
        return r["content"]
else:
    from mlx_lm import load, generate
    from mlx_lm.sample_utils import make_sampler
    model, tok = load(a.model, adapter_path=a.adapter)
    sampler = make_sampler(temp=0.0)

    def run(text):
        prompt = tok.apply_chat_template([{"role": "system", "content": S.SYSTEM}, {"role": "user", "content": text}],
                                         tokenize=False, add_generation_prompt=True, enable_thinking=False)
        return generate(model, tok, prompt, max_tokens=80, sampler=sampler)
lat = []
res, t0 = [], time.time()
for x in items:
    out = run(x["text"]).strip().split("\n")[0]
    try:
        p = S.drop_unfaithful(x["text"], S.parse(out)); ok = True
    except ValueError:
        p, ok = None, False
    unfaithful = 0
    if ok:
        unfaithful = len(S.parse(out)["mentions"]) - len(p["mentions"])
    res.append(dict(x, out=out, ok=ok, p=p, g=S.parse(x["label"]), unf=unfaithful))
dt = (time.time() - t0) / len(items)
okr = [r for r in res if r["ok"]]
print(f"== {a.data}  n={len(res)}  格式合法 {len(okr)/len(res):.0%}  平均 {dt*1000:.0f}ms/条（Mac）")
if lat:
    L = sorted(lat); print(f"llama.cpp 模型耗时 中位 {L[len(L)//2]:.0f}ms  P95 {L[int(len(L)*.95)]:.0f}ms")

def pr(tp, fp, fn):
    P = tp / (tp + fp) if tp + fp else 0; R = tp / (tp + fn) if tp + fn else 0
    return P, R, (2 * P * R / (P + R) if P + R else 0)

# intent: binary actionable
act = lambda d: d is not None and d["intent"] != "记录"
tp = sum(act(r["g"]) and act(r["p"]) for r in res); fp = sum(not act(r["g"]) and act(r["p"]) for r in res)
fn = sum(act(r["g"]) and not act(r["p"]) for r in res)
P, R, F = pr(tp, fp, fn)
print(f"意图  要办召回 {R:.0%}  精确 {P:.0%}  送云端 {sum(act(r['p']) for r in res)}/{len(res)}  (漏 {fn})  三类全对 {sum(r['ok'] and r['p']['intent']==r['g']['intent'] for r in res)/len(res):.0%}")
# mood
m_exact = sum(r["ok"] and r["p"]["mood"] == r["g"]["mood"] for r in res) / len(res)
m_val = sum(r["ok"] and S.valence(r["p"]["mood"]) == S.valence(r["g"]["mood"]) for r in res) / len(res)
neg = [r for r in res if S.valence(r["g"]["mood"]) < 0]
neg_r = sum(r["ok"] and S.valence(r["p"]["mood"]) < 0 for r in neg) / max(1, len(neg))
flip = sum(r["ok"] and S.valence(r["p"]["mood"]) * S.valence(r["g"]["mood"]) < 0 for r in res) / len(res)
print(f"情绪  具体词 {m_exact:.0%}  三档 {m_val:.0%}  正负颠倒 {flip:.0%}  消极召回 {neg_r:.0%} ({len(neg)} 条)")
# aspects micro-F1
tp = fp = fn = 0
for r in res:
    g = set(r["g"]["aspects"]); p = set(r["p"]["aspects"]) if r["ok"] else set()
    tp += len(g & p); fp += len(p - g); fn += len(g - p)
print("方面  P %.0f%% R %.0f%% F1 %.2f" % tuple(v * (100 if i < 2 else 1) for i, v in enumerate(pr(tp, fp, fn))))
# mentions
tp = fp = fn = 0; vtp = 0
for r in res:
    norm = lambda ms: {(t, S._squash(v)) for t, v in ms}
    g = norm(r["g"]["mentions"]); p = norm(r["p"]["mentions"]) if r["ok"] else set()
    tp += len(g & p); fp += len(p - g); fn += len(g - p)
    vtp += len({v for _, v in g} & {v for _, v in p})
P, R, F = pr(tp, fp, fn)
print(f"提及  P {P:.0%} R {R:.0%} F1 {F:.2f}  （只看原文不看类型的命中 {vtp}）  编造被丢弃 {sum(r['unf'] for r in res)}")
# highlight
tp = sum(r["g"]["highlight"] and r["ok"] and r["p"]["highlight"] for r in res)
fp = sum((not r["g"]["highlight"]) and r["ok"] and r["p"]["highlight"] for r in res)
fn = sum(r["g"]["highlight"] and not (r["ok"] and r["p"]["highlight"]) for r in res)
P, R, F = pr(tp, fp, fn)
print(f"高光  P {P:.0%} R {R:.0%}  (标了 {tp+fp}, 实际 {tp+fn})")
if a.show:
    print("\n-- 意图错的：")
    for r in res:
        if act(r["g"]) != act(r["p"]):
            print(f"  [{'漏' if act(r['g']) else '误'}] {r['text'][:60]!r} → {r['out']}")
if a.out:
    with open(a.out, "w") as f:
        for r in res:
            f.write(json.dumps({k: r[k] for k in ("text", "label", "out", "ok")}, ensure_ascii=False) + "\n")
