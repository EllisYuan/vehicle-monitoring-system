# 任务 04 验证记录：司机档案与关联历史

## 范围

实现 `driver` 与 `vehicle_driver_binding`：司机仅采集 `driver_no`、`name`、`phone`、`fleet_id`、`status`；每车至多一名当前关联司机；关联使用生效区间、变更不倒填；管理员维护档案与关联；监控员全企业只读；车队长仅授权车队且 phone 中间四位掩码。

本记录只覆盖本票范围。报警快照与「报警关联当时司机」留待 12/13 复验。

## 交付物

| 类型 | 路径 |
|---|---|
| Migration | `server/src/main/resources/db/migration/V5__driver_and_association_history.sql` |
| 后端服务 | `server/src/main/java/com/vms/fleet/DriverService.java` |
| 后端接口 | `server/src/main/java/com/vms/fleet/DriverController.java`、`DriverBindingController.java` |
| 权限 | `Permission.DRIVER_MANAGE`（仅 ADMIN） |
| 审计 | `AuditService.CATEGORY_DRIVER` |
| 前端 API | `web/src/features/drivers/api.ts` |
| 前端页面 | `web/src/features/drivers/views/DriverListView.vue` |
| 路由与导航 | `web/src/app/router.ts`（`/monitor/drivers`、`/admin/drivers`）、`AppShell.vue` |
| 后端测试 | `server/src/test/java/com/vms/fleet/DriverFlowTest.java` |
| 浏览器测试 | `web/e2e/drivers.spec.ts` |
| 受影响既有测试 | `server/src/test/java/com/vms/identity/SchemaUpgradeTest.java`（migration 数与表清单） |

模块归属按设计 §3 的 server 包表处理：该表将「车队、车辆、司机及关联历史」划给 `fleet`，故司机代码放在 `com.vms.fleet`，不新建 `driver` 包。

## 接口

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/drivers?fleetId=` | `FLEET_VIEW` | 档案列表，按授权范围过滤；车队长 phone 掩码 |
| `GET /api/v1/drivers/{id}` | `FLEET_VIEW` | 档案详情 |
| `POST /api/v1/drivers` | `DRIVER_MANAGE` | 登记司机 |
| `PUT /api/v1/drivers/{id}` | `DRIVER_MANAGE` | 修改 driver_no、name、phone、status |
| `GET /api/v1/drivers/{id}/vehicle-bindings` | `FLEET_VIEW` | 司机视角关联历史（该司机关联过的车辆区间） |
| `GET /api/v1/vehicles/{id}/driver` | `FLEET_VIEW` | 车辆当前司机；司机越授权范围时按未关联返回 |
| `PUT /api/v1/vehicles/{id}/driver` | `DRIVER_MANAGE` | 关联或解除；同车队换车在本事务内结束旧区间 |
| `GET /api/v1/vehicles/{id}/driver-bindings` | `FLEET_VIEW` | 车辆视角关联历史（该车历任司机） |

## 数据库设计要点

- `driver`：唯一键 `uk_driver_no`，`ck_driver_status` 限定 `ENABLED|DISABLED`，`ck_driver_phone` 限定 11 位数字，外键 `fk_driver_fleet`。
- `vehicle_driver_binding`：`effective_from` / `effective_to` 半开区间，`ck_driver_binding_interval` 保证 `effective_to > effective_from`。
- 两个 generated column 承担并发约束：`current_vehicle_id`（`effective_to IS NULL` 时取 `vehicle_id`）上建 `uk_driver_binding_current_vehicle`，`current_driver_id` 上建 `uk_driver_binding_current_driver`。前者对应 spec「车辆同时至多一名关联司机」；后者额外保证「一名司机同时只关联一辆车」，是换车语义与数据范围一致性的前提。
- 停用司机与历史区间不删除：外键阻止删除被引用的司机，历史行保留 `effective_to` 快照。

## 关联规则

1. **同车队约束**：`bind` 比较 `driver.fleet_id` 与车辆当前 `fleet_id`，不一致返回 `DRIVER_FLEET_MISMATCH`。缺少该约束时，A 队车队长可经车辆详情读到 B 队司机的姓名与掩码电话，B 队车牌也会经司机列表泄露给 A 队。
2. **换车原子化**：`PUT /vehicles/{id}/driver` 在持有司机行锁的同一事务内结束该司机原有区间并建立新区间，前端不需要「先解绑再关联」的两步补偿，不会留下已解绑的中间态。
3. **历史双视角**：
   - 司机视角 `GET /drivers/{id}/vehicle-bindings` 返回该司机关联过的车辆区间；车辆越授权范围的行不返回。
   - 车辆视角 `GET /vehicles/{id}/driver-bindings` 返回该车历任司机；司机越授权范围的行不返回。
   - 页面「关联历史」使用司机视角，避免把同车其他司机的区间误显示为该司机的历史。

## 执行结果

### 环境

Windows 11、JDK 21.0.9、Maven Wrapper 3.9.12、Docker 29.1.3、MySQL 8.4 与 Redis 8.4 Testcontainers、Playwright 1.58.1 与 Chromium 1208、业务时钟 Asia/Shanghai。

### 命令与结果

| 时间 | 命令 | 结果 |
|---|---|---|
| 2026-11-10 | `mvnw.cmd -B -pl server -Dtest=DriverFlowTest test`（实现前） | 6 项全部失败，均为 `Status expected:<201> but was:<404>`，确认红灯 |
| 2026-11-10 | 同上（实现后） | `Tests run: 6, Failures: 0` |
| 2026-11-10 | 补审计与数据库约束用例后 | `Tests run: 8, Failures: 0, Errors: 0` |
| 2026-11-10 | `pnpm --dir web typecheck` / `lint` / `build` | 全部退出码 0 |
| 2026-11-10 | `mvnw.cmd -B "-Dvms.browser-tests=true" test`（审阅修复前） | `Tests run: 66, Failures: 0`，BUILD SUCCESS |
| 2026-11-10 | 审阅修复后补测 | `DriverFlowTest` 13 项通过 |
| 2026-11-10 | **最终全量回归** | `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| 2026-11-10 | 同轮 Playwright | `16 passed`，含新增 2 个司机场景（`drivers.spec.ts`） |

