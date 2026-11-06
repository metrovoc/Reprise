package com.metrovoc.reprise.format;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.metrovoc.reprise.format.Actions.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayClipTest {
    @TempDir Path dir;

    private Path source() throws Exception {
        var meta = new ReplayMeta();
        meta.chunkCount = 4;
        meta.durationTicks = 6;
        meta.worldSwitchTicks.addAll(List.of(2, 3, 4, 5));
        Path file = dir.resolve("source.reprise");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            var out = new DataOutputStream(zip);
            zip.putNextEntry(new ZipEntry(chunkFile(0)));
            out.write(new byte[]{1, 10, 1, 11, 1, 12, 1, 13});
            for (int i = 0; i < 3; i++) {
                var segment = new ReplayMeta.Segment(segmentFile(i), i * 2, i == 2);
                segment.ticks = 2;
                meta.segments.add(segment);
                zip.putNextEntry(new ZipEntry(segment.file));
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.write(new byte[]{CHUNK_REF, (byte) i, SNAPSHOT_END, GAME_PACKET, 1, 42, TICK,
                    CHUNK_REF, (byte) (i + 1), TICK});
            }
            for (int i = 0; i <= 6; i++) {
                var marker = new ReplayMeta.Marker();
                marker.tick = i;
                marker.name = "mark " + i;
                meta.markers.add(marker);
            }
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(meta.toJson().getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static byte[] bytes(ByteBuffer input) {
        byte[] data = new byte[input.remaining()];
        input.get(data);
        return data;
    }

    @Test void inPointInsideSegmentAndOutPointOnWorldReset() throws Exception {
        Path original = source();
        byte[] preserved = Files.readAllBytes(original);
        try (var clip = new ReplayFile(ReplayClip.save(original, 3, 4))) {
            assertEquals(1, clip.meta().durationTicks);
            assertEquals(2, clip.meta().segments.size());
            assertEquals(List.of(1), clip.meta().worldSwitchTicks);
            assertEquals(List.of(0, 1), clip.meta().markers.stream().map(m -> m.tick).toList());
            assertEquals("mark 3", clip.meta().markers.getFirst().name);
            assertEquals(2, clip.meta().chunkCount);
            assertArrayEquals(new byte[]{11}, clip.chunk(0));
            assertArrayEquals(new byte[]{12}, clip.chunk(1));
            assertArrayEquals(new byte[]{CHUNK_REF, 0, GAME_PACKET, 1, 42, SNAPSHOT_TICK, SNAPSHOT_END, CHUNK_REF, 1, TICK}, bytes(clip.segment(0)));
            assertArrayEquals(new byte[]{CHUNK_REF, 1, TICK}, bytes(clip.segmentActions(0)));
            assertTrue(clip.meta().segments.get(1).reset);
            assertEquals(0, clip.meta().segments.get(1).ticks);
            assertArrayEquals(new byte[]{CHUNK_REF, 1, SNAPSHOT_END}, bytes(clip.segment(1)));
        }
        assertArrayEquals(preserved, Files.readAllBytes(original));
    }

    @Test void foldedTicksSurviveReclippingAndJoiningWithoutExtendingPlayback() throws Exception {
        Path original = source();
        Path clip = ReplayClip.save(original, 1, 4);
        Path nested = ReplayClip.save(clip, 1, 3);
        try (var replay = new ReplayFile(nested)) {
            assertEquals(2, replay.meta().durationTicks);
            // The in-point lands on the second original checkpoint; its full snapshot supersedes the first.
            assertEquals(VERSION, replay.segment(0).getInt(Integer.BYTES));
        }
        Path folded = ReplayClip.save(clip, 0, 1);
        Path joined = ReplayJoiner.join(original, folded, "Joined");
        try (var replay = new ReplayFile(joined)) {
            int cut = replay.meta().segments.stream().filter(segment -> segment.startTick == 6)
                .mapToInt(replay.meta().segments::indexOf).findFirst().orElseThrow();
            assertEquals(7, replay.meta().durationTicks);
            assertEquals(List.of(2, 3, 4, 5, 7), replay.meta().worldSwitchTicks);
            assertEquals(SNAPSHOT_TICK_VERSION, replay.segment(cut).getInt(Integer.BYTES));
            assertArrayEquals(new byte[]{CHUNK_REF, 0, GAME_PACKET, 1, 42, SNAPSHOT_TICK, SNAPSHOT_END,
                CHUNK_REF, 1, TICK}, bytes(replay.segment(cut)));
            replay.meta().segments.get(cut).actionsOffset = 0;
            assertArrayEquals(new byte[]{CHUNK_REF, 1, TICK}, bytes(replay.segmentActions(cut)));
        }
    }

    @Test void startsExactlyAtResetAndEndsInsideSegment() throws Exception {
        try (var clip = new ReplayFile(ReplayClip.save(source(), 4, 5))) {
            assertEquals(1, clip.meta().segments.size());
            assertEquals(1, clip.meta().chunkCount);
            assertArrayEquals(new byte[]{12}, clip.chunk(0));
            assertArrayEquals(new byte[]{CHUNK_REF, 0, SNAPSHOT_END, GAME_PACKET, 1, 42, TICK}, bytes(clip.segment(0)));
        }
    }
}
