package com.metrovoc.reprise.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExportFilesTest {
    @TempDir Path root;

    @Test void sameNameRendersKeepBothVideos() throws Exception {
        try (var first = new ExportFiles(root, "shot", ".mp4");
             var second = new ExportFiles(root, "shot", ".mp4")) {
            Files.writeString(first.media, "first");
            Files.writeString(second.media, "second");
            var start = new CyclicBarrier(2);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var one = workers.submit(() -> { start.await(); return first.publish(); });
                var two = workers.submit(() -> { start.await(); return second.publish(); });
                assertNotEquals(one.get(), two.get());
                assertEquals("first", Files.readString(one.get()));
                assertEquals("second", Files.readString(two.get()));
            }
        }
        try (var files = Files.list(root)) { assertEquals(2, files.count()); }
    }

    @Test void cancelledSequenceDiscardsFramesAndAudioAndPreservesPreviousRender() throws Exception {
        Path previous = Files.createDirectory(root.resolve("shot"));
        Files.writeString(previous.resolve("audio.wav"), "original");
        try (var job = new ExportFiles(root, "shot", "")) {
            Files.createDirectory(job.media);
            Files.writeString(job.media.resolve("frame_000000.png"), "partial");
            Files.writeString(job.work.resolve("audio.wav"), "partial");
        }
        assertEquals("original", Files.readString(previous.resolve("audio.wav")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
}
