# 任务 25 验证记录：30 天量级轨迹容量验收

状态：**Phase A（就绪）已完成；满规模生成、查询、清理与 AC7 复验均未执行**。本文件中所有“待回填”项不得视为通过；AC33 未执行，不得宣称通过。估算值均已明确标注为“估算”，不得写成实测。

## 基线与范围

| 项目 | 值 |
|---|---|
| 分支 | `feat/ticket-25-capacity` |
| Phase A 合并前 HEAD | `45e04c77256b6bb3b4527550f4787213ee3ab5d8`（任务 25 harness 骨架与冒烟证据） |
| 合入集成分支 | `4a38b9827c0f0e7890a8daf84379898174815b7d`（`integration/tickets-08-27`，含任务 14/18/19/20/23） |
| 合并提交 | `a481a9fdcd8d69c54431b41ed2856873ae8cc57a`（`--no-ff`，ort 自动合并，无冲突） |
| Phase A 提交 | 本文件所在提交（`git log --oneline -1`） |
| 容量目标 | 100 终端 × 8640 点/日 × 30 日 = **25,920,000** retained rows（AC33） |
| 附加过期数据 | 3 个自然日 × 100 终端 × 8640 点 = **2,592,000** rows，仅用于清理计量 |
| 总生成量 | **28,512,000** rows / 33 个自然日 |

范围依据：ticket `25-thirty-day-track-capacity.md`、spec AC33/US52/US54、`docs/design/系统设计方案.md` 第 11 节、ADR `0006-track-retention.md`。保留规则、分区维护、失败告警以任务 11 的实现与验证记录（`11-verification.md`）为准，本票不重做。

## 证据边界

- 上一轮冒烟 `.scratch/vehicle-monitoring/evidence/25-capacity-metrics.log` 的参数为 `retainedDays=30, expiredDays=3, terminals=2, pointsPerDay=100, batchSize=50`，实际 6600 行（retained 6000），日志自身标记 `capacity.scale=非验收规模`。它只证明 harness 自洽与流程可跑，**不是 AC33 证据，不得据此宣称容量通过，也不得外推 2592 万行**。
- 本文件只记录实际执行的事实；未执行项一律写“待执行/待回填”。
- 模拟数据只写入 harness 自建的 Testcontainers MySQL 容器（独立车队/车辆/终端标识），不进入正式 migration、初始化脚本或开发库。

## Phase A 已完成（本轮）

### 1. 合并基线与冲突

- `git merge --no-ff integration/tickets-08-27` 自动合并成功，无冲突，无手工语义取舍。
- `SchemaUpgradeTest` 合并后采用集成分支断言：13 个 migration（12 个 SQL + Java migration `V6__durable_position`），表清单含 `alarm*`、`geofence*`、`command_log`，清单与合并后实际 schema 一致。
- 合并只引入集成分支内容；任务 25 自身提交未改动 `server/src/main/resources/db/migration`、`server/src/main/java/db/migration` 或产品代码（`git diff --stat 45e04c7^..45e04c7 -- server/src/main/resources server/src/main/java` 为空）。

### 2. 编译与 test 编译（已执行）

| 命令（均经 `with-test-lock.ps1` 全局串行锁） | 结果 | 日志 |
|---|---|---|
| `mvnw.cmd -B -DskipTests package` | exit 0，`BUILD SUCCESS`；server 编译 105 个 main 源文件、35 个 test 源文件（含 `TrackCapacityHarnessTest`） | `.scratch/vehicle-monitoring/evidence/25-phaseA-package.log` |
| `mvnw.cmd -B -pl server -DskipTests test-compile`（首次，未带 `-am`） | exit 1，`BUILD FAILURE`：`com.vms:protocol:jar:0.1.0-SNAPSHOT` 未安装到本地仓库，server 模块无法解析模块间依赖。属构建命令问题，非源码问题；该次日志已被下一条命令覆盖 | （未保存，见本行说明） |
| `mvnw.cmd -B -pl server -am -DskipTests test-compile` | exit 0，`BUILD SUCCESS`（6.6 s）；本次为增量编译，输出 `Nothing to compile - all classes are up to date`，因为上一条 `package` 已完成全量 main/test 编译；`TrackCapacityHarnessTest` 的实际编译证据以上一行 `package`（35 个 test 源文件）为准 | `.scratch/vehicle-monitoring/evidence/25-phaseA-test-compile.log` |

本轮未启动 Testcontainers/MySQL，未运行任何测试方法，未生成数据。

### 3. Harness 静态校准结论（未改产品代码，未改 harness 代码）

