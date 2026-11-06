package com.metrovoc.reprise.format;

import com.metrovoc.reprise.util.Varints;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import net.minecraft.network.VarInt;
import net.minecraft.util.Mth;

/** Equally spaced samples of one entity's feet position and view angles across one recorded tick. */
public final class PovSamples {
    public record Pose(double x, double y, double z, float yaw, float pitch) {
        public Pose interpolate(Pose to, double fraction) {
            return new Pose(Mth.lerp(fraction, x, to.x), Mth.lerp(fraction, y, to.y), Mth.lerp(fraction, z, to.z),
                Mth.wrapDegrees(Mth.rotLerp((float) fraction, yaw, to.yaw)),
                Mth.wrapDegrees(Mth.rotLerp((float) fraction, pitch, to.pitch)));
        }
    }
    private static final int STRIDE = 3 * Double.BYTES + 2 * Float.BYTES;
    private final ByteBuffer samples;
    public final int entityId;

    public PovSamples(ByteBuffer payload) {
        this(payload, false);
    }

    public PovSamples(ByteBuffer payload, boolean packed) {
        entityId = Varints.read(payload);
        int count = Varints.read(payload);
        if (packed) payload = unpack(payload, count);
        if (count < 1 || (long) count * STRIDE != payload.remaining()) {
            throw new IllegalArgumentException("Invalid recorded POV sample count");
        }
        samples = payload.slice().asReadOnlyBuffer();
        for (int pos = 0; pos < samples.limit(); pos += STRIDE) {
            if (!Double.isFinite(samples.getDouble(pos)) || !Double.isFinite(samples.getDouble(pos + 8))
                || !Double.isFinite(samples.getDouble(pos + 16)) || !Float.isFinite(samples.getFloat(pos + 24))
                || !Float.isFinite(samples.getFloat(pos + 28))) throw new IllegalArgumentException("Nonfinite POV sample");
        }
    }

    public Pose at(float fraction) {
        int last = samples.limit() / STRIDE - 1;
        double t = Math.clamp((double) fraction, 0, 1) * last;
        int first = (int) t;
        Pose start = pose(first);
        return t == first ? start : start.interpolate(pose(first + 1), t - first);
    }

    private Pose pose(int index) {
        int a = index * STRIDE;
        return new Pose(samples.getDouble(a), samples.getDouble(a + 8), samples.getDouble(a + 16),
            Mth.wrapDegrees(samples.getFloat(a + 24)), Mth.wrapDegrees(samples.getFloat(a + 28)));
    }

    // Offsets in the original XYZ/yaw/pitch wire layout.
    private static final int[] OFFSETS = {0, 8, 16, 24, 28};

    private static long bits(Pose pose, int channel) {
        return switch (channel) {
            case 0 -> Double.doubleToRawLongBits(pose.x);
            case 1 -> Double.doubleToRawLongBits(pose.y);
            case 2 -> Double.doubleToRawLongBits(pose.z);
            case 3 -> Float.floatToRawIntBits(pose.yaw);
            case 4 -> Float.floatToRawIntBits(pose.pitch);
            default -> throw new AssertionError(channel);
        };
    }

    private static int packedBytes(int mask, int count) {
        int varyingBytes = Long.BYTES * Integer.bitCount(mask & 7) + Integer.BYTES * Integer.bitCount(mask >>> 3);
        return STRIDE + (count - 1) * varyingBytes;
    }

    /** Each tick is self-contained, including at seek checkpoints. Constant fields need only their first value. */
    public static byte[] encodePacked(int entityId, Pose[] samples) {
        int count = samples.length;
        if (count < 1 || count > 13) throw new IllegalArgumentException("Invalid native POV sample count");
        int mask = 0;
        for (int ch = 0; ch < OFFSETS.length; ch++) {
            long first = bits(samples[0], ch);
            for (int i = 1; i < count; i++) {
                if (bits(samples[i], ch) != first) {
                    mask |= 1 << ch;
                    break;
                }
            }
        }
        int size = VarInt.getByteSize(entityId) + VarInt.getByteSize(count) + 1 + packedBytes(mask, count);
        var buffer = Unpooled.wrappedBuffer(new byte[size]).clear();
        try {
            VarInt.write(buffer, entityId);
            VarInt.write(buffer, count);
            buffer.writeByte(mask);
            for (int ch = 0; ch < OFFSETS.length; ch++) {
                long previous = 0;
                int words = (mask & (1 << ch)) == 0 ? 1 : count;
                for (int i = 0; i < words; i++) {
                    long current = bits(samples[i], ch);
                    long delta = previous ^ current;
                    if (ch < 3) buffer.writeLong(delta);
                    else buffer.writeInt((int) delta);
                    previous = current;
                }
            }
            return buffer.array();
        } finally {
            buffer.release();
        }
    }

    private static ByteBuffer unpack(ByteBuffer input, int count) {
        if (count < 1 || count > 13 || !input.hasRemaining()) throw new IllegalArgumentException("Invalid native POV sample count");
        int mask = Byte.toUnsignedInt(input.get());
        if ((mask >> OFFSETS.length) != 0) throw new IllegalArgumentException("Unknown POV channels");
        if (input.remaining() != packedBytes(mask, count)) throw new IllegalArgumentException("Invalid packed POV length");
        var output = ByteBuffer.allocate(count * STRIDE);
        for (int ch = 0; ch < OFFSETS.length; ch++) {
            long word = 0;
            boolean varying = (mask & (1 << ch)) != 0;
            for (int i = 0; i < count; i++) {
                if (i == 0 || varying) word ^= ch < 3 ? input.getLong() : input.getInt();
                int offset = i * STRIDE + OFFSETS[ch];
                if (ch < 3) output.putLong(offset, word);
                else output.putInt(offset, (int) word);
            }
        }
        return output;
    }
}
