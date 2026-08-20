/**
 * 临时独立测试：不重启 dsh web，用假 ctx 驱动插件 host 面，
 * 代理目标指向当前真实运行的 dsh web（127.0.0.1:3080）。
 */
import { apply } from "./src/index.js"

const fakeWeb = {
  port: 3080,
  host: "127.0.0.1",
  register(route) {
    console.log(`[test] route registered: ${route.kind} ${route.path}`)
    return () => {}
  },
  tapIndex(transform) {
    console.log("[test] tapIndex registered")
    return () => {}
  },
}

const ctx = {
  get: (name) => (name === "webServer" ? fakeWeb : undefined),
  logger: {
    info: (...a) => console.log("[info]", ...a),
    warn: (...a) => console.log("[warn]", ...a),
    error: (...a) => console.log("[error]", ...a),
  },
  effect: (fn, label) => {
    console.log("[test] effect registered:", label)
    return fn ? undefined : undefined
  },
}

apply(ctx, {
  port: 18641,
  autoApprove: true,
  pairingTtlSeconds: 600,
  extraUrls: ["http://test-relay.example.com:18641"],
  stateDir: "/tmp/dsh-links-test",
  debug: true,
})

console.log("[test] plugin applied, proxy should listen on 18641")
setInterval(() => {}, 1 << 30) // keep alive
