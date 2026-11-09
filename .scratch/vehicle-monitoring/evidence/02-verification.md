# 任务 02 验证记录

状态：2026-11-09 恢复验证完成。最终代码合并回归 Maven 45 项全部通过（44 项后端与集成测试 + 1 项真实浏览器集成入口），零失败、零错误、零跳过；该入口内 Chromium 12 项全部通过。前端类型、lint、生产构建通过。

## 环境与资源边界

| 项 | 实际配置 |
|---|---|
| 系统 | Windows 11 Pro for Workstations |
| JDK | Alibaba Dragonwell 21.0.9.0.9；Maven 用 JAVA_HOME 显式指定，不使用 PATH 的 JDK 17 |
| Maven | Wrapper 3.9.12 |
| Node / pnpm | 24.11.1 / 10.16.1 |
| MySQL | Testcontainers mysql:8.4，最大 512 MiB、1 CPU，buffer pool 128 MiB |
| Redis | Testcontainers redis:8.4，最大 128 MiB、0.5 CPU |
| 浏览器 | 本机已有 Chromium 1234，VMS_E2E_CHROMIUM 指向其 executable |
| 测试并发 | Surefire forkCount=1、reuseForks=false、测试 JVM Xmx512m；Playwright workers=1 |
| 自动清理 | Ryuk 保持启用；测试类退出释放 MySQL/Redis，浏览器启动器 finally 停止其创建的 Vite/Playwright 子进程 |

不启动或读取正常开发库口令，不修改其他项目容器。测试期间采样 MySQL 约 328 MiB、Redis 约 15 MiB；只同时运行一组数据库和缓存及 Ryuk。最终回归结束后核查 org.testcontainers 标签无残留；正常开发容器和其他项目未清理。

## 执行方式

后端与真实浏览器统一入口：设置 JAVA_HOME，取消 TESTCONTAINERS_RYUK_DISABLED，设置本机 Chromium executable，然后执行 Maven Wrapper 的 `-B -Dvms.browser-tests=true test`。

只执行浏览器集成入口时使用 `-Dtest=BrowserFlowTest -Dvms.browser-tests=true test`。BrowserFlowTest 启动实际随机端口的 Spring Boot 服务和真实 MySQL/Redis，生成随机的仅测试管理员口令，以子进程环境传给 Playwright，不打印口令，不写本地凭证文件。Vite 通过 VMS_API_TARGET 指向该后端，使用随机可用端口，结束后自动停止。

前端检查：`pnpm --dir web typecheck`、`pnpm --dir web lint`、`pnpm --dir web build`。typecheck 已改为 `vue-tsc -b`，实际检查引用的 app/node TypeScript 项目，避免空根项目误报通过。

## 测试边界

- 后端：MockMvc 公开 HTTP 请求，真实 MySQL 8.4、Redis；权限拒绝、数据库事务与实际会话失效均有观测。
- 并发：通过公开 HTTP 同步发起，PasswordHasher 测试替身仅控制调度窗口，仍执行 BCrypt，不替代密码判断。
- 时间：可控业务 Clock 验证锁定10分钟；独立短到期上下文验证绝对期限，生产配置断言28800秒、不滑动。
- 浏览器：实际 Spring Boot socket + Vite + Chromium，测试真实登录、两布局、角色菜单/URL、改密、旧会话提示；网络异常用浏览器请求拦截验证页面行为，不冒充服务端故障恢复已验收。

## 修复与 red → green 记录

| 问题 | 复现及修复 | 证据 |
|---|---|---|
| Sa-Token 错连本机固定Redis | @ServiceConnection不改alone-redis属性；DynamicPropertyRegistrar绑定本次容器映射端口 | IntegrationContainers；AccountSessionTest通过 |
| 锁定过期后新周期提前再锁 | 新增expiredLockStartsANewFailureCycle先失败；SQL先按旧locked_until计算计数，再按新计数设置锁定，锁未到期不更新 | logs/02-lock-expiry-red.log；后续锁定5项通过 |
| 并发来源限速绕过 | 10个并发请求曾全进入认证；Permit原子占位，成功/非凭证异常释放，失败保留，3次额度只放行3个 | logs/02-rate-concurrency-red.log；LoginRateLimitTest 4项通过 |
| 改密审计失败仍改密码 | 临时使audit_log不可写，旧代码接口失败但旧密码无法登录；改为密码与审计同事务 | logs/02-audit-and-rate-red.log；AccountSessionTest通过 |
| 并发改密覆盖管理员重置 | 调度两HTTP请求，旧代码改密204覆盖重置；WHERE auth_version + ENABLED守卫，旧变更409 | logs/02-password-race-red.log；AccountSessionTest通过 |
| 账号状态/密码整行覆盖 | 密码、状态、授权分别定向SQL，auth_version原子递增 | AccountSessionTest、FleetAccessTest |
| 审计断言被setUp污染 | 按本次target选择审计，而非全表anyMatch；重置请求明确断言204 | FleetAccessTest、AccountSessionTest |
| 前端恢复与导航 | 401只由该请求指定处理者处理、保留redirect；5xx保留token并提示不可用；非管理员不能area=admin；退出中断也本地退出 | Playwright恢复、布局、改密用例 |
| 浏览器测试本身的启动与时序 | Java探针用HTTP/1.1；缺默认Chromium改用现有executable；登录后等待current-user再跳转 | 失败日志保留，最终12项通过 |

