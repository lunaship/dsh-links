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
    "requests": { "snapshot": true, "reconnectGraceMs": 30000 }
  }
}
```

旧 App 忽略未知字段。旧插件忽略 `caps` 查询参数。

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
- 实时流、重连快照、`GET .../requests`、提交响应走同一归并：终态不得被 pending 回滚。
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
