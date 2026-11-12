# 06: 模拟终端接入与通信状态

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 03, 05

## What to build

建立 Netty TCP 接入与最小 Java CLI 模拟终端：CLI 通过真实 TCP 完成注册（0x0100/0x8100）、鉴权（0x0102）、心跳（0x0002）、注销（0x0003）与双向通用应答（0x0001/0x8001）。仅预登记、启用且绑定的终端可接入；注册不自动建档；鉴权码由平台注册响应下发，服务端保存摘要比对，不明文展示或写入日志。未知、停用、未绑定、鉴权失败终端按可信身份规范拒绝，不维护其在线状态、不写车辆数据。当前 Channel 与 connection_epoch 只由 access 进程内注册表确认；新鉴权替换旧连接，旧会话应答不能完成新指令。合法注销应答后撤销连接代次，非主动断线立即停止可发送性。页面展示 last_seen 与 ever_authenticated，区分“未接入”与离线。指令代次与应答关联留给 16 复验。

## Spec coverage

- 主：[AC08](../spec.md)；US25、US26、US35、US36 的接入部分。
- 次：AC17 接入部分（新替旧、注销）；US39 最后通信时间的展示基础。
- 复验：指令代次与旧会话应答不误配由 16 验证；本票实现 epoch 与 Channel 权威，不替代 16 的测试，也无指令实现。

## Acceptance criteria

- [x] CLI 经真实 TCP 完成注册、鉴权、心跳、注销，报文为实际字节
- [x] 未登记/停用/未绑定/鉴权失败终端被拒，不自动建档、不维护在线
- [x] 新连接鉴权后旧连接失效，旧代次不再作为有效通信来源；真实指令应答匹配由 16 复验
- [x] 合法注销正确应答并撤销连接代次，在途可发送性停止
- [x] 页面显示 last_seen 与 ever_authenticated，“未接入”不显示为离线
- [x] 鉴权码不明文展示或写入日志

## Scope boundaries

- 不做位置上报与地图（07）、指令（16）、离线报警判定（14）与围栏（15）。
- 不做真实终端认证或完整标准互通声明；CLI 为模拟数据来源，其位置和状态属模拟数据。
- 本票的 TCP 行为不替代 05 的独立 fixture，也不替代 03 的档案约束测试。

## Validation

- 以 CLI 对真实 TCP listener 发送真实报文，记录脱敏报文证据，鉴权码及注册应答中的鉴权材料不进入 hex 日志；以 HTTP 页面核对通信状态；SQL 核对未知终端未建档、未写入。
- 记录命令、环境、版本、时段与期望/实际结果；状态不得预填。

## Comments

- 2026-11-12：任务 05 已在最新 `main`（`4d58a23`）回归通过后开始本票实现；本票按真实 TCP public seam 验收，未扩展位置、指令、报警或围栏。

## Answer

已完成：

- 新增 Netty TCP listener 与 Java CLI simulator；listener 使用实际 TCP 字节、`FrameCodec` 和 `0x0100/0x8100`、`0x0102/0x8001`、`0x0002/0x8001`、`0x0003/0x8001` 流程。
- 仅允许预登记、`ENABLED` 且绑定 `ENABLED` vehicle 的 terminal 注册；未知、停用、未绑定和错误鉴权均拒绝，注册不会自动创建 terminal。
- 注册鉴权材料只在内存和注册应答中传递，数据库写入 `auth_secret_hash` BCrypt 摘要；集成测试确认数据库值以 `$2` 开头，未鉴权失败时不写 `ever_authenticated` 或 `last_seen_at`。
- `TerminalConnectionRegistry` 以当前 `Channel` 对象和单调 `connection_epoch` 作为连接权威；新鉴权关闭旧 Channel，旧 session 不能删除新 session；注销和非主动断连清理当前 session。
- terminal view 增加 `lastSeenAt`、`everAuthenticated`、`communicationStatus`，页面展示档案状态、通信状态和最后通信时间；`NOT_CONNECTED` 不显示为 `OFFLINE`，在线判断同时受 terminal、vehicle 和当前 registry session 约束。

验证：

- `./mvnw.cmd -B -pl server -am '-Dtest=TerminalTcpIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：13 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- `./mvnw.cmd -B test`：Maven reactor 串行通过；protocol 64 tests、server 84 tests、simulator 无测试，共 148 tests，0 failures、0 errors、1 skipped（`BrowserFlowTest`）；root、protocol、server、simulator 四个 module 均 `SUCCESS`，最终 `BUILD SUCCESS`，Surefire 保持 `forkCount=1`、`reuseForks=false`。
- `./mvnw.cmd -B -pl server,simulator -am package -DskipTests`：server 与 simulator reactor package 通过，`BUILD SUCCESS`。
- `pnpm --dir web typecheck`：通过。
- `pnpm --dir web lint`：通过。
- `pnpm --dir web build`：通过。
- 独立 CLI 真实 TCP 验收：2026-11-12 13:42（Asia/Shanghai），Java 21.0.9、Windows 11、server TCP `19000`、HTTP `18080`、独立 MySQL `mysql:8.4` 和 Redis `redis:8.4` 临时容器；命令为 `C:\Java\jdk-21.0.9\bin\java.exe -cp simulator\\target\\classes;protocol\\target\\classes com.vms.simulator.TerminalSimulator --host 127.0.0.1 --port 19000 --terminal 01380013800000000099`，退出码 0，脱敏输出为 `register: accepted`、`authentication: accepted`、`heartbeat: accepted`、`logout: accepted`；stderr 为空，未输出鉴权码。HTTP 复核显示 `everAuthenticated=true`、注销后 `communicationStatus=OFFLINE`；SQL 复核 `auth_secret_hash` 仅记录 `$2a` 前缀。
- Testcontainers 日志确认本轮启用 Ryuk；测试结束后 `docker ps -a` 未发现本轮 Ryuk、MySQL、Redis 容器遗留。仍运行的既有 `mysql:8.4` 开发容器未处理。

已知边界：

- CLI 独立进程验收已完成；server integration test 与独立 simulator 均通过真实 Socket 字节流程。
- `0x0001` 双向通用应答只保留当前 session 的接收/拒绝边界，真实指令与应答代次留给任务 16。
