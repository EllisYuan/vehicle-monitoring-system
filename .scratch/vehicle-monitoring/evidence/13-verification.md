# 任务13 验证记录：报警处置闭环

- 日期：2026-11-16
- 分支：`feat/ticket-13-alarm-handling`
- 基线：从集成分支 `4e3cd32`（任务09、10）建立分支，先以 `git merge --no-ff feat/ticket-12-overspeed-alarm` 合入报警基础（HEAD `57b58af`），再实施本票；完成验证后合并 `integration/tickets-08-27`（最终 HEAD 见交付报告）。
- 环境：Windows 11 Pro for Workstations 10.0.26200；JDK 21.0.9（`C:\Java\jdk-21.0.9`）；Maven Wrapper 3.9.12；Spring Boot 4.1.2；Testcontainers `mysql:8.4`、`redis:8.4`、`testcontainers/ryuk:0.14.0`（Ryuk 启用）；Node/pnpm 10.16.1；Playwright chromium（workers=1，retries=0，用例超时 60s）；业务时区 Asia/Shanghai。
- 串行方式：所有 Maven 与浏览器命令均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1`（互斥量 `Local\vms-verification-lock`）串行执行，脚本首参为本 worktree 的 `mvnw.cmd` 绝对路径；未并行 Maven。
- 证据存放：原始日志位于 .scratch/vehicle-monitoring/evidence/logs/13-*.log；仓库 .gitignore 仅放行 evidence/*.log，故同内容另存为 .scratch/vehicle-monitoring/evidence/13-*.log 随提交归档，截图为 evidence/13-alarm-handling.png、evidence/13-alarm-leader-readonly.png。

## 1. TDD 红→绿记录

公共命令前缀（锁内）：`mvnw.cmd -B -pl server -am -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test`

| 切片 | 红阶段实际 | 绿阶段实际 | 证据 |
| --- | --- | --- | --- |
| 迁移与 SchemaUpgradeTest | 2 个用例均失败（期望迁移/表清单尚未包含 `alarm_handle_log`），`Tests run: 2, Failures: 2`，BUILD FAILURE，52.6s | `Tests run: 2, Failures: 0`，BUILD SUCCESS，47.4s | `13-red-schema.log`、`13-green-schema.log` |
| 列表与详情（双状态、司机快照、掩码、权限矩阵） | `GET /api/v1/alarms` 返回 404，`Tests run: 1, Failures: 1`，BUILD FAILURE，53.5s | `Tests run: 1, Failures: 0`，BUILD SUCCESS，46.8s | `13-red-handling.log`、`13-green-list-detail.log` |
| 状态机、处置日志与非法迁移 | `POST /api/v1/alarms/{id}/handle` 返回 404，`Tests run: 2, Failures: 1`，BUILD FAILURE，60.0s | `Tests run: 2, Failures: 0`，BUILD SUCCESS，45.9s | `13-red-handle.log`、`13-green-handle.log` |
| 恢复写 CONDITION 日志与新周期 | `recoveryLogsConditionChangeSeparatelyAndNewCycleCreatesNewAlarm` 失败（缺条件变化历史），`Tests run: 3, Failures: 1`，BUILD FAILURE，44.7s | `Tests run: 3, Failures: 0`，BUILD SUCCESS，46.3s | `13-red-condition-log.log`、`13-green-condition-log.log` |
| 全流程（6 项） | —（前四个切片已逐段转绿） | `AlarmHandlingFlowTest` `Tests run: 6, Failures: 0`，BUILD SUCCESS，56.7s | `13-full-flow.log` |

红阶段失败均为目标接口/断言尚未实现导致，未预填通过；绿阶段为最小实现后转绿。`AlarmHandlingFlowTest` 使用真实 Testcontainers MySQL 8.4/Redis 8.4 与真实 HTTP/WS/TCP 链路。

## 2. 逐条 AC

### AC1 PENDING→PROCESSING→HANDLED/IGNORED 迁移可用，非法迁移被拒绝 — 通过

- 命令：锁内 `mvnw.cmd -B -pl server -am -Dtest=AlarmHandlingFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；浏览器用例见第 5 节。
- 期望：合法迁移 200 且返回目标 `handleStatus`；`PENDING→HANDLED`、终态重开等非法迁移 409 并带 `code=ALARM_HANDLE_ILLEGAL`，数据库状态与日志不变。
- 实际：`PENDING→PROCESSING` 200（`handleStatus=PROCESSING`）；`PENDING→HANDLED` 409 `ALARM_HANDLE_ILLEGAL`，`alarm.handle_status` 保持 PENDING、`alarm_handle_log` 仍 1 条；`HANDLED→PROCESSING` 终态重开 409，历史保持 2 条；浏览器从 PENDING 依次操作“开始处理”“处理完成”后分别显示“处理中”“已处理”。
- 证据：`AlarmHandlingFlowTest.handleStateMachineLogsEachStepSeparatelyAndRejectsIllegalTransitions`（`13-green-handle.log`、`13-full-flow.log`）；`13-playwright.log` 中 `alarms.spec.ts`。

