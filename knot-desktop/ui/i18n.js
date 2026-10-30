// KnotYup 国际化：简体中文 / 繁體中文 / English
// 用法：静态文本用 data-i18n / data-i18n-placeholder 属性；动态文本 t(key)

const MESSAGES = {
  'zh-Hans': {
    tagline_1: '把设备', tagline_2: '打成一个结',
    tagline_sub: '在同一局域网内共享剪贴板，让手机成为电脑的触控板。',
    create_group: '创建群', create_desc: '成为群主，生成 PIN 码供其他设备加入。',
    join_group: '加入群', join_desc: '输入群主的地址与 PIN 码。',
    btn_create: '创建群', btn_join: '加入',
    addr_placeholder: '192.168.1.100:35217', pin_placeholder: 'PIN 码',
    status_offline: '未连接', status_owner: '群主 · 已连接', status_member: '已入群',
    devices: '设备', clipboard: '剪贴板', click_hint: '点击条目即可复制',
    discovered: '局域网发现的群', discovering: '正在扫描局域网…', no_discovered: '未发现附近的群',
    leave: '退出群', kick: '移出', you: '本机',
    empty_1: '暂无同步记录', empty_2: '在任一设备上复制点什么试试',
    group_addr: '群地址', group_pin: 'PIN 码', owner_addr: '群主地址',
    join_hint: '其他设备用地址 + PIN 加入本群', member_hint: '你是本群成员',
    promoted: '你已成为新群主', reconnecting: '群主已离开，正在重连…',
    reconnected: '已重新入群', group_lost: '群已失联',
    transfer_leave: '转让并退出', transfer_hint: '勾选要转让给的设备（不勾选则解散群）',
    copy: '复制', copied: '已复制到剪贴板', copied_short: '已复制',
    group_created: '群已创建 · PIN {pin}', joined: '已加入群', left: '已退出群',
    kicked: '你已被群主移出',
    group_dissolved: '群主已解散群',
    confirm_leave_title: '退出群',
    confirm_leave_msg: '确定要退出当前群吗？',
    dissolve: '解散群', confirm_dissolve_msg: '确定要解散群吗？所有成员将被移出。',
    confirm_kick_msg: '确定要将 {name} 移出群吗？',
    confirm_ok: '确定', confirm_cancel: '取消',
    member_joined: '{name} 加入了群', member_left: '{name} 离开了群',
    fill_addr_pin: '请填写群地址和 PIN 码', already_in_group: '已在群中，请先退出',
    owner_only: '只有群主可以移出设备', copy_failed: '复制失败',
    wrong_pin: 'PIN 码不正确', join_failed: '连接失败，请检查地址和网络',
  },
  'zh-Hant': {
    tagline_1: '把裝置', tagline_2: '打成一個結',
    tagline_sub: '在同一區域網路內共享剪貼板，讓手機成為電腦的觸控板。',
    create_group: '建立群', create_desc: '成為群主，產生 PIN 碼供其他裝置加入。',
    join_group: '加入群', join_desc: '輸入群主的位址與 PIN 碼。',
    btn_create: '建立群', btn_join: '加入',
    addr_placeholder: '192.168.1.100:35217', pin_placeholder: 'PIN 碼',
    status_offline: '未連線', status_owner: '群主 · 已連線', status_member: '已加入',
    devices: '裝置', clipboard: '剪貼板', click_hint: '點擊條目即可複製',
    discovered: '區域網路發現的群', discovering: '正在掃描區域網路…', no_discovered: '未發現附近的群',
    leave: '退出群', kick: '移出', you: '本機',
    empty_1: '暫無同步記錄', empty_2: '在任何一台裝置上複製點什麼試試',
    group_addr: '群位址', group_pin: 'PIN 碼', owner_addr: '群主位址',
    join_hint: '其他裝置用位址 + PIN 加入本群', member_hint: '你是本群成員',
    promoted: '你已成為新群主', reconnecting: '群主已離開，正在重連…',
    reconnected: '已重新入群', group_lost: '群已失聯',
    transfer_leave: '轉讓並退出', transfer_hint: '勾選要轉讓給的裝置（不勾選則解散群）',
    copy: '複製', copied: '已複製到剪貼板', copied_short: '已複製',
    group_created: '群已建立 · PIN {pin}', joined: '已加入群', left: '已退出群',
    kicked: '你已被群主移出',
    group_dissolved: '群主已解散群',
    confirm_leave_title: '退出群',
    confirm_leave_msg: '確定要退出當前群嗎？',
    dissolve: '解散群', confirm_dissolve_msg: '確定要解散群嗎？所有成員將被移出。',
    confirm_kick_msg: '確定要將 {name} 移出群嗎？',
    confirm_ok: '確定', confirm_cancel: '取消',
    member_joined: '{name} 加入了群', member_left: '{name} 離開了群',
    fill_addr_pin: '請填寫群位址和 PIN 碼', already_in_group: '已在群中，請先退出',
    owner_only: '只有群主可以移出裝置', copy_failed: '複製失敗',
    wrong_pin: 'PIN 碼不正確', join_failed: '連線失敗，請檢查位址和網路',
  },
  'en': {
    tagline_1: 'Tie your devices', tagline_2: 'into one knot',
    tagline_sub: 'Share clipboard across your local network. Use your phone as a trackpad.',
    create_group: 'Create a Group', create_desc: 'Become the owner and generate a PIN for others to join.',
    join_group: 'Join a Group', join_desc: 'Enter the owner’s address and PIN.',
    btn_create: 'Create Group', btn_join: 'Join',
    addr_placeholder: '192.168.1.100:35217', pin_placeholder: 'PIN',
    status_offline: 'Offline', status_owner: 'Owner · Connected', status_member: 'Connected',
    devices: 'Devices', clipboard: 'Clipboard', click_hint: 'Click an item to copy',
    discovered: 'Nearby groups', discovering: 'Scanning local network…', no_discovered: 'No nearby groups found',
    leave: 'Leave Group', kick: 'Remove', you: 'This device',
    empty_1: 'Nothing synced yet', empty_2: 'Copy something on any device to try it out',
    group_addr: 'Group address', group_pin: 'PIN', owner_addr: 'Owner address',
    join_hint: 'Others join with address + PIN', member_hint: 'You are a member',
    copy: 'Copy', copied: 'Copied to clipboard', copied_short: 'Copied',
    group_created: 'Group created · PIN {pin}', joined: 'Joined group', left: 'Left group',
    kicked: 'You were removed by the owner',
    group_dissolved: 'The owner dissolved the group',
    promoted: 'You are now the owner', reconnecting: 'Owner left, reconnecting…',
    reconnected: 'Reconnected to the group', group_lost: 'Group unreachable',
    transfer_leave: 'Transfer & Leave', transfer_hint: 'Pick a device to transfer ownership (leave empty to dissolve)',
    confirm_leave_title: 'Leave Group',
    confirm_leave_msg: 'Are you sure you want to leave this group?',
    dissolve: 'Dissolve Group', confirm_dissolve_msg: 'Dissolve this group? All members will be removed.',
    confirm_kick_msg: 'Remove {name} from the group?',
    confirm_ok: 'Confirm', confirm_cancel: 'Cancel',
    member_joined: '{name} joined the group', member_left: '{name} left the group',
    fill_addr_pin: 'Please enter address and PIN', already_in_group: 'Already in a group — leave first',
    owner_only: 'Only the owner can remove devices', copy_failed: 'Copy failed',
    wrong_pin: 'Incorrect PIN', join_failed: 'Connection failed — check address and network',
  },
};

