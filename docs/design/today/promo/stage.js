// 「今日」宣传片共用的舞台：水彩底、小团子、字幕、手指、音效时间点和片尾。
// 每支片子只写自己的分镜和画面，调用 Stage.run({ ... }) 开拍。
// 依赖 ../../companion/promo/clock.js（先加载）和 ../../companion/index.html（水彩底和小团子）。
(() => {
  const $ = (id) => document.getElementById(id);
  const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
  const smooth = (a, b, v) => { const x = clamp((v - a) / (b - a)); return x * x * (3 - 2 * x); };
  const ease = (x) => 1 - Math.pow(1 - clamp(x), 3);
  const backOut = (x) => { const u = clamp(x) - 1; return 1 + 2.7 * u * u * u + 1.7 * u * u; };
  const center = (el) => { const r = el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; };

  // 音效时间点：渲染脚本读出去合成声音
  const cues = window.__cues = [];
  let now = 0;
  const cue = (type, extra = {}) => cues.push({ t: +now.toFixed(3), type, ...extra });

  /** 借 companion 原型的水彩底和小团子，返回它的引擎（window.minis）。 */
  async function load() {
    const html = await (await fetch('../../companion/index.html')).text();
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const style = document.createElement('style');
    style.textContent = doc.querySelector('style').textContent;
    // 放在页面自己的样式前面，页面可以覆盖它
    document.head.insertBefore(style, document.head.querySelector('link[rel="stylesheet"], style'));
    $('proto').innerHTML = doc.body.innerHTML.replace(/<script[\s\S]*?<\/script>/g, '');
    $('washHost').appendChild(document.querySelector('#proto .wash'));
    $('charHost').appendChild($('stageSvg'));
    const engine = document.createElement('script');
    engine.textContent = doc.querySelector('body script').textContent;
    document.body.appendChild(engine);
    const M = window.minis;
    M.applySkin('milk');
    M.sim.happyUntil = 0;
    M.sim.waveUntil = 0;
    return M;
  }

  /**
   * 开拍。scenes：[{ t, title, sub, end? }]；actions：[[t, fn]]，每个只触发一次；
   * taps：[[t, () => 元素]]，手指按时间点过去；update(t)：每帧画自己的画面；
   * ending：{ at }，小团子从天上掉下来、片尾字出来的时间。
   */
  function run({ M, duration, scenes, actions = [], taps = [], update, ending }) {
    const acts = [...actions, ...taps.map(([t]) => [t, () => cue('tap')])];
    if (ending) {
      acts.push([ending.at, () => { M.setState('idle'); M.sim.lastInteract = performance.now(); M.sim.hopY = -620; M.sim.hopV = 0; cue('drop'); }]);
      acts.push([ending.at + 1.2, () => { M.sim.waveUntil = performance.now() + 2800; M.sim.happyUntil = performance.now() + 2800; M.hop(200); cue('end'); }]);
    }
    acts.sort((a, b) => a[0] - b[0]);
    let next = 0, sceneIdx = -1, wasAir = false, peak = 0;

    function frame(t) {
      now = t;
      while (next < acts.length && acts[next][0] <= t) acts[next++][1]();

      // 落地：「啵嘤」一声（片尾那一下）
      const air = M.sim.hopY < 0;
      if (air) peak = Math.min(peak, M.sim.hopY);
      if (wasAir && !air && ending && t > ending.at) { cue('land', { h: Math.round(-peak) }); peak = 0; }
      wasAir = air;

      // 水彩晕开
      const bloom = smooth(0, 1.4, t);
      $('washHost').style.opacity = bloom;
      $('washHost').style.transform = `scale(${(1.25 - 0.25 * bloom).toFixed(4)})`;

      // 字幕
      let si = 0;
      scenes.forEach((s, i) => { if (t >= s.t) si = i; });
      const sc = scenes[si], nextT = scenes[si + 1]?.t ?? duration;
      if (si !== sceneIdx) { sceneIdx = si; $('title').textContent = sc.title; $('sub').textContent = sc.sub; }
      const local = t - sc.t;
      const show = smooth(0.05, 0.45, local) * (1 - smooth(nextT - sc.t - 0.3, nextT - sc.t, local));
      $('titles').style.opacity = sc.end ? 0 : show;
      $('titles').style.transform = `translateY(${((1 - smooth(0.05, 0.5, local)) * 14).toFixed(2)}px)`;
      $('counter').textContent = sc.end ? '' : `${String(si + 1).padStart(2, '0')} / ${String(scenes.length - 1).padStart(2, '0')}`;

      update(t);

      // 手指：轻点
      let fx = null, fy = null, fo = 0, press = 0, ripple = null;
      for (const [tt, target] of taps) {
        if (t > tt - 0.4 && t < tt + 0.4) {
          [fx, fy] = center(target());
          fy += (1 - smooth(tt - 0.4, tt - 0.05, t)) * 26;
          fo = smooth(tt - 0.4, tt - 0.18, t) * (1 - smooth(tt + 0.18, tt + 0.4, t));
          press = 1 - Math.abs(t - tt) / 0.12;
        }
        if (t >= tt && t < tt + 0.5) ripple = { at: center(target()), k: (t - tt) / 0.5 };
      }
      $('finger').style.opacity = fo.toFixed(3);
      if (fx != null) $('finger').style.transform = `translate(${fx.toFixed(1)}px, ${fy.toFixed(1)}px) scale(${(1 - clamp(press) * 0.18).toFixed(3)})`;
      if (ripple) {
        $('ripple').style.opacity = (1 - ripple.k).toFixed(3);
        $('ripple').style.transform = `translate(${ripple.at[0].toFixed(1)}px, ${ripple.at[1].toFixed(1)}px) scale(${(1 + ripple.k * 1.6).toFixed(3)})`;
      } else $('ripple').style.opacity = 0;

      // 片尾：小团子从天上掉下来，挥挥手，名字浮上来
      if (ending && t >= ending.at - 0.1) {
        $('charHost').style.transform = '';
        $('charHost').style.opacity = smooth(ending.at - 0.1, ending.at + 0.1, t);
        $('shadow').style.opacity = clamp(1 + M.sim.hopY / 160);
      }
      if (ending) {
        $('end').style.opacity = smooth(ending.at + 0.9, ending.at + 1.5, t);
        $('end').style.transform = `translateY(${((1 - smooth(ending.at + 0.9, ending.at + 1.6, t)) * 18).toFixed(2)}px)`;
      }
      $('fade').style.opacity = smooth(duration - 0.6, duration, t);
    }

    promoRun(frame, duration);
  }

  window.Stage = { $, clamp, smooth, ease, backOut, center, cue, load, run };
})();
