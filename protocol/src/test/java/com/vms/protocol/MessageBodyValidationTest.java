package com.vms.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageBodyValidationTest {

    private static final String POSITION_HEX =
            "00 00 00 00 00 00 00 03 01 C9 C3 80 07 27 0E 00 00 32 02 58 00 5A 26 11 11 10 30 00 01 04 00 00 13 88 FE 02 12 34";

    @Test
    void positionUsesDirectionBitsAndUnsignedMagnitudes() {
        byte[] body = Fixtures.hex(POSITION_HEX);
        body[7] = 0x0F;
        PositionReport report = PositionReport.parse(body);
        assertThat(report.latitude()).isEqualTo(-30.0);
        assertThat(report.longitude()).isEqualTo(-120.0);
        assertThat(report.south()).isTrue();
        assertThat(report.west()).isTrue();
        assertThat(report.encode()).isEqualTo(body);
    }

    @Test
    void rejectsInvalidCoordinatesAndDates() {
        byte[] body = Fixtures.hex(POSITION_HEX);
        body[8] = (byte) 0xFF;
        assertThatThrownBy(() -> PositionReport.parse(body)).isInstanceOf(MalformedFrameException.class);
        byte[] invalidDate = Fixtures.hex(POSITION_HEX);
        invalidDate[23] = 0x13;
        assertThatThrownBy(() -> PositionReport.parse(invalidDate))
                .isInstanceOf(MalformedFrameException.class).hasMessageContaining("时间");
        assertThatThrownBy(() -> new PositionReport(0, 3, -30, 120, 0, 0, 0,
                LocalDateTime.of(2026, 11, 11, 10, 30), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerModelIsLeftPaddedAndRoundTrips() {
        TerminalRegister register = new TerminalRegister(1, 1, "ABC12", "TESTMODEL",
                "013800138000", 2, "湘A12345");
        byte[] body = register.encode();
        assertThat(Arrays.copyOfRange(body, 15, 36)).containsOnly((byte) 0);
        assertThat(TerminalRegister.parse(body).model()).isEqualTo("TESTMODEL");
        assertThat(TerminalRegister.parse(body).encode()).isEqualTo(body);
    }

    @Test
    void authenticationPadsSoftwareVersionAtEnd() {
        TerminalAuthentication auth = new TerminalAuthentication("AUTH", "012345678901234", "v1");
        byte[] body = auth.encode();
        assertThat(Arrays.copyOfRange(body, body.length - 18, body.length)).containsOnly((byte) 0);
        assertThat(TerminalAuthentication.parse(body).softwareVersion()).isEqualTo("v1");
        assertThat(TerminalAuthentication.parse(body).encode()).isEqualTo(body);
        body[body.length - 1] = 'X';
        assertThatThrownBy(() -> TerminalAuthentication.parse(body))
                .isInstanceOf(MalformedFrameException.class);
    }

    @Test
    void batchRejectsEncodedBodyBeyondSingleFrame() {
        PositionReport item = PositionReport.parse(Fixtures.hex(POSITION_HEX));
        byte[] large = new byte[250];
        PositionReport extended = new PositionReport(item.alarmFlag(), item.status(), item.latitude(),
                item.longitude(), item.altitude(), item.speed(), item.direction(), item.time(),
                List.of(new Tlv(0xFE, large)));
        assertThatThrownBy(() -> new PositionBatch(PositionBatch.TYPE_NORMAL,
                List.of(extended, extended, extended, extended)).encode())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1023");
    }
}
