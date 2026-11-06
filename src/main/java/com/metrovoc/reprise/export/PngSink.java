package com.metrovoc.reprise.export;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes each frame as a numbered PNG. */
final class PngSink implements FrameSink {
    private final Path dir;
    private final int width, height;
    private final boolean alpha;
    private int index;

    static Path createDirectory(Path parent, String base) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Path path = parent.resolve(attempt == 1 ? base : base + "_" + attempt);
            try {
                return Files.createDirectory(path);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A short export or a retry can start in the same second as the last one.
            }
        }
    }

    PngSink(Path dir, int width, int height, boolean alpha) {
        this.dir = dir;
        this.alpha = alpha;
        this.width = width;
        this.height = height;
    }

    @Override
    public void frame(byte[] rgba) throws IOException {
        try (NativeImage image = new NativeImage(this.width, this.height, false)) {
            for (int y = 0; y < this.height; y++) {
                int row = (this.height - 1 - y) * this.width * 4;
                for (int x = 0; x < this.width; x++) {
                    int i = row + x * 4;
                    int abgr = (this.alpha ? rgba[i + 3] << 24 : 0xFF000000) | (rgba[i + 2] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | (rgba[i] & 0xFF);
                    image.setPixelABGR(x, y, abgr);
                }
            }
            image.writeToFile(this.dir.resolve(String.format("frame_%06d.png", this.index++)));
        }
    }

    @Override
    public boolean finish() {
        return true;
    }

    @Override
    public void abort() {
    }
}
