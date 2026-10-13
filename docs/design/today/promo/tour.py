"""合成「今日」功能巡礼片的声音：背景音乐 + 界面音效。

用法：python tour.py <cues.json> <out.wav>（./build.sh tour 会调用）
乐器和混音在 ../../companion/promo/synth.py（和 companion 宣传片共用），这里只写配乐和音效。
音效时间点来自 render.cjs 渲染时记录的 cues。
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "companion", "promo"))
from music import common_sfx, pop_bed  # noqa: E402
from synth import Mix, bell, blip, hz, kalimba, low, noise_burst, pop, rng, syllable  # noqa: E402

data = json.load(open(sys.argv[1]))
DUR = data["duration"]
CUES = data["cues"]
mix = Mix(DUR)
add = mix.add
first = lambda typ: next(c["t"] for c in CUES if c["type"] == typ)

# ── 背景音乐（music.py，和其它片子共用）：打字和读回复的时候，旋律让一让 ──

TYPING = (first("type") - 0.2, first("send") + 0.3)
CHAT = (first("ask") - 0.2, first("file"))
pop_bed(add, DUR, first("end"), lambda t: 0.09 if TYPING[0] <= t < TYPING[1] or CHAT[0] <= t < CHAT[1] else 0.17)

# ── 界面音效 ───────────────────────────────────────────────

STEP_NOTES = [76, 79, 83, 86, 88]  # 每记一组，音往上走一级


def swoosh(d, lo, hi, gain, t, rising=True):
    s = noise_burst(d, lo, hi)
    add(s if rising else s[::-1], t, gain)


for c in CUES:
    t, typ = c["t"], c["type"]
    if common_sfx(add, c):
        continue
    elif typ == "open":
        swoosh(0.7, 400, 3000, 0.05, t - 0.1)
    elif typ == "weather":
        for i, m in enumerate([84, 88, 91]):
            add(bell(hz(m), 1.2), t + i * 0.08, 0.07, pan=0.25)
    elif typ == "sheet":
        swoosh(0.3, 600, 4000, 0.05, t, rising=not c.get("close"))
    elif typ == "step":
        add(kalimba(hz(STEP_NOTES[min(c.get("i", 0), 4)] + 12), 0.6), t + 0.02, 0.16)
    elif typ == "complete":
        for i, m in enumerate([84, 88, 91, 96]):
            add(bell(hz(m), 1.3), t + 0.03 + i * 0.05, 0.1, pan=(i - 1.5) * 0.2)
    elif typ == "celebrate":
        # 团子庆祝：一串往上走的铃声 + 一声开心的「呀～咿」+ 落地的啵嘤
        for i, m in enumerate([79, 84, 88, 91, 96, 100]):
            add(bell(hz(m), 1.4), t + 0.08 + i * 0.05, 0.08, pan=(i - 2.5) * 0.15)
        add(syllable(700, 0.13, "a"), t + 0.1, 0.2)
        add(syllable(930, 0.2, "i", consonant=False), t + 0.26, 0.2)
        add(noise_burst(0.35, 3000, 9000), t + 0.05, 0.03)
    elif typ == "photo":
        # 相机快门：两下短促的咔嚓 + 一点闪光的亮音
        add(noise_burst(0.012, 1500, 7000, "decay"), t, 0.35)
        add(noise_burst(0.02, 900, 5000, "decay"), t + 0.07, 0.28)
        add(bell(hz(96), 1.0), t + 0.12, 0.06)
    elif typ == "type":
        add(noise_burst(0.014, 1800, 6000, "decay"), t, 0.12 + 0.04 * rng.random(), pan=rng.uniform(-0.2, 0.2))
        add(low(noise_burst(0.02, 200, 900, "decay"), 800), t, 0.08)
    elif typ == "send":
        add(blip(600, 1300, 0.12), t, 0.16)
        swoosh(0.25, 1000, 6000, 0.04, t)
    elif typ == "notify":
        add(bell(hz(91), 1.4), t, 0.16)
        add(bell(hz(86), 1.6), t + 0.16, 0.14)
    elif typ == "banner":
        add(kalimba(hz(79), 0.5), t, 0.08)
    elif typ == "swipe":
        swoosh(0.28, 700, 5000, 0.05, t)
    elif typ == "ask":
        add(pop(), t, 0.25)
        add(blip(800, 1050, 0.06), t + 0.04, 0.1)
    elif typ == "stream":
        # 回复一行行写出来：轻轻的小音符
        x, i = t, 0
        while x < c["until"]:
            add(bell(hz([88, 91, 93, 96][i % 4]), 0.5), x, 0.025, pan=rng.uniform(-0.3, 0.3))
            x += 0.18
            i += 1
    elif typ == "file":
        swoosh(0.35, 1500, 6500, 0.07, t)
        swoosh(0.25, 1800, 7000, 0.05, t + 0.3)

mix.write(sys.argv[2])
