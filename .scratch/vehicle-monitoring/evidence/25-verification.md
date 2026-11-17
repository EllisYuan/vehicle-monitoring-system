# 任务 25 验证记录：30 天量级轨迹容量验收

状态：**已完成**。Phase A（基线升级、harness 校准）与 Phase B（满规模生成、查询/清理计量、AC7 复验）均已执行；AC33 在本环境与本次参数下通过。容量结论只覆盖本环境与本次生成参数，不换算为其他硬件，不使用 18000 理论行或 Phase A 估算值。

## 结论摘要

| 项 | 实测值 |
|---|---|
| 保留窗口行数（retained） | **25,920,000**（目标 25,920,000，达成） |
| 过期窗口行数（expired） | **2,592,000** |
| 总行数（total） | **28,512,000**（与参数推导一致） |
| 生成总耗时 | **1,257,715 ms ≈ 20 分 58 秒**，22,669.7 行/秒 |
| 生成批数 | 16,500 批（33 日 × 100 终端 × 5 批） |
| 单批 SQL 最大字节 | 479,091 B < `max_allowed_packet`=1,048,576 B |
| 宿主磁盘变化 | 生成前 43,963,174,912 B → 生成后 37,873,156,096 B，差 **6,090,018,816 B ≈ 5.67 GiB** |
| 容器内 `track_point*.ibd` | **8,111,220 KiB ≈ 7.74 GiB**（291.3 B/行）；清理后 7,373,928 KiB |
| 分区数 | 生成后 40；清理后 37 |
| 分区范围 | `p20261016`（2026-10-16）… `p20261124`（2026-11-24）；retained `p20261019`…`p20261117`；expired `p20261016`…`p20261018`；future `p20261118`…`p20261124` |
| 单日查询（2026-11-07） | EXPLAIN partitions=`p20261107`；cold **943 ms** / warm **195 ms**；raw 8640 / display 75 |
| 跨日查询（2026-11-07 20:00–11-08 04:00） | EXPLAIN partitions=`p20261107,p20261108`；cold **173 ms** / warm **118 ms**；raw 2881 / display 25 |
| 过期分区清理 | **650 ms**；drop 3 个 expired 分区；retained 行数 25,920,000 不变；total 降到 25,920,000；alerts=0 |
| 清理释放（容器内 du） | 754,987,008 B ≈ 720 MiB；宿主可用空间基本不变（VHDX 不自动收缩） |
| 满规模测试总耗时 | 1,407 s ≈ 23 分 27 秒（5 项测试全部通过） |
| AC7 复验 | `TrackRetentionMaintenanceTest` **10/10 通过**，77.44 s，BUILD SUCCESS |
| 磁盘最低可用 | 35.27 GiB（未触及 18 GB 门槛） |

## 基线与范围

| 项目 | 值 |
|---|---|
| 分支 | `feat/ticket-25-capacity` |
| 满规模运行 HEAD | `9e92985`（合入集成分支最新 tip `d169d5c` 后的 merge commit；运行期工作区含本文件所述 harness `max_allowed_packet` 度量的未提交改动） |
| 合入集成分支 | `git merge --no-ff integration/tickets-08-27`，tip `d169d5c459a610d4e7de6f70b302663da662f813`，自动合并无冲突 |
| 容量目标 | 100 终端 × 8640 点/日 × 30 日 = **25,920,000** retained rows（AC33） |
| 附加过期数据 | 3 个自然日 × 100 终端 × 8640 点 = **2,592,000** rows，仅用于清理计量 |
| 总生成量 | **28,512,000** rows / 33 个自然日 |

范围依据：ticket `25-thirty-day-track-capacity.md`、spec AC33/US52/US54、`docs/design/系统设计方案.md` 第 11 节、ADR `0006-track-retention.md`。保留规则、分区维护与失败告警以任务 11 的实现与验证记录（`11-verification.md`）为准，本票未重做。

## 运行环境（实测）

### 硬件与系统

| 项 | 值 |
|---|---|
| 机型 | Xiaomi Redmi Book Pro 14 2024 |
| CPU | Intel Core Ultra 5 125H，14 核 / 18 逻辑处理器（`Runtime.availableProcessors`=18） |
| 内存 | 宿主总内存 16,799,621,120 B ≈ 15.65 GiB；harness 启动时 `host.memory_free_bytes`=356,286,464 B ≈ 340 MiB（偏低，见“限制与未覆盖”） |
| OS | Windows 11 Pro for Workstations 10.0.26200 |
| Docker | Docker Desktop，Server 29.1.3；Docker VM `memTotal`=1,997,275,136 B ≈ 1.86 GiB，`NCPU`=2 |
| 时钟与测量 | 耗时用 `System.nanoTime()`；业务日期/保留窗口用 `Clock.system(Asia/Shanghai)`；宿主磁盘用 `Files.getFileStore(Path.of("C:\\")).getUsableSpace()`；容器磁盘用 `du -sk`；逐批/逐日耗时由 harness 写入 CSV |

