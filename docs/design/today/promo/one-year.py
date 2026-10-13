"""合成场景片①「一年后的今天」的声音：慢一点、暖一点的配乐 + 少量音效。

用法：python one-year.py <cues.json> <out.wav>（./build.sh one-year 会调用）
乐器和混音在 ../../companion/promo/synth.py，和其它宣传片共用。
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "companion", "promo"))
from music import common_sfx  # noqa: E402
from synth import Mix, bass, bell, blip, hz, kalimba, low, noise_burst, pad, rng, syllable, thump  # noqa: E402

data = json.load(open(sys.argv[1]))
DUR = data["duration"]
CUES = data["cues"]
mix = Mix(DUR)
add = mix.add
first = lambda typ: next(c["t"] for c in CUES if c["type"] == typ)

# ── 配乐：76 BPM，F - G - Em - Am（IV - V - iii - vi），开头只有铺底和几个音，回忆那段慢慢满起来 ──

BEAT = 60 / 76
BAR = BEAT * 4
PROG = [([53, 57, 60, 64], 41), ([55, 59, 62, 67], 43), ([52, 55, 59, 64], 40), ([57, 60, 64, 67], 45)]
# 每小节 8 个八分音符的旋律（None = 休止），两段轮着用
MEL_A = [[72, None, 69, None, 72, 74, None, None], [74, None, 71, None, 74, 76, None, None],
         [76, None, 74, 72, 71, None, 67, None], [69, None, None, 72, 71, None, 69, None]]
MEL_B = [[77, None, 76, 72, None, 74, 72, None], [79, None, 76, 74, None, 76, 74, None],
         [76, 79, None, 76, 74, None, 72, None], [72, None, 71, None, 69, None, None, None]]
START = 0.3
MEMORY = (first("swipe"), first("letter"))  # 回看 + 这一年：音乐最满
END_CHORD = first("end")

b = 0
while True:
    start = START + b * BAR
    if start >= END_CHORD - 0.1:
        break
    chord, root = PROG[b % 4]
    full = MEMORY[0] - 0.2 <= start + BAR / 2 < MEMORY[1]
    add(pad(chord, BAR + 0.8, att=0.9, rel=1.2), start, 0.11)
    add(bass(hz(root), BAR), start, 0.12 if full else 0.08)
    if full:
        add(thump(), start, 0.1)
        add(thump(), start + 2 * BEAT, 0.07)
    mel = (MEL_A if (b // 4) % 2 == 0 else MEL_B)[b % 4]
    for i, m in enumerate(mel):
        nt = start + i * BEAT / 2
        if m is None or nt >= END_CHORD - 0.1:
            continue
        if start < first("notify") - 0.3:
            # 开头：只有零星几个八音盒音
            if i in (0, 4):
                add(bell(hz(m + 12), 2.0), nt, 0.05)
            continue
        add(kalimba(hz(m), 1.8), nt, 0.15 if full else 0.11, pan=(-0.15 if i % 2 else 0.15))
    b += 1

end_len = DUR - END_CHORD + 0.4
add(pad([41, 53, 57, 60, 64], end_len, att=0.05, rel=end_len * 0.8), END_CHORD, 0.13)  # 落在 F 大七，留一点余味
add(bass(hz(29), end_len), END_CHORD, 0.16)
for i, m in enumerate([65, 69, 72, 76]):
    add(kalimba(hz(m), 2.8), END_CHORD + i * 0.11, 0.18)

# ── 音效 ───────────────────────────────────────────────

POLA_NOTES = [72, 74, 76, 79, 81, 84]

for c in CUES:
    t, typ = c["t"], c["type"]
    if common_sfx(add, c):
        continue
    elif typ == "open":
        add(noise_burst(0.8, 300, 2500), t - 0.1, 0.04)
    elif typ == "notify":
        add(bell(hz(84), 1.6), t, 0.14)
        add(bell(hz(91), 1.8), t + 0.18, 0.12)
    elif typ == "swipe":
        add(noise_burst(0.45, 800, 5000), t, 0.05)
        for i, m in enumerate([72, 76, 79, 84]):
            add(bell(hz(m), 1.6), t + 0.1 + i * 0.07, 0.05)
    elif typ == "pola":
        # 拍立得落下：纸片落桌的一下 + 一个往上走的音
        i = c.get("i", 0)
        add(low(noise_burst(0.05, 300, 3000, "decay"), 2500), t + 0.45, 0.25)
        add(kalimba(hz(POLA_NOTES[i % 6] + 12), 1.2), t + 0.05, 0.1)
    elif typ == "letter":
        add(noise_burst(0.4, 1500, 6500), t, 0.06)
        add(noise_burst(0.3, 1800, 7000), t + 0.32, 0.04)
    elif typ == "count":
        x, k = t, 0
        while x < c["until"]:
            add(blip(1500 + 40 * k, 1550 + 40 * k, 0.03), x, 0.025)
            x += 0.07 + 0.01 * k
            k += 1
    elif typ == "write":
        # 写字：很轻的笔尖沙沙声
        x = t
        while x < c["until"]:
            add(noise_burst(0.05, 2500, 8000), x, 0.02 + 0.01 * rng.random())
            x += rng.uniform(0.09, 0.16)
    elif typ == "sign":
        add(syllable(760, 0.12, "a"), t + 0.05, 0.16)
        add(syllable(1000, 0.18, "i", consonant=False), t + 0.2, 0.16)
        for i, m in enumerate([84, 88, 91]):
            add(bell(hz(m), 1.2), t + 0.1 + i * 0.05, 0.06)

mix.write(sys.argv[2])
