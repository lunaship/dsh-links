# Changelog

- Relay Control 支持维护者开通的管控租户：邀请与 Host 按 `user_id` 隔离。自托管仍是单个 `admin`；没有 App 登录、没有公开注册。会话继续只存在于插件本机。停用租户会立刻作废未用邀请、吊销其 Host，并让已有控制台会话失效；维护者可恢复登录（已吊销 Host 不自动回来）。托管租户默认最多 4 个未用邀请、8 台已接入 Host，避免共享 Relay 被单户占满。配额「已接入」是未吊销名额，插件断开不释放。租户控制台 Host 列表主表只显示占名额的电脑，已吊销折起。邀请主表是未用码和仍占名额的已接入记录，失效折起；清理不会删掉还能对照吊销电脑的已用码。租户概览大数显示已接入占名额与未用邀请（对照上限），不是含已吊销的历史总数，管理员台账列出各户用量，已接入或未用码满额的标「已满」并排到前面（`tenant list` 同理），维护者不开户内机器，对方自己登录去吊销或签发；台账可再复制登录地址（回环不外发）。台账「查看电脑」只列出该户 Host / 邀请 / 记录（类似按用户看节点），优先让对方自己登录吊销；SSH 用 `tenant hosts --login` 列出占名额电脑（`--all` 含已吊销，不含路由密钥）；未用邀请满额时不带 `replaceOldestUnused` 仍返回 409，控制台确认后会作废最早未用码再签发（未用名额仍不超过上限）。满额 ENROLL（接入码有效、名额已满）返回 `QUOTA_EXCEEDED`，插件提示到控制台吊销，而不是再签发接入码。满额不消耗该码，吊销后同一张码可再接入。已接入满额且仍有未用码时，控制台签发按钮改为「同一电脑换路由」，避免作废新电脑已贴的码。吊销腾出名额后，若仍有未用码则提示新电脑再点接入，而不是立刻再签发。租户控制台在已接入满额时显示横幅，离线 Host 标「占名额」，列表把离线占名额的排在前面、最久未见的标「建议吊销」，横幅可一键吊销那台；签发前确认这张码只适合同一电脑换新路由。控制台不再要求换 Relay 前先吊销。控制台操作记录（`GET /v1/events`）记下谁签发/吊销了邀请与 Host，以及控制台登录成败（不含接入码、密码、会话）。未知登录名的失败不写入，以免冲掉真实操作记录。维护者可用 `dsh-links-relay tenant` 在本机开通/停用/恢复租户。Host 列表显示插件本机主机名，并按 Agent `REGISTER` 与节流后的 `PING` 显示在线/离线（写入 `last_seen_at`）；插件断开后立即离线，不是 90 秒窗口里的假在线。插件「断开」暂停 Agent 并保留路由凭据，可重连，不是吊销。同一电脑贴新接入码会换新路由、不占额外名额；旧路由的 `REVOKED` 不会擦掉新凭据，接入失败会恢复原 Agent。换到别的 Relay 会先 409 确认（`confirmRelaySwitch`）；新接入成功后插件会对原 Relay 发 `REVOKE_SELF` 以空出名额，原 Relay 不可达时才提醒去原控制台吊销，若原接入串带有控制台地址则插件可打开「原控制台」（不是 App 登录）。新电脑贴带控制台地址的接入码却满额时，插件记住该公网地址，刷新后仍可打开控制台去吊销。更换或换 Relay 成功后插件提示同一网络下的手机下次打开即可跟上，纯远程请重新扫云端配对码，手机不登录控制台。云端配对码在插件已有有效路由时即显示，不必等 Agent 心跳在线；暂停或吊销后隐藏。换路由后云端码按路由戳刷新，避免扫到旧码。App `CONNECT` 在 MAC 通过后若 Host 已吊销或旧路由已被替换，返回 `REVOKED`（不是 `AUTH_FAILED`），手机清掉失效云端路由、保留局域网配对，并在设备列表留下「扫码恢复云端」（本机标记，不登录 Control）。设备列表健康探测不再把该错误当成暂时离线，也不再因此删除整台配对。`GET /dsh-link/mobile/bootstrap` 带当前 `relay` 快照（未接入为 `null`），同一网络下可换上新路由。插件暂停仍是可重试的 `AGENT_OFFLINE`。匿名日流量挂起返回可重试的 `RATE_LIMITED`，不是 `REVOKED`：插件保留路由凭据，手机保留配对，控制台显示「挂起（日流量）」，UTC 零点后自动恢复；邀请制租户不受日流量限制。控制台吊销 Host 后，插件把 `REVOKED` 当终态：停止 REGISTER 重连、清掉 route 凭据（保留本机 Host 密钥以便再接入），并提示到控制台签发新接入码。控制台「复制」优先给出可粘贴进插件的完整接入串（含主机）；裸邀请码只作为官方 Relay 的简写。吊销后在同一台自建 Relay 上再贴裸码，会回到已记住的主机，而不是误连官方。新开或被重置密码的租户必须先改成自己的控制台密码，才能签发或吊销；自建 `admin` 仍只用 config 密码。TLS 反代若对回环 Control 走 HTTP，可设 `admin_secure_cookies`，登录 Cookie 才会带 Secure；自建 `http://127.0.0.1` 保持关闭。Control 只在对端是回环时采信 `X-Forwarded-Proto` / `X-Forwarded-For`，这样租户经 HTTPS 反代能登录，登录限流也不会把所有人算成本机。租户可自己改控制台密码，管理员可重置；其它控制台会话会失效。租户可清理自己的失效邀请和已吊销 Host，不会动到别人；Host 列表用电脑名、Host ID 和接入时间区分同名电脑，邀请记录标出消费该码的电脑（吊销或删除 Host 后仍保留当时的电脑名），电脑仍占名额时可从该行吊销，操作记录带电脑名（仍不含接入码）。接入码默认 8 小时、最长 24 小时；控制台可选 30 分钟 / 2 小时 / 8 小时 / 24 小时，签发结果写出截止时间；超长 `ttl` 会被夹紧。满额 ENROLL 不消耗该码，并把短于默认时长的未用码续到 8 小时（不超过签发后 24 小时），方便吊销后再贴。开通租户后优先交出 config 的 `public_control_url`（指向回环 8080 的 HTTPS 反代）；未配置时才抄非回环 Origin。`127.0.0.1` 不会当成租户登录入口。配置了该地址时，接入串带 `c=`，插件记住并可「打开控制台」；手机 bootstrap / 云端码不含此字段。App 仍不登录 Control。插件「断开」仍只暂停；「释放名额」发送 DLR `REVOKE_SELF`，立刻空出控制台已接入名额（手机随后看到 `REVOKED`，云端字段失效、局域网配对仍在），不经过 App 登录。
- 历史把成功的 write/edit/str_replace_editor 投影为本轮产出文件；`GET .../sessions/:id/file?path=` 在工作区沙箱内读文件（上限 8MB）。bootstrap `capabilities.files.workspace` 声明该能力。
- 会话列表不再下发 JSON `null` 的 `agentPreset` / `cwd` 等可选字段。旧 App 的 `JSONObject.optString` 会把 `null` 显示成顶栏字面量「null」；缺键则回落默认预设。
- 兼容性：DSH 源码基线由 `0.1.2-alpha.5` 升至 `0.1.5-alpha.2`（npm `alpha`）。npm `latest` / `next` 现为 `0.1.2-rc.1`。详见 `docs/COMPATIBILITY.md`。
- 移动端同步合同写明：`GET .../requests` 快照须带 pending 澄清题目与审批元数据；App 历史刷新 / 重同步后不得丢掉仍 pending 的澄清与审批卡。
- Relay README 不再把已删除的旧私有仓写成「已归档」；race 测试命令与 CI 一致，不再把 `CGO_ENABLED=0` 和 `-race` 写在一起。
- CI 比对插件 `testdata/dlr1-vectors.json` 与同仓 `relay/testdata` 镜像；RC1 证据脚本的 Relay 命令与门禁对齐。
- README 截图标明仍来自 App `0.5.0-beta.14`；SECURITY 写明公开仓含 `relay/` 源码、npm 包不含。

