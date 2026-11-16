# 任务16 验证记录：文本与临时跟踪指令

- 日期：2026-11-16
- 分支：feat/ticket-16-commands（本记录基于 d112e72 工作区；该提交已合入 integration/tickets-08-27 @ 2e9fbe2）
- 结论：**Status: resolved**。10 项验收标准全部通过；0x8202 停止跟踪形态（间隔 0）因 05 独立 fixture 无样例未核对，页面不暴露停止入口，标注 blocked；真实车载终端未执行，模拟终端复用共享 protocol codec。

## 环境

- Windows 11 Pro for Workstations 10.0.26200；JDK 21.0.9；Maven Wrapper（apache-maven-3.9.12）。
- Spring Boot 4.1.2、Flyway、JdbcTemplate；Testcontainers 2.0.5：MySQL 8.4、Redis 8.4、Ryuk 0.14.0（保持启用）。
- Playwright 1.58.1 chromium；pnpm 10.16.1；Vue 3.5、vue-router 5。
- Maven 与浏览器验证均通过全局互斥脚本 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行；surefire forkCount=1。
- 静态检查不占用 8080/9000/5173 端口，直接执行。
- 协议正确性依据 05 独立 fixture `protocol/src/test/resources/fixtures/jt808-2019.txt`，未以自生成样例判定标准正确。

## 已执行

### 1. CommandFlowTest（真实 TCP + Testcontainers，锁内）

命令：

```
with-test-lock.ps1 -Command '<worktree>\mvnw.cmd','-B','-pl','server','-am','-Dtest=CommandFlowTest','-Dsurefire.failIfNoSpecifiedTests=false','test'
```

- 时段：2026-11-16 10:55:23 测试启动，10:57:10 左右结束（Total time 01:47 min）。
- 结果：`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 80.66 s`；`BUILD SUCCESS`；锁脚本退出码 0。
- 该次重跑在 testCompile 之后执行，编译产物经字节检查含 `timeoutAt` 与 `SocketTimeoutException`，确认新增断言实际运行。
- Flyway 在 Testcontainers MySQL 中应用 V1、V2、V3、V4、V5、V6、V14 共 7 个 migration，最终版本 v14。
- 同日 10:44 的上一轮同组 11 项同样全通过（81.57 s），但其 testCompile 早于新增断言，仅作参考，不作为本记录依据。

逐测试覆盖：

| 测试 | 验证点 |
| --- | --- |
| `textCommandReachesTerminalAndAckCompletesWithEpochAndSerial` | 0x8300 下行经真实 TCP 到达；body 与 fixture `01 01 B2 E2 CA D4 CE C4 B1 BE` 完全一致；serial、epoch、terminal_id、vehicle_id 关联；0x0001 应答后 ACK_SUCCESS；ACK_SUCCESS 审计 1 次 |
| `trackingCommandUsesIndependentFixtureBody` | 0x8202 body 与 fixture `00 1E 00 00 01 2C` 一致；0x0001 应答后 ACK_SUCCESS |
| `textAndTrackingBoundsAreValidatedBeforeSubmission` | 101 汉字 → 400 COMMAND_TEXT_TOO_LONG；空文本、emoji → 400 COMMAND_TEXT_INVALID；interval 0/61、valid 3601 → 400 COMMAND_TRACKING_INVALID；100 汉字 = 200 GBK 字节通过；interval 60、valid 3600 通过 |
| `offlineTerminalIsRejectedWithoutQueueing` | 未连接终端 → 409 COMMAND_TERMINAL_OFFLINE；command_log 0 行；OFFLINE_REJECTED 审计 1 次（不排队） |
| `noAckTimesOutAfterThirtySecondsAndLateAckOnlyLeavesEvidence` | MutableClock 推进 31 s 后 TIMEOUT；timeoutAt = sendTime + 30 s；TIMEOUT 审计 1 次；TCP 500 ms 窗口内无第二下行帧（不自动重发）；迟到 0x0001 仅记 LATE_REPLY，状态仍 TIMEOUT，replyTime/replyResult 保持空 |
| `staleEpochReplyDoesNotCompleteCommandButNewEpochCan` | 旧连接应答记 STALE_EPOCH_REPLY，旧指令仍 SENT、replyTime 空；新 epoch 指令可正常完成 |
| `terminalRejectionMarksCommandFailed` | 终端应答 result != 0 → FAILED，记 TERMINAL_REJECTED 审计 |
| `logoutFailsInFlightCommand` | 合法 0x0003 注销使在途 SENT 指令收尾为 FAILED，记 LOGOUT_FAILED 审计 |
| `fleetLeaderCannotSendAndMonitorPassesDispatchPermission` | FLEET_LEADER 发送 → 403 FORBIDDEN 且记 DENIED 审计；MONITOR（无 fleetIds）具备 COMMAND_DISPATCH，离线时返回 409 而非 403，区分权限与连接状态 |
| `manualRetryCreatesNewRecordAndKeepsHistory` | 手工重试创建新 command_log 记录并带 retry_of_id，旧记录状态与应答历史不被改写 |
| `listShowsSubmissionAndCommunicationSeparatelyWithinScope` | 列表将提交字段与通信字段分开展示；按 FleetScope 过滤；timeoutAt 随通信结果返回 |

