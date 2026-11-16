# 09: 断线补传与历史归属

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 08

## What to build

实现 0x0704 批量位置与历史归属：先验证全包结构、项数（≤20）与体长（≤1023），再在一个事务中处理；成功表示每项正常保存、幂等存在或异常已保存，结构或数据库失败不形成可见部分成功、不回成功。CLI 增加断线重连与补传场景，覆盖换绑后补传旧时刻位置：按 gps_time 归属旧车辆与旧车队，写入 fleet_id_at_event；旧车队不因补传重获读取权限，新队不能读转入前历史；无法唯一归属写异常，不用接收时的当前关系推测。补传只补轨迹，不回退最新位置、不改变围栏或当前报警状态。报警关联当时司机的快照与历史报警权限由 12/13 复验，本票不新增司机档案依赖。

## Spec coverage

- 主：[AC06](../spec.md) 车辆/车队部分；AC12；US28、US30、US33、US34。
- 次：AC13 的历史补传部分；US17 换绑的历史区间；US22 转队历史。
- 复验：司机快照与报警历史详情由 12/13 复验；定位异常报警由 14；本票不实现报警，也不替代 08 的单点幂等/冲突测试。

## Acceptance criteria

- [x] 0x0704 先整包验证结构/项数/长度，再单事务处理
- [x] 混合新点、重复、冲突的成功包全部可解释，重复不新增
- [x] 结构或中途数据库失败不部分提交、不回成功
- [x] CLI 断线重连后补传旧时刻位置，归属旧车辆与旧车队
- [x] 换绑后新队不可读转入前历史；旧队不因补传获得新位置权限
- [x] 无法唯一归属写异常，不按当前关系推测
- [x] 补传不回退最新位置与实时状态

## Scope boundaries

- 不做报警、处置、围栏（后续票）；不实现 0704 之外的批量或其他标准消息。
- 按分配保持依赖为 08，不新增对 04 的依赖；司机与报警关联在 12/13 复验。
- 不替代 08 的幂等/冲突测试，也不替代 06 的注册鉴权测试。

## Validation

- 真实 TCP 经 CLI 构造 0704 混合包、断线与换绑补传；SQL 核对事务与 fleet_id_at_event；HTTP 以三角色核对历史读取权限；浏览器核对位置不回退。
- 记录命令、环境、结果与证据位置；状态不得预填。

## Comments

（暂无讨论）

## Answer

实现：
- `PositionIngestService.acceptBatch` 先 `PositionBatch.parse` 整包校验（≤20 项、body ≤1023、声明项数与实际一致），再在单个 `@Transactional` 中逐项落库；任一项失败整批回滚，`TerminalFrameHandler` 关闭连接且不发 0x8001。
- `resolveOwnershipAt` 按 `gps_time` 在 `terminal_vehicle_binding` 与 `vehicle_fleet_history` 的唯一有效区间内归属车辆/车队，车辆须 `ENABLED`；无法唯一归属写 `telemetry_anomaly.reason='UNASSIGNABLE'`，不按当前关系推测。`track_point.fleet_id_at_event` 记录事件时刻车队。
- 幂等保持 08 语义：`uk_track_terminal_time (terminal_id, gps_time)`；重复一致不新增，同秒不同内容写 `CONFLICTING_CONTENT`。补传仅写 HISTORY，不推进 `vehicle_last_position`/`vehicle_monitor_state`/`notification_outbox`。
- simulator 增加 `--batch mixed`、`--batch-size`、`--backfill`、`--backfill-seconds`、`--backfill-interval-ms`、`--reconnect true|false`；`web/e2e/monitoring.spec.ts` 增加“断线重连补传后列表与地图不回退”用例。
- 无新增迁移；`SchemaUpgradeTest` 未改动（9 migrations/23 表/37 partitions）。

验证命令与结果（完整记录见 `.scratch/vehicle-monitoring/evidence/09-verification.md`）：
- `mvnw.cmd -B -pl server -am -Dtest=BatchBackfillFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`：7/7 通过。
- 真实 CLI + 独立 MySQL/Redis + HTTP 三角色：`verify-09.ps1` 输出 `ALL CHECKS PASSED`（`09-cli-http-sql.log`）。
- `mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`：BrowserFlowTest 通过，Playwright 37 passed（`09-browser.log`、`09-playwright.log`，截图 `09-backfill-no-rollback.png`）。
- 回归 `PositionQualityFlowTest`+`TelemetryFlowTest`+`SchemaUpgradeTest`+`BatchBackfillFlowTest`：27/27 通过（`09-regression.log`）。
- 前端 `pnpm --dir web typecheck|lint|build`：exit 0（lint 39 条既有 warning，0 error；`09-web-checks.log`）。
- 提交前合并 `integration/tickets-08-27` 至 `f3d2645`（含任务10）无冲突；合并后回归 6 个测试类 45/45 通过（`09-postmerge-backend.log`），BrowserFlowTest 1/1 通过（`09-postmerge-browser.log`），前端 typecheck/lint/build exit 0（`09-postmerge-web-checks.log`）。保留期外拒绝摘要恢复接收时绑定语义（`currentOwnership`），仅用于运营摘要，不参与历史归属与读取授权。

边界与后续复验：轨迹查询 API/回放属 10；司机与报警关联属 12/13；报警产生与处置属 14；真实天地图属 26。本票不替代 08 幂等/冲突测试与 06 注册鉴权测试；`TelemetryFlowTest` 仅在测试辅助中把绑定区间回溯以适配按 `gps_time` 归属的新语义。