## dsh-links 0.1.0-beta.14 — 2026-09-07

- 补发历史超过 500 事件时不再静默跳号：无法证明连续覆盖则发重同步信号，游标不越过缺口。
- 多题澄清改为逐题校验；旧 App 不声明多题能力时回落桌面，避免用第一题答案填其余题。
- 审批生命周期与 SSE 断开分离：短暂断线有限宽限，吊销/插件退出立即失效；重复提交返回已记录终态。
- 历史与实时流把 `approval/asked` 与 `approval/decided` 归并为同一请求状态。
- 配套合同见 `docs/MOBILE_SYNC_CONTRACT.md`。完整后台推送（ENH-01）仍为渠道待定，未实施。
- Relay 源码并入本仓库 `relay/`；Android 客户端仍为私有仓。
- Android 客户端需 `0.5.0-beta.16` 才能使用重同步与多题校验；旧组合不会静默丢事件，但也不能假装完全兼容。
- npm `beta` 自 `0.1.0-beta.12` 以来的下一发包；源码线上的 `0.1.0-beta.13`（DSH `0.1.2-alpha.5` 适配）未单独发包，本版一并包含。

## dsh-links 0.1.0-beta.13 — 2026-09-02

- 适配 DSH `0.1.2-alpha.5`：ApiProxy 移除后，本机调用改走 Typert Gateway（`session/list`、`session/page`、`session/modelCatalog`、`settings/describe|update`、`agentPresets/list`）；`session.history` 与 `workspace.list` 分别适配为 `session/page`（或 `session/follow` 快照）与 `workspace/follow` 基线。
- `session.prompt` 补上必填 `requestId`；审批从 `Session.snapshotEvents()` 反查 `approval/asked`；澄清卡改接 `user-questions/request` waterfall（不再依赖已删除的 `/api/events.mux` / `/api/respond`）。
- 客户端注入去掉已下线的 `@deepseek-ai/dsh-client-runtime`，改为 `dsh-client-ui-layout` + `dsh-client-ui-settings`。
- 轮询/补洞的 `session.history` 带 `maxMessages` 时走 `session/page`，不再每秒开一条 `session/follow`；仅手机打开无参尾页、或 list 没有 `asOfSeq` 时才 follow。澄清卡答题与审批一样要求该会话当前 SSE 订阅。peer `@deepseek-ai/cordis` 锁到 `^4.0.2`。
- 兼容性：DSH 基线由 `0.1.1-rc.2` 升至 `0.1.2-alpha.5`（npm `alpha`）。npm `latest` 仍为 `0.1.1-rc.2`。详见 `docs/COMPATIBILITY.md`。

