# 02: 账号角色与车队授权

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 01

## What to build

建立车队账号体系：创建与停用账号、管理员重置密码、本人修改密码；固定角色 ADMIN、MONITOR、FLEET_LEADER，每账号一个角色，车队长可授权一个或多个车队（sys_user、sys_user_fleet 及必要 migration）。登录 8 小时绝对到期不滑动；单账号单有效会话，后登录使旧会话失效并给出明确提示；连续失败 5 次锁定 10 分钟，成功登录重置计数，并增加来源速率限制。改密、重置、停用、撤权使旧会话立即失效（HTTP）。提供监控与管理两种页面布局及功能导航，按三角色权限渲染；本票已提供的 HTTP 账号与车队列表、详情和写操作在服务端校验功能权限与数据范围，不信任客户端传来的 fleet_id。登录失败/锁定、账号与授权变更写审计。权限判定从首个接口起生效。

## Spec coverage

- 主：[AC02](../spec.md)（三角色两车队跨队拒绝、操作矩阵 HTTP 部分）；AC04（5 次锁定、成功重置、错误响应不泄露）。
- 次：AC03 HTTP 部分（停用/改密/新登录替换/到期/撤权）；US4–US11、US12。
- 复验：WS 订阅权限由 17 实际验证，导出权限由 20 验证；本票只做 HTTP，两处拆开验证，不以计划代替执行，也不因未实现而阻塞本票关闭。

## Acceptance criteria

- [x] 三角色账号与两个车队可创建，每账号仅一个角色，车队长可分配多车队
- [x] 车队长跨队改 URL 或查询参数请求被服务端拒绝，列表/详情/聚合不泄露其他车队
- [x] 本票已交付的账号、车队和授权 HTTP 能力符合 spec I3；后续业务资源由对应 ticket 验证并在 27 全量复验
- [x] 8 小时绝对到期，不随活跃续期
- [x] 同账号重复登录使旧会话失效并有明确提示
- [x] 连续 5 次失败锁定 10 分钟；锁定前成功登录重置计数
- [x] 改密、重置、停用、撤权后旧会话 HTTP 立即失效
- [x] 账号与授权变更、登录失败与锁定写入审计，不含凭证
- [x] 隐藏菜单不是权限依据：直接请求同样被检查

## Scope boundaries

- 不实现 WS 订阅（17）、Excel 导出（20）、车辆/终端/司机档案（03、04）、TCP 与指令。
- 不重复实现 01 的登录骨架；01 已执行测试不得在本票计为通过。
- 冻结的登录/授权事件审计在本票交付，系统审计查询页属 21。

## Validation

- 真实 HTTP 以三角色分别请求跨队与越权资源，记录请求、响应与 SQL 或日志证据。
- 锁定与到期用可控业务时钟或配置缩短窗口时，须说明不改变生产语义。
- 浏览器验证两布局导航与角色差异；记录命令、环境、结果，状态不得预填。

## Comments

2026-11-09 实现复核（进行中）：

- 修复测试隔离：Sa-Token 的 `alone-redis` 只读固定端口，测试此前实际连本机 Redis；现通过 `DynamicPropertyRegistrar` 指向测试容器。
- 改为定向 SQL 更新密码/状态/授权版本，避免整行回写覆盖并发的停用、改密与授权变更。
- 失败计数改为单条 SQL 原子自增；修正 MySQL 自左向右求值导致锁定判断读到自增后新值、提前锁定的缺陷。
- 锁定期间统一返回 401 `INVALID_CREDENTIALS`，不再用 423 暴露账号存在性或锁定状态。
- 来源限速只统计失败尝试并清理过期来源；成功登录不占配额。
- 审计测试按本次操作的目标行断言，避免 setUp 写入使断言空转。
- 前端修复：网络错误不再误清 token；401 全局处理与路由守卫不再竞争丢失 redirect；改密页按来源区域渲染；账号列表隐藏自己的停用按钮；退出请求失败也完成本地退出。
- 已通过：`FleetAccessTest` 9、`AccountSessionTest` 7、`LoginFlowTest` 11、`BootstrapAdminTest` 3、`CredentialLogHygieneTest` 1、`SchemaUpgradeTest` 1、`SessionExpiryTest` 1；前端 typecheck/lint 无错误。
- 以上为清理前的阶段记录。恢复后完成锁定过期新周期、并发限速、审计失败回滚与并发改密覆盖重置的 red→green 回归；最终 Maven 45 项（包含浏览器集成入口）与该入口内 Chromium 12 项全部通过，无失败/错误/跳过，详见验证记录。
- 新增串行 fork、JVM/容器资源上限与 Ryuk 自动回收配置；最终核查无 Testcontainers 容器及本轮 Vite/Playwright 子进程残留。

## Answer

2026-11-09 已实现并完成验收，状态 `resolved`。三角色/多车队授权、HTTP数据权限、8小时绝对单会话、锁定与并发限速、改密/重置/停用/撤权、审计及两布局均有实际证据。

- 全量 Maven：45项通过，failures/errors/skipped均0；其中包含1项真实浏览器集成入口，内部Chromium 12项全部通过。
- 审阅后公开HTTP并发与子进程清理复验：6项通过，Chromium再次12项通过。
- 前端 typecheck（vue-tsc -b）、lint、生产build及后端package均通过。
- 测试串行且有资源上限，Ryuk启用；结束后无Testcontainers容器及本轮Vite/Playwright进程残留。正常开发容器与其他项目资源未清理。
- 两轴审阅：本票Spec范围无剩余可复现缺陷；Standards改进已修复，Nginx可信代理来源地址验收明确移交27。

详见 [02-verification](../evidence/02-verification.md)。WS订阅撤权、Excel与Linux部署仍分别由17、20、27执行；未改原始Word，未commit/push。
