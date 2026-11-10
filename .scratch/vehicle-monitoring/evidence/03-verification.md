# 任务 03 实施与验证记录

2026-11-10：实现、真实 MySQL 集成测试、前端静态检查、真实浏览器场景均已执行。最终全量回归结果以本文末节为准；ticket 状态与验收勾选按该结果更新。

## 交付内容

- `V4__vehicle_terminal_history.sql`：`vehicle`、`terminal`、`terminal_vehicle_binding`、`vehicle_fleet_history`。当前一对一由仅在 `effective_to IS NULL` 时非 NULL 的 generated columns（`current_terminal_id`、`current_vehicle_id`）加唯一约束保证；历史表有引用外键与非零区间 CHECK。
- `fleet/VehicleService`、`VehicleController`：车辆登记、车牌与启停维护、按车队筛选、详情、转队与车队归属历史。
- `terminal/TerminalService`、`TerminalController`、`TerminalBindingController`：20 位 `terminal_no` 预登记与启停；绑定/替换/解绑与绑定历史端点留在 `/api/v1/vehicles/{id}/terminal`、`/terminal-bindings`。`auth_secret_hash` 预登记时为 NULL，鉴权材料由任务 06 的注册流程生成；响应不含该字段。
- `identity/access/FleetScopeSql`、`infrastructure/EffectiveTime`：数据范围转 SQL 与生效区间口径的唯一入口。
- 前端 `features/archives`：档案页接入 `/admin/archives`（仅 ADMIN）与 `/monitor/archives`（三角色）；登记、启停、车牌编辑、绑定/解绑、转队、历史查看。`FleetListView` 增加名称编辑。

## 事务、并发与区间

- 绑定事务 `READ_COMMITTED`；先按 `terminal.id` 升序锁相关终端行，再锁 `vehicle` 行；锁内重读当前引用，变更时关闭旧区间并插入新区间。generated columns 唯一约束作为当前一对一兜底。
- 生效时间由服务端 Asia/Shanghai 业务 Clock 生成并截断至 `DATETIME(3)`；同毫秒、时钟回拨或早于档案创建时间时返回 409 `EFFECTIVE_TIME_NOT_ADVANCED`；不接受客户端提交历史生效时间。
- 转队锁 `vehicle`，关闭 `vehicle_fleet_history` 旧区间、写新区间、更新 `vehicle.fleet_id`，并在同一事务写审计。重复转入当前车队为幂等（不因该队当前停用而报错）。
- 停用保留档案与全部历史，无物理删除 API。

## 权限

- 写端点（车辆、终端、绑定、转队）使用 `Permission.FLEET_MANAGE`，仅 ADMIN。
- 读端点使用 `FLEET_VIEW` + `FleetScope`：监控员全企业；车队长仅授权车队当前车辆及其当前绑定终端，不可浏览未绑定终端。
- `vehicle_fleet_history` 可读但按历史 `fleet_id` 过滤，车队长只看到自己授权车队的区间。
- `terminal-bindings` 完整历史含转队前其他车队终端，属未授权历史（AC06），仅 ADMIN/MONITOR 可读；车队长返回 403，页面对其不发起该请求、只展示车队归属历史。
- 实现、测试与设计稿在此口径上一致。

## 执行记录

环境：Windows 11、JDK 21.0.9、Maven Wrapper 3.9.12、Docker 29.1.3、MySQL 8.4 与 Redis 8.4 Testcontainers、业务时钟 Asia/Shanghai、Chromium 1234。