### AC2 每次处置记录操作者、时间、备注与状态变化 — 通过

- 命令：同上。
- 期望：每次人工处置在 `alarm_handle_log` 写入一行 `change_type='HANDLE'`，含 `operator_id`、`created_at`、`remark`、`from_status`、`to_status`；详情 `handleHistory` 可读。
- 实际：首行 `from_status=PENDING`、`to_status=PROCESSING`、`operator_id=actorId`、`remark='开始核查'`、`created_at` 非空；第二行 `PROCESSING→HANDLED`，`operatorName='handle-admin'`、`remark='已联系司机'`；详情 `handleHistory` 按顺序返回 2 条。
- 证据：同上；审计表另有 `CATEGORY_ALARM`/`HANDLE` 记录（`actor_id`、`result=SUCCESS`）。

### AC3 condition_state 与 handle_status 独立，恢复不改处置，处置不改恢复 — 通过

- 命令：同上。
- 期望：异常恢复只改 `condition_state` 并写 `CONDITION` 日志，不产生人工处置历史、不改 `handle_status`；人工处置只改 `handle_status` 并写 `HANDLE` 日志，不改 `condition_state`。
- 实际：恢复后 `condition_state=RECOVERED`、`handle_status=PENDING`；`conditionHistory` 1 条（`CONDITION`、`ACTIVE→RECOVERED`、`operatorId=null`、`remark=null`），`handleHistory` 0 条；随后人工处置后 `conditionState=RECOVERED`、`handleStatus=PROCESSING`，两套历史分别为 1 条与 1 条。
- 证据：`AlarmHandlingFlowTest.recoveryLogsConditionChangeSeparatelyAndNewCycleCreatesNewAlarm`（`13-red-condition-log.log` 红、`13-green-condition-log.log` 绿、`13-full-flow.log`）。

### AC4 终态不可重开，新周期生成新事件 — 通过

- 命令：同上。
- 期望：`HANDLED`/`IGNORED` 不能回到 `PROCESSING`；同一车辆/类型/来源/维度出现新异常周期时新建 `alarm` 行，旧行保持终态与恢复状态。
- 实际：`HANDLED→PROCESSING` 409 `ALARM_HANDLE_ILLEGAL`；新周期产生的新 `alarm.id` 与旧 `alarm.id` 不同，旧行保持 `condition_state=RECOVERED`、`handle_status=HANDLED`，新行 `condition_state=ACTIVE`、`handle_status=PENDING`；旧行 `conditionHistory` 仍为 1 条。新周期事件沿用任务12的 `alarm:created:<id>` 语义（回归 `OverspeedAlarmFlowTest` 覆盖）。
- 证据：同 AC1/AC3 测试；回归见第 3 节。

### AC5 车队长只读授权范围报警与处置历史 — 通过

- 命令：同上；浏览器用例 `alarms.spec.ts`（管理员 + 车队长两个浏览器上下文）。
- 期望：`FLEET_LEADER` 只能看到授权车队的报警与处置历史；越界车队详情 403；未登录 401；不存在 404；车队长调用处置接口 403；页面无处置表单。
- 实际：车队长列表含授权车队报警、不含其他车队报警；越界详情 403；未登录 401；不存在 404；车队长 `POST /handle` 403 且数据库状态不变；浏览器车队长页面显示 `alarm-readonly-note`、`alarm-readonly-detail-note`，`alarm-handle-form` 数量为 0。
- 证据：`AlarmHandlingFlowTest.listAndDetailExposeBothStatesDriverSnapshotPositionAndMasking`（`13-full-flow.log`）；`13-playwright.log`；截图 `13-alarm-leader-readonly.png`。

### AC6 详情显示当时关联司机，换关联后历史不回写 — 通过

