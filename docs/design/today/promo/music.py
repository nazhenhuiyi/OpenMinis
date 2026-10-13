"""「今日」宣传片共用的配乐和音效：轻快的背景音乐、手指轻点、片尾小团子落地。

各支片子的 <name>.py 从这里取，只写自己特有的音效。乐器和混音在 companion 的 synth.py。
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "companion", "promo"))
from synth import bass, bell, glide, hz, kalimba, noise_burst, pad, pop, shaker, thump  # noqa: E402

# 100 BPM，C - Am - F - G，卡林巴琴旋律 + 柔和铺底（和 companion 同一个调性）
BEAT, BAR = 0.6, 2.4
PROG = [([60, 64, 67], 48), ([57, 60, 64], 45), ([53, 57, 60], 41), ([55, 59, 62], 43)]
MEL_A = [[76, 79, 81, 79, 76, None, 74, 72], [72, 76, 79, 76, 81, None, 79, 76],
         [77, 81, 84, 81, 79, None, 77, None], [79, None, 81, 83, 86, None, 83, 79]]
MEL_B = [[72, None, 76, 79, 84, None, 81, 79], [76, None, 72, 76, 79, 81, None, 76],
         [81, None, 77, 81, 84, 86, 84, None], [83, 81, 79, None, 74, None, 79, None]]


def pop_bed(add, duration, end_chord, mel_gain=lambda t: 0.17, start=0.6):
    """轻快的背景音乐，从 start 铺到 end_chord，最后落在 C 大三和弦上一直响到结束。"""
    add(pad([60, 64, 67], start + 1.2, att=1.0, rel=0.6), 0, 0.09)
    b = 0
    while True:
        bar = start + b * BAR
        if bar >= end_chord - 0.05:
            break
        chord, root = PROG[b % 4]
        add(pad(chord, BAR + 0.5), bar, 0.09)
        for beat in range(4):
            bt = bar + beat * BEAT
            if bt >= end_chord - 0.05:
                break
            if beat in (0, 2):
                add(bass(hz(root)), bt, 0.15)
                add(thump(), bt, 0.18)
            add(shaker(), bt + BEAT / 2, 0.03, pan=0.3)
        mel = (MEL_A if (b // 4) % 2 == 0 else MEL_B)[b % 4]
        for i, m in enumerate(mel):
            nt = bar + i * BEAT / 2
            if m is not None and nt < end_chord - 0.1:
                add(kalimba(hz(m)), nt, mel_gain(nt), pan=(-0.15 if i % 2 else 0.15))
        b += 1

    end_len = duration - end_chord + 0.4
    add(pad([48, 60, 64, 67, 72], end_len, att=0.05, rel=end_len * 0.8), end_chord, 0.12)
    add(bass(hz(36), end_len), end_chord, 0.18)
    for i, m in enumerate([72, 76, 79, 84]):
        add(kalimba(hz(m), 2.5), end_chord + i * 0.09, 0.2)


def common_sfx(add, c):
    """stage.js 发出的通用音效（轻点、片尾掉落 / 落地 / 挥手）。处理了返回 True。"""
    t, typ = c["t"], c["type"]
    if typ == "tap":
        add(pop(), t, 0.35)
        add(noise_burst(0.004, 2000, 8000, "decay"), t, 0.15)
    elif typ == "drop":
        d = 0.9
        s, tt = glide(lambda x: 1500 - 1100 * (x / d) ** 0.8 + 18 * np.sin(2 * np.pi * 7 * x), d)
        add(s * np.sin(np.pi * tt / d) ** 0.5, t, 0.06)
    elif typ == "land":
        k = float(np.clip(c.get("h", 150) / 420, 0.25, 1))
        s, tt = glide(lambda x: 150 + 230 * (1 - np.exp(-x * 14)) + 28 * np.sin(2 * np.pi * 17 * x) * np.exp(-x * 6), 0.42)
        add(s * np.exp(-tt * 8.5) * np.minimum(1, tt / 0.004), t, 0.4 * k)
        add(thump(), t, 0.28 * k)
    elif typ == "end":
        for i, m in enumerate([84, 88, 91, 96]):
            add(bell(hz(m), 2.0), t + i * 0.07, 0.09)
    else:
        return False
    return True
