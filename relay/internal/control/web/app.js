const ENDPOINTS = {
  login: '/login',
  logout: '/logout',
  overview: '/v1/overview',
  hosts: '/v1/hosts',
  invites: '/v1/invites',
  events: '/v1/events',
  tenants: '/v1/tenants',
  accountPassword: '/v1/account/password'
};

const REFRESH_MS = 5000;
let refreshTimer = null;
let latestInviteCode = '';
let latestTlsFingerprint = '';
let latestEnrollURI = '';
let latestInviteExpiresAt = 0;
let lastHostsJSON = '';
let lastHostsList = [];
let lastInvitesJSON = '';
let lastInvitesList = [];
let lastEventsList = [];
let lastTenantsList = [];
let adminFocusTenant = null;
let currentRole = 'admin';
let configuredControlURL = '';

const byId = id => document.getElementById(id);

function isLoopbackHostname(host) {
  const h = String(host || '').toLowerCase().replace(/^\[|\]$/g, '');
  return !h || h === 'localhost' || h === '::1' || h === '0:0:0:0:0:0:0:1' || /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(h);
}

function originControlURL() {
  try {
    if (isLoopbackHostname(new URL(window.location.origin).hostname)) return '';
    return window.location.origin;
  } catch (_) {
    return '';
  }
}

function publicControlURL() {
  const configured = String(configuredControlURL || '').replace(/\/+$/, '');
  if (configured) return configured;
  return originControlURL();
}

function tenantHandoffCopy(loginName) {
  const url = publicControlURL();
  if (url) {
    return `已开通 ${loginName}。把 ${url} 和账号交给对方；对方登录后必须先改密码才能发码。不要公开注册入口。`;
  }
  return `已开通 ${loginName}。未配置 public_control_url，且本页是回环地址，不要把 127.0.0.1 发给租户。把 HTTPS 反代地址和账号交给对方；对方登录后必须先改密码才能发码。`;
}

function announce(message) {
  byId('appStatus').textContent = message;
}

function setPanelMessage(id, message) {
  const element = byId(id);
  element.textContent = message || '';
  element.hidden = !message;
}

async function request(path, options = {}, redirectOnUnauthorized = true) {
  const headers = { Accept: 'application/json', ...(options.headers || {}) };
  if (options.body || ['POST', 'PUT', 'PATCH', 'DELETE'].includes(options.method)) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(path, { ...options, headers, credentials: 'include' });

  if (!response.ok) {
    if (response.status === 401 && redirectOnUnauthorized) {
      showLogin('会话已过期。请重新登录。');
    }
    const raw = await response.text();
    let detail = raw;
    try { detail = JSON.parse(raw).error || raw; } catch (_) {}
    const error = new Error(detail || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }

  const raw = await response.text();
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return raw; }
}

function stopRefresh() {
  if (refreshTimer) window.clearInterval(refreshTimer);
  refreshTimer = null;
}

function showLogin(message = '') {
  adminFocusTenant = null;
  stopRefresh();
  byId('loginPanel').classList.remove('hidden');
  byId('mainPanel').classList.add('hidden');
  byId('loginErr').textContent = message;
  byId('inpPass').value = '';
  window.requestAnimationFrame(() => byId('inpUser').focus());
}

async function showMain(overview) {
  applyControlRole(overview && overview.role);
  byId('loginPanel').classList.add('hidden');
  byId('mainPanel').classList.remove('hidden');
  renderOverview(overview);
  const jobs = [loadHosts(), loadInvites(), loadEvents()];
  if (currentRole === 'admin') {
    jobs.push(loadDevices(), loadTenants());
  }
  await Promise.allSettled(jobs);
  stopRefresh();
  refreshTimer = window.setInterval(() => {
    loadOverview();
    loadHosts();
    loadInvites();
    loadEvents();
    if (currentRole === 'admin') {
      loadDevices();
      loadTenants();
    }
  }, REFRESH_MS);
}

async function checkSession() {
  try {
    const overview = await request(ENDPOINTS.overview, {}, false);
    await showMain(overview);
  } catch (error) {
    showLogin(error.status === 401 ? '' : '无法连接控制面。请检查 Relay 是否正在运行。');
  }
}

function setButtonState(button, state, label) {
  button.dataset.state = state || '';
  button.disabled = state === 'loading';
  button.setAttribute('aria-busy', String(state === 'loading'));
  button.textContent = label;
}

async function withLoading(button, loadingLabel, task) {
  const idleLabel = button.textContent;
  setButtonState(button, 'loading', loadingLabel);
  try {
    return await task();
  } finally {
    if (button.dataset.state === 'loading') setButtonState(button, '', idleLabel);
  }
}

byId('loginForm').addEventListener('submit', async event => {
  event.preventDefault();
  const user = byId('inpUser').value.trim();
  const password = byId('inpPass').value;
  byId('loginErr').textContent = '';
  byId('inpUser').setAttribute('aria-invalid', String(!user));
  byId('inpPass').setAttribute('aria-invalid', String(!password));

  if (!user || !password) {
    byId('loginErr').textContent = '请输入账号和密码。';
    return;
  }

  try {
    await withLoading(byId('btnLogin'), '登录中', async () => {
      await request(ENDPOINTS.login, {
        method: 'POST',
        body: JSON.stringify({ user, password })
      }, false);
      byId('inpPass').value = '';
      await checkSession();
    });
  } catch (error) {
    byId('loginErr').textContent = error.status === 401
      ? '账号或密码不正确。'
      : '登录请求未完成。请检查控制面连接。';
  }
});

byId('btnLogout').addEventListener('click', async () => {
  try {
    await withLoading(byId('btnLogout'), '退出中', () => request(ENDPOINTS.logout, { method: 'POST' }, false));
  } finally {
    showLogin();
  }
});

function setConnectionState(online) {
  const state = byId('connectionState');
  state.dataset.state = online ? 'online' : 'offline';
  byId('connectionLabel').textContent = online ? '控制面已响应' : '控制面连接中断';
  byId('statControl').textContent = online ? '正常' : '中断';
}

function applyControlRole(role) {
  currentRole = role === 'tenant' ? 'tenant' : 'admin';
  if (currentRole !== 'admin') adminFocusTenant = null;
  const admin = currentRole === 'admin';
  ['devicesSection', 'tenantsSection', 'railTenants'].forEach(id => {
    const element = byId(id);
    if (element) element.hidden = !admin;
  });
  const passwordSection = byId('passwordSection');
  if (passwordSection) passwordSection.hidden = admin;
}

function applyPasswordGate(mustChange) {
  const required = currentRole === 'tenant' && !!mustChange;
  const banner = byId('passwordMustChange');
  if (banner) banner.hidden = !required;
  ['inviteCreateSection', 'hostsSection', 'invitesSection', 'eventsSection'].forEach(id => {
    const element = byId(id);
    if (element && currentRole === 'tenant') element.hidden = required;
  });
  const passwordSection = byId('passwordSection');
  if (passwordSection && currentRole === 'tenant') passwordSection.hidden = false;
  updateHostQuotaBanner(lastHostsList);
}

function renderOverview(data) {
  applyControlRole(data && data.role);
  applyPasswordGate(data && data.mustChangePassword);
  byId('statHosts').textContent = new Intl.NumberFormat('zh-CN').format(Number(data.hosts || 0));
  byId('statInvites').textContent = new Intl.NumberFormat('zh-CN').format(Number(data.invites || 0));
  byId('statUpdated').textContent = `更新于 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date())}`;
  setConnectionState(true);
  renderTlsFingerprint(data && data.tlsFingerprint);
  const hasPublicHost = typeof data.publicHost === 'string' && data.publicHost.trim() !== '';
  byId('publicHostMissing').hidden = hasPublicHost;
  configuredControlURL = typeof data.publicControlURL === 'string' ? data.publicControlURL.trim() : '';
  const missingControl = byId('publicControlURLMissing');
  if (missingControl) {
    missingControl.hidden = currentRole !== 'admin' || !!(configuredControlURL || originControlURL());
  }
  latestAnonymousEnroll = !!data.anonymousEnroll;
  renderAnonymousSwitch();
  renderQuota(data && data.quota);
}

let latestQuota = null;

function inviteQuotaFull() {
  if (!latestQuota) return false;
  const unused = Number(latestQuota.unusedInvites) || 0;
  const maxI = Number(latestQuota.maxUnusedInvites) || 0;
  return maxI > 0 && unused >= maxI;
}

function hostQuotaFull() {
  if (!latestQuota) return false;
  if (latestQuota.hostFull === true) return true;
  const live = Number(latestQuota.liveHosts) || 0;
  const maxH = Number(latestQuota.maxLiveHosts) || 0;
  return maxH > 0 && live >= maxH;
}

