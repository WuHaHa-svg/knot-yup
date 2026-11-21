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
  // 文件共享列表
  try {
    const f = await invoke('get_files', {});
    groupFiles = f.group_files || {};
    localFileIds = new Set((f.local_files || []).map((x) => x.file_id));
    if (typeof renderFiles === 'function') renderFiles();
  } catch { /* 后端未就绪 */ }
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
    clipItems = [{ from: e.payload.from, name: e.payload.name, text: e.payload.text || '', at_ms: Date.now() }, ...clipItems].slice(0, 50);
    renderClipboard();
  });
  await listen('knot://kicked', (e) => {
    const p = e.payload;
    // 提示策略：异常终态才弹 error；解散是群主主动操作的结果用普通色；
    // 转让完成已由 knot://toast TRANSFERRED 弹过一次 left，这里只重置 UI 不再弹（修双弹）
    if (p === 'TRANSFERRED') { /* 静默 */ }
    else if (p === 'GROUP_DISSOLVED' || p === 'DISSOLVED') toast(t('group_dissolved'));
    else if (p === 'GROUP_LOST') toast(t('group_lost'), 'error');
    else toast(t('kicked'), 'error');
    hideLoading();
    state.session = null;
    clipItems = [];
    renderSession();
    renderClipboard();
  });
  await listen('knot://error', (e) => toast(e.payload, 'error'));
  // 连接断开（成员侧群主失联）：留在群页面弹「连接已断开」，用户决策重新加入或退出
  await listen('knot://conn-lost', async (e) => {
    const { addr, pin, owner } = e.payload || {};
    hideLoading();
    const msg = t('conn_lost_msg').replaceAll('{name}', owner || '');
    // 循环弹窗（与安卓一致）：重新加入全程 Loading 静默——
    // 成功/已在群中 → 关 Loading 刷新，无提示；失败 → 关 Loading 重新弹窗，无提示
    for (;;) {
      const rejoin = await showConfirm(t('conn_lost_title'), msg, t('rejoin'), t('leave'));
      if (!rejoin) break;
      try {
        showLoading('joining');
        // 5s 内未加入成功即判失败，不无限 Loading
        await Promise.race([
          invoke('join_group', { addr, pin }),
          new Promise((_, rej) => setTimeout(() => rej(new Error('JOIN_TIMEOUT')), 5000)),
        ]);
        await refresh();
        hideLoading();
        return;
      } catch (err) {
        // 已在群中（误判断连/会话仍在）：直接按加入成功处理
        if (String(err).includes('ALREADY_IN_GROUP')) {
          await refresh();
          hideLoading();
          return;
        }
        hideLoading();
      }
    }
    // 退出群：清 UI 回首页
    state.session = null;
    clipItems = [];
    renderSession();
    renderClipboard();
    try { await invoke('leave_group', { transferTo: null }); } catch { /* 无会话时是 no-op */ }
    toast(t('left'));
    await refresh();
  });
  await listen('knot://toast', (e) => {
    toast(String(e.payload));
    // 转让终态：关 Loading（成功弹 left / 失败弹 transfer_failed 由上面的 toast 完成）
    if (e.payload === 'TRANSFERRED' || e.payload === 'TRANSFER_FAILED') hideLoading();
    // 升主/重连/转让完成后会话已换新，需要刷新视图
    if (e.payload === 'PROMOTED' || e.payload === 'TRANSFERRED' || e.payload === 'MIGRATED') refresh();
  });
  await listen('knot://member-change', (e) => {
    toast(t(e.payload.type === 'joined' ? 'member_joined' : 'member_left', { name: e.payload.name }));
  });
  await listen('knot://discovered', (e) => {
    state.discovered = e.payload;
    renderDiscovered();
  });
  await listen('knot://file-changed', async (e) => {
    // FileAdd 事件带 from/name：他人共享时弹 toast「XXX共享了文件」+ 文件名副标题；自己共享不弹
    const p = e.payload;
    if (p && p.from && p.name && state.device && p.from !== state.device.id) {
      toast(t('file_shared', { name: p.from_name }), 'info', p.name);
    }
    try {
      const f = await invoke('get_files', {});
      groupFiles = f.group_files || {};
      localFileIds = new Set((f.local_files || []).map((x) => x.file_id));
      if (typeof renderFiles === 'function') renderFiles();
    } catch { /* ignore */ }
  });

  await listen('knot://file-tasks', (e) => {
    state.transferTasks = e.payload || [];
    if (typeof renderTasks === 'function' && (typeof activePanel === 'undefined' || activePanel === 'tasks')) renderTasks();
  });

  // 调试入口：双击首页右下角版号 → 系统浏览器打开实时日志页（软件继续运行）
  $('versionBadge').ondblclick = () => window.open('http://127.0.0.1:45692', '_blank');
  // 版号动态读取（真相源 = Cargo.toml package.version，tauri.conf.json 已设 null 继承）
  try {
    const v = await window.__TAURI__.app.getVersion();
    if (v) $('versionBadge').textContent = `v${v}`;
  } catch { /* 全局 API 不可用时保留占位 v… */ }
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
