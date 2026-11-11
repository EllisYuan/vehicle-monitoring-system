package com.vms.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 坏帧、越界长度与不支持消息的安全处理。
 * 原则：可识别时规范拒绝，无法安全识别时抛 {@link MalformedFrameException} 由调用方丢弃记录。
 */
class FrameCodecRejectionTest {

    @Test
    void rejectsFrameWithoutBothFlags() {
        assertThatThrownBy(() -> FrameCodec.decode(new byte[] { 0x00, 0x02, 0x7E }))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("标识位");
    }

    @Test
    void rejectsEmptyOrTooShortInput() {
        assertThatThrownBy(() -> FrameCodec.decode(new byte[0]))
                .isInstanceOf(MalformedFrameException.class);
        assertThatThrownBy(() -> FrameCodec.decode(new byte[] { 0x7E }))
                .isInstanceOf(MalformedFrameException.class);
    }

    @Test
    void rejectsBadChecksum() throws Exception {
        byte[] frame = original("heartbeat");
        frame[frame.length - 2] ^= 0x01;
        assertThatThrownBy(() -> FrameCodec.decode(frame))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("校验码");
    }

    @Test
    void rejectsIllegalEscapeSequence() {
        byte[] frame = Fixtures.hex("7E 00 02 40 00 01 00 00 00 00 01 38 00 13 80 00 00 01 7D 03 00 7E");
        assertThatThrownBy(() -> FrameCodec.decode(frame))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("转义");
    }

    @Test
    void rejectsDanglingEscapeAtEnd() {
        byte[] frame = Fixtures.hex("7E 00 02 40 00 01 00 00 00 00 01 38 00 13 80 00 00 01 7D 7E");
        assertThatThrownBy(() -> FrameCodec.decode(frame))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("转义");
    }

    @Test
    void rejectsUnknownMessageId() {
        // 0x8500 汽车控制在本子集内不支持
        byte[] frame = Fixtures.hex("7E 85 00 40 00 01 00 00 00 00 01 38 00 13 80 00 00 01 6F 7E");
        assertThatThrownBy(() -> FrameCodec.decode(frame))
                .isInstanceOf(UnsupportedMessageException.class)
                .satisfies(error -> assertThat(((UnsupportedMessageException) error).code())
                        .isEqualTo("UNKNOWN_MESSAGE"));
    }

    @Test
    void rejectsEncodingUnknownMessageId() {
        MessageHeader header = new MessageHeader(0x8500, BodyProperties.plain(0), 1,
                "00000000013800138000", 1);
        assertThatThrownBy(() -> FrameCodec.encode(new Frame(header, new byte[0])))
                .isInstanceOf(UnsupportedMessageException.class)
                .satisfies(error -> assertThat(((UnsupportedMessageException) error).code())
                        .isEqualTo("UNKNOWN_MESSAGE"));
    }

