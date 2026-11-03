// KnotYup 桌面端首页：局域网发现列表、PIN 弹窗、创建/加入动作。

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
  $('pinName').textContent = group.name;
  $('pinAddr').textContent = group.addr;
  input.value = '';
  modal.classList.remove('hidden');
  setTimeout(() => input.focus(), 50);
  const close = () => {
    modal.classList.add('hidden');
    $('pinJoin').onclick = $('pinCancel').onclick = modal.onclick = null;
  };
  $('pinJoin').textContent = t('btn_join');
  $('pinCancel').textContent = t('confirm_cancel');
  const doJoin = async () => {
    const pin = input.value.trim();
    if (!pin) return toast(t('fill_addr_pin'), 'error');
    $('pinJoin').disabled = true;
    close();
    showLoading(); // Loading 立即出现
    await sleep(500); // 统一入群缓冲
    try {
      await invoke('join_group', { addr: group.addr, pin });
      await refresh();
    } catch (e) {
      hideLoading();
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
  input.onkeydown = (e) => {
    if (e.key === 'Enter') doJoin();
  };
}

// ── 动作：创建 / 手动加入 ─────────────────

$('btnCreate').onclick = async () => {
  $('btnCreate').disabled = true;
  try {
    const r = await invoke('create_group', {});
    toast(t('group_created', { pin: r.pin }), 'success');
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
  if (!addr || !pin) return toast(t('fill_addr_pin'), 'error');
  $('btnJoin').disabled = true;
  showLoading(); // Loading 立即出现
  await sleep(500); // 统一入群缓冲
  try {
    await invoke('join_group', { addr, pin });
    await refresh();
  } catch (e) {
    hideLoading();
    toast(String(e));
  } finally {
    $('btnJoin').disabled = false;
  }
};
$('inputPin').addEventListener('keydown', (e) => e.key === 'Enter' && $('btnJoin').click());
$('inputAddr').addEventListener('keydown', (e) => e.key === 'Enter' && $('btnJoin').click());