| 校准项 | 结论 |
|---|---|
| 构造器与类型 | `TrackRetentionPolicy(JdbcTemplate, Clock, TrackRetentionProperties)`、`TrackRetentionProperties` 5 个 null 采用默认值 30/7/1/365、`TrackPartitionCatalog(JdbcTemplate)`、`TrackPartitionMaintenance(JdbcTemplate, TrackPartitionCatalog, TrackRetentionPolicy, Clock)`、`AuditService(JdbcTemplate, Clock)`、`TrackQueryService(JdbcTemplate, FleetScopeResolver, TrackRetentionPolicy, TrackPartitionCatalog, AuditService)` 全部与合并后源码一致；`MaintenanceResult`、`TrackView` 访问器一致 |
| `track_point` 列 | harness INSERT 的 15 列与 `V6__durable_position` 建表一致；`V8/V9/V12/V13` 均未 `ALTER TABLE track_point` |
| 档案字段 | `terminal.last_logout_at` 为 V12 新增 nullable 列，不影响 harness INSERT；`terminal_no` 的 `ck_terminal_no CHECK (terminal_no REGEXP '^[0-9]{20}$')` 与 harness 的 `99` + 18 位数字一致；`vehicle`、`fleet`、`sys_user`、`terminal_vehicle_binding`、`vehicle_fleet_history` 的 INSERT 列均有效 |
| 保留配置 | `V11` seed `system_config.track.retention.days='30'`；harness 断言 `retentionDays()` 与 `vms.capacity.days` 相等，一致 |
| 分区窗口 | 维护逻辑 `ensureWindow(today-29, today+7)` + `dropBefore(today-29)`；harness 预建过期日 `today-32..today-30`，期望生成后 40 个分区（30 retained + 7 future + 3 expired），清理后 37 个，与代码一致 |
| 批量 INSERT | `pointsPerDay=8640`、`batchSize=2000` 时每终端每日 5 批（2000×4 + 640）；单行 SQL 约 238 字节（冒烟 `sql_bytes=11913/50`），最大单条 INSERT 约 476 KB。Connector/J 9.6 在未显式设置 `maxAllowedPacket` 时使用服务端 `max_allowed_packet`（源码核对 `NativeProtocol`），mysql:8.4 默认 64 MB，远大于 476 KB；运行时应记录 `SELECT @@max_allowed_packet` 作为实测依据 |
| 查询限制 | `MAX_WINDOW_SECONDS=24*3600`（单日 00:00:00–23:59:59 合法；跨日 20:00–次日 04:00 合法）、`MAX_DISPLAY_POINTS=2000`；单日期望 raw 8640，跨日期望 raw 2881，display 均 ≤2000 |
| 结论 | 未发现阻断性缺陷；本轮不需要修改 harness 或产品代码 |

## 满规模运行参数（待执行）

```text
mvnw.cmd -B -pl server -am -Dvms.capacity=true -Dtest=TrackCapacityHarnessTest ^
  -Dsurefire.failIfNoSpecifiedTests=false ^
  -Dvms.capacity.days=30 -Dvms.capacity.expired-days=3 ^
  -Dvms.capacity.terminals=100 -Dvms.capacity.points-per-day=8640 ^
  -Dvms.capacity.batch=2000 -Dvms.capacity.generate=true test
```

（Windows shell 下 `^` 为续行；也可单行执行。必须经 `with-test-lock.ps1` 全局串行锁包装，锁等待上限 120 分钟。）

批次划分（计划）：

| 项 | 值 |
|---|---|
| 每终端每日批数 | 5（2000 + 2000 + 2000 + 2000 + 640） |
| 自然日数 | 33（30 retained + 3 expired） |
| 终端数 | 100 |
| 总批数 | 16,500 |
| 单批最大行数 | 2000 |
| 单批最大 SQL 约 | 476 KB（按冒烟 238 B/行推导） |

运行前置条件（待协调者确认后执行）：

1. ticket 22 验收结束，获得独占窗口；不与其他 Maven/Testcontainers/重 IO 任务并行。
2. Docker Desktop 运行中；`mysql:8.4`（1.12 GB）已在本地镜像缓存，无需拉取；Ryuk 保持启用（未设置 `TESTCONTAINERS_RYUK_DISABLED`）。不得清理他人容器或执行任何 prune。
3. C: 可用空间：启动时 ≥22 GB，运行中低于 18 GB 立即停手报告（硬边界 15 GB）。**2026-11-17 实测 C: 41.8 GB 可用 / 475.5 GB（[待协调者确认] 与派单所述约 27.5 GB 不一致，以运行时实测为准）。** Docker data-root 为 `/var/lib/docker`（Docker Desktop Linux 容器），WSL VHDX 增长会占用宿主 C:，容器删除后宿主空间可能不会自动收缩。
4. 宿主可用内存 ≥4 GB（上次冒烟时 `host.memory_free_bytes=1,186,496,512` 偏低）；容器限制 memory=1200 MiB、nanoCPUs=2.0。
5. JDK 21（`JAVA_HOME=C:\Java\jdk-21.0.9`，enforcer `[21,22)`）；surefire 固定 `forkCount=1, reuseForks=false, -Xmx512m`；如出现 OOM，可临时以 `-DargLine=-Xmx1024m` 覆盖。
6. 证据目录 `.scratch/vehicle-monitoring/evidence/logs/` 可写；harness 会覆盖同名 `25-capacity-*.log/csv`。

