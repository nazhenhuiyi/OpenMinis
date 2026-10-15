#!/usr/bin/env python3
"""Offline eval: can a small on-device LLM tell which 碎碎念 need action?

Talks to a running llama-server (see README.md). Output format the model is
forced into (GBNF grammar):  否  |  是|待办|时间|事情  |  是|查询|时间|问题

    python3 scripts/murmur_eval/eval.py --port 8089 --prompt fewshot
"""
import argparse
import json
import pathlib
import statistics
import time
import urllib.request

HERE = pathlib.Path(__file__).parent

RULES = """你帮用户整理手机日记里随手写的碎碎念。判断这一句是不是用户接下来要做的事，或者需要帮忙查的问题。
- 只是记录、感受、抱怨、回忆、愿望，或者已经做完的事：输出 否
- 用户自己接下来要做的事：输出 是|待办|时间|事情
- 需要帮忙查资料、计算、推荐、回答的问题：输出 是|查询|时间|问题
时间照抄原话里的说法，没有就写 无。事情要简短。拿不准时输出 否。"""

EXAMPLES = """
例子：
今天加班好累 → 否
烦，下周还要出差 → 否
刚把水电费交了 → 否
好想吃火锅 → 否
记得明天中午给小陈转钱 → 是|待办|明天中午|给小陈转钱
洗发水没了，得买 → 是|待办|无|买洗发水
高铁上能带充电宝吗 → 是|查询|无|高铁能否带充电宝"""

GRAMMAR = r'''
root  ::= "否" | "是|" kind "|" field "|" field
kind  ::= "待办" | "查询"
field ::= [^|\n]{1,24}
'''

# Variant "cat": the first word is the category itself, no 是/否 — "是" also
# means "is" and a 0.6B model leans on it for almost everything.
CAT_RULES = """你帮用户整理手机日记里随手写的碎碎念，给每一句分类：
- 记录：记录、感受、抱怨、回忆、愿望、已经发生或做完的事。大部分碎碎念都是记录
- 待办：用户自己接下来要去做的事。输出 待办|时间|事情
- 查询：需要帮忙查资料、计算、推荐、回答的问题。输出 查询|时间|问题
时间照抄原话里的说法，没有就写 无。事情要简短。拿不准时输出 记录。"""

CAT_SHOTS = [
    ("今天加班好累", "记录"),
    ("记得明天中午给小陈转钱", "待办|明天中午|给小陈转钱"),
    ("烦，下周还要出差", "记录"),
    ("高铁上能带充电宝吗", "查询|无|高铁能否带充电宝"),
    ("刚把水电费交了", "记录"),
    ("洗发水没了，得买", "待办|无|买洗发水"),
    ("好想吃火锅", "记录"),
    ("公园的银杏黄了", "记录"),
]

# Balanced: questions and "提醒我…" were the misses with CAT_SHOTS.
CAT_SHOTS2 = [
    ("今天加班好累", "记录"),
    ("记得明天中午给小陈转钱", "待办|明天中午|给小陈转钱"),
    ("高铁上能带充电宝吗", "查询|无|高铁能否带充电宝"),
    ("刚把水电费交了", "记录"),
    ("洗发水没了", "待办|无|买洗发水"),
    ("胃有点胀怎么办", "查询|无|胃胀怎么缓解"),
    ("好想吃火锅", "记录"),
    ("周三下午四点提醒我开会", "待办|周三下午四点|开会"),
    ("朋友送了我一盆多肉", "记录"),
    ("附近有没有修自行车的", "查询|无|附近修自行车的地方"),
    ("烦，下周还要出差", "记录"),
]

CAT_GRAMMAR = r'''
root  ::= "记录" | kind "|" field "|" field
kind  ::= "待办" | "查询"
field ::= [^|\n]{1,24}
'''