## dsh-links 0.1.0-beta.12 — 2026-08-30

- 「手机连接」面板改为 Claude 风：暖象牙纸底 + 赤陶主色、衬线标题、下划线 Tab、设置/设备分组列表；暗色为暖炭黑（`prefers-color-scheme`）。
- 布局收紧：配对区横向（二维码左、配对码与复制右），内容列约 452px 居中，避免宽设置面板被拉散。仅动 JSX 与 STYLE，接口与后端逻辑无变更。
- 下线 `GET /dsh-link/mobile/balance`：`llm.balance` 并非 DSH 的 RPC 方法（rc.8 起即不存在），端点自上线即返回不可用；同从 `RPC_METHOD_ALLOWLIST` 移除。App 关于页余额入口已容错为不展示（`balance = null`），无需 App 改动；若未来要恢复，需实现真实的余额代查（如经插件读取 DeepSeek 平台 API）。
- 兼容性：DSH 基线由 `0.1.0-rc.8` 升至 `0.1.1-rc.2`（npm latest）。已在本机对 rc.2 完成冒烟：插件加载、`/dsh-link/*` 路由、`session.list` / `session.history` / `llm.models` / `workspace.list` / `settings.describe` RPC、`events.mux` WebSocket 帧与设置面板 slot 均正常；真机端到端（扫码、SSE 推送、审批）尚未在 rc.2 重跑，详见 `docs/COMPATIBILITY.md`。