## 预估（估算，非实测）

- **生成耗时**：冒烟 6600 行 / 852 ms ≈ 7,746 行/秒（batch=50）。若按该速率线性外推，28,512,000 行 ≈ 3,681 秒 ≈ 61 分钟；batch=2000 摊薄语句开销后吞吐应更高。估算生成阶段 25–60 分钟。
- **计数与查询开销**：harness 多次 `COUNT(*)` 扫描 retained 分区（约 6 次）与全表（28.5M 行），2 vCPU/768M buffer pool 下估算 10–30 分钟；查询、EXPLAIN、清理与容器启动/13 个 migration 估算 5–10 分钟。
- **总窗口**：估算 60–120 分钟（不含失败重试）；建议协调者预留 2 小时独占窗口。
- **磁盘占用**：冒烟 `information_schema.TABLES` 的 `data_length + index_length = 3,604,480` 字节 / 6600 行 ≈ 546 字节/行；28,512,000 行 ≈ **15.6 GB（估算）**；加 InnoDB redo/undo/temp 与碎片余量，峰值约 16–19 GB（估算）。清理 drop 3/33 个日分区约释放 9% ≈ 1.4–1.7 GB（估算，实际以 `cleanup.released_bytes` 为准）；retained 数据保留在容器内直到 harness 结束并移除容器。

## 逐条 AC 测量方法与待回填

| AC | 测量方法（harness/metric 键或命令） | 通过判据 | 状态 |
|---|---|---|---|
| 显式命令生成约 2592 万量级行，记录实际行数与生成耗时 | 显式 `-Dvms.capacity=true` 运行；`generated.total_rows`、`generated.retained_rows`、`generated.expected_total_rows`、`generated.expected_retained_rows`、`generated.total_elapsed_ms`、`generated.rows_per_second`、`generated.batches`、`generation.day`（逐日行数/批数/耗时）、`25-capacity-days.csv`、`25-capacity-batches.csv` | `generated.retained_rows == 25,920,000` 且 total == 28,512,000；低于目标时 harness 断言失败并写明“容量验证未完成” | 未执行；【待回填】 |
| 记录磁盘占用与分区范围 | `disk_before_generation`、`disk_after_generation`、`container.disk_after_generation`、`table.track_point.data_length_bytes/index_length_bytes/total_bytes/avg_row_length_bytes`、`table.track_point.container_du_kb`、逐分区 `partition=` 行、`partitions.count/first/last`、`partitions.retained_first/retained_last` | 生成后可计量磁盘占用（harness 断言 ≥100 字节/行）；分区 40 个，retained 范围 `today-29..today`，future `today+1..today+7`，expired `today-32..today-30` | 未执行；【待回填】 |
| 跨日查询记录 EXPLAIN 与执行耗时 | `explain.single-day`、`explain.cross-midnight-8h`、`query.single-day.*`、`query.cross-midnight-8h.*`（pruned_partitions、cold_ms、warm_ms、raw_points、display_points、segments、gaps、sql、args） | EXPLAIN `partitions` 精确等于查询日窗口（单日 1 个、跨日 2 个，无全分区扫描）；raw 8640 / 2881；display ≤2000；记录 cold/warm ms | 未执行；【待回填】 |
| 过期分区清理记录耗时与对保留数据影响 | `cleanup.elapsed_ms`、`cleanup.result_success`、`cleanup.created_partitions`、`cleanup.dropped_partitions`、`cleanup.dropped_rows_estimate`、`cleanup.before/after.partitions/retained_rows/total_rows/container_du_kb/alerts`、`cleanup.released_bytes`、`cleanup.recorded.*`、清理后重复查询 `query.after-cleanup-*` | drop 的分区恰为 3 个 expired；retained 行数前后相等且 == 25,920,000；total 行数降到 retained；磁盘释放 >0；成功清理 alerts=0；清理后查询结果不变 | 未执行；【待回填】 |
| 生成量不足时明确未完成，不宣称通过 | harness 在 `expectedRetainedRows < 25,920,000` 时写 `capacity.scale=非验收规模…` 并在 ≥ 目标分支断言；本记录与 ticket Answer 如实填写 | 未达 25,920,000 时标记未完成；不得以冒烟或 18000 行外推 | 未执行；【待回填】 |
| 不改正式 migration，不混入模拟档案 | 静态：`git diff --stat 45e04c7^..45e04c7 -- server/src/main/resources server/src/main/java` 为空；`grep -rn "CAP-FLEET" server/src/main/resources/db/migration server/src/main/java/db/migration` 无结果；运行期：容器内 `fleet` 仅 `CAP-FLEET`、`vehicle` 仅 `CAP-%`、`terminal` 仅 `99` 前缀（harness 断言） | 无 migration/产品代码改动；模拟档案只存在于专用容器 | 静态核对通过；运行期【待回填】 |
| 保留边界与预建失败告警复验通过 | harness：`system_config=30`、分区窗口断言、`track_partition_maintenance(trigger_type='STARTUP')` SUCCESS；另跑 `TrackRetentionMaintenanceTest`（`startupMaintenanceEnsuresRetentionAndFuturePartitionsAndRecordsResult`、`failedMaintenanceIsRecordedAsAlertWithVisibleReason`，至少这两个方法） | 保留窗口 `[today-29, today]`、预建 `today+1..today+7`；维护失败写 `track_partition_maintenance(status='FAILED')` 与 `track_partition_alert(alert_type='PARTITION_MAINTENANCE_FAILED')` 且 message 可见 | 未执行；【待回填】 |
| 环境资源不足写 blocked 并说明缺口 | 运行时记录 C: 可用空间、宿主内存、容器资源；低于门槛即停止 | 低于启动门槛不启动；运行中低于 18 GB 停手，ticket 写 `Status: blocked` 并说明缺口 | 未触发；【待回填】 |

