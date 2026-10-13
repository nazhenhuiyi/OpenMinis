// 宣传片共用的时钟（companion 和 today 都用）。
// 普通打开：按真实时间播放。?render：换成虚拟时钟，render.cjs 逐帧调用 window.__step。
// 必须作为普通 <script src> 放在页面其它脚本之前加载，好在它们之前装好虚拟时钟。
window.RENDER = location.search.includes('render');
if (RENDER) {
  const V = { now: 0, timers: [], raf: [], id: 1 };
  window.__V = V;
  performance.now = () => V.now;
  window.setTimeout = (fn, ms = 0, ...args) => { const id = V.id++; V.timers.push({ id, at: V.now + ms, fn, args }); return id; };
  window.setInterval = (fn, ms, ...args) => { const id = V.id++; V.timers.push({ id, at: V.now + ms, fn, args, every: Math.max(1, ms) }); return id; };
  window.clearTimeout = window.clearInterval = (id) => { V.timers = V.timers.filter((t) => t.id !== id); };
  window.requestAnimationFrame = (fn) => { V.raf.push(fn); return 0; };
  // 可复现的随机数：同一份分镜，每次渲染都一模一样
  let seed = 20261012;
  Math.random = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
}

/** 驱动分镜：update(t) 每帧调用一次，t 是秒。 */
window.promoRun = (update, duration) => {
  if (RENDER) {
    const V = window.__V;
    const starts = new WeakMap();
    // CSS 动画 / 过渡也跟着虚拟时钟走
    const syncCss = () => {
      for (const a of document.getAnimations()) {
        if (!starts.has(a)) starts.set(a, V.now - (a.currentTime || 0));
        a.pause();
        a.currentTime = V.now - starts.get(a);
      }
    };
    window.__step = (ms) => {
      const target = V.now + ms;
      for (;;) {
        const due = V.timers.filter((x) => x.at <= target).sort((a, b) => a.at - b.at)[0];
        if (!due) break;
        V.now = due.at;
        if (due.every) due.at += due.every; else V.timers = V.timers.filter((x) => x !== due);
        due.fn(...(due.args || []));
      }
      V.now = target;
      const cbs = V.raf; V.raf = [];
      cbs.forEach((f) => f(V.now));
      update(V.now / 1000);
      syncCss();
    };
    window.__duration = duration;
    window.__ready = true;
  } else {
    const t0 = performance.now();
    const loop = (ts) => {
      update(Math.min(duration, (ts - t0) / 1000));
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }
};
