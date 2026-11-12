# 07: 可靠位置上报到实时地图

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 06

## What to build

实现 0x0200 位置上报与实时地图最小闭环：Flyway 建立 track_point 日分区初始表、vehicle_last_position 快照、必要 vehicle_monitor_state 与最小 notification_outbox。一个数据库事务内保存位置或异常、单调有效最新快照、必要判定状态与待通知记录，提交后才回成功 ACK，随后刷新 Redis 缓存并经 WS 推送。位置 WS 最多每 2 秒合并为每车最新点；首次建立的 WS 订阅即按账号角色与车队授权过滤，REST 快照与列表同口径，从第一次发布起就不存在不含权限的临时推送。最小 outbox worker 处理待通知，只清理其已读版本。提供监控布局首页：OpenLayers 叠加天地图 WMTS（地址、图层、key 由配置）与车辆点、列表、最后更新时间、速度方向、ACC 与定位状态；底图失败显示错误并保留列表。DB 失败不回成功；Redis/WS 故障不回滚已提交事实、不拖住 ACK。事务边界须允许后续报警/围栏状态在同一事务接入。

## Spec coverage

- 主：[AC09](../spec.md)；US27、US37、US38、US39、US41、US42、US43（首次即权限）、US44。
- 次：AC15 基础（缓存/WS 故障不回滚、快照可重建、通知可重试）；AC29 的初次安全与旧版本不覆盖基础。
- 复验：14/15 的报警/围栏状态必须接入本票同一事务，16 指令、18 重连与故障恢复深化均不重做本票推送通道；真实天地图 key/CRS/attribution 属 26，本票只按配置接入并保留 AC30 未执行状态。

## Acceptance criteria

- [ ] 正常 0x0200 在事务提交后才回成功 ACK；DB 失败无成功 ACK、无正常提交
- [ ] 位置、最新快照、必要判定状态与 outbox 记录在同一事务提交
- [ ] 提交后缓存刷新，WS ≤2 秒合并推送每车最新点
- [ ] Redis/WS 不可用不改变已提交事实、不拖住 ACK，恢复后快照可重建
- [ ] 首次 WS 订阅与 REST 列表即按角色/车队过滤
- [ ] 页面显示车辆点、列表、速度/方向/ACC/定位状态与最后更新时间
- [ ] 日分区表与 vehicle_id+gps_time 查询索引由 Flyway 建立
- [ ] 底图失败显示错误并保留可用列表

## Scope boundaries

- 不做幂等冲突、时间容差与无效定位（08）；不做 0704 与补传归属（09）；不做报警判定（14 等）、围栏（15）、轨迹回放（10、11）。
- 首次 WS 快照与订阅间隙、重连补齐、慢客户端、撤权深化按 17/18；本票保持安全与基本推送，17/18 不重做通道，本票也不替代其验收。
- 报警/围栏能力尚未开发不阻塞本票；本票只预留同事务接入点，不实现其业务规则。
- 不替代 06 的接入测试与 05 的独立 fixture；真实底图验证不在本票完成。

## Validation

- 真实 TCP 发送 0x0200；暂停或停止 MySQL 制造失败；使用真实 Redis 与 WS 客户端；浏览器加载配置的 WMTS 并记录网络条件。
- 核对事务与 ACK 先后、WS 延迟与合并、授权过滤、缓存故障时的提交事实；记录命令、环境、结果与证据位置，不得预填通过。

## Comments

（暂无讨论）

## Answer

已完成：

- 新增 Flyway V6：`track_point` 日分区（当前日期前 29 天、今天及未来 7 天）、`vehicle_last_position`、`vehicle_monitor_state`、`notification_outbox`；建立 `(terminal_id, gps_time)` 唯一键和 `(vehicle_id, gps_time)` 查询索引。
- 接入真实 `0x0200`：EventLoop 只分帧并记录 `received_at`，有界业务执行器调用 Spring `@Transactional` 服务；同一事务写位置、单调快照、判定状态、outbox 和终端通信时间，事务完成后才回 `0x8001` 成功 ACK。
- 新增 `/api/v1/monitor/snapshot` 和一次性短期 `/api/v1/auth/ws-ticket`；REST、首次 WS 握手和每次 WS 输出均复用当前角色/车队范围，车队长不能读取未授权车队。
- 新增 `/ws/monitor` 位置推送；worker 按每车最新版本合并，`DONE/RETRY` 更新带 `event_id + version` 条件，避免旧 worker 清除新版本；Redis 缓存可从 MySQL 快照重建，Redis/WS 故障不影响已提交位置和 TCP ACK。
- 新增监控首页：车辆筛选、通信/定位新鲜度、速度/方向/ACC/定位状态、WGS84 位置点、OpenLayers WMTS 配置与底图失败降级；新增真实 TCP simulator `--positions` 等位置场景参数。

验证证据：

- `mvnw.cmd -B -pl server -am '-Dtest=TerminalTcpIntegrationTest,TelemetryFlowTest,SchemaUpgradeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：24 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS；review 修复后覆盖任务 06 TCP 回归、任务 07 事务/授权/缓存场景。
- review 后修复：outbox 第 10 次失败转 `DEAD`；TCP 业务改为按 `terminal_no` 串行且有界的 dispatcher，跨连接仍保持终端级顺序。
- `mvnw.cmd -B -pl server -am '-Dvms.browser-tests=true' '-Dtest=BrowserFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：17 项 Chromium 全部通过（原有 16 项 + 监控场景），review 修复后的真实 TCP/WS/browser 回归；截图 `web/test-results/07-live-monitor-wmts-fixture-failure.png` 验证 WMTS 失败仍保留列表。
- `pnpm --dir web typecheck`：通过。
- `pnpm --dir web lint`：通过。
- `pnpm --dir web build`：通过（Vite 299 modules）。
- `./mvnw.cmd -B -pl server -am -DskipTests compile`：通过。
- 模拟器独立 JDK 21 `javac` 编译及 fixture 验证：通过；未把 fixture 结果伪称为真实后端验收。
- 真实天地图 key、网络、条款和 CRS 对齐未执行，按任务 26 保留；幂等冲突、补传归属、撤权深化和通知故障深化留给 08/09/17/18。
