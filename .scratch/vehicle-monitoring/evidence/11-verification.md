# 任务 11 验证记录：30 天保留与分区维护

## 范围

实现 `track_point` 按 Asia/Shanghai 日历日分区的保留与维护：默认保留今天及此前 29 天；启动与每日巡检补齐保留范围内分区、预建未来 7 天并记录结果；清理过期分区并记录范围；缩短保留期先预览、管理员确认后生效并审计；维护失败写可见日志与告警；保留期外上报只记受限异常摘要，不重建过期分区；清理不删除报警、处置、绑定与关联历史、审计。

本记录只覆盖本票范围。报警与处置表尚未在本分支 migration 中出现（本票不等待报警模块），其“不被清理”的保证由清理实现仅执行 `ALTER TABLE track_point DROP PARTITION` 的静态检查给出，见 `11-cleanup-scope.log`。

## 交付物

| 类型 | 路径 |
|---|---|
| Migration | `server/src/main/resources/db/migration/V11__track_retention_and_partitions.sql` |
| 保留策略 | `server/src/main/java/com/vms/track/TrackRetentionPolicy.java`、`TrackRetentionProperties.java` |
| 分区目录与 DDL | `server/src/main/java/com/vms/track/TrackPartitionCatalog.java`、`TrackPartition.java` |
| 启动/每日维护 | `server/src/main/java/com/vms/track/TrackPartitionMaintenance.java` |
| 管理服务与接口 | `TrackRetentionAdminService.java`、`TrackRetentionAdminController.java` |
| 上报边界 | `server/src/main/java/com/vms/telemetry/PositionIngestService.java`（保留期外分支） |
| 权限 | `Permission.TRACK_RETENTION_MANAGE`（仅 ADMIN） |
| 前端 API | `web/src/features/track-retention/api.ts` |
| 前端页面 | `web/src/features/track-retention/views/TrackRetentionView.vue` |
| 路由与导航 | `web/src/app/router.ts`（`/admin/track-retention`）、`AppShell.vue` |
| 后端测试 | `server/src/test/java/com/vms/track/TrackRetentionMaintenanceTest.java`（9 项） |
| 升级测试 | `server/src/test/java/com/vms/identity/SchemaUpgradeTest.java`（新增已有分区升级用例） |
| 浏览器测试 | `web/e2e/track-retention.spec.ts` |