### 软件与配置

| 项 | 值 |
|---|---|
| JDK | Oracle JDK **21.0.9**（`JAVA_HOME=C:\Java\jdk-21.0.9`，enforcer `[21,22)`） |
| Maven | Apache Maven **3.9.12**（Maven Wrapper 3.3.4） |
| Spring Boot | **4.1.2**（`server/pom.xml` parent） |
| MySQL | `mysql:8.4`，实例版本 **8.4.13**；容器 `modest_lichterman`（运行期名称），ID `9cb5f60d9286…` |
| Redis | `redis:8.4`（AC7 `TrackRetentionMaintenanceTest` 使用） |
| 容器资源 | memory=1,258,291,200 B（1200 MiB），nanoCPUs=2,000,000,000 |
| MySQL 参数 | `--skip-log-bin --innodb-buffer-pool-size=768M --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=40 --performance-schema=OFF`；`innodb_buffer_pool_size=805,306,368`；`log_bin=0`；`performance_schema=0`；charset `utf8mb4/utf8mb4_0900_ai_ci`；session time_zone `Asia/Shanghai`；`innodb_file_per_table=1` |
| `max_allowed_packet` | **1,048,576 B（1 MiB）**，非 64 MB 默认值 |
| Surefire | `forkCount=1`、`reuseForks=false`、`argLine=-Xmx512m`（未触发 OOM，未用 `-DargLine` 覆盖） |
| Flyway | 应用 **13** 个正式 migration（与合并后基线一致） |

## 执行命令

满规模运行（经 `with-test-lock.ps1` 全局串行锁；锁等待上限 120 分钟；实际未等待）：

```text
cd <worktree>
mvnw.cmd -B -pl server -am \
  -Dtest=TrackCapacityHarnessTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dvms.capacity=true \
  -Dvms.capacity.days=30 -Dvms.capacity.expired-days=3 \
  -Dvms.capacity.terminals=100 -Dvms.capacity.points-per-day=8640 \
  -Dvms.capacity.batch=2000 -Dvms.capacity.generate=true \
  test
```

- 必须以 `-pl server -am` 从 worktree 根执行：单模块会因 `com.vms:protocol` 未安装到本地仓库失败；且 Maven 以调用者 CWD 为项目根，不能仅调用 worktree 的 `mvnw.cmd` 而停留在他处。
- 显式系统属性触发，不经过 migration、不写正式初始化脚本。
- `batch=2000` 依据：实际 `max_allowed_packet=1,048,576 B`，单批最大 SQL 479,091 B，占限值 45.7%，无需降到 200。

AC7 复验：

