# 17: 实时订阅撤权与重连校正

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 07

## What to build

在 07 交付的 WS 基础上：握手使用登录后取得的短时一次性 ticket，ticket 与 token 不出现在日志；订阅与输出按最新权限过滤。停用、改密、新登录替换、撤权、到期后旧订阅最迟 5 秒停止推送并退出或重建。帧包含事件标识、聚合对象、version、server_time，旧版本不覆盖新状态。重连恢复订阅后再次查询快照与报警列表，补齐初始查询至订阅之间的窗口。慢客户端有界缓冲，超限断开并显示连接状态，不无限堆积。位置每车最多 2 秒合并为最新点，合并窗口内旧点不覆盖新点。初始快照由 REST 查询提供，WS 只推送变化；页面显示已连接、断线、重连中与数据陈旧状态，不以缓存恢复的旧位置标记在线。撤权与重连路径保留必要审计记录，不记录 ticket 原文。握手鉴权完成前不进入订阅列表，失败握手不建立会话。不重做 07 基础订阅；13、27 完成后复验报警列表补齐。本票自带测试。

## Spec coverage

主：AC03 WS 部分、AC29（US 9、42、43），见 [spec](../spec.md#可检查验收场景)。
复验：AC02 的 WS 订阅部分（HTTP 部分归 02）；位置合并规则在 [18](18-outbox-retries-and-cache-recovery.md) 深化。报警列表补齐依赖 [13](13-alarm-handling-and-history.md) 与 27 汇总复验。

## Acceptance criteria

- [ ] WS 握手使用一次性 ticket，ticket/token 不出现在日志
- [ ] 停用、改密、新登录、撤权或到期后旧订阅 5 秒内停止推送
- [ ] 帧含事件标识、version、server_time，旧版本不覆盖新状态
- [ ] 重连后重新查询快照，窗口内变化补齐；报警列表尚未提供时用受控变更验证通用校正机制
- [ ] 慢客户端有界缓冲，超限断开且连接状态可见
- [ ] 位置每车最多 2 秒合并推送
- [ ] 车队长跨队订阅被过滤，不泄露出授权范围数据
- [ ] 本票记录报警列表在 13/27 的后续复验项；未交付报警功能不阻塞本票现有位置通道验收

## Scope boundaries

不做 HTTP 权限整体复验（02）、不做 outbox 重试深化与 Redis 恢复（18）、不承诺每浏览器恰好一次投递；不改 07 的位置事务。后票不代本票测试。

## Validation

以真实浏览器与 WS 客户端验证订阅、撤权、重连与慢客户端；以真实 HTTP 触发权限变化；记录 ticket 日志检索结果、帧版本行为、断开与补齐时间。记录命令、环境、版本、时段、期望与实际，逐条标注状态。

## Comments

（暂无）

## Answer

已完成实时订阅撤权与重连校正，并合入 `integration/tickets-08-27` 后复跑通过。

### 实现

- 后端 `MonitorWebSocketHandler`：握手鉴权通过后才进入订阅列表；每秒重新 `auth.requireToken` 与 `requirePermission(FLEET_VIEW)`，停用、改密、新登录替换、撤权、到期均以 close code 1008 / `SUBSCRIPTION_REVOKED` 关闭；慢客户端使用有界 `ArrayBlockingQueue`（默认 512，测试配 8）+ 虚拟线程 sender，超限以 1011 `SESSION_NOT_RELIABLE` 关闭并记 WARN；每帧重新鉴权并按当前车队范围过滤；审计 `WS_SUBSCRIBE` / `WS_SUBSCRIPTION_REVOKED`，detail 仅 `sessionId=...;reason=...`，不含 ticket/token。
- 前端 `useMonitorStream`：新增 `disconnected` / `stopped` 状态与“数据陈旧”；1008 后停止自动重连、清空车辆列表并显示失效提示；新增 `revoked` 守卫并 abort 在途快照，撤权后迟到快照不得回填；重连成功后重新查询快照；旧 `position.version` 帧丢弃。
- `HomeView.vue` 增加 `data-testid="monitor-data-stale"`。
- 新增 `MonitorWebSocketSubscriptionTest`（6 项）、`MonitorWebSocketExpiryTest`（1 项）、`web/e2e/monitoring-stream.spec.ts`（3 项）、`web/playwright.mock.config.ts`；未删除既有用例。

### 命令与结果

- 合并前后端订阅/到期：`mvnw.cmd -B -pl server -am -Dtest=MonitorWebSocketSubscriptionTest,MonitorWebSocketExpiryTest -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 7, Failures: 0, Errors: 0`。
- 合并后后端：`mvnw.cmd -B -pl server -am -Dtest=MonitorWebSocketSubscriptionTest,MonitorWebSocketExpiryTest,TelemetryFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 17, Failures: 0, Errors: 0`（含 07 一次性 ticket 与位置合并用例）。
- 合并后前端 mock e2e（`web` 目录、锁内）：`.\node_modules\.bin\playwright.cmd test -c playwright.mock.config.ts --reporter=list` → `3 passed`。
- 合并后真实浏览器：先 `mvnw.cmd -B -pl simulator -am -DskipTests package`，再 `mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` → BrowserFlowTest 1/1，内层 Playwright `31 passed`（含真实 WS 的 `monitoring.spec.ts` 与本票 3 条用例）。首次执行因 worktree 未预编译 simulator 导致 2 条既有用例无法启动 `TerminalSimulator`，预编译后复跑通过，失败日志保留。
- `pnpm --dir web typecheck`、`lint`、`build` 均 exit 0。
- 合并：`git merge integration/tickets-08-27` fast-forward 至 `6987d1b`，无冲突；ticket 26 基线（`basemap.ts`、`monitor-map-attribution`、TileMatrix 1..18、tianditu fixture 与环境变量）保留。
- 容器：Ryuk 0.14.0 全程启用，复跑结束 `docker ps` 无本轮 Testcontainers 容器残留；未清理其他 ticket/项目容器。

### 证据

`.scratch/vehicle-monitoring/evidence/17-verification.md`（逐条 AC 状态与说明）、`17-*.log`（含 `17-postmerge-backend.log`、`17-postmerge-web-stream.log`、`17-postmerge-browser.log`、`17-postmerge-playwright.log`、`17-simulator-build.log` 及首次失败记录）。

### 后续复验

- 13/27 交付报警列表后，复验首次快照至订阅之间、断线重连期间报警新增/处置的补齐；本票以位置快照受控变化验证同一通用校正机制。
- 8 小时绝对到期用 `sa-token.timeout=4` 缩短验证同一 `requireToken` 语义，未等待生产时长。
- 慢客户端为真实 TCP 不读帧客户端，不是真实网络拥塞；以服务器 WARN 与订阅移除作为断开证据。
