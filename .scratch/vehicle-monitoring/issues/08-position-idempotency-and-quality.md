# 08: 重复、冲突与定位质量处理

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 07

## What to build

补齐位置幂等、冲突与定位质量：唯一位置键为 terminal_id+gps_time，规范化内容一致（payload_hash，不含 serial_no、received_at、包顺序）为重传，返回成功不重复入库；同终端同秒不同内容保留首条正常位置，后到内容写 telemetry_anomaly 且可查，不覆盖、不进实时判定，异常提交后才应答；不使用 INSERT IGNORE 吞掉截断、分区缺失或其他 SQL 错误，仅处理预期唯一键冲突。时差 received_at−gps_time 在 −30 至 60 秒内才可成为实时候选；容差内未来点仅入历史，不推进水位/报警/围栏，时间追上也不追认；超过 30 秒记异常；旧于已处理点只补历史，不回退最新位置。结构合法但定位无效的点可存 track_point，lng/lat 为 NULL、locate_state 标记，保存受限原始内容用于比对，不进地图/超速/围栏；非法时间、越界坐标、无法归属写异常原因；保留期外记受限异常，不重建过期分区。

## Spec coverage

- 主：[AC10](../spec.md)、AC11、AC13；US28、US29、US30、US31。
- 次：AC14 的 NULL 持久化与不进地图部分；US32。
- 复验：0704 批量幂等属 09；定位异常报警的连续 3 点成立/恢复属 14；本票不实现报警，14 在本票事务基础上接同一事务，不能替代本票输入与存储验证。

## Acceptance criteria

- [ ] ACK 丢失后重传同定位体但改流水号，仅一条正常位置且返回成功
- [ ] 同秒不同内容保留首条，冲突写 telemetry_anomaly 且可查
- [ ] 冲突点不改变快照，也不进入后续实时判定输入
- [ ] 时差窗口外的未来/过旧点按规则分流：仅历史或异常
- [ ] 容差内未来点不冻结正常流，时间追上也不追认
- [ ] 定位无效点 lng/lat 为 NULL 且页面地图不显示
- [ ] 非法时间、越界坐标、无法归属记录异常原因
- [ ] 保留期外不重建分区，仅记受限异常摘要

## Scope boundaries

- 不做 0704 与补传归属（09）、报警生成（14）、围栏（15）、轨迹查询（10）、保留期维护（11）。
- 本票保证无效坐标不参与超速或围栏计算，同时为定位无效报警保留状态输入；定位异常成立/恢复由 14 实现并验证。
- 不替代 07 的 ACK/事务基础测试；08 也不重复实现 07 的推送通道。

## Validation

- 真实 TCP 重传与并发同秒冲突；真实 MySQL 核对唯一键、分区与异常表；WS 与浏览器核对快照与地图表现。
- 可控时钟推进时差窗口；记录命令、环境、期望/实际与证据位置，不得预填通过。

## Comments

（暂无讨论）

## Answer

已实现并验证（2026-11-16，分支 `feat/ticket-08-idempotency-quality`）。

### 改动