| 时间 | 命令 | 结果 |
|---|---|---|
| 2026-11-10 09:29 | `mvn` 全量 test + Playwright（并行会话执行，见 03-test-run.log） | ArchiveFlowTest 10 项通过；layouts/login 场景通过 |
| 2026-11-10 10:05 | `mvnw.cmd -B -pl server -Dtest=ArchiveFlowTest test` | 12 项通过，30.57s |
| 2026-11-10 10:06 | `mvnw.cmd -B -pl server -Dtest=SchemaUpgradeTest test` | 1 项通过；4 个 migration 空库应用并可重复执行 |
| 2026-11-10 10:07 | `pnpm --dir web typecheck` / `lint` / `build` | 均 exit 0 |
| 2026-11-10 10:09–10:20 | `mvnw.cmd -B -Dvms.browser-tests=true test` | 57 项通过，failures/errors/skipped 均 0，BUILD SUCCESS；BrowserFlowTest 内 14 个 Chromium 场景通过（含 archives.spec.ts 2 项） |
| 2026-11-10 10:2x | 同上（审阅修复后，最终回归） | 58 项通过，failures/errors/skipped 均 0，BUILD SUCCESS；BrowserFlowTest 内 14 个 Chromium 场景通过 |

审阅前的一次全量回归中，`-Dvms.browser-tests=true` 未加引号被 PowerShell 拆成两个参数，以 `Unknown lifecycle phase` 失败；属命令引号问题，加引号后重跑成功。

浏览器截图 `web/test-results/archive-history.png` 已目视检查：换绑区间与转队区间左闭右开、首尾相接，停用后档案与历史保留，管理员操作按钮按权限渲染。

## 两轴审阅与修复

基线与范围：工作区相对 `ddb306d`，含未跟踪新增文件。

Standards 轴发现并已修复：

- 文档口径冲突（车队长绑定历史、ticket 状态、验证记录自相矛盾）——统一为「仅 ADMIN/MONITOR 可读完整绑定历史」并改写本文与 ticket。
- 审计分类硬编码 `"VEHICLE"`/`"TERMINAL"` → 增加 `AuditService.CATEGORY_VEHICLE`、`CATEGORY_TERMINAL`。
- `TerminalService.lockTerminal` 注释称避免重复查询但又重查状态 → 改为一次锁定返回 `TerminalSnapshot(status, createdAt)`。
- 前端 `const canReadHistory = true` 死条件 → 删除；`AccountStatus` 命名错位 → 统一使用 `shared/status.ts` 的 `EnabledStatus`。

Spec 轴发现并已修复：

- 车队长可读完整绑定历史，违反 AC06「新队不能读未授权历史」→ 恢复为 ADMIN/MONITOR 限定，并补充测试断言 403。
- 转队幂等缺陷：重复转入当前车队且该队已停用会返回 409 → 先判同队再校验车队状态，新增回归测试。

Spec 轴确认成立、未改动：generated column 唯一约束与锁顺序无重叠/绕过路径；审计与业务同事务；三类读端点按 FleetScope 过滤、写端点严格限 ADMIN。

未采纳并说明理由：

- `fleetCode` 不可编辑：编号作为稳定业务标识与审计对象，创建后固定；US13 的「维护」由创建时的编号与后续名称、启停覆盖。如需改编号应作为独立决策。
- `FLEET_DISABLED`、`VEHICLE_DISABLED` 拒绝：AC 明文只要求终端不可用不能绑定；车辆与车队停用同样不应新建关联，属工程默认，已在设计稿记录。
- `VehicleService.update` 重复提交相同值仍写审计、`TerminalService.bind` 审计 target 使用 `vehicle:<id>`（category 为 TERMINAL）：均为可追溯事实，分类即表示操作来源；不在本票改动审计查询口径（21）。

## 未执行与范围边界

- 未在本票验证 TCP 换绑后的会话处置（06）与补传归属（09）。
- 司机档案与关联（04）、审计查询界面（21）不在本票。
- 未修改原始 Word，未填写论文测试通过或性能数字。

## 最终全量回归

2026-11-10 审阅修复后执行 `mvnw.cmd -B "-Dvms.browser-tests=true" test`：`Tests run: 58, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（logs/03-final-regression.log）。明细：ArchiveFlowTest 13、AccountSessionTest 9、BootstrapAdminTest 3、BrowserFlowTest 1、CredentialLogHygieneTest 1、FleetAccessTest 9、LoginFlowTest 11、LoginLockoutTest 5、LoginRateLimitTest 4、SchemaUpgradeTest 1、SessionExpiryTest 1。BrowserFlowTest 内 14 个 Chromium 场景全部通过。结束后 `docker ps -a` 无 testcontainers/ryuk 残留。
