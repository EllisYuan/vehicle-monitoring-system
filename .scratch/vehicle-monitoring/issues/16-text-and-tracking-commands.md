# 16: 文本与临时跟踪指令

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 07

## What to build

页面与独立 CLI 发起文本（0x8300）与临时跟踪（0x8202）指令，验收以实际 TCP 报文行为为准。文本最多 100 汉字，按真实 GBK 字节校验；跟踪间隔 1–60 秒、有效期最多 3600 秒，不超过单包容量。状态区分 SUBMITTED、SENT、ACK_SUCCESS、FAILED、TIMEOUT，成功仅表示终端应答。指令关联 terminal_id、connection_epoch、serial_no 与应答消息类型；默认 30 秒超时、不自动重发，手工重试创建新记录；离线拒绝不排队；迟到或旧 epoch 应答留痕，不改写历史；合法注销时在途指令 FAILED 收尾。页面提交状态与通信结果分开展示。写审计，车队长不可发送。模拟终端实际接收、正确应答并改变临时上报行为。指令日志展示提交、发送、应答与超时时刻；超时与失败不表述为司机已读或已执行。模拟终端复用共享 codec，协议正确性以 05 的独立 fixture 为准，不以自生成样例判定。本票自带 CLI、页面、API、审计与测试。

## Spec coverage

主：AC24（US 72–77），见 [spec](../spec.md#可检查验收场景)。
复验：AC17 指令部分（旧会话应答不能完成新指令，US 75），接入与连接代次由 06 交付，本票按指令语义复验。实时推送与撤权见 [17](17-websocket-revocation-and-resync.md)。

## Acceptance criteria

- [x] 页面发起文本与跟踪，模拟终端经真实 TCP 接收并应答
- [x] 超过 100 汉字或 GBK 字节超限的文本被拒
- [x] 跟踪间隔 1–60 秒、有效期最多 3600 秒校验
- [x] 状态区分 SUBMITTED/SENT/ACK_SUCCESS/FAILED/TIMEOUT
- [x] 30 秒超时不自动重发，手工重试建新记录
- [x] 离线终端拒绝发送，不排队
- [x] 旧 epoch 或迟到应答留痕，不改写历史
- [x] 合法注销时在途指令 FAILED 收尾
- [x] 车队长发送被拒，操作写入审计
- [x] 模拟终端临时上报行为实际改变

## Scope boundaries

不做任意原始指令、断油断电、参数设置、分包与加密消息；不实现超时后的自动补发；文本与跟踪的停止形态仅在标准核对后实现，未核对项标注阻塞。后票不代本票测试。

## Validation

以真实 TCP 连接模拟终端（含独立 CLI 场景），检查终端接收、应答与上报间隔变化；离线、超时、迟到与错误应答逐项构造；浏览器验证提交与结果分离显示及权限。记录命令、环境、版本、时段、期望与实际，逐条标注状态。

## Comments

（暂无）

## Answer

已实现并验证文本（0x8300）与临时跟踪（0x8202）完整链路：页面/API 发起、服务端校验、真实 TCP 下行、状态流转、审计与独立 simulator CLI 应答。验证于 2026-11-16 在锁内执行：

- CommandFlowTest 11/11 通过（80.66 s，BUILD SUCCESS，含 timeoutAt 与不自动重发断言）：fixture 报文、文本/跟踪边界、离线不排队、30 s 超时与迟到留痕、旧 epoch、终端拒绝、注销 FAILED、车队长拒绝与 MONITOR 放行、手工重试、列表分离。
- BrowserFlowTest + simulator：Playwright 28 passed（2.5m），commands.spec.ts 28.2 s；simulator 实际收到 0x8300/0x8202、回 0x0001，并在跟踪后按 1 s 上报（trackingIntervalSeconds=1），页面提交与通信结果分开展示。
- pnpm --dir web typecheck、lint、build 均 exit 0。

未执行/阻塞：0x8202 停止跟踪形态（间隔 0）缺少 05 独立 fixture，标准字节未核对，页面不暴露入口，标注 blocked；真实车载终端未接入，本轮为模拟终端。详见 evidence/16-verification.md 与 evidence/16-*.log、16-commands-ack.png。
