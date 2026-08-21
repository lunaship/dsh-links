# 公开前核对（等你确认后再执行）

当前 GitHub `lunaship/dsh-links` 仍应保持 **private**。本地已拆好：

| 分支 | 用途 | 是否可进公开仓 |
|---|---|---|
| `main` | 插件 + 文档 + branding（无 App 源码、无 App 历史） | 是 |
| `private/android-full` | 完整 Android 工程 + 近期 WIP | **否**（仅本机，勿推到将公开的 remote） |
| `archive/pre-public-main` | 拆分前旧 main（含 App 历史） | 否 |

## 你确认「确认公开」后才会执行

1. `git push -u origin main --force`（orphan 历史，必须 force）
2. 确认 `origin` 上没有 `private/android-full`
3. `gh repo edit lunaship/dsh-links --visibility public`
4. （可选）GitHub Releases 上传正式签名 APK

## 公开前请确认

- [ ] 接受先开源插件 / 或 Release APK 已就绪
- [ ] 同意对 `main` force-push
- [ ] 同意 `private/android-full` 不推到公开 GitHub
- [ ] 已读 README / LICENSE 混合开源口径

回复 **确认公开** 后再继续。在此之前不会推送，也不会改仓库可见性。
