// 逐帧渲染 promo.html → 无声视频 + 音效时间点（cues.json）。
// 用法：NODE_PATH=<装了 puppeteer-core 的 node_modules> node render.cjs <out-dir> [fps] [--preview]
// 别的宣传片也用这个脚本：ROOT=<静态文件根目录> PAGE=<相对 ROOT 的页面路径>（默认是 companion 的）。
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const puppeteer = require('puppeteer-core');

const ROOT = path.resolve(process.env.ROOT || path.join(__dirname, '..')); // 默认 companion/
const PAGE = process.env.PAGE || 'promo/promo.html';
const OUT = path.resolve(process.argv[2] || '/tmp/minis-promo-out');
const FPS = Number(process.argv[3]) || 30;
const PREVIEW = process.argv.includes('--preview'); // 只截几张关键帧，用来检查画面
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg', '.png': 'image/png', '.css': 'text/css' };
const server = http.createServer((req, res) => {
  const file = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(ROOT) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/${PAGE}?render`;
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--hide-scrollbars', '--force-color-profile=srgb'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.error('page error:', e.message));
  page.on('console', (m) => m.type() === 'error' && console.error('console:', m.text()));
  await page.setViewport({ width: 540, height: 960, deviceScaleFactor: 2 });
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction('window.__ready === true', { timeout: 15000 });
  await page.evaluate(() => document.fonts.ready);

  const duration = await page.evaluate(() => window.__duration);
  const total = Math.round(duration * FPS);
  const step = 1000 / FPS;

  if (PREVIEW) {
    const marks = (process.env.MARKS || '2,4.7,8.5,12,15.5,20.5,25.5,29,33.2,36.5,40.5').split(',').map(Number);
    let t = 0;
    for (const m of marks) {
      while (t < m) { await page.evaluate((s) => window.__step(s), step); t += step / 1000; }
      await page.screenshot({ path: path.join(OUT, `preview-${m}.png`) });
    }
    await browser.close(); server.close();
    console.log('preview frames in', OUT);
    return;
  }

  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '17', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', path.join(OUT, 'video.mp4')],
  { stdio: ['pipe', 'inherit', 'inherit'] });
  const started = Date.now();
  for (let i = 0; i < total; i++) {
    await page.evaluate((s) => window.__step(s), step);
    const jpg = await page.screenshot({ type: 'jpeg', quality: 95, optimizeForSpeed: true });
    if (!ff.stdin.write(jpg)) await new Promise((r) => ff.stdin.once('drain', r));
    if (i % 60 === 0) process.stdout.write(`frame ${i}/${total}  ${((Date.now() - started) / 1000).toFixed(0)}s\n`);
  }
  ff.stdin.end();
  await new Promise((r) => ff.on('close', r));

  const cues = await page.evaluate(() => window.__cues);
  fs.writeFileSync(path.join(OUT, 'cues.json'), JSON.stringify({ duration, fps: FPS, cues }, null, 1));
  await browser.close(); server.close();
  console.log(`done: ${total} frames, ${cues.length} cues → ${OUT}`);
})().catch((e) => { console.error(e); process.exit(1); });
