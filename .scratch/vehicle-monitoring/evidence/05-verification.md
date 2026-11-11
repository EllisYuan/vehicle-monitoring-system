# 任务 05 验证记录：JT/T 808-2019 独立报文验证

## 状态与验收口径

任务状态：`resolved`。

本次按用户确认的口径，按 **JT/T 808-2019 正文**完成并关闭任务。2021 年第 1 号修改单未取得，本记录不声称覆盖修改单；标准正文表 10 自身存在字段起始位置不一致，也在下文单独披露。

## 标准资料 provenance

- 本地文件：`JT-T 808-2019.pdf`（不纳入 Git）
- 文件大小：3,423,765 bytes
- PDF：1.6，56 pages
- SHA-256：`0a4bff06234042237fdf0f1b544a6eaf1fc67f5042f9cf88179e083518c9e990`
- 封面：中华人民共和国交通运输行业标准 JT/T 808—2019；2019-03-15 发布；2019-07-01 实施；中华人民共和国交通运输部发布
- 本地页预览：`.scratch/vehicle-monitoring/evidence/jt808-preview/`，仅用于页码定位，不作为独立标准正文提交
- PDF 与 PNG 预览均保持未跟踪，不复制标准长文本进仓库

已核对页码映射（印刷页 / PDF 页）：

| 内容 | 印刷页 | PDF 页 |
|---|---:|---:|
| 4.4.2 转义、表 1/2/3 | 3–4 | 7–8 |
| 4.4.5 XOR 校验 | 5 | 9 |
| 表 4/5 通用应答 | 9–10 | 13–14 |
| 表 8/9/10 注册、注册应答、鉴权 | 11–12 | 15–16 |
| 表 23–31 位置基本信息、状态、报警、附加项 | 21–24 | 25–28 |
| 表 34 临时位置跟踪 | 25 | 29 |
| 表 37/38 文本下发与标志 | 26–27 | 30–31 |
| 表 67/68 定位数据批量上传 | 37 | 41 |

## 实现交付

| 路径 | 内容 |
|---|---|
| `pom.xml` | 聚合 `protocol` module |
| `protocol/pom.xml` | 独立 jar；JUnit/AssertJ 仅 test scope；无 Spring/MySQL/server 运行时依赖 |
| `protocol/src/main/java/com/vms/protocol/Bcd.java` | BCD 编解码与非法半字节拒绝 |
| `protocol/src/main/java/com/vms/protocol/Gbk.java` | GBK 编解码 |
| `protocol/src/main/java/com/vms/protocol/BodyProperties.java` | 消息体长度、加密、分包、版本位 |
| `protocol/src/main/java/com/vms/protocol/MessageHeader.java` | 2019 消息头 |
| `protocol/src/main/java/com/vms/protocol/FrameCodec.java` | 0x7e 定界、转义、XOR、长度与不支持消息拒绝 |
| `protocol/src/main/java/com/vms/protocol/Tlv.java` | 变长附加信息项 |
| `protocol/src/main/java/com/vms/protocol/TerminalRegister*.java` | 0x0100、0x8100 |
| `protocol/src/main/java/com/vms/protocol/TerminalAuthentication.java` | 0x0102 |
| `protocol/src/main/java/com/vms/protocol/PositionReport.java` | 0x0200 |
| `protocol/src/main/java/com/vms/protocol/PositionBatch.java` | 0x0704，最多 20 项与逐项长度 |
| `protocol/src/main/java/com/vms/protocol/TextIssue.java` | 0x8300，GBK |
| `protocol/src/main/java/com/vms/protocol/TemporaryTracking.java` | 0x8202 |
| `protocol/src/test/resources/fixtures/jt808-2019.txt` | 独立十六进制 fixture 与标准条款出处 |

## 独立 fixture 出处

fixture 文件中的预期字节由标准字段表和独立 Python 计算脚本手工构造，不调用 Java codec 生成预期。覆盖：

- 0x0001、0x8001：表 4/5
- 0x0002、0x0003：表 2、8.3、8.10
- 0x0100、0x8100：表 8/9
- 0x0102：表 10
- 0x0200：表 23 与表 26/27 附加项
- 0x0704：表 67/68
- 0x8300：表 37/38
- 0x8202：表 34
- 转义、加密、分包拒绝：4.4.2、4.4.3.3、4.4.3.4、表 3

每个成功 fixture 测试先解析独立字节并逐字段断言，再用编码结果与 fixture 比较；round-trip 不是唯一证据。

## 测试结果

环境：Windows 11；Maven Wrapper 3.9.12；Maven 通过 `JAVA_HOME=C:\Java\jdk-21.0.9` 使用 JDK 21.0.9；当前 PATH 中独立 `java` 命令可能仍显示 JDK 17，不能作为 Maven 实际运行版本依据。

命令：

```text
mvnw.cmd -B -pl protocol test
```

结果：

```text
CodecUnitsTest          Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0
FrameCodecRejectionTest Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
FrameCodecTest          Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
MessageBodyValidationTest Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

本轮 protocol 测试未启动 Spring、MySQL、Redis 或 Testcontainers。

## 验收项

- [x] 独立 hex fixture 覆盖 2019 消息头、转义与 XOR
- [x] BCD、GBK、TLV 编解码与独立预期字节一致
- [x] 单包消息体超过 1023 字节拒绝；2011 头、分包与加密消息有明确 `VERSION_2011` / `PACKAGED` / `ENCRYPTED` 拒绝
- [x] 0x0704 项数上限 20、每项长度和总长度校验
- [x] 坏帧、非法转义、错误校验、未知消息、越界长度安全拒绝
- [x] fixture 标注标准条款/表号；预期字节不是 Java codec 自生成
- [x] 标准正文内部不一致与修改单缺失已记录，不伪造修改单覆盖结论

## 明确限制

1. **表 10 内部不一致**：IMEI 为 `BYTE[15]` 且起始字节标为 `n+1`，软件版本号起始字节表格写为 `n+9`；按字段长度自洽应为 `n+16`。实现按 `n+16` 读取，并保留该标准原文矛盾，后续若取得勘误/修改单需复核。
2. **表 23 起始字节标注不一致**：纬度从 8、经度从 12、高程从 16、速度从 18、方向从 20，方向为 WORD 后时间字段按长度应从 22 开始，但表格写为 21；实现按前述字段长度从 22 读取，并在 fixture/evidence 中披露。
3. **2021 年第 1 号修改单**：本次未取得，不声称兼容或覆盖修改单；用户已选择按 2019 正文关闭本票。
3. **0x0704 专用应答**：本票只实现批量数据体结构与校验，不做 TCP 应答业务；通用应答映射留给 06。
4. **协议子集边界**：不支持分包、加密、旧版 2011 头、TCP listener、会话、数据库、JT/T 809、JT/T 1078 和真实终端互通。
5. **标准引用的外部字段**：车牌颜色等引用其他标准的业务枚举不在本票扩展，只保留原始 BYTE。
