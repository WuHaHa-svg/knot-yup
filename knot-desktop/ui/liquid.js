/* 液态流动背景（Canvas 版，与 Android 端 LiquidBackground.kt 同源算法）：
 * 白底上若干柔和色斑持续漂移、涨缩、彼此交融。
 * - 每色斑 5 个独立相位累加器（x主/x副/y主/y副/呼吸），每帧按 帧间隔×速度 推进后 mod 2π
 * - 各分量速度互为无理数倍（π/4、√2…），联合轨迹永不重复；数值有界，长时间运行精度不劣化
 * - 30fps 节流；窗口隐藏时 rAF 自动停帧（零后台开销）
 */
(() => {
  const canvas = document.getElementById('liquid-bg');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');

  const BLOBS = [
    // bx, by, ax, ay, rBase, rBreathe, color —— 与 Android 端同色板
    { bx: .16, by: .22, ax: .13, ay: .10, r0: .62, r1: .10, c: '#dce7fb' }, // 淡蓝
    { bx: .86, by: .28, ax: .11, ay: .14, r0: .58, r1: .12, c: '#ede3fb' }, // 淡紫
    { bx: .24, by: .84, ax: .14, ay: .09, r0: .66, r1: .09, c: '#dff6f0' }, // 淡青
    { bx: .80, by: .82, ax: .10, ay: .13, r0: .56, r1: .13, c: '#fbe7ee' }, // 淡粉
    { bx: .52, by: .50, ax: .08, ay: .08, r0: .50, r1: .08, c: '#fbf3dc' }, // 中央淡金
    { bx: .05, by: .55, ax: .09, ay: .12, r0: .54, r1: .11, c: '#e3f5e3' }, // 淡绿
    { bx: .55, by: .08, ax: .12, ay: .09, r0: .60, r1: .10, c: '#fefbf2' }, // 淡橙（极淡）
    { bx: .45, by: .92, ax: .10, ay: .11, r0: .58, r1: .09, c: '#e4f0fc' }, // 淡天蓝
  ];
  const N = BLOBS.length, P = 5; // 每色斑 5 相位
  // 基速互为无理数倍：π/4、√2、√3、√5、√7、√11、√13、√17
  const base = [0.2749, 0.4950, 0.6063, 0.7827, 0.9260, 1.1608, 1.2620, 1.4431]; // ×0.35（再减半）调慢
  // 分量倍率：x主 / x副 / y主 / y副 / 呼吸
  const mult = [1.0, 1.6180, 1.3, 2.4142, 0.71];
  const phases = new Float32Array(N * P);
  const TWO_PI = Math.PI * 2;

  function resize() {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(canvas.clientWidth * dpr);
    canvas.height = Math.round(canvas.clientHeight * dpr);
  }
  window.addEventListener('resize', resize);
  resize();

  let last = performance.now(), frame = 0;
  function draw(now) {
    requestAnimationFrame(draw);
    frame++;
    if (frame % 2 !== 0) return; // 30fps 节流
    const dt = Math.min((now - last) / 1000, .1); // 防切后台回来时大步长跳变
    last = now;
    for (let i = 0; i < N; i++) {
      for (let c = 0; c < P; c++) {
        const k = i * P + c;
        phases[k] = (phases[k] + dt * base[i] * mult[c]) % TWO_PI;
      }
    }
    const w = canvas.width, h = canvas.height, minDim = Math.min(w, h);
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, w, h);
    for (let i = 0; i < N; i++) {
      const b = BLOBS[i], p = i * P;
      const cx = w * (b.bx + b.ax * (Math.sin(phases[p]) + .45 * Math.sin(phases[p + 1] + i)));
      const cy = h * (b.by + b.ay * (Math.cos(phases[p + 2] + i) + .45 * Math.cos(phases[p + 3])));
      const r = minDim * (b.r0 + b.r1 * Math.sin(phases[p + 4] + i * 2.1));
      const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
      g.addColorStop(0, b.c);
      g.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, TWO_PI);
      ctx.fill();
    }
  }
  requestAnimationFrame(draw);
})();
