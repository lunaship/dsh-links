/**
 * DSH Mobile — App 注入的最小层（v2）
 * ============================================================
 * 移动 UI 布局已交给 dsh-ui-mobile（DSH 官方插件，max-width:767px 激活，
 * 桌面/平板原样）。本文件只保留 App 侧必须的原生↔Web 桥：
 *   - 深链：dsh-mobile-open-session → 定位并打开目标会话
 *   - 结果回传：dsh-mobile-open-session-result
 * 不再注入任何布局代码（无 FAB/抽屉/遮罩），避免与 dsh-ui-mobile 冲突。
 */
(function () {
  'use strict';

  // 会话行选择器（DSH 0.1.x；dsh-ui-mobile 不改变会话行结构）
  var SEL = {
    sessionRow: '[role="treeitem"], .YDXeBa_sessionRow, .YDXeBa_searchResultRow',
  };

  // ---- 深链：会话定位桥 ----
  // App 侧（通知点击）注入：
  //   window.dispatchEvent(new CustomEvent('dsh-mobile-open-session',
  //     { detail: { sessionId, requestId } }))
  // 完成后回传：
  //   window.dispatchEvent(new CustomEvent('dsh-mobile-open-session-result',
  //     { detail: { requestId, ok, error } }))
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
})();
