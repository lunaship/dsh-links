/**
 * dsh-links 客户端面（src/client.js，由 build-client.mjs 生成，勿手改）
 * 单模块：createPanelModule —— 「手机连接」面板（src/module2.js）
 * 说明：移动布局适配已由 Android App 注入（assets/mobile-client.js），
 *       本插件不注入任何页面布局，桌面端 DSH Web UI 保持原样。
 */
/**
 * dsh-links 客户端面 · 面板模块（作为 createPanelModule 工厂被主模块组合调用）
 * 「手机连接」：局域网配对与远端连接路线（Tailscale / Cloudflare Tunnel / DSH Links Relay）。
 *
 * Hallmark pre-emit critique: P5 H5 E5 S5 R5 V5
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
    .dshlink-remote-card {
      display: flex; flex-direction: column; gap: 10px; padding: 14px;
      border-radius: 12px; border: 1px solid var(--dl-line); background: var(--dl-paper);
    }
    .dshlink-remote-card-head { display: flex; align-items: flex-start; gap: 9px }
    .dshlink-remote-icon {
      display: grid; place-items: center; flex: none; width: 27px; height: 27px; border-radius: 8px;
      color: #7050c4; background: rgba(112, 78, 196, 0.11); font-size: 14px; font-weight: 700;
    }
    .dshlink-remote-title { font-size: 13px; font-weight: 650; color: var(--dl-ink) }
    .dshlink-remote-summary { margin: 2px 0 0; font-size: 11px; line-height: 1.45; color: var(--dl-muted) }
    .dshlink-remote-badge {
      flex: none; margin-left: auto; font-size: 10px; font-weight: 700; letter-spacing: 0.04em;
      color: #7050c4; background: rgba(112, 78, 196, 0.1); border-radius: 999px; padding: 3px 7px;
    }
    .dshlink-remote-steps { margin: 0; padding-left: 19px; font-size: 11px; line-height: 1.55; color: var(--dl-muted) }
    .dshlink-remote-steps li + li { margin-top: 4px }
    .dshlink-remote-code, .dshlink-remote-fingerprint {
      margin: 0; overflow-x: auto; white-space: pre-wrap; word-break: break-word;
      font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: 10px; line-height: 1.5;
      color: var(--dl-muted); background: var(--dl-soft); border: 1px solid var(--dl-line); border-radius: 8px; padding: 9px 10px;
    }
    .dshlink-remote-fingerprint { color: var(--dl-ink); letter-spacing: 0.02em }
    .dshlink-remote-note {
      margin: 0; padding: 9px 10px; font-size: 11px; line-height: 1.5; color: var(--dl-muted);
      background: rgba(185, 124, 18, 0.08); border: 1px solid rgba(185, 124, 18, 0.19); border-radius: 8px;
    }
    .dshlink-relay-flow {
      margin: 0; font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: 10px;
      line-height: 1.5; color: var(--dl-muted); text-align: center;
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
          children: '局域网 Beta 可立即扫码配对；扫码地址或手动地址可连接家中或远程服务器上的 DSH。跨网络可自管 Tunnel，DSH Links Relay 正在建设中。',
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
                  children: '10 分钟内有效。用 DSH Links App 扫码即可；二维码已包含地址、配对码和安全指纹。',
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

  function RemoteBody({ info }) {
    const pairingCode = info?.pairingCode || '本页加载完成后显示'
    const fingerprint = info?.certFingerprint ? formatFingerprint(info.certFingerprint) : '本页加载完成后显示'
    const cloudflaredConfig = `tunnel: <你的 Tunnel UUID>
credentials-file: <cloudflared 凭据文件>
ingress:
  - hostname: dsh.example.com
    service: https://127.0.0.1:18640
    originRequest:
      noTLSVerify: true
  - service: http_status:404`

    return jsxs('div', {
      className: 'dshlink-remote',
      children: [
        jsx('p', {
          className: 'dshlink-remote-intro',
          children: '扫码中的地址或手动填写的地址可以指向家中电脑或远程服务器上的 DSH。当前跨网络可使用自管 Tunnel；它们不改变配对码、设备 Token 或吊销机制，只改变手机抵达 DSH 的网络路径。',
        }),
        jsxs('section', {
          className: 'dshlink-remote-card',
          children: [
            jsxs('div', {
              className: 'dshlink-remote-card-head',
              children: [
                jsx('span', { className: 'dshlink-remote-icon', 'aria-hidden': true, children: 'T' }),
                jsxs('div', {
                  children: [
                    jsx('div', { className: 'dshlink-remote-title', children: 'Tailscale 私网连接' }),
                    jsx('p', { className: 'dshlink-remote-summary', children: '推荐个人使用：手机和电脑加入同一 tailnet，不开放公网入口。' }),
                  ],
                }),
                jsx('span', { className: 'dshlink-remote-badge', children: '推荐' }),
              ],
            }),
            jsxs('ol', {
              className: 'dshlink-remote-steps',
              children: [
                jsx('li', { children: '在电脑和手机安装 Tailscale，并登录同一个 tailnet。' }),
                jsx('li', { children: '电脑执行 `tailscale ip -4`，取得 100.x.y.z 地址。' }),
                jsx('li', { children: `在 DSH Links App 选择“手动添加”，填入 https://100.x.y.z:18640 与本页配对码 ${pairingCode}。` }),
                jsx('li', { children: '首次连接会要求核对证书指纹；核对一致后再继续。' }),
              ],
            }),
            jsxs('div', {
              children: [
                jsx('div', { className: 'dshlink-section-label', children: 'Tailscale 手动连接时核对的 TLS 指纹' }),
                jsx('pre', { className: 'dshlink-remote-fingerprint', children: fingerprint }),
              ],
            }),
          ],
        }),
        jsxs('section', {
          className: 'dshlink-remote-card',
          children: [
            jsxs('div', {
              className: 'dshlink-remote-card-head',
              children: [
                jsx('span', { className: 'dshlink-remote-icon', 'aria-hidden': true, children: 'C' }),
                jsxs('div', {
                  children: [
                    jsx('div', { className: 'dshlink-remote-title', children: 'Cloudflare Tunnel' }),
                    jsx('p', { className: 'dshlink-remote-summary', children: '使用你自己的域名把公开 HTTPS 请求转入本机；不需要路由器端口转发。' }),
                  ],
                }),
                jsx('span', { className: 'dshlink-remote-badge', children: '实验性' }),
              ],
            }),
            jsxs('ol', {
              className: 'dshlink-remote-steps',
              children: [
                jsx('li', { children: '创建你自己的 Tunnel 和 hostname，再将 hostname 仅指向本机 18640。' }),
                jsx('li', { children: '使用下面的 ingress；不要把 DSH Web 管理台 3080 一起发布。' }),
                jsx('li', { children: `在 App“手动添加”中填入 https://你的域名 与本页配对码 ${pairingCode}。` }),
              ],
            }),
            jsx('pre', { className: 'dshlink-remote-code', children: cloudflaredConfig }),
            jsx('p', {
              className: 'dshlink-remote-note',
              children: '当前 App 不会完成 Cloudflare Access 登录，也不会携带 Access JWT；启用 `originRequest.access.required` 会导致配对与运行请求失败，请勿在当前 Beta 启用。',
            }),
          ],
        }),
        jsxs('section', {
          className: 'dshlink-roadmap',
          children: [
            jsx('span', { className: 'dshlink-roadmap-mark', 'aria-hidden': true, children: '↗' }),
            jsxs('div', {
              className: 'dshlink-roadmap-copy',
              children: [
                jsx('div', { className: 'dshlink-roadmap-title', children: 'DSH Links Relay' }),
                jsx('p', {
                  className: 'dshlink-roadmap-text',
                  children: '正在建设中。计划中电脑侧 local-relay 与手机 App 均主动连接 Relay；Relay 仅实时转发已配对设备的请求与响应，不持久化存储会话内容、文件、工作区数据或设备内容。这样个人电脑不接受公网入站连接，设备吊销会切断后续远端请求。',
                }),
                jsx('p', { className: 'dshlink-relay-flow', children: '手机 App ⇄ DSH Links Relay（建设中）⇄ local-relay ⇄ 127.0.0.1:18640' }),
              ],
            }),
            jsx('span', { className: 'dshlink-roadmap-status', children: '建设中' }),
          ],
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

  function ConnectionBody({ info, devices, err, revoke }) {
    const [active, setActive] = React.useState('lan')
    if (err) return jsx('div', { className: 'dshlink-status is-error', children: `加载失败：${err}` })
    if (!info) return jsx('div', { className: 'dshlink-status', children: '加载中…' })
    return jsxs('div', {
      className: 'dshlink-connection',
      children: [
        jsx(ConnectionTabs, { active, onChange: setActive }),
        active === 'lan' ? jsx(LanBody, { info, devices, revoke }) : jsx(RemoteBody, { info }),
      ],
    })
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
                  jsx(ConnectionBody, { info, devices, err, revoke }),
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
    const { info, devices, err, revoke } = usePairData(true)

    return jsxs(React.Fragment, {
      children: [
        jsx('style', { children: STYLE, 'data-plugin': 'dsh-links' }),
        jsxs('div', {
          className: 'dshlink-settings dshlink-root',
          children: [
            jsx(BrandHeader, {}),
            jsx(ConnectionBody, { info, devices, err, revoke }),
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

window.__ModuleLoader__.load({
  id: 'dsh-links',
  factory: (require) => {
    const panel = createPanelModule(require)
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    function apply(ctx) {
      panel.apply(ctx)
    }

    exports.apply = apply
    exports.inject = panel.inject ?? []
    return module.exports
  },
})
