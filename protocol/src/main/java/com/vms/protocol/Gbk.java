package com.vms.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

/**
 * 字符串编解码（JT/T 808-2019 表 1：STRING 为 GBK 编码，若无数据则置空）。
 */
public final class Gbk {

    public static final Charset CHARSET = Charset.forName("GBK");

    private Gbk() {
    }

    public static byte[] encode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("GBK 内容不能为 null，无数据请用空串");
        }
        try {
            ByteBuffer encoded = CHARSET.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("文本无法编码为 GBK", e);
        }
    }

    public static String decode(byte[] bytes) {
        try {
            CharBuffer decoded = CHARSET.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new MalformedFrameException("文本不是合法 GBK 字节序列");
        }
    }
}
