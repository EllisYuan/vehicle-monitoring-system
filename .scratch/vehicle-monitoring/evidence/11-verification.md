# 任务 11 验证记录：30 天保留与分区维护

## 范围

实现 `track_point` 按 Asia/Shanghai 日历日分区的保留与维护：默认保留今天及此前 29 天；启动与每日巡检补齐保留范围内分区、预建未来 7 天并记录结果；清理过期分区并记录范围；缩短保留期先预览、管理员确认后生效并审计；维护失败写可见日志与告警；保留期外上报只记受限异常摘要，不重建过期分区；清理不删除报警、处置、绑定与关联历史、审计。

本记录只覆盖本票范围。报警与处置表尚未在本分支 migration 中出现（由 12/13 票交付），其“不被清理”的保证由清理实现仅执行 `ALTER TABLE track_point DROP PARTITION` 的静态检查给出，见 `11-cleanup-scope.log`。

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
| 路由与导航 | `web/src/app/router.ts`（`/admin/track-retention`）、`AppShell.vue`（`nav-admin-track-retention`） |
| 后端测试 | `server/src/test/java/com/vms/track/TrackRetentionMaintenanceTest.java`（10 项） |
| 升级测试 | `server/src/test/java/com/vms/identity/SchemaUpgradeTest.java`（新增已有分区升级用例） |
| 浏览器测试 | `web/e2e/track-retention.spec.ts` |

