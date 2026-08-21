# dsh-links-relay

自建远程中继（规划中）。用于在没有公网 IP 时，把手机安全接到本机 `dsh-links` 插件（默认 `18640`），而不是把 DSH Web 管理台裸暴露到公网。

当前状态：**占位**。正式实现前，这里只放实验笔记与 Cloudflare Tunnel 配置草稿；**不属于**当前公开 Beta 支持路径。

## 计划形态（草案）

- 电脑侧：本地进程对接 `https://127.0.0.1:18640`
- 远端：你自己的域名 / VPS / Tunnel
- 手机：App 手动添加 `https://你的域名` + 同一套配对码

## 本目录现有文件

| 文件 | 说明 |
|---|---|
| [`REMOTE-NOTES.md`](REMOTE-NOTES.md) | 早期 Cloudflare Tunnel 调研笔记（实验性） |
| [`cloudflared.yml.example`](cloudflared.yml.example) | cloudflared 配置模板草稿 |

正式 relay 代码落地后，再补协议、鉴权与安装说明。