## dsh-links 0.1.0-beta.11 — 2026-08-28

- 「手机连接」面板 UI 重设计：配色收敛为统一的青绿令牌体系（主色/危险/成功/警告各配柔和底色与边线），圆角统一为 8/12/18 三档。
- 面板质感升级：弹窗遮罩毛玻璃、三层投影、品牌头渐变链接图标；二维码卡片加高光与虚线装饰框，配对码下方补充扫码引导文案。
- 交互细节：待确认设备圆点呼吸动效（`prefers-reduced-motion` 下自动关闭）、主/次按钮与输入框完整 hover/active/focus/disabled 状态、「配对需本机确认」升级为卡片式开关、设备列表数量徽标、暴露警告加警示图标、弹窗支持 Esc 关闭。
- 逻辑、数据流与接口调用无变更；Android 客户端仍为 `0.5.0-beta.14`。

## dsh-links 0.1.0-beta.10 — 2026-08-27

- 工程：新增 push/PR CI——DLR/1 向量校验、`build:client` 产物一致性检查、单测、`pack` 试运行、生产依赖审计。
- 工程：新增 RC1 封测计划（T1–T6、停止条件、出口标准）、七层验收证据模板与三仓证据收集脚本。
- Android 客户端仍为 `0.5.0-beta.14`，本次无 App 变更。

## dsh-links 0.1.0-beta.9 — 2026-08-26

- 安全：配对码只留在进程内存，`state.json` 不再写入 salt/hash。旧版落盘哈希可离线穷举 6 位码；升级后重启即作废当前二维码，已配对设备不受影响。
- 安全：18640 HTTPS 明确最低 TLS 1.2；手机端 prompt 图片只接受 png/jpeg/webp/gif。
- Android 客户端 `0.5.0-beta.14`：Markdown 图片拒绝十进制/十六进制/短格式 IP；Relay HTTP/1.1 客户端拒绝请求行与头里的 CR/LF。

## dsh-links 0.1.0-beta.8 — 2026-08-26

- 官方 Relay 只需接入码即可接入：主机与 TLS 指纹预填，不再要求手抄指纹或粘贴 enroll URI；已接入主机重连会复用已存的 TLS pin。
- 「手机连接」面板改为二维码优先、文案更安静。
- 设备 token 同时接受 `Authorization: Bearer`；HMAC 密钥在启动时落盘，避免重启后换钥导致已配对设备失效。
- 手机新建会话优先传 `workspaceId`，不再只靠目录路径，避免会话进错网页工作区。
- Android 客户端 `0.5.0-beta.13`：侧栏按工作区成员关系分组；启动图标改为角色绘；工作区列表与主机登记对齐，过期配对可恢复。

## dsh-links 0.1.0-beta.7 — 2026-08-25

- 面板可开启「配对需本机确认」：扫码仍发 token，但 API 要等本机点批准才放行（兼容旧 App）。关闭该开关只影响此后新配对，已在等待的设备须逐台批准、拒绝或到期，不会被静默放行。
- 「手机连接」增加吊销全部设备，并展示 18640 监听地址与可达网段（非私有地址 / extraUrls 时红色警告）。
- 设备 token 哈希改为恒定时间比较；Relay 自签 TLS 必须提供完整 SHA-256 指纹，控制帧有长度与并发上限。
- 文档补充 Android 客户端须先验证书指纹再发送配对码；失败即中断，成功后钉扎。
- Android 客户端 `0.5.0-beta.12`：识别主机确认 pending，不在批准前进工作区；同步网页归档工作区、加大无障碍点击区域，并收紧连接失败处理。