日志：`evidence/04-test-run.log`（最终回归）、`evidence/logs/02-playwright.log`（`BrowserFlowTest` 固定写入该文件名）。

### 测试分类明细（最终回归）

| 测试类 | 项数 | 结果 |
|---|---|---|
| DriverFlowTest | 13 | 通过 |
| ArchiveFlowTest | 13 | 通过 |
| AccountSessionTest | 9 | 通过 |
| BootstrapAdminTest | 3 | 通过 |
| BrowserFlowTest | 1（内含 16 个 Chromium 场景） | 通过 |
| CredentialLogHygieneTest | 1 | 通过 |
| FleetAccessTest | 9 | 通过 |
| LoginFlowTest | 11 | 通过 |
| LoginLockoutTest | 5 | 通过 |
| LoginRateLimitTest | 4 | 通过 |
| SchemaUpgradeTest | 1 | 通过 |
| SessionExpiryTest | 1 | 通过 |
| **合计** | **71** | **全部通过** |

测试结束核对 `docker ps -a`：Testcontainers 与 Ryuk 容器已自动回收，无残留；其他项目的开发容器未受影响。

## 验收项对照

- [x] 司机档案仅含 driver_no、name、phone、fleet_id、status 五项业务字段 —— 迁移中无证件号与影像列；`DriverFlowTest` 断言响应无 `idCardNo`、`licenseNo`。
- [x] 每车至多一条当前关联，真实 MySQL 并发维护不重叠 —— `uk_driver_binding_current_vehicle` 由数据库强制；`concurrentAssociationLeavesExactlyOneCurrentDriverPerVehicle` 用双线程并发写入后核对 `current_vehicle_id` 计数为 1；`databaseRejectsTwoCurrentAssociationsAndDeletingReferencedArchives` 直接用 SQL 插入第二条当前关联，确认抛 `DuplicateKeyException`。
- [x] 关联变更保留生效区间历史，不倒填 —— `associationKeepsHalfOpenHistoryAndUnbindingShowsUnassociated` 核对前段 `effective_to` 等于后段 `effective_from`，末段 `effective_to` 为 null；浏览器场景核对页面展示「至今」。
- [x] 车队长响应中 phone 中间四位掩码，管理员/监控员完整 —— `leaderSeesMaskedPhoneAndOnlyGrantedFleetsWhileMonitorAndAdminSeeFullPhone` 同时校验三角色；浏览器场景校验页面文本 `138****5678`。
- [x] 车队长只能查看授权车队司机，跨队请求被拒 —— 同一用例校验车队长列表仅含授权车队，且带其他 `fleetId` 查询返回 403；`DriverService.list` 经 `FleetScopeSql` 过滤并显式拒绝越范围 `fleetId`。
- [x] 停用司机保留历史引用，可解释既有记录 —— `disabledDriverStaysReferencedByHistoryAndCannotBeNewlyAssociated` 确认停用后历史仍含该司机；外键阻止删除被引用司机。
- [x] 无法确定关联时显示未关联，不猜测身份或归属 —— `current` 无关联时返回 `driverId` 为 null 的结构，页面渲染「未关联」。

