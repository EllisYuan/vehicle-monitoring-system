# 任务20 验证证据：三类 Excel 导出

- 分支：`feat/ticket-20-redacted-exports`（基线 `199e0f1`，已含任务13）
- 工作区：`C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-a9cc3552c3c13480e`
- 验证日期：2026-11-16
- 环境：Windows 11 Pro for Workstations；项目 `server/pom.xml` 配置 `java.version=21`；Maven wrapper；Apache POI `poi-ooxml` 5.4.0；Testcontainers `mysql:8.4` + `redis:8.4`，Ryuk 0.14.0 启用；无新增 migration（Flyway 仍为 12 个迁移，v14）
- 全局锁：本轮所有 Maven 命令均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行，未并行 Maven，未绕过锁。

## 结论总览

| # | 验收点 | 状态 | 主要证据 |
| --- | --- | --- | --- |
| AC1 | 三类导出各生成可正常打开的 xlsx，列与页面筛选条件一致 | 通过 | `ReportExportFlowTest` 用例1–3 |
| AC2 | ≤10000 行正常导出；10001 行明确失败并提示缩小范围，无部分文件 | 通过 | `ReportExportFlowTest#exportRowLimitRejectsOverLimitWithExplicitErrorForAllThreeTypes` |
| AC3 | 不含 phone、精确坐标、密码/鉴权码/token；车牌正常显示 | 通过 | 列白名单静态核对 + 用例1–3 表头/车牌断言 + 用例7 审计无凭证关键字 |
| AC4 | 以 =、+、-、@ 开头的样例在 Excel 中显示为文本，不执行公式 | 通过 | `ReportExportFlowTest#formulaLikeValuesAreEscapedAndDisplayedAsText` |
| AC5 | 车队长导出仅含授权车队，篡改筛选参数无效 | 通过 | `ReportExportFlowTest#fleetLeaderExportsOnlyAuthorizedFleetsAndTamperingIsRejected` |
| AC6 | 权限、超限、数据库失败均不产生残缺下载 | 通过 | 403/400 由用例4、6覆盖；数据库失败由用例8（删除 `alarm_handle_log` 后导出）覆盖 |
| AC7 | audit_log 记录条件、行数与结果，导出正文不写审计 | 通过 | 用例4、6、7、8 的 `audit_log` 直查 |
| AC8 | 抽检内容与页面、真实 MySQL 直查一致 | 通过 | 用例1–3 与 `/vehicles`、`/alarms` 页面顺序及 `count(*)` 对照 |
| AC02（复验） | 导出越权 | 通过 | 用例6：车队长 `fleetId` 越权 403，三个导出均无文件 |

## 一、实现范围

- 新增 `com.vms.reporting`：
  - `ReportExportController`：`GET /api/v1/reports/exports/vehicles.xlsx`、`/alarms.xlsx`、`/handling-records.xlsx`，可选 `fleetId`；类级 `@RequiresPermission(Permission.EXPORT_DATA)`；成功响应 `Content-Disposition: attachment` 与 xlsx 内容类型。
  - `ReportExportService`：复用 `VehicleService` 与 `AlarmQueryService` 的同权限、同筛选查询；导出前以同一条件 `count(*)` 判断 10000 数据行上限；内存生成工作簿后一次性返回；成功/失败均写 `EXPORT` 审计。
  - `ExcelWorkbook`：XSSF 工作簿，全部单元格按文本写入；首字符为 `=`、`+`、`-`、`@` 时加 `'` 前缀转义。
- `VehicleService` 增加 `count(user, fleetId)` 并抽出 `filter(...)`，列表与计数共用 `FleetScopeSql` 条件；`AlarmQueryService` 增加 `count`、`handlingRecords`、`countHandlingRecords`，报警列表与处置记录分别使用页面排序（`start_time DESC, id DESC` 与 `created_at, id`）。
- `AuditService` 增加 `CATEGORY_EXPORT = "EXPORT"`；`Permission` 增加 `EXPORT_DATA(ADMIN, MONITOR, FLEET_LEADER)`。
- `server/pom.xml` 增加 `org.apache.poi:poi-ooxml:5.4.0`。离线核验：`C:\Users\YuanS\.m2\repository\org\apache\poi\poi-ooxml\5.4.0\poi-ooxml-5.4.0.jar` 与 `...\poi\5.4.0\poi-5.4.0.jar` 均存在，锁内构建可直接解析。
- 设计同步：`docs/design/系统设计方案.md` 第 10 节后补充任务20 的接口、权限、上限、白名单、转义与审计说明。
- 未新增表、未新增 migration；未修改前端（本票各 AC 均为 HTTP 下载文件与数据库一致性，导出以 API 提供）。

