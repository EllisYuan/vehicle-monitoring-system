package com.vms.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 基础编码单元测试（JT/T 808-2019 表 1、4.3、4.4.5）。
 * 这些用例只验证编码规则本身，不依赖任何消息结构。
 */
class CodecUnitsTest {

    @Test
    void bcdEncodesTwoDigitsPerByteHighNibbleFirst() {
        assertThat(Fixtures.hex(Bcd.encode("261111103000"))).isEqualTo("26 11 11 10 30 00");
        assertThat(Bcd.decode(Fixtures.hex("26 11 11 10 30 00"))).isEqualTo("261111103000");
    }

    @Test
    void bcdRoundTripsTerminalPhone() {
        String phone = "00000000013800138000";
        assertThat(Bcd.decode(Bcd.encode(phone))).isEqualTo(phone);
        assertThat(Bcd.encode(phone)).hasSize(10);
    }

    @Test
    void gbkEncodesChineseWithTwoBytesPerCharacter() {
        assertThat(Fixtures.hex(Gbk.encode("测试文本"))).isEqualTo("B2 E2 CA D4 CE C4 B1 BE");
        assertThat(Gbk.decode(Fixtures.hex("B2 E2 CA D4 CE C4 B1 BE"))).isEqualTo("测试文本");
    }

    @Test
    void checksumIsByteWiseXorOverHeaderAndBody() {
        // 4.4.5：从消息头首字节开始逐字节异或，直到消息体末字节
        byte[] head = Fixtures.hex("00 02 40 00 01 00 00 00 00 01 38 00 13 80 00 00 01");
        int xor = 0;
        for (byte value : head) {
            xor ^= value & 0xFF;
        }
        assertThat(xor).isEqualTo(0xE8);
    }

    @Test
    void bodyPropertiesRoundTripsLengthAndFlags() {
        BodyProperties properties = BodyProperties.plain(38);
        assertThat(properties.toInt()).isEqualTo(38 | (1 << 14));
        BodyProperties decoded = BodyProperties.of(properties.toInt());
        assertThat(decoded.rawLength()).isEqualTo(38);
        assertThat(decoded.versionFlag()).isTrue();
        assertThat(decoded.encrypted()).isFalse();
        assertThat(decoded.packaged()).isFalse();
    }

    @Test
    void headerEncodesFixedSeventeenBytesWhenNotPackaged() {
        MessageHeader header = new MessageHeader(0x0200, BodyProperties.plain(0), 1,
                "00000000013800138000", 1);
        assertThat(header.length()).isEqualTo(17);
        assertThat(Fixtures.hex(header.encode()))
                .isEqualTo("02 00 40 00 01 00 00 00 00 01 38 00 13 80 00 00 01");
    }
}