### 2. BrowserFlowTest + 独立 simulator CLI（真实浏览器、真实 TCP、Vite）

命令：

```
with-test-lock.ps1 -Command '<worktree>\mvnw.cmd','-B','-pl','server','-am','-Dvms.browser-tests=true','-Dtest=CommandFlowTest,BrowserFlowTest','-Dsurefire.failIfNoSpecifiedTests=false','test'
```

- 时段：2026-11-16 约 10:43:44—10:49:11（Total time 05:27 min）。
- 结果：CommandFlowTest 11/11；BrowserFlowTest 包装测试 1/1；Maven `BUILD SUCCESS`；Playwright `28 passed (2.5m)`，其中 `commands.spec.ts` 28.2 s 通过。
- 模拟器为 `simulator/src/main/java/com/vms/simulator/TerminalSimulator.java` 独立进程，复用 protocol 模块共享 codec；Playwright 收集其 stdout，实际收到并应答指令：

```
text: received type=1 flag=1 content=模拟文本指令
ack: responseId=0x8300 commandSerial=1
tracking: intervalSeconds=1 validSeconds=60
ack: responseId=0x8202 commandSerial=2
position: accepted simulation=true trackingIntervalSeconds=0
position: accepted simulation=true trackingIntervalSeconds=1
position: accepted simulation=true trackingIntervalSeconds=1
```

- 跟踪前默认上报间隔为 20 s、跟踪间隔为 0；提交临时跟踪（间隔 1 s、有效期 60 s）后，模拟终端按 `trackingIntervalSeconds=1` 连续上报，相邻两次事件间隔断言在 500—5000 ms 内，证明临时上报行为实际改变。
- 页面断言：最新提交区与表格同时展示提交结果与通信结果；文本与跟踪各有一条记录且通信结果独立更新；表格展示“间隔 1 秒 · 有效期 60 秒”。
- 截图：`16-commands-ack.png`（#1 文本、#2 临时跟踪，提交“已提交”、通信“终端应答成功”、应答 0x0001、serial 1/2、epoch 1；时间列展示发送与应答时刻）。
- 离线、超时、迟到与旧 epoch 无法由浏览器在合理时间内构造，均由第 1 项服务端测试构造；浏览器只验证在线应答路径。

### 3. TDD 红测

- `16-browser-red.log`：`commands.spec.ts` 对最新提交区摘要 `间隔 5 秒` 的断言失败（`1 failed / 27 passed`，`Error: expect(locator).toContainText(expected) failed`），`16-browser-red-maven.log` 对应 `Tests run: 1, Failures: 1` 与 `BUILD FAILURE`。
- 红测定位到页面最新提交区未展示指令摘要；实现后按最终交互改为最新区展示指令类型、表格行展示跟踪参数，绿测通过。
- 新增 timeoutAt 与不自动重发断言后，`16-test-run.log` 为锁内重跑绿测。

### 4. 静态检查（最终代码状态）

| 命令 | 结果 |
| --- | --- |
| `pnpm --dir web typecheck`（vue-tsc -b） | exit 0，12 s |
| `pnpm --dir web lint`（eslint .） | exit 0，21 s |
| `pnpm --dir web build`（vite build） | exit 0，12 s，`✓ built in 911ms` |

### 5. 测试环境回收

- 重跑结束后执行 `docker ps -a --filter name=testcontainers`，输出为空，无 Testcontainers/Ryuk 残留；未清理其他开发容器或数据库。
- `16-browser-green.log` 中有 `java.net.SocketException: Connection reset` 的 Netty 堆栈，为测试终端或浏览器关闭连接产生的客户端断连日志，测试结果为全通过，不影响结论。

## 协议核对与未核对项