## AC7 复验命令（待执行）

```text
mvnw.cmd -B -pl server -am -Dtest=TrackRetentionMaintenanceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

若只需最小复验，可用方法过滤运行启动窗口与失败告警两个用例；整类 10 项会同时覆盖清理、预览、确认与保留期外上报路径。该类使用 `IntegrationContainers`（`mysql:8.4` + `redis:8.4`），需与满规模 harness 串行执行，不得并行。

## 运行后需回填的原始证据

- `.scratch/vehicle-monitoring/evidence/logs/25-capacity-metrics.log`（metrics 主记录）
- `.scratch/vehicle-monitoring/evidence/logs/25-capacity-days.csv`（逐日行数/批数/耗时）
- `.scratch/vehicle-monitoring/evidence/logs/25-capacity-batches.csv`（逐批 build/execute 耗时与 SQL 字节）
- Surefire 控制台日志（含 `BUILD SUCCESS/FAILURE` 与 `[capacity]` 行）
- 运行后按凭证扫描结果，将需入库的 `*.log` 复制到 `.scratch/vehicle-monitoring/evidence/` 顶层（`.gitignore` 只放行顶层 `*.log`）。本轮已在 `.gitignore` 增加 `/.scratch/vehicle-monitoring/evidence/logs/`，使 harness 工作目录内的 `*.log/*.csv` 默认不入 Git；如需随票保留指标，复制到顶层后再提交。

## 未执行项清单

1. 未启动任何 Testcontainers/MySQL/Redis 容器（本轮编译不触发容器）。
2. 未运行 `TrackCapacityHarnessTest`，未生成任何容量数据。
3. 未运行 `TrackRetentionMaintenanceTest`，AC7 失败告警复验未执行。
4. 未采集运行期 MySQL/宿主/容器实测指标；本文件中的耗时与磁盘数字均为估算。
5. 未填写 ticket Answer、未勾选任何 AC。
6. 未能复制原始日志到顶层 evidence（运行尚未发生）。

## 需协调者确认

1. **独占窗口长度**：建议 2 小时（估算 60–120 分钟 + 重试余量），在 ticket 22 验收结束并释放全局锁后开始。
2. **磁盘门槛**：按 16–19 GB（估算）峰值，建议启动门槛 ≥22 GB；运行中 <18 GB 停手。当前实测 C: 41.8 GB 可用（2026-11-17），与派单所述约 27.5 GB 不一致，请以启动前复测为准；并确认 Docker Desktop 数据盘位于 C: 时接受 WSL VHDX 增长且不执行 prune。
3. **内存门槛**：建议宿主可用内存 ≥4 GB；若不足，先等待其他容器/任务退出。
4. **AC7 复验范围**：是否在满规模窗口内一并运行 `TrackRetentionMaintenanceTest`（额外约 2–5 分钟与两个容器）。
5. **批次大小**：默认 `batchSize=2000`（单条 INSERT 约 476 KB）；若运行时记录的服务端 `max_allowed_packet` 小于该值，则降到 200（约 48 KB）重跑，并在记录中说明。
