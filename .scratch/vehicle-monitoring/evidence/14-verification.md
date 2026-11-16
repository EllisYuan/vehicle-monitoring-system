# 任务14 验证记录：离线、定位与终端来源报警

- 日期：2026-11-16
- 分支：feat/ticket-14-offline-alarms
- 基线：`integration/tickets-08-27` @ `4e3cd32`；先合入 `feat/ticket-12-overspeed-alarm` @ `c4d40c8`（合并提交 `f6b7f56`，未修改任务12内容）。完成前再合并 `integration/tickets-08-27` @ `7412c1f`（合并提交 `11b80c5`）；该次合并只含任务09证据/issue 文档更正，无代码差异。
- 结论：9 条 AC 中 8 条通过；GNSS 与终端来源报警位因缺少已核对映射标为 blocked，未编造。新增迁移 V12；未占用、未修改其他迁移。

## 环境

- Windows 11 Pro for Workstations 10.0.26200；JDK 21（`JAVA_HOME=C:\Java\jdk-21.0.9`）。
- Maven Wrapper `mvnw.cmd`；Testcontainers 2.0.5：MySQL 8.4、Redis 8.4、Ryuk 0.14.0（保持启用，结束自动回收）。
- 所有 Maven 与真实 server 命令均经全局互斥脚本 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行；Maven 命令首个参数为本 worktree 的 `mvnw.cmd` 绝对路径。独立 CLI 脚本本身作为该锁的命令参数执行。
- CLI 场景使用独立容器 `t14-mysql`（端口 13311，root/t14pass，库 vms）与 `t14-redis`（16383）；打包后的 `server/target/server-0.1.0-SNAPSHOT.jar` 监听 HTTP 18094、TCP 19014；脚本结束只删除本脚本容器。
- simulator 以 `protocol/target/classes` 与 `simulator/target/classes` 运行，使用同一 JDK 21。

## 实现要点

- **离线判定**：`OfflineAlarmService` 以 `terminal.last_seen_at`（有效通信时间）为唯一依据，不依赖当前 TCP 连接。`scan()` 选择 `terminal.status='ENABLED'`、`vehicle.status='ENABLED'`、`ever_authenticated=TRUE`、`last_seen_at <= now-180s`、且当前绑定存在的终端；`last_logout_at IS NULL OR last_logout_at < last_seen_at` 排除合法注销，`NOT EXISTS` 既有 ACTIVE 事件保证不重复建事件。已有 ACTIVE 的终端不再新建。
- **启动宽限**：服务构造时记录 `startedAt`，`scan()` 在 `now < startedAt+180s` 时直接返回；调度初始延迟同时配置为 180000ms。宽限只抑制扫描，不改变既有 ACTIVE 事件。
- **恢复**：`authenticate`（鉴权成功）、`touch`（心跳/终端应答）、位置报文接收 `touchTerminal` 在同一事务内调用 `recover`，把该终端 ACTIVE 离线事件改为 RECOVERED 并写 `alarm:recovered:<id>` outbox；`last_time` 不因恢复改变。合法 0x0003 走 `lawfulLogout`：写 `terminal.last_logout_at`，不恢复既有 ACTIVE，也不新造报警。非主动断连不写 `last_logout_at`。
- **管理操作**：停用终端、停用车辆、解绑/换绑只把该范围内 ACTIVE 的 OFFLINE/POSITION_INVALID 事件标注 `alarm.scope_changed_at`；事件保持 ACTIVE、`recovered_at` 保持 NULL，不自动恢复；停用/解绑后扫描条件排除，不再新造通信故障报警。
- **定位无效**：`PositionQualityParticipant.observe` 只在实时无效点调用；`PositionInvalidAlarmParticipant` 同时实现 `PositionStateParticipant`（有效实时点）与 `PositionQualityParticipant`（无效实时点），在 07 位置事务内维护 `alarm_candidate_state`（方向 INVALID/VALID，`rule_id`/`rule_version` 为 NULL），连续 3 点成立 ACTIVE、连续 3 个有效点 RECOVERED；无效点不写 `vehicle_last_position`/`vehicle_monitor_state`/位置 outbox，不进入 `applyRealtimeState`（超速与后续围栏判定路径）。
- **坐标与原始内容**：`track_point.lng/lat` 对定位无效点写 NULL，不伪造 0/0、不复用旧坐标；保留该点 `raw_body`（受限原始内容）与 `payload_hash`、`locate_state='INVALID'`。
- **同事务与 ACK**：定位无效报警、候选 state 与 outbox 写入都在 `PositionIngestService.accept/acceptBatch` 的 `@Transactional` 内，没有单独提交路径；提交成功后由 `TerminalFrameHandler` 回 0x8001（ADR 0005）。报警写入失败时位置、候选、outbox 一并回滚，连接关闭且不返回成功 ACK。
- **迁移 V12**：`terminal.last_logout_at`、`alarm.scope_changed_at`、`alarm.alarm_type` 允许 OFFLINE/POSITION_INVALID、候选表 `rule_id`/`rule_version` 可空与方向 INVALID/VALID、`idx_alarm_terminal_type_state`。
- **调度的隔离**：`spring.task.scheduling.pool.size` 2→4，使两个 outbox worker、指令超时、离线扫描与每日分区维护互不挤占；新增 `vms.alarm.offline-scan-interval-ms`（默认 10000）与 `vms.alarm.offline-scan-initial-delay-ms`（默认 180000）。
- **simulator**：新增 `--logout true|false`（默认 true），用于 CLI 场景制造“失联但不主动注销”。

