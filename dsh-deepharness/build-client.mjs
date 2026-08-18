/**
 * 组装 src/client.js：
 *   vendor/hanui-client.js（原样，MIT）+ src/module2.js（连接面板）
 *   → 拆掉 hanui 的独立 load 包装变成工厂函数，与面板一起组合进
 *     唯一入口模块 id 'dsh-deepharness'（客户端运行器只启动 manifest 声明的模块）。
 */
import { readFileSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(fileURLToPath(import.meta.url))
const hanui = readFileSync(join(root, "vendor", "hanui-client.js"), "utf8")
const panel = readFileSync(join(root, "src", "module2.js"), "utf8")

const header = `/**
 * dsh-deepharness 客户端面（src/client.js，由 build-client.mjs 生成，勿手改）
 * 单入口模块组合两个工厂：
 *   createHanuiModule —— 移动布局壳（MIT License, Copyright (c) dsh-mobile-hanui contributors,
 *                        https://github.com/Z-6354/dsh-mobile-hanui，vendor/hanui-client.js 原样合并）
 *   createPanelModule —— 「手机连接」面板（src/module2.js）
 */
`

const loadHeader = "window.__ModuleLoader__.load({\n  id: 'dsh-mobile-hanui',\n  factory: (require) => {"
if (!hanui.includes(loadHeader)) throw new Error("hanui load header not found")
let h = hanui.replace(loadHeader, "const createHanuiModule = (require) => {")

const tailMarker = "return module.exports\n  },\n})"
const tailIdx = h.indexOf(tailMarker)
if (tailIdx === -1) throw new Error("hanui module tail not found")
h = h.slice(0, tailIdx) + "return module.exports\n  }" + h.slice(tailIdx + tailMarker.length)

// 优化：悬浮球默认位置改到左下角（避开顶部标题区），并通过版本化存储键重置已保存的旧位置
h = h.replace("const FAB_POS_KEY = 'dsh-mobile-fab-pos'", "const FAB_POS_KEY = 'dsh-mobile-fab-pos-v2'")
h = h.replace("return { left: 10, top: 10 }", "return { left: 10, top: 999999 }")

const boot = `
window.__ModuleLoader__.load({
  id: 'dsh-deepharness',
  factory: (require) => {
    const hanui = createHanuiModule(require)
    const panel = createPanelModule(require)
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    // 移动端已改为原生 App 实现，不再注入移动布局壳（hanui）与 Web UI 适配样式；
    // 仅保留「手机连接」面板（配对码/二维码/设备管理）。
    function apply(ctx) {
      panel.apply(ctx)
    }
    const inject = [...new Set([...(hanui.inject ?? []), ...(panel.inject ?? [])])]

    exports.apply = apply
    exports.inject = inject
    return module.exports
  },
})
`

const out = header + h + "\n" + panel + "\n" + boot
writeFileSync(join(root, "src", "client.js"), out)
console.log(`client.js written: ${out.split("\n").length} lines`)
