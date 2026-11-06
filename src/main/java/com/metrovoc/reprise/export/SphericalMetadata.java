package com.metrovoc.reprise.export;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Adds Spherical Video V2 to the first (video) sample entry in our staged FFmpeg output. */
final class SphericalMetadata {
    private static byte[] box(String type, byte[]... fields) {
        int size = 8;
        for (byte[] field : fields) size += field.length;
        var out = ByteBuffer.allocate(size).putInt(size).put(type.getBytes(StandardCharsets.US_ASCII));
        for (byte[] field : fields) out.put(field);
        return out.array();
    }

    private static final byte[] SPHERE = box("sv3d",
        box("svhd", new byte[4], "Reprise\0".getBytes(StandardCharsets.UTF_8)),
        box("proj", box("prhd", new byte[16]), box("equi", new byte[20])));

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

    static void tag(Path file) throws IOException {
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
                f.seek(end + SPHERE.length);
                f.write(buffer, 0, count);
            }
            f.seek(insert);
            f.write(SPHERE);
            for (Box box : parents) {
                long size = box.size + SPHERE.length;
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

    private SphericalMetadata() {}
}
