package com.metrovoc.reprise.format;

import com.metrovoc.reprise.record.ReplayPacker;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Publishes a complete edited archive alongside its source recordings. */
final class ReplayOutput {
    interface Contents {
        void write(ZipOutputStream zip) throws IOException;
    }

    static Path write(Path folder, ReplayMeta metadata, Contents contents) throws IOException {
        Path temp = Files.createTempFile(folder, ".edit-", ".part");
        try {
            try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                contents.write(zip);
                zip.putNextEntry(new ZipEntry("meta.json"));
                zip.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
            }
            while (true) {
                try {
                    return Files.move(temp, ReplayPacker.uniqueName(folder, metadata.name));
                } catch (FileAlreadyExistsException ignored) {
                    // A concurrent edit selected the same name.
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private ReplayOutput() {}
}
