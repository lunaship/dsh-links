# 移动端同步与请求状态合同

业务层协议，独立于 DLR/1。内层证书指纹、设备 Token 和邀请制不因本文件升级改变。

当前插件 `protocol` 为 `2`。App 在建流时发送 `caps=sync2,multiQuestion,requestState`。

## 能力协商

`GET /dsh-link/mobile/bootstrap` 增加：

```json
{
  "protocol": 2,
  "capabilities": {
    "sync": { "resync": true, "catchupIntegrity": true },
    "questions": { "multi": true, "serverValidation": true },
    "requests": { "snapshot": true, "reconnectGraceMs": 30000 },
    "files": { "workspace": true, "maxBytes": 8388608 }
  },
  "archivedSessionIds": ["<session-id>"]
}
```

旧 App 忽略未知字段。旧插件忽略 `caps` 查询参数。

`archivedSessionIds` 与 Web 的工作区归档集合保持一致；Web 恢复会话后，该 id 也必须从 App 的归档集合移除。App 的本机恢复仅是用户明确选择的临时覆盖，不能把服务端新归档的会话重新带回侧边栏。`sessions` 仍保留完整会话行，供设置页恢复；App 在冷启动选择会话前先应用该集合，因此已在 Web 删除的会话不会短暂出现在 App 侧边栏或被自动选中。该集合是快照字段，不代表底层会话日志已被物理删除。

`GET /dsh-link/mobile/sessions` 也返回同名 `archivedSessionIds`。App 的后台会话刷新必须先应用该集合，再更新列表和当前选择，避免列表请求与工作区请求之间产生短暂不一致。

`GET /dsh-link/mobile/sessions/search` 同样遵守该集合：成功搜索和降级的标题搜索都不会返回 Web 已归档的 `sessionId`。

产出文件：历史投影可含 `role: "produced_files"` 与 `files` 路径列表。具备 `capabilities.files.workspace` 时，`GET /dsh-link/mobile/sessions/:id/file?path=` 在该会话 cwd 沙箱内返回原始字节（默认上限 8MB）。路径越出工作区返回 403。旧 App 忽略未知 role，仍可走工具结果文本。

## 云端路由快照

`GET /dsh-link/mobile/bootstrap` 在设备 token 鉴权后附带当前插件 Relay 路由（与扫码 `pair-info?via=relay` 同形）：

```json
{
  "relay": {
    "v": 2,
    "client": "relay.example:8443",
    "routeId": "<b64u>",
    "routeSecret": "<b64u>",
    "tlsFingerprint": "<optional sha256 hex>"
  }
}
```

未接入、已吊销或已「释放名额」时下发 `"relay": null`，App 清掉本机云端字段并保留局域网配对。旧插件不下发该键，App 不得因此擦掉已存路由。旧 App 忽略未知字段。该快照不含 Control 登录地址；接入串里的 `c=` 只给电脑插件，Android App 不登录 Control。

插件更换 Relay / 释放名额后，旧 `routeId` 的 CONNECT 在 MAC 通过后返回 DLR `REVOKED`。App 不得把整台配对删掉：同一网络仍可用设备 token；恢复云端需重新扫码（纯远程手机在下次进局域网或重扫之前拿不到新路由）。App 在本机记下「需扫码恢复云端」，设备列表保留扫码入口，不登录 Control。插件在已有有效路由时即显示云端配对码，不必等 Agent 心跳在线；暂停或吊销后隐藏。换路由后按非密钥路由戳刷新该码，避免扫到旧路由。该码不含 Control 登录地址。

| 组合 | 行为 |
| --- | --- |
| 新 App / 新插件 | 补发不完整时发 `resync-required`，App 拉快照后从快照游标继续 |
| 旧 App / 新插件 | 不发送截断尾部，不推进游标越过缺口；发 `error`（`upgradeRequired`），连接保持，避免重连风暴 |
| 新 App / 旧插件 | 无重同步事件；App 不假装已具备完整恢复。需两端一起升级 |

禁止靠无限断开重连修补缺口。

## 快照与增量

- 历史 REST 是快照；SSE `message` 是增量。
- `loadEventsAfter` 只有在 `afterSeq+1` 到已收集尾部连续可证时才交付该批次。
- 达到 10 页 / 500 事件、页内截断、日志缺口或 seed 队列溢出时，`complete=false`，不得把尾部 701…1200 当作 afterSeq=100 的连续补发。
- 新 SSE 事件 `resync-required`：`{ sessionId, reason, afterSeq, oldestAvailableSeq, nextCursor }`。
- App 丢弃本轮在途增量中不可证的分页缓存，保留输入草稿；用会话 generation 丢弃迟到结果。
- 提交游标（已应用）与接收游标分开；重连 `afterSeq` 使用已提交游标。

## 请求状态

状态：`pending` / `resolved` / `cancelled` / `expired` / `unknown`。

DSH 结果映射：`allowed-once`/`rejected` → `resolved`；`cancelled` → `cancelled`；`unavailable` → `expired`。

- 同页 `approval/asked` 与 `approval/decided` 投影为一张终态卡。
- 同 `approvalId` 的审批卡、同 `rpcId` 的澄清卡在客户端各归并为一张；终态不得被 pending 回滚。
- 实时流、重连快照、`GET .../requests`、提交响应走同一归并：终态不得被 pending 回滚。
- `GET .../requests` 的 pending 澄清带原始 `questions` 数组，pending 审批带 `toolName` / `callId`；空白 id 忽略。
- 澄清卡不进 history 投影。App 历史刷新与 `resync-required` 后必须保留仍 pending 的 `question` / `approval` 气泡，并用快照补回进程重启或列表被清空后缺失的 pending 卡。
- 审批重复提交若已有终态，返回 `alreadySettled` 且不再次 settle。
- SSE 断开后有不超过 30 秒且不超过原审批剩余期限的重连宽限；重连不重置 5 分钟总超时。
- 设备吊销、DSH abort、插件退出立即结束，不能借宽限恢复权限。
- 插件重启后内存回调不可恢复，未决请求失效。
- 澄清多题：旧 App 不声明 `multiQuestion` 时回落桌面，不在手机上构造未展示题目的答案。

## 错误与重试

- 校验失败（未知题目 ID、无效选项、缺必填、超长）返回 400，请求保持可处理。
- 已结束且无终态缓存：409。
- 非当前会话 / 非授权设备：403/409。
- 非幂等 prompt 不自动重放。

## 后台通知

完整后台推送（ENH-01）渠道待定，未实施。现有通知仍只覆盖 App 进程收到当前会话 SSE 之后的本地提醒。
