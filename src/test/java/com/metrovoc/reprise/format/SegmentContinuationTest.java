package com.metrovoc.reprise.format;

import com.metrovoc.reprise.record.ReplayWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SegmentContinuationTest {
    @TempDir Path directory;

    @Test
    void writerFailureFlushesTheRecoverableFirstSegment() throws Exception {
        ReplayWriter writer = new ReplayWriter(directory, new ReplayMeta());
        var fault = new IllegalStateException("simulated write failure");
        writer.submit(w -> {
            w.beginSegment(true);
            w.snapshotEnd();
            w.tick();
            throw fault;
        });
        writer.close();
        assertSame(fault, writer.failure());
        ReplayMeta checkpoint = ReplayMeta.fromJson(Files.readString(directory.resolve("meta.json")));
        var first = checkpoint.segments.getFirst();
        assertTrue(first.reset);
        byte[] bytes = Files.readAllBytes(directory.resolve(first.file));
        assertEquals(first.actionsOffset + 1, bytes.length);
        assertEquals(Actions.TICK, bytes[first.actionsOffset]);
    }

    @Test
    void continuesAcrossAnHourOfTicksWithIndexedAndLegacySnapshots() throws Exception {
        Path recording = directory.resolve("recording");
        ReplayWriter writer = new ReplayWriter(recording, new ReplayMeta());
        byte[] packet = new byte[16_384];
        try {
            for (int i = 0; i < 12; i++) {
                writer.submit(w -> {
                    w.beginSegment(false);
                    w.configPacket(packet);
                    w.gamePacket(packet);
                    w.chunkPacket(packet);
                    w.snapshotEnd();
                });
                for (int tick = 0; tick < 6000; tick++) {
                    writer.submit(ReplayWriter::tick);
                }
            }
        } finally {
            writer.close();
        }
        assertNull(writer.failure());
        Path archive = directory.resolve("hour.reprise");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive));
             var files = Files.walk(recording)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(recording.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        try (ReplayFile replay = new ReplayFile(archive)) {
            assertEquals(72_000, replay.meta().durationTicks);
            assertEquals(1, replay.meta().chunkCount);
            for (int index = 0; index < 12; index++) {
                ReplayMeta.Segment segment = replay.meta().segments.get(index);
                ByteBuffer actions = replay.segmentActions(index);
                assertEquals(segment.actionsOffset, actions.position());
                assertEquals(6000, actions.remaining());
                segment.actionsOffset = 0;
                assertEquals(actions, replay.segmentActions(index));
                while (actions.hasRemaining()) {
                    assertEquals(Actions.TICK, actions.get());
                }
                assertEquals(Actions.CONFIG_PACKET, replay.segment(index).get());
            }
        }
    }
}
