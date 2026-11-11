# 05: JT/T 808-2019 独立报文验证

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 01

## What to build

在独立 `protocol` 模块实现 JT/T 808-2019 核心子集 codec，并以有标准条目依据的独立 hex fixture 验证预期。支持 ID 为 0x0100、0x8100、0x0102、0x0002、0x0003、0x0001、0x8001、0x0200、0x0704、0x8300、0x8202；覆盖 2019 消息头、终端手机号 BCD、GBK、转义/XOR、定长与变长 TLV、头长与体长限制、0x0704 项数/长度校验、坏帧与不支持消息的安全拒绝。

本次验收口径按用户确认：**按 JT/T 808-2019 正文完成并关闭**。下载到本地的正文未发现独立 2021 修改单，因此本票只声明“2019 正文子集符合性”；修改单影响不作为本次正文验收的阻塞项，仍在验证记录中明确披露。

## Spec coverage

- 主：[AC07](../spec.md)；US87、US36 的协议部分。
- 次：US25 的报文基础；I8 协议子集与长度限制。
- 复验：06 起实际 TCP 接入依赖本票 codec，但独立 fixture 仍须保留参与后续验证，不能被端到端通过替代；本票不做 TCP listener、会话或数据库。

## Acceptance criteria

- [x] 独立 hex fixture 覆盖 2019 消息头与转义/XOR 校验并通过
- [x] BCD、GBK、TLV 编解码与独立预期字节一致
- [x] 单包超过 1023 字节被拒绝，分包与加密消息可解释拒绝
- [x] 0x0704 项数 ≤20 与长度双重校验生效
- [x] 坏帧、未知字段、越界长度安全处理，不崩溃不拼接假 ACK
- [x] fixture 标注标准条目/出处；共享 codec 生成物不作为唯一依据
- [x] 待核字段已在本票记录：2021 修改单未取得；表 10 的 `n+9` 与 `BYTE[15]` 存在标准正文内部不一致；表 23 的时间起始字节与前序 WORD 字段长度不一致；代码按字段自洽布局解析，并在 evidence 中披露

## Scope boundaries

- 不做 TCP listener、会话、注册鉴权业务与数据库（06）；不做 JT/T 809、1078、分包重组、旧版 808 或加密。
- 本票不产生业务页面；模拟器 CLI 属 06。
- 不把根目录标准 PDF、渲染预览图或标准长文本复制进 Git；仅在 evidence 记录来源、页码、哈希和核对结论。

## Validation

- 独立测试命令：`mvnw.cmd -B -pl protocol test`。
- 本模块不启动 Spring、MySQL、Redis 或 Testcontainers。
- 记录 JDK/Maven/依赖版本、逐条 fixture 出处与实际结果；明确区分正文已核、标准内部矛盾、修改单未取得和未做的真实终端互通。

## Comments

2026-11-11：用户已提供本地 `JT-T 808-2019.pdf`。文件为 56 页 PDF，3,423,765 bytes，SHA-256 `0a4bff06234042237fdf0f1b544a6eaf1fc67f5042f9cf88179e083518c9e990`。封面显示交通运输部发布、2019-03-15 发布、2019-07-01 实施；目录显示正文和附录 A/B。

已核对正文：4.4.2/4.4.5（印刷 p.3，PDF p.7）转义与 XOR；表 2/3（印刷 p.4，PDF p.8）消息头与封装项；表 4/5（p.9-10，PDF p.13-14）通用应答；表 8/9/10（p.11-12，PDF p.15-16）注册、注册应答、鉴权；表 23-31（p.21-24，PDF p.25-28）位置；表 34（p.25，PDF p.29）临时跟踪；表 37/38（p.26-27，PDF p.30-31）文本；表 67/68（p.37，PDF p.41）批量定位。

标准正文表 10 内部存在不一致：IMEI 为 `BYTE[15]` 且起始字节为 `n+1`，软件版本号表格写为 `n+9`，按字段长度自洽应为 `n+16`。表 23 的时间字段前方向为 WORD，字段起始位置按长度应为 22，但表格标为 21。本实现按字段长度自洽布局解析；两处不一致与未取得的 2021 修改单均在 evidence 中保留，不伪造“修改单已核对”。

## Answer

已实现并验收，证据见 [05-verification.md](../evidence/05-verification.md)。

交付：根 `pom.xml` 新增 `protocol` module；`protocol/pom.xml` 为无 Spring/数据库/server 运行时依赖的独立 jar；`com.vms.protocol` 提供 BCD、GBK、消息头/属性、帧转义与 XOR、TLV、注册/鉴权/通用应答/位置/批量/文本/临时跟踪消息体解析；独立 fixture 位于 `protocol/src/test/resources/fixtures/jt808-2019.txt`。

结果：`CodecUnitsTest` 6 项、`FrameCodecRejectionTest` 25 项、`FrameCodecTest` 23 项、`MessageBodyValidationTest` 7 项、`TemporaryTrackingTest` 3 项，共 **64 项通过**；命令为 `mvnw.cmd -B -pl protocol test`，未启动 Testcontainers。新增回归覆盖未知 message ID encode 拒绝、非法 BCD 坏帧跳过、共享标识位重同步、`0x01` 固定 TLV 长度及 `0x8202` 工程参数边界。

限制：只声明 2019 正文子集；不支持分包、加密、旧版 2011 头、TCP listener、会话和真实终端互通；2021 修改单未取得，表 10 的正文内部偏差已披露。