const LANGS = ['zh-Hans', 'zh-Hant', 'en'];
const LANG_LABEL = { 'zh-Hans': '简', 'zh-Hant': '繁', 'en': 'EN' };

let currentLang = localStorage.getItem('knotyup-lang');
if (!LANGS.includes(currentLang)) {
  const nav = navigator.language || 'en';
  currentLang = nav.startsWith('zh') ? (/(TW|HK|MO|Hant)/i.test(nav) ? 'zh-Hant' : 'zh-Hans') : 'en';
}

function t(key, vars) {
  let s = (MESSAGES[currentLang] && MESSAGES[currentLang][key]) || MESSAGES['en'][key] || key;
  if (vars) for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, v);
  return s;
}

function applyI18n() {
  document.querySelectorAll('[data-i18n]').forEach((el) => {
    const parts = el.dataset.i18n.split('|'); // "tagline_1|em:tagline_2" 形式留给模板拼接，这里只处理单键
    if (parts.length === 1) el.textContent = t(parts[0]);
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
    el.placeholder = t(el.dataset.i18nPlaceholder);
  });
  document.documentElement.lang = currentLang === 'zh-Hant' ? 'zh-Hant' : currentLang === 'zh-Hans' ? 'zh-CN' : 'en';
}

function nextLang() {
  currentLang = LANGS[(LANGS.indexOf(currentLang) + 1) % LANGS.length];
  localStorage.setItem('knotyup-lang', currentLang);
  applyI18n();
  document.dispatchEvent(new CustomEvent('langchange'));
}
