// KnotYup 桌面端装配：状态刷新、事件订阅、启动引导。
// 依赖 common.js / home.js / group.js 先行加载（共享全局，无构建步骤）。

async function refresh() {
  const s = await invoke('get_state', {});
  state.device = s.device;
  state.localIp = s.local_ip;
  state.session = s.session;
  clipItems = s.history || [];
  renderSession();
  renderClipboard();
  renderDiscovered();
}

// ── 事件订阅 ──────────────────────────────

async function main() {
  applyI18n();
  initThemePicker();
  initLangMenu();

  // 开屏层淡出时才挂载主界面（display:none 期间 CSS 挂载动画不会提前播放）
  setTimeout(() => {
    const root = document.getElementById('appRoot');
    if (root) root.style.display = ''; // 恢复 .app 的 flex，挂载动画此刻开始
    const boot = document.getElementById('boot-splash');
    if (boot) {
      boot.classList.add('out');
      setTimeout(() => boot.remove(), 500);
    }
  }, 1950); // 等最后一字蹦跳完成（1.14s 延迟 + 0.65s 动画 ≈ 1.8s）再淡出

  await listen('knot://members', (e) => {
    hideLoading();
    if (state.session) {
      state.session.members = e.payload;
      renderMembers();
    }
  });
  await listen('knot://clipboard', (e) => {
    clipItems = [{ from: e.payload.from, text: e.payload.text || '', at_ms: Date.now() }, ...clipItems].slice(0, 50);
    renderClipboard();
  });
  await listen('knot://kicked', (e) => {
    const p = e.payload;
    toast(p === 'GROUP_DISSOLVED' || p === 'DISSOLVED' ? t('group_dissolved') : p === 'GROUP_LOST' ? t('group_lost') : p === 'TRANSFERRED' ? t('left') : t('kicked'), 'error');
    hideLoading();
    state.session = null;
    clipItems = [];
    renderSession();
    renderClipboard();
  });
  await listen('knot://error', (e) => toast(e.payload, 'error'));
  await listen('knot://toast', (e) => {
    toast(String(e.payload));
    // 升主/重连/转让完成后会话已换新，需要刷新视图
    if (e.payload === 'RECONNECTED' || e.payload === 'PROMOTED' || e.payload === 'TRANSFERRED' || e.payload === 'MIGRATED') refresh();
  });
  await listen('knot://member-change', (e) => {
    toast(t(e.payload.type === 'joined' ? 'member_joined' : 'member_left', { name: e.payload.name }));
  });
  await listen('knot://discovered', (e) => {
    state.discovered = e.payload;
    renderDiscovered();
  });

  // 调试入口：双击首页右下角版号 → 系统浏览器打开实时日志页（软件继续运行）
  $('versionBadge').ondblclick = () => window.open('http://127.0.0.1:45692', '_blank');
  // 发布版（release 构建）后端不编译调试服务，此处隐藏入口
  try {
    const dbg = await invoke('debug_tools_enabled');
    if (!dbg) $('versionBadge').style.display = 'none';
  } catch { $('versionBadge').style.display = 'none'; }

  state.discoveredScanning = true;
  renderDiscovered();
  await refresh();
}

main();