## 二、TDD 红→绿记录

每个切片先以目标断言失败，再实现到通过；日志均在本目录 `logs/`。

| 切片 | 红阶段证据 | 绿阶段证据 |
| --- | --- | --- |
| 1 车辆导出 | `20-red-slice1.log`：请求 `/reports/exports/vehicles.xlsx` 返回 404，`expected: 200 but was: 404` | `20-green-slice1.log`：`Tests run: 1, Failures: 0`，BUILD SUCCESS |
| 2 报警与处置导出 | `20-red-slice2.log`：`/alarms.xlsx` 与 `/handling-records.xlsx` 均 404，2 项失败 | `20-green-slice2.log`：`Tests run: 2, Failures: 0`，BUILD SUCCESS |
| 3 上限与转义、越权、审计 | `20-red-slice3.log`：超限用例在 10001 行时仍返回 200，`expected: 400 but was: 200` | `20-green-slice3.log`：`Tests run: 4, Failures: 0`，BUILD SUCCESS |
| 4 数据库失败无残缺文件（补充覆盖） | 实现已存在，本项为 AC6 覆盖补强，不宣称红阶段；首次运行发现测试断言与 Spring 500 默认错误头不符（`inline;filename=f.txt`），修正断言后通过，过程见 `20-green-all2.log` 与 `20-green-dbfailure.log` | `20-green-dbfailure.log`：`Tests run: 1, Failures: 0`；`20-green-final.log`：8 项全通过 |

## 三、完整导出类与回归

### 3.1 完整导出类（锁内）

```powershell
& 'C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1' -Command '.\mvnw.cmd','-B','-Dsurefire.failIfNoSpecifiedTests=false','-Dtest=ReportExportFlowTest','test'
```

- 结果：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，耗时 211.7 s（`20-green-final.log`）。
- 运行日志确认 Testcontainers `mysql:8.4`、`redis:8.4`、Ryuk 正常启动；Flyway：`Successfully applied 12 migrations ... now at version v14`。
- 此前 7 项版本亦全绿（`20-green-all.log`，`Tests run: 7`）。

### 3.2 回归（锁内单次 Maven）

```powershell
& 'C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1' -Command '.\mvnw.cmd','-B','-Dsurefire.failIfNoSpecifiedTests=false','-Dtest=SchemaUpgradeTest,AlarmHandlingFlowTest,TrackQueryFlowTest,CredentialLogHygieneTest','test'
```