## 已执行

### 1. TDD 切片一：定位无效报警（真实 TCP + Testcontainers MySQL）

命令（走锁）：

```
mvnw.cmd -B -pl server -am -Dtest=OfflineAndPositionAlarmFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 红：`14-red-position-invalid.log`，2 项中 1 failure + 1 error（候选查询无行、报警写入失败未返回成功 ACK 的断言先红）。
- 中途：`14-green-position-invalid.log` 仍有 1 failure——`assertThat(...isEqualTo(2))` 的 Integer/Long 类型不一致，属测试断言写法问题，实现已按预期落库。
- 绿：`14-green-position-invalid-rerun.log`，`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- 覆盖：两个无效点不成立、候选 `rule_id` 为 NULL、无效点速度再高也不进入 OVERSPEED 候选；第三个无效点成立 ACTIVE，`start_time/last_time` 为第三点时间，lng/lat 为 NULL、`locate_state='INVALID'`、`raw_body` 非空、无快照/监控状态/位置 outbox；两个有效点不恢复、第三个恢复且 `last_time` 停留在最后无效点；报警写入失败整体回滚、无成功 ACK，重试后成立。

### 2. TDD 切片二：离线、管理操作与保留边界（同一测试类）

- 红：`14-red-offline.log`，8 项中 1 failure + 4 errors（离线扫描器尚不存在/未接入，候选与事件查询无行）。
- 中途：`14-green-offline.log` 8 项中 1 failure——解绑用例在绑定同一时刻解绑触发 `EFFECTIVE_TIME_NOT_ADVANCED` 409；测试在解绑前推进时钟 1 秒后复跑。
- 绿：`14-green-offline-rerun.log`，`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- 覆盖：
  1. 启动宽限内扫描不生成离线事件；推进 180 秒后成立 ACTIVE，`start_time=last_seen_at+180s`，source=PLATFORM、rule 字段全 NULL、created outbox 1 条。
  2. 已鉴权、启用、曾接入终端 179 秒不成立、181 秒成立；重连鉴权即 RECOVERED，`recovered_at` 为鉴权时刻、`last_time` 不变、recovered outbox 1 条、不重复新事件。
  3. 未接入、合法注销（0x0003 写 `last_logout_at`）、停用终端、停用车辆、解绑终端均不生成离线报警。
  4. 合法补传报文接收更新 `last_seen_at` 并恢复既有 ACTIVE，但包内 HISTORY 点不推进 `vehicle_last_position`（仍为实时点、version=1），不进入 OVERSPEED 候选。
  5. 停用终端、解绑、停用车辆后，既有 ACTIVE 保持 ACTIVE、`recovered_at` NULL、`scope_changed_at` 非空；再次扫描不新增事件。
  6. 保留边界：`RETENTION_OUTSIDE` 同时写 `track_retention_rejection(reason='OUTSIDE_RETENTION')` 与 `telemetry_anomaly(reason='RETENTION_OUTSIDE')`；`FUTURE_BEYOND_TOLERANCE` 只写 `telemetry_anomaly`，不写保留拒绝；两者均不落 `track_point`、不写快照，并明确断言两者区分。

### 3. 编译、回归与构建（走锁）

```
mvnw.cmd -B -pl server -am -DskipTests compile
mvnw.cmd -B -pl server -am -Dtest=OverspeedAlarmFlowTest,PositionQualityFlowTest,TelemetryFlowTest,BatchBackfillFlowTest,TrackRetentionMaintenanceTest,SchemaUpgradeTest -Dsurefire.failIfNoSpecifiedTests=false test
mvnw.cmd -B -DskipTests package
```

- `14-compile.log`：BUILD SUCCESS。
- `14-regression.log`：`Tests run: 47, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。分项：`OverspeedAlarmFlowTest` 10、`SchemaUpgradeTest` 2、`BatchBackfillFlowTest` 7、`PositionQualityFlowTest` 8、`TelemetryFlowTest` 10、`TrackRetentionMaintenanceTest` 10。日志中的 `injected_alarm_failure` 堆栈为 `OverspeedAlarmFlowTest` 故意注入失败的用例，该类最终 10/10 通过。
- `SchemaUpgradeTest` 当时按本分支实际迁移集合更新为空库 11 个迁移、`flyway_schema_history` applied/versions=11、V6 起升级 5 个；日志确认升级到 v14。该数字已被第 5 节合并后的实测取代。
- `14-build.log`：`-DskipTests package` BUILD SUCCESS，server jar 44,160,921 字节，simulator 含 `--logout` 的类已重新编译。

