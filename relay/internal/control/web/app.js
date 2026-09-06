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
  await Promise.allSettled([loadHosts(), loadInvites(), loadDevices()]);
  stopRefresh();
  refreshTimer = window.setInterval(() => {
    loadOverview();
    loadHosts();
    loadInvites();
    loadDevices();
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
  const hasPublicHost = typeof data.publicHost === 'string' && data.publicHost.trim() !== '';
  byId('publicHostMissing').hidden = hasPublicHost;
  latestAnonymousEnroll = !!data.anonymousEnroll;
  renderAnonymousSwitch();
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
