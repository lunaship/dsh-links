# RC1 版本与验收证据记录

每次 RC1 验收先在三仓各自工作树锁定 revision，再执行
`node scripts/collect-rc1-evidence.mjs`。生成的 JSON 是一次性证据，不应提交到
长期兼容矩阵；矩阵只记录版本、发布状态和验证范围，不写 moving `main` hash
或 ahead count。

## 记录规则

- 记录三仓完整 commit SHA、分支/标签、工作树是否干净、DSH 实际版本、APK
  版本与 SHA-256；不要只写 `main`。
- 记录每条命令、开始/结束时间、退出码、测试计数和原始输出保存位置。
- 单元测试、主机集成、真实 Android→Relay→Plugin、真实公网 CA/TLS、24h
  soak、容量测量是六个独立层；任一层没有执行就写“未验证”。
- 证据文件不得含 Token、`routeSecret`、邀请码、私钥、明文凭据或消息正文。

## 本次记录

```text
记录编号：RC1-____
生成时间（UTC）：
DSH 版本：
Plugin revision / version：
Android revision / version / APK SHA-256：
Relay revision：
工作树状态：clean / dirty（dirty 时列出但不覆盖的文件）
```

| 验收层 | 命令/步骤 | 结果 | 证据文件 | 边界 |
|---|---|---|---|---|
| 插件单元/主机测试 | `pnpm test` | PASS/FAIL | | 不是 Android E2E |
| Android JVM/lint/build | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | PASS/FAIL | | 不代表真机网络 |
| Relay race/vet/build | `CGO_ENABLED=0 go test ./... -race ...` | PASS/FAIL | | 不代表公网运行 |
| Android→Relay→Plugin | 真机、固定 revision、真实请求 | PASS/FAIL/未验证 | | 必须单独记录 |
| 公网 CA/DNS/TLS | 真实域名和证书检查 | PASS/FAIL/未验证 | | 本地自签不等价 |
| 24h soak | `deploy/soak-single-instance.sh` | PASS/FAIL/未验证 | | 未满 24h 不得标 PASS |
| 容量/阈值 | `deploy/collect-capacity.sh` | 实测值/未验证 | | 不把目标数字当测量 |

## 结论

```text
已验证：
未验证：
阻塞/残余风险：
是否满足 RC1 封闭 Beta 退出标准：是 / 否 / 未决定
复核人：
```
