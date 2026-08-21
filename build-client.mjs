/**
 * 组装 src/client.js —— 仅「手机连接」面板（配对码/二维码/设备管理）。
 * 移动布局壳（hanui）已迁出插件，由 App 注入（assets/mobile-client.js），
 * 插件不再持有任何页面布局代码。
 */
import { readFileSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(fileURLToPath(import.meta.url))
const panel = readFileSync(join(root, "src", "module2.js"), "utf8")

const header = `/**
 * dsh-links 客户端面（src/client.js，由 build-client.mjs 生成，勿手改）
 * 单模块：createPanelModule —— 「手机连接」面板（src/module2.js）
 * 说明：移动布局适配已由 Android App 注入（assets/mobile-client.js），
 *       本插件不注入任何页面布局，桌面端 DSH Web UI 保持原样。
 */
`

const boot = `
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
`

const out = header + panel + boot
writeFileSync(join(root, "src", "client.js"), out)
console.log(`client.js written: ${out.split("\n").length} lines`)