- 结果：`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（`20-regression.log`）。
  - `SchemaUpgradeTest` 2 项（迁移数与表清单未受本票影响）
  - `AlarmHandlingFlowTest` 6 项（`AlarmQueryService` 重构未改变页面语义）
  - `TrackQueryFlowTest` 8 项
  - `CredentialLogHygieneTest` 1 项（导出审计不引入凭证日志）

## 四、逐条 AC 说明

### AC1 三类 xlsx 与页面条件一致

- 车辆（用例1）：表头 `车牌/车队/状态/终端编号`；导出车牌顺序与 `GET /vehicles?fleetId=` 完全一致（`plate_no` 排序，车牌唯一，次序稳定）；响应 `content-type` 为 xlsx，`content-disposition` 以 `attachment;` 开头且含 `.xlsx`。
- 报警（用例2）：表头 `车牌/车队/来源/报警类型/条件状态/处置状态/开始时间/最后时间/恢复时间`；导出车牌顺序与 `GET /alarms?fleetId=` 一致（`start_time DESC, id DESC`），行数 = 页面行数 = `SELECT count(*) FROM alarm WHERE fleet_id_at_event=?`。
- 处置记录（用例3）：通过 API 将报警 `PENDING→PROCESSING→HANDLED` 两次处置后导出；表头 `车牌/车队/变更类型/原状态/新状态/操作人/备注/时间`；3 行（表头 + 2 条），状态迁移 `PENDING→PROCESSING→HANDLED`、操作人 `export-admin`、备注 `开始核查/处理完成` 与报警详情 `handleHistory` 一致；MySQL `alarm_handle_log` 关联计数为 2。
- 三类工作簿均以 POI `XSSFWorkbook` 实际读回（非仅字节比对），可正常打开。

### AC2 10000 行上限与无部分文件

- 用例4 对三类分别构造 10000 行：导出 200，读回 10001 行（含表头）。
- 各再加 1 行后：均返回 400，`code=EXPORT_ROW_LIMIT_EXCEEDED`，`detail` 含“缩小筛选范围”；响应无 `content-disposition`，`content-type` 不含 `spreadsheet`。
- 实现上先以与列表相同的 `FleetScopeSql` 条件执行 `count(*)`，超限在查询列表与生成工作簿之前抛错；工作簿在内存 `ByteArrayOutputStream` 完成后才写入响应，异常路径不产生文件。10001 行时最新 `EXPORT` 审计为 `FAILURE`、`rows=10001`、`reason=ROW_LIMIT`。

### AC3 字段白名单与脱敏

- 导出列集合静态核对：不含 driver `phone`、`lng/lat`、`password`、`authCode`、`token`、`ticket`；车牌按原值显示（用例1–3 断言真实车牌）。
- 用例7 直查 `audit_log`：`EXPORT` 明细不含 `satoken`、`password`、`authCode` 关键字，也不含导出车牌正文。

### AC4 公式注入转义

- 用例5 创建车牌 `=1+1`、`+86`、`-2+3`、`@SUM(A1)` 及一个正常车牌，读出值为 `'=1+1`、`'+86`、`'-2+3`、`'@SUM(A1)`，原始未转义形式不存在。
- 读回 helper 对每个单元格断言 `CellType != FORMULA`，确认不会作为公式执行；所有单元格按文本写入。

### AC5 车队长范围与参数篡改

- 用例6：管理员创建车队 A/B 与各自车辆、报警；车队长仅授权车队 A。
- 车队长导出 `vehicles.xlsx`、`alarms.xlsx` 仅含车队 A 车牌；对三类请求 `fleetId=<车队B>` 均返回 403 `FORBIDDEN`，无 `content-disposition`；最新 `EXPORT` 审计 `FAILURE`、`reason=FORBIDDEN`。
- 服务端在 `VehicleService.count/list` 与 `AlarmQueryService.count/list/handlingRecords` 内用 `FleetScopeResolver`/`FleetScopeSql` 重新解析范围，URL 参数不能扩大结果。

### AC6 失败不产生残缺下载

- 超限：见 AC2，400 + 无附件头。
- 越权：见 AC5，403 + 无附件头。
- 数据库失败（用例8）：删除 `alarm_handle_log` 后导出处置记录，返回 500；`content-disposition` 非 attachment 且不含 `.xlsx`，`content-type` 不含 `spreadsheet`；最新 `EXPORT` 审计 `FAILURE`、`type=HANDLING`、`rows=0`、`reason=QUERY_FAILED`。Spring 默认错误处理在该 500 上附加 `inline;filename=f.txt`，为 JSON 错误体的内联提示，不是工作簿下载。
- 实现中计数、查询、工作簿生成三处异常均先写失败审计再抛出，响应由统一异常处理生成，不存在“先写文件头再失败”的路径。

### AC7 审计记录

- 用例7 成功导出后：`actor_name=export-admin`、`result=SUCCESS`，`detail` 含 `type=VEHICLES`、`fleetId=<车队>`、`rows=2`，不含车牌。
- 失败审计见 AC2、AC5、AC6；所有 `EXPORT` 明细均无凭证关键字（全表断言为 0）。

### AC8 与页面、MySQL 直查一致

- 用例1–3 已逐项比较导出内容与页面接口返回顺序、行数，以及真实 MySQL `count(*)`；用例3 另核对 `alarm_handle_log` 关联计数。

## 五、容器与磁盘

- 本轮所有测试的 mysql/redis/Ryuk 容器均由 Ryuk 在 JVM 结束后回收；复查 `docker ps -a` 无本轮新增运行容器，仅保留其他项目的 `reverent_brahmagupta mysql:8.4`（未清理）。
- C: 可用约 28.0 GB，高于 15 GB 硬边界；未执行 `docker volume prune`/`system prune`。

## 六、偏差与边界

- 前端未改动：本票 AC 以 HTTP 下载文件与页面数据一致性判定，导出以 API 交付；`/admin/reports` 占位与统计页面属任务19 范围，未在本票新增页面入口。
- 数据库失败用例为 AC6 覆盖补强，在实现完成后新增；首次运行因对 Spring 500 默认 `Content-Disposition` 的断言过严而失败，修正测试断言后 1/1、随后完整类 8/8 通过，未修改产品代码。
- 未新增导出类型、异步导出中心、原始轨迹批量导出、地图截图或对外推送；未改变监控页与轨迹的坐标展示与授权规则。
