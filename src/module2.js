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
    const d = Date.now() - lastSeenAt
    if (d < 6e4) return '刚刚在线'
    if (d < 36e5) return `${d / 6e4 | 0} 分钟前在线`
    if (d < 864e5) return `${d / 36e5 | 0} 小时前在线`
    return `${d / 864e5 | 0} 天前在线`
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
      controlUrl: publicControlURL(parsed.searchParams.get('c') || parsed.searchParams.get('control') || ''),
    }
  }

  function publicControlURL(raw) {
    const text = String(raw ?? '').trim()
    if (!text || text.length > 512) return ''
    let parsed
    try { parsed = new URL(text) } catch { return '' }
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search || parsed.hash) return ''
    const host = String(parsed.hostname || '').toLowerCase().replace(/^\[|\]$/g, '')
    if (!host) return ''
    const locals = new Set(['localhost', 'localhost.', '::1', '0.0.0.0', '::', '0:0:0:0:0:0:0:0', '0:0:0:0:0:0:0:1'])
    if (locals.has(host) || host === '127.0.0.1' || host.startsWith('127.')) return ''
    const path = String(parsed.pathname || '').replace(/\/+$/, '')
    return path && path !== '/' ? `https://${parsed.host}${path}` : `https://${parsed.host}`
  }

  const OFFICIAL_RELAY_HOST = 'relay.dshlinks.com'
  const OFFICIAL_ENROLL_API = 'https://enroll.dshlinks.com/api/official-invite'
  const OFFICIAL_RELAY_TLS_SHA256 = '6fbe09cb8809714ec1c9eec1b982212bdc78e06870abd5ed21442bd4e6d3f9ea'

  function looksLikeInviteCode(raw) {
    return /^[A-Za-z0-9_-]{16,64}$/.test(String(raw ?? '').trim())
  }

  function isOfficialRelayHost(address) {
    const raw = String(address ?? '').trim().toLowerCase()
    if (!raw) return true
    return raw === OFFICIAL_RELAY_HOST || raw.startsWith(OFFICIAL_RELAY_HOST + ':')
  }

  function resolvePaste(raw, previous = {}) {
    const parsed = parseEnrollText(raw)
    if (parsed) {
      if (parsed.insecureTls) return parsed
      if (isOfficialRelayHost(parsed.address)) {
        return {
          address: parsed.address,
          inviteCode: parsed.inviteCode,
          insecureTls: true,
          tlsFingerprint: OFFICIAL_RELAY_TLS_SHA256,
          controlUrl: parsed.controlUrl,
        }
      }
      return parsed
    }
    const invite = String(raw ?? '').trim()
    if (!looksLikeInviteCode(invite)) return null
    const previousAddress = String(previous.address ?? '').trim()
    if (previousAddress && !isOfficialRelayHost(previousAddress)) {
      return {
        address: previousAddress,
        inviteCode: invite,
        insecureTls: previous.insecureTls === true,
        tlsFingerprint: previous.tlsFingerprint || '',
      }
    }
    return {
      address: OFFICIAL_RELAY_HOST,
      inviteCode: invite,
      insecureTls: true,
      tlsFingerprint: OFFICIAL_RELAY_TLS_SHA256,
    }
  }

  const STYLE = `
    .dshlink-root {
      /* Hallmark · component: settings panel · genre: ultra-clean minimal · theme: DeepSeek cyan/teal-blue
       *   macrostructure: segmented-tabs · gallery-pedestal pair card · flat unnested hardware roster
       *   craft: 1.6px geometric line iconography · Apple/Linear elegance · micro-beveled pedestals */
      --cl-bg: #f8fafc;
      --cl-surface: #ffffff;
      --cl-surface-hover: #fcfdfe;
      --cl-inset: #f1f5f9;
      --cl-ink: #0b0f19;
      --cl-muted: #4b5563;
      --cl-faint: #9ca3af;
      --cl-line: rgba(15, 23, 42, 0.08);
      --cl-line-subtle: rgba(15, 23, 42, 0.04);
      --cl-line-strong: rgba(15, 23, 42, 0.15);
      --cl-accent: #0284c7;
      --cl-accent-deep: #0369a1;
      --cl-accent-bright: #38bdf8;
      --cl-accent-soft: rgba(2, 132, 199, 0.08);
      --cl-accent-line: rgba(2, 132, 199, 0.22);
      --cl-accent-text: #0369a1;
      --cl-ok: #059669;
      --cl-ok-soft: rgba(5, 150, 105, 0.08);
      --cl-ok-line: rgba(5, 150, 105, 0.22);
      --cl-danger: #e11d48;
      --cl-danger-soft: rgba(225, 29, 72, 0.08);
      --cl-danger-line: rgba(225, 29, 72, 0.22);
      --cl-warn: #d97706;
      --cl-warn-soft: rgba(217, 119, 6, 0.08);
      --cl-warn-line: rgba(217, 119, 6, 0.22);
      --cl-radius-s: 8px;
      --cl-radius-m: 12px;
      --cl-radius-l: 16px;
      --cl-ease: cubic-bezier(0.16, 1, 0.3, 1);
      --cl-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
      --cl-mono: ui-monospace, SFMono-Regular, "Roboto Mono", Menlo, Consolas, monospace;
      font-family: var(--cl-sans);
      color: var(--cl-ink);
      -webkit-font-smoothing: antialiased;
    }
    :root.dark .dshlink-root,
    [data-theme="dark"] .dshlink-root,
    @media (prefers-color-scheme: dark) {
      .dshlink-root {
        --cl-bg: #0b0d13;
        --cl-surface: #121622;
        --cl-surface-hover: #161b29;
        --cl-inset: #0c0e16;
        --cl-ink: #f3f4f6;
        --cl-muted: #9ca3af;
        --cl-faint: #6b7280;
        --cl-line: rgba(255, 255, 255, 0.08);
        --cl-line-subtle: rgba(255, 255, 255, 0.04);
        --cl-line-strong: rgba(255, 255, 255, 0.16);
        --cl-accent: #38bdf8;
        --cl-accent-deep: #0284c7;
        --cl-accent-bright: #7dd3fc;
        --cl-accent-soft: rgba(56, 189, 248, 0.12);
        --cl-accent-line: rgba(56, 189, 248, 0.28);
        --cl-accent-text: #38bdf8;
        --cl-ok: #10b981;
        --cl-ok-soft: rgba(16, 185, 129, 0.12);
        --cl-ok-line: rgba(16, 185, 129, 0.26);
        --cl-danger: #fb7185;
        --cl-danger-soft: rgba(251, 113, 133, 0.12);
        --cl-danger-line: rgba(251, 113, 133, 0.26);
        --cl-warn: #fbbf24;
        --cl-warn-soft: rgba(251, 191, 36, 0.12);
        --cl-warn-line: rgba(251, 191, 36, 0.28);
      }
    }

    /* modal shell (used by LinkPanel) */
    .dshlink-backdrop {
      position: fixed; inset: 0; z-index: 99995;
      background: rgba(11, 13, 19, 0.58);
      backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
      display: flex; align-items: center; justify-content: center; padding: 20px;
      animation: dshlink-fadein 0.2s ease;
    }
    @keyframes dshlink-fadein { from { opacity: 0 } to { opacity: 1 } }
    .dshlink-panel {
      width: min(440px, 100%); max-height: 86vh; overflow: auto;
      border-radius: var(--cl-radius-l); background: var(--cl-bg);
      border: 1px solid var(--cl-line);
      box-shadow: 0 20px 48px -12px rgba(0, 0, 0, 0.32), 0 0 0 1px var(--cl-line-subtle);
      padding: 24px 22px; display: flex; flex-direction: column; gap: 18px;
      animation: dshlink-rise 0.26s var(--cl-ease);
    }
    @keyframes dshlink-rise {
      from { opacity: 0; transform: translateY(10px) scale(0.985) }
      to { opacity: 1; transform: none }
    }
    @media (prefers-reduced-motion: reduce) {
      .dshlink-backdrop, .dshlink-panel { animation-duration: 0.01s }
      .dshlink-device-dot.is-pending::after { animation: none }
    }

    .dshlink-settings { display: flex; flex-direction: column; gap: 16px; max-width: 452px; margin: 0 auto; }

    /* ---- header ---- */
    .dshlink-brand { display: flex; align-items: center; gap: 12px; }
    .dshlink-brand-mark {
      flex: none; width: 36px; height: 36px; border-radius: 10px;
      display: flex; align-items: center; justify-content: center;
      color: var(--cl-accent-text);
      background: linear-gradient(135deg, var(--cl-accent-soft), transparent);
      border: 1px solid var(--cl-accent-line);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
    }
    .dshlink-brand-mark svg { display: block; }
    .dshlink-brand-copy { display: flex; flex-direction: column; gap: 1px; flex: 1; min-width: 0; }
    .dshlink-brand-title {
      font-family: var(--cl-sans); font-size: 17.5px; font-weight: 600;
      letter-spacing: -0.015em; line-height: 1.25; color: var(--cl-ink); margin: 0;
    }
    .dshlink-brand-sub { font-size: 11.5px; color: var(--cl-faint); margin: 0; }
    .dshlink-status-pill {
      flex: none; display: inline-flex; align-items: center; gap: 6px;
      font-size: 11.5px; font-weight: 500; color: var(--cl-muted); white-space: nowrap;
      padding: 3px 9px; border-radius: 999px; background: var(--cl-inset); border: 1px solid var(--cl-line);
      transition: all 0.2s ease;
    }
    .dshlink-status-pill .d { width: 6px; height: 6px; border-radius: 50%; background: var(--cl-ok); }
    .dshlink-status-pill[data-tone="accent"] { color: var(--cl-accent-text); background: var(--cl-accent-soft); border-color: var(--cl-accent-line); }
    .dshlink-status-pill[data-tone="accent"] .d { background: var(--cl-accent); box-shadow: 0 0 5px var(--cl-accent); }
    .dshlink-status-pill[data-tone="warn"] { color: var(--cl-warn); background: var(--cl-warn-soft); border-color: var(--cl-warn-line); }
    .dshlink-status-pill[data-tone="warn"] .d { background: var(--cl-warn); box-shadow: 0 0 5px var(--cl-warn); }
    .dshlink-status-pill[data-tone="ok"] { color: var(--cl-ok); background: var(--cl-ok-soft); border-color: var(--cl-ok-line); }
    .dshlink-status-pill[data-tone="ok"] .d { background: var(--cl-ok); box-shadow: 0 0 5px var(--cl-ok); }

    /* ---- segmented tabs ---- */
    .dshlink-tabs {
      display: grid; grid-template-columns: 1fr 1fr; gap: 3px; padding: 3px;
      background: var(--cl-inset); border-radius: var(--cl-radius-m); border: 1px solid var(--cl-line-subtle);
    }
    .dshlink-tab {
      position: relative; appearance: none; cursor: pointer; border: 0; background: transparent;
      color: var(--cl-muted); font: inherit; font-size: 13px; font-weight: 500; padding: 7px 12px;
      border-radius: calc(var(--cl-radius-m) - 3px); white-space: nowrap;
      transition: all 0.18s var(--cl-ease); display: inline-flex; align-items: center; justify-content: center; gap: 7px;
    }
    .dshlink-tab:hover:not(.is-active) { color: var(--cl-ink); background: rgba(125, 125, 125, 0.05); }
    .dshlink-tab.is-active {
      color: var(--cl-ink); font-weight: 600; background: var(--cl-surface);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05), 0 0.5px 1px rgba(0, 0, 0, 0.04);
    }
    .dshlink-tab:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 1px; }
    .dshlink-tab-glyph { display: inline-flex; align-items: center; justify-content: center; opacity: 0.85; }
    .dshlink-tab-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--cl-ok); box-shadow: 0 0 5px var(--cl-ok); }

    .dshlink-connection { display: flex; flex-direction: column; gap: 16px; }
    .dshlink-lan, .dshlink-remote { display: flex; flex-direction: column; gap: 16px; }

    /* ---- pending request hero card (state morphing) ---- */
    .dshlink-pending-hero {
      display: flex; flex-direction: column; gap: 12px;
      padding: 15px 18px; border-radius: var(--cl-radius-l);
      background: var(--cl-warn-soft); border: 1px solid var(--cl-warn-line);
      backdrop-filter: blur(8px);
      box-shadow: 0 4px 20px -2px rgba(217, 119, 6, 0.08);
      animation: dshlink-rise 0.25s var(--cl-ease);
    }
    .dshlink-pending-header { display: flex; align-items: center; gap: 8px; }
    .dshlink-pending-pulse {
      position: relative; width: 7px; height: 7px; border-radius: 50%;
      background: var(--cl-warn);
    }
    .dshlink-pending-pulse::after {
      content: ""; position: absolute; inset: -4px; border-radius: 50%;
      border: 1.5px solid var(--cl-warn); opacity: 0;
      animation: dshlink-ping 1.6s ease-out infinite;
    }
    .dshlink-pending-badge {
      font-size: 11.5px; font-weight: 600; letter-spacing: 0.04em;
      color: var(--cl-warn);
    }
    .dshlink-pending-body {
      display: flex; align-items: center; justify-content: space-between;
      gap: 12px; flex-wrap: wrap;
    }
    .dshlink-pending-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .dshlink-pending-name-row { display: flex; align-items: center; gap: 6px; }
    .dshlink-pending-device-glyph { display: inline-flex; align-items: center; color: var(--cl-warn); }
    .dshlink-pending-name {
      font-size: 14.5px; font-weight: 600; color: var(--cl-ink);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .dshlink-pending-meta { font-size: 12px; color: var(--cl-muted); }
    .dshlink-pending-actions { display: flex; align-items: center; gap: 8px; flex: none; }
    .dshlink-hero-approve {
      appearance: none; cursor: pointer; border: 1px solid var(--cl-ok-line);
      border-radius: 8px; padding: 6px 14px; font: inherit; font-size: 12.5px; font-weight: 600;
      background: var(--cl-ok); color: #ffffff;
      box-shadow: 0 1px 6px rgba(5, 150, 105, 0.25);
      transition: all 0.15s ease; display: inline-flex; align-items: center; gap: 5px;
    }
    .dshlink-hero-approve:hover { filter: brightness(1.06); transform: translateY(-0.5px); }
    .dshlink-hero-approve:active { transform: translateY(0.5px); }
    .dshlink-hero-approve:focus-visible { outline: 2px solid var(--cl-ok); outline-offset: 2px; }
    .dshlink-hero-reject {
      appearance: none; cursor: pointer; border: 1px solid var(--cl-line);
      border-radius: 8px; padding: 6px 12px; font: inherit; font-size: 12.5px; font-weight: 500;
      background: var(--cl-surface); color: var(--cl-muted);
      transition: all 0.15s ease; display: inline-flex; align-items: center; gap: 4px;
    }
    .dshlink-hero-reject:hover { background: var(--cl-danger-soft); border-color: var(--cl-danger-line); color: var(--cl-danger); }
    .dshlink-hero-reject:active { transform: translateY(0.5px); }
    .dshlink-hero-reject:focus-visible { outline: 2px solid var(--cl-danger); outline-offset: 2px; }
    .dshlink-pending-more { font-size: 11.5px; color: var(--cl-muted); }

    /* ---- pairing (horizontal gallery pedestal) ---- */
    .dshlink-pair {
      display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 20px;
      align-items: center; padding: 18px 20px; border-radius: var(--cl-radius-l);
      background: var(--cl-surface); border: 1px solid var(--cl-line);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
    }
    .dshlink-qr-plate {
      appearance: none; cursor: zoom-in; width: 148px; height: 148px;
      border-radius: var(--cl-radius-m); background: #ffffff; padding: 8px;
      border: 1px solid var(--cl-line);
      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.04);
      transition: all 0.2s var(--cl-ease);
      display: flex; align-items: center; justify-content: center;
    }
    .dshlink-qr-plate:hover {
      transform: translateY(-1px) scale(1.015);
      box-shadow: 0 8px 24px -4px rgba(0, 0, 0, 0.08);
      border-color: var(--cl-accent-line);
    }
    .dshlink-qr { display: block; width: 100%; height: 100%; border-radius: 6px; }
    .dshlink-qr-zoom {
      position: fixed; inset: 0; z-index: 9999; display: flex; flex-direction: column;
      align-items: center; justify-content: center; gap: 14px;
      background: rgba(11, 13, 19, 0.68); backdrop-filter: blur(8px); cursor: zoom-out;
    }
    .dshlink-qr-zoom img {
      width: min(72vmin, 360px); height: auto; padding: 14px; border-radius: var(--cl-radius-l);
      background: #ffffff; box-shadow: 0 24px 64px rgba(0, 0, 0, 0.35);
    }
    .dshlink-qr-zoom-hint { color: #f3f4f6; font-size: 12px; font-weight: 500; opacity: 0.85; }
    .dshlink-pair-meta { display: flex; flex-direction: column; gap: 9px; min-width: 0; }
    .dshlink-pair-label { font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--cl-faint); }
    .dshlink-pair-code-row { display: flex; align-items: center; gap: 10px; min-width: 0; flex-wrap: wrap; }
    .dshlink-code {
      font-family: var(--cl-mono); font-size: 22px; font-weight: 600;
      letter-spacing: 0.16em; font-variant-numeric: tabular-nums; line-height: 1;
      color: var(--cl-ink); margin: 0; overflow-wrap: anywhere;
      padding: 7px 12px; border-radius: var(--cl-radius-s);
      background: var(--cl-inset); border: 1px solid var(--cl-line-subtle);
      display: inline-flex; align-items: center;
    }
    .dshlink-copy {
      appearance: none; cursor: pointer; border: 1px solid var(--cl-line);
      background: var(--cl-surface); color: var(--cl-muted); font: inherit;
      font-size: 12px; font-weight: 500; padding: 5px 10px; border-radius: var(--cl-radius-s);
      align-self: center; display: inline-flex; align-items: center; gap: 6px;
      transition: all 0.15s ease;
    }
    .dshlink-copy:hover { background: var(--cl-inset); color: var(--cl-ink); border-color: var(--cl-line-strong); }
    .dshlink-copy.is-copied { color: var(--cl-ok); background: var(--cl-ok-soft); border-color: var(--cl-ok-line); font-weight: 600; }
    .dshlink-copy:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }
    .dshlink-copy svg { display: block; }
    .dshlink-pair-hint { margin: 2px 0 0; font-size: 12px; line-height: 1.55; color: var(--cl-muted); }

    /* ---- grouped section ---- */
    .dshlink-section { display: flex; flex-direction: column; gap: 10px; }
    .dshlink-section-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-width: 0; padding: 0 2px; }
    .dshlink-section-label { display: flex; align-items: center; gap: 8px; font-size: 12.5px; font-weight: 600; letter-spacing: 0.02em; color: var(--cl-muted); }
    .dshlink-section-count { min-width: 19px; height: 19px; padding: 0 6px; border-radius: 999px; display: inline-flex; align-items: center; justify-content: center; background: var(--cl-accent-soft); color: var(--cl-accent-text); font-size: 11px; font-weight: 700; font-variant-numeric: tabular-nums; }
    .dshlink-revoke-all { appearance: none; cursor: pointer; border: 0; background: transparent; color: var(--cl-danger); padding: 4px 8px; border-radius: 8px; font: inherit; font-size: 12px; font-weight: 600; transition: background 0.15s ease; }
    .dshlink-revoke-all:hover { background: var(--cl-danger-soft); }
    .dshlink-revoke-all:focus-visible { outline: 2px solid var(--cl-danger); outline-offset: 2px; }

    /* ---- flat confirm row (no redundant subheadings) ---- */
    .dshlink-confirm-row {
      display: flex; align-items: center; justify-content: space-between; gap: 14px;
      padding: 13px 18px; border-radius: var(--cl-radius-l);
      background: var(--cl-surface); border: 1px solid var(--cl-line);
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
      transition: border-color 0.15s ease, background 0.15s ease;
    }
    .dshlink-confirm-row:hover { border-color: var(--cl-accent-line); background: var(--cl-surface-hover); }

    /* ---- group container + rows ---- */
    .dshlink-group { background: var(--cl-surface); border: 1px solid var(--cl-line); border-radius: var(--cl-radius-l); overflow: hidden; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02); }
    .dshlink-row { display: flex; align-items: center; gap: 12px; padding: 14px 18px; }
    .dshlink-row + .dshlink-row, .dshlink-device + .dshlink-device { border-top: 1px solid var(--cl-line); }

    /* ---- confirm row contents ---- */
    .dshlink-confirm-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .dshlink-confirm-title { font-size: 13px; font-weight: 600; color: var(--cl-ink); }
    .dshlink-confirm-sub { font-size: 11.5px; color: var(--cl-faint); line-height: 1.4; }
    .dshlink-switch { position: relative; display: inline-flex; align-items: center; cursor: pointer; user-select: none; flex: none; }
    .dshlink-switch input { position: absolute; opacity: 0; width: 1px; height: 1px; }
    .dshlink-switch-track { position: relative; width: 38px; height: 22px; flex: none; border-radius: 999px; background: var(--cl-line-strong); transition: background 0.2s var(--cl-ease); }
    .dshlink-switch-track::after { content: ""; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: #ffffff; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.24); transition: transform 0.2s var(--cl-ease); }
    .dshlink-switch input:checked + .dshlink-switch-track { background: var(--cl-accent); }
    .dshlink-switch input:checked + .dshlink-switch-track::after { transform: translateX(16px); }
    .dshlink-switch input:focus-visible + .dshlink-switch-track { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    /* ---- device rows (inside group) ---- */
    .dshlink-device { display: flex; align-items: center; gap: 11px; padding: 12px 18px; transition: background 0.15s ease; }
    .dshlink-device:hover { background: var(--cl-inset); }
    .dshlink-device.is-pending { background: var(--cl-warn-soft); }
    .dshlink-device-dot { position: relative; flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--cl-ok); box-shadow: 0 0 6px var(--cl-ok-line); }
    .dshlink-device-dot.is-pending { background: var(--cl-warn); box-shadow: 0 0 6px var(--cl-warn-line); }
    .dshlink-device-dot.is-pending::after { content: ""; position: absolute; inset: -4px; border-radius: 50%; border: 1.5px solid var(--cl-warn); opacity: 0; animation: dshlink-ping 1.6s ease-out infinite; }
    @keyframes dshlink-ping { 0% { transform: scale(0.5); opacity: 0.6 } 70%, 100% { transform: scale(1.5); opacity: 0 } }
    .dshlink-device-icon { display: inline-flex; align-items: center; justify-content: center; color: var(--cl-faint); flex: none; }
    .dshlink-device-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px; }
    .dshlink-device-name-row { display: flex; align-items: center; gap: 7px; min-width: 0; }
    .dshlink-device-name { font-size: 13.5px; font-weight: 600; color: var(--cl-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .dshlink-device-badge { flex: none; font-size: 10px; font-weight: 600; line-height: 1; color: var(--cl-warn); background: var(--cl-warn-soft); border: 1px solid var(--cl-warn-line); padding: 2px 6px; border-radius: 999px; }
    .dshlink-device-time { font-size: 11.5px; color: var(--cl-faint); }
    .dshlink-device-actions { display: flex; gap: 6px; flex: none; }
    .dshlink-approve, .dshlink-revoke {
      flex: none; appearance: none; cursor: pointer; border-radius: 7px; padding: 4px 10px;
      font: inherit; font-size: 11.5px; font-weight: 600; transition: all 0.15s ease;
      display: inline-flex; align-items: center; gap: 4px;
    }
    .dshlink-approve { border: 1px solid var(--cl-ok-line); background: var(--cl-ok-soft); color: var(--cl-ok); }
    .dshlink-approve:hover { background: var(--cl-ok); color: #ffffff; }
    .dshlink-approve:active { transform: scale(0.97); }
    .dshlink-revoke { border: 1px solid var(--cl-line); background: var(--cl-surface); color: var(--cl-muted); }
    .dshlink-revoke:hover { border-color: var(--cl-danger-line); background: var(--cl-danger-soft); color: var(--cl-danger); }
    .dshlink-revoke:active { transform: scale(0.97); }
    .dshlink-approve:focus-visible, .dshlink-revoke:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }

    /* ---- empty ---- */
    .dshlink-empty {
      font-size: 12.5px; color: var(--cl-faint); padding: 24px 16px; border-radius: var(--cl-radius-l);
      border: 1px dashed var(--cl-line-strong); text-align: center; display: flex; flex-direction: column;
      align-items: center; gap: 10px; background: var(--cl-inset);
    }
    .dshlink-empty-glyph { display: flex; align-items: center; justify-content: center; color: var(--cl-faint); opacity: 0.85; }

    /* ---- exposure banner ---- */
    .dshlink-expose {
      display: flex; gap: 10px; align-items: center; margin: 0; font-size: 11.5px; line-height: 1.5;
      font-weight: 500; padding: 10px 14px; border-radius: var(--cl-radius-m);
      background: var(--cl-danger-soft); border: 1px solid var(--cl-danger-line); color: var(--cl-danger);
    }
    .dshlink-expose-icon { flex: none; display: flex; align-items: center; }
    .dshlink-expose-text { flex: 1; min-width: 0; }

    /* ---- relay ---- */
    .dshlink-relay-form { display: flex; flex-direction: column; gap: 12px; margin: 0; }
    .dshlink-relay-row { display: flex; flex-direction: column; gap: 7px; }
    .dshlink-relay-row label { font-size: 12px; font-weight: 600; color: var(--cl-muted); }
    .dshlink-field { width: 100%; box-sizing: border-box; border: 1px solid var(--cl-line-strong); border-radius: var(--cl-radius-m); padding: 11px 13px; font: inherit; font-size: 13px; background: var(--cl-surface); color: var(--cl-ink); transition: border-color 0.15s ease, box-shadow 0.15s ease; }
    .dshlink-field::placeholder { color: var(--cl-faint); }
    .dshlink-field:hover { border-color: var(--cl-accent-line); }
    .dshlink-field:focus { outline: none; border-color: var(--cl-accent); box-shadow: 0 0 0 3px var(--cl-accent-soft); }
    .dshlink-relay-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .dshlink-steps { display: flex; flex-direction: column; gap: 0; border: 1px solid var(--cl-line); border-radius: var(--cl-radius-l); background: var(--cl-surface); overflow: hidden; }
    .dshlink-step { display: flex; gap: 12px; padding: 14px 16px; }
    .dshlink-step + .dshlink-step { border-top: 1px solid var(--cl-line); }
    .dshlink-step-num { flex: none; width: 22px; height: 22px; border-radius: 50%; background: var(--cl-accent-soft); color: var(--cl-accent-text); font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; margin-top: 1px; }
    .dshlink-step-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
    .dshlink-step-title { font-size: 13px; font-weight: 700; color: var(--cl-ink); margin: 0; }
    .dshlink-step-desc { font-size: 12px; line-height: 1.55; color: var(--cl-muted); margin: 0; }
    .dshlink-fetch { align-self: flex-start; appearance: none; cursor: pointer; border: 1px solid var(--cl-accent-line); border-radius: 8px; padding: 6px 12px; background: var(--cl-accent-soft); color: var(--cl-accent-text); font: inherit; font-size: 12.5px; font-weight: 600; transition: background 0.15s ease, border-color 0.15s ease; }
    .dshlink-fetch:hover:not(:disabled) { background: var(--cl-accent-line); }
    .dshlink-fetch:active:not(:disabled) { transform: translateY(1px); }
    .dshlink-fetch:disabled { opacity: 0.55; cursor: not-allowed; }
    .dshlink-fetch:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }
    .dshlink-relay-control {
      appearance: none; cursor: pointer; border: 0; background: transparent;
      color: var(--cl-accent-text); font: inherit; font-size: 12px; font-weight: 500;
      padding: 4px 6px; border-radius: 6px; text-decoration: none; align-self: center;
      display: inline-flex; align-items: center; gap: 4px; transition: background 0.15s ease;
    }
    .dshlink-relay-control:hover { background: var(--cl-accent-soft); }
    .dshlink-relay-control:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }
    .dshlink-relay-status { font-size: 12.5px; color: var(--cl-muted); margin: 0; }
    .dshlink-relay-status.is-ok { color: var(--cl-ok); font-weight: 600; }
    .dshlink-relay-status.is-error { color: var(--cl-danger); font-weight: 600; }
    .dshlink-relay-replaced { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; width: 100%; }
    .dshlink-relay-online { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 13px 16px; border-radius: var(--cl-radius-m); background: var(--cl-ok-soft); border: 1px solid var(--cl-ok-line); }
    .dshlink-relay-online .dshlink-relay-status { flex: 1; min-width: 120px; display: flex; align-items: center; gap: 9px; }
    .dshlink-relay-online .rdot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--cl-ok); }
    .dshlink-primary {
      appearance: none; cursor: pointer; border: 1px solid var(--cl-accent-deep);
      border-radius: 9px; padding: 8px 16px; color: #ffffff; font: inherit; font-size: 12.5px; font-weight: 600;
      background: var(--cl-accent); box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.15);
      transition: all 0.15s ease;
    }
    .dshlink-primary:hover:not(:disabled) { background: var(--cl-accent-deep); transform: translateY(-0.5px); box-shadow: 0 2px 6px rgba(0, 0, 0, 0.18); }
    .dshlink-primary:active:not(:disabled) { transform: translateY(0.5px); }
    .dshlink-primary:disabled { opacity: 0.45; cursor: not-allowed; }
    .dshlink-primary:focus-visible { outline: 2px solid var(--cl-accent-deep); outline-offset: 2px; }
    .dshlink-secondary {
      appearance: none; cursor: pointer; border: 1px solid var(--cl-line-strong);
      border-radius: 9px; padding: 7px 13px; background: var(--cl-surface); font: inherit;
      font-size: 12.5px; font-weight: 500; color: var(--cl-ink); box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
      transition: all 0.15s ease;
    }
    .dshlink-secondary:hover { background: var(--cl-inset); border-color: var(--cl-accent-line); color: var(--cl-ink); }
    .dshlink-secondary:active { transform: translateY(0.5px); }
    .dshlink-secondary:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    .dshlink-status { font-size: 12.5px; color: var(--cl-muted); padding: 4px 0; }
    .dshlink-status.is-error { color: var(--cl-danger); }

    .dshlink-close {
      margin-top: 4px; width: 100%; appearance: none; cursor: pointer; border: 1px solid var(--cl-line);
      border-radius: var(--cl-radius-m); padding: 10px 14px; background: var(--cl-inset); color: var(--cl-ink);
      font: inherit; font-size: 13px; font-weight: 600; transition: all 0.15s ease;
    }
    .dshlink-close:hover { background: var(--cl-surface); border-color: var(--cl-line-strong); }
    .dshlink-close:active { transform: scale(0.99); }
    .dshlink-close:focus-visible { outline: 2px solid var(--cl-accent); outline-offset: 2px; }

    @media (pointer: coarse) {
      .dshlink-tab { padding-top: 10px; padding-bottom: 10px; }
      .dshlink-field { min-height: 44px; }
    }
    @media (max-width: 400px) {
      .dshlink-pair { grid-template-columns: 1fr; justify-items: center; text-align: center; }
      .dshlink-pair-meta { align-items: center; }
      .dshlink-pair-code-row { justify-content: center; }
    }
  `

  /* ========================================================================
   * 精密矢量微图标库 (Bespoke Minimal Iconography System)
   * 规范：24x24 视口 · 统一 1.6px/2.2px 圆角几何描边 · 纯净通透
   * ======================================================================== */
  const BRAND_GLYPH = jsxs('svg', {
    width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('rect', { x: 3, y: 3, width: 10, height: 18, rx: 2.5 }),
      jsx('path', { d: 'M7 17.5h2' }),
      jsx('path', { d: 'M16 8.5a4 4 0 0 1 0 7' }),
      jsx('path', { d: 'M19 5.5a8 8 0 0 1 0 13' }),
    ],
  })

  const COPY_GLYPH = jsxs('svg', {
    width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('rect', { x: 8.5, y: 8.5, width: 11.5, height: 11.5, rx: 2.5 }),
      jsx('path', { d: 'M15.5 8.5V5.5a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3' }),
    ],
  })

  const CHECK_GLYPH = jsx('svg', {
    width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: jsx('path', { d: 'M4.5 12.5l5 5 10-10' }),
  })

  const LAN_GLYPH = jsxs('svg', {
    width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('path', { d: 'M4.5 10.5a11 11 0 0 1 15 0' }),
      jsx('path', { d: 'M7.8 14a6.5 6.5 0 0 1 8.4 0' }),
      jsx('circle', { cx: 12, cy: 17.5, r: 1.25, fill: 'currentColor' }),
    ],
  })

  const CLOUD_GLYPH = jsx('svg', {
    width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: jsx('path', { d: 'M6.5 18a4.5 4.5 0 0 1-.8-8.9A6 6 0 0 1 17.2 8.5 4.5 4.5 0 0 1 18.5 18H6.5Z' }),
  })

  const PHONE_MICRO_GLYPH = jsxs('svg', {
    width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('rect', { x: 5, y: 2, width: 14, height: 20, rx: 3 }),
      jsx('path', { d: 'M11 17.5h2' }),
    ],
  })

  const APPROVE_MICRO_GLYPH = jsx('svg', {
    width: 12, height: 12, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: jsx('path', { d: 'M4.5 12.5l5 5 10-10' }),
  })

  const REJECT_MICRO_GLYPH = jsx('svg', {
    width: 11, height: 11, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: jsx('path', { d: 'M18 6L6 18M6 6l12 12' }),
  })

  const EXTERNAL_LINK_GLYPH = jsx('svg', {
    width: 11, height: 11, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: jsx('path', { d: 'M7 17L17 7M17 7H9M17 7v8' }),
  })

  const EMPTY_DEVICES_GLYPH = jsxs('svg', {
    width: 30, height: 30, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.4, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('rect', { x: 6, y: 3, width: 12, height: 18, rx: 3 }),
      jsx('path', { d: 'M10.5 17.5h3' }),
      jsx('path', { d: 'M10 8.5a2.5 2.5 0 0 1 4 0c0 1.2-1.2 1.8-1.5 2.5' }),
      jsx('circle', { cx: 12.2, cy: 13.5, r: 0.6, fill: 'currentColor' }),
    ],
  })

  const EXPOSURE_SHIELD_GLYPH = jsxs('svg', {
    width: 15, height: 15, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round',
    'aria-hidden': true,
    children: [
      jsx('path', { d: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z' }),
      jsx('path', { d: 'M12 8v4M12 16h.01' }),
    ],
  })

  function connectionStatus(info, relay, devices) {
    const pending = (devices ?? []).filter((d) => d?.status === 'pending')
    if (pending.length) return { tone: 'warn', text: `${pending.length} 台待确认` }
    const paired = (devices ?? []).filter((d) => d && d.status !== 'pending')
    if (paired.length) return { tone: 'ok', text: `${paired.length} 台手机在线` }
    if (relay?.status === 'online') return { tone: 'accent', text: '中继就绪 · 等待扫码' }
    if (info) return { tone: 'ok', text: '局域网就绪' }
  }

  function BrandHeader({ status }) {
    return jsxs('div', {
      className: 'dshlink-brand',
      children: [
        jsx('div', { className: 'dshlink-brand-mark', 'aria-hidden': true, children: BRAND_GLYPH }),
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
    const replacing = device?.replacing ? ' · 批准后替换同名旧设备' : ''
    return `待确认 · ${via}${replacing}${from}`
  }

  function ExposureBanner({ exposure }) {
    if (exposure?.level !== 'untrusted' || !exposure.warning) return null
    return jsxs('div', {
      className: 'dshlink-expose',
      children: [
        jsx('span', { className: 'dshlink-expose-icon', 'aria-hidden': true, children: EXPOSURE_SHIELD_GLYPH }),
        jsx('span', { className: 'dshlink-expose-text', children: exposure.warning }),
      ],
    })
  }

  function PendingHeroCard({ pendingDevices, approve, revoke }) {
    if (!pendingDevices || pendingDevices.length === 0) return null
    const dev = pendingDevices[0]
    const otherCount = pendingDevices.length - 1
    return jsxs('div', {
      className: 'dshlink-pending-hero',
      role: 'alert',
      children: [
        jsxs('div', {
          className: 'dshlink-pending-header',
          children: [
            jsx('span', { className: 'dshlink-pending-pulse', 'aria-hidden': true }),
            jsx('span', { className: 'dshlink-pending-badge', children: '新设备请求接入' }),
          ],
        }),
        jsxs('div', {
          className: 'dshlink-pending-body',
          children: [
            jsxs('div', {
              className: 'dshlink-pending-info',
              children: [
                jsxs('div', {
                  className: 'dshlink-pending-name-row',
                  children: [
                    jsx('span', { className: 'dshlink-pending-device-glyph', 'aria-hidden': true, children: PHONE_MICRO_GLYPH }),
                    jsx('div', { className: 'dshlink-pending-name', children: dev.name }),
                  ],
                }),
                jsx('div', { className: 'dshlink-pending-meta', children: pendingLabel(dev) }),
              ],
            }),
            jsxs('div', {
              className: 'dshlink-pending-actions',
              children: [
                jsx('button', {
                  type: 'button',
                  className: 'dshlink-hero-approve',
                  onClick: () => approve(dev.deviceId),
                  children: [APPROVE_MICRO_GLYPH, '批准接入'],
                }),
                jsx('button', {
                  type: 'button',
                  className: 'dshlink-hero-reject',
                  onClick: () => revoke(dev.deviceId ? { deviceId: dev.deviceId } : { name: dev.name }),
                  children: [REJECT_MICRO_GLYPH, '拒绝'],
                }),
              ],
            }),
          ],
        }),
        otherCount > 0
          ? jsx('div', { className: 'dshlink-pending-more', children: `还有 ${otherCount} 台设备等待审批，请在下方列表处理` })
          : null,
      ],
    })
  }

  function ConfirmRow({ value, onChange }) {
    return jsxs('div', {
      className: 'dshlink-confirm-row',
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
    })
  }
  const ConfirmSection = ConfirmRow

  function DeviceRow({ device, isPending, approve, revoke }) {
    return jsxs('div', {
      className: 'dshlink-device' + (isPending ? ' is-pending' : ''),
      children: [
        jsx('span', { className: 'dshlink-device-dot' + (isPending ? ' is-pending' : ''), 'aria-hidden': true }),
        jsx('span', { className: 'dshlink-device-icon', 'aria-hidden': true, children: PHONE_MICRO_GLYPH }),
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
                  children: [APPROVE_MICRO_GLYPH, '批准'],
                })
              : null,
            jsx('button', {
              type: 'button',
              className: 'dshlink-revoke',
              onClick: () => revoke(device.deviceId ? { deviceId: device.deviceId } : { name: device.name }),
              children: isPending ? [REJECT_MICRO_GLYPH, '拒绝'] : '吊销',
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
        jsx('div', { className: 'dshlink-empty-glyph', 'aria-hidden': true, children: EMPTY_DEVICES_GLYPH }),
        jsx('span', { children: '还没有配对设备 · 用手机扫码即可接入' }),
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
              children: ['已连接设备', jsx('span', { className: 'dshlink-section-count', children: total })],
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

  function formatPairCode(code) {
    const raw = String(code ?? '').trim()
    if (!raw) return '—'
    if (raw.length === 6) {
      return `${raw.slice(0, 3)} · ${raw.slice(3)}`
    }
    return raw
  }

  function PairCard({ via, code, label, hint, stamp }) {
    const [copied, setCopied] = React.useState(false)
    const [zoom, setZoom] = React.useState(false)
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
    const qrKey = `${via}:${code || ''}:${stamp || ''}`
    const qrSrc = `/dsh-link/qr.png?via=${via}&v=${encodeURIComponent(code || '')}`
      + (stamp ? `&r=${encodeURIComponent(stamp)}` : '')
    return jsxs('div', {
      className: 'dshlink-pair',
      children: [
        jsx('button', {
          type: 'button',
          className: 'dshlink-qr-plate',
          onClick: () => setZoom(true),
          title: '点击放大二维码',
          'aria-label': `放大${label}二维码`,
          children: jsx('img', {
            className: 'dshlink-qr',
            key: qrKey,
            src: qrSrc,
            alt: label,
          }),
        }),
        zoom
          ? jsxs('div', {
              className: 'dshlink-qr-zoom',
              role: 'button',
              tabIndex: -1,
              onClick: () => setZoom(false),
              children: [
                jsx('img', { src: qrSrc, alt: label }),
                jsx('div', { className: 'dshlink-qr-zoom-hint', children: '轻触任意位置关闭' }),
              ],
            })
          : null,
        jsxs('div', {
          className: 'dshlink-pair-meta',
          children: [
            jsx('div', { className: 'dshlink-pair-label', children: label }),
            jsxs('div', {
              className: 'dshlink-pair-code-row',
              children: [
                jsx('div', { className: 'dshlink-code', children: formatPairCode(code) }),
                code
                  ? jsxs('button', {
                      type: 'button',
                      className: 'dshlink-copy' + (copied ? ' is-copied' : ''),
                      onClick: copyCode,
                      title: '复制配对码',
                      'aria-label': '复制配对码',
                      children: copied ? [CHECK_GLYPH, '已复制'] : [COPY_GLYPH, '复制'],
                    })
                  : null,
              ],
            }),
            jsx('p', { className: 'dshlink-pair-hint', children: hint || '用手机 App 扫码，或在手机端输入上方配对码。' }),
          ],
        }),
      ],
    })
  }

  function LanBody({ info, devices, approve, revoke, revokeAll, setRequireConfirm }) {
    const lanDevices = devicesVia(devices, 'lan')
    const pendingDevices = lanDevices.filter(isPendingDevice)
    return jsxs('div', {
      className: 'dshlink-lan',
      children: [
        pendingDevices.length > 0
          ? jsx(PendingHeroCard, { pendingDevices, approve, revoke })
          : null,
        jsx(PairCard, { via: 'lan', code: info.pairingCode, label: '配对码' }),
        jsx(ConfirmRow, { value: info.requireConfirm, onChange: setRequireConfirm }),
        jsx(DeviceSection, { devices: lanDevices, approve, revoke, revokeAll }),
      ],
    })
  }

  function ReplacedRelayBanner({ host, controlUrl, onAck }) {
    if (!host) return null
    const href = publicControlURL(controlUrl)
    return jsxs('div', {
      className: 'dshlink-relay-replaced',
      children: [
        jsx('p', {
          className: 'dshlink-relay-status is-error',
          children: href
            ? `请打开原控制台吊销这台电脑，否则 ${host} 上的名额仍占用。`
            : `请到原控制台（${host}）吊销这台电脑，否则那边名额仍占用。`,
        }),
        href
          ? jsxs('a', {
              className: 'dshlink-relay-control',
              href,
              target: '_blank',
              rel: 'noopener noreferrer',
              children: ['打开原控制台', EXTERNAL_LINK_GLYPH],
            })
          : null,
        jsx('button', {
          type: 'button',
          className: 'dshlink-secondary',
          onClick: onAck,
          children: '知道了',
        }),
      ],
    })
  }

  function RelayControlLink({ url }) {
    const href = publicControlURL(url)
    if (!href) return null
    return jsxs('a', {
      className: 'dshlink-relay-control',
      href,
      target: '_blank',
      rel: 'noopener noreferrer',
      children: ['打开控制台', EXTERNAL_LINK_GLYPH],
    })
  }

  function RelayForm({ relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced }) {
    const [paste, setPaste] = React.useState('')
    const [busy, setBusy] = React.useState(false)
    const [message, setMessage] = React.useState('')
    const [replace, setReplace] = React.useState(false)
    const [fetching, setFetching] = React.useState(false)
    const [fetchedAt, setFetchedAt] = React.useState('')
    const fetchInvite = async () => {
      if (fetching || busy) return
      setFetching(true)
      setMessage('')
      try {
        const res = await fetch(OFFICIAL_ENROLL_API, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: '{}',
        })
        const data = await res.json().catch(() => ({}))
        if (res.status === 429) {
          const seconds = Number(data?.retryAfter) || 0
          const wait = seconds > 0
            ? `（约 ${seconds >= 3600 ? `${Math.ceil(seconds / 3600)} 小时` : `${Math.max(1, Math.ceil(seconds / 60))} 分钟`}后再试）`
            : ''
          throw new Error(data?.error === 'already_issued' ? `今天已领取过接入码，请用已有的码${wait}` : '领取太频繁，请稍后再试')
        }
        if (res.status === 503) throw new Error('接入名额已满，请稍后再试')
        if (!res.ok || !data?.enroll) throw new Error(data?.error ? String(data.error) : `领取失败（HTTP ${res.status}）`)
        setPaste(String(data.enroll))
        setMessage('')
        setFetchedAt('已填入插件接入码。请在 24 小时内点「接入」；接入成功后长期有效，不用再领。')
      } catch (err) {
        setFetchedAt('')
        setMessage(String(err?.message ?? err))
      } finally {
        setFetching(false)
      }
    }
    const online = relay?.status === 'online'
    const paused = relay?.status === 'paused'
    const revoked = relay?.status === 'revoked'
    const enrolled = !revoked && (relay?.enrolled === true || online || paused || (Boolean(relay?.agentAddress) && relay?.status === 'offline'))
    const parsedEnroll = (() => {
      try {
        return resolvePaste(paste, (revoked || paused) ? {
          address: relay?.agentAddress,
          insecureTls: relay?.insecureTls,
          tlsFingerprint: relay?.tlsFingerprint,
        } : {})
      } catch { return null }
    })()
    const canSubmit = Boolean(parsedEnroll?.inviteCode) && !busy
    const connectedHost = displayRelayHost(relay?.agentAddress) || OFFICIAL_RELAY_HOST
    const release = async () => {
      if (busy) return
      setBusy(true)
      setMessage('')
      try {
        await onRelease()
      } catch (err) {
        setMessage(String(err?.message ?? err))
      } finally {
        setBusy(false)
      }
    }
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
          controlUrl: parsedEnroll.controlUrl,
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
      return jsxs('div', {
        className: 'dshlink-relay-form',
        children: [
          jsxs('div', {
            className: 'dshlink-relay-online',
            children: [
              online
                ? jsxs('p', {
                    className: 'dshlink-relay-status is-ok',
                    children: [jsx('span', { className: 'rdot', 'aria-hidden': true }), connectedHost],
                  })
                : jsx('p', {
                    className: 'dshlink-relay-status' + (relay?.status === 'error' || revoked ? ' is-error' : ''),
                    children: paused
                      ? (relay?.error || '已断开。点重新连接即可，不用新接入码。名额仍占用；要空出名额请点「释放名额」。')
                      : (relay?.error || `正在连接 ${connectedHost}`),
                  }),
              jsxs('div', {
                className: 'dshlink-relay-actions',
                children: [
                  paused
                    ? jsx('button', {
                        type: 'button',
                        className: 'dshlink-primary',
                        onClick: onReconnect,
                        children: '重新连接',
                      })
                    : jsx('button', {
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
                  jsx('button', {
                    type: 'button',
                    className: 'dshlink-revoke',
                    disabled: busy,
                    onClick: release,
                    children: '释放名额',
                  }),
                  jsx(RelayControlLink, { url: relay?.controlUrl || parsedEnroll?.controlUrl }),
                ],
              }),
            ],
          }),
          message ? jsx('p', { className: 'dshlink-relay-status is-error', children: message }) : null,
          jsx(ReplacedRelayBanner, { host: relay?.replacedRelayHost, controlUrl: relay?.replacedRelayControlUrl, onAck: onAckReplaced }),
        ],
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
              placeholder: '粘贴控制台接入码',
              onChange: (event) => { setPaste(event.target.value); setMessage('') },
              autoComplete: 'off',
              spellCheck: false,
            }),
          ],
        }),
        jsxs('div', {
          className: 'dshlink-steps',
          children: [
            jsxs('div', {
              className: 'dshlink-step',
              children: [
                jsx('span', { className: 'dshlink-step-num', children: '1' }),
                jsxs('div', {
                  className: 'dshlink-step-body',
                  children: [
                    jsx('p', { className: 'dshlink-step-title', children: '获取接入码' }),
                    jsx('p', { className: 'dshlink-step-desc', children: '码 24 小时内有效，每天限领 1 次；接入成功后长期有效，管理员可在控制台随时吊销。' }),
                    jsx('button', {
                      type: 'button',
                      className: 'dshlink-fetch',
                      disabled: fetching || busy,
                      onClick: fetchInvite,
                      children: fetching ? '获取中…' : '获取dshlinks插件接入码',
                    }),
                    fetchedAt ? jsx('p', { className: 'dshlink-relay-status is-ok', children: fetchedAt }) : null,
                  ],
                }),
              ],
            }),
            jsxs('div', {
              className: 'dshlink-step',
              children: [
                jsx('span', { className: 'dshlink-step-num', children: '2' }),
                jsxs('div', {
                  className: 'dshlink-step-body',
                  children: [
                    jsx('p', { className: 'dshlink-step-title', children: '粘贴并接入' }),
                    jsx('p', { className: 'dshlink-step-desc', children: '接入码来自 Relay 控制台自动签发。手机扫的是插件配对码，不是登录账号。' }),
                  ],
                }),
              ],
            }),
          ],
        }),
        jsx('p', {
          className: 'dshlink-relay-status' + (revoked ? ' is-error' : ''),
          children: revoked
            ? (relay?.error || '接入已被控制台吊销。请重新获取接入码后再接入。')
            : enrolled
              ? '同一电脑贴新码会换新路由，不占额外名额。换到别的 Relay 会先确认；插件会尝试从原控制台移除这台电脑。'
              : '已有接入码可直接粘贴（接入码来自 Relay 控制台自动签发，24 小时内有效；接入成功后长期有效）。',
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
            jsx(RelayControlLink, { url: relay?.controlUrl || parsedEnroll?.controlUrl }),
          ],
        }),
        message ? jsx('p', { className: 'dshlink-relay-status is-error', children: message }) : null,
        jsx(ReplacedRelayBanner, { host: relay?.replacedRelayHost, controlUrl: relay?.replacedRelayControlUrl, onAck: onAckReplaced }),
      ],
    })
  }

  function phoneRelayRouteHint({ routeRotated, previousReleased } = {}) {
    if (!routeRotated) return ''
    if (previousReleased === false) {
      return '云端路由已更换，原 Relay 名额可能仍占用。同一网络下的手机下次打开即可跟上；纯远程请重新扫云端配对码。手机不登录控制台。'
    }
    return '云端路由已更换。同一网络下的手机下次打开即可跟上；纯远程请重新扫云端配对码。手机不登录控制台。'
  }

  function showCloudPairQR(relay) {
    if (!relay) return false
    if (relay.status === 'revoked' || relay.status === 'paused') return false
    return relay.enrolled === true
  }

  function cloudPairHint(online) {
    return online
      ? '扫这张云端配对码。手机不登录控制台。'
      : 'Relay 连上后即可扫这张云端码。同一网络也可先用局域网。手机不登录控制台。'
  }

  function RemoteBody({ info, devices, approve, revoke, revokeAll, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, phoneHint }) {
    const pairingCode = info?.pairingCode || ''
    const online = relay?.status === 'online'
    const relayDevices = devicesVia(devices, 'relay')
    const pendingDevices = relayDevices.filter(isPendingDevice)
    return jsxs('div', {
      className: 'dshlink-remote',
      children: [
        pendingDevices.length > 0
          ? jsx(PendingHeroCard, { pendingDevices, approve, revoke })
          : null,
        jsx(RelayForm, { relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced }),
        phoneHint ? jsx('p', { className: 'dshlink-pair-hint', children: phoneHint }) : null,
        showCloudPairQR(relay)
          ? jsx(PairCard, { via: 'relay', code: pairingCode, label: '云端配对码', hint: cloudPairHint(online), stamp: relay?.pairStamp })
          : null,
        jsx(DeviceSection, { devices: relayDevices, approve, revoke, revokeAll }),
      ],
    })
  }

  function ConnectionTabs({ active, onChange, relayOnline }) {
    return jsxs('div', {
      className: 'dshlink-tabs',
      role: 'navigation',
      'aria-label': '连接方式',
      children: [
        jsxs('button', {
          type: 'button', 'aria-pressed': active === 'lan',
          className: 'dshlink-tab' + (active === 'lan' ? ' is-active' : ''),
          onClick: () => onChange('lan'),
          children: [
            jsx('span', { className: 'dshlink-tab-glyph', 'aria-hidden': true, children: LAN_GLYPH }),
            '局域网',
          ],
        }),
        jsxs('button', {
          type: 'button', 'aria-pressed': active === 'remote',
          className: 'dshlink-tab' + (active === 'remote' ? ' is-active' : ''),
          onClick: () => onChange('remote'),
          children: [
            jsx('span', { className: 'dshlink-tab-glyph', 'aria-hidden': true, children: CLOUD_GLYPH }),
            '远端连接',
            relayOnline ? jsx('span', { className: 'dshlink-tab-dot', title: '中继就绪' }) : null,
          ],
        }),
      ],
    })
  }

  function ConnectionBody({ info, devices, err, starting, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, load, phoneHint }) {
    const [active, setActive] = React.useState('lan')
    React.useEffect(() => { load?.() }, [active, load])
    if (starting) return jsx('div', { className: 'dshlink-status', children: '手机连接正在启动…' })
    if (err) return jsx('div', { className: 'dshlink-status is-error', children: `加载失败：${err}` })
    if (!info) return jsx('div', { className: 'dshlink-status', children: '加载中…' })
    const relayOnline = relay?.status === 'online'
    return jsxs('div', {
      className: 'dshlink-connection',
      children: [
        jsx(ConnectionTabs, { active, onChange: setActive, relayOnline }),
        active === 'lan'
          ? jsx(LanBody, { info, devices, approve, revoke, revokeAll, setRequireConfirm })
          : jsx(RemoteBody, { info, devices, approve, revoke, revokeAll, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, phoneHint }),
        // 网卡告警压在最下面：它是常驻提示（这台机器一直有 Tailscale 地址），
        // 放顶部会把配对卡片整个往下顶，挡住这一屏真正要用的东西。
        jsx(ExposureBanner, { exposure: info.exposure }),
      ],
    })
  }

  function usePairData(active) {
    const [info, setInfo] = React.useState(null)
    const [devices, setDevices] = React.useState([])
    const [relay, setRelay] = React.useState(null)
    const [err, setErr] = React.useState('')
    const [phoneHint, setPhoneHint] = React.useState('')
    // 启动就绪窗口：pair-info 503(proxy_not_ready) 表示 HTTPS 尚未 listen。
    // 有限重试后升级为明确失败；服务端报 phase=failed 时立即给出失败提示。
    const [starting, setStarting] = React.useState(false)
    const startRetries = React.useRef(0)
    const START_RETRY_LIMIT = 20

    const load = React.useCallback(async () => {
      try {
        const resInfo = await fetch('/dsh-link/pair-info')
        if (resInfo.status === 503) {
          const data = await resInfo.json().catch(() => ({}))
          if (data?.error === 'proxy_not_ready') {
            if (data.phase === 'failed') {
              setStarting(false)
              setErr('手机连接启动失败，HTTPS 端口未能就绪。请查看 DSH 日志或重启 DSH 后重试。')
              return
            }
            if (startRetries.current >= START_RETRY_LIMIT) {
              setStarting(false)
              setErr('手机连接启动超时，HTTPS 端口长时间未就绪。请查看 DSH 日志或重启 DSH 后重试。')
              return
            }
            startRetries.current += 1
            setStarting(true)
            setErr('')
            return
          }
          throw new Error(`HTTP ${resInfo.status}`)
        }
        startRetries.current = 0
        setStarting(false)
        const resDevices = await fetch('/dsh-link/devices')
        const resRelay = await fetch('/dsh-link/relay-status')
        if (!resInfo.ok || !resDevices.ok) throw new Error(`HTTP ${resInfo.status}/${resDevices.status}`)
        setInfo(await resInfo.json())
        const data = await resDevices.json()
        setDevices(data.devices ?? [])
        if (resRelay.ok) setRelay(await resRelay.json())
        setErr('')
      } catch (e) {
        setStarting(false)
        setErr(String(e?.message ?? e))
      }
    }, [])

    const pendingCount = devices.filter((d) => d.status === 'pending').length

    React.useEffect(() => {
      if (!active) return undefined
      load()
      const timer = setInterval(load, starting ? 1500 : (pendingCount > 0 ? 2000 : 8000))
      return () => clearInterval(timer)
    }, [active, load, pendingCount, starting])

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

    const onEnroll = async ({ address, inviteCode, insecureTls, tlsFingerprint, controlUrl }, confirmRelaySwitch = false) => {
      const res = await fetch('/dsh-link/relay-enroll', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ address, inviteCode, insecureTls, tlsFingerprint, confirmRelaySwitch, controlUrl }),
      })
      const data = await res.json().catch(() => ({}))
      if (res.status === 409 && data.code === 'relay_switch') {
        if (!window.confirm(data.error || '换到另一台 Relay 时，插件会尝试从原控制台移除这台电脑。确认继续？')) return
        return onEnroll({ address, inviteCode, insecureTls, tlsFingerprint, controlUrl }, true)
      }
      if (!res.ok) {
        await load().catch(() => {})
        throw new Error(data.error || `HTTP ${res.status}`)
      }
      setPhoneHint(phoneRelayRouteHint({
        routeRotated: data.routeRotated === true,
        previousReleased: data.previousReleased,
      }))
      await load()
    }
    const onDisconnect = async () => {
      await fetch('/dsh-link/relay-disconnect', { method: 'POST' })
      await load()
    }
    const onRelease = async () => {
      if (!window.confirm('从控制台移除这台电脑？名额立刻空出，手机云端配对会失效，需要新接入码才能再连。只想暂停请用「断开」。')) return
      const res = await fetch('/dsh-link/relay-release', { method: 'POST' })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
      await load()
    }
    const onReconnect = async () => {
      const res = await fetch('/dsh-link/relay-reconnect', { method: 'POST' })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
      await load()
    }
    const onAckReplaced = async () => {
      await fetch('/dsh-link/relay-ack-replaced', { method: 'POST' })
      await load()
    }
    return { info, devices, err, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, load, phoneHint }
  }

  function LinkPanel() {
    const [open, setOpen] = React.useState(false)
    const { info, devices, err, starting, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, load, phoneHint } = usePairData(open)

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
                  jsx(BrandHeader, { status: connectionStatus(info, relay, devices) }),
                  jsx(ConnectionBody, { info, devices, err, starting, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, load, phoneHint }),
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
    const { info, devices, err, starting, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, load, phoneHint } = usePairData(true)

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        jsxs('div', {
          className: 'dshlink-settings dshlink-root',
          children: [
            jsx(BrandHeader, { status: connectionStatus(info, relay, devices) }),
            jsx(ConnectionBody, { info, devices, err, starting, revoke, approve, revokeAll, setRequireConfirm, relay, onEnroll, onDisconnect, onReconnect, onRelease, onAckReplaced, load, phoneHint }),
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
