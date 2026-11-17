# 25: 30 天量级轨迹容量验收

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 10, 11

## What to build

用显式命令（非 migration）在专用环境生成约 30 天量级历史轨迹。按 100 终端 × 8640 点/日 × 30 日估算约 2592 万行，实际以生成参数记录。生成分批写入并记录每批耗时；跨日与单日查询分别计时；清理在副本环境执行，避免破坏验收数据；同时记录磁盘与内存余量。记录实际行数、磁盘占用、分区范围、跨日查询 EXPLAIN 与执行耗时、过期分区清理耗时及对保留数据的影响。复验 30 天保留边界、未来分区预建与维护失败告警，以 11 的实现为准。资源不足时明确写 blocked；生成数据量不足则明确标记容量验证未完成，不得宣称通过，也不从 18000 行外推容量。模拟数据不得混入正式 migration，也不得与业务档案混淆。

## Spec coverage

主：AC33。次（复验）：US52 默认保留今天及此前 29 天、US54 维护失败可见告警。详见 [spec](../spec.md)。

## Acceptance criteria

- [x] 显式命令生成约 2592 万量级行，记录实际行数与生成耗时
- [x] 记录磁盘占用与分区范围
- [x] 跨日查询记录 EXPLAIN 与执行耗时
- [x] 过期分区清理记录耗时与对保留数据影响
- [x] 生成量不足时明确未完成，不宣称通过
- [x] 不改正式 migration，不混入模拟档案
- [x] 保留边界与预建失败告警复验通过
- [x] 环境资源不足写 blocked 并说明缺口

## Scope boundaries

不承诺真实生产容量；不做 100 终端实时性能（24）；不做轨迹导出；不引入测试专用查询接口或绕过权限的数据通道；不引入新分区策略或自动清理业务审计；不把理论行数写成实测结果。

## Validation

在专用环境以真实 MySQL 生成并查询；生成数据使用独立标识，验证后按环境清理；保存行数、磁盘、EXPLAIN、耗时与清理结果；记录硬件、版本、命令与参数。未执行或数据不足的项不得勾选。

## Comments

依赖 10 的轨迹查询回放与 11 的分区/保留能力；本票只负责容量数据生成、计量与记录，不重做保留规则。若执行计划显示分区裁剪未生效，记录为失败并修复后再测。容量结论只覆盖本环境与生成参数，不换算为其他硬件。

## Answer

**已完成（本环境、本次参数）。** 满规模运行成功，AC33 在 100 终端 × 8640 点/日 × 30 日、`batch=2000` 下达成，所有 8 条 AC 均满足；详见 `.scratch/vehicle-monitoring/evidence/25-verification.md`。

### 分支与基线

- 分支 `feat/ticket-25-capacity`；运行 HEAD `9e92985`（先合并集成分支最新 tip `d169d5c459a610d4e7de6f70b302663da662f813`，`--no-ff`，无冲突）。
- 本次为独占运行：全程仅 `silly_haslett`、`reverent_brahmagupta` 两个他人容器存在，无其他 Maven/Playwright/Testcontainers 任务；全部 Maven 经 `with-test-lock.ps1` 全局锁串行，未等待、未超时、未绕过。

### 命令

```text
mvnw.cmd -B -pl server -am -Dtest=TrackCapacityHarnessTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dvms.capacity=true -Dvms.capacity.days=30 -Dvms.capacity.expired-days=3 \
  -Dvms.capacity.terminals=100 -Dvms.capacity.points-per-day=8640 \
  -Dvms.capacity.batch=2000 -Dvms.capacity.generate=true test
```

必须 `-pl server -am`（单模块因 `com.vms:protocol` 未安装到本地仓库失败）。

### 实际参数与实测结果

