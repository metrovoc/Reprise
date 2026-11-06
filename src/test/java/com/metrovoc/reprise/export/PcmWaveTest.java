package com.metrovoc.reprise.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PcmWaveTest {
    @Test void smallWaveDeclaresStereoPcmAndExactPayloadLength() {
        ByteBuffer h = ByteBuffer.wrap(PcmWave.header(48000)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(h.array(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals(192036, h.getInt(4));
        assertEquals(1, h.getShort(20));
        assertEquals(2, h.getShort(22));
        assertEquals(48000, h.getInt(24));
        assertEquals(16, h.getShort(34));
        assertEquals(192000, h.getInt(40));
    }

    @Test void largeWaveUses64BitChunkAndSampleCounts() {
        long frames = 1L << 31;
        ByteBuffer h = ByteBuffer.wrap(PcmWave.header(frames)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RF64", new String(h.array(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals(frames * 4 + 72, h.getLong(20));
        assertEquals(frames * 4, h.getLong(28));
        assertEquals(frames, h.getLong(36));
        assertEquals(-1, h.getInt(76));
    }

    @Test void clampsHotSamplesWithoutWrapping() {
        assertEquals(32767, PcmWave.sample(1.05f));
        assertEquals(-32768, PcmWave.sample(-1.05f));
        assertEquals(16384, PcmWave.sample(.5f));
        assertEquals(0, PcmWave.sample(0f));
    }

}
