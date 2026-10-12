"""合成宣传片的声音：背景音乐 + 音效 + 小团子的"萌语"。

用法：python audio.py <cues.json> <out.wav>
全部用 numpy 现场合成，没有用任何采样素材，所以没有版权问题。
音效时间点来自 render.cjs 渲染时记录的 cues；说话 / 聆听的音节
按引擎里驱动嘴型的同一个公式（index.html 的 speechEnvelope）排布，和嘴型对齐。
"""
import json
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, fftconvolve, sosfilt

SR = 44100
data = json.load(open(sys.argv[1]))
DUR = data["duration"]
CUES = data["cues"]
N = int(SR * (DUR + 0.5))
OUT_L = np.zeros(N)
OUT_R = np.zeros(N)
rng = np.random.default_rng(20261012)


def tv(d):
    return np.arange(int(d * SR)) / SR


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def band(sig, lo, hi, order=2):
    return sosfilt(butter(order, [lo / (SR / 2), min(hi, SR / 2 - 100) / (SR / 2)], btype="band", output="sos"), sig)


def low(sig, fc, order=2):
    return sosfilt(butter(order, fc / (SR / 2), btype="low", output="sos"), sig)


def add(sig, t, gain=1.0, pan=0.0):
    i = int(round(t * SR))
    if i >= N or i < 0:
        return
    s = sig[: N - i] * gain
    a = (pan + 1) * np.pi / 4
    OUT_L[i : i + len(s)] += s * np.cos(a) * 1.414
    OUT_R[i : i + len(s)] += s * np.sin(a) * 1.414


def glide(f_of_t, d):
    """频率随时间变化的正弦（相位积分，避免爆音）。"""
    t = tv(d)
    return np.sin(2 * np.pi * np.cumsum(f_of_t(t)) / SR), t


# ── 乐器 ────────────────────────────────────────────────

def kalimba(f, d=1.4):
    t = tv(d)
    s = (np.sin(2 * np.pi * f * t) * np.exp(-t * 3.0)
         + 0.35 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t * 7)
         + 0.12 * np.sin(2 * np.pi * 5.4 * f * t) * np.exp(-t * 20))
    return s * np.minimum(1, t / 0.003)


def bell(f, d=1.6):
    t = tv(d)
    s = sum(g * np.sin(2 * np.pi * f * r * t) * np.exp(-t * k)
            for r, g, k in [(1, 1, 2.4), (2.76, 0.4, 5), (5.4, 0.2, 9), (8.93, 0.08, 15)])
    return s * np.minimum(1, t / 0.002)


def pad(notes, d, att=0.6, rel=0.9):
    t = tv(d)
    env = np.minimum(1, t / att) * np.clip((d - t) / rel, 0, 1)
    s = sum(np.sin(2 * np.pi * hz(m) * t) + 0.5 * np.sin(2 * np.pi * hz(m) * 1.004 * t + 1.3)
            + 0.2 * np.sin(2 * np.pi * hz(m + 12) * t) for m in notes)
    return low(s, 2200) * env / len(notes)


def bass(f, d=1.1):
    t = tv(d)
    return (np.sin(2 * np.pi * f * t) + 0.25 * np.sin(4 * np.pi * f * t)) * np.exp(-t * 2.2) * np.minimum(1, t / 0.01)


def thump():
    s, t = glide(lambda t: 48 + 80 * np.exp(-t * 28), 0.25)
    return s * np.exp(-t * 13)


def shaker():
    t = tv(0.07)
    return band(rng.standard_normal(len(t)), 5000, 12000) * np.exp(-t * 70)


def noise_burst(d, lo, hi, shape="swell"):
    t = tv(d)
    env = np.sin(np.pi * t / d) ** 2 if shape == "swell" else np.exp(-t * 40)
    return band(rng.standard_normal(len(t)), lo, hi) * env


# ── 萌语：锯齿声源 + 两个共振峰，像动森里的小动物说话 ─────────────

VOWELS = {"a": (800, 1250), "i": (320, 2300), "u": (360, 900), "e": (520, 1900), "o": (520, 950)}


def syllable(f0, d, vowel, shift=1.3, consonant=True):
    t = tv(d)
    f = f0 * (1 + 0.07 * np.sin(np.pi * t / d))
    ph = 2 * np.pi * np.cumsum(f) / SR
    src = sum(np.sin(k * ph) / k for k in range(1, 12))
    f1, f2 = VOWELS[vowel]
    out = band(src, f1 * shift - 140, f1 * shift + 140) + 0.6 * band(src, f2 * shift - 200, f2 * shift + 200)
    env = np.minimum(1, t / 0.012) * np.clip((d - t) / 0.06, 0, 1)
    out = out * env
    if consonant:
        c = noise_burst(0.018, 3000, 9000, "decay")
        out[: len(c)] += c * 0.25
    return out / (np.abs(out).max() + 1e-9)


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


def pop():
    s, t = glide(lambda t: 320 + 800 * np.exp(-t * 55), 0.09)
    return s * np.exp(-t * 42)


def blip(f_from, f_to, d=0.075):
    s, t = glide(lambda t: f_from + (f_to - f_from) * (t / d), d)
    return s * np.sin(np.pi * t / d)


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

# ── 混音：一点房间混响 + 柔和限幅 + 淡出 ─────────────────────────

ir_t = tv(0.7)
ir = rng.standard_normal(len(ir_t)) * np.exp(-ir_t * 7)
ir = low(ir, 5000)
ir /= np.abs(ir).sum() ** 0.5 * 18
mix = np.stack([OUT_L, OUT_R])
wet = np.stack([fftconvolve(ch, ir)[:N] for ch in mix])
mix = mix + wet * 0.5
fade = np.clip((DUR - np.arange(N) / SR) / 0.8, 0, 1)
mix *= fade
mix /= np.abs(mix).max() + 1e-9
mix = np.tanh(mix * 1.4) / np.tanh(1.4) * 0.89
wavfile.write(sys.argv[2], SR, (mix.T * 32767).astype(np.int16))
print(f"wrote {sys.argv[2]}  {DUR:.1f}s  peak={np.abs(mix).max():.2f}")
