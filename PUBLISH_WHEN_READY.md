# 公开前核对（等你确认后再执行）

当前 GitHub `lunaship/dsh-links` 仍应保持 **private**。

本地总目录三个包：

```
app/                 # Android（闭源，勿推进将公开的 remote）
dsh-links/           # 插件（可公开）
dsh-links-relay/     # 中继占位（可公开笔记；实现后另议）
```

| 分支 | 用途 |
|---|---|
| `main` | 公开树：插件 + relay 占位 + 文档（无 App 源码） |
| `private/android-full` | 本机完整树（含 `app/`） |

## 你确认「确认公开」后才会执行

1. 按公开树更新并 `git push -u origin main --force`（如需要）
2. 确认 `origin` 上没有 `private/android-full`、没有 `app/` 源码
3. `gh repo edit lunaship/dsh-links --visibility public`
4. （可选）Releases 上传正式签名 APK

回复 **确认公开** 后再继续。
