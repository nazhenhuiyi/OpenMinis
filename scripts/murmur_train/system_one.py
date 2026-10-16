#!/usr/bin/env python3
"""Typed decisions with calibrated probabilities, Jev-style ("System One"), from the
on-device 碎碎念 model — compared with how the app reads it today.

A  today's tag line, generated with the grammar; the probability of each field is
   read from the model's next-token distribution at that field (no extra cost on
   the phone: the logits are there while decoding).
B  questions instead of a line: the murmur is read once (prompt cache), then each
   typed question (Choice / yes-no) costs only its own few tokens; the answer is
   the distribution over the options' first tokens.

Temperature scaling is fitted per field on --dev and applied to the test set.

    llama-server -m murmur-0.6b-soup5-Q4_0.gguf --port 8091 -ngl 0 -t 2 --cache-ram 0
    python3 scripts/murmur_train/system_one.py ~/models/murmur_real_private/test.jsonl \
        --dev scripts/murmur_train/dev/dev_long.jsonl --port 8091 [--base-port 8092]
"""
import argparse, json, math, pathlib, sys, time, urllib.request
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import schema as S

TIERS = ["明亮", "平常", "低沉"]
QSYS = "读用户的碎碎念，回答问题，只输出一个选项。"
QUESTIONS = {
    "action": ("这条碎碎念里，有没有需要之后去做或去查的事？", ["是", "否"]),
    "tier": ("整体心情是？", TIERS),
    "food": ("和吃喝有关吗？", ["是", "否"]),
    "hl": ("这是值得以后回味的高光时刻吗？", ["是", "否"]),
}


def chat(system, user):
    return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
            "<|im_start|>assistant\n<think>\n\n</think>\n\n")


class Server:
    def __init__(self, port):
        self.port, self.first = port, {}

    def call(self, path, body):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        return json.load(urllib.request.urlopen(req))

    def first_token(self, s):
        if s not in self.first:
            self.first[s] = self.call("tokenize", {"content": s})["tokens"][0]
        return self.first[s]

    def dist(self, prompt, options):
        """Log-probabilities of each option's first token next (raw, before any sampling); and ms spent."""
        r = self.call("completion", {"prompt": prompt, "n_predict": 1, "temperature": 0, "cache_prompt": True, "n_probs": 300})
        top = {t["id"]: t["logprob"] for t in r["completion_probabilities"][0]["top_logprobs"]}
        floor = min(top.values()) - 1.0
        return [top.get(self.first_token(o), floor) for o in options], r["timings"]["prompt_ms"] + r["timings"]["predicted_ms"]

    def line(self, prompt, grammar):
        r = self.call("completion", {"prompt": prompt, "n_predict": 80, "temperature": 0, "cache_prompt": True, "grammar": grammar})
        return r["content"].strip().split("\n")[0], r["timings"]["prompt_ms"] + r["timings"]["predicted_ms"]


def softmax(lp, T=1.0):
    m = max(x / T for x in lp)
    e = [math.exp(x / T - m) for x in lp]
    s = sum(e)
    return [x / s for x in e]


def gold(label):
    g = S.parse(label)
    return {
        "action": 0 if g["intent"] != "记录" else 1,          # index into ["是","否"]
        "tier": {1: 0, 0: 1, -1: 2}[S.valence(g["mood"])],
        "food": 0 if "吃喝" in g["aspects"] else 1,
        "hl": 0 if g["highlight"] == "是" else 1,
    }


def method_a(srv, text, grammar):
    """Field distributions along today's line. Returns {field: logprobs over that field's options}, ms."""
    p0 = chat(S.SYSTEM, text)
    out, ms = srv.line(p0, grammar)
    f = out.split("|")
    lp_int, _ = srv.dist(p0, S.INTENTS)
    lp_mood, _ = srv.dist(p0 + f[0] + "|", S.MOODS)
    lp_asp, _ = srv.dist(p0 + "|".join(f[:2]) + "|", S.ASPECTS)
    lp_hl, _ = srv.dist(p0 + "|".join(f[:4]) + "|", S.HIGHLIGHT)
    pi, pm, pa = softmax(lp_int), softmax(lp_mood), softmax(lp_asp)
    # Collapse to the same binary / three-way questions as B, as log-probabilities.
    act = 1 - pi[0]
    tier = [sum(p for p, m in zip(pm, S.MOODS) if S.valence(m) == v) for v in (1, 0, -1)]
    food = pa[S.ASPECTS.index("吃喝")]
    # 吃喝 as the second aspect counts too, when the line has it.
    if "吃喝" in f[2].split(",")[1:]:
        food = max(food, 0.5 + food / 2)
    lg = lambda ps: [math.log(max(p, 1e-9)) for p in ps]
    return {"action": lg([act, 1 - act]), "tier": lg(tier), "food": lg([food, 1 - food]), "hl": lp_hl}, ms


