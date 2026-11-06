package com.metrovoc.reprise.format;

import com.metrovoc.reprise.util.Varints;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReplayJoinerTest {
    @TempDir Path dir;

    private Path recording(String name, int firstChunk, int count, int ref) throws IOException {
        Path path = dir.resolve(name + ".reprise");
        var meta = new ReplayMeta();
        meta.name = name;
        meta.durationTicks = 1;
        meta.chunkCount = count;
        var marker = new ReplayMeta.Marker();
        marker.name = name;
        meta.markers.add(marker);
        meta.segments.add(new ReplayMeta.Segment(Actions.segmentFile(0), 0, false));
        meta.segments.getFirst().ticks = 1;
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            var out = new DataOutputStream(zip);
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(meta.toJson().getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(Actions.chunkFile(0)));
            for (int i = 0; i < count; i++) {
                Varints.write(out, 4);
                out.writeInt(firstChunk + i);
            }
            zip.putNextEntry(new ZipEntry(Actions.segmentFile(0)));
            out.writeInt(Actions.MAGIC);
            out.writeInt(Actions.VERSION);
            out.writeByte(Actions.CHUNK_REF);
            Varints.write(out, ref);
            out.writeByte(Actions.SNAPSHOT_END);
            out.writeByte(Actions.CHUNK_REF);
            Varints.write(out, 0);
            out.writeByte(Actions.TICK);
        }
        return path;
    }

    @Test void remapsSharedTerrainAndReindexesSnapshotsAndMarkers() throws Exception {
        Path first = recording("first", 0, 128, 127);
        Path second = recording("second", 127, 2, 1);
        byte[] original = Files.readAllBytes(second);
        Path joined = ReplayJoiner.join(first, second, "Two worlds");
        assertArrayEquals(original, Files.readAllBytes(second));
        try (var replay = new ReplayFile(joined)) {
            var meta = replay.meta();
            assertEquals("Two worlds", meta.name);
            assertNotEquals(ReplayFile.readMeta(first).uuid, meta.uuid);
            assertEquals(2, meta.durationTicks);
            assertEquals(129, meta.chunkCount);
            assertEquals("second", meta.markers.get(1).name);
            assertEquals(1, meta.markers.get(1).tick);
            assertTrue(meta.segments.get(1).reset);
            assertEquals(1, meta.segments.get(1).startTick);
            var snapshot = replay.segment(1);
            assertEquals(Actions.CHUNK_REF, snapshot.get());
            assertEquals(128, Varints.read(snapshot));
            assertEquals(128, ByteBuffer.wrap(replay.chunk(128)).getInt());
            assertEquals(Actions.SNAPSHOT_END, snapshot.get());
            var actions = replay.segmentActions(1);
            assertEquals(snapshot.position(), actions.position());
            assertEquals(Actions.CHUNK_REF, actions.get());
            assertEquals(127, Varints.read(actions));
            assertEquals(Actions.TICK, actions.get());
        }
    }

    @Test void failedJoinLeavesOnlyTheSources() throws Exception {
        Path good = recording("first", 0, 1, 0);
        Path bad = recording("bad", 1, 1, 1);
        assertThrows(IOException.class, () -> ReplayJoiner.join(good, bad, "Broken"));
        try (var paths = Files.list(dir)) {
            assertEquals(2, paths.count());
        }
    }
}