function unusedInviteCount() {
  return Number(latestQuota && latestQuota.unusedInvites) || 0;
}

function unusedRetryHint() {
  const n = unusedInviteCount();
  if (n > 0) return `已有 ${n} 张未用码，吊销后让新电脑再点接入，不必再签发。`;
  return '已贴过的未用码仍有效，不必再签发。';
}

function applyInviteQuotaLock() {
  const btn = byId('btnInvite');
  const ttl = byId('inviteTTL');
  const inviteFull = inviteQuotaFull();
  const retryExisting = hostQuotaFull() && unusedInviteCount() > 0;
  if (btn) {
    btn.disabled = false;
    btn.textContent = retryExisting ? '同一电脑换路由' : '创建邀请码';
    if (retryExisting) {
      btn.title = '新电脑用已有未用码再接入。这只给同一电脑换新路由。';
    } else if (inviteFull) {
      btn.title = '未用邀请已满。签发会立刻作废最早那张未用码。';
    } else {
      btn.title = '';
    }
  }
  if (ttl) ttl.disabled = false;
}

function renderQuota(quota) {
  latestQuota = quota || null;
  const hostLabel = byId('statHostsLabel');
  const inviteLabel = byId('statInvitesLabel');
  const hostValue = byId('statHosts');
  const inviteValue = byId('statInvites');
  const hostNote = byId('statHostsNote');
  const inviteNote = byId('statInvitesNote');
  const forge = byId('forgeQuota');
  const format = (n) => new Intl.NumberFormat('zh-CN').format(Number(n) || 0);
  if (!latestQuota) {
    if (hostLabel) hostLabel.textContent = 'Host 总数';
    if (inviteLabel) inviteLabel.textContent = '邀请总数';
    if (hostNote) hostNote.textContent = '含已吊销';
    if (inviteNote) inviteNote.textContent = '含历史记录';
    if (forge) forge.textContent = '一次性 · 仅显示一次 · 最长 24 小时';
    updateHostQuotaBanner(lastHostsList);
    applyInviteQuotaLock();
    return;
  }
  const live = Number(latestQuota.liveHosts) || 0;
  const maxH = Number(latestQuota.maxLiveHosts) || 0;
  const unused = Number(latestQuota.unusedInvites) || 0;
  const maxI = Number(latestQuota.maxUnusedInvites) || 0;
  if (hostLabel) hostLabel.textContent = '已接入';
  if (hostValue) hostValue.textContent = format(live);
  if (hostNote) hostNote.textContent = maxH > 0 ? `上限 ${maxH}` : '未吊销名额';
  if (inviteLabel) inviteLabel.textContent = '未用邀请';
  if (inviteValue) inviteValue.textContent = format(unused);
  if (inviteNote) inviteNote.textContent = maxI > 0 ? `上限 ${maxI}` : '未过期未吊销';
  if (forge) {
    if (hostQuotaFull() && unused > 0) {
      forge.textContent = `已有 ${unused} 张未用码。新电脑先吊销再接入，不必再签发。同一电脑换路由才点下面。`;
    } else if (inviteQuotaFull()) {
      forge.textContent = `一次性 · 最长 24 小时 · 未用邀请 ${unused} / ${maxI} · 再签发会作废最早未用码`;
    } else {
      forge.textContent = `一次性 · 最长 24 小时 · 未用邀请 ${unused} / ${maxI}`;
    }
  }
  updateHostQuotaBanner(lastHostsList);
  applyInviteQuotaLock();
}

function inviteCreateError(error) {
  const raw = String((error && error.message) || '');
  if (error && error.status === 409 && raw.includes('invite limit')) {
    return '未用邀请已满。请确认后作废最早未用码再签发，或等电脑接入、手动吊销未用码。';
  }
  if (error && error.status === 409 && raw.includes('host limit')) {
    return '接入名额已满。请先吊销不用的电脑。已贴进插件但没接上的码仍有效，不必再签发。';
  }
  return `邀请码未创建：${raw}`;
}

function renderTlsFingerprint(value) {
  const fingerprint = typeof value === 'string' ? value.trim().toLowerCase() : '';
  const valid = /^[0-9a-f]{64}$/.test(fingerprint);
  latestTlsFingerprint = valid ? fingerprint : '';
  byId('tlsFingerprint').textContent = latestTlsFingerprint;
  byId('tlsFingerprintBlock').hidden = !valid;
  byId('tlsFingerprintMissing').hidden = valid;
}

async function loadOverview() {
  try {
    renderOverview(await request(ENDPOINTS.overview));
  } catch (_) {
    setConnectionState(false);
  }
}

function formatTime(seconds) {
  if (!seconds) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  }).format(new Date(seconds * 1000));
}

function formatLastSeen(seconds) {
  if (!seconds) return '从未心跳';
  const then = Number(seconds) * 1000;
  if (!Number.isFinite(then) || then <= 0) return '从未心跳';
  const diff = Date.now() - then;
  const abs = formatTime(seconds);
  if (diff < 45 * 1000) return '刚刚';
  if (diff < 3600 * 1000) return `${Math.max(1, Math.floor(diff / 60000))} 分钟前`;
  if (diff < 86400 * 1000) return `${Math.max(1, Math.floor(diff / 3600000))} 小时前`;
  if (diff < 30 * 86400 * 1000) return `${Math.max(1, Math.floor(diff / 86400000))} 天前`;
  return abs;
}

function hostOccupiesSlot(host) {
  return Boolean(host) && !host.revokedAt;
}

function hostLastSeenValue(host) {
  return Number(host && host.lastSeenAt) || 0;
}

function compareHostsForRevoke(a, b) {
  const aRev = Boolean(a && a.revokedAt);
  const bRev = Boolean(b && b.revokedAt);
  if (aRev !== bRev) return aRev ? 1 : -1;
  const aOn = a && a.online === true && !aRev;
  const bOn = b && b.online === true && !bRev;
  if (aOn !== bOn) return aOn ? 1 : -1;
  const aSeen = hostLastSeenValue(a);
  const bSeen = hostLastSeenValue(b);
  if (aSeen !== bSeen) return aOn ? bSeen - aSeen : aSeen - bSeen;
  return (Number(a && a.createdAt) || 0) - (Number(b && b.createdAt) || 0);
}

function sortHostsForDisplay(list) {
  return (Array.isArray(list) ? list.slice() : []).sort(compareHostsForRevoke);
}

function suggestedRevokeHost(list) {
  if (!hostQuotaFull()) return null;
  const live = (Array.isArray(list) ? list : []).filter(hostOccupiesSlot);
  if (!live.length) return null;
  live.sort(compareHostsForRevoke);
  const first = live[0];
  if (!first || first.online === true) return null;
  return first;
}