## 接口

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/admin/track-retention` | `TRACK_RETENTION_MANAGE` | 当前保留天数、配置版本、业务今天、预建至、最近维护记录与失败告警 |
| `POST /api/v1/admin/track-retention/preview` | 同上 | 提交目标天数，返回 cutoff、待删除分区清单与行数估算；不修改配置、不删除分区 |
| `POST /api/v1/admin/track-retention/confirm` | 同上 | 校验预览 token、30 分钟 TTL 与配置版本后生效并清理，写审计；失败返回 409 并保留失败证据 |

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
2. `ApplicationReadyEvent` 触发 `STARTUP` 维护；`@Scheduled(cron = "${vms.track.daily-cron}")` 触发 `DAILY`；管理员确认触发 `CONFIRM`；测试可用 `MANUAL`。
3. 每次维护先 `policy.refresh()` 读取 `system_config`，随后 `ensureWindow(firstRetained, today+futureDays)` 补齐缺失日分区，再 `dropBefore(firstRetained)` 删除过期日分区，最后写 `track_partition_maintenance`。维护使用 `ReentrantLock.tryLock`，并发触发时跳过并返回失败结果。
4. `ensureWindow` 对窗口末端缺失日在末尾 `ADD PARTITION`；对窗口内部缺失日 `REORGANIZE` 下一个既有分区，保留其数据后插入。
5. 缩短保留期：preview 校验 `[1,365]` 且必须小于当前值，记录 `PREVIEWED`；confirm 行锁读取并校验状态、TTL、配置版本，更新 `system_config`、`policy.refresh()`，执行 `CONFIRM` 维护；成功写 `TRACK_RETENTION` 审计，detail 含 `旧值 -> 新值; dropped=...`；失败同样写 FAILURE 审计并保留 FAILED 状态。
6. 维护异常：`ERROR` 日志记录 trigger 与原因，写 `track_partition_maintenance` FAILED 行与 `track_partition_alert`（`PARTITION_MAINTENANCE_FAILED`），不静默。
7. 保留期外上报：`PositionIngestService.accept` 在通过终端/车辆校验与 `fixValid` 后判断 `isWithinTrackWindow`；不在窗口内时写受限摘要、更新终端 `last_seen_at` 后正常返回，由 `TerminalFrameHandler` 回成功 ACK；不写 `track_point`、不推进实时快照/outbox/participants、不触发分区补齐。
8. 确认失败留痕：`Confirm` 使用 `@Transactional(noRollbackFor = ConflictException.class)`。预览失效、配置版本变化或分区维护失败时，事务仍提交 FAILED/EXPIRED 状态、失败维护记录、告警与 FAILURE 审计，再返回 409；否则这些证据会随事务回滚，管理员只能看到 409 而查不到原因（本切片由续做时的红灯测试发现）。

## 环境

Windows 11、JDK 21（Maven Wrapper）、Docker 29.1.3、Testcontainers `mysql:8.4` 与 `redis:8.4`、Playwright 1.58.1 + Chromium 1208、pnpm 10.16.1、业务时区 Asia/Shanghai。所有 Maven/浏览器验证均经 `with-test-lock.ps1` 全局串行锁执行，未并行运行 Maven 测试；Ryuk 保持启用。

## TDD 红绿灯（按切片）

| 切片 | 红灯 | 绿灯 |
|---|---|---|
| V11 迁移与升级 | `11-red-schema.log`：`expected: 7 but was: 6` | `11-schema-green.log` |
| 启动维护与分区窗口 | `11-red-startup.log`：实现类尚不存在导致编译失败 | `11-startup-green.log`（1 项通过） |
| 维护失败告警 | 启动切片内先红后绿 | `11-failure-alert-green.log`（1 项通过） |
| 保留期外上报 | `11-red-rejection.log`：`EmptyResultDataAccessException`（无摘要行） | `11-rejection-green.log`（1 项通过） |
| 维护、预览、确认、清理与历史保留 | — | `11-server-green.log`：`SchemaUpgradeTest` 2 项 + `TrackRetentionMaintenanceTest` 8 项，共 10 项通过 |
| 确认失败保留失败证据（续做） | `11-red-confirm-failure.log`：`expected: "FAILED" but was: "PREVIEWED"`，失败维护记录/告警/审计随事务回滚 | `11-confirm-failure-green.log`：`TrackRetentionMaintenanceTest` 10 项通过 |

## 命令与结果

### 合入集成分支前

| 时间 | 命令 | 结果 |
|---|---|---|
| 2026-11-16 | 各切片 `mvnw.cmd -B -pl server -am -Dtest=... test` | 见上表 |
| 2026-11-16 | `mvnw.cmd -B -pl server -am test`（合入前全量） | protocol 64 项 + server 103 项（1 skipped 为未开浏览器门控的 BrowserFlowTest），BUILD SUCCESS；`11-server-full.log` |
| 2026-11-16 | `pnpm typecheck` / `lint` / `build` | 全部退出码 0；`11-web-checks.log`、`11-web-checks-retry.log` |
| 2026-11-16 | 清理范围静态检查 | `track` 包无 `DELETE`/`TRUNCATE`/`DROP TABLE`，仅 `TrackPartitionCatalog.java:95` 的 `DROP PARTITION`；`11-cleanup-scope.log` |
| 2026-11-16 | `-Dvms.browser-tests=true -Dtest=TrackRetentionMaintenanceTest,BrowserFlowTest`（合入前） | 首轮 Vite 启动失败（corepack/pnpm 环境）；修复后 `11-premerge-browser-retry.log` 中 Track 9 项通过、Playwright 16/18（2 项前端缺陷）；修复页面后 `11-premerge-browser-retry2.log` 的 surefire 为 Track 9 项、BrowserFlow 1 项通过，`evidence/logs/02-playwright.log` 18 项通过（Maven 控制台输出文件在该轮被截断，以 surefire 报告与 Playwright 日志为准） |

### 合并 `integration/tickets-08-27`（含 16/17/26）后

| 时间 | 命令（均经测试锁） | 结果 | 证据 |
|---|---|---|---|
| 2026-11-16 | `mvnw.cmd -B -pl server -am -Dtest=SchemaUpgradeTest,TrackRetentionMaintenanceTest,TelemetryFlowTest,CommandFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` | 33 项通过：SchemaUpgradeTest 2、TrackRetentionMaintenanceTest 10、TelemetryFlowTest 10、CommandFlowTest 11；BUILD SUCCESS | `11-postmerge-server.log` |
| 2026-11-16 | `pnpm --dir web typecheck` / `lint` / `build` | 退出码均 0（lint 39 条 warning、0 error） | `11-postmerge-web-checks.log` |
| 2026-11-16 | `mvnw.cmd -B -pl simulator -am -DskipTests package` | BUILD SUCCESS | `11-postmerge-simulator-build.log` |
| 2026-11-16 | `mvnw.cmd -B -pl server -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest,TrackRetentionMaintenanceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 11 项通过：BrowserFlowTest 1、TrackRetentionMaintenanceTest 10；BUILD SUCCESS | `11-postmerge-browser.log` |
| 2026-11-16 | Playwright（由 BrowserFlowTest 启动） | 32 项全部通过，含 `track-retention.spec.ts`、`commands.spec.ts`、`basemap-config.spec.ts`、`monitoring-stream.spec.ts` | `evidence/logs/02-playwright.log` |