- 命令：同上。
- 期望：详情与列表使用报警发生时保存的 `driver_id`；车辆改绑司机后，既有报警仍显示原司机。
- 实际：详情 `driver.id` 与列表 `driverId` 均为报警时司机；改绑后详情 `driver.id` 不变，`driver.name` 与 `driver` 表原记录一致。
- 证据：同 AC5 测试（`13-full-flow.log`）。

### AC7 车队长司机电话中间四位掩码 — 通过

- 命令：同上；浏览器车队长上下文。
- 期望：管理员看到完整 `13812345678`；车队长看到 `138****5678`。
- 实际：管理员详情 `driver.phone=13812345678`；车队长详情 `driver.phone=138****5678`，浏览器页面文本一致。
- 证据：同 AC5 测试；`13-playwright.log`；截图 `13-alarm-handling.png`、`13-alarm-leader-readonly.png`。

### AC8 处置日志与 outbox 同事务，提交后即时推送 — 通过

- 命令：同上（含 WS 订阅与并发线程）。
- 期望：处置时 `alarm_handle_log` 与 `notification_outbox` 同一事务提交；outbox 写失败时整体回滚；提交后 WS 收到 `alarm_event`；并发处置仅一方成功。
- 实际：人为使 outbox 写入违反 CHECK 约束时接口返回 ≥500，`alarm.handle_status` 保持 PENDING、处置日志 0 条、outbox 0 条；移除约束后重试 200 且日志 1 条。正常处置后 WS 收到 `type=alarm_event`、`event_id=alarm:handle:<alarmId>:<logId>`，`payload.conditionState=ACTIVE`，outbox 最终 `DONE`。并发两次处置结果为 200 与 409（`ALARM_HANDLE_ILLEGAL`），库中仅 1 条日志、1 条 outbox。
- 证据：`handlePushesAlarmEventAfterCommitAndOutboxMarkedDone`、`handleLogAndOutboxRollBackTogetherWhenOutboxWriteFails`、`concurrentHandlingUsesCurrentStatusConditionalUpdate`（`13-full-flow.log`）。

### AC9 真实 HTTP 权限矩阵测试与数据库核对记录 — 通过

- 命令：同上；测试以 `@SpringBootTest(RANDOM_PORT)` 起真实服务，经 HTTP 调用并直接用 `JdbcTemplate` 核对 `alarm`、`alarm_handle_log`、`notification_outbox`、审计表。
- 期望：401/403/404/409 分支与数据库行数、字段值可核对。
- 实际：见 AC1/AC5/AC8 的实际结果；`AlarmHandlingFlowTest` 6/6 通过。
- 证据：`13-full-flow.log`。

## 3. 回归

锁内命令：

```
mvnw.cmd -B -pl server -am -Dtest=OverspeedAlarmFlowTest,TelemetryFlowTest,SchemaUpgradeTest,PositionQualityFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 结果：`Tests run: 30, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，`Total time: 03:56 min`（`OverspeedAlarmFlowTest` 10/10、`SchemaUpgradeTest` 2/2、`PositionQualityFlowTest` 8/8、`TelemetryFlowTest` 10/10）。
- 说明：`SchemaUpgradeTest` 期望 11 个迁移且表清单含 `alarm_handle_log`，与 V9 一致；其余为既有业务回归，未修改断言。
- 证据：`13-regression.log`（含 `[lock] acquired` 与 `[lock] command exit code: 0`）。

### 合并 `integration/tickets-08-27`（`82ff22a`，含任务15围栏）后复验

- 首次锁内 `clean test`（`13-post-merge-regression.log`）：`AlarmHandlingFlowTest` 6/6、`OverspeedAlarmFlowTest` 10/10、`PositionQualityFlowTest` 8/8、`TelemetryFlowTest` 10/10 通过；`SchemaUpgradeTest` 2 项失败，原因是合并后迁移总数为 12（`V6__durable_position` Java 迁移 + 11 个 SQL，新增 `V13__geofence.sql`），而测试仍沿用合并双方各自的 11；按合并后实际语义将期望更正为 12/12/6。
- 修正后锁内重跑（`13-post-merge-schema.log`）：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- 其余四个测试类在同一次合并后运行中已全绿，未受该计数修正影响。

## 4. 前端静态检查与构建