function createElement(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function createEmptyState(title, detail) {
  const root = createElement('div', 'empty-state');
  const mark = createElement('span', 'empty-mark');
  mark.setAttribute('aria-hidden', 'true');
  root.append(mark, createElement('strong', '', title), createElement('p', '', detail));
  return root;
}

function appendHeaderRow(table, labels) {
  const thead = document.createElement('thead');
  const row = document.createElement('tr');
  labels.forEach(label => row.append(createElement('th', '', label)));
  thead.append(row);
  table.append(thead);
}

function appendCell(row, label, content, className = '') {
  const cell = createElement('td', className);
  cell.dataset.label = label;
  if (content instanceof Node) cell.append(content);
  else cell.textContent = content;
  row.append(cell);
}

function hostStatusBadge(host, suggested) {
  if (host.revokedAt) return createStatus('已吊销', 'muted');
  if (Number(host.suspendedUntil) * 1000 > Date.now()) return createStatus('挂起（日流量）', 'warning');
  if (host.online) return createStatus('在线');
  if (currentRole === 'tenant') {
    return createStatus(suggested ? '离线（占名额）· 建议吊销' : '离线（占名额）', 'warning');
  }
  return createStatus(suggested ? '离线 · 建议吊销' : '离线', 'warning');
}

function createStatus(label, tone = '') {
  const badge = createElement('span', 'status-badge', label);
  if (tone) badge.dataset.tone = tone;
  return badge;
}

function createDangerButton(label, ariaLabel, action) {
  const button = createElement('button', 'button button--danger', label);
  button.type = 'button';
  button.setAttribute('aria-label', ariaLabel);
  button.addEventListener('click', () => {
    if (!window.confirm(`${ariaLabel}？`)) return;
    withLoading(button, `${label}中`, action).catch(error => {
      announce(`操作失败：${error.message}`);
    });
  });
  return button;
}

function createActionGroup(buttons) {
  const wrap = createElement('div', 'cell-actions');
  buttons.forEach(button => wrap.append(button));
  return wrap;
}

function hostQuotaBannerCopy(list, suggested) {
  const retry = unusedRetryHint();
  if (suggested) {
    const name = String(suggested.hostName || suggested.id || '一台离线电脑').trim() || '一台离线电脑';
    return `已接入名额已满。「${name}」离线最久仍占名额。吊销后新电脑才能接入。${retry}同一电脑贴新码不用新名额。换到别的 Relay 不必先在这里吊销，插件会尝试自动释放。`;
  }
  const occupying = (Array.isArray(list) ? list : []).filter(hostOccupiesSlot);
  if (!occupying.length) {
    return `已接入名额已满。离线电脑仍占名额，列表会排在前面。换新电脑请先吊销一台。${retry}同一电脑贴新码不用新名额。换到别的 Relay 不必先在这里吊销，插件会尝试自动释放。`;
  }
  return `已接入名额已满。当前电脑都在线，请在列表里选一台不用的吊销。${retry}同一电脑贴新码不用新名额。`;
}

function passwordChangeRequired() {
  const banner = byId('passwordMustChange');
  return currentRole === 'tenant' && Boolean(banner && !banner.hidden);
}

function updateHostQuotaBanner(list) {
  const banner = byId('hostQuotaFull');
  if (!banner) return;
  const full = hostQuotaFull() && !passwordChangeRequired();
  banner.hidden = !full;
  banner.replaceChildren();
  if (!full) return;
  const suggested = suggestedRevokeHost(list);
  banner.append(createElement('span', '', hostQuotaBannerCopy(list, suggested)));
  if (!suggested || !suggested.id) return;
  const button = createElement('button', 'button button--danger', '吊销这台');
  button.type = 'button';
  const label = hostActionLabel(suggested);
  button.setAttribute('aria-label', `吊销 Host ${label}`);
  button.addEventListener('click', () => {
    if (!window.confirm(`吊销 Host ${label}？这台电脑的云端路由会立刻失效。`)) return;
    withLoading(button, '吊销中', () => revokeLiveHost(suggested)).catch(error => {
      announce(`操作失败：${error.message}`);
    });
  });
  banner.append(button);
}

async function revokeLiveHost(host) {
  const label = hostActionLabel(host);
  await request(`${ENDPOINTS.hosts}/${encodeURIComponent(host.id)}/revoke`, {
    method: 'POST', body: '{}'
  });
  lastHostsJSON = '';
  await Promise.all([loadHosts(), loadInvites(), loadOverview(), loadEvents()]);
  announce(`${label} 已吊销。插件会停止重连。${revokeHostFollowUp()}`);
}

function revokeHostFollowUp() {
  const unused = unusedInviteCount();
  if (unused > 0) {
    return `名额已空出。有 ${unused} 张未用码，让新电脑再点接入，不必再签发。被吊销的电脑若要回来才需要新码。`;
  }
  return '名额已空出。新电脑或这台要回来，再签发接入码。';
}

function hostTableLabels() {
  return currentRole === 'admin'
    ? ['Host', '租户', '状态', '最近心跳', '接入', '操作']
    : ['Host', '状态', '最近心跳', '接入', '操作'];
}

function appendHostTable(parent, hosts, suggested, caption) {
  const table = document.createElement('table');
  table.append(createElement('caption', 'sr-only', caption));
  appendHeaderRow(table, hostTableLabels());
  const tbody = document.createElement('tbody');
  hosts.forEach(host => tbody.append(hostTableRow(host, suggested)));
  table.append(tbody);
  parent.append(table);
}

function hostTableRow(host, suggested) {
  const row = document.createElement('tr');
  const identity = createElement('div', 'cell-stack');
  identity.append(createElement('strong', '', host.hostName || host.id || '未命名 Host'));
  identity.append(createElement('code', '', host.id || '—'));
  appendCell(row, 'Host', identity);
  if (currentRole === 'admin') {
    appendCell(row, '租户', tenantLabel(host.userId, host.loginName));
  }
  appendCell(row, '状态', hostStatusBadge(host, suggested && suggested.id === host.id));
  const seen = createElement('span', 'tnum', formatLastSeen(host.lastSeenAt));
  if (host.lastSeenAt) seen.title = formatTime(host.lastSeenAt);
  appendCell(row, '最近心跳', seen, 'tnum');
  appendCell(row, '接入', formatTime(host.createdAt), 'tnum');

  const label = hostActionLabel(host);
  const actions = [];
  if (!host.revokedAt) {
    actions.push(createDangerButton('吊销', `吊销 Host ${label}`, () => revokeLiveHost(host)));
  }
  actions.push(createDangerButton('删除', `删除 Host ${label}`, async () => {
    await request(`${ENDPOINTS.hosts}/${encodeURIComponent(host.id)}/delete`, {
      method: 'POST', body: '{}'
    });
    await Promise.all([loadHosts(), loadInvites(), loadOverview(), loadEvents()]);
    announce(`${label} 已删除。`);
  }));
  appendCell(row, '操作', createActionGroup(actions));
  return row;
}

function renderHosts(list) {
  lastHostsList = Array.isArray(list) ? list : [];
  updateHostQuotaBanner(lastHostsList);
  const root = byId('hosts');
  root.replaceChildren();
  appendAdminFocusNote(root, '电脑');
  const visible = lastHostsList.filter(host => ownedByAdminFocus(host.userId));
  const occupying = sortHostsForDisplay(visible.filter(hostOccupiesSlot));
  const revoked = visible.filter(host => host && host.revokedAt);
  const suggested = suggestedRevokeHost(visible);
  const purge = byId('btnPurgeHosts');
  if (purge) purge.hidden = revoked.length === 0;

  const who = adminFocusTenant ? adminFocusTenant.loginName : '';
  if (!occupying.length && !revoked.length) {
    root.append(createEmptyState(who ? `${who} 还没有 Host` : '暂无 Host', '插件接入后会出现在这里。'));
    return;
  }
  if (!occupying.length) {
    root.append(createEmptyState(who ? `${who} 没有占名额的电脑` : '没有占名额的电脑', '已吊销的在下方，清理后从列表消失。'));
  } else {
    appendHostTable(root, occupying, suggested, '占名额的 Host');
  }
  if (!revoked.length) return;
  const fold = document.createElement('details');
  fold.className = 'revoked-hosts';
  const summary = document.createElement('summary');
  summary.textContent = `已吊销 ${revoked.length} 台`;
  fold.append(summary);
  appendHostTable(fold, sortHostsForDisplay(revoked), null, '已吊销 Host');
  root.append(fold);
}

async function loadHosts() {
  try {
    const list = await request(ENDPOINTS.hosts);
    const next = JSON.stringify(list);
    if (next === lastHostsJSON) return;
    lastHostsJSON = next;
    renderHosts(list);
  } catch (error) {
    setPanelMessage('hostsMessage', `Host 列表未刷新：${error.message}`);
  }
}

function hostActionLabel(host) {
  const name = String(host.hostName || '').trim();
  const id = String(host.id || '').trim();
  if (name && id && name !== id) return `${name}（${id}）`;
  return name || id || '未命名 Host';
}

function consumedHostLabel(invite) {
  if (!invite.consumedAt) return '—';
  const name = String(invite.consumedHostName || '').trim();
  const id = String(invite.consumedHostId || '').trim();
  if (!name && !id) return '已接入';
  return hostActionLabel({ hostName: name, id });
}

function inviteState(invite) {
  if (invite.revokedAt) return ['已吊销', 'muted'];
  if (invite.consumedAt) return ['已消费', 'muted'];
  if (Number(invite.expiresAt) * 1000 < Date.now()) return ['已过期', 'warning'];
  return ['有效', ''];
}

function tenantLabel(userId, loginName) {
  if (loginName) return String(loginName);
  if (!userId || userId === 'user-default') return 'admin';
  return String(userId);
}

function adminFocusId() {
  return adminFocusTenant && adminFocusTenant.id ? String(adminFocusTenant.id) : '';
}

function ownedByAdminFocus(userId) {
  const focus = adminFocusId();
  if (!focus) return true;
  return String(userId || '') === focus;
}

function refreshAdminFocusViews() {
  renderHosts(lastHostsList);
  renderInvites(lastInvitesList);
  renderEvents(lastEventsList);
  renderTenants(lastTenantsList);
}

function focusAdminTenant(row) {
  if (currentRole !== 'admin' || !row || !row.id) return;
  adminFocusTenant = { id: String(row.id), loginName: String(row.loginName || row.id) };
  refreshAdminFocusViews();
  const hosts = byId('hostsSection');
  if (hosts) hosts.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function clearAdminTenantFocus() {
  adminFocusTenant = null;
  refreshAdminFocusViews();
}

function appendAdminFocusNote(parent, noun) {
  if (currentRole !== 'admin' || !adminFocusTenant) return;
  const note = createElement('p', 'filter-note');
  note.append(document.createTextNode(`只看 ${adminFocusTenant.loginName} 的${noun}。优先让对方自己登录处理；紧急时下面仍可吊销。`));
  const clear = createElement('button', 'button button--quiet', '显示全部');
  clear.type = 'button';
  clear.addEventListener('click', clearAdminTenantFocus);
  note.append(clear);
  parent.append(note);
}

function inviteIsLiveUnused(invite) {
  return inviteState(invite)[0] === '有效';
}

function inviteBindsOccupyingHost(invite) {
  return Boolean(invite && invite.consumedHostLive && invite.consumedHostId);
}

function inviteTableLabels() {
  return currentRole === 'admin'
    ? ['邀请 ID', '租户', '状态', '接入电脑', '创建时间', '过期时间', '操作']
    : ['邀请 ID', '状态', '接入电脑', '创建时间', '过期时间', '操作'];
}

function appendInviteTable(parent, invites, oldestLive, caption) {
  const table = document.createElement('table');
  table.append(createElement('caption', 'sr-only', caption));
  appendHeaderRow(table, inviteTableLabels());
  const tbody = document.createElement('tbody');
  invites.forEach(invite => tbody.append(inviteTableRow(invite, oldestLive)));
  table.append(tbody);
  parent.append(table);
}

function inviteTableRow(invite, oldestLive) {
  const row = document.createElement('tr');
  const [status, tone] = inviteState(invite);
  const shortId = String(invite.id).slice(0, 8);
  appendCell(row, '邀请 ID', createElement('code', 'cell-secondary', `${shortId}…`));
  if (currentRole === 'admin') {
    appendCell(row, '租户', tenantLabel(invite.userId, invite.loginName));
  }
  const statusLabel = oldestLive && oldestLive.id === invite.id ? '有效 · 再签发会作废' : status;
  appendCell(row, '状态', createStatus(statusLabel, tone));
  appendCell(row, '接入电脑', consumedHostLabel(invite));
  appendCell(row, '创建时间', formatTime(invite.createdAt), 'tnum');
  appendCell(row, '过期时间', formatTime(invite.expiresAt), 'tnum');

  const actions = [];
  if (status === '有效') {
    actions.push(createDangerButton('吊销', `吊销邀请 ${shortId}`, async () => {
      await request(`${ENDPOINTS.invites}/${encodeURIComponent(invite.id)}/revoke`, {
        method: 'POST', body: '{}'
      });
      await Promise.all([loadInvites(), loadOverview(), loadEvents()]);
      announce('邀请码已吊销。');
    }));
  }
  if (invite.consumedHostLive && invite.consumedHostId && !passwordChangeRequired()) {
    const host = { id: invite.consumedHostId, hostName: invite.consumedHostName };
    actions.push(createDangerButton('吊销电脑', `吊销 Host ${hostActionLabel(host)}`, () => revokeLiveHost(host)));
  }
  actions.push(createDangerButton('删除', `删除邀请 ${shortId}`, async () => {
    await request(`${ENDPOINTS.invites}/${encodeURIComponent(invite.id)}/delete`, {
      method: 'POST', body: '{}'
    });
    await Promise.all([loadInvites(), loadOverview(), loadEvents()]);
    announce('邀请记录已删除。');
  }));
  appendCell(row, '操作', createActionGroup(actions));
  return row;
}

function renderInvites(list) {
  lastInvitesList = Array.isArray(list) ? list : [];
  const root = byId('invites');
  root.replaceChildren();
  appendAdminFocusNote(root, '邀请');
  const all = lastInvitesList.filter(invite => ownedByAdminFocus(invite.userId));
  const unused = all.filter(inviteIsLiveUnused);
  const occupying = all.filter(inviteBindsOccupyingHost);
  const stale = all.filter(invite => !inviteIsLiveUnused(invite) && !inviteBindsOccupyingHost(invite));
  const oldestLive = inviteQuotaFull() ? oldestUnusedInvite(all) : null;
  const purge = byId('btnPurgeInvites');
  if (purge) purge.hidden = stale.length === 0;

  const who = adminFocusTenant ? adminFocusTenant.loginName : '';
  if (!unused.length && !occupying.length && !stale.length) {
    root.append(createEmptyState(who ? `${who} 还没有邀请` : '暂无邀请', '创建邀请码后会出现在这里。'));
    return;
  }
  if (!unused.length && !occupying.length) {
    root.append(createEmptyState(who ? `${who} 没有未用或已接入的邀请` : '没有未用或已接入的邀请', '失效记录在下方，清理后从列表消失。'));
  } else {
    if (unused.length) appendInviteTable(root, unused, oldestLive, '未用邀请');
    if (occupying.length) appendInviteTable(root, occupying, null, '已接入邀请');
  }
  if (!stale.length) return;
  const fold = document.createElement('details');
  fold.className = 'stale-invites';
  const summary = document.createElement('summary');
  summary.textContent = `失效 ${stale.length} 条`;
  fold.append(summary);
  appendInviteTable(fold, stale, null, '失效邀请');
  root.append(fold);
}

async function loadInvites() {
  try {
    const list = await request(ENDPOINTS.invites);
    const next = JSON.stringify(list);
    if (next === lastInvitesJSON) return;
    lastInvitesJSON = next;
    lastInvitesList = Array.isArray(list) ? list : [];
    renderInvites(list);
  } catch (error) {
    setPanelMessage('invitesMessage', `邀请记录未刷新：${error.message}`);
  }
}

const EVENT_LABELS = {
  'invite.create': '签发邀请',
  'invite.revoke': '吊销邀请',
  'invite.delete': '删除邀请',
  'host.enroll': 'Host 接入',
  'host.revoke': '吊销 Host',
  'host.delete': '删除 Host',
  'tenant.create': '开通租户',
  'tenant.disable': '停用租户',
  'tenant.enable': '恢复租户',
  'tenant.password': '重置租户密码',
  'account.password': '修改密码',
  'auth.login': '登录成功',
  'auth.login.fail': '登录失败'
};

let lastEventsJSON = '';

function eventActionLabel(action) {
  return EVENT_LABELS[action] || action;
}

function eventTargetLabel(ev) {
  const detail = String(ev.detail || '').trim();
  const id = String(ev.targetId || '');
  const shortId = id.length > 12 ? `${id.slice(0, 8)}…` : id;
  if (detail && detail !== id) return `${detail} · ${shortId}`;
  return shortId;
}

function renderEvents(list) {
  lastEventsList = Array.isArray(list) ? list : [];
  const root = byId('events');
  if (!root) return;
  root.replaceChildren();
  appendAdminFocusNote(root, '操作记录');
  const visible = lastEventsList.filter(ev => ownedByAdminFocus(ev.subjectUserId));
  const who = adminFocusTenant ? adminFocusTenant.loginName : '';
  if (!visible.length) {
    root.append(createEmptyState(who ? `${who} 还没有操作记录` : '还没有操作记录', '签发、接入、吊销和登录会出现在这里。接入码和密码不会写入记录。'));
    return;
  }
  const table = document.createElement('table');
  table.className = 'data-table';
  appendHeaderRow(table, currentRole === 'admin'
    ? ['时间', '操作', '对象', '操作者', '租户']
    : ['时间', '操作', '对象', '操作者']);
  const tbody = document.createElement('tbody');
  visible.forEach(ev => {
    const row = document.createElement('tr');
    appendCell(row, '时间', formatTime(ev.at), 'tnum');
    appendCell(row, '操作', eventActionLabel(ev.action));
    appendCell(row, '对象', createElement('code', 'cell-secondary', eventTargetLabel(ev)));
    appendCell(row, '操作者', ev.actorLogin === 'enroll' ? '接入' : (ev.actorLogin || '—'));
    if (currentRole === 'admin') {
      appendCell(row, '租户', tenantLabel(ev.subjectUserId, ev.subjectLogin));
    }
    tbody.append(row);
  });
  table.append(tbody);
  root.append(table);
}

async function loadEvents() {
  try {
    const list = await request(ENDPOINTS.events);
    const next = JSON.stringify(list);
    if (next === lastEventsJSON) return;
    lastEventsJSON = next;
    renderEvents(list);
  } catch (error) {
    setPanelMessage('eventsMessage', `操作记录未刷新：${error.message}`);
  }
}

const INVITE_TTL_ALLOWED = new Set(['30m', '2h', '8h', '24h']);

function inviteTTLValue() {
  const sel = byId('inviteTTL');
  const raw = sel ? String(sel.value || '8h') : '8h';
  return INVITE_TTL_ALLOWED.has(raw) ? raw : '8h';
}

function oldestUnusedInvite(list) {
  const live = (Array.isArray(list) ? list : []).filter(invite => inviteState(invite)[0] === '有效');
  live.sort((a, b) => (Number(a.createdAt) || 0) - (Number(b.createdAt) || 0));
  return live[0] || null;
}

function confirmReplaceOldestUnused() {
  const oldest = oldestUnusedInvite(lastInvitesList);
  const when = oldest ? formatTime(oldest.createdAt) : '';
  const who = when ? `${when} 创建的那张未用码` : '最早那张未用码';
  return window.confirm(`未用邀请已满。签发会立刻作废${who}，拿着旧码的电脑将无法接入。继续？`);
}

function inviteExpiryCopy(expiresAt, controlUrl) {
  const at = Number(expiresAt) || 0;
  const packed = Boolean(controlUrl);
  if (at <= 0) {
    return packed
      ? '粘贴进电脑插件「远端连接」。接入串含控制台地址，吊销后可从插件打开。仅显示这一次，关闭后无法找回。'
      : '粘贴进电脑插件「远端连接」。仅显示这一次，关闭后无法找回。';
  }
  return packed
    ? `有效至 ${formatTime(at)}。粘贴进电脑插件「远端连接」。接入串含控制台地址，吊销后可从插件打开。仅显示这一次，关闭后无法找回。`
    : `有效至 ${formatTime(at)}。粘贴进电脑插件「远端连接」。仅显示这一次，关闭后无法找回。`;
}

function confirmMintWhenCapped() {
  const hostFull = hostQuotaFull();
  const inviteFull = inviteQuotaFull();
  const unused = unusedInviteCount();
  if (hostFull && inviteFull) {
    return window.confirm('已接入名额已满，且未用码已满。签发会作废最早那张未用码（新电脑可能已经贴过）。新电脑应先吊销再用已贴的码。继续签发的码只适合同一电脑换新路由。仍要签发？');
  }
  if (inviteFull && !confirmReplaceOldestUnused()) return false;
  if (hostFull && unused > 0) {
    return window.confirm(`已有 ${unused} 张未用码。新电脑吊销一台后用已贴的码再接入，不必再签发。继续签发的码只适合同一电脑换新路由。仍要签发？`);
  }
  if (hostFull) {
    return window.confirm('已接入名额已满。新电脑要先吊销一台。这张码只适合同一电脑换新路由。继续签发？');
  }
  return true;
}

byId('btnInvite').addEventListener('click', async () => {
  byId('inviteMessage').textContent = '';
  const replaceOldestUnused = inviteQuotaFull();
  if (!confirmMintWhenCapped()) {
    return;
  }
  try {
    await withLoading(byId('btnInvite'), '创建中', async () => {
      const result = await request(ENDPOINTS.invites, {
        method: 'POST', body: JSON.stringify({ ttl: inviteTTLValue(), replaceOldestUnused })
      });
      latestInviteCode = result.inviteCode || '';
      latestEnrollURI = result.enroll || '';
      latestInviteExpiresAt = Number(result.expiresAt) || 0;
      const paste = latestEnrollURI || latestInviteCode;
      byId('inviteCode').textContent = paste;
      const expiry = byId('inviteExpiryNote');
      if (expiry) expiry.textContent = inviteExpiryCopy(latestInviteExpiresAt, result.controlUrl);
      const bare = byId('inviteBare');
      if (bare) bare.textContent = latestInviteCode;
      byId('enrollBlock').hidden = !latestEnrollURI;
      byId('inviteResult').hidden = false;
      if (latestEnrollURI) {
        const canvas = byId('inviteQr');
        const ok = qrRender(canvas, latestEnrollURI);
        if (!ok) {
          canvas.style.display = 'none';
          byId('inviteQrNote')?.remove?.();
        } else {
          canvas.style.display = '';
        }
      }
      byId('btnCopyInvite').focus();
      await Promise.all([loadInvites(), loadOverview(), loadEvents()]);
      announce(result.replacedInviteId ? '接入码已创建，已作废最早那张未用码。' : '接入码已创建。');
    });
  } catch (error) {
    byId('inviteMessage').textContent = inviteCreateError(error);
  } finally {
    applyInviteQuotaLock();
  }
});

byId('btnPurgeHosts').addEventListener('click', async () => {
  const prompt = currentRole === 'admin'
    ? (adminFocusTenant
      ? '清理会删除所有租户的已吊销记录，不只是当前查看的这一户。还在用的电脑不会动。继续？'
      : '删除全部已吊销的 Host 记录？还在用的电脑不会动。')
    : '删除你已吊销的 Host 记录？还在用的电脑不会动。别人的记录不会动。';
  if (!window.confirm(prompt)) return;
  const button = byId('btnPurgeHosts');
  try {
    await withLoading(button, '清理中', async () => {
      const result = await request(`${ENDPOINTS.hosts}/purge`, { method: 'POST', body: '{}' });
      lastHostsJSON = '';
      await Promise.all([loadHosts(), loadOverview(), loadEvents()]);
      const n = Number(result.deleted) || 0;
      const message = n ? `已删除 ${n} 条已吊销 Host。` : '没有已吊销的 Host。要去掉还在用的电脑，点该行「删除」。';
      setPanelMessage('hostsMessage', message);
      announce(message);
    });
  } catch (error) {
    setPanelMessage('hostsMessage', `清理失败：${error.message}`);
  }
});

byId('btnPurgeInvites').addEventListener('click', async () => {
  const prompt = currentRole === 'admin'
    ? (adminFocusTenant
      ? '清理会删除所有租户的失效邀请，不只是当前查看的这一户。未用码和仍占名额的已接入记录会保留。继续？'
      : '删除已过期、已吊销、以及电脑已不占名额的已用邀请？未用码和仍占名额的已接入记录会保留。')
    : '删除你的已过期、已吊销、以及电脑已不占名额的已用邀请？未用码和仍占名额的已接入记录会保留。别人的记录不会动。';
  if (!window.confirm(prompt)) return;
  const button = byId('btnPurgeInvites');
  try {
    await withLoading(button, '清理中', async () => {
      const result = await request(`${ENDPOINTS.invites}/purge`, { method: 'POST', body: '{}' });
      lastInvitesJSON = '';
      await Promise.all([loadInvites(), loadOverview(), loadEvents()]);
      const n = Number(result.deleted) || 0;
      const message = n ? `已删除 ${n} 条失效邀请。` : '没有可清理的失效邀请。仍占名额的已接入记录会保留。';
      setPanelMessage('invitesMessage', message);
      announce(message);
    });
  } catch (error) {
    setPanelMessage('invitesMessage', `清理失败：${error.message}`);
  }
});

async function copyText(value) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(value);
      return;
    } catch (_) {
      // Some hardened loopback browsers expose Clipboard API but deny writes.
    }
  }
  const area = document.createElement('textarea');
  area.value = value;
  area.setAttribute('readonly', '');
  area.className = 'sr-only';
  document.body.append(area);
  area.select();
  document.execCommand('copy');
  area.remove();
}