- `server/src/main/java/com/vms/telemetry/PositionIngestService.java`：位置入库改为以唯一键 `uk_track_terminal_time (terminal_id, gps_time)` 为幂等权威；只捕获 `DuplicateKeyException` 进入重传/冲突比对，不用 `INSERT IGNORE`；`payload_hash` 取规范化 `PositionReport.encode()` 的 SHA-256（不含 serial_no、received_at、包顺序）；同秒不同内容写 `telemetry_anomaly`（`CONFLICTING_CONTENT`），不覆盖首条、不改变快照、不进入实时判定；时差窗口 `[-30s, 60s]` 且新于水位才为 REALTIME，容差内未来点与过旧点只写 HISTORY，超容差写 `FUTURE_BEYOND_TOLERANCE`；定位无效写 `track_point` 且 `lng`/`lat` 为 NULL、`locate_state='INVALID'`，不进地图/快照/outbox；非法时间、越界坐标、无法归属、保留期外分别写 `INVALID_TIME`、`COORDINATE_OUT_OF_RANGE`、`UNASSIGNABLE`、`RETENTION_OUTSIDE`。
- `server/src/main/java/com/vms/telemetry/RawPositionFrame.java`：新增 0x0200 宽松解析，结构错误仍关连接，非法时间/越界坐标转为质量问题记录。
- `server/src/main/resources/db/migration/V7__telemetry_anomaly.sql`：新增 `telemetry_anomaly`（`terminal_id`、`vehicle_id`、`fleet_id_at_event`、`gps_time`、`received_at`、`reason`、`payload_hash`、`body_length`、`raw_body VARBINARY(255)`，按 `received_at`、`(vehicle_id, gps_time)`、`(terminal_id, gps_time)` 建索引）。
- `server/src/main/java/com/vms/access/TerminalFrameHandler.java`：以 `positions.accept(session, frame.body(), receivedAt)` 替换严格解析入口；`accept` 事务提交后才回 0x8001 成功 ACK。
- `simulator/src/main/java/com/vms/simulator/TerminalSimulator.java`：新增 `--fix-valid` 选项，用于真实 TCP 上报无效定位。
- `docs/design/系统设计方案.md`：补 `telemetry_anomaly` 结构与位置分流规则。
- 测试：`PositionQualityFlowTest`（8 项，真实 TCP + Testcontainers MySQL）、`SchemaUpgradeTest` 同步为 8 个迁移（V1–V6 + V7 + V14）与含 `telemetry_anomaly` 的 18 张表、`web/e2e/monitoring.spec.ts` 新增无效定位浏览器用例。

### 命令与结果

- 红→绿：`mvnw.cmd -B -pl server -am -Dtest=PositionQualityFlowTest ... test`，红 3 轮（1 error；4 项中 2 failures/1 error；8 项中 3 failures/5 errors），绿 3 轮全通过。
- 合并 `integration/tickets-08-27` 后：`mvnw.cmd -B -pl server -am clean -Dtest=PositionQualityFlowTest,TelemetryFlowTest,SchemaUpgradeTest ... test` → 19 项全通过（8 + 10 + 1）。
- 浏览器：`mvnw.cmd -B -pl simulator -am -DskipTests package` 成功；`mvnw.cmd -B -pl server -am -Dtest=BrowserFlowTest -Dvms.browser-tests=true ... test` 通过，Playwright 35 passed（含无效定位地图用例）。
- 前端：`pnpm --dir web typecheck|lint|build` 均 exit 0。
- 静态：`server/src`、`simulator/src`、`web/src` 无 `INSERT IGNORE`。
- 以上命令均经 `with-test-lock.ps1` 串行执行；测试结束无 Testcontainers/Ryuk 残留，未清理其他项目容器。

### 证据

- `.scratch/vehicle-monitoring/evidence/08-verification.md`：逐条 AC 状态、命令、环境、期望/实际。
- 日志：`08-red-idempotency.log`、`08-green-idempotency.log`、`08-red-window-quality.log`、`08-green-window-quality.log`、`08-red-quality-branches.log`、`08-green-quality-branches.log`、`08-postmerge-backend.log`、`08-simulator-build.log`、`08-browser.log`、`08-playwright.log`、`08-typecheck.log`、`08-lint.log`、`08-build.log`；截图 `08-invalid-fix-not-on-map.png`。

### 未完成 / 阻塞

- 无阻塞。异常管理查询页面不在本票范围，当前仅经受限 DB 投影追溯。
- 0704 批量幂等属任务09；定位异常报警成立/恢复属任务14；本票未实现，后续票应在本票事务基础上接入。

### 后续复验提示

- 任务09/14/10/11 合入后应复验唯一键幂等、冲突不覆盖、窗口分流与异常行不被破坏；新增迁移后同步 `SchemaUpgradeTest` 的迁移数与表清单。