def build_prompt(system: str, text: str, shots=()) -> str:
    # Qwen3 chat template with an empty think block = thinking off.
    turns = "".join(
        f"<|im_start|>user\n{u}<|im_end|>\n<|im_start|>assistant\n{a}<|im_end|>\n" for u, a in shots
    )
    return (
        f"<|im_start|>system\n{system}<|im_end|>\n{turns}"
        f"<|im_start|>user\n{text}<|im_end|>\n"
        "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    )


# Variant 3: real murmurs are long reflections with one actionable clause
# buried inside — ask "does ANY sentence…", and show mixed examples.
CAT_RULES3 = """用户在手机日记里随手写碎碎念，大多是感受和想法，有时会夹着一句接下来要做的事，或者一个想问的问题。
看这段话里有没有任何一句是：
- 用户接下来要去做的具体的事（明天、今晚、得、记得、准备……）：输出 待办|时间|事情
- 用户想知道答案的问题（什么、为什么、怎么、哪里……）：输出 查询|时间|问题
都没有，只是记录已经发生的事、感受、观点、愿望：输出 记录
时间照抄原话，没有就写 无。事情要简短。"""

CAT_SHOTS3 = [
    ("今天加班好累", "记录"),
    ("刚把水电费交了，松了口气", "记录"),
    ("最近读了好多余华，感觉人生就是活着本身，后天要去图书馆还书了", "待办|后天|去图书馆还书"),
    ("好想吃火锅", "记录"),
    ("这是什么鸟呀，尾巴好长", "查询|无|这是什么鸟"),
    ("今天做的红烧肉有点咸，下次少放点酱油", "记录"),
    ("胃有点胀，吃多了，为什么一吃面就胀气呢", "查询|无|为什么吃面会胀气"),
    ("工作好难，大家都在焦虑\n周三下午四点要开会", "待办|周三下午四点|开会"),
    ("人生就是一场旅行，风景比终点重要", "记录"),
    ("洗发水没了", "待办|无|买洗发水"),
    ("新买的杯子很好看，提高了幸福感", "记录"),
]

# Variant 4: the 1.7B false positives on real murmurs were rhetorical /
# reflective questions, wishes, things already done, opinions rewritten as
# "为什么…". Spell those out; examples are written fresh, not copied.
CAT_RULES4 = """用户在手机日记里随手写碎碎念。绝大多数是记录：发生的事、感受、观点、感慨、思考、愿望。偶尔会夹着一句需要处理的事。

只有下面两种情况才不是记录：
1. 待办：用户自己接下来要去做的一件具体的事，通常有"明天、今晚、待会儿、得、记得、要去"这类字眼，或者东西用完了要补。输出 待办|时间|事情
2. 查询：用户真的想知道答案的具体问题，比如认东西（这是什么花）、问做法（怎么去除水垢）、问事实（这两种药能一起吃吗）。输出 查询|时间|问题

下面这些都是 记录：
- 感慨或思考人生、社会、AI 的问题，比如"人为什么要工作呢"、"如何活得更自在？"
- 愿望和期待："希望……"、"想去……"、"有机会试试"
- 已经做完的事，哪怕提到了动作："做了……"、"买了……"、"完成了"
- 观点和评价，不要把它改写成问题
时间照抄原话，没有就写 无。事情要简短。"""

CAT_SHOTS4 = [
    ("今天加班好累", "记录"),
    ("最近读了好多余华，感觉人生就是活着本身，后天要去图书馆还书了", "待办|后天|去图书馆还书"),
    ("人活着到底是为了什么呢？也许只是为了看看风景吧", "记录"),
    ("这是什么鸟呀，尾巴好长", "查询|无|这是什么鸟"),
    ("希望明年能去一趟冰岛", "记录"),
    ("胃有点胀，为什么一吃面就胀气呢", "查询|无|为什么吃面会胀气"),
    ("烤了一个戚风蛋糕，有点塌", "记录"),
    ("工作好难，大家都在焦虑\n周三下午四点要开会", "待办|周三下午四点|开会"),
    ("短视频其实都是垃圾，浪费时间", "记录"),
    ("洗发水没了", "待办|无|买洗发水"),
    ("AI 会不会有一天取代所有人呢", "记录"),
    ("新买的杯子很好看，提高了幸福感", "记录"),
]

VARIANTS = {
    # name: (system, chat-turn shots, grammar)
    "rules": (lambda: (RULES, (), GRAMMAR)),
    "fewshot": (lambda: (RULES + EXAMPLES, (), GRAMMAR)),
    "cat": (lambda: (CAT_RULES + "\n\n例子：\n" + "\n".join(f"{u} → {a}" for u, a in CAT_SHOTS), (), CAT_GRAMMAR)),
    "catchat": (lambda: (CAT_RULES, CAT_SHOTS, CAT_GRAMMAR)),
    "catchat4": (lambda: (CAT_RULES4, CAT_SHOTS4, CAT_GRAMMAR)),
    "catchat3": (lambda: (CAT_RULES3, CAT_SHOTS3, CAT_GRAMMAR)),
    "catchat2": (lambda: (CAT_RULES.replace("大部分碎碎念都是记录\n", "\n"), CAT_SHOTS2, CAT_GRAMMAR)),
}


def load(path: pathlib.Path):
    rows = []
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        c = (line.split("\t") + [""] * 6)[:6]
        rows.append(dict(text=c[0].replace("\\n", "\n"), y=c[1], kind=c[2], when=c[3], what=c[4], hard=c[5] == "1"))
    return rows


def ask(port: int, prompt: str, grammar):
    body = {"prompt": prompt, "n_predict": 48, "temperature": 0, "cache_prompt": True}
    if grammar:
        body["grammar"] = grammar
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/completion",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req) as r:
        out = json.load(r)
    wall = (time.perf_counter() - t0) * 1000
    return out["content"].strip(), out.get("timings", {}), wall


def parse(s: str):
    s = s.split("\n")[0].strip()
    if s in ("否", "记录"):
        return dict(y="否", ok=True)
    if s.startswith(("待办|", "查询|")):
        s = "是|" + s
    p = s.split("|")
    if len(p) == 4 and p[0] == "是" and p[1] in ("待办", "查询"):
        return dict(y="是", kind=p[1], when=p[2].strip(), what=p[3].strip(), ok=True)
    return dict(y="是" if s.startswith("是") else "否", ok=False)


def when_match(gold: str, pred: str) -> bool:
    if gold == "无" or pred == "无":
        return gold == pred
    return gold in pred or pred in gold


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--prompt", choices=list(VARIANTS), default="fewshot")
    ap.add_argument("--no-grammar", action="store_true")
    ap.add_argument("--data", default=str(HERE / "dataset.tsv"))
    ap.add_argument("--out", default=None, help="write per-item jsonl here")
    a = ap.parse_args()

    system, shots, grammar = VARIANTS[a.prompt]()
    grammar = None if a.no_grammar else grammar
    rows = load(pathlib.Path(a.data))
    ask(a.port, build_prompt(system, "预热", shots), grammar)  # warm the prompt cache

    res = []
    for r in rows:
        out, t, wall = ask(a.port, build_prompt(system, r["text"], shots), grammar)
        p = parse(out)
        res.append(dict(r, out=out, p=p, t=t, wall=wall))

    def prf(items):
        tp = sum(1 for x in items if x["y"] == "是" and x["p"]["y"] == "是")
        fp = sum(1 for x in items if x["y"] == "否" and x["p"]["y"] == "是")
        fn = sum(1 for x in items if x["y"] == "是" and x["p"]["y"] == "否")
        tn = len(items) - tp - fp - fn
        P = tp / (tp + fp) if tp + fp else 0
        R = tp / (tp + fn) if tp + fn else 0
        F = 2 * P * R / (P + R) if P + R else 0
        return tp, fp, fn, tn, P, R, F

    print(f"== prompt={a.prompt} grammar={not a.no_grammar} n={len(res)}")
    for name, items in (("全部", res), ("去掉有争议", [x for x in res if not x["hard"]])):
        tp, fp, fn, tn, P, R, F = prf(items)
        print(f"{name:6} 精确率 {P:.0%}  召回率 {R:.0%}  F1 {F:.2f}   (TP{tp} FP{fp} FN{fn} TN{tn})")

    tps = [x for x in res if x["y"] == "是" and x["p"]["y"] == "是" and x["p"]["ok"]]
    if tps:
        kind = sum(x["kind"] == x["p"]["kind"] for x in tps) / len(tps)
        when = sum(when_match(x["when"], x["p"]["when"]) for x in tps) / len(tps)
        print(f"答对'是'的里面：类型正确 {kind:.0%}  时间正确 {when:.0%}")
    print(f"格式合法 {sum(x['p']['ok'] for x in res) / len(res):.0%}")

    def ms(key, items):
        v = [x["t"].get(key, 0) for x in items]
        return statistics.median(v), sorted(v)[int(len(v) * 0.95) - 1]

    no = [x for x in res if x["p"]["y"] == "否"]
    yes = [x for x in res if x["p"]["y"] == "是"]
    pp = [x["t"].get("prompt_n", 0) for x in res]
    print(f"每条新读入 token 中位数 {statistics.median(pp):.0f}（系统提示已缓存）")
    for label, items in (("判'否'", no), ("判'是'", yes)):
        if items:
            tot = [x["t"].get("prompt_ms", 0) + x["t"].get("predicted_ms", 0) for x in items]
            print(f"{label} 模型耗时 中位 {statistics.median(tot):.0f}ms  P95 {sorted(tot)[max(0, int(len(tot) * 0.95) - 1)]:.0f}ms"
                  f"  (生成 {statistics.median([x['t'].get('predicted_n', 0) for x in items]):.0f} token)")

    print("\n-- 判错的：")
    for x in res:
        wrong = x["y"] != x["p"]["y"]
        if wrong:
            tag = "误报" if x["y"] == "否" else "漏判"
            print(f"  [{tag}{'·争议' if x['hard'] else ''}] {x['text']}  →  {x['out']}")
    print("\n-- 判对'是'的输出（看字段质量）：")
    for x in tps:
        flag = "" if x["kind"] == x["p"]["kind"] and when_match(x["when"], x["p"]["when"]) else "  ✗"
        print(f"  {x['text']}  →  {x['out']}{flag}")

    if a.out:
        with open(a.out, "w") as f:
            for x in res:
                f.write(json.dumps({k: x[k] for k in ("text", "y", "hard", "out", "wall")}, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