    @Test
    void rejectsFrameBodyLengthAtConstruction() {
        MessageHeader header = new MessageHeader(0x0002, BodyProperties.plain(1), 1,
                "00000000013800138000", 1);
        assertThatThrownBy(() -> new Frame(header, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消息体属性声明");
    }

    @Test
    void rejectsBodyLengthDisagreeingWithProperty() throws Exception {
        byte[] frame = original("heartbeat");
        // 把消息体属性长度改成 5，但帧内没有多余字节
        frame[3] = (byte) 0x05;
        assertThatThrownBy(() -> FrameCodec.decode(frame))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("消息体长度");
    }

    @Test
    void rejectsTruncatedBody() throws Exception {
        byte[] frame = original("position-0200");
        byte[] truncated = java.util.Arrays.copyOf(frame, frame.length - 5);
        assertThatThrownBy(() -> FrameCodec.decode(truncated))
                .isInstanceOf(MalformedFrameException.class);
    }

    @Test
    void rejectsOversizedBodyWhenEncoding() {
        byte[] tooLarge = new byte[BodyProperties.MAX_BODY_LENGTH + 1];
        assertThatThrownBy(() -> BodyProperties.plain(tooLarge.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 位");
    }

    @Test
    void rejects2011Header() {
        byte[] frame = Fixtures.hex("7E 00 02 00 00 00 00 00 00 01 38 00 13 80 00 00 01 A8 7E");
        assertThatThrownBy(() -> FrameCodec.decode(frame))
                .isInstanceOf(UnsupportedMessageException.class)
                .satisfies(error -> assertThat(((UnsupportedMessageException) error).code())
                        .isEqualTo("VERSION_2011"));
    }

    @Test
    void rejectsEncodingPackagedOrEncryptedFrames() {
        MessageHeader packaged = new MessageHeader(0x0002,
                new BodyProperties(0, false, true, true), 1, "00000000013800138000", 1);
        assertThatThrownBy(() -> FrameCodec.encode(new Frame(packaged, new byte[0])))
                .isInstanceOf(UnsupportedMessageException.class)
                .satisfies(error -> assertThat(((UnsupportedMessageException) error).code())
                        .isEqualTo("PACKAGED"));

        MessageHeader encrypted = new MessageHeader(0x0002,
                new BodyProperties(0, true, false, true), 1, "00000000013800138000", 1);
        assertThatThrownBy(() -> FrameCodec.encode(new Frame(encrypted, new byte[0])))
                .isInstanceOf(UnsupportedMessageException.class)
                .satisfies(error -> assertThat(((UnsupportedMessageException) error).code())
                        .isEqualTo("ENCRYPTED"));
    }

    static Stream<Arguments> invalidBatchCounts() {
        return Stream.of(
                // 表 67：数据项个数必须大于 0
                Arguments.of(0, "必须大于 0"),
                // 本子集限制每包最多 20 项
                Arguments.of(21, "最多 20 项"));
    }

    @ParameterizedTest(name = "count={0}")
    @MethodSource("invalidBatchCounts")
    void rejectsBatchWithInvalidItemCount(int count, String message) {
        byte[] body = new byte[3];
        Bytes.putUnsignedShort(body, 0, count);
        body[2] = PositionBatch.TYPE_NORMAL;
        assertThatThrownBy(() -> PositionBatch.parse(body))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining(message);
    }

    @Test
    void rejectsBatchWhenDeclaredItemLengthIsTooSmall() {
        byte[] body = new byte[] { 0, 1, PositionBatch.TYPE_NORMAL, 0, 10,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        assertThatThrownBy(() -> PositionBatch.parse(body))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("小于基本位置信息长度");
    }

    @Test
    void rejectsBatchWithTrailingBytes() {
        // 声明 28 字节但只给 10 字节，且后面没有更多数据
        byte[] body = new byte[] { 0, 1, PositionBatch.TYPE_NORMAL, 0, 28,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        assertThatThrownBy(() -> PositionBatch.parse(body))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("不足以读取");
    }

    @Test
    void rejectsBatchWithUnknownDataType() {
        byte[] body = new byte[] { 0, 1, 2 };
        assertThatThrownBy(() -> PositionBatch.parse(body))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("位置数据类型未知");
    }

    @Test
    void rejectsPositionReportWithTrailingBytes() {
        // 基本位置信息 28 字节之后多出 1 字节，且不是合法的 TLV
        byte[] body = Fixtures.hex(
                "00 00 00 00 00 00 00 03 01 C9 C3 80 07 27 0E 00 00 32 02 58 00 5A 26 11 11 10 30 00 02");
        assertThatThrownBy(() -> PositionReport.parse(body))
                .isInstanceOf(MalformedFrameException.class);
    }

    @Test
    void splitsStreamIntoTolerableFrames() throws Exception {
        byte[] good = original("heartbeat");
        byte[] stream = concat(new byte[] { 0x00, 0x11 }, good, new byte[] { 0x22 }, good);
        assertThat(FrameCodec.splitFrames(stream)).hasSize(2);
        for (byte[] candidate : FrameCodec.splitFrames(stream)) {
            assertThat(FrameCodec.decode(candidate).type()).isEqualTo(MessageType.HEARTBEAT);
        }
    }

    @Test
    void splitFramesRejectsNullStream() {
        assertThatThrownBy(() -> FrameCodec.splitFrames(null))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("字节流");
    }

    @Test
    void splitsAdjacentFramesWithSharedOrRepeatedFlag() throws Exception {
        byte[] first = original("heartbeat");
        byte[] second = original("heartbeat");
        byte[] shared = concat(first, java.util.Arrays.copyOfRange(second, 1, second.length));
        assertThat(FrameCodec.splitFrames(shared)).hasSize(2);
        assertThat(FrameCodec.splitFrames(concat(first, second))).hasSize(2);
    }

    @Test
    void skipsInvalidBcdFrameAndKeepsFollowingGoodFrame() throws Exception {
        byte[] bad = original("heartbeat");
        bad[6] = (byte) 0xAB;
        byte[] good = original("heartbeat");
        assertThatThrownBy(() -> FrameCodec.decode(bad))
                .isInstanceOf(MalformedFrameException.class);
        assertThat(FrameCodec.splitFrames(concat(bad, good))).hasSize(1);
    }

    @Test
    void bcdRejectsNonDecimalAndOddLength() {
        assertThatThrownBy(() -> Bcd.encode("12A4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bcd.encode("123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bcd.decode(new byte[] { (byte) 0xAB }))
                .isInstanceOf(MalformedFrameException.class);
    }

    @Test
    void gbkRejectsNullButAcceptsEmpty() {
        assertThatThrownBy(() -> Gbk.encode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Gbk.encode("")).isEmpty();
    }

    private byte[] original(String name) throws Exception {
        return Fixtures.all().filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow().frame().clone();
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