byId('btnCopyInvite').addEventListener('click', async () => {
  const button = byId('btnCopyInvite');
  try {
    await copyText(latestEnrollURI || latestInviteCode);
    setButtonState(button, 'success', '已复制');
    announce('接入码已复制。');
    window.setTimeout(() => setButtonState(button, '', '复制'), 2500);
  } catch (_) {
    byId('inviteMessage').textContent = '无法写入剪贴板。请手动选择并复制接入码。';
  }
});

byId('btnCopyFingerprint').addEventListener('click', async () => {
  const button = byId('btnCopyFingerprint');
  try {
    await copyText(latestTlsFingerprint);
    setButtonState(button, 'success', '已复制');
    announce('TLS 指纹已复制。');
    window.setTimeout(() => setButtonState(button, '', '复制'), 2500);
  } catch (_) {
    byId('inviteMessage').textContent = '无法写入剪贴板。请手动选择并复制 TLS 指纹。';
  }
});

document.querySelectorAll('.rail-link').forEach(link => {
  link.addEventListener('click', () => {
    const href = link.getAttribute('href');
    document.querySelectorAll('.rail-link').forEach(item => {
      if (item.getAttribute('href') === href) item.setAttribute('aria-current', 'page');
      else item.removeAttribute('aria-current');
    });
    const menu = link.closest('details');
    if (menu) menu.open = false;
  });
});

