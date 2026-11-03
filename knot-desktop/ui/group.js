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
