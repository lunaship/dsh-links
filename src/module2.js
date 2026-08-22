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

  function urlRows(info) {
    return info?.infos
      ?? info?.urls?.map((u) => ({ url: u, label: u, category: 'other', isRecommended: false }))
      ?? []
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
      display: flex; flex-direction: column; gap: 6px;
    }
    .dshlink-brand-kicker {
      font-size: 11px; font-weight: 600; letter-spacing: 0.14em;
      text-transform: uppercase; color: var(--dl-accent);
    }
    .dshlink-brand-title {
      font-size: 20px; font-weight: 650; letter-spacing: -0.02em;
      line-height: 1.25; margin: 0;
    }
    .dshlink-brand-lede {
      margin: 0; font-size: 13px; line-height: 1.5; color: var(--dl-muted);
      max-width: 36em;
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
      display: flex; flex-direction: column; gap: 8px; min-width: 0;
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
    .dshlink-hint {
      margin: 0; font-size: 12px; line-height: 1.45; color: var(--dl-muted);
    }

    .dshlink-tunnel-note {
      margin: 0; font-size: 12px; line-height: 1.5; color: var(--dl-muted);
      padding: 10px 12px; border-radius: 10px;
      background: var(--dl-soft); border: 1px dashed var(--dl-line);
    }
    .dshlink-roadmap {
      display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 10px;
      align-items: start; padding: 13px 14px; border-radius: 12px;
      background: linear-gradient(135deg, rgba(112, 78, 196, 0.09), transparent 72%), var(--dl-paper);
      border: 1px solid rgba(112, 78, 196, 0.18);
    }
    .dshlink-roadmap-mark {
      display: grid; place-items: center; width: 28px; height: 28px; border-radius: 9px;
      color: #7050c4; background: rgba(112, 78, 196, 0.11); font-size: 14px; font-weight: 700;
    }
    .dshlink-roadmap-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px }
    .dshlink-roadmap-title { font-size: 13px; font-weight: 650; color: var(--dl-ink) }
    .dshlink-roadmap-text { margin: 0; font-size: 12px; line-height: 1.5; color: var(--dl-muted) }
    .dshlink-roadmap-status {
      align-self: start; margin-left: 8px; font-size: 10px; font-weight: 700; letter-spacing: 0.04em;
      color: #7050c4; background: rgba(112, 78, 196, 0.1); border-radius: 999px; padding: 3px 7px;
    }

    .dshlink-remote { display: flex; flex-direction: column; gap: 12px }
    .dshlink-remote-intro {
      margin: 0; font-size: 12px; line-height: 1.5; color: var(--dl-muted);
    }
    .dshlink-step {
      display: flex; gap: 12px; align-items: flex-start;
      padding: 14px; border-radius: 12px; border: 1px solid var(--dl-line); background: var(--dl-paper);
    }
    .dshlink-step.is-pending { opacity: 0.78; }
    .dshlink-step-num {
      display: grid; place-items: center; flex: none; width: 22px; height: 22px; margin-top: 1px;
      border-radius: 999px; font-size: 11px; font-weight: 700; color: #fff; background: var(--dl-accent);
    }
    .dshlink-step.is-pending .dshlink-step-num { background: var(--dl-muted); color: var(--dl-paper); }
    .dshlink-step-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; }
    .dshlink-step-title { font-size: 13px; font-weight: 650; color: var(--dl-ink) }
    .dshlink-qr-pending {
      display: grid; place-items: center; min-height: 168px; border-radius: 12px;
      border: 1px dashed var(--dl-line); background: var(--dl-soft);
      color: var(--dl-muted); font-size: 12px; line-height: 1.55; text-align: center; padding: 18px 16px;
    }

    .dshlink-urls {
      display: flex; flex-direction: column; gap: 6px;
      padding: 12px; border-radius: 12px;
      background: var(--dl-soft); border: 1px solid var(--dl-line);
    }
    .dshlink-url-row {
      display: flex; align-items: flex-start; gap: 8px;
      font-size: 12px; line-height: 1.45; word-break: break-all;
      color: var(--dl-muted);
    }
    .dshlink-url-row.is-rec { color: var(--dl-ink); font-weight: 500 }
    .dshlink-pill {
      flex: none; margin-top: 1px;
      font-size: 10px; font-weight: 700; letter-spacing: 0.04em;
      color: var(--dl-ok);
      background: rgba(31, 138, 76, 0.1);
      border-radius: 999px; padding: 2px 7px;
    }

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
    .dshlink-relay-check { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--dl-muted) }
    .dshlink-relay-status { font-size: 12px; color: var(--dl-muted) }
    .dshlink-relay-status.is-ok { color: var(--dl-ok) }
    .dshlink-relay-status.is-error { color: var(--dl-danger) }
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
        jsx('div', { className: 'dshlink-brand-kicker', children: 'DSH Links · 局域网 Beta' }),
        jsx('div', { className: 'dshlink-brand-title', children: '手机连接' }),
        jsx('p', {
          className: 'dshlink-brand-lede',
          children: '局域网和云端是两张码、两个设备。云端码只在电脑用接入码连上 Relay 之后才会出现。',
        }),
      ],
    })
  }

  function devicesVia(devices, via) {
    return (devices ?? []).filter((d) => (d.via === 'relay' ? 'relay' : 'lan') === via)
  }

  function DeviceList({ devices, revoke, empty }) {
    return jsxs('div', {
      className: 'dshlink-devices',
      children: [
        jsx('div', { className: 'dshlink-section-label', children: '已配对设备' }),
        devices.length
          ? devices.map((d) =>
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
                    onClick: () => revoke(d.name),
                    children: '吊销',
                  }),
                ],
              }),
            )
          : jsx('div', { className: 'dshlink-empty', children: empty }),
      ],
    })
  }

  function LanBody({ info, devices, revoke }) {
    const rows = urlRows(info)
    return jsxs('div', {
      className: 'dshlink-lan',
      children: [
        jsxs('div', {
          className: 'dshlink-pair',
          children: [
            jsx('div', {
              className: 'dshlink-qr-plate',
              children: jsx('img', {
                className: 'dshlink-qr',
                src: '/dsh-link/qr.png?via=lan&t=' + encodeURIComponent(info.pairingCode || ''),
                alt: '局域网配对二维码',
              }),
            }),
            jsxs('div', {
              className: 'dshlink-pair-meta',
              children: [
                jsx('div', { className: 'dshlink-pair-label', children: '一次性配对码' }),
                jsx('p', { className: 'dshlink-code', children: info.pairingCode }),
                jsx('p', {
                  className: 'dshlink-hint',
                  children: '只授权局域网。扫完后配对码会作废，云端请再打开「远端连接」扫第二张码。',
                }),
              ],
            }),
          ],
        }),
        rows.length
          ? jsxs('div', {
              className: 'dshlink-urls',
              children: [
                jsx('div', { className: 'dshlink-section-label', children: '可访问地址' }),
                ...rows.map((item, i) => {
                  const rec = item.isRecommended || i === 0
                  return jsxs('div', {
                    key: item.url,
                    className: 'dshlink-url-row' + (rec ? ' is-rec' : ''),
                    children: [
                      rec ? jsx('span', { className: 'dshlink-pill', children: '推荐' }) : null,
                      jsx('span', { children: item.url }),
                    ],
                  })
                }),
              ],
            })
          : null,
        jsx('p', {
          className: 'dshlink-tunnel-note',
          children: '请让手机与电脑连上同一可信 Wi‑Fi 后扫码。丢失手机时在此立即吊销设备。勿将 18640 裸暴露到公网。',
        }),
        jsx(DeviceList, {
          devices: devicesVia(devices, 'lan'),
          revoke,
          empty: '暂无已配对的局域网设备',
        }),
      ],
    })
  }

  function RelayForm({ relay, onEnroll, onDisconnect }) {
    const [address, setAddress] = React.useState(relay?.agentAddress || '')
    const [invite, setInvite] = React.useState('')
    const [insecureTls, setInsecureTls] = React.useState(relay?.insecureTls !== false)
    const [busy, setBusy] = React.useState(false)
    const [message, setMessage] = React.useState('')
    React.useEffect(() => {
      if (relay?.agentAddress) setAddress((cur) => cur || relay.agentAddress)
      if (relay && 'insecureTls' in relay) setInsecureTls(relay.insecureTls !== false)
    }, [relay?.agentAddress, relay?.insecureTls])
    const online = relay?.status === 'online'
    const canSubmit = address.trim() && invite.trim() && !busy
    const submit = async (event) => {
      event.preventDefault()
      if (!canSubmit) return
      setBusy(true)
      setMessage('')
      try {
        await onEnroll({ address, inviteCode: invite, insecureTls })
        setInvite('')
      } catch (err) {
        setMessage(String(err?.message ?? err))
      } finally {
        setBusy(false)
      }
    }
    return jsxs('form', {
      className: 'dshlink-relay-form',
      onSubmit: submit,
      children: [
        jsxs('div', {
          className: 'dshlink-relay-row',
          children: [
            jsx('label', { htmlFor: 'dsh-relay-addr', children: 'Relay 地址' }),
            jsx('input', {
              id: 'dsh-relay-addr',
              className: 'dshlink-field',
              value: address,
              placeholder: 'host 或 host:8444',
              onChange: (event) => setAddress(event.target.value),
              autoComplete: 'off',
            }),
          ],
        }),
        jsxs('div', {
          className: 'dshlink-relay-row',
          children: [
            jsx('label', { htmlFor: 'dsh-relay-invite', children: '接入码' }),
            jsx('input', {
              id: 'dsh-relay-invite',
              className: 'dshlink-field',
              value: invite,
              placeholder: '在 Relay 控制台生成的一次性接入码',
              onChange: (event) => setInvite(event.target.value),
              autoComplete: 'off',
            }),
          ],
        }),
        jsxs('label', {
          className: 'dshlink-relay-check',
          children: [
            jsx('input', {
              type: 'checkbox',
              checked: insecureTls,
              onChange: (event) => setInsecureTls(event.target.checked),
            }),
            '允许自签 TLS（自托管试验）',
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
            online ? jsx('button', {
              type: 'button',
              className: 'dshlink-secondary',
              onClick: onDisconnect,
              children: '断开',
            }) : null,
          ],
        }),
        jsx('p', {
          className: 'dshlink-relay-status' + (relay?.status === 'error' || message ? ' is-error' : online ? ' is-ok' : ''),
          children: message || (online ? `已在线 ${relay.agentAddress}` : (relay?.error || '未接入')),
        }),
      ],
    })
  }

  function RemoteBody({ info, devices, revoke, relay, onEnroll, onDisconnect }) {
    const pairingCode = info?.pairingCode || ''
    const online = relay?.status === 'online'
    return jsxs('div', {
      className: 'dshlink-remote',
      children: [
        jsx('p', {
          className: 'dshlink-remote-intro',
          children: '出门用手机前，电脑要先用接入码连上 Relay。接入成功后才会出现给手机扫的云端码；这张码不含接入码。',
        }),
        jsxs('section', {
          className: 'dshlink-step',
          children: [
            jsx('span', { className: 'dshlink-step-num', 'aria-hidden': true, children: '1' }),
            jsxs('div', {
              className: 'dshlink-step-body',
              children: [
                jsx('div', { className: 'dshlink-step-title', children: '填入地址和接入码' }),
                jsx(RelayForm, { relay, onEnroll, onDisconnect }),
              ],
            }),
          ],
        }),
        jsxs('section', {
          className: 'dshlink-step' + (online ? '' : ' is-pending'),
          children: [
            jsx('span', { className: 'dshlink-step-num', 'aria-hidden': true, children: '2' }),
            jsxs('div', {
              className: 'dshlink-step-body',
              children: [
                jsx('div', { className: 'dshlink-step-title', children: '用手机扫云端码' }),
                online
                  ? jsxs('div', {
                      className: 'dshlink-pair',
                      children: [
                        jsx('div', {
                          className: 'dshlink-qr-plate',
                          children: jsx('img', {
                            className: 'dshlink-qr',
                            src: '/dsh-link/qr.png?via=relay&t=' + encodeURIComponent(pairingCode),
                            alt: '云端配对二维码',
                          }),
                        }),
                        jsxs('div', {
                          className: 'dshlink-pair-meta',
                          children: [
                            jsx('div', { className: 'dshlink-pair-label', children: '云端配对码' }),
                            jsx('p', { className: 'dshlink-code', children: pairingCode || '本页加载完成后显示' }),
                            jsx('p', {
                              className: 'dshlink-hint',
                              children: '与局域网不是同一张码。扫这张会在手机上多一条「云端」设备，只走 Relay。',
                            }),
                          ],
                        }),
                      ],
                    })
                  : jsx('div', {
                      className: 'dshlink-qr-pending',
                      children: '接入成功后才会显示二维码。新用户请先完成上一步：远端必须有接入码。',
                    }),
              ],
            }),
          ],
        }),
        jsx(DeviceList, {
          devices: devicesVia(devices, 'relay'),
          revoke,
          empty: '暂无已配对的云端设备',
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

  function ConnectionBody({ info, devices, err, revoke, relay, onEnroll, onDisconnect, load }) {
    const [active, setActive] = React.useState('lan')
    React.useEffect(() => { load?.() }, [active, load])
    if (err) return jsx('div', { className: 'dshlink-status is-error', children: `加载失败：${err}` })
    if (!info) return jsx('div', { className: 'dshlink-status', children: '加载中…' })
    return jsxs('div', {
      className: 'dshlink-connection',
      children: [
        jsx(ConnectionTabs, { active, onChange: setActive }),
        active === 'lan' ? jsx(LanBody, { info, devices, revoke }) : jsx(RemoteBody, { info, devices, revoke, relay, onEnroll, onDisconnect }),
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

    React.useEffect(() => {
      if (!active) return undefined
      load()
      const timer = setInterval(load, 30_000)
      return () => clearInterval(timer)
    }, [active, load])

    const revoke = async (deviceName) => {
      try {
        await fetch('/dsh-link/revoke', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ name: deviceName }),
        })
      } finally {
        load()
      }
    }

    const onEnroll = async ({ address, inviteCode, insecureTls }) => {
      const res = await fetch('/dsh-link/relay-enroll', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ address, inviteCode, insecureTls }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
      await load()
    }
    const onDisconnect = async () => {
      await fetch('/dsh-link/relay-disconnect', { method: 'POST' })
      await load()
    }

    return { info, devices, err, revoke, relay, onEnroll, onDisconnect, load }
  }

  function LinkPanel() {
    const [open, setOpen] = React.useState(false)
    const { info, devices, err, revoke, relay, onEnroll, onDisconnect, load } = usePairData(open)

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
                  jsx(ConnectionBody, { info, devices, err, revoke, relay, onEnroll, onDisconnect, load }),
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
    const { info, devices, err, revoke, relay, onEnroll, onDisconnect, load } = usePairData(true)

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        jsxs('div', {
          className: 'dshlink-settings dshlink-root',
          children: [
            jsx(BrandHeader, {}),
            jsx(ConnectionBody, { info, devices, err, revoke, relay, onEnroll, onDisconnect, load }),
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