## 代码审阅后的修正

两轴审阅（Standards + Spec）发现以下问题，均已修正并补测：

| 问题 | 修正 | 覆盖测试 |
|---|---|---|
| 新建 `com.vms.driver` 与设计 §3 包表冲突（该表把司机划给 `fleet`） | 迁入 `com.vms.fleet` | 包结构，编译即证 |
| 车辆状态直接查 `vehicle` 表（Feature Envy，绕过 `VehicleService`） | 改为 `vehicles.get` + 新增 `vehicles.createdAt` | 既有用例 |
| 页面「司机历史」取的是**车辆**历史，会显示同车其他司机的区间 | 新增司机视角接口 `GET /drivers/{id}/vehicle-bindings`，页面改用它 | `driverHistoryIsDriverCentricNotVehicleCentric` |
| 未校验司机与车辆同队，车队长可跨队看到姓名与电话 | 新增 `DRIVER_FLEET_MISMATCH` | `driverAndVehicleMustBelongToSameFleet` |
| 前端换车为两步操作，第二步失败会留下已解绑中间态 | 服务端单次请求内原子换车 | `movingDriverToAnotherVehicleEndsPreviousIntervalAtomically` |
| `driver_no` 创建后不可修改（US18 要求可维护） | `UpdateRequest` 增加 `driverNo`，重复返回 `DRIVER_NO_EXISTS` | `driverNoCanBeCorrectedAndDuplicateIsRejected` |
| 车辆视角历史对车队长一律 403，范围过粗 | 改为按授权范围过滤行，不整体拒绝 | `leaderSeesOnlyOwnFleetDriversAndNeverCrossFleetCarPlates` |

未采纳的判断项：`DriverService.bind` 与 `TerminalService.bind` 的锁与区间结构相似，但两者的换车语义与并发条件不同，抽取公共辅助会引入额外抽象；前端列表页的 `load/perform/refresh` 与 scoped CSS 与档案页相似，属页面级复用，待第三个同类页面出现时再抽取。

## 已知偏差与后续事项

1. **`SchemaUpgradeTest` 需随每个新 migration 更新**：该测试硬编码了 migration 数量与表清单，本票将其从 4 改为 5、表清单加入 `driver` 与 `vehicle_driver_binding`。后续 06 起每票加 migration 都会触及此文件，属预期维护成本。
2. **`BrowserFlowTest` 的 Playwright 日志文件名固定为 `02-playwright.log`**：该名称沿用任务 02，本票未改名。本记录附注了实际归属，避免误认为 02 的证据；如需改名属测试基础设施调整，不在本票范围。
3. **`BrowserFlowTest` 的 Playwright 日志文件名固定为 `02-playwright.log`**：该名称沿用任务 02，本票未改名。本记录附注了实际归属，避免误认为 02 的证据；如需改名属测试基础设施调整，不在本票范围。
4. **`driver_no` 可修改但不可改 `fleet_id`**：US18 要求维护编号、姓名、电话、车队与状态；ticket 同时写明「本票不复验司机转队（无对应需求）」，故 `fleet_id` 在创建后不可改，避免引入未定义的转队区间语义。需要调整司机归属时，应作为后续 ticket 明确其历史口径。
5. **报警快照未在本票验证**：按 ticket 与 spec 分段验收约定，报警生成时的 `driver_id` 与关联信息快照由 12/13 复验，本票只保证关联历史可解释、可追溯。
6. **司机转队未实现**：`driver.fleet_id` 为档案归属，不是生效区间历史。
7. **关联维护入口仅管理员**：车队长对司机为只读，不存在司机档案的越权编辑入口，符合 Scope boundaries。