```text
mvnw.cmd -B -pl server -am -Dtest=TrackRetentionMaintenanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 实测结果与逐条 AC

### AC1 显式命令生成约 2592 万量级行，记录实际行数与生成耗时

- `generated.retained_rows=25,920,000`（等于 `required_retained_rows` 25,920,000）；
- `generated.total_rows=28,512,000` = `generated.expected_total_rows`；
- `generated.total_elapsed_ms=1,257,715`，`generated.rows_per_second=22,669.7`，`generated.batches=16,500`，`generated.batch_size=2000`；
- 逐日：33 日全部 `rows=864000`、`batches=500`；单日耗时 avg 38,107 ms / min 28,615 ms / p95 48,855 ms / max 52,500 ms；
- 逐批：16,500 批；build avg 0.47 ms / p95 1 ms / max 174 ms；execute avg 73.51 ms / p50 62 ms / p95 158 ms / max 3,275 ms；SQL 字节 min 150,904 / avg 410,207 / max 479,091；
- 满规模测试整体 1,407 s（≈23 分 27 秒），其中生成 1,257.7 s，其余约 149 s 为容器启动、迁移、`COUNT(*)`、EXPLAIN/查询、清理与拆除。

判定：**通过**。

### AC2 记录磁盘占用与分区范围

- 宿主：`disk_before_generation.usable=43,963,174,912 B` → `disk_after_generation.usable=37,873,156,096 B`，差 6,090,018,816 B ≈ **5.67 GiB**；最低可用 **35.27 GiB**（`disk_after_generation`），磁盘看门狗采样最低 35.61 GiB（09:58）；
- 容器内 `track_point*.ibd`：`container_du_kb=8,111,220` KiB ≈ **7.74 GiB**，28,512,000 行 → **291.3 B/行**；清理后 7,373,928 KiB ≈ 7.03 GiB（retained 25,920,000 行仍为 291.3 B/行）；
- `information_schema.TABLES` 的 `TABLE_ROWS=9,249,915`、`data_length=2,097,659,904`、`index_length=535,658,496`、`avg_row_length=226` 在批量写入后为**过期近似统计**，低于实际行数与实际文件占用；容量结论以 `COUNT(*)` 与容器 `du` 为准，不引用该近似值；
- 分区 40 个：`p20261016` … `p20261124`；retained `p20261019`…`p20261117`（30 个，2026-10-19 至 2026-11-17），expired `p20261016`…`p20261018`（3 个），future `p20261118`…`p20261124`（7 个）；`partitions.count=40`、`partitions.first=p20261016`、`partitions.last=p20261124`；
- 清理后 `cleanup.after.partitions=37`。

判定：**通过**。

### AC3 跨日查询记录 EXPLAIN 与执行耗时

生产查询由 `RecordingJdbcTemplate` 捕获后执行 `EXPLAIN`，分区裁剪结果与查询日窗口逐一比对。

| 查询 | EXPLAIN partitions | 访问方式 | cold | warm | raw | display | segments/gaps |
|---|---|---|---|---|---|---|---|
| 单日 2026-11-07 00:00:00–23:59:59 | `p20261107` | `type=range`, `key=idx_track_vehicle_time`, `key_len=13`, `rows=16086`, `Extra=Using index condition` | 943 ms | 195 ms | 8640 | 75 | 3 / 2 |
| 跨日 2026-11-07 20:00–11-08 04:00 | `p20261107,p20261108` | `type=range`, `key=idx_track_vehicle_time`, `key_len=13`, `rows=2881`, `Extra=Using index condition` | 173 ms | 118 ms | 2881 | 25 | 2 / 1 |

- 查询 SQL 由 ticket 10 生产 `TrackQueryService` 发出（`... FROM track_point PARTITION (p…) WHERE vehicle_id = ? AND gps_time >= ? AND gps_time <= ? AND 1=1 ORDER BY gps_time, id`）；分区裁剪生效，无全分区扫描；
- raw 点数与生成参数一致（单日 8640、跨日 2881），display 均 ≤2000；查询后再次执行结果一致（单日 236 ms、跨日 167 ms）。

判定：**通过**。

### AC4 过期分区清理记录耗时与对保留数据影响

| 指标 | 实测 |
|---|---|
| `cleanup.elapsed_ms` | **650** |
| `cleanup.result_success` | true |
| `cleanup.dropped_partitions` | `p20261016,p20261017,p20261018`（恰为 3 个 expired） |
| `cleanup.created_partitions` | 空 |
| `cleanup.before.retained_rows` / `total_rows` / `expired_rows` | 25,920,000 / 28,512,000 / 2,592,000 |
| `cleanup.after.retained_rows` / `total_rows` | 25,920,000 / 25,920,000 |
| `cleanup.after.alerts` | 0（成功清理不产生失败告警） |
| `cleanup.released_bytes` | 754,987,008 B ≈ 720 MiB（容器内 `du` 差） |
| `cleanup.before/after.container_du_kb` | 8,111,220 / 7,373,928 |
| `cleanup.recorded.dropped_rows_estimate` | 805,630（来自 `information_schema` 近似统计；实际 expired 行数为 2,592,000） |
| 清理后查询 | 单日 raw 8640 / display 75 / 236 ms；跨日 raw 2881 / display 25 / 167 ms，与清理前一致 |

宿主可用空间在清理前后为 37,909,331,968 → 37,910,216,704 B（+884,736 B，基本不变）：`DROP PARTITION` 释放 InnoDB 页，但 Docker Desktop 的 WSL VHDX 不会随容器内文件删除自动收缩；释放量以容器内 `du` 计。

判定：**通过**。

### AC5 生成量不足时明确未完成，不宣称通过

- 本次 `expectedRetainedRows=25,920,000 ≥ REQUIRED_RETAINED_ROWS=25,920,000`，实际 retained 与 total 均等于推导值，目标达成；
- harness 内置保护仍在：低于目标时写入 `capacity.scale=非验收规模…` 并断言失败；本次未触发；
- 本记录与 ticket Answer 只引用本次实测 25,920,000/28,512,000，不使用 18,000 理论行或 Phase A 估算值。

判定：**通过（条件未触发）**。

### AC6 不改正式 migration，不混入模拟档案

- 静态：`git diff --stat d169d5c..HEAD -- server/src/main/resources/db/migration server/src/main/java/db/migration` 为空；`grep -rn "CAP-FLEET"` 在上述目录无结果；本票未修改产品代码；
- 运行期断言（`capacityArchivesAndPartitionWindowAreDedicatedAndRecorded` 通过）：容器内 `fleet` 仅 1 条 `CAP-FLEET`，`vehicle` 100 条且全部 `plate_no LIKE 'CAP-%'`、无非 `CAP-%` 车辆，`terminal` 100 条且全部 `terminal_no LIKE '99%'`，绑定与车队历史各 100 条，`system_config.track.retention.days='30'`；
- 模拟档案只存在于 harness 自建 Testcontainers MySQL 容器，容器结束已回收；未进入正式 migration、初始化脚本或开发库。

判定：**通过**。

### AC7 保留边界与预建失败告警复验通过

满规模 harness 内已验证：启动维护 `status=SUCCESS`、`retentionDays=30`、40 个分区窗口（retained 30 + expired 3 + future 7）；成功清理 alerts=0。

另跑 `TrackRetentionMaintenanceTest`（10 项，77.44 s，BUILD SUCCESS，`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`），方法覆盖：

1. `startupMaintenanceEnsuresRetentionAndFuturePartitionsAndRecordsResult`：启动维护记录 SUCCESS/retention_days=30，保留 `[today-29, today]` 与预建 `today+1..today+7`；
2. `expiredPartitionsOutsideRetentionAreDroppedAndRangeIsRecorded`：保留期外分区被 drop 并记录；
3. `failedMaintenanceIsRecordedAsAlertWithVisibleReason`：维护失败写 `track_partition_maintenance(status='FAILED')` 与 `track_partition_alert(alert_type='PARTITION_MAINTENANCE_FAILED')`，message 可见原因；
4. `retentionShorteningPreviewListsDroppedRangeWithoutChangingConfig`；
5. `confirmedShorteningDropsExpiredPartitionsAndAuditsOperatorOldNewAndResult`；
6. `outOfRetentionPositionRecordsRestrictedRejectionWithoutRebuildingPartition`；
7. `dailyInspectionRecordsResultAndRepeatedStartupStaysIdempotent`；
8. `cleanupKeepsBindingHistoryAuditAndRetentionRecords`；
9. `maintenanceFillsMissingHistoricalDayInsideRetentionRange`；
10. `failedConfirmKeepsFailureEvidenceAndDoesNotReportSuccess`。

判定：**通过**（复验任务 11 的保留规则，未重做保留逻辑）。

### AC8 环境资源不足写 blocked 并说明缺口

- 未触发 blocked；本次资源足够：宿主 C: 启动前可用 40.94 GiB，运行中最低 35.27 GiB，未低于 18 GB 门槛；容器内存 1200 MiB / 2 CPU 正常，无 OOM；
- 实测偏差：harness 启动时宿主 `memory_free_bytes` 仅 ≈340 MiB（Windows Available 约 794 MB），明显低于 Phase A 建议的 ≥4 GB。该环境仍完成了满规模生成与全部查询/清理，未出现容器重启或 JVM OOM；本记录如实标注，容量结论不因此扩展或缩减；
- 运行期间未清理他人容器，未执行任何 prune。

判定：**通过（未触发资源 blocked；内存偏低已如实记录）**。

## 逐条 AC 汇总

| AC | 状态 | 主要证据 |
|---|---|---|
| 显式命令生成约 2592 万量级行，记录实际行数与生成耗时 | 通过 | `25-capacity-metrics.log`；`25-capacity-days.log`；`25-capacity-batch-summary.log`；`25-capacity-run.log` |
| 记录磁盘占用与分区范围 | 通过 | `25-capacity-metrics.log`（磁盘、分区段）；`25-capacity-disk-watch.log` |
| 跨日查询记录 EXPLAIN 与执行耗时 | 通过 | `25-capacity-metrics.log`（`explain.*`、`query.*`） |
| 过期分区清理记录耗时与对保留数据影响 | 通过 | `25-capacity-metrics.log`（`cleanup.*`、`query.after-cleanup-*`） |
| 生成量不足时明确未完成，不宣称通过 | 通过（条件未触发） | `25-capacity-metrics.log`；harness 断言 |
| 不改正式 migration，不混入模拟档案 | 通过 | 静态 diff/grep；`25-capacity-run.log` 中 harness 档案断言 |
| 保留边界与预建失败告警复验通过 | 通过 | `25-ac7-retention.log`（10/10）；`25-capacity-metrics.log` |
| 环境资源不足写 blocked 并说明缺口 | 通过（未触发） | `25-capacity-disk-watch.log`；运行环境节 |

## 证据文件清单

顶层（`.scratch/vehicle-monitoring/evidence/`，随 ticket 入库；`.gitignore` 仅放行该层 `*.log`）：

| 文件 | 内容 |
|---|---|
| `25-capacity-run.log` | 满规模 Maven/Surefire 控制台（含 `[capacity]` 指标行、`Tests run: 5`、`BUILD SUCCESS`） |
| `25-capacity-metrics.log` | harness 全量 metrics（环境、行数、逐日、分区、EXPLAIN、查询、清理） |
| `25-capacity-days.log` | 逐日行数/批数/耗时 CSV |
| `25-capacity-batch-summary.log` | 16,500 批的 build/execute/SQL 字节统计（avg/p50/p95/max） |
| `25-capacity-day-summary.log` | 33 日耗时统计（avg/min/p95/max） |
| `25-capacity-disk-watch.log` | 运行期宿主磁盘采样（含最低点） |
| `25-ac7-retention.log` | `TrackRetentionMaintenanceTest` 10 项运行日志 |

原始体量文件（`.scratch/vehicle-monitoring/evidence/logs/`，被 `.gitignore` 忽略，仅本地保留）：`25-capacity-batches.csv`（16,500 批逐批明细，约 1.0 MB）、`25-capacity-days.csv`、各运行日志原件、`25-phaseB-test-compile.log`。

## 限制与未覆盖

- 容量结论只覆盖本环境（Windows 11 + Docker Desktop 2 vCPU/1.9 GiB VM + mysql:8.4 单实例）与本次参数（100 终端 × 8640 点/日 × 30 日 + 3 个过期日，batch=2000）；不外推到其他硬件、并发写入、真实终端接入或更长保留期。
- 未测试 100 终端实时写入性能（属 ticket 24）、轨迹导出、自动清理业务审计；未引入新分区策略。
- 生成数据只存在于已回收的 Testcontainers 容器；宿主 Docker VHDX 不会自动收缩，后续需要时由环境侧回收空间，本票未执行任何 prune。
- `information_schema.TABLES/PARTITIONS` 的 `TABLE_ROWS`、`DATA_LENGTH` 为近似统计；实际行数以 `COUNT(*)`、磁盘以容器 `du` 为准。
- 清理的 `dropped_rows_estimate=805,630` 为近似统计值，非实际删除行数（实际 expired 行数为 2,592,000，由 `COUNT(*)` 在清理前测得）。
- 运行期宿主可用内存仅约 340 MiB（harness 启动时），未导致失败，但该环境不适合并行运行其他容器测试。

## 与 Phase A 估算的差异

| 项 | Phase A 估算 | 实测 |
|---|---|---|
| 生成耗时 | 25–60 分钟 | **20 分 58 秒** |
| 总窗口 | 60–120 分钟 | **23 分 27 秒**（满规模单次） |
| 磁盘占用 | 15.6 GB（按冒烟 546 B/行）；峰值 16–19 GB | 容器内 7.74 GiB（291.3 B/行）；宿主字节差 5.67 GiB |
| `max_allowed_packet` | 预期 64 MB 默认 | **1 MiB**（单批 479 KB 仍低于限值） |
| 单批 SQL | 约 476 KB（238 B/行推导） | 平均 410,207 B，最大 479,091 B |
| 吞吐 | 按冒烟 7,746 行/秒线性外推约 61 分钟 | 22,669.7 行/秒（batch=2000） |

Phase A 的估算值仅保留为过程记录，不作为本票结论。

## Phase A 历史（已完成，摘要）

- 合并基线 `a481a9f`（集成分支 `4a38b98`），无冲突；`SchemaUpgradeTest` 与合并后 13 个 migration 一致；任务 25 自身提交未改 migration/产品代码。
- `mvnw.cmd -B -DskipTests package` 成功（server 105 main / 35 test 源文件）；单模块 `-pl server` 因 `com.vms:protocol` 未安装而失败，故满规模必须 `-am`。
- harness 静态校准：构造器、`track_point` 15 列、档案字段、保留配置、分区窗口、批量 INSERT、查询限制均与合并后源码一致；未发现阻断缺陷。
- 本轮 Phase B 在 harness 中新增 `mysql.max_allowed_packet` 度量（仅新增 metrics 行，未改生成/断言逻辑）。
