# 04: 司机档案与关联历史

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 03

## What to build

实现 driver 与 vehicle_driver_binding：司机仅采集 driver_no、name、phone、fleet_id、status，不采集证件号或影像。每车至多一名当前关联司机，关联使用生效区间，变更不倒填，并发维护不形成重叠当前关系，历史保留。管理员维护档案与关联；监控员全企业只读；车队长仅授权车队且 phone 中间四位掩码，管理员与监控员按职责看完整号码。停用司机保留历史引用。为后续报警提供数据基础：报警将固定生成时的 driver_id 与关联信息，本票只保证关联历史可解释、可追溯，报警快照在 12/13 复验。页面提供档案列表、编辑与车辆关联操作，关键写操作与授权变化留审计。

## Spec coverage

- 主：[AC05](../spec.md) 司机关联部分；US18、US19、US21（关联无法确定时显示未关联）、US24。
- 次：US20、US22/US23 的档案停用与历史引用；AC06 的“报警关联当时司机”由 12/13 复验。
- 复验：12/13 的报警快照不能替代本票关联区间与掩码验证；本票不复验司机转队（无对应需求）。

## Acceptance criteria

- [x] 司机档案仅含 driver_no、name、phone、fleet_id、status 五项业务字段
- [x] 每车至多一条当前关联，真实 MySQL 并发维护不重叠
- [x] 关联变更保留生效区间历史，不倒填
- [x] 车队长响应中 phone 中间四位掩码，管理员/监控员完整
- [x] 车队长只能查看授权车队司机，跨队请求被拒
- [x] 停用司机保留历史引用，可解释既有记录
- [x] 无法确定关联时显示未关联，不猜测身份或归属

## Scope boundaries

- 不做报警与快照逻辑（12/13），不做司机 App/H5、证照采集与驾驶员身份认证。
- 不实现车辆/终端档案本身（03）；不做司机为车队长可维护的越权入口。
- 本票不替代 03 的绑定并发测试。

## Validation

- 真实 HTTP 以三角色校验字段、掩码与数据范围；真实 MySQL 并发制造双当前关联并确认被拒。
- 浏览器验证列表、编辑与关联；SQL 核对区间与历史。
- 记录命令、环境与结果；状态不得预填。

## Comments

（暂无讨论）

## Answer

已实现并验收，验证记录见 [04-verification.md](../evidence/04-verification.md)。

交付：`V5__driver_and_association_history.sql`（`driver`、`vehicle_driver_binding`）、`com.vms.fleet` 下的 `DriverService`/`DriverController`/`DriverBindingController`、前端 `features/drivers/` 与 `/monitor/drivers`、`/admin/drivers` 路由。

结果：`DriverFlowTest` 13 项、`drivers.spec.ts` 2 项浏览器场景、全量 `Tests run: 66` 与 Playwright 16 项均通过。

两处实现取舍记录：
1. **司机模块归入 `com.vms.fleet`**：设计 §3 的 server 包表已将「车队、车辆、司机及关联历史」划给 `fleet`，故不新建 `driver` 包。
2. **在同一车队内换车为一次原子操作**：`PUT /vehicles/{id}/driver` 会在本事务内结束该司机原有区间，避免前端两步补偿留下已解绑的中间态；绑定要求司机与车辆属于同一车队（`DRIVER_FLEET_MISMATCH`），否则车队长可经车辆详情读到其他车队司机的姓名与电话。

历史读取分两个视角：`GET /drivers/{id}/vehicle-bindings` 为司机视角（该司机关联过的车辆区间），`GET /vehicles/{id}/driver-bindings` 为车辆视角（该车历任司机）；两者都按授权范围过滤，越范围的行不返回而不是猜测归属。

未覆盖：报警生成时的司机快照由 12/13 复验；司机转队无对应需求，`driver.fleet_id` 为档案归属而非生效区间历史。
