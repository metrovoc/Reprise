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

    @Test void rejectsIncompleteAndNonfiniteInput() {
        assertThrows(IllegalArgumentException.class, () -> new PovSamples(samplePair().limit(65)));
        assertThrows(IllegalArgumentException.class, () -> new PovSamples(samplePair().putFloat(62, Float.NaN)));
    }
}
