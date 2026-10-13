"""合成场景片②「拍一张，就认识它」的声音：轻快的背景音乐 + 快门、Minis 建议、盖章、图鉴的音效。

用法：python snap.py <cues.json> <out.wav>（./build.sh snap 会调用）
配乐和通用音效在 music.py，乐器和混音在 companion 的 synth.py。
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "companion", "promo"))
from music import common_sfx, pop_bed  # noqa: E402
from synth import Mix, bell, blip, hz, kalimba, low, noise_burst, pop  # noqa: E402

data = json.load(open(sys.argv[1]))
DUR = data["duration"]
CUES = data["cues"]
mix = Mix(DUR)
add = mix.add
first = lambda typ: next(c["t"] for c in CUES if c["type"] == typ)

pop_bed(add, DUR, first("end"), lambda t: 0.14)

DONE_NOTES = [76, 79, 81, 84, 86, 88, 91]  # 每记下一种，音往上走一级

for c in CUES:
    t, typ = c["t"], c["type"]
    if common_sfx(add, c):
        continue
    elif typ == "open":
        add(noise_burst(0.7, 400, 3000), t - 0.1, 0.05)
    elif typ == "shutter":
        # 相机快门：两下短促的咔嚓
        add(noise_burst(0.012, 1500, 7000, "decay"), t, 0.4)
        add(noise_burst(0.02, 900, 5000, "decay"), t + 0.07, 0.3)
        add(low(noise_burst(0.03, 200, 1500, "decay"), 1200), t, 0.2)
    elif typ == "photo":
        # 照片长进时间线
        add(noise_burst(0.25, 800, 5000), t, 0.035)
        add(kalimba(hz(72), 0.5), t + 0.05, 0.06)
    elif typ == "suggest":
        # Minis 冒出建议：亮晶晶的两下
        add(blip(900, 1300, 0.08), t, 0.07)
        add(bell(hz(91), 0.8), t + 0.05, 0.05)
    elif typ == "stamp":
        # 记下：盖章的「咚」+ 往上走的铃声
        i = c.get("i", 0)
        add(low(noise_burst(0.06, 100, 1200, "decay"), 900), t, 0.4)
        add(bell(hz(DONE_NOTES[i % 7] + 12), 1.0), t + 0.03, 0.1)
        add(bell(hz(DONE_NOTES[i % 7] + 19), 1.0), t + 0.11, 0.08)
    elif typ == "habit":
        for j, m in enumerate([84, 88, 91]):
            add(bell(hz(m), 1.0), t + 0.05 + j * 0.06, 0.07)
    elif typ == "menu":
        add(pop(), t, 0.2)
    elif typ == "swipe":
        add(noise_burst(0.3, 700, 5000), t, 0.05)
    elif typ == "item":
        add(kalimba(hz(DONE_NOTES[c.get("i", 0) % 7] + 12), 0.6), t, 0.07)
    elif typ == "ask":
        add(pop(), t, 0.25)
        add(blip(800, 1050, 0.06), t + 0.04, 0.1)
    elif typ == "stream":
        x, j = t, 0
        while x < c["until"]:
            add(bell(hz([88, 91, 93, 96][j % 4]), 0.5), x, 0.025)
            x += 0.18
            j += 1

mix.write(sys.argv[2])
