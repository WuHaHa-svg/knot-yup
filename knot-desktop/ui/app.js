// KnotYup 桌面端 UI 逻辑（无构建步骤，直接使用 Tauri 全局 API）

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const $ = (id) => document.getElementById(id);
const state = { device: null, session: null, discovered: [], discoveredScanning: false };

// ── 主题 ─────────────────────────────────

const THEMES = [
  { id: 'indigo',  color: '#5b5bd6' },
  { id: 'blue',    color: '#3b82f6' },
  { id: 'teal',    color: '#14b8a6' },
  { id: 'green',   color: '#10b981' },
  { id: 'orange',  color: '#f59e0b' },
  { id: 'rose',    color: '#f43f5e' },
  { id: 'pink',    color: '#ec4899' },
  { id: 'violet',  color: '#8b5cf6' },
  { id: 'blackgold', color: '#c9a227' },
];

function applyTheme(id) {
  const th = THEMES.find((t) => t.id === id) || THEMES[0];
  document.documentElement.style.setProperty('--accent', th.color);
  state.theme = th.id;
  localStorage.setItem('knotyup-theme', th.id);
  document.querySelectorAll('.swatch').forEach((s) => s.classList.toggle('active', s.dataset.theme === th.id));
}

function initThemePicker() {
  const box = $('themeSwatches');
  THEMES.forEach((th) => {
    const b = document.createElement('button');
    b.className = 'swatch';
    b.style.background = th.color;
    b.dataset.theme = th.id;
    b.title = th.id;
    b.onclick = () => {
      applyTheme(th.id);
      box.classList.add('hidden');
    };
    box.appendChild(b);
  });
  $('btnTheme').onclick = (e) => {
    e.stopPropagation();
    box.classList.toggle('hidden');
  };
  document.addEventListener('click', (e) => {
    if (!box.contains(e.target)) box.classList.add('hidden');
  });
  applyTheme(localStorage.getItem('knotyup-theme'));
}

