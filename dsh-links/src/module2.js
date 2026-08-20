/**
 * dsh-links 客户端面 · 面板模块（作为 createPanelModule 工厂被主模块组合调用）
 * 「📱 手机连接」入口：侧栏设置区旁的小手机图标（复用侧栏 iconButton 样式），
 * 点击弹出面板：二维码 + 配对码 + 已配对设备管理。
 *
 * v3 — 修复手机图标被挤出侧栏 + tooltip 被裁切：
 *   · settings 区域子元素 flex:0 0 auto，不拉伸
 *   · 手机图标 prepend 到设置左边（HStudio 布局）
 *   · tooltip 改 position:fixed + JS 定位（不受父级 overflow 影响）
 *   · 面板动画/样式精致化
 */
const createPanelModule = (require) => {
  const React = require('react')
  const { jsx, jsxs } = require('react/jsx-runtime')

  function formatFingerprint(fp) {
    const hex = String(fp || '').replace(/[^0-9a-f]/gi, '').toLowerCase()
    if (hex.length !== 64) return fp || ''
    return hex.match(/.{1,4}/g).join(' ')
  }

  function FingerprintBlock(info) {
    if (!info?.certFingerprint) return null
    return jsxs(React.Fragment, { children: [
      jsx('div', { className: 'dshlink-sub', children: 'TLS 指纹（手动添加时请与手机核对）' }),
      jsx('div', { className: 'dshlink-fp', children: formatFingerprint(info.certFingerprint) }),
    ] })
  }

  const STYLE = `
    /* ====== 面板样式 ====== */
    .dshlink-backdrop {
      --dshlink-bg: var(--dsw-alias-bg-base, #fafafa);
      --dshlink-card: var(--dsw-alias-bg-layer-2, #ffffff);
      --dshlink-text: var(--dsw-alias-label-primary, #1a1a1a);
      --dshlink-secondary: var(--dsw-alias-label-secondary, #666666);
      --dshlink-border: var(--dsw-alias-border-l2, #e0e0e0);
      position: fixed; inset: 0; z-index: 99995;
      background: rgba(0, 0, 0, 0.45);
      display: flex; align-items: center; justify-content: center;
      padding: 16px;
      animation: dshlink-fadein 0.2s ease;
    }
    @keyframes dshlink-fadein {
      from { opacity: 0; }
      to { opacity: 1; }
    }
    .dshlink-panel {
      background: var(--dshlink-card); color: var(--dshlink-text);
      border: 1px solid var(--dshlink-border);
      border-radius: 14px; padding: 20px;
      width: 100%; max-width: 400px; max-height: 82vh; overflow: auto;
      box-shadow: 0 16px 48px rgba(0, 0, 0, 0.25);
      font-family: system-ui, -apple-system, "PingFang SC", sans-serif;
      animation: dshlink-slideup 0.25s cubic-bezier(0.32, 0.72, 0, 1);
    }
    @keyframes dshlink-slideup {
      from { opacity: 0; transform: translateY(12px); }
      to { opacity: 1; transform: translateY(0); }
    }
    .dshlink-title {
      font-size: 17px; font-weight: 700; margin-bottom: 8px;
    }
    .dshlink-sub {
      font-size: 13px; color: var(--dshlink-secondary); margin: 6px 0; word-break: break-all;
    }
    .dshlink-code {
      font-size: 28px; font-weight: 800; letter-spacing: 8px; color: var(--dshlink-text);
      margin: 8px 0; text-align: center;
      background: var(--dsw-alias-bg-layer-1, #f0f0f0); border-radius: 10px; padding: 12px;
    }
    .dshlink-qr {
      display: block; width: 220px; height: 220px;
      margin: 12px auto; border-radius: 10px;
    }
    .dshlink-fp {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px; letter-spacing: 0.04em;
      word-break: break-all;
      background: var(--dsw-alias-bg-layer-1, #f0f0f0);
      border-radius: 8px; padding: 8px 10px; margin: 8px 0;
    }
    .dshlink-urls {
      font-size: 11px; color: var(--dshlink-secondary); white-space: pre-line;
      margin: 6px 0; word-break: break-all;
    }
    .dshlink-devices {
      margin-top: 12px; border-top: 1px solid var(--dshlink-border); padding-top: 10px;
    }
    .dshlink-device {
      display: flex; align-items: center; justify-content: space-between;
      padding: 8px 4px; font-size: 13px;
      border-radius: 8px;
    }
    .dshlink-device:hover {
      background: var(--dsw-alias-bg-layer-1, #f5f5f5);
    }
    .dshlink-device button {
      border: 1px solid #fca5a5; background: #fef2f2; color: #b91c1c;
      border-radius: 6px; padding: 4px 12px; font-size: 12px; cursor: pointer;
      transition: background 0.15s ease;
    }
    .dshlink-device button:hover {
      background: #fee2e2;
    }
    .dshlink-close {
      margin-top: 14px; width: 100%; padding: 10px; border: none; border-radius: 10px;
      background: var(--dshlink-text); color: var(--dshlink-card); font-size: 14px; font-weight: 500;
      cursor: pointer; transition: background 0.15s ease;
    }
    .dshlink-close:hover {
      opacity: 0.82;
    }

    /* ====== 设置面板分区（settings.section）优雅布局 ====== */
    .dshlink-settings {
      font-family: system-ui, -apple-system, "PingFang SC", sans-serif;
      color: var(--dsw-alias-label-primary, #1a1a1a);
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 4px 0 20px;
    }
    /* 标题行 */
    .dshlink-settings .dshlink-head {
      display: flex; align-items: center; gap: 8px;
    }
    .dshlink-settings .dshlink-head-icon {
      display: flex; align-items: center; justify-content: center;
      width: 28px; height: 28px; flex: none;
      border-radius: 8px;
      background: var(--dsw-alias-bg-layer-1, #f0f0f0);
      color: var(--dsw-alias-label-primary, #1a1a1a);
    }
    .dshlink-settings .dshlink-title {
      font-size: 15px; font-weight: 700;
    }
    .dshlink-settings .dshlink-sub {
      font-size: 12px; color: var(--dsw-alias-label-secondary, #666666);
      line-height: 18px; margin: 0; word-break: break-all;
    }
    /* 二维码 + 配对码 主卡片 */
    .dshlink-settings .dshlink-card {
      display: flex; flex-direction: column; align-items: center; gap: 8px;
      background: var(--dsw-alias-bg-layer-1, #f7f7f7);
      border: 1px solid var(--dsw-alias-border-l2, #e8e8e8);
      border-radius: 12px;
      padding: 16px;
    }
    .dshlink-settings .dshlink-qr {
      display: block; width: 116px; height: 116px;
      border-radius: 8px;
      background: #ffffff;
      box-shadow: var(--dsw-shadow-lv2, 0 2px 8px rgba(0, 0, 0, .15));
    }
    .dshlink-settings .dshlink-code {
      font-size: 24px; font-weight: 800; letter-spacing: 8px;
      font-variant-numeric: tabular-nums;
      text-align: center;
      color: var(--dsw-alias-label-primary, #1a1a1a);
    }
    .dshlink-settings .dshlink-hint {
      font-size: 11px; color: var(--dsw-alias-label-tertiary, #999999);
      text-align: center; margin: 0;
    }
    /* 地址卡片 */
    .dshlink-settings .dshlink-urls {
      font-size: 11px; color: var(--dsw-alias-label-secondary, #666666);
      font-family: "SF Mono", Menlo, Consolas, monospace;
      white-space: pre-line; margin: 0; word-break: break-all;
      background: var(--dsw-alias-bg-layer-1, #f7f7f7);
      border: 1px solid var(--dsw-alias-border-l2, #e8e8e8);
      border-radius: 10px; padding: 10px 12px;
      line-height: 20px;
    }
    /* 设备列表 */
    .dshlink-settings .dshlink-devices {
      display: flex; flex-direction: column; gap: 4px;
    }
    .dshlink-settings .dshlink-devices-title {
      font-size: 12px; color: var(--dsw-alias-label-secondary, #666666);
      margin: 4px 0 2px;
    }
    .dshlink-settings .dshlink-device {
      display: flex; align-items: center; gap: 10px;
      padding: 8px 10px; font-size: 13px; border-radius: 10px;
      background: var(--dsw-alias-bg-layer-1, #f7f7f7);
      border: 1px solid var(--dsw-alias-border-l2, #e8e8e8);
      transition: background-color .15s ease;
    }
    .dshlink-settings .dshlink-device:hover {
      background: var(--dsw-alias-bg-layer-2, #efefef);
    }
    .dshlink-settings .dshlink-device-dot {
      width: 7px; height: 7px; flex: none;
      border-radius: 50%;
      background: var(--dsw-alias-state-success-primary, #22c55e);
    }
    .dshlink-settings .dshlink-device-copy {
      display: flex; flex-direction: column; gap: 1px;
      min-width: 0; flex: 1;
    }
    .dshlink-settings .dshlink-device-name {
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      font-weight: 500;
    }
    .dshlink-settings .dshlink-device-time {
      font-size: 11px; color: var(--dsw-alias-label-tertiary, #999999);
    }
    .dshlink-settings .dshlink-device button {
      flex: none;
      border: 1px solid #fca5a5; background: #fef2f2; color: #b91c1c;
      border-radius: 6px; padding: 3px 10px; font-size: 12px; cursor: pointer;
      transition: background-color .15s ease;
    }
    .dshlink-settings .dshlink-device button:hover {
      background: #fee2e2;
    }
    .dshlink-settings .dshlink-empty {
      font-size: 12px; color: var(--dsw-alias-label-tertiary, #999999);
      padding: 6px 2px;
    }
  `

  const PHONE_SVG =
    '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="6.2" y="2.8" width="11.6" height="18.4" rx="2.6"/><line x1="10.2" y1="18.4" x2="13.8" y2="18.4"/></svg>'

  function LinkPanel() {
    const [open, setOpen] = React.useState(false)
    const [info, setInfo] = React.useState(null)
    const [devices, setDevices] = React.useState([])
    const [err, setErr] = React.useState('')

    React.useEffect(() => {
      window.__dshlinkOpenPanel = () => setOpen(true)
      return () => {
        delete window.__dshlinkOpenPanel
      }
    }, [])

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
      if (open) load()
    }, [open, load])

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

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        open
          ? jsx('div', {
              className: 'dshlink-backdrop',
              onClick: () => setOpen(false),
              children: jsx('div', {
                className: 'dshlink-panel',
                onClick: (e) => e.stopPropagation(),
                children: jsxs(React.Fragment, {
                  children: [
                    jsx('div', { className: 'dshlink-title', children: '📱 手机连接' }),
                    err
                      ? jsx('div', { className: 'dshlink-sub', children: `加载失败：${err}` })
                      : !info
                        ? jsx('div', { className: 'dshlink-sub', children: '加载中…' })
                        : jsxs(React.Fragment, {
                            children: [
                              jsx('div', { className: 'dshlink-sub', children: '用 dsh 手机 App 扫码，即可从手机访问本机' }),
                              jsx('div', { className: 'dshlink-code', children: info.pairingCode }),
                              FingerprintBlock(info),
                              jsx('div', { className: 'dshlink-sub', children: '配对码 10 分钟内有效，扫码自动批准' }),
                              jsx('img', { className: 'dshlink-qr', src: '/dsh-link/qr.png', alt: '配对二维码' }),
(info.infos ?? info.urls?.map((u) => ({ url: u, label: u, category: "other", isRecommended: false })) ?? []).length
                              ? jsx('div', { className: 'dshlink-urls', children: (
                                (info.infos ?? info.urls?.map((u) => ({ url: u, label: u, category: "other", isRecommended: false })) ?? []).map((item, i) => {
                                  const isRecommended = item.isRecommended || i === 0
                                  return jsx('div', {
                                    key: item.url,
                                    style: {
                                      display: 'flex',
                                      alignItems: 'center',
                                      gap: '4px',
                                      fontSize: '11px',
                                      color: isRecommended ? 'var(--dshlink-text)' : 'var(--dshlink-secondary)',
                                      marginTop: '6px',
                                    },
                                    children: [
                                      isRecommended && jsx('span', { style: { color: '#22c55e', fontWeight: '600' }, children: '⭐ 推荐' }),
                                      item.url
                                    ]
                                  })
                                })
                              ) })
                              : null,
                              devices.length
                                ? jsx('div', {
                                    className: 'dshlink-devices',
                                    children: jsxs(React.Fragment, {
                                      children: [
                                        jsx('div', { className: 'dshlink-sub', children: '已配对设备' }),
                                        ...devices.map((d) =>
                                          jsxs('div', { className: 'dshlink-device', key: d.name, children: [
                                            jsx('span', { children: d.name }),
                                            jsx('button', { onClick: () => revoke(d.name), children: '吊销' }),
                                          ] }),
                                        ),
                                      ],
                                    }),
                                  })
                                : null,
                            ],
                          }),
                    jsx('button', { className: 'dshlink-close', onClick: () => setOpen(false), children: '关闭' }),
                  ],
                }),
              }),
            })
          : null,
      ],
    })
  }

  /** 设备最近在线时间（相对时间） */
  function deviceSeenLabel(lastSeenAt) {
    if (!lastSeenAt) return '暂未连接'
    const diff = Date.now() - lastSeenAt
    if (diff < 60_000) return '刚刚在线'
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前在线`
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前在线`
    return `${Math.floor(diff / 86_400_000)} 天前在线`
  }

  /** 手机连接设置分区（注册到 Web UI 设置面板 settings.section，替代侧边栏图标） */
  function DshLinkSettingsSection() {
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
      load()
      const timer = setInterval(load, 30_000)
      return () => clearInterval(timer)
    }, [load])

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

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        jsxs('div', {
          className: 'dshlink-settings',
          children: [
            jsxs('div', { className: 'dshlink-head', children: [
              jsx('div', { className: 'dshlink-head-icon', children:
                jsx('svg', { viewBox: '0 0 24 24', width: 15, height: 15, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
                  children: jsxs(React.Fragment, { children: [
                    jsx('rect', { x: 6.2, y: 2.8, width: 11.6, height: 18.4, rx: 2.6 }),
                    jsx('line', { x1: 10.2, y1: 18.4, x2: 13.8, y2: 18.4 }),
                  ] }) }) }),
              jsx('div', { className: 'dshlink-title', children: '手机连接' }),
            ]}),
            jsx('div', { className: 'dshlink-sub', children: '用 dsh 手机 App 扫码，即可从手机访问本机' }),
            err
              ? jsx('div', { className: 'dshlink-sub', children: `加载失败：${err}` })
              : !info
                ? jsx('div', { className: 'dshlink-sub', children: '加载中…' })
                : jsxs(React.Fragment, {
                    children: [
                      jsxs('div', { className: 'dshlink-card', children: [
                        jsx('img', { className: 'dshlink-qr', src: '/dsh-link/qr.png', alt: '配对二维码' }),
                        jsx('div', { className: 'dshlink-code', children: info.pairingCode }),
                        FingerprintBlock(info),
                        jsx('p', { className: 'dshlink-hint', children: '配对码 10 分钟内有效，扫码自动批准' }),
                      ]}),
                      (info.infos ?? info.urls?.map((u) => ({ url: u, label: u, category: "other", isRecommended: false })) ?? []).length
                        ? jsx('div', { className: 'dshlink-urls', children: (
                            (info.infos ?? info.urls?.map((u) => ({ url: u, label: u, category: "other", isRecommended: false })) ?? []).map((item, i) => {
                              const isRecommended = item.isRecommended || i === 0
                              return jsx('div', {
                                key: item.url,
                                style: {
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: '4px',
                                  marginTop: '4px',
                                  color: isRecommended ? 'var(--dsw-alias-label-primary, #1a1a1a)' : 'var(--dsw-alias-label-secondary, #666666)',
                                },
                                children: [
                                  isRecommended && jsx('span', { style: { color: '#22c55e', fontWeight: '600', fontSize: '10px' }, children: '⭐ 推荐' }),
                                  item.url
                                ]
                              })
})
                          ) })
                        : null,
                        jsxs('div', { className: 'dshlink-devices', children: [
                        jsx('div', { className: 'dshlink-devices-title', children: '已配对设备' }),
                        devices.length
                          ? devices.map((d) =>
                              jsxs('div', { className: 'dshlink-device', key: d.name, children: [
                                jsx('span', { className: 'dshlink-device-dot' }),
                                jsxs('div', { className: 'dshlink-device-copy', children: [
                                  jsx('div', { className: 'dshlink-device-name', children: d.name }),
                                  jsx('div', { className: 'dshlink-device-time', children: deviceSeenLabel(d.lastSeenAt) }),
                                ]}),
                                jsx('button', { onClick: () => revoke(d.name), children: '吊销' }),
                              ] }),
                            )
                          : jsx('div', { className: 'dshlink-empty', children: '暂无已配对设备' }),
                      ]}),
                    ],
                  }),
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
