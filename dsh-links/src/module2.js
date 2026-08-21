/**
 * dsh-links 客户端面 · 面板模块（作为 createPanelModule 工厂被主模块组合调用）
 * 「手机连接」：局域网配对（二维码 / 配对码 / 设备）+ 云端连接（敬请期待）。
 *
 * Hallmark pre-emit critique: P5 H5 E4 S5 R5 V4
 */
const createPanelModule = (require) => {
  const React = require('react')
  const { jsx, jsxs } = require('react/jsx-runtime')

  function formatFingerprint(fp) {
    const hex = String(fp || '').replace(/[^0-9a-f]/gi, '').toLowerCase()
    if (hex.length !== 64) return fp || ''
    return hex.match(/.{1,4}/g).join(' ')
  }

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

    .dshlink-modes {
      display: grid; grid-template-columns: 1fr 1fr; gap: 4px;
      padding: 4px; border-radius: 12px;
      background: var(--dl-soft);
      border: 1px solid var(--dl-line);
    }
    .dshlink-mode {
      appearance: none; border: 0; cursor: pointer;
      border-radius: 9px; padding: 10px 12px;
      background: transparent; color: var(--dl-muted);
      font: inherit; font-size: 13px; font-weight: 600;
      letter-spacing: 0.01em;
      transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
    }
    .dshlink-mode:hover { color: var(--dl-ink) }
    .dshlink-mode:focus-visible {
      outline: 2px solid var(--dl-accent); outline-offset: 2px;
    }
    .dshlink-mode.is-active {
      background: var(--dl-paper);
      color: var(--dl-ink);
      box-shadow: 0 1px 2px rgba(18, 20, 26, 0.06), 0 4px 12px rgba(18, 20, 26, 0.06);
    }

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

    .dshlink-fp-block { display: flex; flex-direction: column; gap: 6px }
    .dshlink-fp {
      font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
      font-size: 11px; line-height: 1.55; letter-spacing: 0.02em;
      word-break: break-all;
      padding: 10px 12px; border-radius: 10px;
      background: var(--dl-soft); border: 1px solid var(--dl-line);
      color: var(--dl-muted);
    }
    .dshlink-fp-help {
      margin: 0; font-size: 11px; line-height: 1.45; color: var(--dl-faint);
    }
    .dshlink-tunnel-note {
      margin: 0; font-size: 12px; line-height: 1.5; color: var(--dl-muted);
      padding: 10px 12px; border-radius: 10px;
      background: var(--dl-soft); border: 1px dashed var(--dl-line);
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

    .dshlink-cloud {
      position: relative; overflow: hidden;
      border-radius: 16px; padding: 28px 22px 24px;
      border: 1px solid var(--dl-line);
      background:
        radial-gradient(120% 90% at 100% 0%, rgba(13, 235, 243, 0.16), transparent 55%),
        radial-gradient(80% 70% at 0% 100%, rgba(10, 158, 170, 0.08), transparent 50%),
        var(--dl-soft);
      text-align: left;
    }
    .dshlink-cloud-badge {
      display: inline-flex; align-items: center;
      font-size: 11px; font-weight: 700; letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--dl-accent);
      background: rgba(255, 255, 255, 0.72);
      border: 1px solid rgba(10, 158, 170, 0.22);
      border-radius: 999px; padding: 4px 10px;
      margin-bottom: 14px;
    }
    .dshlink-cloud-title {
      margin: 0 0 8px; font-size: 18px; font-weight: 650;
      letter-spacing: -0.02em; line-height: 1.3;
    }
    .dshlink-cloud-body {
      margin: 0; font-size: 13px; line-height: 1.55; color: var(--dl-muted);
      max-width: 32em;
    }
    .dshlink-cloud-note {
      margin: 16px 0 0; font-size: 12px; color: var(--dl-faint);
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

  function ModeSwitch({ mode, onChange }) {
    return jsxs('div', {
      className: 'dshlink-modes',
      role: 'tablist',
      'aria-label': '连接方式',
      children: [
        jsx('button', {
          type: 'button',
          role: 'tab',
          'aria-selected': mode === 'lan',
          className: 'dshlink-mode' + (mode === 'lan' ? ' is-active' : ''),
          onClick: () => onChange('lan'),
          children: '局域网',
        }),
        jsx('button', {
          type: 'button',
          role: 'tab',
          'aria-selected': mode === 'cloud',
          className: 'dshlink-mode' + (mode === 'cloud' ? ' is-active' : ''),
          onClick: () => onChange('cloud'),
          children: '云端连接',
        }),
      ],
    })
  }

  function BrandHeader() {
    return jsxs('div', {
      className: 'dshlink-brand',
      children: [
        jsx('div', { className: 'dshlink-brand-kicker', children: 'DSH Links' }),
        jsx('div', { className: 'dshlink-brand-title', children: '手机连接' }),
        jsx('p', {
          className: 'dshlink-brand-lede',
          children: '把本机 dsh 接到手机。局域网立即可用；云端通道正在筹备。',
        }),
      ],
    })
  }

  function CloudComingSoon() {
    return jsxs('div', {
      className: 'dshlink-cloud',
      children: [
        jsx('div', { className: 'dshlink-cloud-badge', children: 'Coming soon' }),
        jsx('div', { className: 'dshlink-cloud-title', children: '云端连接 · 敬请期待' }),
        jsx('p', {
          className: 'dshlink-cloud-body',
          children: '跨网络、不依赖同一 Wi‑Fi 的安全中继还在搭建中。上线后，你仍可用现有配对关系，从任意网络访问本机会话。',
        }),
        jsx('p', {
          className: 'dshlink-cloud-note',
          children: '当前请使用「局域网」：手机与电脑连上同一网络后扫码即可。',
        }),
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
                src: '/dsh-link/qr.png',
                alt: '配对二维码',
              }),
            }),
            jsxs('div', {
              className: 'dshlink-pair-meta',
              children: [
                jsx('div', { className: 'dshlink-pair-label', children: '一次性配对码' }),
                jsx('p', { className: 'dshlink-code', children: info.pairingCode }),
                jsx('p', {
                  className: 'dshlink-hint',
                  children: '10 分钟内有效。用 DSH Links App 扫码即可（指纹已写入二维码，无需手填）；或手动输入地址与配对码。',
                }),
              ],
            }),
          ],
        }),
        info.certFingerprint
          ? jsxs('div', {
              className: 'dshlink-fp-block',
              children: [
                jsx('div', { className: 'dshlink-section-label', children: 'TLS 指纹' }),
                jsx('div', { className: 'dshlink-fp', children: formatFingerprint(info.certFingerprint) }),
                jsx('p', {
                  className: 'dshlink-fp-help',
                  children: '自签证书的身份指纹。扫码自动带上，不用填写；仅手动添加时在手机上对照确认即可。',
                }),
              ],
            })
          : null,
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
          children: '手机与电脑不在同一 Wi‑Fi 时，有基础可自建 Tailscale / VPN / frp 等穿透，并把地址写入配置 extraUrls；勿将 18640 裸暴露到公网。',
        }),
        jsxs('div', {
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
              : jsx('div', { className: 'dshlink-empty', children: '暂无已配对设备' }),
          ],
        }),
      ],
    })
  }

  function ConnectionBody({ mode, info, devices, err, revoke }) {
    if (mode === 'cloud') return jsx(CloudComingSoon, {})
    if (err) return jsx('div', { className: 'dshlink-status is-error', children: `加载失败：${err}` })
    if (!info) return jsx('div', { className: 'dshlink-status', children: '加载中…' })
    return jsx(LanBody, { info, devices, revoke })
  }

  function usePairData(active) {
    const [info, setInfo] = React.useState(null)
    const [devices, setDevices] = React.useState([])
    const [err, setErr] = React.useState('')

    const load = React.useCallback(async () => {
      try {
        const resInfo = await fetch('/dsh-link/pair-info')
        const resDevices = await fetch('/dsh-link/devices')
        if (!resInfo.ok || !resDevices.ok) throw new Error(`HTTP ${resInfo.status}/${resDevices.status}`)
        setInfo(await resInfo.json())
        const data = await resDevices.json()
        setDevices(data.devices ?? [])
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

    return { info, devices, err, revoke }
  }

  function LinkPanel() {
    const [open, setOpen] = React.useState(false)
    const [mode, setMode] = React.useState('lan')
    const { info, devices, err, revoke } = usePairData(open)

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
                  jsx(ModeSwitch, { mode, onChange: setMode }),
                  jsx(ConnectionBody, { mode, info, devices, err, revoke }),
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
    const [mode, setMode] = React.useState('lan')
    const { info, devices, err, revoke } = usePairData(true)

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        jsxs('div', {
          className: 'dshlink-settings dshlink-root',
          children: [
            jsx(BrandHeader, {}),
            jsx(ModeSwitch, { mode, onChange: setMode }),
            jsx(ConnectionBody, { mode, info, devices, err, revoke }),
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
