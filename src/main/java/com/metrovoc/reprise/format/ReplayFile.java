package com.metrovoc.reprise.format;

import com.metrovoc.reprise.util.Varints;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A {@code .reprise} file opened for playback. Segments and chunk files load on demand and may be dropped under memory pressure. */
public final class ReplayFile implements Closeable {
    private final Path path;
    private final ZipFile zip;
    private final ReplayMeta meta;
    private final Map<Integer, SoftReference<byte[]>> segments = new HashMap<>();
    private final Map<Integer, SoftReference<ChunkFile>> chunkFiles = new HashMap<>();

    private record ChunkFile(byte[] data, IntArrayList offsets, IntArrayList lengths) {}

    public ReplayFile(Path path) throws IOException {
        this.path = path;
        this.zip = new ZipFile(path.toFile());
        ZipEntry metaEntry = this.zip.getEntry("meta.json");
        if (metaEntry == null) {
            this.zip.close();
            throw new IOException("Not a Reprise replay: " + path.getFileName());
        }
        try (InputStream in = this.zip.getInputStream(metaEntry)) {
            this.meta = ReplayMeta.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static ReplayMeta readMeta(Path path) throws IOException {
        try (ReplayFile file = new ReplayFile(path)) {
            return file.meta();
        }
    }

    public Path path() {
        return this.path;
    }

    public ReplayMeta meta() {
        return this.meta;
    }

    public byte[] entry(String name) throws IOException {
        ZipEntry entry = this.zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = this.zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** The segment's bytes, after the header. */
    public synchronized ByteBuffer segment(int index) throws IOException {
        SoftReference<byte[]> ref = this.segments.get(index);
        byte[] data = ref == null ? null : ref.get();
        if (data == null) {
            data = entry(this.meta.segments.get(index).file);
            if (data == null) {
                throw new IOException("Missing segment " + index);
            }
            this.segments.put(index, new SoftReference<>(data));
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        if (buf.getInt() != Actions.MAGIC) {
            throw new IOException("Bad segment header in segment " + index);
        }
        int version = buf.getInt();
        if (version > Actions.VERSION) {
            throw new IOException("Segment " + index + " needs a newer version of Reprise");
        }
        return buf;
    }

    /** An encoded chunk packet from the chunk cache. */
    public synchronized byte[] chunk(int index) throws IOException {
        int fileIndex = index / Actions.CHUNKS_PER_FILE;
        SoftReference<ChunkFile> ref = this.chunkFiles.get(fileIndex);
        ChunkFile file = ref == null ? null : ref.get();
        if (file == null) {
            byte[] data = entry(Actions.chunkFile(fileIndex));
            if (data == null) {
                throw new IOException("Missing chunk file " + fileIndex);
            }
            IntArrayList offsets = new IntArrayList();
            IntArrayList lengths = new IntArrayList();
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                int length = Varints.read(buf);
                offsets.add(buf.position());
                lengths.add(length);
                buf.position(buf.position() + length);
            }
            file = new ChunkFile(data, offsets, lengths);
            this.chunkFiles.put(fileIndex, new SoftReference<>(file));
        }
        int local = index % Actions.CHUNKS_PER_FILE;
        int start = file.offsets.getInt(local);
        byte[] out = new byte[file.lengths.getInt(local)];
        System.arraycopy(file.data, start, out, 0, out.length);
        return out;
    }

    @Override
    public void close() throws IOException {
        this.zip.close();
    }
}
