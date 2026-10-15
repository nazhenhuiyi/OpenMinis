#!/usr/bin/env python3
"""Check every data/*.tsv line (text<TAB>label) against GUIDELINE/schema; print stats."""
import collections, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import schema as S

files = sys.argv[1:] or sorted(str(p) for p in (pathlib.Path(__file__).parent / "data").glob("*.tsv"))
rows, bad, seen = [], 0, set()
for f in files:
    for n, line in enumerate(open(f), 1):
        if not line.strip() or line.startswith("#"):
            continue
        try:
            t, lab = line.rstrip("\n").split("\t")
            t = t.replace("\\n", "\n")
            d = S.check(t, lab)
        except ValueError as e:
            bad += 1; print(f"{pathlib.Path(f).name}:{n} {e} | {line[:40]}"); continue
        if t in seen:
            bad += 1; print(f"{pathlib.Path(f).name}:{n} 重复 | {t[:40]}"); continue
        seen.add(t); rows.append(d)
print(f"{len(rows)} ok, {bad} bad")
C = collections.Counter
print("意图", C(d["intent"] for d in rows).most_common())
print("情绪", C(d["mood"] for d in rows).most_common())
print("方面", C(a for d in rows for a in d["aspects"]).most_common())
print("提及", C(t for d in rows for t, _ in d["mentions"]).most_common(), " 无提及", sum(not d["mentions"] for d in rows))
print("高光", sum(d["highlight"] for d in rows))
sys.exit(1 if bad else 0)
