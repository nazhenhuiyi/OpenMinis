"""Label schema for the 碎碎念 tagger (see GUIDELINE.md).

One line:  意图|情绪|方面|提及|高光
"""
import re

SYSTEM = "给碎碎念打标签：意图|情绪|方面|提及|高光"

INTENTS = ["记录", "待办", "查询"]
MOODS = ["开心", "满足", "感动", "期待", "平静", "好奇", "疲惫", "低落", "焦虑", "烦躁", "生气", "难受"]
POSITIVE = {"开心", "满足", "感动", "期待"}
NEGATIVE = {"疲惫", "低落", "焦虑", "烦躁", "生气", "难受"}
ASPECTS = ["吃喝", "身体", "运动", "工作", "学习阅读", "影音娱乐", "家人", "朋友社交", "伴侣", "孩子",
           "宠物", "自然天气", "出行", "居家", "钱", "创作爱好", "想法"]
MENTION_TYPES = ["吃喝", "书影", "音乐", "人", "地点", "症状", "药", "运动", "数量", "花销", "植物", "动物"]
HIGHLIGHT = ["是", "否"]


def valence(mood: str) -> int:
    return 1 if mood in POSITIVE else -1 if mood in NEGATIVE else 0


def parse(line: str):
    """Returns a dict, or raises ValueError with the reason."""
    p = line.strip().split("|")
    if len(p) != 5:
        raise ValueError(f"需要 5 段，得到 {len(p)} 段")
    intent, mood, aspects, mentions, hl = p
    if intent not in INTENTS:
        raise ValueError(f"意图 {intent!r}")
    if mood not in MOODS:
        raise ValueError(f"情绪 {mood!r}")
    asp = aspects.split(",")
    if not 1 <= len(asp) <= 2 or any(a not in ASPECTS for a in asp) or len(set(asp)) != len(asp):
        raise ValueError(f"方面 {aspects!r}")
    men = []
    if mentions != "无":
        for m in mentions.split(";"):
            t, _, v = m.partition(":")
            if t not in MENTION_TYPES or not v:
                raise ValueError(f"提及 {m!r}")
            men.append((t, v))
        if len(men) > 5:
            raise ValueError("提及超过 5 个")
    if hl not in HIGHLIGHT:
        raise ValueError(f"高光 {hl!r}")
    return dict(intent=intent, mood=mood, aspects=asp, mentions=men, highlight=hl == "是")


def _squash(s: str) -> str:
    return re.sub(r"\s+", "", s)


def faithful(text: str, value: str) -> bool:
    """Mention value appears in the text (whitespace-insensitive: "34分钟" ~ "34 分钟")."""
    return value in text or _squash(value) in _squash(text)


def check(text: str, line: str):
    """parse() plus: every mention must appear verbatim in the text."""
    d = parse(line)
    for t, v in d["mentions"]:
        if v not in text:
            raise ValueError(f"提及不在原文里 {t}:{v}")
    return d


def drop_unfaithful(text: str, d: dict) -> dict:
    """What the app does at runtime: silently drop mentions not in the text."""
    return dict(d, mentions=[(t, v) for t, v in d["mentions"] if faithful(text, v)])


# llama.cpp GBNF for inference; mention values are free text, faithfulness is
# enforced afterwards by drop_unfaithful().
def gbnf() -> str:
    alt = lambda xs: " | ".join(f'"{x}"' for x in xs)
    return "\n".join([
        'root ::= intent "|" mood "|" aspects "|" mentions "|" hl',
        f"intent ::= {alt(INTENTS)}",
        f"mood ::= {alt(MOODS)}",
        f"aspect ::= {alt(ASPECTS)}",
        'aspects ::= aspect ("," aspect)?',
        f"mtype ::= {alt(MENTION_TYPES)}",
        r'mval ::= [^|;:\n]{1,24}',
        'mention ::= mtype ":" mval',
        'mentions ::= "无" | mention (";" mention){0,4}',
        f"hl ::= {alt(HIGHLIGHT)}",
    ])


def clean_text(t: str) -> str:
    t = re.sub(r"::image.*?::(?=\s|$)", "", t, flags=re.S).strip()
    return re.sub(r"\n{2,}", "\n", t)[:300]
