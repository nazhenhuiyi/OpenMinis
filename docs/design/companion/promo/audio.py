"""合成宣传片的声音：背景音乐 + 音效 + 小团子的"萌语"。

用法：python audio.py <cues.json> <out.wav>
乐器和混音在 synth.py（和 today 的宣传片共用），这里只写配乐和音效时间点。
音效时间点来自 render.cjs 渲染时记录的 cues；说话 / 聆听的音节
按引擎里驱动嘴型的同一个公式（index.html 的 speechEnvelope）排布，和嘴型对齐。
"""
import json
import sys

import numpy as np

from synth import (SR, Mix, bass, bell, blip, glide, hz, kalimba, low, noise_burst, pad, pop, rng, shaker,
                   syllable, thump, tv, VOWELS)

data = json.load(open(sys.argv[1]))
DUR = data["duration"]
CUES = data["cues"]
mix = Mix(DUR)
add = mix.add


def speech_env(t):
    """index.html 里 speechEnvelope 的 Python 版。"""
    syl = max(0.0, np.sin(t * 11))
    accent = 0.55 + 0.45 * abs(np.sin(t * 3.3))
    phrase = 1 if np.sin(t * 1.7) > -0.6 else 0.1
    return syl * accent * phrase, phrase


def babble(t0, t1, offset, *, scale=1.0, shift_t=0.0, f0=640, shift=1.3, gain=0.3, pan=0.0, lp=None):
    """在 [t0, t1) 里，按嘴型公式的每个 sin 正半周放一个音节。

    引擎的音量是 speechEnvelope(u)，u = sim.t * scale + shift_t；offset = 视频时间 - sim.t。
    """
    buf = np.zeros(int((t1 - t0 + 0.5) * SR))
    u0, u1 = (t0 - offset) * scale + shift_t, (t1 - offset) * scale + shift_t
    k = int(np.ceil(u0 * 11 / (2 * np.pi)))
    while True:
        ua = 2 * np.pi * k / 11
        k += 1
        if ua >= u1:
            break
        if ua < u0:
            continue
        amp, phrase = speech_env(ua + np.pi / 22)
        if phrase < 0.5 or amp < 0.08:
            continue
        start = (ua - shift_t) / scale + offset + 0.025
        d = (np.pi / 11) / scale * 0.85
        pitch = f0 * rng.choice([1, 1.12, 1.25, 1.06, 0.94, 1.19]) * (1 + 0.12 * amp)
        s = syllable(pitch, d, rng.choice(list(VOWELS)), shift, consonant=rng.random() < 0.6)
        i = int((start - t0) * SR)
        buf[i : i + len(s)] += s[: len(buf) - i] * (0.45 + 0.55 * amp)
    if lp:
        buf = low(buf, lp)
    add(buf, t0, gain, pan)


# ── 背景音乐：100 BPM，C - Am - F - G，卡林巴琴旋律 + 柔和铺底 ─────────

cue_t = lambda typ: [c for c in CUES if c["type"] == typ]
LAND0 = next(c["t"] for c in CUES if c["type"] == "land")
BEAT, BAR = 0.6, 2.4
PROG = [([60, 64, 67], 48), ([57, 60, 64], 45), ([53, 57, 60], 41), ([55, 59, 62], 43)]
MEL_A = [[76, 79, 81, 79, 76, None, 74, 72], [72, 76, 79, 76, 81, None, 79, 76],
         [77, 81, 84, 81, 79, None, 77, None], [79, None, 81, 83, 86, None, 83, 79]]
MEL_B = [[72, None, 76, 79, 84, None, 81, 79], [76, None, 72, 76, 79, 81, None, 76],
         [81, None, 77, 81, 84, 86, 84, None], [83, 81, 79, None, 74, None, 79, None]]
SLEEP = (35.0, 37.4)
END_CHORD = 39.9


def mel_gain(t):
    if SLEEP[0] <= t < SLEEP[1]:
        return 0
    if 23.0 <= t < 28.2:
        return 0.08
    if 10.6 <= t < 14.0:
        return 0.12
    return 0.2


# 开场：落地前铺底慢慢升起来
add(pad([60, 64, 67], LAND0 + 0.6, att=LAND0, rel=0.5), 0, 0.10)

