package com.metrovoc.reprise.format;

import com.metrovoc.reprise.util.Varints;
import java.nio.ByteBuffer;
import net.minecraft.util.Mth;

/** Equally spaced samples of one entity's feet position and view angles across one recorded tick. */
public final class PovSamples {
    public record Pose(double x, double y, double z, float yaw, float pitch) {}
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
        int a = first * STRIDE, b = Math.min(first + 1, last) * STRIDE;
        double f = t - first;
        return new Pose(Mth.lerp(f, samples.getDouble(a), samples.getDouble(b)),
            Mth.lerp(f, samples.getDouble(a + 8), samples.getDouble(b + 8)),
            Mth.lerp(f, samples.getDouble(a + 16), samples.getDouble(b + 16)),
            Mth.wrapDegrees(Mth.rotLerp((float) f, samples.getFloat(a + 24), samples.getFloat(b + 24))),
            Mth.wrapDegrees(Mth.rotLerp((float) f, samples.getFloat(a + 28), samples.getFloat(b + 28))));
    }
}
