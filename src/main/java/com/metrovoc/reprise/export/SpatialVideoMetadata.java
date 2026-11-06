package com.metrovoc.reprise.export;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Adds stereo layout or spherical projection metadata to the first (video) sample entry in our staged FFmpeg output. */
final class SpatialVideoMetadata {
    private static byte[] box(String type, byte[]... fields) {
        int size = 8;
        for (byte[] field : fields) size += field.length;
        var out = ByteBuffer.allocate(size).putInt(size).put(type.getBytes(StandardCharsets.US_ASCII));
        for (byte[] field : fields) out.put(field);
        return out.array();
    }

    private static void putCenteredBounds(ByteBuffer equi, int visible, int full) {
        long removed = (long) (full - visible) * 0xFFFF_FFFFL;
        // Readers round the leading margin up and derive the trailing margin from the full image size.
        long leading = removed / (2L * full);
        long total = Math.ceilDiv(removed, full);
        equi.putInt((int) leading).putInt((int) (total - leading));
    }

    private static byte[] sphere(int width, int height, int fullWidth) {
        var equi = ByteBuffer.allocate(20).putInt(0);
        putCenteredBounds(equi, height, fullWidth / 2);
        putCenteredBounds(equi, width, fullWidth);
        return box("sv3d", box("svhd", new byte[4], "Reprise\0".getBytes(StandardCharsets.UTF_8)),
            box("proj", box("prhd", new byte[16]), box("equi", equi.array())));
    }

    private record Box(long start, long size, int header, String type) {
        long end() { return start + size; }
        long payload() { return start + header; }
    }

    private static Box read(RandomAccessFile f, long pos, long end) throws IOException {
        if (end - pos < 8) throw new IOException("Truncated video index");
        f.seek(pos);
        long size = Integer.toUnsignedLong(f.readInt());
        byte[] type = new byte[4];
        f.readFully(type);
        int header = size == 1 ? 16 : 8;
        if (size == 1) size = f.readLong();
        if (size == 0) size = end - pos;
        if (size < header || size > end - pos) throw new IOException("Invalid video index size");
        return new Box(pos, size, header, new String(type, StandardCharsets.US_ASCII));
    }

    private static Box find(RandomAccessFile f, Box parent, String name) throws IOException {
        for (long pos = parent.payload(); pos < parent.end();) {
            var child = read(f, pos, parent.end());
            if (child.type.equals(name)) return child;
            pos = child.end();
        }
        throw new IOException("Missing " + name + " in video index");
    }

    static void tag(Path file, int width, int height, int fullWidth, boolean stereo) throws IOException {
        // st3d layout 2 is left-eye-first side-by-side. Flat stereo carries no spherical projection.
        byte[] metadata = stereo ? box("st3d", new byte[]{0, 0, 0, 0, 2}) : sphere(width, height, fullWidth);
        try (var f = new RandomAccessFile(file.toFile(), "rw")) {
            List<Box> parents = new ArrayList<>();
            var parent = new Box(0, f.length(), 0, "");
            for (String name : List.of("moov", "trak", "mdia", "minf", "stbl", "stsd")) {
                parent = find(f, parent, name);
                parents.add(parent);
            }
            // FFmpeg wrote the media before the index, so growing the index preserves all chunk offsets.
            if (parents.getFirst().end() != f.length()) throw new IOException("Expected a trailing video index");
            var entry = read(f, parent.payload() + 8, parent.end());
            parents.add(entry);
            long insert = entry.payload() + 78; // VisualSampleEntry fields
            while (insert < entry.end()) {
                var child = read(f, insert, entry.end());
                if (child.type.equals("pasp") || child.type.equals("clap")) break;
                insert = child.end();
            }
            if (insert > entry.end()) throw new IOException("Invalid video sample entry");
            byte[] buffer = new byte[65536];
            for (long end = f.length(); end > insert;) {
                int count = (int) Math.min(buffer.length, end - insert);
                end -= count;
                f.seek(end);
                f.readFully(buffer, 0, count);
                f.seek(end + metadata.length);
                f.write(buffer, 0, count);
            }
            f.seek(insert);
            f.write(metadata);
            for (Box box : parents) {
                long size = box.size + metadata.length;
                if (box.header == 16) {
                    f.seek(box.start + 8);
                    f.writeLong(size);
                } else {
                    if (size > 0xFFFFFFFFL) throw new IOException("Video index exceeds 4 GiB");
                    f.seek(box.start);
                    f.writeInt((int) size);
                }
            }
        }
    }

    private SpatialVideoMetadata() {}
}
