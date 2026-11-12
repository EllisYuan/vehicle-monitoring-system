package com.vms.access;

import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/** 将 TCP 粘包、拆包还原为完整 JT/T 808 frame。 */
final class TerminalFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        while (true) {
            int start = findFlag(input, input.readerIndex(), input.writerIndex());
            if (start < 0) {
                input.skipBytes(input.readableBytes());
                return;
            }
            if (start > input.readerIndex()) {
                input.skipBytes(start - input.readerIndex());
            }
            int end = findFlag(input, start + 1, input.writerIndex());
            if (end < 0) {
                return;
            }
            int length = end - start + 1;
            byte[] encoded = new byte[length];
            input.getBytes(start, encoded);
            input.skipBytes(length);
            Frame frame = FrameCodec.decode(encoded);
            output.add(frame);
        }
    }

    private static int findFlag(ByteBuf input, int from, int to) {
        for (int index = from; index < to; index++) {
            if (input.getUnsignedByte(index) == 0x7E) {
                return index;
            }
        }
        return -1;
    }
}
