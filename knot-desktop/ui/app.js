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

const PLATFORM_LABEL = () => ({
  macos: { 'zh-Hans': 'macOS', 'zh-Hant': 'macOS', en: 'macOS' },
  windows: { 'zh-Hans': 'Windows', 'zh-Hant': 'Windows', en: 'Windows' },
  android: { 'zh-Hans': '安卓', 'zh-Hant': '安卓', en: 'Android' },
  linux: { 'zh-Hans': 'Linux', 'zh-Hant': 'Linux', en: 'Linux' },
});

// ── 确认弹窗（Promise 化） ────────────────

function showConfirm(title, msg, okText, cancelText) {
  return new Promise((resolve) => {
    const modal = $('confirmModal');
    $('confirmTitle').textContent = title;
    $('confirmMsg').textContent = msg;
    $('confirmOk').textContent = okText;
    $('confirmCancel').textContent = cancelText;
    modal.classList.remove('hidden');
    const close = (val) => {
      modal.classList.add('hidden');
      $('confirmOk').onclick = $('confirmCancel').onclick = modal.onclick = null;
      resolve(val);
    };
    $('confirmOk').onclick = () => close(true);
    $('confirmCancel').onclick = () => close(false);
    modal.onclick = (e) => {
      if (e.target === modal) close(false);
    };
  });
}

function toast(msg) {
  // 后端错误码 → 本地化文案；未知内容原样展示
  const codeMap = {
    ALREADY_IN_GROUP: t('already_in_group'),
    OWNER_ONLY: t('owner_only'),
    WRONG_PIN: t('wrong_pin'),
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
  const chip = $('statusChip');
  if (s) {
    chip.classList.add('online');
    $('statusText').textContent = t(s.role === 'owner' ? 'status_owner' : 'status_member');
    // 群主显示「解散群」，成员显示「退出群」
    $('btnLeave').textContent = t(s.role === 'owner' ? 'dissolve' : 'leave');
    renderMembers();
    renderGroupInfo();
  } else {
    chip.classList.remove('online');
    $('statusText').textContent = t('status_offline');
  }
}

function renderMembers() {
  const s = state.session;
  if (!s) return;
  const list = $('memberList');
  list.innerHTML = '';
  const isOwner = s.role === 'owner';
  s.members.forEach((m, i) => {
    const isSelf = state.device && m.id === state.device.id;
    const li = document.createElement('li');
    li.className = 'member-item';
    li.style.setProperty('--i', i);
    li.innerHTML = `
      <div class="avatar" style="background:${avatarColor(m.id)}">${escapeHtml(m.name.charAt(0).toUpperCase())}</div>
      <div class="member-meta">
        <div class="member-name">
          ${escapeHtml(m.name)}
          ${isSelf ? `<span class="you">${t('you')}</span>` : ''}
          ${i === 0 ? '<span class="owner" title="Owner"><svg class="icon sm"><use href="#i-crown"/></svg></span>' : ''}
        </div>
        <div class="member-sub">${(PLATFORM_LABEL()[m.platform] || {})[currentLang] || m.platform}</div>
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

function renderGroupInfo() {
  const s = state.session;
  if (!s) return;
  const info = $('groupInfo');
  if (s.role === 'owner') {
    info.innerHTML = `
      <div class="info-row"><span>${t('group_addr')}</span><b>${escapeHtml(s.addr)}</b><button class="copy-link" data-copy="${escapeHtml(s.addr)}"><svg class="icon sm"><use href="#i-copy"/></svg>${t('copy')}</button></div>
      <div class="info-row"><span>${t('group_pin')}</span><b>${escapeHtml(s.pin)}</b><button class="copy-link" data-copy="${escapeHtml(s.pin)}"><svg class="icon sm"><use href="#i-copy"/></svg>${t('copy')}</button></div>
      <div class="info-row" style="color:var(--text-3)"><span>${t('join_hint')}</span></div>
    `;
  } else {
    info.innerHTML = `
      <div class="info-row"><span>${t('owner_addr')}</span><b>${escapeHtml(s.addr)}</b></div>
      <div class="info-row" style="color:var(--text-3)"><span>${t('member_hint')}</span></div>
    `;
  }
  info.querySelectorAll('.copy-link').forEach((btn) => {
    btn.onclick = () => {
      navigator.clipboard.writeText(btn.dataset.copy).then(() => toast(t('copied')));
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
  const groups = state.discovered.filter((g) => !state.session || g.addr !== state.session.addr);
  if (groups.length === 0) {
    box.innerHTML = `<div class="discovered-empty">${state.discoveredScanning ? t('discovering') : t('no_discovered')}</div>`;
    return;
  }
  groups.forEach((g) => {
    const el = document.createElement('button');
    el.className = 'discovered-item';
    el.innerHTML = `
      <span class="discovered-dot"></span>
      <span class="discovered-name">${escapeHtml(g.name)}</span>
      <span class="discovered-addr">${escapeHtml(g.addr)}</span>
    `;
    el.onclick = () => {
      $('inputAddr').value = g.addr;
      $('inputPin').focus();
    };
    box.appendChild(el);
  });
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
  const ok = await showConfirm(
    isOwner ? t('dissolve') : t('leave'),
    isOwner ? t('confirm_dissolve_msg') : t('confirm_leave_msg'),
    t('confirm_ok'), t('confirm_cancel')
  );
  if (!ok) return;
  try {
    await invoke('leave_group', {});
    toast(t('left'));
    clipItems = [];
    await refresh();
  } catch (e) {
    toast(String(e));
  }
};

$('btnLang').onclick = () => {
  nextLang();
  $('btnLang').textContent = LANG_LABEL[currentLang];
  renderSession();
  renderClipboard();
  renderDiscovered();
};

async function refresh() {
  const s = await invoke('get_state', {});
  state.device = s.device;
  state.session = s.session;
  clipItems = s.history || [];
  renderSession();
  renderClipboard();
  renderDiscovered();
}

// ── 事件订阅 ─────────────────────────────

async function main() {
  $('btnLang').textContent = LANG_LABEL[currentLang];
  applyI18n();
  initThemePicker();

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
    toast(e.payload === 'GROUP_DISSOLVED' ? t('group_dissolved') : t('kicked'));
    state.session = null;
    clipItems = [];
    renderSession();
    renderClipboard();
  });
  await listen('knot://error', (e) => toast(e.payload));
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
