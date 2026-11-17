# 任务19 验证记录：有权限的监控统计

- 日期：2026-11-16
- 分支：`feat/ticket-19-monitoring-statistics`；起算 HEAD `199e0f1`（已含任务13），完成验证并合并 `integration/tickets-08-27` 后的最终 HEAD 见交付报告。
- 环境：Windows 11 Pro for Workstations 10.0.26200；JDK 21.0.9（`C:\Java\jdk-21.0.9`）；Maven Wrapper 3.9.12；Spring Boot 4.1.2；Testcontainers `mysql:8.4`、`redis:8.4`、`testcontainers/ryuk:0.14.0`（Ryuk 启用）；Node/pnpm 10.16.1；Playwright chromium（workers=1，retries=0）；业务时区 Asia/Shanghai；可控时钟 `MutableClock` 固定 `2026-06-15T02:00:00Z`（Asia/Shanghai 10:00）。
- 串行方式：所有 Maven/浏览器命令均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1`（互斥量 `Local\vms-verification-lock`）执行；跨 ticket 排队等待属正常，未并行 Maven。前端三项在锁外执行。
- 证据存放：原始日志位于 `.scratch/vehicle-monitoring/evidence/logs/19-*.log`；仓库 `.gitignore` 仅放行 `evidence/*.log`，故同内容另存为 `.scratch/vehicle-monitoring/evidence/19-*.log` 随提交归档。

## 1. TDD 红→绿记录

公共命令前缀（锁内）：`mvnw.cmd -B -pl server -am -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test`

| 阶段 | 实际结果 | 证据 |
| --- | --- | --- |
| 红（今日口径 + 处理率两个切片） | `GET /api/v1/statistics/overview` 返回 404，`Tests run: 2, Failures: 2`，BUILD FAILURE，1:15 min；两处失败均指向统计接口尚未实现 | `19-red-overview.log` |
| 绿（编译迭代） | 测试源码编译失败两次：`StatisticsStartupGraceTest` 登录辅助方法参数顺序错误（`call` 签名为 method、path、body、token）；修正测试代码后进入测试执行 | `19-green-statistics-testcompile-error.log`、`19-green-statistics-testcompile-error2.log` |
| 绿（命令迭代） | 首次用 `-Dtest=A+B` 时 surefire 静默匹配 0 个测试（BUILD SUCCESS、无 `Tests run` 行）；确认分隔符必须为逗号 | `19-green-statistics-zero-match.log` |
| 绿（首轮） | `Tests run: 8, Failures: 1`：`historicalAlarmsStayWithFleetAtEventWhileVehiclesFollowCurrentArchive` 在 `PUT /vehicles/{id}/fleet` 处收到 409 `EFFECTIVE_TIME_NOT_ADVANCED`——换队要求业务时钟严格晚于上一段区间起点，而固定 `MutableClock` 未推进；属测试数据准备问题，非统计实现缺陷。修正：换队前 `clock.advance(Duration.ofSeconds(1))`，并把「今日窗口终点」断言改为按请求前的可控时钟换算（仍验证窗口止于当前、起点为上海零点） | `19-green-statistics-fail1.log` |
| 绿（正式） | 见「3. 统计切片绿测」 | `19-green-statistics.log` |

## 2. 前端闸门（锁外）

命令均在 `web` 目录执行，记录 exit code：

| 命令 | 实际结果 | exit code | 证据 |
| --- | --- | --- | --- |
| `pnpm --dir web typecheck` | `vue-tsc -b` 无输出错误 | 0 | `19-web-typecheck.log` |
| `pnpm --dir web lint` | `✖ 137 problems (0 errors, 137 warnings)`；全部 warning 位于 `alarm-rules`、`alarms`、`track-retention`、`tracks` 等既有视图，本票文件不在其中 | 0 | `19-web-lint.log` |
| `pnpm --dir web exec eslint src/features/statistics e2e/statistics.spec.ts src/app/router.ts` | 0 problems（本票新增/修改文件不引入 warning） | 0 | `19-web-lint-scoped.log` |
| `pnpm --dir web build` | `vue-tsc -b && vite build` 成功，产物含 `StatisticsView-B98aLaF9.js 9.25 kB`、`StatisticsView-DH8JmuZ7.css 1.99 kB` | 0 | `19-web-build.log` |

warning 数与基线 131 的差异全部来自合入的既有视图（本票作用域 eslint 为 0），未新增告警。

## 3. 统计切片绿测（锁内）

命令：`mvnw.cmd -B -pl server -am -Dtest=StatisticsFlowTest,StatisticsStartupGraceTest -Dsurefire.failIfNoSpecifiedTests=false test`

结果：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，1:26 min，exit code 0；证据 `19-green-statistics.log`。

| 测试方法 | 对应 AC | 关键断言 |
| --- | --- | --- |
| `StatisticsFlowTest.todayMetricsUseShanghaiDayWindowAndDoNotLeakAcrossDays` | AC1、AC3（PENDING/PROCESSING 分列基础） | `timezone=Asia/Shanghai`；`today=2026-06-15`；窗口起点 `2026-06-15T00:00:00+08:00`、终点等于可控时钟当前时刻；昨日 23:59:59 与次日 08:00 的报警均不计入今日（今日 2 条）；未登录 401 |
| `StatisticsFlowTest.handlingRateUsesSameBatchAndReportsNullWhenDenominatorIsZero` | AC2 | 今日批次 4 条（PENDING/PROCESSING/HANDLED/IGNORED 各 1），分子 2、分母 4、`percent=50.0`、`note=含误报`；昨日 HANDLED 不计入；零报警车队分母 0、`percent=null`（页面显示“—”） |
| `StatisticsFlowTest.leaderOnlySeesAuthorizedFleetAndOutOfScopeFleetIsForbidden` | AC6 | 车队长 overview 仅 1 车 1 报警；显式传未授权 `fleetId` 时 overview/trend/types/fleets 四个视图均 403 且 `code=FORBIDDEN`；扩大日期区间后趋势合计仍等于 MySQL 直查的授权车队计数；`fleets` 仅 1 行；MONITOR 与 ADMIN 的全车队总览一致 |
| `StatisticsFlowTest.historicalAlarmsStayWithFleetAtEventWhileVehiclesFollowCurrentArchive` | AC5 | 报警后经 `PUT /vehicles/{id}/fleet` 把车辆换到新队：原队车辆数 0、报警数 1；新队车辆数 1、报警数 0；`/fleets` 聚合行同样分别成立 |
| `StatisticsFlowTest.vehicleStatesAreSeparatedAndOfflineUsesCommittedFacts` | AC4、AC8 | 真实 TCP 注册+鉴权 → online=1；`last_seen_at=now-181s` → offline=1；`now-179s` 且无在途会话 → activeDisconnected=1；未接入、停用、未绑定各 1；startupPending=0；online+offline≠total；total 与 MySQL `COUNT(*)` 一致 |
| `StatisticsFlowTest.trendTypesAndFleetViewsRecomputeFromTheSameTodayBatch` | AC7（同时复验 AC1–AC3） | 同一车队同一批次 6 条报警：overview、trend 当日点、types 单一类型行、fleets 行、fleets 合计逐字段相等（`assertSameMetrics` 覆盖 total/pending/processing/handled/ignored 与处理率分子分母百分比备注）；全车队视图与总览相等，各车队报警合计等于总览；MySQL 直查 total=6、settled=3；两天趋势为 1 与 6 |
| `StatisticsFlowTest.invalidDateRangesAreRejected` | AC6（区间/起始日期篡改） | 起始晚于结束、结束晚于今日、跨度 91 天均 400 且 `code=INVALID_DATE_RANGE`，不返回扩大结果 |
| `StatisticsStartupGraceTest.startupGraceSeparatesPendingConfirmationFromOffline` | AC8 | 独立上下文 `vms.statistics.startup-grace-seconds=180`；曾鉴权且 `last_seen_at=now-600s` 的终端先计入 startupPending=1/offline=0；`clock.advance(181s)` 后 startupPending=0/offline=1 |

## 4. 回归测试（锁内）

命令：`mvnw.cmd -B -pl server -am -Dtest=AlarmHandlingFlowTest,SchemaUpgradeTest,TelemetryFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`

结果：`Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，2:11 min，exit code 0；证据 `19-regression-alarm-schema-telemetry.log`。

| 测试类 | 结果 | 耗时 |
| --- | --- | --- |
| `com.vms.alarm.AlarmHandlingFlowTest` | Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 | 34.35 s |
| `com.vms.identity.SchemaUpgradeTest` | Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 | 35.02 s |
| `com.vms.telemetry.TelemetryFlowTest` | Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 | 49.79 s |

## 5. 逐 AC 结论

| AC | 结论 | 依据 |
| --- | --- | --- |
| 1. 今日指标按 Asia/Shanghai 00:00 至当前计算，跨日数据不串日 | 通过 | `todayMetricsUseShanghaiDayWindowAndDoNotLeakAcrossDays`：时区、日期、窗口起点固定，终点等于可控时钟当前时刻；昨日与次日报警不计入（`19-green-statistics.log`） |
| 2. 处理率同批分母、零分母“—”并标注含误报 | 通过 | `handlingRateUsesSameBatchAndReportsNullWhenDenominatorIsZero`：分子 2/分母 4/50.0/`含误报`，零分母 `percent=null`；前端 `formatRate` 对 `null` 返回 `—`（`web/src/features/statistics/api.ts`，typecheck/build 通过）；e2e 断言零分母行显示 `—`（见第 6 节） |
| 3. PENDING 与 PROCESSING 分列 | 通过 | DTO 字段 `pending`/`processing` 分列，页面 `stats-alarm-pending`/`stats-alarm-processing` 分卡展示；测试与 MySQL 直查逐字段对照（`19-green-statistics.log`） |
| 4. 六种终端状态分离，不强行凑总数 | 通过 | `vehicleStatesAreSeparatedAndOfflineUsesCommittedFacts`：6 类各 1 且 `online+offline≠total`，total 与 MySQL 一致 |
| 5. 车队聚合历史报警按 `fleet_id_at_event`、车辆按当前档案 | 通过 | `historicalAlarmsStayWithFleetAtEventWhileVehiclesFollowCurrentArchive`：换队后原队车辆 0/报警 1，新队车辆 1/报警 0；聚合行一致。静态核查：统计 SQL 仅以 `a.fleet_id_at_event` 过滤报警、`v.fleet_id` 过滤车辆，未出现 `currentOwnership`（`19-static-checks.log`） |
| 6. 车队长仅见授权车队；篡改参数不扩大结果 | 通过 | `leaderOnlySeesAuthorizedFleetAndOutOfScopeFleetIsForbidden`：四个视图越权 `fleetId` 均 403 `FORBIDDEN`，扩大日期区间后合计仍等于 MySQL 授权车队计数，`fleets` 仅授权 1 行；`invalidDateRangesAreRejected`：倒置/未来/超 90 天均 400 `INVALID_DATE_RANGE`；控制器 `@RequiresPermission(FLEET_VIEW)` 且所有过滤经 `FleetScopeSql.where` |
| 7. 趋势、类型、车队三视图与明细可相互复算 | 通过 | `trendTypesAndFleetViewsRecomputeFromTheSameTodayBatch`：同一批次在 overview、trend 当日点、types 类型行、fleets 行与合计逐字段相等，全车队合计等于总览，且与 MySQL 直查 total=6/settled=3 一致；实现上四视图共用 `alarmBuckets`/`vehicleStates` |
| 8. 离线分类以受控时钟/fixture 验证并记录方法 | 通过 | `vehicleStatesAreSeparatedAndOfflineUsesCommittedFacts`（179/181 秒边界、真实 TCP 会话）与 `StatisticsStartupGraceTest`（`clock.advance(181s)` 后 startupPending→offline） |

## 6. 浏览器 e2e（锁内，可选执行）

命令：`mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`

| 轮次 | 结果 | 说明 | 证据 |
| --- | --- | --- | --- |
| 首轮 | BUILD FAILURE，`Tests run: 1, Failures: 1`，Playwright exit 1，`38 passed (3.3m)` | 6 项失败全部为 `ClassNotFoundException: com.vms.simulator.TerminalSimulator`——simulator 模块在 reactor 中排于 server 之后，server 测试执行时 `simulator/target/classes` 尚未生成（环境准备问题，与本票无关）；本票 `statistics.spec.ts` 在此轮已通过（2.7s） | `19-browser-flow-fail1.log`、`19-playwright-fail1.log` |
| 预处理 | BUILD SUCCESS，exit 0 | 先执行 `mvnw.cmd -B -pl simulator -am -DskipTests package` 生成 simulator 产物 | `19-simulator-compile.log` |
| 重跑 | BUILD SUCCESS，`Tests run: 1, Failures: 0, Errors: 0`，4:35 min，exit 0；Playwright `44 passed (3.8m)` | 44 项 = 任务13合入后基线 43 项 + 本票新增 `statistics.spec.ts` 1 项；`statistics.spec.ts` 2.8s 通过，断言六状态分列、车队行零分母显示“—” | `19-browser-flow-rerun1.log`、`19-playwright.log` |

未修改任何既有 spec，未放宽断言。

## 7. 容器与进程回收

- 运行前：存在其他 ticket 的 MySQL/Testcontainers 容器与一个 4 小时前的 `reverent_brahmagupta`（`mysql:8.4`），不属于本轮。
- 运行后 `docker ps`：本轮 Testcontainers MySQL/Redis 与 Ryuk 均已回收，仅剩 `reverent_brahmagupta`；未执行任何 `prune`，未清理他人容器。
- 进程：无本 worktree 遗留的 Maven/simulator/Node 进程；残留 java 进程属于其他 ticket 正在执行的 Maven。

## 8. 未执行项与说明

- 按 ticket 允许，离线边界使用 `MutableClock`/fixture 验证（179/181 秒与 181 秒推进），未执行真实等待 180 秒的用例；生产阈值仍为 180 秒。
- 未新增表、未新增 migration、未扩角色；未改告警判定、轨迹保留或导出实现。
- 本票全部 8 条 AC 均有上述执行证据，无仅静态检查即判通过项；前端三项为构建/类型/规范检查，页面运行行为由浏览器 e2e 补充验证。

## 9. 合并 integration/tickets-08-27 后复验

- 合并提交：`bae0ae1`（`Merge branch 'integration/tickets-08-27' into feat/ticket-19-monitoring-statistics`，无冲突自动合并）。`git diff --stat d0f7fe2..HEAD` 对本票统计相关路径（后端 main/test、前端 features/statistics、e2e spec）无任何差异；合并带入的任务14代码未改变本票实现。
- 迁移口径核实：仓库共 13 个 Flyway 迁移 = 12 个 SQL（V1–V5、V7–V9、V11–V14）+ Java 版 `server/src/main/java/db/migration/V6__durable_position.java`。测试执行日志显示 `Successfully validated 13 migrations`、`Successfully applied 13 migrations ... now at version v14`，与 `SchemaUpgradeTest` 的 13 项断言一致。
- 命令（锁内）：`mvnw.cmd -B -pl server -am -Dtest=StatisticsFlowTest,StatisticsStartupGraceTest,AlarmHandlingFlowTest,SchemaUpgradeTest,TelemetryFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：`Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，4:48 min，exit code 0；证据 `19-postmerge-tests.log`。
- 逐类：`AlarmHandlingFlowTest` 6（70.60 s）、`SchemaUpgradeTest` 2（37.47 s）、`StatisticsFlowTest` 7（44.89 s）、`StatisticsStartupGraceTest` 1（43.07 s）、`TelemetryFlowTest` 10（60.62 s），均 0 失败 0 错误。
- 日志中的 `injected_position_failure`、`ck_fail_outbox_13_9` 约束异常为既有测试故意注入的失败场景，相关测试类均通过。
- 本轮 Testcontainers MySQL/Redis 与 Ryuk 随各测试类 JVM 退出回收，未执行任何 prune；详见交付报告。
