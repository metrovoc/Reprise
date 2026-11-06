package com.metrovoc.reprise.export;

import com.metrovoc.reprise.record.ReplayPacker;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keeps encoder intermediates separate from completed videos and frame sequences. */
final class ExportFiles implements AutoCloseable {
    final Path work;
    final Path media;
    private final String base;
    private final String extension;

    ExportFiles(Path parent, String base, String extension) throws IOException {
        this.base = base;
        this.extension = extension;
        this.work = Files.createTempDirectory(parent, ".render-");
        this.media = work.resolve("media" + extension);
    }

    Path publish() throws IOException {
        for (int attempt = 1; ; attempt++) {
            String stem = attempt == 1 ? base : base + "_" + attempt;
            Path dest = work.resolveSibling(stem + extension);
            if (Files.exists(dest)) continue;
            // Competing clients can finish renders with the same timestamp.
            Path claim = dest.resolveSibling("." + dest.getFileName() + ".claim");
            try {
                Files.createFile(claim);
            } catch (FileAlreadyExistsException busy) {
                continue;
            }
            try {
                if (!Files.exists(dest)) return Files.move(media, dest);
            } finally {
                Files.delete(claim);
            }
        }
    }

    @Override
    public void close() throws IOException {
        ReplayPacker.deleteTree(work);
    }
}
