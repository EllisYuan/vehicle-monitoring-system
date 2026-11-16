# 19: 有权限的监控统计

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 13

## What to build

在档案、告警与轨迹事实之上实现统计页。今日指标包含车辆总数、在线数、离线数、今日报警数、PENDING 待处理数、PROCESSING 处理中数，今日按 Asia/Shanghai 00:00 至当前计算。另提供日期趋势、报警类型分布与车队聚合。处理率以同一 start_time 时段内创建的全部报警为分母，分子为该批当前 HANDLED/IGNORED 的记录，零分母显示“—”，页面明确标注包含误报，使指标可复算。在线只计启用且绑定在线终端的车辆；离线只计曾接入且满足离线条件者；未接入、停用、主动断开、启动待确认分别单列，在线加离线不必等于总数。所有查询先按角色与授权 fleet 过滤，不能信任客户端传入的 fleet_id。当前车辆统计按当前档案，历史报警按 fleet_id_at_event。数据不足显示 0 或“—”，不伪造、不补数。离线分类可用受控时钟或固定 fixture 高效验证，生产阈值仍为 180 秒；若执行真实等待用例，记录实际时段与结果。

## Spec coverage

主：US78、US79、US80、US81，AC25。次（复验）：US43 与 AC02 中统计入口、车队聚合的权限项。详见 [spec](../spec.md)。

## Acceptance criteria

- [x] 今日指标按 Asia/Shanghai 00:00 至当前计算，跨日构造数据不串日
- [x] 处理率分子为同批 HANDLED+IGNORED，分母为同批创建总数，零分母显示“—”并标注含误报
- [x] PENDING 与 PROCESSING 分别呈现，不合并成一个数字
- [x] 在线、离线、未接入/停用/主动断开/启动待确认分列，不强行凑总数
- [x] 车队聚合中历史报警按 fleet_id_at_event、当前车辆及在线情况按当前档案归属计算
- [x] 车队长仅见授权车队；篡改查询参数、区间或起始日期不扩大结果
- [x] 趋势、类型、车队三视图与明细数据可相互复算一致
- [x] 离线分类以受控时钟/fixture 验证通过，并记录方法

## Scope boundaries

只做统计读取与页面，不改告警判定、轨迹保留或导出实现；不新增表、不扩角色、不加入里程、油耗、利用率等指标；不做异步报表中心或额外第三方开放平台 API；为统计页面提供的正常 HTTP 接口属于本票。统计与导出共用权限口径，但两者独立交付，后票不代本票补测试。

## Validation

通过 HTTP 边界以三角色、两车队构造数据验证；结果与真实 MySQL 直查对照；用受控时钟推进验证今日边界与离线分类；记录环境、版本、命令、期望与实际结果及证据位置。未执行的项不得标记通过。

## Comments

依赖 13 提供告警与处置数据；事件车队字段以实际 migration 为准。与 20 号导出票共享权限与口径，但不复用其实现判断。

## Answer

已实现并验证（2026-11-16，分支 `feat/ticket-19-monitoring-statistics`）。

- 后端新增 `/api/v1/statistics` 四视图（GET `overview`/`trend`/`types`/`fleets`），权限 `FLEET_VIEW`，数据范围一律经 `FleetScopeSql` 过滤，显式越权 `fleetId` 返回 403 `FORBIDDEN`；区间非法（起始晚于结束、晚于今日、超过 90 天）返回 400 `INVALID_DATE_RANGE`。
- 口径：`overview` 固定 Asia/Shanghai 当日 `[00:00, now]`（注入 `Clock`，测试用 `MutableClock`）；趋势、类型、车队三视图与总览共用同一 `start_time` 报警批次和同一车辆状态分类；车辆按当前 `fleet_id`、历史报警按 `fleet_id_at_event`；处理率分子为同批 HANDLED+IGNORED、分母为同批创建总数，零分母 `percent=null`（页面显示“—”），标注“含误报”；在线/离线/未接入/停用/主动断开/启动待确认/未绑定分列，离线仅依据已提交 `last_seen_at` 与 180 秒阈值；启动待确认宽限由 `vms.statistics.startup-grace-seconds`（默认 180）控制。
- 前端 `/monitor/stats` 统计页（`web/src/features/statistics/`），含区间查询、趋势/类型/车队表与零分母“—”；`pnpm typecheck`/`lint`/`build` 均 exit 0，本票文件 eslint 0 problems。
- 验证：统计切片 8 项测试全绿（`19-green-statistics.log`），回归 `AlarmHandlingFlowTest`/`SchemaUpgradeTest`/`TelemetryFlowTest` 共 18 项全绿（`19-regression-alarm-schema-telemetry.log`），浏览器 e2e 44 项全绿并含新增 `statistics.spec.ts`（`19-playwright.log`）；未新增表/迁移。
- 证据：`.scratch/vehicle-monitoring/evidence/19-verification.md` 与 `evidence/19-*.log`。
