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
        entityId = Varints.read(payload);
        int count = Varints.read(payload);
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

    public static byte[] encode(int entityId, Pose[] samples) {
        int header = VarInt.getByteSize(entityId) + VarInt.getByteSize(samples.length);
        var buffer = Unpooled.wrappedBuffer(new byte[header + samples.length * STRIDE]).clear();
        try {
            VarInt.write(buffer, entityId);
            VarInt.write(buffer, samples.length);
            for (Pose pose : samples) {
                buffer.writeDouble(pose.x).writeDouble(pose.y).writeDouble(pose.z).writeFloat(pose.yaw).writeFloat(pose.pitch);
            }
            return buffer.array();
        } finally {
            buffer.release();
        }
    }
}