- 0x8300：标志 1、类型 1、GBK“测试文本”的完整消息体与 05 fixture 一致；文本按真实 GBK 编码字节校验，上限 200 字节（100 汉字），另受单包容量约束。
- 0x8202：间隔 30 s、有效期 300 s 的消息体与 05 fixture 一致；间隔 1—60 s、有效期 0—3600 s，间隔 0 表示停止。
- 应答类型：0x0001 终端通用应答；command_log 记录 reply_message_id、reply_result、reply_time，并保留 serial_no、connection_epoch。
- **未核对（blocked）**：0x8202 停止跟踪形态（间隔 0、有效期 0）的标准字节布局，05 fixture 无该样例。页面未暴露停止入口，未以自生成样例判定，待补充独立 fixture 后再实现。
- 超时由 MutableClock 推进 31 s 构造，非真实等待 30 s；TIMEOUT 由每秒扫描 worker 落库，测试已验证其只落状态、不重发。
- 页面文案只表述提交与通信结果；超时与失败未表述为司机已读或已执行。

## 逐项 AC 状态

| Acceptance criterion | 状态 | 依据 |
| --- | --- | --- |
| 页面发起文本与跟踪，模拟终端经真实 TCP 接收并应答 | 通过 | BrowserFlowTest + simulator：`text: received ...`、`tracking: intervalSeconds=1 validSeconds=60`、`ack: responseId=0x8300/0x8202`；页面通信结果“终端应答成功” |
| 超过 100 汉字或 GBK 字节超限的文本被拒 | 通过 | 101 汉字 → COMMAND_TEXT_TOO_LONG；emoji/空 → COMMAND_TEXT_INVALID；100 汉字 = 200 字节通过 |
| 跟踪间隔 1—60 秒、有效期最多 3600 秒校验 | 通过 | interval 0/61、valid 3601 拒绝；interval 60、valid 3600 通过 |
| 状态区分 SUBMITTED/SENT/ACK_SUCCESS/FAILED/TIMEOUT | 通过 | 各测试分别断言五态；页面标签映射一致 |
| 30 秒超时不自动重发，手工重试建新记录 | 通过 | clock +31 s → TIMEOUT，timeoutAt=sendTime+30 s；500 ms 窗口无第二帧；retry 记录带 retry_of_id 且旧记录不变 |
| 离线终端拒绝发送，不排队 | 通过 | 409 COMMAND_TERMINAL_OFFLINE；command_log 0 行；OFFLINE_REJECTED 审计 |
| 旧 epoch 或迟到应答留痕，不改写历史 | 通过 | STALE_EPOCH_REPLY、LATE_REPLY 审计；状态、replyTime、replyResult 不被改写 |
| 合法注销时在途指令 FAILED 收尾 | 通过 | `logoutFailsInFlightCommand`：FAILED + LOGOUT_FAILED 审计 |
| 车队长发送被拒，操作写入审计 | 通过 | FLEET_LEADER 403 + DENIED 审计；MONITOR 可下达（离线 409） |
| 模拟终端临时上报行为实际改变 | 通过 | 跟踪后连续两条 `trackingIntervalSeconds=1` 位置事件，间隔 500—5000 ms；跟踪前记录为 0 |

## 未执行

- 真实车载终端接入：未执行；本轮终端为复用共享 codec 的 simulator CLI，真实设备行为待现场验证。
- 0x8202 停止跟踪标准形态：blocked，见上节；页面不提供入口。
- 真实 30 秒等待与真实网络故障：未执行；超时用 MutableClock 构造，离线用未连接状态构造。
- 真实天地图与真实车辆位置不属本票验证范围，见任务26。

## 证据索引

- `.scratch/vehicle-monitoring/evidence/16-browser-red.log`：TDD 红测 Playwright 明细（commands.spec.ts 1 项失败）。
- `.scratch/vehicle-monitoring/evidence/16-browser-red-maven.log`：红测 Maven 输出（BUILD FAILURE）。
- `.scratch/vehicle-monitoring/evidence/16-browser-green.log`：CommandFlowTest + BrowserFlowTest 最终全绿 Maven 输出。
- `.scratch/vehicle-monitoring/evidence/16-playwright-green.log`：Playwright 28 passed 与 simulator 实际收发输出。
- `.scratch/vehicle-monitoring/evidence/16-test-run.log`：含 timeoutAt 与不自动重发新断言的锁内 CommandFlowTest 重跑（11/11）。
- `.scratch/vehicle-monitoring/evidence/16-typecheck.log`、`16-lint.log`、`16-build.log`：静态检查与构建。
- `.scratch/vehicle-monitoring/evidence/16-commands-ack.png`：页面提交与通信结果分离展示截图。