### 4. 最小独立 CLI 场景（真实 TCP + 独立 MySQL/Redis + 数据库核对）

脚本：`C:\Users\YuanS\AppData\Local\Temp\vms-ticket14\verify-14.ps1`。命令：

```
& 'C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1' 'C:\Users\YuanS\AppData\Local\Temp\vms-ticket14\verify-14.ps1' *> .scratch\vehicle-monitoring\evidence\logs\14-cli-offline.log
```

`evidence/logs/` 目录被 `.gitignore` 的 `*.log` 忽略；本节与第 1–3 节的日志已原样复制到 `evidence/14-*.log`（顶层例外白名单），随提交保留。`14-server-*.log` 由脚本直接写入顶层。

- 运行时段：server #1 于 15:21:58 启动，server #2 于 15:25:18 启动；15:28:31 结束。
- 数据准备：建车队 T14-F、车辆 T14-A/T14-B/T14-C 与终端 `01380015000000000014/24/34` 并绑定；管理员 `t14-admin`。
- 注销场景：B 以默认 `--logout true --positions 0 --linger-ms 500` 接入并发送 0x0003；SQL 断言 `ever_authenticated=1`、`last_logout_at` 非空、`last_seen_at` 非空（`1 1 1`），回拨 B `last_seen_at` 400 秒后扫描，B 的 OFFLINE 报警数 0。
- 失联场景：A、C 以 `--logout false --positions 0 --linger-ms 500` 接入后断开；SQL 断言两者 `last_logout_at IS NULL`（各 `1`）。
- 离线成立：server #1 启动宽限（185 秒）后回拨 A `last_seen_at` 400 秒，等待扫描；SQL 返回 `1 PLATFORM OFFLINE ACTIVE 1 0`（`rule_id IS NULL`、`TIMESTAMPDIFF(SECOND,last_seen_at+180s,start_time)=0`）；`alarm:created:1` outbox 为 `ALARM_EVENT 1 1`；B 0 条、C 0 条。
- 有效重连：A 重新走注册/鉴权/心跳；SQL 返回 `RECOVERED 0 0`（`recovered_at` 非空、`start_time` 与 `last_time` 差 0），`alarm:recovered:1` outbox 存在，A 报警总数仍为 1（无重复事件）。
- 重启宽限：停止 server #1，回拨 C `last_seen_at` 400 秒，启动 server #2；启动后 26 秒 SQL 查 C 报警数为 0；等待超过 180 秒宽限后，C 生成 `2 PLATFORM OFFLINE ACTIVE 1 0`（start_time 仍等于旧 `last_seen_at+180s`），`alarm:created:2` outbox 存在。
- 报警汇总：终端 14（A）`OFFLINE RECOVERED PLATFORM`，`recovered_at` 非空、`scope_changed_at` NULL；终端 34（C）`OFFLINE ACTIVE PLATFORM`；终端 24（B）无报警。
- 结果：`ALL CHECKS PASSED`，`[lock] command exit code: 0`，`cleanup: server stopped, t14-mysql/t14-redis removed`；`t14-mysql`/`t14-redis` 已从容器列表消失，未触碰其他 agent 的容器。
- server 控制台：`14-server-first.log`、`14-server-second.log`；两个 `.err.log` 均为空。server #1 日志仅有 MySQL 8.4 对 `Integer display width is deprecated` 的 Flyway WARN，无 ERROR/Exception。

