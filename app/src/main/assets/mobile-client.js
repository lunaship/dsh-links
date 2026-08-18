/**
 * DSH Mobile Shell — App 注入的移动适配层（自包含，零外部依赖）
 * ============================================================
 * 注入位置：WebActivity（仅 App WebView；桌面浏览器永不加载本脚本）
 * 激活条件：UA 含 "DshMobile" 且视口宽度 <= 768px
 * 功能：FAB / 左抽屉 / 遮罩 / 会话行自动收起 / 深链会话定位桥
 * 依赖：assets/mobile_override.css（同由 App 注入）
 *
 * 说明：DSH Web UI 本体不做任何修改；本层通过 DOM class 选择器
 * 与 DSH 页面解耦适配（选择器随 DSH 版本在 capabilities 中声明）。
 */
(function () {
  'use strict';

  // ---- 激活条件 ----
  var UA = navigator.userAgent || '';
  if (UA.indexOf('DshMobile') === -1) return;          // 非 App WebView
  if (window.innerWidth > 768) return;                  // 平板横屏 / 桌面

  var HTML = document.documentElement;
  var SHELL = 'dsh-mobile-shell';
  var DRAWER_OPEN = 'dsh-mobile-drawer-open';

  // DSH DOM 选择器（对应 DSH 0.1.x 构建，见 capabilities 声明）
  var SEL = {
    frame: '.pI_x6G_frame',
    sidebar: '.pI_x6G_sidebarCol',
    sessionRow: '[role="treeitem"], .YDXeBa_sessionRow, .YDXeBa_searchResultRow',
    searchInput: '.YDXeBa_searchInput, input[placeholder*="搜索"], input[placeholder*="Search"]',
  };

  var state = { ready: false, fab: null, backdrop: null };

  function isDshPage() {
    return !!document.querySelector(SEL.frame);
  }

  // ---- 抽屉开关（通过 html class 驱动 CSS，不触碰 DSH 内部状态）----
  function openDrawer() {
    HTML.classList.add(DRAWER_OPEN);
  }
  function closeDrawer() {
    HTML.classList.remove(DRAWER_OPEN);
  }
  function toggleDrawer() {
    if (HTML.classList.contains(DRAWER_OPEN)) closeDrawer();
    else openDrawer();
  }

  // ---- FAB（hamburger）----
  function ensureFab() {
    if (state.fab || !isDshPage()) return;
    var fab = document.createElement('button');
    fab.className = 'dsh-mobile-fab';
    fab.setAttribute('aria-label', '打开会话列表');
    fab.innerHTML = '<span></span><span></span><span></span>';
    fab.addEventListener('click', toggleDrawer);
    fab.addEventListener('pointerdown', function (e) { e.preventDefault(); });
    document.body.appendChild(fab);
    state.fab = fab;
  }

  // ---- 遮罩 ----
  function ensureBackdrop() {
    if (state.backdrop) return;
    var bd = document.createElement('div');
    bd.className = 'dsh-mobile-backdrop';
    bd.addEventListener('click', closeDrawer);
    bd.addEventListener('touchstart', function (e) { e.stopPropagation(); }, { passive: true });
    document.body.appendChild(bd);
    state.backdrop = bd;
  }

  // ---- 会话行点击后自动收起抽屉 ----
  function bindSessionRowAutoClose() {
    document.addEventListener('click', function (e) {
      if (!HTML.classList.contains(DRAWER_OPEN)) return;
      var t = e.target;
      if (!(t instanceof Element)) return;
      var row = t.closest(SEL.sessionRow);
      if (row) setTimeout(closeDrawer, 160);
    }, true);
  }

  // ---- 深链：会话定位桥 ----
  // App 侧（通知点击）注入：window.dispatchEvent(new CustomEvent(
  //   'dsh-mobile-open-session', { detail: { sessionId, requestId } }))
  // 完成后回传：window.dispatchEvent(new CustomEvent(
  //   'dsh-mobile-open-session-result', { detail: { requestId, ok, error } }))
  function findSessionRow(sessionId) {
    if (!sessionId) return null;
    var rows = document.querySelectorAll(SEL.sessionRow);
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var ds = r.getAttribute('data-session-id') || r.getAttribute('data-id') || '';
      if (ds === sessionId) return r;
      var label = (r.getAttribute('title') || r.textContent || '').trim();
      if (label === sessionId || label.indexOf(sessionId) === 0) return r;
    }
    return null;
  }

  function openSession(sessionId, requestId) {
    function report(ok, error) {
      window.dispatchEvent(new CustomEvent('dsh-mobile-open-session-result', {
        detail: { requestId: requestId || null, ok: ok, error: error || null },
      }));
    }
    if (!sessionId) return report(false, 'missing-session-id');
    var row = findSessionRow(sessionId);
    if (!row) return report(false, 'session-not-in-list');
    openDrawer();
    setTimeout(function () {
      try {
        row.scrollIntoView({ block: 'center' });
        row.click();
        report(true);
      } catch (err) {
        report(false, String(err && err.message ? err.message : err));
      }
    }, 220);
  }

  window.addEventListener('dsh-mobile-open-session', function (e) {
    var d = (e && e.detail) || {};
    openSession(d.sessionId, d.requestId);
  });

  // ---- 启动 ----
  function boot() {
    HTML.classList.add(SHELL);
    ensureFab();
    ensureBackdrop();
    bindSessionRowAutoClose();
    state.ready = true;
  }

  // SPA 页面可能延迟渲染 frame：轮询等待
  var tries = 0;
  (function wait() {
    if (isDshPage()) { boot(); return; }
    if (++tries < 60) setTimeout(wait, 250);   // 最多等 15s
  })();

  // 窗口变宽（旋转/分屏）时退出移动壳
  window.addEventListener('resize', function () {
    if (window.innerWidth > 768) {
      HTML.classList.remove(SHELL);
      HTML.classList.remove(DRAWER_OPEN);
    } else if (!state.ready && isDshPage()) {
      boot();
    }
  });
})();
