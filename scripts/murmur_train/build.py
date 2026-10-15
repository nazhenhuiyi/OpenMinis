#!/usr/bin/env python3
"""data/*.tsv -> out/{train,valid}.jsonl in mlx-lm chat format.

Real murmurs are longer than what we hand-write and often jump between topics,
so the train split is augmented with composites: 2-3 murmurs from the same
batch joined line by line, labels derived by the GUIDELINE rules (any action
wins, mood = the last part, aspects/mentions merged, highlight = any).
The valid split is originals only.
"""
import argparse, json, pathlib, random, sys
HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE))
import schema as S

ap = argparse.ArgumentParser()
ap.add_argument("--composites", type=int, default=400)
a = ap.parse_args()

rows = []  # (text, label, batch)
for f in sorted((HERE / "data").glob("*.tsv")):
    for line in open(f):
        if line.strip() and not line.startswith("#"):
            t, lab = line.rstrip("\n").split("\t")
            t = t.replace("\\n", "\n")
            S.check(t, lab)
            rows.append((t, lab, f.stem))
rng = random.Random(0)
rng.shuffle(rows)
n_val = max(30, len(rows) // 10)
val, train = rows[:n_val], rows[n_val:]


def fmt(d):
    men = ";".join(f"{t}:{v}" for t, v in d["mentions"]) or "无"
    return "|".join([d["intent"], d["mood"], ",".join(d["aspects"]), men, "是" if d["highlight"] else "否"])


def compose(parts):
    ds = [S.parse(lab) for _, lab, _ in parts]
    acts = [d["intent"] for d in ds if d["intent"] != "记录"]
    aspects = []
    for d in ds:
        for x in d["aspects"]:
            if x not in aspects:
                aspects.append(x)
    seen, mentions = set(), []
    for d in ds:
        for m in d["mentions"]:
            if m[1] not in seen:
                seen.add(m[1]); mentions.append(m)
    text = "\n".join(t for t, _, _ in parts)
    d = dict(intent=acts[0] if acts else "记录", mood=ds[-1]["mood"], aspects=aspects[:2],
             mentions=mentions[:5], highlight=any(d["highlight"] for d in ds))
    lab = fmt(d)
    S.check(text, lab)
    return text, lab


by_batch = {}
for r in train:
    by_batch.setdefault(r[2], []).append(r)
batches = [b for b in by_batch.values() if len(b) >= 3]
comps = []
for _ in range(a.composites):
    b = rng.choice(batches)
    parts = rng.sample(b, rng.choice([2, 2, 3]))
    comps.append((*compose(parts), "composite"))

out = HERE / "out"; out.mkdir(exist_ok=True)
msg = lambda t, lab: {"messages": [{"role": "system", "content": S.SYSTEM},
                                   {"role": "user", "content": t},
                                   {"role": "assistant", "content": lab}]}
train_all = train + comps
rng.shuffle(train_all)
for name, part in (("valid", val), ("train", train_all)):
    with open(out / f"{name}.jsonl", "w") as f:
        for t, lab, _ in part:
            f.write(json.dumps(msg(t, lab), ensure_ascii=False) + "\n")
    print(name, len(part))
