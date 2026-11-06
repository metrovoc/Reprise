package com.metrovoc.reprise.format;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PovSamplesTest {
    private static ByteBuffer samplePair() {
        var bytes = ByteBuffer.allocate(66).put((byte) 3).put((byte) 2);
        bytes.putDouble(2).putDouble(4).putDouble(8).putFloat(175).putFloat(-30);
        bytes.putDouble(4).putDouble(8).putDouble(4).putFloat(-165).putFloat(10);
        return bytes.flip();
    }

    @Test void interpolatesPositionAndCrossesTheYawSeam() {
        var track = new PovSamples(samplePair());
        assertEquals(3, track.entityId);
        assertEquals(new PovSamples.Pose(3, 6, 6, -175, -10), track.at(.5f));
        assertEquals(2, track.at(-1).x());
        assertEquals(-165, track.at(2).yaw());
    }

    @Test void packedAndOriginalSamplesProduceIdenticalPoses() {
        var poses = new PovSamples.Pose[] {
            new PovSamples.Pose(2, 4, 8, 175, -30),
            new PovSamples.Pose(4, 8, 4, -165, 10)
        };
        var packed = new PovSamples(ByteBuffer.wrap(PovSamples.encodePacked(500, poses)), true);
        var original = new PovSamples(samplePair());
        assertEquals(500, packed.entityId);
        for (int i = 0; i <= 32; i++) assertEquals(original.at(i / 32f), packed.at(i / 32f));
    }

    @Test void packedChannelsPreserveSignedZeroAndFullPrecision() {
        var points = new PovSamples.Pose[13];
        for (int i = 0; i < points.length; i++) {
            points[i] = new PovSamples.Pose(-1234.5678, i % 2 == 0 ? -0.0 : 0.0, Math.nextUp(1.25 * i),
                (float) (1.25 * i - 5), -12.5f);
        }
        var bytes = PovSamples.encodePacked(2, points);
        // ID, count, mask, first pose, then only Y, Z and yaw vary.
        assertEquals(3 + 32 + 12 * 20, bytes.length);
        var packed = new PovSamples(ByteBuffer.wrap(bytes), true);
        for (int i : new int[]{0, 3, 6, 9, 12}) assertEquals(points[i], packed.at(i / 12f));
        assertThrows(IllegalArgumentException.class, () -> new PovSamples(ByteBuffer.wrap(bytes).limit(bytes.length - 1), true));
        bytes[2] |= 32;
        assertThrows(IllegalArgumentException.class, () -> new PovSamples(ByteBuffer.wrap(bytes), true));
    }

    @Test void rejectsIncompleteAndNonfiniteInput() {
        assertThrows(IllegalArgumentException.class, () -> new PovSamples(samplePair().limit(65)));
        assertThrows(IllegalArgumentException.class, () -> new PovSamples(samplePair().putFloat(62, Float.NaN)));
    }
}
