import glob, time, numpy as np, onnxruntime as ort
from PIL import Image
M, S = np.array([0.48145466, 0.4578275, 0.40821073]), np.array([0.26862954, 0.26130258, 0.27577711])
def prep(p):
    im = Image.open(p).convert('RGB'); w, h = im.size; s = 224 / min(w, h)
    im = im.resize((max(224, round(w * s)), max(224, round(h * s))), Image.BICUBIC); w, h = im.size
    l, t = (w - 224) // 2, (h - 224) // 2
    x = (np.asarray(im.crop((l, t, l + 224, t + 224)), np.float32) / 255 - M) / S
    return x.transpose(2, 0, 1)[None].astype(np.float32)
