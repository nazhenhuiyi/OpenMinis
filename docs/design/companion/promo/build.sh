#!/usr/bin/env bash
# 出宣传片：逐帧渲染画面 → 按时间点合成声音 → 合成 mp4。
# 依赖：Google Chrome、ffmpeg、node（puppeteer-core）、python（numpy + scipy）。
#   NODE_MODULES=…/node_modules  PYTHON=…/bin/python  ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
WORK=${WORK:-/tmp/minis-promo-out}
NODE_MODULES=${NODE_MODULES:-/tmp/minis-promo-render/node_modules}
PYTHON=${PYTHON:-/tmp/minis-promo-venv/bin/python}
OUT=${OUT:-minis-companion-promo.mp4}

NODE_PATH="$NODE_MODULES" node render.cjs "$WORK" 30
"$PYTHON" audio.py "$WORK/cues.json" "$WORK/audio.wav"
ffmpeg -y -loglevel error -i "$WORK/video.mp4" -i "$WORK/audio.wav" \
  -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart "$OUT"
echo "→ $(pwd)/$OUT"
