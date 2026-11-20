// KnotYup 桌面端群页面：会话视图切换、成员列表、剪贴板历史、分享弹层、退出/转让。

// ── 视图渲染 ──────────────────────────────

function renderSession() {
  const s = state.session;
  if (!s) membersSig = ''; // 离群后重置指纹，重入群时强制渲染
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
    btn.onclick = () => navigator.clipboard.writeText(btn.dataset.copy).then(() => toast(t('copied'), 'success'));
  });
}

// ── 成员列表 ──────────────────────────────

// 设备列表指纹：内容未变跳过渲染（Pong 每 5s 携带成员快照，避免无谓全量重建 DOM）
let membersSig = '';
function renderMembers() {
  const s = state.session;
  if (!s) return;
  const sig = `${s.role}|` + s.members.map((m) => `${m.id}:${m.name}:${m.platform}`).join(',');
  if (sig === membersSig) return;
  membersSig = sig;
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
          ${m.role === 'owner' ? '<span class="owner" title="Owner"><svg class="icon sm"><use href="#i-crown"/></svg></span>' : ''}
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

// ── 剪贴板历史 ────────────────────────────

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
        toast(t('copied_short'), 'success');
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

// ── 文件共享（两级目录：设备文件夹 → 文件）────────────

let groupFiles = {};   // { device_id: [SharedFile…] }
let localFileIds = new Set();
let fileViewDevice = null; // null=设备列表；否则某 device_id
let activePanel = 'clipboard';

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}

/** 按扩展名映射文件类型 symbol id + 主题色（图标集与配色同安卓 FileTab 一一对应）。 */
function fileIcon(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  const map = {
    img: { color: '#EC4899', exts: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'heic', 'ico'] },
    doc: { color: '#3B82F6', exts: ['doc', 'docx', 'txt', 'md', 'pdf', 'rtf', 'pages'] },
    sheet: { color: '#16A34A', exts: ['xls', 'xlsx', 'csv', 'numbers'] },
    video: { color: '#8B5CF6', exts: ['mp4', 'mov', 'avi', 'mkv', 'webm', 'flv'] },
    audio: { color: '#EA580C', exts: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a'] },
    archive: { color: '#B45309', exts: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2'] },
    apk: { color: '#34A853', exts: ['apk', 'aab'] },
    exe: { color: '#0078D4', exts: ['exe', 'msi'] },
    dmg: { color: '#64748B', exts: ['dmg', 'pkg', 'app'] },
  };
  for (const [id, v] of Object.entries(map)) {
    if (v.exts.includes(ext)) return { icon: `i-${id}`, color: v.color };
  }
  return { icon: 'i-file', color: null };
}

function setPanel(tab) {
  activePanel = tab;
  document.querySelectorAll('.panel-tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  $('paneClipboard').classList.toggle('hidden', tab !== 'clipboard');
  $('paneFiles').classList.toggle('hidden', tab !== 'files');
  if (tab === 'files') renderFiles();
}

function renderFiles() {
  const box = $('fileList');
  if (!box) return;
  box.innerHTML = '';
  // 头部：一级隐藏返回，二级显示返回；共享按钮两级都在
  const backBtn = $('btnFileBack');
  if (backBtn) {
    backBtn.classList.toggle('hidden', !fileViewDevice);
    backBtn.onclick = () => { fileViewDevice = null; renderFiles(); };
  }
  if (!fileViewDevice) {
    // 一级：设备文件夹——以成员表为准，群里有几个设备就有几个文件夹（空的也显示）
    const members = (state.session && state.session.members) || [];
    if (members.length === 0) {
      box.innerHTML = `<div class="empty">${t('empty_file')}</div>`;
      return;
    }
    members.forEach((m) => {
      const files = (groupFiles || {})[m.id] || [];
      const row = document.createElement('button');
      row.className = 'file-folder';
      row.innerHTML = `
        <div class="folder-ic"><svg class="icon"><use href="#i-folder"/></svg></div>
        <span class="folder-name">${escapeHtml(nameOf(m.id))}</span>
        <span class="folder-count">${files.length}</span>
      `;
      row.onclick = () => { fileViewDevice = m.id; renderFiles(); };
      box.appendChild(row);
    });
    return;
  }
  // 二级：某设备的文件（返回按钮已在头部）
  const files = (groupFiles || {})[fileViewDevice] || [];
  if (files.length === 0) {
    box.innerHTML += `<div class="empty">${t('empty_file')}</div>`;
    return;
  }
  const isMine = state.device && fileViewDevice === state.device.id;
  files.forEach((f) => {
    const { icon, color } = fileIcon(f.name);
    const row = document.createElement('div');
    row.className = 'file-item';
    const chip = color
      ? `style="color:${color};background:color-mix(in srgb, ${color} 14%, transparent)"`
      : '';
    row.innerHTML = `
      <div class="file-ic" ${chip}><svg class="icon sm"><use href="#${icon}"/></svg></div>
      <div class="file-meta">
        <div class="file-name" title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</div>
        <div class="file-sub">${fmtSize(f.size)} · ${fmtTime(f.added_at)}</div>
      </div>
    `;
    const actions = document.createElement('div');
    actions.className = 'file-actions';
    if (!isMine) {
      const dl = document.createElement('button');
      dl.className = 'btn-mini';
      dl.title = t('download');
      dl.innerHTML = `<svg class="icon sm"><use href="#i-download"/></svg>`;
      dl.onclick = (e) => { e.stopPropagation(); downloadFile(fileViewDevice, f); };
      actions.appendChild(dl);
    } else if (localFileIds.has(f.file_id)) {
      const del = document.createElement('button');
      del.className = 'btn-mini danger';
      del.title = t('delete_file');
      del.innerHTML = `<svg class="icon sm"><use href="#i-trash"/></svg>`;
      del.onclick = async (e) => {
        e.stopPropagation();
        const ok = await showConfirm(t('delete_file'), t('confirm_delete_file', { name: f.name }), t('confirm_ok'), t('confirm_cancel'));
        if (!ok) return;
        try { await invoke('remove_files', { fileIds: [f.file_id] }); await refresh(); }
        catch (err) { toast(String(err), 'error'); }
      };
      actions.appendChild(del);
    }
    row.appendChild(actions);
    box.appendChild(row);
  });
}

async function downloadFile(seedId, file) {
  try {
    const dest = await invoke('pick_save_path', { defaultName: file.name });
    if (!dest) return;
    toast(t('downloading') || 'Downloading…');
    await invoke('download_file', { seedDeviceId: seedId, srcFileId: file.file_id, dest });
    toast(t('download_done') || 'Downloaded', 'success');
    await refresh();
  } catch (err) {
    toast(String(err), 'error');
  }
}

// tab 头点击
document.querySelectorAll('.panel-tab').forEach((b) => {
  b.onclick = () => setPanel(b.dataset.tab);
});
// 共享文件按钮
const shareBtn = $('btnShareFile');
if (shareBtn) shareBtn.onclick = async () => {
  try {
    const path = await invoke('pick_share_file');
    if (!path) return;
    await invoke('share_file', { path });
    await refresh();
    toast(t('shared') || 'Shared', 'success');
  } catch (err) {
    toast(String(err), 'error');
  }
};

// ── 动作：退出 / 转让 / 分享弹层 ──────────

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
    if (transferTo) {
      // 转让：Loading 遮罩全程静默，终态事件（TRANSFERRED/TRANSFER_FAILED）统一提示并关 Loading
      showLoading('transferring');
    } else {
      toast(t('left'));
    }
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
