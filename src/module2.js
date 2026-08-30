/**
 * dsh-links 客户端面 · 面板模块（作为 createPanelModule 工厂被主模块组合调用）
 * 「手机连接」：局域网配对与远端 Relay（接入成功后才显示云端二维码）。
 *
 * Hallmark pre-emit critique: P5 H5 E5 S5 R5 V5
 */
const createPanelModule = (require) => {
  const React = require('react')
  const { jsx, jsxs } = require('react/jsx-runtime')

  function deviceSeenLabel(lastSeenAt) {
    if (!lastSeenAt) return '暂未连接'
    const diff = Date.now() - lastSeenAt
    if (diff < 60_000) return '刚刚在线'
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前在线`
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前在线`
    return `${Math.floor(diff / 86_400_000)} 天前在线`
  }

  /** 默认 Agent/Client 端口不展示；自定义端口仍原样显示。 */
  function displayRelayHost(address) {
    const raw = String(address ?? '').trim()
    if (!raw) return ''
    if (raw.startsWith('[')) {
      const end = raw.indexOf(']')
      if (end > 0) {
        const host = raw.slice(1, end)
        const rest = raw.slice(end + 1)
        if (!rest || rest === ':8444' || rest === ':8443') return host
      }
      return raw
    }
    if (raw.endsWith(':8444') || raw.endsWith(':8443')) return raw.slice(0, -5)
    return raw
  }

  function parseEnrollText(raw) {
    const text = String(raw ?? '').trim()
    if (!text) return null
    const token = text.split(/\s+/).find((part) => part.startsWith('dsh-relay://')) ?? ''
    if (!token) return null
    let parsed
    try { parsed = new URL(token) } catch { throw new Error('接入信息无效') }
    if (parsed.protocol !== 'dsh-relay:') throw new Error('接入信息无效')
    const host = parsed.hostname
    if (!host) throw new Error('接入信息缺少主机')
    const invite = String(parsed.searchParams.get('i') || parsed.searchParams.get('invite') || '').trim()
    if (!invite) throw new Error('接入信息缺少接入码')
    const fpRaw = String(parsed.searchParams.get('fp') || '').replace(/[:\s]/g, '').toLowerCase()
    if (fpRaw && !/^[0-9a-f]{64}$/.test(fpRaw)) throw new Error('自签 TLS 需要 64 位 SHA-256 指纹')
    const port = parsed.port
    return {
      address: port ? `${host}:${port}` : host,
      inviteCode: invite,
      insecureTls: Boolean(fpRaw),
      tlsFingerprint: fpRaw,
    }
  }

  const OFFICIAL_RELAY_HOST = 'relay.dshlinks.com'
  const OFFICIAL_RELAY_TLS_SHA256 = '6fbe09cb8809714ec1c9eec1b982212bdc78e06870abd5ed21442bd4e6d3f9ea'

  function looksLikeInviteCode(raw) {
    return /^[A-Za-z0-9_-]{16,64}$/.test(String(raw ?? '').trim())
  }

  function resolvePaste(raw) {
    const parsed = parseEnrollText(raw)
    if (parsed) {
      if (parsed.insecureTls) return parsed
      return {
        address: parsed.address,
        inviteCode: parsed.inviteCode,
        insecureTls: true,
        tlsFingerprint: OFFICIAL_RELAY_TLS_SHA256,
      }
    }
    const invite = String(raw ?? '').trim()
    if (!looksLikeInviteCode(invite)) return null
    return {
      address: OFFICIAL_RELAY_HOST,
      inviteCode: invite,
      insecureTls: true,
      tlsFingerprint: OFFICIAL_RELAY_TLS_SHA256,
    }
  }

  const STYLE = `
    .dshlink-root {
      /* Hallmark · component: settings panel · genre: editorial-warm · theme: custom "Claude"
       *   macrostructure: grouped-settings-document (underline tabs · centered pair hero · hairline-divided lists)
       *   paper: warm ivory · accent: clay/terracotta · display: serif-touch · spacing: 4pt
       * states: default · hover · focus-visible · active · disabled · loading · error · success
       * pre-emit critique: P5 H5 E5 S5 R5 V5 · contrast: pass */
      --cl-bg: #faf9f5;
      --cl-surface: #ffffff;
      --cl-inset: #f5f3ec;
      --cl-ink: #1f1e1d;
      --cl-muted: #6b6a63;
      --cl-faint: #9a978c;
      --cl-line: #e7e3d8;
      --cl-line-strong: #d8d3c4;
      --cl-accent: #c96442;
      --cl-accent-deep: #b0522f;
      --cl-accent-bright: #d97757;
      --cl-accent-soft: #f5e7df;
      --cl-accent-line: #e6c9b9;
      --cl-accent-text: var(--cl-accent-deep);
      --cl-ok: #4f7a52; --cl-ok-soft: #e8efe4; --cl-ok-line: #cfe0cb;
      --cl-danger: #b0432f; --cl-danger-soft: #f6e5df; --cl-danger-line: #e8cabf;
      --cl-warn: #a5751f; --cl-warn-soft: #f5ecd6; --cl-warn-line: #e6d3a8;
      --cl-radius-s: 9px; --cl-radius-m: 13px; --cl-radius-l: 16px;
      --cl-ease: cubic-bezier(0.22, 1, 0.36, 1);
      --cl-serif: "Tiempos Headline", "Copernicus", "Songti SC", "STSong", "Georgia", serif;
      --cl-sans: ui-sans-serif, -apple-system, "Segoe UI", "PingFang SC", "Noto Sans SC", system-ui, sans-serif;
      font-family: var(--cl-sans);
      color: var(--cl-ink);
      -webkit-font-smoothing: antialiased;
    }
    @media (prefers-color-scheme: dark) {
      .dshlink-root {
        --cl-bg: #262624;
        --cl-surface: #302f2d;
        --cl-inset: #211f1e;
        --cl-ink: #f4f2ea;
        --cl-muted: #b7b3a7;
        --cl-faint: #8a877c;
        --cl-line: #403e39;
        --cl-line-strong: #524f48;
        --cl-accent: #dd8461;
        --cl-accent-deep: #c96b48;
        --cl-accent-bright: #e69675;
        --cl-accent-soft: rgba(217, 119, 87, 0.15);
        --cl-accent-line: rgba(217, 119, 87, 0.32);
        --cl-accent-text: var(--cl-accent-bright);
        --cl-ok: #86a986; --cl-ok-soft: rgba(134, 169, 134, 0.15); --cl-ok-line: rgba(134, 169, 134, 0.3);
        --cl-danger: #d98a76; --cl-danger-soft: rgba(217, 138, 118, 0.14); --cl-danger-line: rgba(217, 138, 118, 0.3);
        --cl-warn: #cba85e; --cl-warn-soft: rgba(203, 168, 94, 0.14); --cl-warn-line: rgba(203, 168, 94, 0.3);
      }
    }

    /* modal shell (used by LinkPanel) */
    .dshlink-backdrop {
      position: fixed; inset: 0; z-index: 99995;
      background: rgba(31, 30, 29, 0.44);
      backdrop-filter: blur(4px); -webkit-backdrop-filter: blur(4px);
      display: flex; align-items: center; justify-content: center; padding: 20px;
      animation: dshlink-fadein 0.2s ease;
    }
    @keyframes dshlink-fadein { from { opacity: 0 } to { opacity: 1 } }
    .dshlink-panel {
      width: min(440px, 100%); max-height: 86vh; overflow: auto;
      border-radius: var(--cl-radius-l); background: var(--cl-bg);
      border: 1px solid var(--cl-line); box-shadow: 0 12px 40px rgba(31, 30, 29, 0.18);
      padding: 24px 22px; display: flex; flex-direction: column; gap: 18px;
      animation: dshlink-rise 0.3s var(--cl-ease);
    }
    @keyframes dshlink-rise {
      from { opacity: 0; transform: translateY(14px) scale(0.98) }
      to { opacity: 1; transform: none }
    }
    @media (prefers-reduced-motion: reduce) {
      .dshlink-backdrop, .dshlink-panel { animation-duration: 0.01s }
      .dshlink-tab::after { transition: none }
      .dshlink-device-dot.is-pending::after { animation: none }
    }

    .dshlink-settings { display: flex; flex-direction: column; gap: 18px; max-width: 452px; margin: 0 auto; }

    /* ---- header ---- */
    .dshlink-brand { display: flex; align-items: center; gap: 12px; }
    .dshlink-brand-mark {
      flex: none; width: 34px; height: 34px; border-radius: 10px;
      display: flex; align-items: center; justify-content: center;
      color: var(--cl-accent-text); background: var(--cl-accent-soft); border: 1px solid var(--cl-accent-line);
    }
    .dshlink-brand-mark svg { display: block; }
    .dshlink-brand-copy { display: flex; flex-direction: column; gap: 1px; flex: 1; min-width: 0; }
    .dshlink-brand-title { font-family: var(--cl-serif); font-style: normal; font-size: 21px; font-weight: 600; letter-spacing: -0.01em; line-height: 1.2; color: var(--cl-ink); margin: 0; }
    .dshlink-brand-sub { font-size: 12px; color: var(--cl-faint); }
    .dshlink-status-pill { flex: none; display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: var(--cl-muted); white-space: nowrap; }
    .dshlink-status-pill .d { width: 7px; height: 7px; border-radius: 50%; background: var(--cl-ok); }
    .dshlink-status-pill[data-tone="accent"] .d { background: var(--cl-accent); }

    /* ---- underline tabs ---- */
    .dshlink-tabs { display: grid; grid-template-columns: 1fr 1fr; border-bottom: 1px solid var(--cl-line); }
    .dshlink-tab {
      position: relative; appearance: none; cursor: pointer; border: 0; background: transparent;
      color: var(--cl-muted); font: inherit; font-size: 13.5px; font-weight: 600; padding: 10px 8px 12px; white-space: nowrap;
      transition: color 0.18s var(--cl-ease);
    }
    .dshlink-tab::after { content: ""; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; border-radius: 2px 2px 0 0; background: var(--cl-accent); transform: scaleX(0); transition: transform 0.2s var(--cl-ease); }
    .dshlink-tab:hover { color: var(--cl-ink); }
    .dshlink-tab.is-active { color: var(--cl-ink); }
    .dshlink-tab.is-active::after { transform: scaleX(1); }
    .dshlink-tab:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    .dshlink-connection { display: flex; flex-direction: column; gap: 18px; }
    .dshlink-lan, .dshlink-remote { display: flex; flex-direction: column; gap: 18px; }

    /* ---- pairing (horizontal) ---- */
    .dshlink-pair { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 20px; align-items: center; padding: 18px; border-radius: var(--cl-radius-l); background: var(--cl-surface); border: 1px solid var(--cl-line); }
    .dshlink-qr-plate { width: 120px; height: 120px; border-radius: var(--cl-radius-m); background: #fff; padding: 9px; border: 1px solid var(--cl-line); }
    .dshlink-qr { display: block; width: 100%; height: 100%; border-radius: 4px; }
    .dshlink-pair-meta { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .dshlink-pair-label { font-size: 11px; font-weight: 600; letter-spacing: 0.1em; text-transform: uppercase; color: var(--cl-faint); }
    .dshlink-pair-code-row { display: flex; align-items: baseline; gap: 12px; min-width: 0; flex-wrap: wrap; }
    .dshlink-code { font-family: var(--cl-serif); font-size: 30px; font-weight: 600; letter-spacing: 0.14em; font-variant-numeric: tabular-nums; line-height: 1; color: var(--cl-ink); margin: 0; overflow-wrap: anywhere; }
    .dshlink-copy { appearance: none; cursor: pointer; border: 0; background: transparent; color: var(--cl-accent-text); font: inherit; font-size: 12.5px; font-weight: 600; padding: 4px 7px; border-radius: 7px; align-self: center; display: inline-flex; align-items: center; gap: 5px; transition: background 0.15s ease, color 0.15s ease; }
    .dshlink-copy:hover { background: var(--cl-accent-soft); }
    .dshlink-copy.is-copied { color: var(--cl-ok); }
    .dshlink-copy:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }
    .dshlink-copy svg { display: block; }
    .dshlink-pair-hint { margin: 2px 0 0; font-size: 12px; line-height: 1.5; color: var(--cl-muted); }

    /* ---- grouped section ---- */
    .dshlink-section { display: flex; flex-direction: column; gap: 10px; }
    .dshlink-section-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-width: 0; padding: 0 2px; }
    .dshlink-section-label { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; letter-spacing: 0.02em; color: var(--cl-muted); }
    .dshlink-section-count { min-width: 19px; height: 19px; padding: 0 6px; border-radius: 999px; display: inline-flex; align-items: center; justify-content: center; background: var(--cl-accent-soft); color: var(--cl-accent-text); font-size: 11px; font-weight: 700; font-variant-numeric: tabular-nums; }
    .dshlink-revoke-all { appearance: none; cursor: pointer; border: 0; background: transparent; color: var(--cl-danger); padding: 4px 8px; border-radius: 8px; font: inherit; font-size: 12px; font-weight: 600; transition: background 0.15s ease; }
    .dshlink-revoke-all:hover { background: var(--cl-danger-soft); }
    .dshlink-revoke-all:focus-visible { outline: 2px solid var(--cl-danger); outline-offset: 2px; }

    /* ---- group container + rows ---- */
    .dshlink-group { background: var(--cl-surface); border: 1px solid var(--cl-line); border-radius: var(--cl-radius-l); overflow: hidden; }
    .dshlink-row { display: flex; align-items: center; gap: 12px; padding: 14px 16px; }
    .dshlink-row + .dshlink-row, .dshlink-device + .dshlink-device { border-top: 1px solid var(--cl-line); }

    /* ---- confirm row ---- */
    .dshlink-confirm-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .dshlink-confirm-title { font-size: 13.5px; font-weight: 600; color: var(--cl-ink); }
    .dshlink-confirm-sub { font-size: 11.5px; color: var(--cl-faint); line-height: 1.45; }
    .dshlink-switch { display: inline-flex; align-items: center; cursor: pointer; user-select: none; flex: none; }
    .dshlink-switch input { position: absolute; opacity: 0; width: 1px; height: 1px; }
    .dshlink-switch-track { position: relative; width: 40px; height: 23px; flex: none; border-radius: 999px; background: var(--cl-line-strong); transition: background 0.18s var(--cl-ease); }
    .dshlink-switch-track::after { content: ""; position: absolute; top: 2px; left: 2px; width: 19px; height: 19px; border-radius: 50%; background: #fff; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.28); transition: transform 0.2s var(--cl-ease); }
    .dshlink-switch input:checked + .dshlink-switch-track { background: var(--cl-accent); }
    .dshlink-switch input:checked + .dshlink-switch-track::after { transform: translateX(17px); }
    .dshlink-switch input:focus-visible + .dshlink-switch-track { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    /* ---- device rows (inside group) ---- */
    .dshlink-device { display: flex; align-items: center; gap: 12px; padding: 13px 16px; }
    .dshlink-device.is-pending { background: var(--cl-warn-soft); }
    .dshlink-device-dot { position: relative; flex: none; width: 9px; height: 9px; border-radius: 50%; background: var(--cl-ok); }
    .dshlink-device-dot.is-pending { background: var(--cl-warn); }
    .dshlink-device-dot.is-pending::after { content: ""; position: absolute; inset: -4px; border-radius: 50%; border: 2px solid var(--cl-warn); opacity: 0; animation: dshlink-ping 1.6s ease-out infinite; }
    @keyframes dshlink-ping { 0% { transform: scale(0.5); opacity: 0.6 } 70%, 100% { transform: scale(1.5); opacity: 0 } }
    .dshlink-device-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px; }
    .dshlink-device-name-row { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .dshlink-device-name { font-size: 13.5px; font-weight: 600; color: var(--cl-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .dshlink-device-badge { flex: none; font-size: 10px; font-weight: 700; line-height: 1; color: var(--cl-warn); background: var(--cl-warn-soft); border: 1px solid var(--cl-warn-line); padding: 2px 7px; border-radius: 999px; }
    .dshlink-device-time { font-size: 11.5px; color: var(--cl-faint); }
    .dshlink-device-actions { display: flex; gap: 6px; flex: none; }
    .dshlink-approve, .dshlink-revoke { flex: none; appearance: none; cursor: pointer; border-radius: 8px; padding: 5px 11px; font: inherit; font-size: 12px; font-weight: 600; transition: background 0.15s ease, border-color 0.15s ease, transform 0.12s ease; }
    .dshlink-approve { border: 1px solid var(--cl-ok-line); background: var(--cl-ok-soft); color: var(--cl-ok); }
    .dshlink-approve:active { transform: scale(0.97); }
    .dshlink-revoke { border: 1px solid var(--cl-danger-line); background: var(--cl-danger-soft); color: var(--cl-danger); }
    .dshlink-revoke:active { transform: scale(0.97); }
    .dshlink-approve:focus-visible, .dshlink-revoke:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }

    /* ---- empty ---- */
    .dshlink-empty { font-size: 12.5px; color: var(--cl-faint); padding: 26px 16px; border-radius: var(--cl-radius-l); border: 1px dashed var(--cl-line-strong); text-align: center; display: flex; flex-direction: column; align-items: center; gap: 8px; background: var(--cl-inset); }
    .dshlink-empty svg { display: block; opacity: 0.75; }

    /* ---- exposure banner ---- */
    .dshlink-expose { display: flex; gap: 10px; align-items: flex-start; margin: 0; font-size: 12px; line-height: 1.55; font-weight: 600; padding: 12px 14px; border-radius: var(--cl-radius-m); background: var(--cl-danger-soft); border: 1px solid var(--cl-danger-line); color: var(--cl-danger); }
    .dshlink-expose-icon { flex: none; margin-top: 1px; }

    /* ---- relay ---- */
    .dshlink-relay-form { display: flex; flex-direction: column; gap: 12px; margin: 0; }
    .dshlink-relay-row { display: flex; flex-direction: column; gap: 7px; }
    .dshlink-relay-row label { font-size: 12px; font-weight: 600; color: var(--cl-muted); }
    .dshlink-field { width: 100%; box-sizing: border-box; border: 1px solid var(--cl-line-strong); border-radius: var(--cl-radius-m); padding: 11px 13px; font: inherit; font-size: 13px; background: var(--cl-surface); color: var(--cl-ink); transition: border-color 0.15s ease, box-shadow 0.15s ease; }
    .dshlink-field::placeholder { color: var(--cl-faint); }
    .dshlink-field:hover { border-color: var(--cl-accent-line); }
    .dshlink-field:focus { outline: none; border-color: var(--cl-accent); box-shadow: 0 0 0 3px var(--cl-accent-soft); }
    .dshlink-relay-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .dshlink-relay-status { font-size: 12.5px; color: var(--cl-muted); margin: 0; }
    .dshlink-relay-status.is-ok { color: var(--cl-ok); font-weight: 600; }
    .dshlink-relay-status.is-error { color: var(--cl-danger); font-weight: 600; }
    .dshlink-relay-online { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 13px 16px; border-radius: var(--cl-radius-m); background: var(--cl-ok-soft); border: 1px solid var(--cl-ok-line); }
    .dshlink-relay-online .dshlink-relay-status { flex: 1; min-width: 120px; display: flex; align-items: center; gap: 9px; }
    .dshlink-relay-online .rdot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--cl-ok); }
    .dshlink-primary { appearance: none; cursor: pointer; border: 0; border-radius: 11px; padding: 10px 18px; color: #fff; font: inherit; font-size: 13px; font-weight: 600; background: var(--cl-accent); transition: background 0.15s ease, transform 0.12s ease; }
    .dshlink-primary:hover:not(:disabled) { background: var(--cl-accent-deep); }
    .dshlink-primary:active:not(:disabled) { transform: translateY(1px); }
    .dshlink-primary:disabled { opacity: 0.45; cursor: not-allowed; }
    .dshlink-primary:focus-visible { outline: 2px solid var(--cl-accent-deep); outline-offset: 2px; }
    .dshlink-secondary { appearance: none; cursor: pointer; border: 1px solid var(--cl-line-strong); border-radius: 10px; padding: 8px 14px; background: var(--cl-surface); font: inherit; font-size: 13px; font-weight: 600; color: var(--cl-ink); transition: background 0.15s ease, border-color 0.15s ease, transform 0.12s ease; }
    .dshlink-secondary:hover { background: var(--cl-inset); border-color: var(--cl-accent-line); }
    .dshlink-secondary:active { transform: translateY(1px); }
    .dshlink-secondary:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    .dshlink-status { font-size: 12.5px; color: var(--cl-muted); padding: 4px 0; }
    .dshlink-status.is-error { color: var(--cl-danger); }

    .dshlink-close { margin-top: 4px; width: 100%; appearance: none; cursor: pointer; border: 1px solid var(--cl-line-strong); border-radius: var(--cl-radius-m); padding: 11px 14px; background: var(--cl-inset); color: var(--cl-ink); font: inherit; font-size: 14px; font-weight: 600; transition: background 0.15s ease, transform 0.12s ease; }
    .dshlink-close:hover { background: var(--cl-line); }
    .dshlink-close:active { transform: scale(0.985); }
    .dshlink-close:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    @media (pointer: coarse) {
      .dshlink-tab { padding-top: 13px; padding-bottom: 15px; }
      .dshlink-field { min-height: 44px; }
    }
    @media (max-width: 400px) {
      .dshlink-pair { grid-template-columns: 1fr; justify-items: center; text-align: center; }
      .dshlink-pair-meta { align-items: center; }
      .dshlink-pair-code-row { justify-content: center; }
    }
  `

  const LINK_GLYPH = jsx('svg', {
    width: 17, height: 17, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 2.1, strokeLinecap: 'round', strokeLinejoin: 'round',
    children: jsx('path', { d: 'M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71' }),
  })

  const COPY_GLYPH = jsxs('svg', {
    width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('rect', { x: 9, y: 9, width: 11, height: 11, rx: 2 }),
      jsx('path', { d: 'M5 15V5a2 2 0 0 1 2-2h10' }),
    ],
  })

  function connectionStatus(info, relay) {
    if (relay?.status === 'online') return { tone: 'accent', text: '云端已连接' }
    if (info) return { tone: 'ok', text: '局域网就绪' }
    return null
  }

  function BrandHeader({ status }) {
    return jsxs('div', {
      className: 'dshlink-brand',
      children: [
        jsx('div', { className: 'dshlink-brand-mark', 'aria-hidden': true, children: LINK_GLYPH }),
        jsxs('div', {
          className: 'dshlink-brand-copy',
          children: [
            jsx('div', { className: 'dshlink-brand-title', children: '手机连接' }),
            jsx('div', { className: 'dshlink-brand-sub', children: '扫码把手机接入这台电脑' }),
          ],
        }),
        status
          ? jsxs('span', {
              className: 'dshlink-status-pill',
              'data-tone': status.tone,
              role: 'status',
              children: [jsx('span', { className: 'd', 'aria-hidden': true }), status.text],
            })
          : null,
      ],
    })
  }

  function devicesVia(devices, via) {
    return (devices ?? []).filter((d) => (d.via === 'relay' ? 'relay' : 'lan') === via)
  }

  function isPendingDevice(device) {
    return device?.status === 'pending'
  }

  function pendingLabel(device) {
    const via = device?.via === 'relay' ? '云端' : '局域网'
    const from = device?.pairedFrom ? ` · 来自 ${device.pairedFrom}` : ''
    return `待确认 · ${via}${from}`
  }

  function ExposureBanner({ exposure }) {
    if (exposure?.level !== 'untrusted' || !exposure.warning) return null
    return jsxs('p', {
      className: 'dshlink-expose',
      children: [
        jsx('svg', {
          className: 'dshlink-expose-icon',
          width: 15, height: 15, viewBox: '0 0 24 24', fill: 'none',
          stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round',
          'aria-hidden': true,
          children: jsx('path', { d: 'M12 9v4M12 17h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z' }),
        }),
        exposure.warning,
      ],
    })
  }

  function ConfirmSection({ value, onChange }) {
    return jsxs('div', {
      className: 'dshlink-section',
      children: [
        jsx('div', {
          className: 'dshlink-section-head',
          children: jsx('div', { className: 'dshlink-section-label', children: '设置' }),
        }),
        jsx('div', {
          className: 'dshlink-group',
          children: jsxs('div', {
            className: 'dshlink-row',
            children: [
              jsxs('div', {
                className: 'dshlink-confirm-copy',
                children: [
                  jsx('div', { className: 'dshlink-confirm-title', children: '配对需本机确认' }),
                  jsx('div', { className: 'dshlink-confirm-sub', children: '开启后，新设备扫码需在本机点「批准」才放行。' }),
                ],
              }),
              jsxs('label', {
                className: 'dshlink-switch',
                children: [
                  jsx('input', {
                    type: 'checkbox',
                    checked: Boolean(value),
                    onChange: (event) => onChange(event.target.checked),
                  }),
                  jsx('span', { className: 'dshlink-switch-track', 'aria-hidden': true }),
                ],
              }),
            ],
          }),
        }),
      ],
    })
  }

  function DeviceRow({ device, isPending, approve, revoke }) {
    return jsxs('div', {
      className: 'dshlink-device' + (isPending ? ' is-pending' : ''),
      children: [
        jsx('span', { className: 'dshlink-device-dot' + (isPending ? ' is-pending' : ''), 'aria-hidden': true }),
        jsxs('div', {
          className: 'dshlink-device-copy',
          children: [
            jsxs('div', {
              className: 'dshlink-device-name-row',
              children: [
                jsx('div', { className: 'dshlink-device-name', children: device.name }),
                isPending ? jsx('span', { className: 'dshlink-device-badge', children: '待确认' }) : null,
              ],
            }),
            jsx('div', { className: 'dshlink-device-time', children: isPending ? pendingLabel(device) : deviceSeenLabel(device.lastSeenAt) }),
          ],
        }),
        jsxs('div', {
          className: 'dshlink-device-actions',
          children: [
            isPending
              ? jsx('button', {
                  type: 'button',
                  className: 'dshlink-approve',
                  onClick: () => approve(device.deviceId),
                  children: '批准',
                })
              : null,
            jsx('button', {
              type: 'button',
              className: 'dshlink-revoke',
              onClick: () => revoke(device.deviceId ? { deviceId: device.deviceId } : { name: device.name }),
              children: isPending ? '拒绝' : '吊销',
            }),
          ],
        }),
      ],
    })
  }

  function EmptyDevices() {
    return jsxs('div', {
      className: 'dshlink-empty',
      children: [
        jsxs('svg', {
          width: 26, height: 26, viewBox: '0 0 24 24', fill: 'none',
          stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
          'aria-hidden': true,
          children: [
            jsx('rect', { x: 5, y: 2, width: 14, height: 20, rx: 3 }),
            jsx('path', { d: 'M11 18h2' }),
          ],
        }),
        '还没有配对设备 · 用手机扫码即可接入',
      ],
    })
  }

  function DeviceSection({ devices, approve, revoke, revokeAll }) {
    const pending = (devices ?? []).filter(isPendingDevice)
    const paired = (devices ?? []).filter((d) => !isPendingDevice(d))
    const total = pending.length + paired.length
    if (!total) return jsx(EmptyDevices, {})
    return jsxs('div', {
      className: 'dshlink-section',
      children: [
        jsxs('div', {
          className: 'dshlink-section-head',
          children: [
            jsxs('div', {
              className: 'dshlink-section-label',
              children: ['设备', jsx('span', { className: 'dshlink-section-count', children: total })],
            }),
            total > 1
              ? jsx('button', { type: 'button', className: 'dshlink-revoke-all', onClick: revokeAll, children: '吊销全部' })
              : null,
          ],
        }),
        jsx('div', {
          className: 'dshlink-group',
          children: [...pending.map((d) => ({ device: d, isPending: true })), ...paired.map((d) => ({ device: d, isPending: false }))].map(({ device, isPending }) =>
            jsx(DeviceRow, {
              device,
              isPending,
              approve,
              revoke,
              key: device.deviceId || device.name,
            }),
          ),
        }),
      ],
    })
  }

  function PairCard({ via, code, label }) {
    const [copied, setCopied] = React.useState(false)
    const copyTimer = React.useRef(0)
    React.useEffect(() => () => clearTimeout(copyTimer.current), [])
    const copyCode = async () => {
      if (!code) return
      try {
        await navigator.clipboard.writeText(code)
        setCopied(true)
        clearTimeout(copyTimer.current)
        copyTimer.current = setTimeout(() => setCopied(false), 2000)
      } catch {}
    }
    return jsxs('div', {
      className: 'dshlink-pair',
      children: [
        jsx('div', {
          className: 'dshlink-qr-plate',
          children: jsx('img', {
            className: 'dshlink-qr',
            key: code || '',
            src: `/dsh-link/qr.png?via=${via}&v=${encodeURIComponent(code || '')}`,
            alt: label,
          }),
        }),
        jsxs('div', {
          className: 'dshlink-pair-meta',
          children: [
            jsx('div', { className: 'dshlink-pair-label', children: label }),
            jsxs('div', {
              className: 'dshlink-pair-code-row',
              children: [
                jsx('div', { className: 'dshlink-code', children: code || '—' }),
                code
                  ? jsxs('button', {
                      type: 'button',
                      className: 'dshlink-copy' + (copied ? ' is-copied' : ''),
                      onClick: copyCode,
                      children: copied ? ['已复制 ✓'] : [COPY_GLYPH, '复制'],
                    })
                  : null,
              ],
            }),
            jsx('p', { className: 'dshlink-pair-hint', children: '用手机 App 扫码，或手动输入配对码。' }),
          ],
        }),
      ],
    })
  }

  function LanBody({ info, devices, approve, revoke, revokeAll, setRequireConfirm }) {
    return jsxs('div', {
      className: 'dshlink-lan',
      children: [
        jsx(PairCard, { via: 'lan', code: info.pairingCode, label: '配对码' }),
        jsx(ConfirmSection, { value: info.requireConfirm, onChange: setRequireConfirm }),
        jsx(DeviceSection, { devices: devicesVia(devices, 'lan'), approve, revoke, revokeAll }),
      ],
    })
  }

  function RelayForm({ relay, onEnroll, onDisconnect }) {
    const [paste, setPaste] = React.useState('')
    const [busy, setBusy] = React.useState(false)
    const [message, setMessage] = React.useState('')
    const [replace, setReplace] = React.useState(false)
    const online = relay?.status === 'online'
    const enrolled = relay?.enrolled === true || online || (Boolean(relay?.agentAddress) && relay?.status === 'offline')
    const parsedEnroll = (() => {
      try { return resolvePaste(paste) } catch { return null }
    })()
    const canSubmit = Boolean(parsedEnroll?.inviteCode) && !busy
    const connectedHost = displayRelayHost(relay?.agentAddress) || OFFICIAL_RELAY_HOST
    const submit = async (event) => {
      event.preventDefault()
      if (!canSubmit) return
      setBusy(true)
      setMessage('')
      try {
        await onEnroll({
          address: parsedEnroll.address,
          inviteCode: parsedEnroll.inviteCode,
          insecureTls: parsedEnroll.insecureTls,
          tlsFingerprint: parsedEnroll.tlsFingerprint,
        })
        setPaste('')
        setReplace(false)
      } catch (err) {
        setMessage(String(err?.message ?? err))
      } finally {
        setBusy(false)
      }
    }
    if (enrolled && !replace) {
      return jsx('div', {
        className: 'dshlink-relay-form',
        children: jsxs('div', {
          className: 'dshlink-relay-online',
          children: [
            online
              ? jsxs('p', {
                  className: 'dshlink-relay-status is-ok',
                  children: [jsx('span', { className: 'rdot', 'aria-hidden': true }), connectedHost],
                })
              : jsx('p', {
                  className: 'dshlink-relay-status' + (relay?.status === 'error' ? ' is-error' : ''),
                  children: relay?.error || `正在连接 ${connectedHost}`,
                }),
            jsxs('div', {
              className: 'dshlink-relay-actions',
              children: [
                jsx('button', {
                  type: 'button',
                  className: 'dshlink-secondary',
                  onClick: onDisconnect,
                  children: '断开',
                }),
                jsx('button', {
                  type: 'button',
                  className: 'dshlink-secondary',
                  onClick: () => { setReplace(true); setMessage('') },
                  children: '更换',
                }),
              ],
            }),
          ],
        }),
      })
    }
    return jsxs('form', {
      className: 'dshlink-relay-form',
      onSubmit: submit,
      children: [
        jsxs('div', {
          className: 'dshlink-relay-row',
          children: [
            jsx('label', { htmlFor: 'dsh-relay-paste', children: '接入码' }),
            jsx('input', {
              id: 'dsh-relay-paste',
              className: 'dshlink-field',
              value: paste,
              placeholder: '粘贴接入码',
              onChange: (event) => { setPaste(event.target.value); setMessage('') },
              autoComplete: 'off',
              spellCheck: false,
            }),
          ],
        }),
        jsxs('div', {
          className: 'dshlink-relay-actions',
          children: [
            jsx('button', {
              type: 'submit',
              className: 'dshlink-primary',
              disabled: !canSubmit,
              children: busy ? '接入中…' : '接入',
            }),
            enrolled ? jsx('button', {
              type: 'button',
              className: 'dshlink-secondary',
              onClick: () => setReplace(false),
              children: '取消',
            }) : null,
          ],
        }),
        message ? jsx('p', { className: 'dshlink-relay-status is-error', children: message }) : null,
      ],
    })
  }

  function RemoteBody({ info, devices, approve, revoke, revokeAll, relay, onEnroll, onDisconnect }) {
    const pairingCode = info?.pairingCode || ''
    const online = relay?.status === 'online'
    return jsxs('div', {
      className: 'dshlink-remote',
      children: [
        jsx(RelayForm, { relay, onEnroll, onDisconnect }),
        online
          ? jsx(PairCard, { via: 'relay', code: pairingCode, label: '云端配对码' })
          : null,
        jsx(DeviceSection, { devices: devicesVia(devices, 'relay'), approve, revoke, revokeAll }),
      ],
    })
  }

  function ConnectionTabs({ active, onChange }) {
    return jsxs('div', {
      className: 'dshlink-tabs',
      role: 'navigation',
      'aria-label': '连接方式',
      children: [
        jsx('button', {
          type: 'button', 'aria-pressed': active === 'lan',
          className: 'dshlink-tab' + (active === 'lan' ? ' is-active' : ''),
          onClick: () => onChange('lan'), children: '局域网',
        }),
        jsx('button', {
          type: 'button', 'aria-pressed': active === 'remote',
          className: 'dshlink-tab' + (active === 'remote' ? ' is-active' : ''),
          onClick: () => onChange('remote'), children: '远端连接',
        }),
      ],
    })
  }

  function ConnectionBody({ info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, load }) {
    const [active, setActive] = React.useState('lan')
    React.useEffect(() => { load?.() }, [active, load])
    if (err) return jsx('div', { className: 'dshlink-status is-error', children: `加载失败：${err}` })
    if (!info) return jsx('div', { className: 'dshlink-status', children: '加载中…' })
    return jsxs('div', {
      className: 'dshlink-connection',
      children: [
        jsx(ConnectionTabs, { active, onChange: setActive }),
        jsx(ExposureBanner, { exposure: info.exposure }),
        active === 'lan'
          ? jsx(LanBody, { info, devices, approve, revoke, revokeAll, setRequireConfirm })
          : jsx(RemoteBody, { info, devices, approve, revoke, revokeAll, relay, onEnroll, onDisconnect }),
      ],
    })
  }

  function usePairData(active) {
    const [info, setInfo] = React.useState(null)
    const [devices, setDevices] = React.useState([])
    const [relay, setRelay] = React.useState(null)
    const [err, setErr] = React.useState('')

    const load = React.useCallback(async () => {
      try {
        const resInfo = await fetch('/dsh-link/pair-info')
        const resDevices = await fetch('/dsh-link/devices')
        const resRelay = await fetch('/dsh-link/relay-status')
        if (!resInfo.ok || !resDevices.ok) throw new Error(`HTTP ${resInfo.status}/${resDevices.status}`)
        setInfo(await resInfo.json())
        const data = await resDevices.json()
        setDevices(data.devices ?? [])
        if (resRelay.ok) setRelay(await resRelay.json())
        setErr('')
      } catch (e) {
        setErr(String(e?.message ?? e))
      }
    }, [])

    const pendingCount = devices.filter((d) => d.status === 'pending').length

    React.useEffect(() => {
      if (!active) return undefined
      load()
      const timer = setInterval(load, pendingCount > 0 ? 2000 : 8000)
      return () => clearInterval(timer)
    }, [active, load, pendingCount])

    const revoke = async (target) => {
      const body = typeof target === 'string' ? { name: target } : (target ?? {})
      try {
        await fetch('/dsh-link/revoke', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body),
        })
      } finally {
        load()
      }
    }

    const approve = async (deviceId) => {
      try {
        await fetch('/dsh-link/pair-approve', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ deviceId }),
        })
      } finally {
        load()
      }
    }

    const revokeAll = async () => {
      if (!window.confirm('吊销全部已配对设备？手机需要重新扫码。')) return
      try {
        await fetch('/dsh-link/revoke-all', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: '{}',
        })
      } finally {
        load()
      }
    }

    const setRequireConfirm = async (requireConfirm) => {
      try {
        await fetch('/dsh-link/pair-settings', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ requireConfirm }),
        })
      } finally {
        load()
      }
    }

    const onEnroll = async ({ address, inviteCode, insecureTls, tlsFingerprint }) => {
      const res = await fetch('/dsh-link/relay-enroll', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ address, inviteCode, insecureTls, tlsFingerprint }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
      await load()
    }
    const onDisconnect = async () => {
      await fetch('/dsh-link/relay-disconnect', { method: 'POST' })
      await load()
    }
    return { info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, load }
  }

  function LinkPanel() {
    const [open, setOpen] = React.useState(false)
    const { info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, load } = usePairData(open)

    React.useEffect(() => {
      window.__dshlinkOpenPanel = () => setOpen(true)
      return () => {
        delete window.__dshlinkOpenPanel
      }
    }, [])

    React.useEffect(() => {
      if (!open) return undefined
      const onKey = (event) => {
        if (event.key === 'Escape') setOpen(false)
      }
      window.addEventListener('keydown', onKey)
      return () => window.removeEventListener('keydown', onKey)
    }, [open])

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        open
          ? jsx('div', {
              className: 'dshlink-backdrop',
              onClick: () => setOpen(false),
              children: jsxs('div', {
                className: 'dshlink-panel dshlink-root',
                onClick: (e) => e.stopPropagation(),
                children: [
                  jsx(BrandHeader, { status: connectionStatus(info, relay) }),
                  jsx(ConnectionBody, { info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, load }),
                  jsx('button', {
                    type: 'button',
                    className: 'dshlink-close',
                    onClick: () => setOpen(false),
                    children: '关闭',
                  }),
                ],
              }),
            })
          : null,
      ],
    })
  }

  function DshLinkSettingsSection() {
    const { info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, load } = usePairData(true)

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        jsxs('div', {
          className: 'dshlink-settings dshlink-root',
          children: [
            jsx(BrandHeader, { status: connectionStatus(info, relay) }),
            jsx(ConnectionBody, { info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, load }),
          ],
        }),
      ],
    })
  }

  function apply(ctx) {
    ctx.effect(
      () =>
        ctx.slots.inject(
          'settings.section',
          () =>
            ctx.slots.register(
              {
                name: 'settings.section',
                id: 'dsh-links',
                order: 25,
                label: () => '手机连接',
                locale: 'dsh-links',
              },
              () => jsx(DshLinkSettingsSection, {}),
            ),
        ),
      'dsh-links: settings.section',
    )
  }

  return { apply, inject: ['slots'] }
}
