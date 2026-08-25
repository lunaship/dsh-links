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
      --dl-ink: var(--dsw-alias-label-primary, #12141a);
      --dl-muted: var(--dsw-alias-label-secondary, #5c6570);
      --dl-faint: var(--dsw-alias-label-tertiary, #8b939c);
      --dl-line: var(--dsw-alias-border-l2, rgba(18, 20, 26, 0.1));
      --dl-soft: var(--dsw-alias-bg-layer-1, #f3f5f7);
      --dl-paper: var(--dsw-alias-bg-layer-2, #ffffff);
      --dl-accent: #0a9eaa;
      --dl-accent-soft: rgba(13, 235, 243, 0.14);
      --dl-danger: #b42318;
      --dl-danger-soft: #fff1f0;
      --dl-ok: #1f8a4c;
      font-family: "Plus Jakarta Sans", "Segoe UI", "PingFang SC", "Noto Sans SC", system-ui, sans-serif;
      color: var(--dl-ink);
      -webkit-font-smoothing: antialiased;
    }

    .dshlink-backdrop {
      position: fixed; inset: 0; z-index: 99995;
      background: rgba(10, 14, 20, 0.42);
      display: flex; align-items: center; justify-content: center;
      padding: 20px;
      animation: dshlink-fadein 0.22s ease;
    }
    @keyframes dshlink-fadein { from { opacity: 0 } to { opacity: 1 } }
    .dshlink-panel {
      width: min(440px, 100%);
      max-height: 86vh;
      overflow: auto;
      border-radius: 20px;
      background: var(--dl-paper);
      border: 1px solid var(--dl-line);
      box-shadow: 0 24px 64px rgba(10, 14, 20, 0.22);
      padding: 22px 22px 18px;
      animation: dshlink-rise 0.28s cubic-bezier(0.22, 1, 0.36, 1);
    }
    @keyframes dshlink-rise {
      from { opacity: 0; transform: translateY(14px) scale(0.98) }
      to { opacity: 1; transform: none }
    }

    .dshlink-settings {
      display: flex; flex-direction: column; gap: 16px;
      padding: 2px 0 18px;
    }

    .dshlink-brand {
      display: flex; flex-direction: column; gap: 4px;
    }
    .dshlink-brand-kicker {
      font-size: 11px; font-weight: 600; letter-spacing: 0.14em;
      text-transform: uppercase; color: var(--dl-accent);
    }
    .dshlink-brand-title {
      font-size: 20px; font-weight: 650; letter-spacing: -0.02em;
      line-height: 1.25; margin: 0;
    }
    .dshlink-tabs {
      display: flex; gap: 4px; padding: 4px; border-radius: 11px;
      background: var(--dl-soft); border: 1px solid var(--dl-line);
    }
    .dshlink-tab {
      flex: 1; appearance: none; cursor: pointer; border: 0; border-radius: 8px;
      min-height: 32px; padding: 6px 10px; background: transparent; color: var(--dl-muted);
      font: inherit; font-size: 12px; font-weight: 600; white-space: nowrap;
      transition: background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
    }
    .dshlink-tab:hover { color: var(--dl-ink) }
    .dshlink-tab.is-active { color: var(--dl-ink); background: var(--dl-paper); box-shadow: 0 1px 3px rgba(10, 14, 20, 0.12) }
    .dshlink-tab:focus-visible { outline: 2px solid var(--dl-accent); outline-offset: 2px }
    .dshlink-connection { display: flex; flex-direction: column; gap: 14px }

    .dshlink-lan {
      display: flex; flex-direction: column; gap: 14px;
    }
    .dshlink-pair {
      display: grid;
      grid-template-columns: 148px minmax(0, 1fr);
      gap: 28px;
      align-items: center;
      padding: 16px 20px 16px 16px;
      border-radius: 16px;
      background:
        linear-gradient(145deg, var(--dl-accent-soft), transparent 58%),
        var(--dl-soft);
      border: 1px solid var(--dl-line);
    }
    @media (max-width: 420px) {
      .dshlink-pair { grid-template-columns: 1fr; justify-items: center; text-align: center }
      .dshlink-pair-meta { align-items: center; padding-left: 0 }
      .dshlink-code { letter-spacing: 0.28em }
    }
    .dshlink-qr-plate {
      width: 148px; height: 148px; border-radius: 14px;
      background: #fff; padding: 10px;
      box-shadow: 0 8px 24px rgba(10, 158, 170, 0.12);
      border: 1px solid rgba(10, 158, 170, 0.18);
    }
    .dshlink-qr {
      display: block; width: 100%; height: 100%;
      border-radius: 6px;
    }
    .dshlink-pair-meta {
      display: flex; flex-direction: column; gap: 6px; min-width: 0;
      padding-left: 8px;
    }
    .dshlink-pair-label {
      font-size: 11px; font-weight: 600; letter-spacing: 0.08em;
      text-transform: uppercase; color: var(--dl-faint);
    }
    .dshlink-code {
      font-size: 28px; font-weight: 700; letter-spacing: 0.22em;
      font-variant-numeric: tabular-nums;
      line-height: 1.1; color: var(--dl-ink);
      margin: 0;
    }

    .dshlink-remote { display: flex; flex-direction: column; gap: 12px }

    .dshlink-devices { display: flex; flex-direction: column; gap: 8px }
    .dshlink-section-label {
      font-size: 12px; font-weight: 600; color: var(--dl-muted);
      letter-spacing: 0.02em;
    }
    .dshlink-device {
      display: flex; align-items: center; gap: 10px;
      padding: 10px 12px; border-radius: 12px;
      background: var(--dl-paper); border: 1px solid var(--dl-line);
      transition: background 0.15s ease;
    }
    .dshlink-device:hover { background: var(--dl-soft) }
    .dshlink-device-dot {
      width: 7px; height: 7px; flex: none; border-radius: 50%;
      background: var(--dl-ok);
      box-shadow: 0 0 0 3px rgba(31, 138, 76, 0.14);
    }
    .dshlink-device-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px }
    .dshlink-device-name {
      font-size: 13px; font-weight: 600;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .dshlink-device-time { font-size: 11px; color: var(--dl-faint) }
    .dshlink-revoke {
      flex: none; appearance: none; cursor: pointer;
      border: 1px solid rgba(180, 35, 24, 0.22);
      background: var(--dl-danger-soft); color: var(--dl-danger);
      border-radius: 8px; padding: 5px 11px;
      font: inherit; font-size: 12px; font-weight: 600;
      transition: background 0.15s ease, transform 0.12s ease;
    }
    .dshlink-revoke:hover { background: #ffe4e1 }
    .dshlink-revoke:active { transform: scale(0.97) }
    .dshlink-revoke:focus-visible { outline: 2px solid var(--dl-danger); outline-offset: 2px }
    .dshlink-empty {
      font-size: 12px; color: var(--dl-faint);
      padding: 12px; border-radius: 12px;
      border: 1px dashed var(--dl-line); text-align: center;
    }
    .dshlink-expose {
      margin: 0; font-size: 12px; line-height: 1.5;
      padding: 10px 12px; border-radius: 10px;
      background: var(--dl-danger-soft);
      border: 1px solid rgba(180, 35, 24, 0.28);
      color: var(--dl-danger);
      font-weight: 600;
    }
    .dshlink-confirm {
      display: flex; align-items: center; gap: 8px;
      margin: 0; font-size: 12px; line-height: 1.45; color: var(--dl-muted);
    }
    .dshlink-confirm input { margin: 0; }
    .dshlink-device.is-pending {
      border-color: rgba(180, 110, 24, 0.28);
      background: #fff8f0;
    }
    .dshlink-device-dot.is-pending {
      background: #c47d12;
      box-shadow: 0 0 0 3px rgba(196, 125, 18, 0.16);
    }
    .dshlink-device-actions { display: flex; gap: 6px; flex: none; }
    .dshlink-approve {
      flex: none; appearance: none; cursor: pointer;
      border: 1px solid rgba(31, 138, 76, 0.28);
      background: #eef8f1; color: var(--dl-ok);
      border-radius: 8px; padding: 5px 11px;
      font: inherit; font-size: 12px; font-weight: 600;
    }
    .dshlink-approve:hover { background: #e3f3e8 }
    .dshlink-approve:focus-visible { outline: 2px solid var(--dl-ok); outline-offset: 2px }
    .dshlink-revoke-all {
      appearance: none; cursor: pointer; align-self: flex-start;
      border: 0; background: transparent; color: var(--dl-danger);
      padding: 0; font: inherit; font-size: 12px; font-weight: 600;
    }
    .dshlink-revoke-all:hover { opacity: 0.72 }
    .dshlink-revoke-all:focus-visible { outline: 2px solid var(--dl-danger); outline-offset: 2px }

    .dshlink-field {
      width: 100%; box-sizing: border-box;
      border: 1px solid var(--dl-line); border-radius: 10px;
      padding: 9px 11px; font: inherit; font-size: 13px;
      background: var(--dl-paper); color: var(--dl-ink);
    }
    .dshlink-field:focus { outline: 2px solid var(--dl-accent); outline-offset: 1px }
    .dshlink-relay-form { display: flex; flex-direction: column; gap: 10px; margin: 0 }
    .dshlink-relay-row { display: flex; flex-direction: column; gap: 4px }
    .dshlink-relay-row label { font-size: 12px; font-weight: 600; color: var(--dl-muted) }
    .dshlink-relay-actions { display: flex; gap: 8px; flex-wrap: wrap }
    .dshlink-relay-status { font-size: 12px; color: var(--dl-muted); margin: 0 }
    .dshlink-relay-status.is-ok { color: var(--dl-ok) }
    .dshlink-relay-status.is-error { color: var(--dl-danger) }
    .dshlink-relay-online {
      display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap;
    }
    .dshlink-primary {
      appearance: none; cursor: pointer; border: 0; border-radius: 10px;
      padding: 8px 14px; background: var(--dl-ink); color: #f7f8fa;
      font: inherit; font-size: 13px; font-weight: 600;
    }
    .dshlink-secondary {
      appearance: none; cursor: pointer; border: 1px solid var(--dl-line);
      border-radius: 10px; padding: 8px 14px; background: var(--dl-paper);
      font: inherit; font-size: 13px; font-weight: 600; color: var(--dl-ink);
    }

    .dshlink-status { font-size: 12px; color: var(--dl-muted); padding: 4px 0 }
    .dshlink-status.is-error { color: var(--dl-danger) }

    .dshlink-close {
      margin-top: 16px; width: 100%; appearance: none; cursor: pointer;
      border: 0; border-radius: 11px; padding: 11px 14px;
      background: var(--dl-ink); color: #f7f8fa;
      font: inherit; font-size: 14px; font-weight: 600;
      transition: opacity 0.15s ease, transform 0.12s ease;
    }
    .dshlink-close:hover { opacity: 0.88 }
    .dshlink-close:active { transform: scale(0.985) }
    .dshlink-close:focus-visible { outline: 2px solid var(--dl-accent); outline-offset: 2px }
  `

  function BrandHeader() {
    return jsxs('div', {
      className: 'dshlink-brand',
      children: [
        jsx('div', { className: 'dshlink-brand-kicker', children: 'DSH Links' }),
        jsx('div', { className: 'dshlink-brand-title', children: '手机连接' }),
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
    return jsx('p', { className: 'dshlink-expose', children: exposure.warning })
  }

  function ConfirmToggle({ requireConfirm, onChange }) {
    return jsxs('label', {
      className: 'dshlink-confirm',
      children: [
        jsx('input', {
          type: 'checkbox',
          checked: Boolean(requireConfirm),
          onChange: (event) => onChange(event.target.checked),
        }),
        '配对需本机确认',
      ],
    })
  }

  function DeviceList({ devices, revoke }) {
    if (!devices.length) return null
    return jsxs('div', {
      className: 'dshlink-devices',
      children: [
        jsx('div', { className: 'dshlink-section-label', children: '已配对设备' }),
        ...devices.map((d) =>
          jsxs('div', {
            className: 'dshlink-device',
            key: d.deviceId || d.name,
            children: [
              jsx('span', { className: 'dshlink-device-dot', 'aria-hidden': true }),
              jsxs('div', {
                className: 'dshlink-device-copy',
                children: [
                  jsx('div', { className: 'dshlink-device-name', children: d.name }),
                  jsx('div', { className: 'dshlink-device-time', children: deviceSeenLabel(d.lastSeenAt) }),
                ],
              }),
              jsx('button', {
                type: 'button',
                className: 'dshlink-revoke',
                onClick: () => revoke(d.deviceId ? { deviceId: d.deviceId } : { name: d.name }),
                children: '吊销',
              }),
            ],
          }),
        ),
      ],
    })
  }

  function PendingList({ devices, approve, revoke }) {
    if (!devices.length) return null
    return jsxs('div', {
      className: 'dshlink-devices',
      children: [
        jsx('div', { className: 'dshlink-section-label', children: '待本机确认' }),
        ...devices.map((d) =>
          jsxs('div', {
            className: 'dshlink-device is-pending',
            key: d.deviceId || d.name,
            children: [
              jsx('span', { className: 'dshlink-device-dot is-pending', 'aria-hidden': true }),
              jsxs('div', {
                className: 'dshlink-device-copy',
                children: [
                  jsx('div', { className: 'dshlink-device-name', children: d.name }),
                  jsx('div', { className: 'dshlink-device-time', children: pendingLabel(d) }),
                ],
              }),
              jsxs('div', {
                className: 'dshlink-device-actions',
                children: [
                  jsx('button', {
                    type: 'button',
                    className: 'dshlink-approve',
                    onClick: () => approve(d.deviceId),
                    children: '批准',
                  }),
                  jsx('button', {
                    type: 'button',
                    className: 'dshlink-revoke',
                    onClick: () => revoke(d.deviceId ? { deviceId: d.deviceId } : { name: d.name }),
                    children: '拒绝',
                  }),
                ],
              }),
            ],
          }),
        ),
      ],
    })
  }

  function PairCard({ via, code, label }) {
    return jsxs('div', {
      className: 'dshlink-pair',
      children: [
        jsx('div', {
          className: 'dshlink-qr-plate',
          children: jsx('img', {
            className: 'dshlink-qr',
            key: code || '',
            src: `/dsh-link/qr.png?via=${via}`,
            alt: label,
          }),
        }),
        jsxs('div', {
          className: 'dshlink-pair-meta',
          children: [
            jsx('div', { className: 'dshlink-pair-label', children: label }),
            jsx('p', { className: 'dshlink-code', children: code || '—' }),
          ],
        }),
      ],
    })
  }

  function LanBody({ info, devices, revoke }) {
    return jsxs('div', {
      className: 'dshlink-lan',
      children: [
        jsx(PairCard, { via: 'lan', code: info.pairingCode, label: '配对码' }),
        jsx(DeviceList, {
          devices: devicesVia(devices, 'lan').filter((d) => !isPendingDevice(d)),
          revoke,
        }),
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
      return jsxs('div', {
        className: 'dshlink-relay-form',
        children: [
          jsxs('div', {
            className: 'dshlink-relay-online',
            children: [
              jsx('p', {
                className: 'dshlink-relay-status' + (relay?.status === 'error' ? ' is-error' : online ? ' is-ok' : ''),
                children: online
                  ? connectedHost
                  : (relay?.error || `正在连接 ${connectedHost}`),
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

  function RemoteBody({ info, devices, revoke, relay, onEnroll, onDisconnect }) {
    const pairingCode = info?.pairingCode || ''
    const online = relay?.status === 'online'
    return jsxs('div', {
      className: 'dshlink-remote',
      children: [
        jsx(RelayForm, { relay, onEnroll, onDisconnect }),
        online
          ? jsx(PairCard, { via: 'relay', code: pairingCode, label: '云端配对码' })
          : null,
        jsx(DeviceList, {
          devices: devicesVia(devices, 'relay').filter((d) => !isPendingDevice(d)),
          revoke,
        }),
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
    const pending = (devices ?? []).filter(isPendingDevice)
    return jsxs('div', {
      className: 'dshlink-connection',
      children: [
        jsx(ConnectionTabs, { active, onChange: setActive }),
        jsx(ExposureBanner, { exposure: info.exposure }),
        jsx(ConfirmToggle, { requireConfirm: info.requireConfirm, onChange: setRequireConfirm }),
        jsx(PendingList, { devices: pending, approve, revoke }),
        active === 'lan' ? jsx(LanBody, { info, devices, revoke }) : jsx(RemoteBody, { info, devices, revoke, relay, onEnroll, onDisconnect }),
        devices.length > 1
          ? jsx('button', {
              type: 'button',
              className: 'dshlink-revoke-all',
              onClick: revokeAll,
              children: '吊销全部',
            })
          : null,
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
                  jsx(BrandHeader, {}),
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
            jsx(BrandHeader, {}),
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