const AVATAR_COLORS = ['#6366f1', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981', '#0ea5e9', '#ef4444', '#14b8a6'];
function avatarColor(id) {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

const PLATFORM_META = {
  macos: { icon: 'i-os-mac', cls: 'mac', label: 'macOS' },
  windows: { icon: 'i-os-win', cls: 'win', label: 'Windows' },
  android: { icon: 'i-os-android', cls: 'droid', label: 'Android' },
  linux: { icon: 'i-devices', cls: 'linux', label: 'Linux' },
};

// ── 确认弹窗（Promise 化） ────────────────

function showConfirm(title, msg, okText, cancelText, members = null) {
  return new Promise((resolve) => {
    const modal = $('confirmModal');
    const list = $('confirmList');
    $('confirmTitle').textContent = title;
    $('confirmMsg').textContent = msg;
    $('confirmOk').textContent = okText;
    $('confirmCancel').textContent = cancelText;
    // 可选设备勾选列表（群主转让）
    let selected = null;
    list.innerHTML = '';
    if (members && members.length) {
      list.classList.remove('hidden');
      members.forEach((m) => {
        const label = document.createElement('label');
        label.innerHTML = `<input type="checkbox" value="${m.id}"><span>${escapeHtml(m.name)}</span>`;
        const box = label.querySelector('input');
        box.onchange = () => {
          if (box.checked) {
            list.querySelectorAll('input').forEach((b) => {
              if (b !== box) b.checked = false;
            });
            selected = m.id;
            $('confirmOk').textContent = t('transfer_leave');
          } else {
            selected = null;
            $('confirmOk').textContent = okText;
          }
        };
        list.appendChild(label);
      });
    } else {
      list.classList.add('hidden');
    }
    modal.classList.remove('hidden');
    const close = (val) => {
      modal.classList.add('hidden');
      $('confirmOk').onclick = $('confirmCancel').onclick = modal.onclick = null;
      resolve(val);
    };
    $('confirmOk').onclick = () => close(selected === null ? true : { transfer: selected });
    $('confirmCancel').onclick = () => close(null);
    modal.onclick = (e) => {
      if (e.target === modal) close(null);
    };
  });
}

function toast(msg) {
  // 后端错误码 → 本地化文案；未知内容原样展示
  const codeMap = {
    ALREADY_IN_GROUP: t('already_in_group'),
    OWNER_ONLY: t('owner_only'),
    WRONG_PIN: t('wrong_pin'),
    PROMOTED: t('promoted'),
    RECONNECTING: t('reconnecting'),
    RECONNECTED: t('reconnected'),
  };
  if (codeMap[msg]) msg = codeMap[msg];
  else if (/^连接失败|^Connection|^协议错误/.test(msg)) msg = t('join_failed');
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('show'), 2200);
}

function fmtTime(ms) {
  const d = new Date(ms);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  return sameDay ? hm : `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// ── 视图渲染 ──────────────────────────────

function renderSession() {
  const s = state.session;
  $('viewHome').classList.toggle('hidden', !!s);
  $('viewGroup').classList.toggle('hidden', !s);
  // 群信息分享按钮：仅群页面显示（不分角色）；离开时同时收起弹层
  $('sharePicker').classList.toggle('hidden', !s);
  if (!s) $('sharePop').classList.add('hidden');
  if (s) {
    // 群主显示「解散群」，成员显示「退出群」
    $('btnLeave').textContent = t(s.role === 'owner' ? 'dissolve' : 'leave');
    renderMembers();
    renderSharePop();
  }
}

// ── 群信息分享弹层 ───────────────────────

// ── 群信息分享弹层（上：入群二维码；下：地址/PIN） ──

function groupQrPayload(s) {
  return JSON.stringify({ v: 1, app: 'knotyup', addr: s.addr, pin: s.pin });
}

function renderSharePop() {
  const s = state.session;
  const pop = $('sharePop');
  if (!s) {
    pop.innerHTML = '';
    return;
  }
  pop.innerHTML = `
    <div class="share-qr"><canvas id="qrCanvas" width="196" height="196"></canvas></div>
    <div class="share-row">
      <span class="share-label">${t('group_addr')}</span>
      <span class="share-value">${escapeHtml(s.addr)}</span>
      <button class="share-copy" data-copy="${escapeHtml(s.addr)}" title="${t('copy')}"><svg class="icon sm"><use href="#i-copy"/></svg></button>
    </div>
    <div class="share-row">
      <span class="share-label">${t('group_pin')}</span>
      <span class="share-value">${escapeHtml(s.pin)}</span>
      <button class="share-copy" data-copy="${escapeHtml(s.pin)}" title="${t('copy')}"><svg class="icon sm"><use href="#i-copy"/></svg></button>
    </div>
    <div class="share-hint">${t('qr_hint')}</div>
  `;
  // 绘制二维码
  try {
    const qr = qrcode(0, 'M');
    qr.addData(groupQrPayload(s));
    qr.make();
    const canvas = $('qrCanvas');
    const ctx = canvas.getContext('2d');
    const n = qr.getModuleCount();
    const cell = Math.floor(canvas.width / (n + 4)); // 含 2 模块静区
    const offset = Math.floor((canvas.width - cell * n) / 2);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#1d2129';
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (qr.isDark(y, x)) ctx.fillRect(offset + x * cell, offset + y * cell, cell, cell);
      }
    }
  } catch (e) {
    console.error('qr render failed', e);
  }
  pop.querySelectorAll('.share-copy').forEach((btn) => {
    btn.onclick = () => navigator.clipboard.writeText(btn.dataset.copy).then(() => toast(t('copied')));
  });
}

function renderMembers() {
  const s = state.session;
  if (!s) return;
  const list = $('memberList');
  list.innerHTML = '';
  const isOwner = s.role === 'owner';
  s.members.forEach((m, i) => {
    const isSelf = state.device && m.id === state.device.id;
    const meta = PLATFORM_META[m.platform] || PLATFORM_META.linux;
    const li = document.createElement('li');
    li.className = 'member-item';
    li.style.setProperty('--i', i);
    li.innerHTML = `
      <div class="avatar os-${meta.cls}">
        <svg class="icon"><use href="#${meta.icon}"/></svg>
      </div>
      <div class="member-meta">
        <div class="member-name" title="${escapeHtml(m.name)}">
          <span class="name-text">${escapeHtml(m.name)}</span>
          ${isSelf ? '<svg class="icon sm self-mark" title="This device"><use href="#i-devices"/></svg>' : ''}
          ${i === 0 ? '<span class="owner" title="Owner"><svg class="icon sm"><use href="#i-crown"/></svg></span>' : ''}
        </div>
        <div class="member-sub">${meta.label}</div>
      </div>
      ${isOwner && !isSelf ? `<button class="kick" data-id="${m.id}">${t('kick')}</button>` : ''}
    `;
    list.appendChild(li);
  });
  $('memberCount').textContent = String(s.members.length);

  list.querySelectorAll('.kick').forEach((btn) => {
    btn.onclick = async () => {
      const id = btn.dataset.id;
      const m = s.members.find((x) => x.id === id);
      const name = m ? m.name : id.slice(0, 6);
      const ok = await showConfirm(
        t('kick'), t('confirm_kick_msg', { name }), t('confirm_ok'), t('confirm_cancel')
      );
      if (!ok) return;
      try {
        await invoke('kick_device', { deviceId: id });
      } catch (e) {
        toast(String(e));
      }
    };
  });
}

let clipItems = [];
function renderClipboard() {
  const list = $('clipList');
  list.innerHTML = '';
  if (clipItems.length === 0) {
    list.innerHTML = `<li class="empty">${t('empty_1')}<br/><span>${t('empty_2')}</span></li>`;
    return;
  }
  clipItems.forEach((item, i) => {
    const li = document.createElement('li');
    li.className = 'clip-item';
    const fromName = nameOf(item.from);
    li.innerHTML = `
      <div class="clip-meta">
        <span class="clip-from">${escapeHtml(fromName)}</span>
        <span class="clip-time">${fmtTime(item.at_ms)}</span>
      </div>
      <div class="clip-text">${escapeHtml(item.text)}</div>
    `;
    li.onclick = async () => {
      try {
        await invoke('copy_history_item', { index: i });
        toast(t('copied_short'));
      } catch (e) {
        toast(String(e));
      }
    };
    list.appendChild(li);
  });
}

function nameOf(deviceId) {
  if (state.device && deviceId === state.device.id) return state.device.name;
  const s = state.session;
  const m = s && s.members.find((m) => m.id === deviceId);
  return m ? m.name : deviceId.slice(0, 6);
}

// ── 局域网发现 ────────────────────────────

function renderDiscovered() {
  const box = $('discoveredList');
  box.innerHTML = '';
  const groups = state.discovered.filter((g) => {
    // 过滤本机 IP 的群（同机多实例测试时不显示自己）
    if (state.localIp && g.addr.startsWith(state.localIp + ':')) return false;
    return !state.session || g.addr !== state.session.addr;
  });
  if (groups.length === 0) {
    box.innerHTML = `
      <div class="discovered-empty">
        <svg class="icon"><use href="#i-devices"/></svg>
        <span>${t('no_discovered')}</span>
      </div>`;
    return;
  }
  groups.forEach((g, i) => {
    const el = document.createElement('button');
    el.className = 'discovered-item';
    el.style.setProperty('--i', i);
    el.innerHTML = `
      <span class="discovered-dot"></span>
      <div class="discovered-meta">
        <span class="discovered-name">${escapeHtml(g.name)}</span>
        <span class="discovered-addr">${escapeHtml(g.addr)}</span>
      </div>
    `;
    el.onclick = () => openPinDialog(g);
    box.appendChild(el);
  });
}

// ── 加入群（PIN）弹窗：点击发现的群直接弹起 ──

function openPinDialog(group) {
  const modal = $('pinModal');
  const input = $('pinInput');
  $('pinTitle').textContent = t('join_group');
  $('pinAddr').textContent = `${group.name} · ${group.addr}`;
  input.value = '';
  modal.classList.remove('hidden');
  input.focus();
  const close = () => {
    modal.classList.add('hidden');
    $('pinJoin').onclick = $('pinCancel').onclick = modal.onclick = null;
  };
  $('pinJoin').textContent = t('btn_join');
  $('pinCancel').textContent = t('confirm_cancel');
  const doJoin = async () => {
    const pin = input.value.trim();
    if (!pin) return toast(t('fill_addr_pin'));
    $('pinJoin').disabled = true;
    try {
      await invoke('join_group', { addr: group.addr, pin });
      close();
      toast(t('joined'));
      await refresh();
    } catch (e) {
      toast(String(e));
    } finally {
      $('pinJoin').disabled = false;
    }
  };
  $('pinJoin').onclick = doJoin;
  $('pinCancel').onclick = close;
  modal.onclick = (e) => {
    if (e.target === modal) close();
  };
  input.onkeydown = (e) => e.key === 'Enter' && doJoin();
}

// ── 动作 ─────────────────────────────────

$('btnCreate').onclick = async () => {
  $('btnCreate').disabled = true;
  try {
    const r = await invoke('create_group', {});
    toast(t('group_created', { pin: r.pin }));
    await refresh();
  } catch (e) {
    toast(String(e));
  } finally {
    $('btnCreate').disabled = false;
  }
};

$('btnJoin').onclick = async () => {
  const addr = $('inputAddr').value.trim();
  const pin = $('inputPin').value.trim();
  if (!addr || !pin) return toast(t('fill_addr_pin'));
  $('btnJoin').disabled = true;
  try {
    await invoke('join_group', { addr, pin });
    toast(t('joined'));
    await refresh();
  } catch (e) {
    toast(String(e));
  } finally {
    $('btnJoin').disabled = false;
  }
};
$('inputPin').addEventListener('keydown', (e) => e.key === 'Enter' && $('btnJoin').click());
$('inputAddr').addEventListener('keydown', (e) => e.key === 'Enter' && $('btnJoin').click());

$('btnLeave').onclick = async () => {
  const isOwner = state.session && state.session.role === 'owner';
  let transferTo = null;
  if (isOwner) {
    const others = (state.session.members || []).filter((m) => !state.device || m.id !== state.device.id);
    const r = await showConfirm(
      t('dissolve'),
      t('transfer_hint'),
      t('confirm_ok'), t('confirm_cancel'),
      others
    );
    if (!r) return;
    if (r !== true) transferTo = r.transfer;
  } else {
    const ok = await showConfirm(t('leave'), t('confirm_leave_msg'), t('confirm_ok'), t('confirm_cancel'));
    if (!ok) return;
  }
  try {
    await invoke('leave_group', { transferTo });
    toast(t('left'));
    clipItems = [];
    await refresh();
  } catch (e) {
    toast(String(e));
  }
};

// 群信息分享弹层开合
$('btnShare').onclick = (e) => {
  e.stopPropagation();
  $('sharePop').classList.toggle('hidden');
};
document.addEventListener('click', (e) => {
  const pop = $('sharePop');
  if (!pop.classList.contains('hidden') && !pop.contains(e.target) && e.target !== $('btnShare')) {
    pop.classList.add('hidden');
  }
});

$('btnLang').onclick = (e) => {
  e.stopPropagation();
  $('langMenu').classList.toggle('hidden');
};
document.addEventListener('click', (e) => {
  const menu = $('langMenu');
  if (!menu.classList.contains('hidden') && !menu.contains(e.target)) menu.classList.add('hidden');
});

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

// ── 事件订阅 ─────────────────────────────

// ── 语言菜单（与主题色板一致的弹出交互） ──

function applyLang(lang) {
  currentLang = lang;
  localStorage.setItem('knotyup-lang', lang);
  applyI18n();
  $('btnLang').textContent = LANG_LABEL[lang] || '简';
  document.querySelectorAll('.lang-item').forEach((b) => b.classList.toggle('active', b.dataset.lang === lang));
  renderSession();
  renderClipboard();
  renderDiscovered();
}

function initLangMenu() {
  const menu = $('langMenu');
  LANGS.forEach((lang) => {
    const b = document.createElement('button');
    b.className = 'lang-item';
    b.dataset.lang = lang;
    b.textContent = LANG_LABEL[lang] || lang;
    b.onclick = () => {
      applyLang(lang);
      menu.classList.add('hidden');
    };
    menu.appendChild(b);
  });
  applyLang(LANGS.includes(currentLang) ? currentLang : 'zh-Hans');
}

async function main() {
  applyI18n();
  initThemePicker();
  initLangMenu();

  await listen('knot://members', (e) => {
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
    toast(p === 'GROUP_DISSOLVED' ? t('group_dissolved') : p === 'GROUP_LOST' ? t('group_lost') : t('kicked'));
    state.session = null;
    clipItems = [];
    renderSession();
    renderClipboard();
  });
  await listen('knot://error', (e) => toast(e.payload));
  await listen('knot://toast', (e) => {
    toast(String(e.payload));
    // 升主/重连成功后会话已换新，需要刷新视图
    if (e.payload === 'RECONNECTED' || e.payload === 'PROMOTED') refresh();
  });
  await listen('knot://member-change', (e) => {
    toast(t(e.payload.type === 'joined' ? 'member_joined' : 'member_left', { name: e.payload.name }));
  });
  await listen('knot://discovered', (e) => {
    state.discovered = e.payload;
    renderDiscovered();
  });

  state.discoveredScanning = true;
  renderDiscovered();
  await refresh();
}

main();