| 项 | 实测 |
|---|---|
| retained 行数 | **25,920,000**（100 × 8640 × 30，等于目标） |
| expired 行数 / total 行数 | **2,592,000** / **28,512,000** |
| 生成耗时 | **1,257,715 ms ≈ 20 分 58 秒**（22,669.7 行/秒，16,500 批） |
| 满规模测试总耗时 | 1,407 s；`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| 逐批耗时 | build avg 0.47 / p95 1 / max 174 ms；execute avg 73.51 / p50 62 / p95 158 / max 3,275 ms；单批 SQL 150,904–479,091 B |
| 逐日耗时 | avg 38,107 / min 28,615 / p95 48,855 / max 52,500 ms，33 日均 864,000 行 |
| `@@max_allowed_packet` | **1,048,576 B（1 MiB）**，单批最大 479,091 B（45.7%），故 `batch=2000` 未降级 |
| surefire | `forkCount=1`、`reuseForks=false`、`argLine=-Xmx512m`，无 OOM，未覆盖 |
| 磁盘 | 宿主 C: 43,963,174,912 → 37,873,156,096 B（−5.67 GiB），最低 35.27 GiB；容器 `track_point*.ibd` 8,111,220 KiB ≈ 7.74 GiB（291.3 B/行） |
| 分区范围 | 40 个：`p20261016`…`p20261124`；retained `p20261019`…`p20261117`，expired `p20261016`…`p20261018`，future `p20261118`…`p20261124` |
| 单日查询（2026-11-07） | `EXPLAIN partitions=p20261107`，`type=range`，`key=idx_track_vehicle_time`；cold 943 ms / warm 195 ms；raw 8640 / display 75 |
| 跨日查询（11-07 20:00–11-08 04:00） | `EXPLAIN partitions=p20261107,p20261108`，`type=range`；cold 173 ms / warm 118 ms；raw 2881 / display 25 |
| 过期分区清理 | **650 ms**，drop `p20261016,p20261017,p20261018`，created 空；retained 前后均 25,920,000，total 28,512,000 → 25,920,000；alerts=0；释放 754,987,008 B（容器 du）；清理后查询结果不变（236/167 ms） |
| AC7 复验 | `TrackRetentionMaintenanceTest` 10/10 通过，77.44 s，BUILD SUCCESS（保留窗口、预建、`PARTITION_MAINTENANCE_FAILED` 告警、缩短预览/确认、保留期外上报、幂等） |
| 环境 | Windows 11 Pro for Workstations 10.0.26200；Core Ultra 5 125H 14C/18T；15.65 GiB RAM；Docker Desktop 29.1.3（VM 2 vCPU/1.86 GiB）；mysql:8.4→8.4.13；redis:8.4；Spring Boot 4.1.2；JDK 21.0.9；Maven 3.9.12；Flyway 13 个 migration；MySQL `--skip-log-bin --innodb-buffer-pool-size=768M --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=40 --performance-schema=OFF` |

### 限制

- 结论只覆盖本环境与上述生成参数；不外推到其他硬件、并发实时写入、真实终端接入或更长保留期。
- `information_schema.TABLES/PARTITIONS` 为近似统计（`TABLE_ROWS=9,249,915`、`cleanup.dropped_rows_estimate=805,630`），实际行数以 `COUNT(*)`、磁盘以容器 `du` 为准。
- 生成数据仅存在于已回收的 Testcontainers 容器；Docker WSL VHDX 不随容器内删除自动收缩，宿主可用空间清理后基本不变（+884,736 B），本票未执行任何 prune。
- harness 启动时宿主可用内存仅 ≈340 MiB，未导致失败，但该环境不宜并行运行其他容器测试。
- 未测试 100 终端实时写入（ticket 24）、轨迹导出与自动清理业务审计；未引入新分区策略。
- 未改 `server/src/main/resources/db/migration`、`server/src/main/java/db/migration` 与产品代码；运行期断言容器内仅有 `CAP-FLEET` / `CAP-%` 车辆 / `99` 前缀终端档案。

### 证据

- `.scratch/vehicle-monitoring/evidence/25-verification.md`（逐条 AC、环境、命令、期望/实际）
- `.scratch/vehicle-monitoring/evidence/25-capacity-run.log`（Maven/Surefire 控制台）
- `.scratch/vehicle-monitoring/evidence/25-capacity-metrics.log`（全量 metrics）
- `.scratch/vehicle-monitoring/evidence/25-capacity-days.log`、`25-capacity-day-summary.log`、`25-capacity-batch-summary.log`
- `.scratch/vehicle-monitoring/evidence/25-capacity-disk-watch.log`
- `.scratch/vehicle-monitoring/evidence/25-ac7-retention.log`
- 逐批 CSV 等原始体量文件在 `evidence/logs/`（gitignore，仅本地保留）
