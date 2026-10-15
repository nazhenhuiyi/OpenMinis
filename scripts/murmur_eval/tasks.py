#!/usr/bin/env python3
"""Other things a 0.6B model might do with a 碎碎念: theme, mood, mentions.

Input is a jsonl of {"text", "themes"} (e.g. extracted from the old journal;
keep private data out of the repo). Needs a running llama-server.

    python3 scripts/murmur_eval/tasks.py /tmp/murmur_real/all.jsonl --task theme
"""
import argparse
import collections
import json
import pathlib
import re
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import eval as E  # noqa: E402

THEMES = {
    "日常片刻": "daily-moments", "自然与时节": "nature-seasons", "今日滋味": "daily-taste",
    "心里的话": "inner-voice", "念头与好奇": "ideas-curiosity", "光影与色彩": "light-colors",
}
THEME_SYS = """给手机日记里的一条碎碎念选一个主题，只输出主题名：
- 日常片刻：生活小事、趣事、听歌看剧等文化欣赏
- 自然与时节：天空、天气、季节变化、动植物观察
- 今日滋味：食物、饮料、做饭、餐厅
- 心里的话：感受、情绪、休息、自我整理
- 念头与好奇：想法、观点、灵感、疑问、知识
- 光影与色彩：光线、色彩、构图等视觉审美"""
THEME_SHOTS = [
    ("今天的晚霞是粉紫色的", "自然与时节"),
    ("自己做了酸菜鱼，比外面的好吃", "今日滋味"),
    ("最近什么都提不起劲，想好好睡一觉", "心里的话"),
    ("我觉得城市的本质是让陌生人合作", "念头与好奇"),
    ("在地铁上听到有人在唱歌，挺可爱的", "日常片刻"),
]
THEME_GRAMMAR = "root ::= " + " | ".join(f'"{t}"' for t in THEMES)

MOODS = ["开心", "满足", "平静", "感动", "期待", "好奇", "疲惫", "低落", "焦虑", "烦躁", "难受"]
POS, NEG = {"开心", "满足", "感动", "期待"}, {"疲惫", "低落", "焦虑", "烦躁", "难受"}
MOOD_SYS = "读一条手机日记里的碎碎念，用一个词说出写的人此刻的心情，只能从这些词里选：" + "、".join(MOODS) + \
    "。没有明显情绪就选 平静；只是在讲道理、发表观点、思考问题，也选 平静。"
MOOD_SHOTS = [
    ("今天的晚霞是粉紫色的，好美", "开心"),
    ("又加班到十一点，累死了", "疲惫"),
    ("我觉得城市的本质是让陌生人合作", "平静"),
    ("下周就要答辩了，心里没底", "焦虑"),
    ("妈妈打电话说想我了", "感动"),
]
MOOD_GRAMMAR = "root ::= " + " | ".join(f'"{m}"' for m in MOODS)

MENTION_SYS = """从碎碎念里找出提到的具体事物，每类用顿号分隔，没有就写 无，按这个格式输出一行：
书影音|吃喝|地点|人"""
MENTION_SHOTS = [
    ("在方所看到《海边的卡夫卡》，回家煮了碗面", "《海边的卡夫卡》|面|方所|无"),
    ("和小王去春熙路吃了火锅，听了陈奕迅的歌", "陈奕迅的歌|火锅|春熙路|小王"),
    ("今天天气不错", "无|无|无|无"),
]
MENTION_GRAMMAR = r'''root ::= f "|" f "|" f "|" f
f ::= [^|\n]{1,40}'''

def clean(t):
    t = re.sub(r"::image.*?::(?=\s|$)", "", t, flags=re.S).strip()
    return re.sub(r"\n{2,}", "\n", t)[:300]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("--task", choices=["theme", "mood", "mention"], required=True)
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--gold", help="tsv of index<TAB>-1/0/1 for mood")
    ap.add_argument("--out")
    a = ap.parse_args()

    items = []
    for line in open(a.data):
        x = json.loads(line)
        t = clean(x["text"])
        if t:
            items.append(dict(text=t, themes=[s.strip() for s in x.get("themes", "").split(",") if s.strip()]))

    system, shots, grammar = {
        "theme": (THEME_SYS, THEME_SHOTS, THEME_GRAMMAR),
        "mood": (MOOD_SYS, MOOD_SHOTS, MOOD_GRAMMAR),
        "mention": (MENTION_SYS, MENTION_SHOTS, MENTION_GRAMMAR),
    }[a.task]
    E.ask(a.port, E.build_prompt(system, "预热", shots), grammar)
    times = []
    for x in items:
        out, t, _ = E.ask(a.port, E.build_prompt(system, x["text"], shots), grammar)
        x["out"] = out.strip()
        times.append(t.get("prompt_ms", 0) + t.get("predicted_ms", 0))
    print(f"== {a.task} n={len(items)}  耗时中位 {statistics.median(times):.0f}ms  P95 {sorted(times)[int(len(times) * .95) - 1]:.0f}ms")

    if a.task == "theme":
        hit = [x for x in items if x["themes"] and THEMES.get(x["out"]) in x["themes"]]
        has = [x for x in items if x["themes"]]
        print(f"命中原有主题之一 {len(hit) / len(has):.0%}  ({len(hit)}/{len(has)})")
        conf = collections.Counter((x["themes"][0], THEMES.get(x["out"])) for x in has)
        print("原主题 → 模型（前 12 种组合）：")
        for (g, p), n in conf.most_common(12):
            print(f"  {g:16} → {p:16} {n}")
    if a.task == "mood":
        print("分布：", collections.Counter(x["out"] for x in items).most_common())
        if a.gold:
            gold = {int(i): int(v) for i, v in (l.split("\t") for l in open(a.gold) if l.strip())}
            val = lambda m: 1 if m in POS else -1 if m in NEG else 0
            ok = sum(val(items[i]["out"]) == v for i, v in gold.items())
            print(f"积极/平淡/消极 和人工标注一致 {ok / len(gold):.0%}  ({ok}/{len(gold)})")
            for i, v in gold.items():
                if val(items[i]["out"]) != v:
                    print(f"  [人工 {v:+d}] {items[i]['out']}  ←  {items[i]['text'][:70]}")
    if a.out:
        with open(a.out, "w") as f:
            for i, x in enumerate(items):
                f.write(json.dumps(dict(i=i, **x), ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