checkSession();

/* ---- QR 编码器（byte 模式 + 纠错 M + 版本 1..40） ----
 * 三类模块绘制、Reed-Solomon、掩码与评分逻辑参考
 * nayuki/QR-Code-generator (MIT License) 的实现思路重写。
 * 用途：把一次性接入串渲染成二维码，方便测试者扫码搬运到电脑。
 */
const QR_ECC_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
const QR_BLOCKS_PER_LEVEL = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];

function qrRawDataModules(ver) {
  let result = (16 * ver + 128) * ver + 64;
  if (ver >= 2) {
    const numAlign = Math.floor(ver / 7) + 2;
    result -= (25 * numAlign - 10) * numAlign - 55;
    if (ver >= 7) result -= 36;
  }
  return result;
}

function qrCapacityBytes(ver) {
  const total = Math.floor(qrRawDataModules(ver) / 8);
  return total - QR_ECC_PER_BLOCK[ver] * QR_BLOCKS_PER_LEVEL[ver];
}

function qrFitVersion(textLen) {
  for (let ver = 1; ver <= 40; ver++) {
    const countBits = ver < 10 ? 8 : 16;
    if (qrCapacityBytes(ver) * 8 >= 4 + countBits + textLen * 8) return ver;
  }
  return 0;
}