均使用 `pnpm --dir web <script>`（pnpm 10.16.1）：

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `pnpm --dir web install` | exit 0，Done in 25.9s | `13-web-install.log` |
| `pnpm --dir web typecheck` | exit 0（`vue-tsc -b` 无报错） | `13-web-typecheck.log` |
| `pnpm --dir web lint` | exit 0，`137 problems (0 errors, 137 warnings)`；本票新增 6 条 `vue/singleline-html-element-content-newline`（`AlarmListView.vue:227–229` 单行 `<dd>`），其余为既有 warning | `13-web-lint.log` |
| `pnpm --dir web build` | exit 0，`✓ built in 563ms`，产物含 `AlarmListView-Du2wYoTt.js` | `13-web-build.log` |

合并集成分支后复跑 `pnpm --dir web typecheck`、`lint`、`build`：均 exit 0（`13-post-merge-web-typecheck.log`、`13-post-merge-web-lint.log`、`13-post-merge-web-build.log`）；lint 仍为 0 error / 137 warning，构建同时含 `AlarmListView` 与围栏页面产物。

## 5. 浏览器 e2e

锁内命令（两条）：

1. `mvnw.cmd -B -pl simulator -am -DskipTests package`
2. `mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`

实际执行（均经 `with-test-lock.ps1` 串行；每条日志含 `[lock] acquired` 与 `[lock] command exit code`）：

- 命令 1（simulator 打包）：`13-simulator-build.log`（16:12:49，BUILD SUCCESS，3.299s，exit 0）；重跑 6 前再次执行，`13-simulator-build-rerun6.log`（BUILD SUCCESS，2.014s，exit 0）。
- 命令 2（BrowserFlowTest）：共执行 7 次（首轮 + rerun1–rerun6），全部原始日志保留，未修改任何既有 spec，未放宽断言：

| 次序 | 完成时间 | 结果 | 说明 | 证据 |
| --- | --- | --- | --- | --- |
| 首轮 | 15:21:38 | BUILD FAILURE，Playwright exit 1，`Tests run: 1, Failures: 1`，252.7s | `alarms.spec.ts` 首版轮询 `/monitor/snapshot` 顶层 `speedKph`，实际字段为 `position.speedKph`，轮询恒为 0 导致等待超时；修正 spec 字段路径后重跑，断言未放宽 | `13-browser-flow.log` |
| rerun1 | 15:29:20 | BUILD FAILURE，`Tests run: 1, Failures: 1`，5.845s | 修正后的 spec 有语法错误，Playwright 启动即失败；修正语法后重跑 | `13-browser-flow-rerun1.log` |
| rerun2 | 15:34:33 | BUILD FAILURE，`Tests run: 1, Failures: 1`，217.1s | `archives.spec.ts` 首个用例 5s 登录等待偶发超时；按允许重跑一次处理，未修改该 spec、未放宽断言 | `13-browser-flow-rerun2.log` |
| rerun3 | 15:46:03 | BUILD FAILURE，245.5s | `[Playwright finishes within four minutes]` 墙钟预算超时中断套件；当次日志另显示 `track-retention.spec.ts` 机内失败（既有用例，文件未改动） | `13-browser-flow-rerun3.log` |
| rerun4 | 15:59:52 | BUILD SUCCESS，`Tests run: 1, Failures: 0`，253.5s | Playwright `40 passed (3.5m)`，`alarms.spec.ts` 通过；截图产出。当次 `02-playwright.log` 未单独留档，其副本被 rerun6 覆盖，rerun4 的绿结果以本 surefire 日志为准 | `13-browser-flow-rerun4.log` |
| rerun5 | 16:18:06 | BUILD FAILURE，245.4s | `[Playwright finishes within four minutes]` 墙钟超时；被中断前的日志显示第 1–37 项全部通过（含 `alarms.spec.ts` 17.0s），第 38 项 `track-retention.spec.ts` 进行中被终止，属超时而非断言失败 | `13-browser-flow-rerun5.log` |
| rerun6 | 16:24:31 | BUILD SUCCESS，`Tests run: 1, Failures: 0`，259.7s | Playwright `40 passed (3.6m)`（39 项基线 + 新增 `alarms.spec.ts` 1 项），`alarms.spec.ts` 16.4s、`track-retention.spec.ts` 5.8s 通过 | `13-browser-flow-rerun6.log`、`13-playwright.log` |

- 结论：浏览器验证以 rerun6 全绿（40/40）为准，rerun4 亦为全绿。新增 `alarms.spec.ts` 覆盖：真实 TCP simulator 触发超速→`PENDING`，管理员在页面依次“开始处理”“处理完成”（详情双状态列、当时司机、完整电话、规则快照“阈值 80 km/h”、处置历史 2 条、终态提示）；车队长仅授权车队可见、只读提示、无处置表单、电话 `138****5678`。截图见 `13-alarm-handling.png`、`13-alarm-leader-readonly.png`。

