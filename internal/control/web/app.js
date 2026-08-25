const ENDPOINTS = {
  login: '/login',
  logout: '/logout',
  overview: '/v1/overview',
  hosts: '/v1/hosts',
  invites: '/v1/invites'
};

const REFRESH_MS = 5000;
let refreshTimer = null;
let latestInviteCode = '';
let latestTlsFingerprint = '';
let latestEnrollURI = '';
let lastHostsJSON = '';
let lastInvitesJSON = '';

const byId = id => document.getElementById(id);

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
  stopRefresh();
  byId('loginPanel').classList.remove('hidden');
  byId('mainPanel').classList.add('hidden');
  byId('loginErr').textContent = message;
  byId('inpPass').value = '';
  window.requestAnimationFrame(() => byId('inpUser').focus());
}

async function showMain(overview) {
  byId('loginPanel').classList.add('hidden');
  byId('mainPanel').classList.remove('hidden');
  renderOverview(overview);
  await Promise.allSettled([loadHosts(), loadInvites()]);
  stopRefresh();
  refreshTimer = window.setInterval(() => {
    loadOverview();
    loadHosts();
    loadInvites();
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
    byId('loginErr').textContent = '请输入管理账号和密码。';
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
      ? '账号或密码不正确。请检查 config.toml。'
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

function renderOverview(data) {
  byId('statHosts').textContent = new Intl.NumberFormat('zh-CN').format(Number(data.hosts || 0));
  byId('statInvites').textContent = new Intl.NumberFormat('zh-CN').format(Number(data.invites || 0));
  byId('statUpdated').textContent = `更新于 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date())}`;
  setConnectionState(true);
  renderTlsFingerprint(data && data.tlsFingerprint);
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

function renderHosts(list) {
  const root = byId('hosts');
  root.replaceChildren();
  if (!Array.isArray(list) || list.length === 0) {
    root.append(createEmptyState('暂无 Host', '插件接入后会出现在这里。'));
    return;
  }

  const table = document.createElement('table');
  table.append(createElement('caption', 'sr-only', 'Host 路由列表'));
  appendHeaderRow(table, ['Host', '状态', 'Generation', 'Streams', '最近心跳', '操作']);
  const tbody = document.createElement('tbody');

  list.forEach(host => {
    const row = document.createElement('tr');
    const identity = createElement('div', 'cell-stack');
    identity.append(createElement('strong', '', host.hostName || host.id || '未命名 Host'));
    identity.append(createElement('code', '', host.routeIdHash || '—'));
    appendCell(row, 'Host', identity);
    appendCell(row, '状态', createStatus(host.revokedAt ? '已吊销' : '可用', host.revokedAt ? 'muted' : ''));
    appendCell(row, 'Generation', String(host.generation ?? '—'), 'tnum');
    appendCell(row, 'Streams', String(host.maxStreams ?? '—'), 'tnum');
    appendCell(row, '最近心跳', formatTime(host.lastSeenAt), 'tnum');

    const name = host.hostName || host.id;
    const actions = [];
    if (!host.revokedAt) {
      actions.push(createDangerButton('吊销', `吊销 Host ${name}`, async () => {
        await request(`${ENDPOINTS.hosts}/${encodeURIComponent(host.id)}/revoke`, {
          method: 'POST', body: '{}'
        });
        await Promise.all([loadHosts(), loadOverview()]);
        announce(`${name} 已吊销。`);
      }));
    }
    actions.push(createDangerButton('删除', `删除 Host ${name}`, async () => {
      await request(`${ENDPOINTS.hosts}/${encodeURIComponent(host.id)}/delete`, {
        method: 'POST', body: '{}'
      });
      await Promise.all([loadHosts(), loadOverview()]);
      announce(`${name} 已删除。`);
    }));
    appendCell(row, '操作', createActionGroup(actions));
    tbody.append(row);
  });

  table.append(tbody);
  root.append(table);
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

function inviteState(invite) {
  if (invite.revokedAt) return ['已吊销', 'muted'];
  if (invite.consumedAt) return ['已消费', 'muted'];
  if (Number(invite.expiresAt) * 1000 < Date.now()) return ['已过期', 'warning'];
  return ['有效', ''];
}

function renderInvites(list) {
  const root = byId('invites');
  root.replaceChildren();
  if (!Array.isArray(list) || list.length === 0) {
    root.append(createEmptyState('暂无邀请', '创建邀请码后会出现在这里。'));
    return;
  }

  const table = document.createElement('table');
  table.append(createElement('caption', 'sr-only', '邀请记录列表'));
  appendHeaderRow(table, ['邀请 ID', '状态', '创建时间', '过期时间', '操作']);
  const tbody = document.createElement('tbody');

  list.forEach(invite => {
    const row = document.createElement('tr');
    const [status, tone] = inviteState(invite);
    const shortId = String(invite.id).slice(0, 8);
    appendCell(row, '邀请 ID', createElement('code', 'cell-secondary', `${shortId}…`));
    appendCell(row, '状态', createStatus(status, tone));
    appendCell(row, '创建时间', formatTime(invite.createdAt), 'tnum');
    appendCell(row, '过期时间', formatTime(invite.expiresAt), 'tnum');

    const actions = [];
    if (status === '有效') {
      actions.push(createDangerButton('吊销', `吊销邀请 ${shortId}`, async () => {
        await request(`${ENDPOINTS.invites}/${encodeURIComponent(invite.id)}/revoke`, {
          method: 'POST', body: '{}'
        });
        await Promise.all([loadInvites(), loadOverview()]);
        announce('邀请码已吊销。');
      }));
    }
    actions.push(createDangerButton('删除', `删除邀请 ${shortId}`, async () => {
      await request(`${ENDPOINTS.invites}/${encodeURIComponent(invite.id)}/delete`, {
        method: 'POST', body: '{}'
      });
      await Promise.all([loadInvites(), loadOverview()]);
      announce('邀请记录已删除。');
    }));
    appendCell(row, '操作', createActionGroup(actions));
    tbody.append(row);
  });

  table.append(tbody);
  root.append(table);
}

async function loadInvites() {
  try {
    const list = await request(ENDPOINTS.invites);
    const next = JSON.stringify(list);
    if (next === lastInvitesJSON) return;
    lastInvitesJSON = next;
    renderInvites(list);
  } catch (error) {
    setPanelMessage('invitesMessage', `邀请记录未刷新：${error.message}`);
  }
}

byId('btnInvite').addEventListener('click', async () => {
  byId('inviteMessage').textContent = '';
  try {
    await withLoading(byId('btnInvite'), '创建中', async () => {
      const result = await request(ENDPOINTS.invites, {
        method: 'POST', body: JSON.stringify({})
      });
      latestInviteCode = result.inviteCode || '';
      latestEnrollURI = result.enroll || '';
      byId('inviteCode').textContent = latestInviteCode;
      byId('enrollURI').textContent = latestEnrollURI;
      byId('enrollBlock').hidden = !latestEnrollURI;
      byId('inviteResult').hidden = false;
      byId('btnCopyInvite').focus();
      await Promise.all([loadInvites(), loadOverview()]);
      announce('接入码已创建。');
    });
  } catch (error) {
    byId('inviteMessage').textContent = `邀请码未创建：${error.message}`;
  }
});

byId('btnPurgeHosts').addEventListener('click', async () => {
  if (!window.confirm('删除全部已吊销的 Host 记录？还在用的电脑不会动。')) return;
  const button = byId('btnPurgeHosts');
  try {
    await withLoading(button, '清理中', async () => {
      const result = await request(`${ENDPOINTS.hosts}/purge`, { method: 'POST', body: '{}' });
      lastHostsJSON = '';
      await Promise.all([loadHosts(), loadOverview()]);
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
  if (!window.confirm('删除已用、过期和已吊销的邀请？未过期的有效邀请会保留。')) return;
  const button = byId('btnPurgeInvites');
  try {
    await withLoading(button, '清理中', async () => {
      const result = await request(`${ENDPOINTS.invites}/purge`, { method: 'POST', body: '{}' });
      lastInvitesJSON = '';
      await Promise.all([loadInvites(), loadOverview()]);
      const n = Number(result.deleted) || 0;
      const message = n ? `已删除 ${n} 条失效邀请。` : '没有失效邀请。有效的请用该行「删除」。';
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
    await copyText(latestInviteCode);
    setButtonState(button, 'success', '已复制');
    announce('接入码已复制。');
    window.setTimeout(() => setButtonState(button, '', '复制接入码'), 2500);
  } catch (_) {
    byId('inviteMessage').textContent = '无法写入剪贴板。请手动选择并复制接入码。';
  }
});

byId('btnCopyEnroll').addEventListener('click', async () => {
  const button = byId('btnCopyEnroll');
  try {
    await copyText(latestEnrollURI);
    setButtonState(button, 'success', '已复制');
    announce('接入信息已复制。');
    window.setTimeout(() => setButtonState(button, '', '复制'), 2500);
  } catch (_) {
    byId('inviteMessage').textContent = '无法写入剪贴板。请手动选择并复制接入信息。';
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