b = 0
while True:
    start = LAND0 + b * BAR
    if start >= END_CHORD - 0.05:
        break
    chord, root = PROG[b % 4]
    sleeping = SLEEP[0] - 0.3 <= start + BAR / 2 < SLEEP[1]
    add(pad(chord, BAR + 0.5), start, 0.065 if sleeping else 0.095)
    if not sleeping:
        for beat in range(4):
            bt = start + beat * BEAT
            if beat in (0, 2):
                add(bass(hz(root)), bt, 0.16)
                add(thump(), bt, 0.22)
            add(shaker(), bt + BEAT / 2, 0.035, pan=0.3)
    mel = (MEL_A if (b // 4) % 2 == 0 else MEL_B)[b % 4]
    for i, m in enumerate(mel):
        nt = start + i * BEAT / 2
        if m is not None and nt < END_CHORD - 0.1 and mel_gain(nt) > 0:
            add(kalimba(hz(m)), nt, mel_gain(nt), pan=(-0.15 if i % 2 else 0.15))
    b += 1

# 睡觉时换成慢慢的八音盒
for dt, m in [(0.35, 79), (1.05, 76), (1.75, 72)]:
    add(bell(hz(m), 2.0), SLEEP[0] + dt, 0.07)

# 结尾：C 大三和弦一路响到结束
end_len = DUR - END_CHORD + 0.4
add(pad([48, 60, 64, 67, 72], end_len, att=0.05, rel=end_len * 0.8), END_CHORD, 0.12)
add(bass(hz(36), end_len), END_CHORD, 0.18)
for i, m in enumerate([72, 76, 79, 84]):
    add(kalimba(hz(m), 2.5), END_CHORD + i * 0.09, 0.2)

# ── 音效 ────────────────────────────────────────────────

def boing(h):
    s, t = glide(lambda t: 150 + 230 * (1 - np.exp(-t * 14)) + 28 * np.sin(2 * np.pi * 17 * t) * np.exp(-t * 6), 0.42)
    return s * np.exp(-t * 8.5) * np.minimum(1, t / 0.004)


PENT = [84, 86, 88, 91, 93, 96]
for c in CUES:
    t, typ = c["t"], c["type"]
    if typ == "drop":
        d = max(0.4, LAND0 - t)
        s, tt = glide(lambda x: 1500 - 1100 * (x / d) ** 0.8 + 18 * np.sin(2 * np.pi * 7 * x), d)
        add(s * np.sin(np.pi * tt / d) ** 0.5, t, 0.07)
    elif typ == "land":
        k = float(np.clip(c.get("h", 150) / 420, 0.25, 1))
        add(boing(k), t, 0.42 * k)
        add(thump(), t, 0.3 * k)
    elif typ in ("tap", "wake"):
        add(pop(), t, 0.5)
        add(noise_burst(0.004, 2000, 8000, "decay"), t, 0.2)
        if typ == "tap":
            add(bell(hz(88), 1.0), t + 0.05, 0.12, pan=0.2)
            add(bell(hz(95), 1.0), t + 0.13, 0.08, pan=0.2)
        else:
            s, tt = glide(lambda x: 520 + 1100 * (x / 0.2), 0.2)
            add(s * np.sin(np.pi * tt / 0.2), t + 0.06, 0.2)
            add(bell(hz(91), 1.2), t + 0.1, 0.12)
    elif typ == "pet":
        i = c.get("i", 0)
        add(bell(hz(PENT[i % len(PENT)]), 0.9), t, 0.1, pan=(-0.35 if i % 2 else 0.35))
        add(low(noise_burst(0.2, 200, 1200), 900), t, 0.06)
    elif typ == "listen":
        add(blip(700, 980), t, 0.22)
        add(blip(980, 1400), t + 0.09, 0.22)
    elif typ == "think":
        for i, m in enumerate([79, 84, 88, 91]):
            add(bell(hz(m), 1.0), t + i * 0.06, 0.07)
        for rep in range(2):
            for i in range(3):
                add(blip(380 + i * 110, 640 + i * 140, 0.06), t + 0.5 + rep * 1.2 + i * 0.2, 0.09)
    elif typ == "tool":
        add(kalimba(hz(81), 0.4), t, 0.14)
        tool = c.get("tool")
        if tool == 4:  # 搜索：叮一声
            add(bell(2093, 1.0), t + 0.1, 0.07)
            add(bell(2637, 1.0), t + 0.42, 0.05)
        elif tool == 1:  # 浏览器：哔哔
            add(blip(1200, 1250, 0.05), t + 0.1, 0.14)
            add(blip(1600, 1650, 0.05), t + 0.18, 0.14)
        elif tool == 2:  # 终端：敲键盘
            x = t + 0.15
            while x < t + 1.35:
                add(noise_burst(0.016, 1800, 6000, "decay"), x, 0.11 + 0.05 * rng.random(), pan=rng.uniform(-0.2, 0.2))
                x += rng.uniform(0.055, 0.12)
        elif tool == 3:  # 文件：翻纸
            add(noise_burst(0.22, 1500, 6000), t + 0.1, 0.07)
            add(noise_burst(0.18, 1800, 7000), t + 0.42, 0.05)
    elif typ == "speak":
        offset = t - c.get("simT", t)
        babble(t, 28.2, offset, f0=640, shift=1.3, gain=0.3)
    elif typ == "done":
        for i, m in enumerate([84, 88, 91, 96, 100]):
            add(bell(hz(m), 1.2), t + 0.04 + i * 0.045, 0.1, pan=(i - 2) * 0.2)
        add(syllable(700, 0.13, "a"), t + 0.06, 0.22)
        add(syllable(930, 0.2, "i", consonant=False), t + 0.22, 0.22)
    elif typ == "skin":
        i = c.get("i", 0)
        add(noise_burst(0.35, 900, 7000), t - 0.12, 0.06)
        for j, m in enumerate([84, 88, 91]):
            add(bell(hz(m + [0, 2, 4, 7][i]), 1.1), t + j * 0.035, 0.08)
    elif typ == "sleep":
        # 呼噜：吸气（低沉的气声）+ 呼气（"噗——"）
        for k in range(2):
            base = t + 0.4 + k * 1.1
            s, tt = glide(lambda x: 95 + 10 * np.sin(2 * np.pi * 3 * x), 0.7)
            add(low(noise_burst(0.7, 150, 900), 700) * 0.7 + s * np.sin(np.pi * tt / 0.7) * 0.25, base, 0.12)
            s, tt = glide(lambda x: 340 - 120 * x / 0.45, 0.45)
            add(s * np.sin(np.pi * tt / 0.45) ** 2, base + 0.75, 0.05)
    elif typ == "end":
        for i, m in enumerate([84, 88, 91, 96]):
            add(bell(hz(m), 2.0), t + i * 0.07, 0.09)

# 聆听：用户在说话（低沉、有点闷，从左边来）
babble(10.75, 14.0, 0.0, scale=0.8, shift_t=3.0, f0=190, shift=1.0, gain=0.13, pan=-0.25, lp=2600)

mix.write(sys.argv[2])
