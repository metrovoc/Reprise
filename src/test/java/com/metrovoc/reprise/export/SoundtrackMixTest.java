package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.AudioCue;
import com.metrovoc.reprise.editor.FrameSchedule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SoundtrackMixTest {
    @Test void zoomedWaveformKeepsShortTransientsAndReadersPreserveStereo(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("impulse.wav");
        int frames = 9600;
        var pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        pcm.putShort(3700 * 4, (short) 16384);
        pcm.putShort(3700 * 4 + 2, (short) -8192);
        try (var out = Files.newOutputStream(path)) {
            out.write(PcmWave.header(frames));
            out.write(pcm.array());
        }
        try (var source = SoundtrackSource.decode(path, "", p -> fail("WAV should decode locally"))) {
            assertEquals(frames, source.frames());
            assertTrue(source.peak(0, .15) >= .5f);
            assertEquals(0, source.peak(.13, .20));
            var clock = new FrameSchedule(List.of(), 0, 20, 80);
            var first = new AudioCue(.125, path.toString(), .05, .1, .5, frames);
            var second = new AudioCue(.125, path.toString(), .05, .1, 1, frames);
            var reader = new SoundtrackMix.Reader(new SoundtrackMix(List.of(SoundtrackMix.clip(clock, first, source),
                SoundtrackMix.clip(clock, second, source))));
            float[] mixed = {.1f, .2f, .1f, .2f, .1f, .2f};
            reader.add(mixed, 1599, 3); // The impulses coincide at 300 + 3700 - 2400.
            assertArrayEquals(new float[]{.1f,.2f,.85f,-.175f,.1f,.2f}, mixed, .000001f);
        }
    }
}