### 5. 合并最新集成分支与合并后复跑（全局锁内）

- 预合并复核：`git merge-base` 为 `7412c1f`，集成分支 tip `d8ddd88`。锁内复跑第 3 步同一命令（显式 `-f <worktree>\pom.xml`，防止在错误目录构建），7 类 **55/55** 通过、BUILD SUCCESS（`14-premerge-rerun.log`）：Offline 8、Overspeed 10、SchemaUpgrade 2、BatchBackfill 7、PositionQuality 8、Telemetry 10、TrackRetention 10。
- 合并：`git merge --no-ff integration/tickets-08-27`，合并提交 `bf25ecd`（双亲 `290b289` + `d8ddd88`）。唯一文本冲突 `server/src/test/java/com/vms/identity/SchemaUpgradeTest.java`（3 组数字），按合并后实测对齐：空库 `migrationsExecuted=13`、history applied/versions=13、自 V6 增量 7；表清单 30 张与 37 分区沿用集成分支断言并实测通过。
- 约束顺序冲突：V13（任务15）晚于 V12 执行，其 `DROP/ADD ck_alarm_type` 原仅允许 `OVERSPEED/GEOFENCE_ENTER/GEOFENCE_LEAVE`，会回退 V12 的 OFFLINE/POSITION_INVALID。合并提交把 V13 约束改为并集 `('OVERSPEED','OFFLINE','POSITION_INVALID','GEOFENCE_ENTER','GEOFENCE_LEAVE')`，保持 13 个迁移；副作用是 V13 checksum 变化，已应用旧 V13 的本地开发库需重建或 `flyway repair`。
- 合并后复跑：同一锁内执行同一 8 类命令，**61/61** 通过、BUILD SUCCESS（`14-postmerge2.log`）：AlarmHandling 6、Offline 8、Overspeed 10、SchemaUpgrade 2、BatchBackfill 7、PositionQuality 8、Telemetry 10、TrackRetention 10。日志确认 `Successfully applied 13 migrations ... now at version v14`，且 Offline 用例在含 V13 的实际 schema 上通过，证明约束并集生效。
- 日志中的 `ck_fail_outbox_13_9`（任务13 注入）与 `CommunicationsException`（任务07 断链用例）为测试故意制造的失败路径，对应测试类均通过。