// GF(2^8) w/ 0x11D —— 对数表
const QR_GF_EXP = new Uint8Array(512);
const QR_GF_LOG = new Uint8Array(256);
(function () {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    QR_GF_EXP[i] = x;
    QR_GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11D;
  }
  for (let i = 255; i < 512; i++) QR_GF_EXP[i] = QR_GF_EXP[i - 255];
})();

function qrMul(a, b) {
  return a === 0 || b === 0 ? 0 : QR_GF_EXP[QR_GF_LOG[a] + QR_GF_LOG[b]];
}

function qrRSDivisor(degree) {
  let result = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(result.length + 1).fill(0);
    for (let j = 0; j < result.length; j++) {
      next[j] ^= qrMul(result[j], QR_GF_EXP[i]);
      next[j + 1] ^= result[j];
    }
    result = next;
  }
  return result;
}

function qrRSRemainder(data, divisor) {
  // divisor 以升序存储（[常数项, x 系数, ..., x^d 系数]，首项恒 1 在末尾）。
  // 除法寄存器按降序语义推进，因此系数从高次往低次取。
  const result = new Array(divisor.length - 1).fill(0);
  for (const b of data) {
    const factor = b ^ result[0];
    result.shift();
    result.push(0);
    for (let i = 0; i < result.length; i++) result[i] ^= qrMul(divisor[divisor.length - 2 - i], factor);
  }
  return result;
}

function qrBuildData(text, ver) {
  const countBits = ver < 10 ? 8 : 16;
  const capBytes = qrCapacityBytes(ver);
  const bytes = new TextEncoder().encode(text);
  const bits = [];
  const pushBits = (value, length) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };
  pushBits(0b0100, 4); // byte mode
  pushBits(bytes.length, countBits);
  for (const b of bytes) pushBits(b, 8);
  const capacityBits = capBytes * 8;
  const terminator = Math.min(4, capacityBits - bits.length);
  pushBits(0, terminator);
  while (bits.length % 8 !== 0) bits.push(0);
  const padByte = [0xEC, 0x11];
  let i = 0;
  while (bits.length < capacityBits) pushBits(padByte[i++ % 2], 8);
  return bits;
}

function qrSplitIntoBlocks(data, ver, eccLen, numBlocks) {
  const rawCodewords = Math.floor(qrRawDataModules(ver) / 8);
  const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
  const shortBlockLen = Math.floor(rawCodewords / numBlocks);
  const bytes = [];
  for (let i = 0; i < data.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | data[i + j];
    bytes.push(b);
  }
  const blocks = [];
  const rsDiv = qrRSDivisor(eccLen);
  let k = 0;
  for (let i = 0; i < numBlocks; i++) {
    const dat = bytes.slice(k, k + shortBlockLen - eccLen + (i < numShortBlocks ? 0 : 1));
    k += dat.length;
    const ecc = qrRSRemainder(dat, rsDiv);
    blocks.push({ dat, ecc });
  }
  return blocks;
}

function qrInterleave(blocks) {
  const result = [];
  const maxData = Math.max(...blocks.map(b => b.dat.length));
  for (let i = 0; i < maxData; i++) {
    for (const b of blocks) if (i < b.dat.length) result.push(b.dat[i]);
  }
  const maxEcc = Math.max(...blocks.map(b => b.ecc.length));
  for (let i = 0; i < maxEcc; i++) {
    for (const b of blocks) if (i < b.ecc.length) result.push(b.ecc[i]);
  }
  return result;
}

function qrAlignmentPositions(ver) {
  if (ver === 1) return [];
  const size = ver * 4 + 17;
  const numAlign = Math.floor(ver / 7) + 2;
  const step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (numAlign * 2 - 2)) * 2;
  const result = [6];
  for (let pos = size - 7; result.length < numAlign; pos -= step) result.splice(1, 0, pos);
  return result;
}

// 画除数据码字以外的全部功能模块。
// 返回 { m, fn, size }：m 是模块颜色（true=深色），fn 标记功能模块
// （无论深浅），数据放置只允许写入 !fn 的格子——白色格式位/分隔符/对齐
// 白圈是功能模块，不能仅凭颜色判断。
function qrDrawFunctionPatterns(ver) {
  const size = ver * 4 + 17;
  const m = Array.from({ length: size }, () => new Array(size).fill(false));
  const fn = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (x, y, v) => { m[y][x] = v; fn[y][x] = true; };
  const setRange = (x0, y0, x1, y1, v) => {
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) set(x, y, v);
  };
  const drawFinder = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        const x = cx + dx, y = cy + dy;
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist !== 2 && dist !== 4);
      }
    }
  };
  drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4);

  // 时序
  for (let i = 8; i < size - 8; i++) {
    set(i, 6, i % 2 === 0);
    set(6, i, i % 2 === 0);
  }
  // 对齐
  const aligns = qrAlignmentPositions(ver);
  for (const y of aligns) {
    for (const x of aligns) {
      if ((x === 6 && y === 6) || (x === 6 && y === size - 7) || (x === size - 7 && y === 6)) continue;
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }
  }
  set(8, size - 8, true); // 暗模块
  return { m, fn, size };
}

function qrFormatBits(bits) {
  let data = bits;
  let rem = data;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  const full = ((data << 10) | rem) ^ 0x5412;
  const out = [];
  for (let i = 0; i < 15; i++) out.push((full >>> i) & 1);
  return out;
}

function qrVersionBits(ver) {
  let rem = ver;
  for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
  const full = (ver << 12) | rem;
  const out = [];
  for (let i = 0; i < 18; i++) out.push((full >>> i) & 1);
  return out;
}

function qrDrawFormatAndVersion(state, ver, formatBits) {
  const { m, fn, size } = state;
  const fmt = qrFormatBits(formatBits);
  const put = (x, y, v) => {
    if (x >= 0 && x < size && y >= 0 && y < size) { m[y][x] = v; fn[y][x] = true; }
  };
  // 围绕左上 finder 的 15 位
  for (let i = 0; i < 6; i++) put(8, i, fmt[i]);
  put(8, 7, fmt[6]);
  put(8, 8, fmt[7]);
  put(7, 8, fmt[8]);
  for (let i = 9; i < 15; i++) put(14 - i, 8, fmt[i]);
  // 另两份副本
  for (let i = 0; i < 8; i++) put(size - 1 - i, 8, fmt[i]);
  for (let i = 8; i < 15; i++) put(8, size - 15 + i, fmt[i]);
  put(8, size - 8, true); // 暗模块旁固定
  if (ver >= 7) {
    const vb = qrVersionBits(ver);
    for (let i = 0; i < 18; i++) {
      const a = Math.floor(i / 3), b = i % 3;
      put(size - 11 + b, a, vb[i]);
      put(a, size - 11 + b, vb[i]);
    }
  }
}