合并后迁移集合为 V1–V6 + V11 + V14，共 8 个迁移。空库一次应用 8 个、`applied=8`、`versions=8`；已有 V6 分区库升级应用 2 个（V11、V14），37 个日分区与既有轨迹数据保持不变（`11-postmerge-server.log`）。

本轮测试结束核对 Docker：Testcontainers 的 Ryuk、mysql:8.4、redis:8.4 均已自动回收，无残留；未清理开发库与其他项目容器。

## 验收项对照

| Acceptance criterion | 结果 | 证据 |
|---|---|---|
| 复用初始迁移后，保留范围内历史日分区和未来分区齐备，空库与已有数据升级均可验证 | 通过 | `SchemaUpgradeTest` 2 项：空库 8 个迁移；已有 V6 库 37 个分区与轨迹数据升级后不变。`TrackRetentionMaintenanceTest` Order 1（启动后今天至前 29 天、未来 7 天齐备）、Order 9（区间内缺口补齐） |
| 启动与每日巡检预建未来 7 天，记录巡检结果 | 通过 | Order 1（STARTUP 记录与分区清单）、Order 7（重复 STARTUP 幂等 + DAILY 记录 SUCCESS、retention_days=10、未来 7 天分区存在） |
| 过期分区清理限定 Shanghai 今天+29 之外，记录删除范围 | 通过 | Order 2：today-40 被删除、today-29 保留，维护记录含 dropped 分区；Order 5：30→10 后删除 today-20 与 today-29，保留 today-9 与 today |
| 缩短保留期返回预览，未确认不删除任何分区 | 通过 | Order 4：preview 只写 PREVIEWED 记录，配置仍为 30、分区未减少；浏览器场景预览后仍显示 30 |
| 确认后生效，审计记录操作者、旧值、新值与结果 | 通过 | Order 5：`system_config` 值 10、version=2、`audit_log` 的 actor/result/target 与 detail `30 -> 10`、change 状态 CONFIRMED；Order 10：失败确认写 FAILURE 审计（actor、`10 -> 5`、失败原因）；浏览器场景确认后显示 7 并出现 CONFIRM 维护记录 |
| 维护失败写可见日志与告警记录，不静默 | 通过 | Order 3：RENAME 表制造失败后维护记录 FAILED、`track_partition_alert` 出现 `PARTITION_MAINTENANCE_FAILED`、ERROR 日志含原因；Order 10：确认路径失败后同样保留 FAILED change、FAILED 维护记录、告警与失败审计，overview 接口可见 |
| 保留期外上报记受限异常摘要，不重建分区 | 通过 | Order 6：ACK result=0；`track_retention_rejection` 含 reason/payload_hash/body_length；无 `track_point`、无 `vehicle_last_position`；today-31 分区未创建 |
| 清理不删除报警、处置、绑定与关联历史、审计 | 通过（报警/处置表未落地，按静态检查） | Order 8：MANUAL 维护前后 `audit_log`、`terminal_vehicle_binding`、`vehicle_fleet_history`、`track_retention_change` 行数不变、维护记录 +1；`11-cleanup-scope.log` 证明 track 包只有 `DROP PARTITION`，无 DELETE/TRUNCATE/DROP TABLE |
| 真实 MySQL 验证分区边界、清理与重启行为 | 通过 | 所有后端测试运行于 Testcontainers `mysql:8.4`；Order 1 启动、Order 7 重启幂等、Order 9 `REORGANIZE` 中间缺口；`SchemaUpgradeTest` 从 V6 升级到最新 |

## 已知偏差与边界

- 报警、处置表尚未在本分支 migration 中出现（由 12/13 票交付）；本票的“不清理”保证由清理实现只执行 `ALTER TABLE track_point DROP PARTITION` 的静态检查，以及绑定/历史/审计行数对照给出。报警表落地后应由对应票复验其不被本维护删除。
- 30 天真实规模容量（AC33/25 票）不在本票范围；本票验证的是分区边界、清理与重启行为，不给出容量或性能结论。
- 合入前浏览器首轮与第二轮失败分别来自 corepack/pnpm 环境与页面组件注册缺陷；后者已修复，最终以合并后的 `11-postmerge-browser.log` 与 32 项 Playwright 结果为准，未把失败运行记为通过。
- 页面“维护失败告警/最近维护记录”列表的渲染由浏览器成功场景与 overview 接口测试共同覆盖；未在浏览器中制造数据库级的维护失败（失败可见性由后端集成测试验证）。
- 本记录不修改 `spec.md`、其他 ticket 或 README 进度。