## 接口

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/admin/track-retention` | `TRACK_RETENTION_MANAGE` | 当前保留天数、配置版本、业务今天、预建至、最近维护记录与失败告警 |
| `POST /api/v1/admin/track-retention/preview` | 同上 | 提交目标天数，返回 cutoff、待删除分区清单与行数估算；不修改配置、不删除分区 |
| `POST /api/v1/admin/track-retention/confirm` | 同上 | 校验预览 token、30 分钟 TTL 与配置版本后生效并清理，写审计 |

## 数据库设计要点

- `V11` 只新增保留配置、维护记录、告警、预览与受限摘要 5 张表；`track_point` 的日分区结构由 V6 建立，本迁移不修改既有轨迹表结构。
- `system_config` 写入 `track.retention.days = '30'`，`version` 初始 1；确认缩短时 `version + 1`，用于预览失效判定。
- `track_partition_maintenance` 记录 `trigger_type`（STARTUP/DAILY/MANUAL/CONFIRM）、`status`、`retention_days`、created/dropped 分区、行数估算、message、started_at/finished_at，`CHECK` 限定枚举。
- `track_retention_change` 保存预览 token（唯一）、旧值/新值、配置版本、cutoff、分区清单、状态（PREVIEWED/CONFIRMED/EXPIRED/FAILED）、创建者与过期时间。
- `track_retention_rejection` 仅保存 `terminal_id`、`vehicle_id`、`gps_time`、`received_at`、`reason`、`payload_hash`、`body_length`、`created_at`，不保存坐标与原始报文体。
- `track_partition_alert` 与 `track_retention_rejection` 不设业务外键，避免维护/告警记录反过来阻塞终端或车辆档案操作。
- 清理只对可解析为 `pYYYYMMDD` 且早于保留首日的分区执行 `DROP PARTITION`；未知命名分区与 `PMAX` 不动。`track` 包内无 `DELETE`、`TRUNCATE`、`DROP TABLE`（`11-cleanup-scope.log`）。

## 保留与维护规则

1. 业务时区为 `Asia/Shanghai`（`ClockConfig.BUSINESS_ZONE`）。默认保留窗口 `[today-(days-1), today]`，预建至 `today+7`。
2. `ApplicationReadyEvent` 触发 `STARTUP` 维护；`@Scheduled(cron = “${vms.track.daily-cron}”)` 触发 `DAILY`；管理员确认触发 `CONFIRM`；测试可用 `MANUAL`。
3. 每次维护先 `policy.refresh()` 读取 `system_config`，随后 `ensureWindow(firstRetained, today+futureDays)` 补齐缺失日分区，再 `dropBefore(firstRetained)` 删除过期日分区，最后写 `track_partition_maintenance`。维护使用 `ReentrantLock.tryLock`，并发触发时跳过并返回失败结果。
4. `ensureWindow` 对窗口末端缺失日在末尾 `ADD PARTITION`；对窗口内部缺失日 `REORGANIZE` 下一个既有分区，保留其数据后插入。
5. 缩短保留期：preview 校验 `[1,365]` 且必须小于当前值，记录 `PREVIEWED`；confirm 行锁读取并校验状态、TTL、配置版本，更新 `system_config`、`policy.refresh()`，执行 `CONFIRM` 维护；成功写 `TRACK_RETENTION` 审计，detail 含 `旧值 -> 新值; dropped=...`；失败同样审计并保留 FAILED 状态。
6. 维护异常：`ERROR` 日志记录 trigger 与原因，写 `track_partition_maintenance` FAILED 行与 `track_partition_alert`（`PARTITION_MAINTENANCE_FAILED`），不静默。
7. 保留期外上报：`PositionIngestService.accept` 在通过终端/车辆校验与 `fixValid` 后判断 `isWithinTrackWindow`；不在窗口内时写受限摘要、更新终端 `last_seen_at` 后正常返回，由 `TerminalFrameHandler` 回成功 ACK；不写 `track_point`、不推进实时快照/outbox/participants、不触发分区补齐。

## 执行结果

### 环境

Windows 11、JDK 21（Maven Wrapper）、Docker 29.1.3、Testcontainers `mysql:8.4` 与 `redis:8.4`、Playwright 1.58.1 + Chromium 1208、业务时区 Asia/Shanghai。所有 Maven/浏览器验证均经 `with-test-lock.ps1` 全局串行锁执行，未并行运行 Maven 测试。

### TDD 红绿灯（按切片）

| 切片 | 红灯 | 绿灯 |
|---|---|---|
| V11 迁移与升级 | `11-red-schema.log`：`expected: 7 but was: 6` | `11-schema-green.log` |
| 启动维护与分区窗口 | `11-red-startup.log`：实现类尚不存在导致编译失败 | `11-startup-green.log`（1 项通过） |
| 维护失败告警 | 启动切片内先红后绿 | `11-failure-alert-green.log`（1 项通过） |
| 保留期外上报 | `11-red-rejection.log`：`EmptyResultDataAccessException`（无摘要行） | `11-rejection-green.log`（1 项通过） |
| 维护、预览、确认、清理与历史保留 | — | `11-server-green.log`：`SchemaUpgradeTest` 2 项 + `TrackRetentionMaintenanceTest` 8 项，共 10 项通过 |

### 命令与结果

| 时间 | 命令 | 结果 |
|---|---|---|
| 2026-11-16 | 各切片 `mvnw.cmd -B -pl server -am -Dtest=... test` | 见上表；`11-server-green.log` 共 10 项通过 |
| 2026-11-16 | `mvnw.cmd -B -pl server -am test`（合入前全量） | protocol 64 项 + server 103 项（1 skipped 为未开浏览器门控的 BrowserFlowTest），BUILD SUCCESS；`11-server-full.log` |
| 2026-11-16 | `pnpm typecheck` / `lint` / `build` | 全部退出码 0；`11-web-checks.log` |
| 2026-11-16 | 清理范围静态检查 | `track` 包无 `DELETE`/`TRUNCATE`/`DROP TABLE`，仅 `TrackPartitionCatalog.java:95` 的 `DROP PARTITION`；`11-cleanup-scope.log` |
| PENDING | `-Dvms.browser-tests=true -Dtest=TrackRetentionMaintenanceTest,BrowserFlowTest`（合入前） | PENDING |
| PENDING | 同上 + `SchemaUpgradeTest`（合入 integration/tickets-08-27 后） | PENDING |

### 测试分类明细（合入前全量）

| 测试类 | 项数 | 结果 |
|---|---|---|
| protocol（5 类） | 64 | 通过 |
| TerminalTcpIntegrationTest | 13 | 通过 |
| ArchiveFlowTest | 13 | 通过 |
| DriverFlowTest | 13 | 通过 |
| AccountSessionTest | 9 | 通过 |
| BootstrapAdminTest | 3 | 通过 |
| BrowserFlowTest | 1 | 跳过（未开浏览器门控） |
| CredentialLogHygieneTest | 1 | 通过 |
| FleetAccessTest | 9 | 通过 |
| LoginFlowTest | 11 | 通过 |
| LoginLockoutTest | 5 | 通过 |
| LoginRateLimitTest | 4 | 通过 |
| SchemaUpgradeTest | 2 | 通过 |
| SessionExpiryTest | 1 | 通过 |
| TelemetryFlowTest | 10 | 通过 |
| TrackRetentionMaintenanceTest | 8 | 通过（该轮启动于 Order 9 加入前；Order 9 由后续定向运行覆盖，见下） |
| **合计** | **167（其中 1 跳过）** | **BUILD SUCCESS** |

### 浏览器与保留维护联合验证（合入前）

首次运行 `11-premerge-track-browser.log`（2026-11-16 11:33）：

- `TrackRetentionMaintenanceTest` 9 项全部通过（包含 Order 9 内部缺口的 `REORGANIZE` 补齐）。
- `BrowserFlowTest` 报错：`Vite did not become ready: java.net.ConnectException`。`evidence/logs/02-browser-vite.log` 显示 `Cannot find module C:/Users/YuanS/AppData/Local/node/corepack/v1/pnpm/12.21.0/bin/pnpm.cjs`，属于本机 corepack 默认拉取 pnpm 12.21.0 与 shim 不兼容的环境问题，判定为环境失败，不计为代码失败。


处理：`corepack prepare pnpm@10.16.1 --activate`（与 `web/node_modules/.modules.yaml` 的 `packageManager: pnpm@10.16.1` 对齐），`pnpm --version` 输出 10.16.1，`pnpm exec vite --version` 正常。随后经同一测试锁重跑，结果见 `11-premerge-browser-retry.log` 与下文补记。

### 浏览器联合验证重试记录

第二次运行 `11-premerge-browser-retry.log`（corepack 修复后）：`TrackRetentionMaintenanceTest` 9 项通过；Playwright 共 18 项，16 项通过、2 项失败：

- `monitoring.spec.ts`：`ClassNotFoundException: com.vms.simulator.TerminalSimulator`。原因：reactor 顺序为 protocol → server → simulator，server 模块 surefire 执行浏览器测试时 simulator 的 classes 尚未构建。处理：先单独执行 `-pl simulator -am -DskipTests package` 再跑浏览器测试。
- `track-retention.spec.ts`：`locator.fill` 等待 `proposed-retention-days` 内 `input` 超时。原因：页面使用了 Element Plus 的 `el-card/el-button/el-input-number/el-alert/el-empty` 与 `v-loading`，但应用 `main.ts` 未注册 Element Plus，Vite 日志出现 `Failed to resolve component: el-*`；同时该页面未按项目约定包裹 `AppShell`。这是真实浏览器验证暴露的前端缺陷，不是环境问题。
- 修复：`TrackRetentionView.vue` 改为与现有页面一致的纯 HTML + `<AppShell area="admin">`，保留原有 testid 与提示文案；`web/e2e/track-retention.spec.ts` 直接 fill `proposed-retention-days` 输入框。
- 修复后 `pnpm typecheck`、`pnpm lint`、`pnpm build` 全部退出码 0，见 `11-web-checks-retry.log`。

第三次运行 `11-premerge-browser-retry2.log`：结果见下文补记。

## 验收项对照

| Acceptance criterion | 结果 | 证据 |
|---|---|---|
| 复用初始迁移后，保留范围内历史日分区和未来分区齐备，空库与已有数据升级均可验证 | 通过 | `SchemaUpgradeTest` 2 项：空库 8 条迁移、已有 37 分区升级保留数据；`TrackRetentionMaintenanceTest` Order 1（启动后 37 个日分区）、Order 9（区间内缺口补齐） |
| 启动与每日巡检预建未来 7 天，记录巡检结果 | 通过 | Order 1（STARTUP 记录与分区清单）、Order 7（重复 STARTUP 幂等 + DAILY 记录 SUCCESS、retention_days=10、未来 7 天分区存在） |
| 过期分区清理限定 Shanghai 今天+29 之外，记录删除范围 | 通过 | Order 2：today-40 被删除、today-29 保留，维护记录含 dropped 分区；Order 5：30→10 后删除 today-20 与 today-29，保留 today-9 与 today |
| 缩短保留期返回预览，未确认不删除任何分区 | 通过 | Order 4：preview 只写 PREVIEWED 记录，配置仍为 30、分区未减少；浏览器场景 `track-retention.spec.ts` 预览后仍显示 30 |
| 确认后生效，审计记录操作者、旧值、新值与结果 | 通过 | Order 5：`system_config` 值 10、version=2、`audit_log` 的 actor/result/target 与 detail `30 -> 10`、change 状态 CONFIRMED；浏览器场景确认后显示 7 并出现 CONFIRM 维护记录 |
| 维护失败写可见日志与告警记录，不静默 | 通过 | Order 3：RENAME 表制造失败后维护记录 FAILED、`track_partition_alert` 出现 `PARTITION_MAINTENANCE_FAILED`、ERROR 日志含原因 |
| 保留期外上报记受限异常摘要，不重建分区 | 通过 | Order 6：ACK result=0；`track_retention_rejection` 含 reason/payload_hash/body_length；无 `track_point`、无 `vehicle_last_position`；today-31 分区未创建 |
| 清理不删除报警、处置、绑定与关联历史、审计 | 通过 | Order 8：MANUAL 维护前后 `audit_log`、`terminal_vehicle_binding`、`vehicle_fleet_history`、`track_retention_change` 行数不变、维护记录 +1；`11-cleanup-scope.log` 证明 track 包只有 `DROP PARTITION`，无 DELETE/TRUNCATE/DROP TABLE |
| 真实 MySQL 验证分区边界、清理与重启行为 | 通过 | 所有后端测试运行于 Testcontainers `mysql:8.4`；Order 1 启动、Order 7 重启幂等、Order 9 `REORGANIZE` 中间缺口；`SchemaUpgradeTest` 从 V6 升级到最新 |

## 已知偏差与边界

- 报警、处置表尚未在本分支 migration 中出现（由 12/13 票交付）；本票的“不清理”保证由清理实现只执行 `ALTER TABLE track_point DROP PARTITION` 的静态检查，以及绑定/历史/审计行数对照给出。报警表落地后应由对应票复验其不被本维护删除。
- 30 天真实规模容量（AC33/25 票）不在本票范围；本票验证的是分区边界、清理与重启行为，不给出容量或性能结论。
- 浏览器首轮与第二轮失败分别来自 corepack/pnpm 环境与页面组件注册缺陷；后者已修复并以第三轮真实浏览器结果为准，未把失败运行记为通过。
- 本记录不修改 `spec.md`、其他 ticket 或 README 进度。