function qrPlaceCodewords(state, ver, codewords, maskIdx) {
  const { m, size } = state;
  const maskFns = [
    (x, y) => (x + y) % 2 === 0,
    (x, y) => y % 2 === 0,
    (x, y) => x % 3 === 0,
    (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
    (x, y) => (x * y) % 2 + (x * y) % 3 === 0,
    (x, y) => ((x * y) % 2 + (x * y) % 3) % 2 === 0,
    (x, y) => ((x + y) % 2 + (x * y) % 3) % 2 === 0,
  ];
  const mask = maskFns[maskIdx];
  // 数据位从流的头部（模式指示位）正向填充；剩余位（remainder bits）
  // 也计入 i 的推进，与规范保持同步。
  let i = 0;
  const totalBits = codewords.length * 8;
  let x = size - 1, upward = true;
  while (x > 0) {
    if (x === 6) x--;
    for (let c = 0; c < size; c++) {
      const y = upward ? size - 1 - c : c;
      for (let j = 0; j < 2; j++) {
        const xx = x - j;
        if (xx < 0) continue;
        if (!state.fn[y][xx]) {
          if (i < totalBits) {
            const v = ((codewords[i >> 3] >>> (7 - (i & 7))) & 1) !== 0;
            m[y][xx] = v !== mask(xx, y);
          } else {
            // 剩余位（remainder bits）：规范中为 0，与其他数据模块一样
            // 参与掩码翻转（解码器忽略其取值，但矩阵应与参考实现一致）。
            m[y][xx] = mask(xx, y);
          }
          i++;
        }
      }
    }
    x -= 2;
    upward = !upward;
  }
}

function qrPenalty(state) {
  const { m, size } = state;
  let score = 0;
  // 规则 1/2：行列连块
  for (let run = 0, i = 0; i < size; i++) {
    let prev = null;
    run = 0;
    for (let j = 0; j <= size; j++) {
      const v = j < size ? m[i][j] : !prev;
      if (v === prev) { run++; continue; }
      if (prev !== null && run >= 5) score += 3 + (run - 5);
      prev = v; run = 1;
    }
  }
  for (let j = 0; j < size; j++) {
    prev = null; run = 0;
    for (let i = 0; i <= size; i++) {
      const v = i < size ? m[i][j] : !prev;
      if (v === prev) { run++; continue; }
      if (prev !== null && run >= 5) score += 3 + (run - 5);
      prev = v; run = 1;
    }
  }
  // 规则 2：2x2 同色块
  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const v = m[y][x];
      if (v === m[y][x + 1] && v === m[y + 1][x] && v === m[y + 1][x + 1]) score += 3;
    }
  }
  // 规则 3：finder 伪装 1011101 前后各 4 白
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size - 6; x++) {
      if (m[y][x] && !m[y][x + 1] && m[y][x + 2] && m[y][x + 3] && m[y][x + 4] && m[y][x + 5] && !m[y][x + 6]) {
        if ((x + 7 < size && !m[y][x + 7]) || (x >= 4 && !m[y][x - 1])) score += 40;
      }
    }
  }
  for (let x = 0; x < size; x++) {
    for (let y = 0; y < size - 6; y++) {
      if (m[y][x] && !m[y + 1][x] && m[y + 2][x] && m[y + 3][x] && m[y + 4][x] && m[y + 5][x] && !m[y + 6][x]) {
        if ((y + 7 < size && !m[y + 7][x]) || (y >= 4 && !m[y - 1][x])) score += 40;
      }
    }
  }
  // 规则 4：深浅比例
  let dark = 0;
  for (const row of m) for (const v of row) if (v) dark++;
  const total = size * size;
  const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
  score += k * 10;
  return score;
}

function qrRender(canvas, text) {
  const textLen = new TextEncoder().encode(text).length;
  const ver = qrFitVersion(textLen);
  if (!ver) return false;
  const bits = qrBuildData(text, ver);
  const eccLen = QR_ECC_PER_BLOCK[ver];
  const blocks = qrSplitIntoBlocks(bits, ver, eccLen, QR_BLOCKS_PER_LEVEL[ver]);
  const codewords = qrInterleave(blocks);
  // 数据模块的掩码在 qrPlaceCodewords 内翻转；格式/版本位是功能模块，
  // 在数据放置前画好且不被掩码影响（格式位自带 0x5412 异或）。
  let bestScore = Infinity, bestState = null;
  for (let mask = 0; mask < 8; mask++) {
    const state = qrDrawFunctionPatterns(ver);
    // 格式信息 5 位 = [纠错级别(2) | 掩码(3)]，纠错位在高位。
    qrDrawFormatAndVersion(state, ver, (0b00 << 3) | mask);
    qrPlaceCodewords(state, ver, codewords, mask);
    const score = qrPenalty(state);
    if (score < bestScore) { bestScore = score; bestState = state; }
  }
  const { m, size } = bestState;
  const scale = Math.max(2, Math.floor(220 / (size + 8)));
  const quiet = Math.max(2, Math.floor((220 - size * scale) / 2));
  canvas.width = size * scale + quiet * 2;
  canvas.height = canvas.width;
  const g = canvas.getContext('2d');
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, canvas.width, canvas.height);
  g.fillStyle = '#0d1117';
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (m[y][x]) g.fillRect(quiet + x * scale, quiet + y * scale, scale, scale);
    }
  }
  return true;
}

/* ---- 匿名设备面板 ---- */
let latestDevices = [];
let latestAnonymousEnroll = false;

function renderDevices(list) {
  latestDevices = list || [];
  const el = byId('devices');
  if (!el) return;
  if (!latestDevices.length) {
    el.innerHTML = '<p class="panel-message">尚未有设备通过匿名自助接入登记。</p>';
    renderAnonymousSwitch();
    return;
  }
  el.innerHTML = `<table class="data-table">
    <thead><tr><th>设备指纹</th><th>状态</th><th>Host 数</th><th>登记时间</th><th></th></tr></thead>
    <tbody>${latestDevices.map((d, i) => {
      const short = (d.id || '').slice(0, 12) + '…';
      const when = d.createdAt ? new Date(d.createdAt * 1000).toLocaleString() : '';
      const live = (d.hostIds || []).length;
      return `<tr>
        <td><code class="invite-code" title="${escapeHtml(d.id)}">${escapeHtml(short)}</code></td>
        <td>${d.enabled ? '<span class="chip chip--ok">启用</span>' : '<span class="chip">已禁用</span>'}</td>
        <td>${live} / ${d.maxHosts}</td>
        <td>${escapeHtml(when)}</td>
        <td>
          ${d.enabled
            ? `<button class="button button--quiet" data-device-action="disable" data-device-index="${i}" type="button">禁用</button>`
            : `<button class="button button--quiet" data-device-action="enable" data-device-index="${i}" type="button">启用</button>`}
          <button class="button button--quiet" data-device-action="delete" data-device-index="${i}" type="button">删除</button>
        </td>
      </tr>`;}).join('')}</tbody>
  </table>`;
  renderAnonymousSwitch();
}

function renderAnonymousSwitch() {
  const btn = byId('btnToggleAnonymous');
  const label = byId('anonymousEnrollLabel');
  if (!btn || !label) return;
  if (currentRole !== 'admin') {
    btn.hidden = true;
    label.hidden = true;
    return;
  }
  btn.hidden = false;
  label.hidden = false;
  if (latestAnonymousEnroll) {
    label.textContent = '匿名自助接入：开';
    btn.textContent = '停止匿名接入';
  } else {
    label.textContent = '匿名自助接入：关';
    btn.textContent = '开启匿名接入';
  }
}

async function loadDevices() {
  try {
    const res = await fetch('/v1/devices', { credentials: 'same-origin' });
    if (!res.ok) return;
    const data = await res.json();
    renderDevices(data.devices || []);
  } catch (_) { /* console renders next refresh */ }
}

async function toggleAnonymous() {
  if (!confirm('切换匿名自助接入开关？')) return;
  const next = !latestAnonymousEnroll;
  try {
    const res = await fetch('/v1/settings/anonymous', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: next }),
    });
    if (!res.ok) { byId('devicesMessage').textContent = '切换失败'; return; }
    latestAnonymousEnroll = next;
    renderAnonymousSwitch();
  } catch (_) { /* ignore */ }
}

byId('btnToggleAnonymous').addEventListener('click', toggleAnonymous);