## dsh-links 0.1.0-beta.6 — 2026-08-23

- 远端连接面板不再展示默认 Relay 端口（8444/8443）；只填主机名即可接入，自定义端口仍可用。
- Android 客户端仍为 `0.5.0-beta.11`，本次无 App 变更。

## dsh-links 0.1.0-beta.5 — 2026-08-22

- 安全加固：设备 token 落盘哈希由裸 SHA-256 换为每安装 HMAC-SHA256；旧哈希在下次认证时自动迁移，手机无需重新配对。
- 安全加固：面板二维码 URL 不再携带配对码（服务端本就不读该参数），配对码不再进入 web 访问日志；配对码轮换改为重挂载取图。
- 安全加固：本机 RPC 方法抽为 `RPC_METHOD_ALLOWLIST` 闭集并在 `callLocalRpc` 内发出请求前强制校验，新增单测锁定闭集与全部调用点，防止未来重构引入开放转发。
- 防御性收口：SSE 补历史期间 mux 排队事件加 2000 条上限，超限丢弃并保留强制轮询兜底。
- 文档：标明云端二维码内含 Relay 路由凭据（`routeSecret`）、与接入码同等敏感；补充手机端审批 5 分钟超时行为说明。
- Android 客户端 `0.5.0-beta.10`：更新启动图标。

## dsh-links 0.1.0-beta.4 — 2026-08-22

- 电脑插件可凭维护者发放的接入码接入 DSH Links Relay；接入成功后才显示单独的云端配对码。
- 局域网设备与云端设备分开列出、分开吊销。
- Android 客户端 `0.5.0-beta.9`：扫云端码走 Relay，与局域网入口并存。
- 公开文档标明 Relay 仍为内测；接入码、Relay 凭据和 `state.json` 不入库、不随 Release / npm 发布。

## dsh-links 0.1.0-beta.3 — 2026-08-22

- Web ↔ 手机实时同步：mux `session/event` 直推到手机 SSE，绕开 1s `session.history` 轮询与「文件未变化」短路。
- mux 桥优先 WebSocket（新版 apiproxy `/api/events.mux` 对 SSE GET 返回 426），失败回退 SSE；握手超时后自动重试。
- 连号快路径直推，跳号走强制补洞轮询；连接补历史完成前不接直推，避免乱序与游标跳号。

## dsh-links 0.1.0-beta.2 — 2026-08-21

- 手机 SSE 转发 Web 澄清卡（`ask_user_question` / mux `question/requested`），并支持 `/question` 回传答案。
- Android 客户端 `0.5.0-beta.2`：会话竞态、审批确认、澄清卡 UI 等修复。

## 0.5.0-beta.1 — 2026-08-21

Android App 局域网 Beta。

- 原生工作台：多主机配对、会话、SSE、审批、通知。
- Release 构建可过 R8；正式签名通过仓库外的环境变量配置。
- 最小权限：删除未使用的旧存储权限，相机为可选硬件。
- 备份与设备迁移排除 Token / HostStore。
- 关于页提供 MIT 与第三方声明入口。
- 产品口径限定为可信局域网；不把远程连接写成可用能力。
- 分发口径：插件 / 文档 MIT 开源；Android 客户端闭源，仅 GitHub Releases 正式签名 APK。

## dsh-links 0.1.0-beta.1 — 2026-08-21

- 局域网扫码配对、一次性配对码、设备吊销、18640 手机 API。
- 「手机连接」面板只保留局域网路径。
- npm metadata、`prepack` 生成校验与 Beta 版本号。