def method_b(srv, text):
    out, total = {}, 0.0
    for k, (q, opts) in QUESTIONS.items():
        lp, ms = srv.dist(chat(QSYS, f"碎碎念：{text}\n\n问题：{q}\n选项：{' / '.join(opts)}"), opts)
        out[k] = lp
        total += ms
    return out, total


def fit_T(rows, field):
    best = (1e9, 1.0)
    for i in range(1, 81):
        T = i / 10
        nll = -sum(math.log(max(softmax(r["lp"][field], T)[r["g"][field]], 1e-12)) for r in rows)
        best = min(best, (nll, T))
    return best[1]


def metrics(rows, field, T):
    n = len(rows)
    acc = brier = 0.0
    bins = [[0, 0.0, 0] for _ in range(10)]
    sel = [0, 0]
    for r in rows:
        p = softmax(r["lp"][field], T)
        k = max(range(len(p)), key=p.__getitem__)
        ok = k == r["g"][field]
        acc += ok
        brier += sum((p[i] - (i == r["g"][field])) ** 2 for i in range(len(p)))
        b = bins[min(int(p[k] * 10), 9)]
        b[0] += 1; b[1] += p[k]; b[2] += ok
        if p[k] >= 0.8:
            sel[0] += 1; sel[1] += ok
    ece = sum(abs(b[1] / b[0] - b[2] / b[0]) * b[0] / n for b in bins if b[0])
    return dict(acc=acc / n, ece=ece, brier=brier / n, cover80=sel[0] / n, acc80=(sel[1] / sel[0] if sel[0] else float("nan")))


def recall_at(rows, budget=0.20):
    """Action: recall when the top `budget` share by P(action) goes to the cloud."""
    ranked = sorted(rows, key=lambda r: -softmax(r["lp"]["action"])[0])
    k = max(1, round(len(rows) * budget))
    pos = sum(1 for r in rows if r["g"]["action"] == 0)
    hit = sum(1 for r in ranked[:k] if r["g"]["action"] == 0)
    return hit / pos if pos else float("nan")


def run(srv, items, method, grammar):
    rows, ms = [], []
    for x in items:
        lp, t = method_a(srv, x["text"], grammar) if method == "A" else method_b(srv, x["text"])
        rows.append({"lp": lp, "g": gold(x["label"])})
        ms.append(t)
    return rows, sorted(ms)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("test"); ap.add_argument("--dev", required=True)
    ap.add_argument("--port", type=int, default=8091); ap.add_argument("--base-port", type=int)
    ap.add_argument("--out")
    a = ap.parse_args()
    test = [json.loads(l) for l in open(a.test)]
    dev = [json.loads(l) for l in open(a.dev)]
    grammar = S.gbnf()
    runs = [("A 现在的一行（读各字段概率）", Server(a.port), "A"), ("B 判断题（微调模型）", Server(a.port), "B")]
    if a.base_port:
        runs.append(("B 判断题（原版 Qwen3-0.6B）", Server(a.base_port), "B"))
    report = {}
    for name, srv, m in runs:
        t0 = time.time()
        drows, _ = run(srv, dev, m, grammar)
        trows, ms = run(srv, test, m, grammar)
        print(f"\n== {name}   每条 中位 {ms[len(ms)//2]:.0f} ms  P95 {ms[int(len(ms)*.95)]:.0f} ms（Mac CPU 2 线程）  共 {time.time()-t0:.0f}s")
        print(f"   要处理的事：云端看最可能的 20% 时召回 {recall_at(trows):.0%}")
        report[name] = {}
        for f in QUESTIONS:
            T = fit_T(drows, f)
            raw, cal = metrics(trows, f, 1.0), metrics(trows, f, T)
            report[name][f] = dict(T=T, raw=raw, cal=cal)
            print(f"   {f:6s} 准确 {raw['acc']:.0%}  ECE {raw['ece']:.3f}→{cal['ece']:.3f} (T={T})  "
                  f"Brier {raw['brier']:.3f}→{cal['brier']:.3f}  把握≥0.8：覆盖 {cal['cover80']:.0%} 准确 {cal['acc80']:.0%}")
    if a.out:
        json.dump(report, open(a.out, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