锁定响应统一401 INVALID_CREDENTIALS，不因账号锁定返回423泄露存在性；本次不更改5次失败/10分钟业务阈值。来源限速对进行中请求先占额度，成功结束释放，所以成功登录不消耗已完成失败配额；活动来源最多10000条，队列受来源额度约束。

## 验收结果

| 验收 | 当前结果 | 测试/证据 |
|---|---|---|
| 三角色、每账号单角色、多车队授权 | 通过 | FleetAccessTest 9项 |
| 车队长跨队列表/详情/过滤拒绝 | 通过 | FleetAccessTest |
| 本票HTTP功能权限矩阵 | 通过 | FleetAccessTest |
| 8小时绝对、不滑动 | 通过 | FleetAccessTest配置 + SessionExpiryTest短窗口实测 |
| 后登录替换旧会话且明确提示 | 通过 | AccountSessionTest + Chromium |
| 连错5次锁10分钟、成功重置、过期新周期、并发不续锁 | 通过 | LoginLockoutTest 5项 |
| 改密/重置/停用/撤权后旧HTTP会话失效 | 通过 | AccountSessionTest 9项 |
| 审计与改密原子性、凭证不入日志 | 通过 | AccountSessionTest、FleetAccessTest、CredentialLogHygieneTest |
| 并发来源限速、成功不耗失败配额 | 通过 | LoginRateLimitTest 4项 |
| 后端全部业务/数据库测试 | 通过 | 最终合并回归中的44项，零失败 |
| Chromium真实交互 | 通过 | 12 passed，logs/02-playwright.log；BrowserFlowTest 1 passed，logs/02-browser-final.log |
| TypeScript、ESLint、生产构建 | 通过 | vue-tsc -b、eslint、Vite build；生产构建完成 |
| 后端可运行制品打包 | 通过 | Maven -DskipTests package，logs/02-package.log BUILD SUCCESS；功能测试由上述全量回归承担 |
| 最终代码合并全量回归 | 通过 | logs/02-all-green.log：Tests run:45, Failures:0, Errors:0, Skipped:0，BUILD SUCCESS |

最终 JUnit XML 汇总：AccountSessionTest 9、BootstrapAdminTest 3、BrowserFlowTest 1、CredentialLogHygieneTest 1、FleetAccessTest 9、LoginFlowTest 11、LoginLockoutTest 5、LoginRateLimitTest 4、SchemaUpgradeTest 1、SessionExpiryTest 1，合计45，failures/errors/skipped均0。BrowserFlowTest内部12个Chromium场景另计，不能将它们与45简单相加当作独立后端测试数。

原始开发阶段的错误、失败和 Docker 中断不能当作通过；上述结果来自恢复清理后实际执行。详细日志写入 logs，日志不进入Git；可追溯摘要保存在本文。浏览器截图为 web/test-results/admin-account-page.png，已目视检查账号页显示与自己停用按钮隐藏；test-results属于临时测试产物。

审阅后跟进回归：BrowserFlowTest + LoginLockoutTest，6项全部通过、零失败/错误/跳过（logs/02-review-followup.log），其中Chromium再次12项通过。并发锁定已改为公开HTTP请求，子进程双层finally及存活句柄强制清理路径验证通过。

## 两轴审阅

- Standards：发现并发锁定测试直调service、子进程清理保障不足，均已修正为公开HTTP并发请求和双层finally/保存子进程句柄；跟进回归见 logs/02-review-followup.log。来源IP在Nginx后的可信代理配置属于部署工单27，已明确移交；本票直连HTTP验收不代表代理部署验证。
- Spec：按I3/I4、AC02–AC04的HTTP范围未发现剩余可复现缺陷。WS/导出仍按原任务拆分验收，没有扩大本票完成声明。
- 审阅基线为 a422cacf31771b8d895b14dad5ae21caf0d5cc11 的工作区改动，并包含未跟踪新增文件；本轮没有commit，不假称已有提交差异。

## 范围与后续

- 02只交付HTTP账号/车队权限，实际WS撤权由17、Excel由20执行，本票不宣称这些未来能力已通过。
- 登录来源取直接remoteAddr。部署到Nginx时，可信代理转发/防伪配置由27验证，不能直接信任任意X-Forwarded-For。
- 仍为单实例内存来源限速，不宣称分布式。
- 正常开发MySQL/Redis保持原状；没有本次创建的永久开发容器。
- Linux Docker Compose、车辆终端/司机档案、TCP与地图为后续ticket，本次不实现。
- 不改原始Word，不填论文测试通过，不commit/push。