### 合并集成分支后复验（`82ff22a`）

合并后按同一锁内两条命令执行 3 次（套件共 43 项 = 39 项基线 + `alarms.spec.ts` 1 项 + `geofences.spec.ts` 3 项）：

| 次序 | 结果 | 说明 | 证据 |
| --- | --- | --- | --- |
| run1 | BUILD FAILURE，245.1s | 四分钟墙钟预算超时中断套件；中断前 `alarms.spec.ts` 通过，`geofences.spec.ts` 第 1 例失败（该例在任务15自身验证中也曾因圆心坐标偶发失败、重跑后通过）；套件被终止，无法取得完整错误详情 | `13-post-merge-browser-flow.log`、`13-post-merge-playwright-run1.log` |
| run2 | BUILD FAILURE，244.2s | 四分钟墙钟预算超时；第 1–40 项通过（含 `alarms.spec.ts` 18.7s 与围栏 3 例），第 41 项 `track-retention.spec.ts` 偶发失败后被终止 | `13-post-merge-browser-flow-run2.log`、`13-post-merge-playwright-run2.log` |
| run3 | BUILD SUCCESS，272.1s | Playwright `43 passed (3.8m)`，含 `alarms.spec.ts` 15.1s、`geofences.spec.ts` 3 例与 `track-retention.spec.ts` 5.6s | `13-post-merge-browser-flow-run3.log`、`13-post-merge-playwright-run3.log`、`13-playwright-merged.log` |

- 结论：合并后浏览器验证以 run3 全绿为准。run1/run2 的失败来自既有 `geofences.spec.ts` 圆心坐标偶发、`track-retention.spec.ts` 偶发与 `BrowserFlowTest` 固定的四分钟墙钟预算（43 项套件接近该上限），本票新增 `alarms.spec.ts` 在三次合并后运行中全部通过；未修改既有 spec，未放宽断言。

## 6. Testcontainers/Ryuk 回收核对

- 本票所有 Maven/浏览器运行均使用 Testcontainers `mysql:8.4`、`redis:8.4` 与 `testcontainers/ryuk:0.14.0`（Ryuk 启用，未关闭）。每次运行后核对 `docker ps -a`：
- 回归运行、rerun4（容器 session `d84c2016…`）、rerun5、rerun6 的 mysql/redis/Ryuk 容器均已自动回收；2026-11-16 16:24:56 最终核对时 `docker ps` 中无本票任何容器与 Ryuk。
- 现存的 `086e2cc950ce`（`reverent_brahmagupta`，`mysql:8.4`）标注 Testcontainers sessionId `4d5db3f4-edb5-447e-9f3e-f0d35d62a879`，不属于本票运行，按“不清理他人容器”保留未动；并发 ticket 15 agent 的容器已由其 Ryuk 回收。
- 其余 Exited 容器（`plp-*`、`vms-*`、`pms_*` 等）为既有开发/其他项目资源，未触碰。

- 合并后复验（run1–run3 与 SchemaUpgradeTest 重跑）的 mysql/redis/Ryuk 容器同样已回收；2026-11-16 17:12 核对时除他人 session 的 `086e2cc950ce` 外无 testcontainers 容器。

## 7. 偏差与遗留

- 基线与分支：按要求从 `4e3cd32` 建立 `feat/ticket-13-alarm-handling`，先 `--no-ff` 合入任务12（`57b58af`）；2026-11-16 已合并 `integration/tickets-08-27`（`82ff22a`，含任务15围栏）并完成上述复验。合并后迁移总数为 12（V6 Java 迁移 + 11 个 SQL），`SchemaUpgradeTest` 期望已按合并语义更正为 12。
- 浏览器用例首轮失败后经历多次重跑，原因与结果均如实记录于第 5 节；未放宽任何既有断言，未修改既有 spec（`archives.spec.ts`/`track-retention.spec.ts` 保持原样）。
- 未实现（属其他票）：规则配置与超速判定（12，已合入）、离线/终端报警（14）、围栏（15）、导出（20）、WS 撤权深化（17）；未做人工归档、终态重开或自动处置。
- 已知跨票问题：`BrowserFlowTest` 依赖 simulator 预编译产物（任务27 在干净环境处理）；本轮以锁内 simulator package/编译产物配合浏览器验证。
