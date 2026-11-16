# 20: 三类 Excel 导出

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 13

## What to build

实现车辆、报警、处置三类同步 Excel 导出。每类最多 10000 数据行；超过直接报错要求缩小范围，不得静默截断，也不得返回残缺文件。导出查询使用与页面相同的服务端权限与筛选、排序条件，车队长只能导出授权车队；筛选参数在服务端重新校验，不能由客户端指定范围扩大结果。字段使用白名单：车牌正常显示，不含司机 phone、精确 lng/lat 或凭证（密码、鉴权码、token、ticket）。所有单元格按文本写入，对以 =、+、-、@ 开头的内容做转义，防止公式注入。生成失败（权限、超限、数据库）返回明确失败，不产生可下载的半成品。每次成功或失败的发起都写 audit_log，记录操作者、筛选条件、行数与结果；导出正文本身不写审计。行数上限在生成前用同一筛选条件计数判断，超限直接失败，避免先加载全部结果；排序与页面一致并使用稳定次序，保证重复导出可对照。同步导出，不做异步导出中心。

## Spec coverage

主：US82、US83、US84，AC26。次（复验）：AC02 中导出越权项。详见 [spec](../spec.md)。

## Acceptance criteria

- [x] 三类导出各生成可正常打开的 xlsx，列与页面筛选条件一致
- [x] ≤10000 行正常导出；10001 行明确失败并提示缩小范围，无部分文件
- [x] 不含 phone、精确坐标、密码/鉴权码/token；车牌正常显示
- [x] 以 =、+、-、@ 开头的样例在 Excel 中显示为文本，不执行公式
- [x] 车队长导出仅含授权车队，篡改筛选参数无效
- [x] 权限、超限、数据库失败均不产生残缺下载
- [x] audit_log 记录条件、行数与结果，导出正文不写审计
- [x] 抽检内容与页面、真实 MySQL 直查一致

## Scope boundaries

不含原始轨迹批量导出、地图截图、异步导出、对外推送；不新增导出类型或权限设计器；脱敏规则不改变监控页与轨迹的授权坐标展示；超限按数据行计算而非文件字节。

## Validation

通过 HTTP 下载真实文件并用 Excel/POI 读回核对列与行数；与真实 MySQL 计数对照；构造公式注入样例打开验证；以车队长会话篡改参数复验；记录环境、版本、命令与结果，未执行项不勾选。

## Comments

与 19 号统计共用权限与事件归属口径；审计记录供 21 号查询。超限边界以 10000 数据行为准。若前序票提供的处置或报警记录字段不完整，回到对应票修复，不在导出中补造数据。

## Answer

已实现并完成验证，逐条 AC 证据、命令与环境记录见 `.scratch/vehicle-monitoring/evidence/20-verification.md`。

实现：

- 新增 `com.vms.reporting`（`ReportExportController`、`ReportExportService`、`ExcelWorkbook`），无新增表与 migration。
- REST：`GET /api/v1/reports/exports/vehicles.xlsx`、`/alarms.xlsx`、`/handling-records.xlsx`，可选 `fleetId`；权限 `EXPORT_DATA`（ADMIN、MONITOR、FLEET_LEADER），Controller 使用 `@RequiresPermission`。
- 复用页面查询：车辆走 `VehicleService`（`plate_no` 排序与 `FleetScopeSql`）；报警与处置记录走 `AlarmQueryService`（`fleet_id_at_event` 范围，报警 `start_time DESC, id DESC`，处置 `created_at, id`）。显式越权 `fleetId` 返回 403 `FORBIDDEN`。
- 导出前以同一筛选条件 `count(*)` 判断 10000 数据行上限，超限返回 400 `EXPORT_ROW_LIMIT_EXCEEDED` 并提示缩小范围；工作簿在内存生成后一次性返回，失败路径不产生部分文件。
- 字段白名单不含 driver phone、精确 lng/lat 或凭证，车牌正常显示；所有单元格按文本写入，以 `=`、`+`、`-`、`@` 开头的值加 `'` 前缀转义。
- 成功与失败均写 `audit_log`（`category=EXPORT`，记录操作者、导出类型、`fleetId`、行数与结果，失败原因 FORBIDDEN/ROW_LIMIT/QUERY_FAILED/WRITE_FAILED），不记录导出正文与敏感字段；`AuditService` 新增 `CATEGORY_EXPORT`，`Permission` 新增 `EXPORT_DATA`。
- `server/pom.xml` 增加 `org.apache.poi:poi-ooxml:5.4.0`（离线仓库 `~/.m2` 已存在，锁内构建可直接解析）。
- 设计文档补充任务20 的接口、权限、上限、白名单、转义与审计说明；前端未改动，导出以 API 下载交付。

验证（均经 `with-test-lock.ps1` 全局串行锁；Testcontainers `mysql:8.4`/`redis:8.4`，Ryuk 启用并已回收）：

- TDD 三切片红→绿：车辆导出 404→1/1；报警与处置导出 404→2/2；上限/转义/越权/审计 200→400 等 4/4；另有数据库失败无残缺文件补强用例。
- `ReportExportFlowTest` 完整 8 项全通过（BUILD SUCCESS，211.7 s）：三类文件与页面顺序及 MySQL `count(*)` 一致、10000 行成功与 10001 行 400 无部分文件、公式样例按文本读回、车队长仅授权车队且越权 403、审计含条件/行数/结果且无正文、删除 `alarm_handle_log` 后导出 500 且无附件、失败审计 `reason=QUERY_FAILED`。
- 回归 `SchemaUpgradeTest`、`AlarmHandlingFlowTest`、`TrackQueryFlowTest`、`CredentialLogHygieneTest` 共 17 项全通过；Flyway 仍为 12 个迁移至 v14。
- 合并 `integration/tickets-08-27`（tip `6cf3998`，含任务14）后复跑受影响测试 25/25 通过（合并后迁移数 13，仍为 v14），见 evidence 第七节。
- 容器已回收，C: 可用约 28.0 GB；未执行 `docker volume prune`/`system prune`。

偏差与边界：未新增前端导出入口；数据库失败用例为实现完成后补强，首次运行仅修正测试对 Spring 500 默认 `Content-Disposition` 的过严断言，产品代码未改。