## AC 状态

| # | Acceptance criterion | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 已启用、已鉴权、曾接入终端失联 180 秒生成离线 ACTIVE | 通过 | 测试类第 3/4/6 项；CLI A、C 的 ACTIVE 与 `start_time=last_seen_at+180s` |
| 2 | 未接入、主动注销、停用、解绑不生成离线报警 | 通过 | 测试类第 5 项；管理操作第 8 项；CLI 注销终端 B 零报警 |
| 3 | 应用启动 180 秒宽限 | 通过 | 测试类第 3 项；CLI 重启 server #2 的 26 秒/190 秒对照 |
| 4 | 有效重连恢复离线报警 | 通过 | 测试类第 4 项；CLI A 重连后 RECOVERED、last_time 不变、recovered outbox |
| 5 | 连续 3 个无效点成立、3 个有效点恢复，坐标 NULL 有证据 | 通过 | 测试类第 1 项；SQL 断言 lng/lat NULL、raw_body 非空 |
| 6 | 无效点不进地图、超速成立/恢复与围栏 | 部分通过 | 地图/快照与超速候选断言通过；围栏属任务15，尚未实现，未执行围栏断言；无效点不进入 `applyRealtimeState` 路径 |
| 7 | GNSS 与终端来源仅映射已核对位，来源保留，未支持不编 | blocked | `PositionReport` 仅有原始 `alarmFlag`，`protocol` 与 ticket 05 fixture 均无可核对的报警位语义；未映射、未生成 TERMINAL 来源事件，未编造 |
| 8 | 报警与 state、outbox 同事务，ACK 时序正确 | 通过 | 测试类第 2 项：报警写入失败时位置/候选/outbox 全回滚、连接关闭且无成功 ACK；重试成功 |
| 9 | 最小 CLI 场景记录失联、恢复、注销与重启宽限 | 通过 | `14-cli-offline.log` 的 `ALL CHECKS PASSED`、SQL 断言与报警汇总 |

## 边界与未执行

- GNSS 异常与终端来源报警位：只保留原始 `alarm_flag`，未实现任何位映射；ticket 05 的独立 fixture 没有报警位语义样例，`protocol` 模块也没有映射表。该 AC 标 blocked，不以自造位语义冒充。
- 围栏：任务15 未实现；本票只保证无效点不进入实时状态路径，未执行围栏计算断言，也未修改围栏相关代码。
- 不重做任务12 的超速规则；不实现任务13 的报警处置界面/接口；未修改 `/monitor/alarms`；未改变任务09 的 `resolveOwnershipAt` 语义；未实现断线补传归属。
- `application.yml` 将调度池 2→4 属本票配置变更，用于避免新增离线扫描与既有 outbox/指令超时/分区维护互相挤占。
- 合并后 `SchemaUpgradeTest` 按实际迁移集合断言 13 个迁移、30 张表、37 分区、自 V6 增量 7；合并后迁移集合为 V1–V5 SQL、V6 Java、V7、V8、V9、V11、V12、V13、V14。
- 本票未改动 `web/`（相对集成分支净差异无前端文件），合并后未执行前端 typecheck/lint。
- CLI 脚本调用锁时以脚本路径作为命令参数（Maven 命令仍以 worktree `mvnw.cmd` 为首参）；锁实现为通用命令包装，脚本结束后自动释放。
- 本轮 Testcontainers/Ryuk 在回归结束后已核对无残留；CLI 脚本在 finally 中只删除 `t14-mysql`/`t14-redis`，未清理其他 agent 的容器。
