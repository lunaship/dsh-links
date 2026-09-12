# dsh-links — AI 协作规则

DSH 手机插件（npm 包）：局域网配对 + 设备管理 + 18640 接入代理 + 手机 API。Relay 源码在 `relay/`，随本仓一起发版。

## 红线

- 插件 state 默认全局共享（`~/.dsh/dsh-links/state.json`，不分 profile）。任何冒烟/联调必须用 `stateDir` 配置隔离（经 `--patch` 的 `- id: dsh-links, config: {stateDir: ...}` 覆盖），且不得调用设备吊销类操作——2026-09-12 曾因用全局 state 冒烟，teardown 吊销了用户两台真机的配对。
- 用户的 DSH host 若以 `link:` 方式加载本仓，改完源码必须重启 host 才生效；重启前确认没有并行会话正在该 host 上工作。

## 门禁命令

- 插件：`npm run prepack`（= build-client + 全量测试）；单跑测试 `npm test`。
- Relay（在 `relay/` 目录下）：`gofmt -l . && go vet ./... && go build ./...`。

## 深入文档

| 主题 | 文件 |
|---|---|
| 手机同步契约（字段级，改手机 API 必读） | `docs/MOBILE_SYNC_CONTRACT.md` |
| 兼容矩阵 / DSH 基线 / 冒烟隔离警告 | `docs/COMPATIBILITY.md` |
| 发布流程与 dist-tag 策略 | `PUBLISH_WHEN_READY.md` |
| RC1 内测计划与证据模板 | `docs/RC1_CLOSED_BETA_TEST_PLAN.md` |
