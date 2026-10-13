"""宣传片共用的合成器：乐器、音效零件和混音。

全部用 numpy 现场合成，没有用任何采样素材，所以没有版权问题。
companion 和 today 的宣传片都从这里取乐器，各自的 audio.py 只写配乐和音效时间点。
随机数用同一个固定种子，所以同一份 cues 每次合成出来的声音都一模一样。
"""
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, fftconvolve, sosfilt

SR = 44100
rng = np.random.default_rng(20261012)


def tv(d):
    return np.arange(int(d * SR)) / SR


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def band(sig, lo, hi, order=2):
    return sosfilt(butter(order, [lo / (SR / 2), min(hi, SR / 2 - 100) / (SR / 2)], btype="band", output="sos"), sig)


def low(sig, fc, order=2):
    return sosfilt(butter(order, fc / (SR / 2), btype="low", output="sos"), sig)


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


def pop():
    s, t = glide(lambda t: 320 + 800 * np.exp(-t * 55), 0.09)
    return s * np.exp(-t * 42)


def blip(f_from, f_to, d=0.075):
    s, t = glide(lambda t: f_from + (f_to - f_from) * (t / d), d)
    return s * np.sin(np.pi * t / d)


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


# ── 混音 ────────────────────────────────────────────────

class Mix:
    """立体声混音台：按时间点叠加声音，最后加一点房间混响、柔和限幅和淡出。"""

    def __init__(self, duration):
        self.duration = duration
        self.n = int(SR * (duration + 0.5))
        self.left = np.zeros(self.n)
        self.right = np.zeros(self.n)

    def add(self, sig, t, gain=1.0, pan=0.0):
        i = int(round(t * SR))
        if i >= self.n or i < 0:
            return
        s = sig[: self.n - i] * gain
        a = (pan + 1) * np.pi / 4
        self.left[i : i + len(s)] += s * np.cos(a) * 1.414
        self.right[i : i + len(s)] += s * np.sin(a) * 1.414

    def write(self, path):
        ir_t = tv(0.7)
        ir = rng.standard_normal(len(ir_t)) * np.exp(-ir_t * 7)
        ir = low(ir, 5000)
        ir /= np.abs(ir).sum() ** 0.5 * 18
        mix = np.stack([self.left, self.right])
        wet = np.stack([fftconvolve(ch, ir)[: self.n] for ch in mix])
        mix = mix + wet * 0.5
        fade = np.clip((self.duration - np.arange(self.n) / SR) / 0.8, 0, 1)
        mix *= fade
        mix /= np.abs(mix).max() + 1e-9
        mix = np.tanh(mix * 1.4) / np.tanh(1.4) * 0.89
        wavfile.write(path, SR, (mix.T * 32767).astype(np.int16))
        print(f"wrote {path}  {self.duration:.1f}s  peak={np.abs(mix).max():.2f}")
