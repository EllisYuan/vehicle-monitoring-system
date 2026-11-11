package com.vms.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 以独立 hex fixture 验证帧结构、消息头与各消息体解析。
 * 每组数据来自 protocol/src/test/resources/fixtures/jt808-2019.txt，逐条标注标准条目。
 */
class FrameCodecTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.vms.protocol.Fixtures#all")
    void decodesOrRejectsIndependentFixture(Fixtures.Case item) {
        if (item.shouldReject()) {
            assertThatThrownBy(() -> FrameCodec.decode(item.frame()))
                    .as(item.source())
                    .isInstanceOf(UnsupportedMessageException.class)
                    .satisfies(error -> assertThat(((UnsupportedMessageException) error).code())
                            .isEqualTo(item.rejectionCode()));
            return;
        }
        Frame frame = FrameCodec.decode(item.frame());
        assertThat(frame.header().messageId()).isEqualTo(item.messageId());
        // 重新编码必须逐字节还原，证明解析与封装对称
        assertThat(Fixtures.hex(FrameCodec.encode(frame))).isEqualTo(Fixtures.hex(item.frame()));
    }

    @Test
    void heartbeatAndLogoutHaveEmptyBodies() throws Exception {
        for (Fixtures.Case item : Fixtures.all().toList()) {
            if (item.name().equals("heartbeat") || item.name().equals("logout")) {
                assertThat(FrameCodec.decode(item.frame()).body()).isEmpty();
            }
        }
    }

    @Test
    void headerCarriesVersionPhoneAndSerialFromTable2() throws Exception {
        Fixtures.Case item = find("position-0200");
        MessageHeader header = FrameCodec.decode(item.frame()).header();
        assertThat(header.protocolVersion()).isEqualTo(1);
        assertThat(header.terminalPhone()).isEqualTo("00000000013800138000");
        assertThat(header.serialNumber()).isEqualTo(1);
        assertThat(header.properties().packaged()).isFalse();
        assertThat(header.properties().encrypted()).isFalse();
        assertThat(header.properties().versionFlag()).isTrue();
        assertThat(header.bodyLength()).isEqualTo(38);
    }

    @Test
    void positionReportParsesBasicFieldsAndAttachments() throws Exception {
        PositionReport report = PositionReport.parse(FrameCodec.decode(find("position-0200").frame()).body());
        assertThat(report.alarmFlag()).isZero();
        assertThat(report.status()).isEqualTo(3);
        assertThat(report.fixValid()).isTrue();
        assertThat(report.south()).isFalse();
        assertThat(report.west()).isFalse();
        assertThat(report.latitude()).isEqualTo(30.0);
        assertThat(report.longitude()).isEqualTo(120.0);
        assertThat(report.altitude()).isEqualTo(50);
        assertThat(report.speed()).isEqualTo(600);
        assertThat(report.direction()).isEqualTo(90);
        assertThat(report.time()).isEqualTo(LocalDateTime.of(2026, 11, 11, 10, 30, 0));
        assertThat(report.attachments()).hasSize(2);
        assertThat(report.attachments().get(0).id()).isEqualTo(0x01);
        assertThat(report.attachments().get(1).id()).isEqualTo(0xFE);
    }

    @Test
    void authenticationFollowsTable10Layout() throws Exception {
        TerminalAuthentication auth =
                TerminalAuthentication.parse(FrameCodec.decode(find("auth-0102").frame()).body());
        assertThat(auth.authCode()).isEqualTo("123456789012");
        assertThat(auth.imei()).isEqualTo("012345678901234");
        assertThat(auth.softwareVersion()).isEqualTo("00000000000000000001");
    }

    @Test
    void batchUploadParsesEachItemWithItsOwnLength() throws Exception {
        PositionBatch batch = PositionBatch.parse(FrameCodec.decode(find("batch-0704").frame()).body());
        assertThat(batch.dataType()).isEqualTo(PositionBatch.TYPE_NORMAL);
        assertThat(batch.items()).hasSize(2);
        assertThat(batch.items().get(0).speed()).isEqualTo(600);
        assertThat(batch.items().get(1).speed()).isEqualTo(610);
        assertThat(batch.items().get(1).time()).isEqualTo(LocalDateTime.of(2026, 11, 11, 10, 30, 1));
    }

    @Test
    void textIssueUsesGbkEncoding() throws Exception {
        TextIssue text = TextIssue.parse(FrameCodec.decode(find("text-8300").frame()).body());
        assertThat(text.flag()).isEqualTo(1);
        assertThat(text.textType()).isEqualTo(TextIssue.TYPE_NOTICE);
        assertThat(text.text()).isEqualTo("测试文本");
    }

    @Test
    void temporaryTrackingFollowsTable34() throws Exception {
        TemporaryTracking tracking =
                TemporaryTracking.parse(FrameCodec.decode(find("tracking-8202").frame()).body());
        assertThat(tracking.intervalSeconds()).isEqualTo(30);
        assertThat(tracking.validSeconds()).isEqualTo(300);
        assertThat(tracking.stop()).isFalse();
        // 表 34：间隔为 0 表示停止跟踪，且无需后续字段
        TemporaryTracking stop = TemporaryTracking.parse(new byte[] { 0, 0 });
        assertThat(stop.stop()).isTrue();
    }

    @Test
    void generalResponsesDecodeResponseSerialAndResult() throws Exception {
        TerminalGeneralResponse terminal =
                TerminalGeneralResponse.parse(FrameCodec.decode(find("general-0001").frame()).body());
        assertThat(terminal.responseSerial()).isEqualTo(1);
        assertThat(terminal.responseId()).isEqualTo(0x0102);
        assertThat(terminal.result()).isZero();

        PlatformGeneralResponse platform =
                PlatformGeneralResponse.parse(FrameCodec.decode(find("general-8001").frame()).body());
        assertThat(platform.responseId()).isEqualTo(0x0102);
    }

    @Test
    void registerAndResponseFollowTable8And9() throws Exception {
        TerminalRegister register =
                TerminalRegister.parse(FrameCodec.decode(find("register-0100").frame()).body());
        assertThat(register.provinceId()).isEqualTo(1);
        assertThat(register.cityId()).isEqualTo(1);
        assertThat(register.manufacturer()).isEqualTo("ABC12");
        assertThat(register.plateColor()).isEqualTo(2);
        assertThat(register.plateNo()).isEqualTo("湘A12345");

        TerminalRegisterResponse response = TerminalRegisterResponse
                .parse(FrameCodec.decode(find("register-response-8100").frame()).body());
        assertThat(response.result()).isEqualTo(TerminalRegisterResponse.RESULT_SUCCESS);
        assertThat(response.authCode()).isEqualTo("AUTH123");
    }

    private Fixtures.Case find(String name) throws Exception {
        return Fixtures.all().filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 fixture：" + name));
    }
}
