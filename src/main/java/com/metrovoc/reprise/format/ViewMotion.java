package com.metrovoc.reprise.format;

import java.nio.ByteBuffer;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.util.Mth;

/** Local camera animation at both ends of one rendered interval. */
public record ViewMotion(float walk0, float walk1, float bob0, float bob1, float eye0, float eye1, float fov0, float fov1) {
    public interface Source {
        ViewMotion reprise$captureView(ClientAvatarState avatar);
    }

    public float walk(float t) { return Mth.lerp(t, walk0, walk1); }
    public float bob(float t) { return Mth.lerp(t, bob0, bob1); }
    public float eye(float t) { return Mth.lerp(t, eye0, eye1); }
    public float fov(float t) { return Mth.lerp(t, fov0, fov1); }

    public byte[] encode() {
        return ByteBuffer.allocate(8 * Float.BYTES).putFloat(walk0).putFloat(walk1).putFloat(bob0).putFloat(bob1)
            .putFloat(eye0).putFloat(eye1).putFloat(fov0).putFloat(fov1).array();
    }

    public static ViewMotion decode(byte[] bytes) {
        if (bytes.length != 8 * Float.BYTES) throw new IllegalArgumentException("Invalid view motion length");
        var b = ByteBuffer.wrap(bytes);
        return new ViewMotion(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(),
            b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
    }
}
