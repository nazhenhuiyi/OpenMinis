#!/usr/bin/env bash
# 出「今日」的宣传片：逐帧渲染画面 → 按时间点合成声音 → 合成 mp4。
#   ./build.sh tour        功能巡礼（tour.html + tour.py）
#   ./build.sh one-year    一年后的今天（one-year.html + one-year.py）
#   ./build.sh snap        拍一张就够了（snap.html + snap.py）
# 渲染脚本、时钟和合成器都和 companion 宣传片共用（../../companion/promo/）。
# 依赖：Google Chrome、ffmpeg、node（puppeteer-core）、python（numpy + scipy）。
#   NODE_MODULES=…/node_modules  PYTHON=…/bin/python  ./build.sh <name>
set -euo pipefail
cd "$(dirname "$0")"
NAME=${1:?用法：./build.sh <tour|one-year|snap>}
WORK=${WORK:-/tmp/minis-today-$NAME}
NODE_MODULES=${NODE_MODULES:-/tmp/minis-promo-render/node_modules}
PYTHON=${PYTHON:-/tmp/minis-promo-venv/bin/python}
OUT=${OUT:-minis-today-$NAME.mp4}

# 页面要读 companion 的原型，所以从 docs/design/ 起服务
ROOT="$(cd ../.. && pwd)" PAGE="today/promo/$NAME.html" NODE_PATH="$NODE_MODULES" \
  node ../../companion/promo/render.cjs "$WORK" 30
"$PYTHON" "$NAME.py" "$WORK/cues.json" "$WORK/audio.wav"
ffmpeg -y -loglevel error -i "$WORK/video.mp4" -i "$WORK/audio.wav" \
  -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart "$OUT"
echo "→ $(pwd)/$OUT"
