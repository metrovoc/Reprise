package com.metrovoc.reprise.util;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class Varints {
    public static void write(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int read(ByteBuffer in) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = in.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) {
                throw new IllegalStateException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    private Varints() {}
}
