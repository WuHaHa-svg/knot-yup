// KnotYup 桌面端公共层：全局状态、Tauri API、主题、语言、弹窗/Toast/Loading、通用工具。
// 无构建步骤：本文件必须最先加载（home.js / group.js / app.js 依赖其中的全局）。

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

// ── 加入群 Loading ────────────────────────

function showLoading() {
  $('loadingText').textContent = t('joining');
  $('loadingModal').classList.remove('hidden');
}

function hideLoading() {
  $('loadingModal').classList.add('hidden');
}

function toast(msg, type = 'info') {
  // 后端错误码 → 本地化文案；未知内容原样展示
  const codeMap = {
    ALREADY_IN_GROUP: t('already_in_group'),
    OWNER_ONLY: t('owner_only'),
    WRONG_PIN: t('wrong_pin'),
    NO_LAN: t('no_lan'),
    PROMOTED: t('promoted'),
    RECONNECTING: t('reconnecting'),
    RECONNECTED: t('reconnected'),
    MIGRATED: t('reconnected'),
    TRANSFER_FAILED: t('transfer_failed'),
    TRANSFERRED: t('left'),
  };
  if (codeMap[msg]) msg = codeMap[msg];
  else if (/^连接失败|^Connection|^协议错误|^入组被拒绝|^发送入组请求失败/.test(msg)) msg = t('join_failed');
  // 兜底：仍是纯 ASCII（后端原始错误串）→ 本地化通用失败文案，避免英文直塞
  else if (/^[\x20-\x7e]+$/.test(msg)) msg = t('op_failed');
  // Toastify 顶部居中提示（z-index 高于弹窗遮罩，不会被挡）
  const colors = {
    info: 'linear-gradient(135deg, #1d2129, #3a4049)',
    success: 'linear-gradient(135deg, #34a97b, #2b8f68)',
    error: 'linear-gradient(135deg, #d94559, #c23346)',
  };
  Toastify({
    text: msg,
    duration: 2200,
    gravity: 'top',
    position: 'center',
    close: false,
    style: {
      background: colors[type] || colors.info,
      borderRadius: '12px',
      boxShadow: '0 10px 30px rgba(16, 24, 40, .28)',
      fontSize: '13px',
      fontWeight: '500',
      padding: '10px 18px',
      maxWidth: '70vw',
    },
  }).showToast();
}

// ── 通用工具 ──────────────────────────────

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

/** 入群前的统一 0.5s 缓冲（对齐安卓端：点击 → Loading → 缓冲 → 执行）。 */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

$('btnLang').onclick = (e) => {
  e.stopPropagation();
  $('langMenu').classList.toggle('hidden');
};
document.addEventListener('click', (e) => {
  const menu = $('langMenu');
  if (!menu.classList.contains('hidden') && !menu.contains(e.target)) menu.classList.add('hidden');
});