document.addEventListener('click', (event) => {
  const btn = event.target.closest('[data-device-action]');
  if (!btn) return;
  const idx = Number(btn.dataset.deviceIndex);
  const dev = latestDevices[idx];
  if (!dev) return;
  const action = btn.dataset.deviceAction;
  if (action === 'delete' && !confirm('删除设备将级联吊销其全部 Host，继续？')) return;
  if (action === 'disable' && !confirm('禁用设备将立即吊销其全部 Host，继续？')) return;
  fetch(`/v1/devices/${encodeURIComponent(dev.id)}/${action}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  }).then((res) => {
    if (res.ok) { loadDevices(); loadHosts(); }
  }).catch(() => {});
});

let lastTenantsJSON = '';

function tenantHostFull(row) {
  if (!row) return false;
  if (row.hostFull === true) return true;
  const live = Number(row.liveHosts) || 0;
  const maxH = Number(row.maxLiveHosts) || 0;
  return maxH > 0 && live >= maxH;
}

function tenantInviteFull(row) {
  if (!row) return false;
  if (row.inviteFull === true) return true;
  const unused = Number(row.unusedInvites) || 0;
  const maxI = Number(row.maxUnusedInvites) || 0;
  return maxI > 0 && unused >= maxI;
}

function tenantStatusLabel(row) {
  if (row && row.disabledAt) return ['已停用', 'muted'];
  if (row && row.passwordMustChange) return ['待改密', 'warning'];
  if (tenantHostFull(row)) return ['已接入已满', 'warning'];
  if (tenantInviteFull(row)) return ['未用码已满', 'warning'];
  return ['可用', ''];
}

function compareTenants(a, b) {
  const aDis = Boolean(a && a.disabledAt);
  const bDis = Boolean(b && b.disabledAt);
  if (aDis !== bDis) return aDis ? 1 : -1;
  const aHost = tenantHostFull(a);
  const bHost = tenantHostFull(b);
  if (aHost !== bHost) return aHost ? -1 : 1;
  const aInv = tenantInviteFull(a);
  const bInv = tenantInviteFull(b);
  if (aInv !== bInv) return aInv ? -1 : 1;
  return String((a && a.loginName) || '').localeCompare(String((b && b.loginName) || ''), 'zh-CN');
}

function renderTenants(list) {
  lastTenantsList = Array.isArray(list) ? list : [];
  const el = byId('tenants');
  if (!el) return;
  const rows = lastTenantsList.slice().sort(compareTenants);
  if (!rows.length) {
    el.replaceChildren(createEmptyState('还没有租户', '开通后对方用登录名进控制台，只能看到自己签发的邀请和 Host。'));
    return;
  }
  const table = createElement('table', 'data-table');
  const thead = createElement('thead');
  thead.innerHTML = '<tr><th>登录名</th><th>显示名</th><th>已接入</th><th>未用邀请</th><th>状态</th><th>开通时间</th><th></th></tr>';
  const tbody = createElement('tbody');
  rows.forEach(row => {
    const tr = createElement('tr');
    if (adminFocusId() && String(row.id) === adminFocusId()) tr.classList.add('is-focus');
    const live = Number(row.liveHosts) || 0;
    const maxH = Number(row.maxLiveHosts) || 0;
    const unused = Number(row.unusedInvites) || 0;
    const maxI = Number(row.maxUnusedInvites) || 0;
    const hostCell = createElement('td', 'tnum', tenantHostFull(row) ? `${live} / ${maxH} · 已满` : `${live} / ${maxH}`);
    const inviteCell = createElement('td', 'tnum', tenantInviteFull(row) ? `${unused} / ${maxI} · 已满` : `${unused} / ${maxI}`);
    const [status, tone] = tenantStatusLabel(row);
    const statusCell = createElement('td');
    statusCell.append(createStatus(status, tone));
    tr.append(
      createElement('td', '', row.loginName || ''),
      createElement('td', '', row.displayName || ''),
      hostCell,
      inviteCell,
      statusCell,
      createElement('td', 'tnum', formatTime(row.createdAt))
    );
    const actions = createElement('td');
    const focused = adminFocusId() && String(row.id) === adminFocusId();
    const view = createElement('button', 'button button--quiet', focused ? '正在查看' : '查看电脑');
    view.type = 'button';
    view.disabled = focused;
    view.addEventListener('click', () => focusAdminTenant(row));
    const copy = createElement('button', 'button button--quiet', '复制入口');
    copy.type = 'button';
    copy.addEventListener('click', async () => {
      const url = publicControlURL();
      if (!url) {
        setPanelMessage('tenantsMessage', tenantHandoffCopy(row.loginName));
        return;
      }
      try {
        await copyText(url);
        setButtonState(copy, 'success', '已复制');
        setPanelMessage('tenantsMessage', `已复制 ${url}。把地址和登录名 ${row.loginName} 交给对方；对方用浏览器进控制台，不要用 App 登录，也不要把 127.0.0.1 外发。`);
        window.setTimeout(() => setButtonState(copy, '', '复制入口'), 2500);
      } catch (_) {
        setPanelMessage('tenantsMessage', '无法写入剪贴板。请把 config 的 public_control_url 私下交给对方。');
      }
    });
    const reset = createElement('button', 'button button--quiet', '重置密码');
    reset.type = 'button';
    reset.addEventListener('click', async () => {
      const password = window.prompt(`给 ${row.loginName} 设新密码（至少 12 位）：`);
      if (!password) return;
      try {
        await request(`${ENDPOINTS.tenants}/${encodeURIComponent(row.id)}/password`, {
          method: 'POST',
          body: JSON.stringify({ password })
        });
        setPanelMessage('tenantsMessage', `已重置 ${row.loginName} 的控制台密码。对方需要重新登录，并再改一次密码后才能发码。`);
        lastEventsJSON = '';
        await loadEvents();
      } catch (error) {
        setPanelMessage('tenantsMessage', `重置失败：${error.message}`);
      }
    });
    if (row.disabledAt) {
      const enable = createElement('button', 'button button--quiet', '恢复');
      enable.type = 'button';
      enable.addEventListener('click', async () => {
        if (!window.confirm(`恢复租户 ${row.loginName}？已吊销的 Host 不会自动回来，对方需重新签发接入码。`)) return;
        try {
          await request(`${ENDPOINTS.tenants}/${encodeURIComponent(row.id)}/enable`, { method: 'POST', body: '{}' });
          lastTenantsJSON = '';
          await loadTenants();
          lastEventsJSON = '';
          await loadEvents();
        } catch (error) {
          setPanelMessage('tenantsMessage', `恢复失败：${error.message}`);
        }
      });
      actions.append(view, copy, reset, enable);
    } else {
      const btn = createElement('button', 'button button--quiet', '停用');
      btn.type = 'button';
      btn.addEventListener('click', async () => {
        if (!window.confirm(`停用租户 ${row.loginName}？未使用的邀请会作废，已接入的 Host 会立即吊销。`)) return;
        try {
          await request(`${ENDPOINTS.tenants}/${encodeURIComponent(row.id)}/disable`, { method: 'POST', body: '{}' });
          lastTenantsJSON = '';
          await loadTenants();
          lastEventsJSON = '';
          await loadEvents();
        } catch (error) {
          setPanelMessage('tenantsMessage', `停用失败：${error.message}`);
        }
      });
      actions.append(view, copy, reset, btn);
    }
    tr.append(actions);
    tbody.append(tr);
  });
  table.append(thead, tbody);
  el.replaceChildren(table);
}

async function loadTenants() {
  if (currentRole !== 'admin') return;
  try {
    const data = await request(ENDPOINTS.tenants);
    const serialized = JSON.stringify(data || []);
    if (serialized === lastTenantsJSON) return;
    lastTenantsJSON = serialized;
    renderTenants(Array.isArray(data) ? data : []);
  } catch (_) { /* next refresh */ }
}

const tenantForm = byId('tenantForm');
if (tenantForm) {
  tenantForm.addEventListener('submit', async event => {
    event.preventDefault();
    const loginName = byId('inpTenantLogin').value.trim();
    const displayName = byId('inpTenantDisplay').value.trim();
    const password = byId('inpTenantPassword').value;
    setPanelMessage('tenantsMessage', '');
    try {
      await withLoading(byId('btnCreateTenant'), '开通中', async () => {
        await request(ENDPOINTS.tenants, {
          method: 'POST',
          body: JSON.stringify({ loginName, displayName, password })
        });
        byId('inpTenantPassword').value = '';
        lastTenantsJSON = '';
        await loadTenants();
        lastEventsJSON = '';
        await loadEvents();
        setPanelMessage('tenantsMessage', tenantHandoffCopy(loginName));
      });
    } catch (error) {
        setPanelMessage('tenantsMessage', `开通失败：${error.message}`);
    }
  });
}

const passwordForm = byId('passwordForm');
if (passwordForm) {
  passwordForm.addEventListener('submit', async event => {
    event.preventDefault();
    const currentPassword = byId('inpCurrentPassword').value;
    const newPassword = byId('inpNewPassword').value;
    setPanelMessage('passwordMessage', '');
    try {
      await withLoading(byId('btnChangePassword'), '更新中', async () => {
        await request(ENDPOINTS.accountPassword, {
          method: 'POST',
          body: JSON.stringify({ currentPassword, newPassword })
        });
        byId('inpCurrentPassword').value = '';
        byId('inpNewPassword').value = '';
        setPanelMessage('passwordMessage', '密码已更新。这次会话继续有效，其它控制台登录会失效。');
        lastEventsJSON = '';
        await Promise.all([loadOverview(), loadEvents()]);
      });
    } catch (error) {
      setPanelMessage('passwordMessage', `更新失败：${error.message}`);
    }
  });
}
