package com.metrovoc.reprise.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.Deflater;

/** A single-channel, losslessly ZIP-compressed scanline OpenEXR. Z stores positive camera-space distance in blocks. */
final class DepthExr {
    private static ByteBuffer little(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void string(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
    }

    private static void attribute(ByteArrayOutputStream header, String name, String type, byte[] value) {
        string(header, name);
        string(header, type);
        header.writeBytes(little(4).putInt(value.length).array());
        header.writeBytes(value);
    }

    static void write(Path file, int width, int height, byte[] pixels, float m22, float m32, boolean zeroToOne) throws IOException {
        var header = new ByteArrayOutputStream();
        header.writeBytes(little(8).putInt(20000630).putInt(2).array());
        var channels = new ByteArrayOutputStream();
        string(channels, "Z");
        channels.writeBytes(little(16).putInt(2).putInt(0).putInt(1).putInt(1).array());
        channels.write(0);
        attribute(header, "channels", "chlist", channels.toByteArray());
        attribute(header, "compression", "compression", new byte[]{3});
        byte[] window = little(16).putInt(0).putInt(0).putInt(width - 1).putInt(height - 1).array();
        attribute(header, "dataWindow", "box2i", window);
        attribute(header, "displayWindow", "box2i", window);
        attribute(header, "lineOrder", "lineOrder", new byte[]{0});
        attribute(header, "pixelAspectRatio", "float", little(4).putFloat(1).array());
        attribute(header, "screenWindowCenter", "v2f", new byte[8]);
        attribute(header, "screenWindowWidth", "float", little(4).putFloat(1).array());
        header.write(0);

        int linesPerBlock = 16;
        var offsets = little(Long.BYTES * Math.ceilDiv(height, linesPerBlock));
        var block = little(width * linesPerBlock * Float.BYTES);
        byte[] predicted = new byte[block.capacity()];
        byte[] compressed = new byte[block.capacity()];
        var raw = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
        try (var out = new RandomAccessFile(file.toFile(), "rw"); var zip = new Deflater(Deflater.BEST_SPEED)) {
            out.setLength(0);
            out.write(header.toByteArray());
            out.seek(header.size() + offsets.capacity());
            for (int first = 0; first < height; first += linesPerBlock) {
                block.clear();
                for (int y = first; y < Math.min(first + linesPerBlock, height); y++) {
                    raw.position((height - y - 1) * width * Float.BYTES);
                    for (int x = 0; x < width; x++) {
                        float depth = raw.getFloat();
                        double ndc = zeroToOne ? depth : 2.0 * depth - 1.0;
                        block.putFloat(depth == 1.0f ? Float.POSITIVE_INFINITY : (float) (m32 / (ndc + m22)));
                    }
                }
                int size = block.position();
                predict(block.array(), predicted, size);
                zip.reset();
                zip.setInput(predicted, 0, size);
                zip.finish();
                int packed = zip.deflate(compressed, 0, size);
                boolean useZip = zip.finished() && packed < size;
                offsets.putLong(out.getFilePointer());
                out.writeInt(Integer.reverseBytes(first));
                out.writeInt(Integer.reverseBytes(useZip ? packed : size));
                out.write(useZip ? compressed : block.array(), 0, useZip ? packed : size);
            }
            out.seek(header.size());
            out.write(offsets.array());
        }
    }

    /** OpenEXR ZIP stores even bytes, then odd bytes, then differences biased by 128. */
    private static void predict(byte[] input, byte[] output, int size) {
        int half = size / 2;
        for (int i = 0; i < half; i++) {
            output[i] = input[2 * i];
            output[half + i] = input[2 * i + 1];
        }
        for (int i = size - 1; i > 0; i--) output[i] = (byte) (output[i] - output[i - 1] + 128);
    }

    private DepthExr() {}
}
