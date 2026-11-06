package com.metrovoc.reprise.format;

import com.metrovoc.reprise.util.Varints;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** A {@code .reprise} file opened for playback. Segments and chunk files load on demand and may be dropped under memory pressure. */
public final class ReplayFile implements Closeable {
    private final Path path;
    private final ZipFile zip;
    private final ReplayMeta meta;
    private final Map<Integer, SoftReference<byte[]>> segments = new HashMap<>();
    private final Map<Integer, SoftReference<ChunkFile>> chunkFiles = new HashMap<>();

    private final Map<String, Path> extractedPacks = new HashMap<>();

    public Path localPack(String hash) throws IOException {
        Path existing = extractedPacks.get(hash);
        if (existing != null) return existing;
        Path temp = Files.createTempFile("reprise-pack-", ".zip");
        try {
            try (var output = Files.newOutputStream(temp)) {
                EmbeddedPacks.extract(zip, hash, output);
            }
            extractedPacks.put(hash, temp);
            return temp;
        } finally {
            if (!extractedPacks.containsKey(hash)) Files.deleteIfExists(temp);
        }
    }

    void copyPacks(ZipOutputStream out, Set<String> copied) throws IOException {
        for (var entries = zip.entries(); entries.hasMoreElements();) {
            String name = entries.nextElement().getName();
            if (!name.startsWith("resourcepack/") || !name.endsWith(".zip")) continue;
            String hash = name.substring("resourcepack/".length(), name.length() - 4);
            if (copied.add(hash)) EmbeddedPacks.copy(zip, out, hash);
        }
    }

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
        } catch (IOException | RuntimeException e) {
            this.zip.close();
            throw e;
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

    public boolean hasCameraPaths() {
        return zip.getEntry("timelines.json") != null;
    }

    public String readCameraPaths() throws IOException {
        var paths = zip.getEntry("timelines.json");
        if (paths == null || paths.getSize() > 16 * 1024 * 1024) throw new IOException("Missing or oversized camera path library");
        return new String(entry("timelines.json"), StandardCharsets.UTF_8);
    }

    public byte[] entry(String name) throws IOException {
        ZipEntry entry = this.zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = this.zip.getInputStream(entry)) {
            // The ZIP directory gives the inflated size. Fill the final array directly even for large terrain files.
            long size = entry.getSize();
            if (size < 0 || size > Integer.MAX_VALUE - 8) {
                throw new IOException("Unsupported replay entry size for " + name + ": " + size);
            }
            byte[] data = new byte[(int) size];
            new DataInputStream(in).readFully(data);
            return data;
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
        if (version > Actions.VIEW_MOTION_VERSION) {
            throw new IOException("Segment " + index + " needs a newer version of Reprise");
        }
        return buf;
    }

    /** Opens the tick stream when continuing into a segment whose snapshot is already represented in the client. */
    public ByteBuffer segmentActions(int index) throws IOException {
        ByteBuffer buf = segment(index);
        int offset = this.meta.segments.get(index).actionsOffset;
        if (offset != 0) {
            if (offset <= buf.position() || offset > buf.limit() || buf.get(offset - 1) != Actions.SNAPSHOT_END) {
                throw new IOException("Invalid snapshot offset in segment " + index);
            }
            return buf.position(offset);
        }
        while (buf.hasRemaining()) {
            switch (buf.get()) {
                case Actions.SNAPSHOT_END -> { return buf; }
                case Actions.SNAPSHOT_TICK -> { }
                case Actions.GAME_PACKET, Actions.CONFIG_PACKET, Actions.POV_SAMPLES, Actions.PACKED_POV, Actions.VIEW_MOTION -> {
                    int length = Varints.read(buf);
                    if (length < 0 || length > buf.remaining()) {
                        throw new IOException("Invalid snapshot packet length in segment " + index);
                    }
                    buf.position(buf.position() + length);
                }
                case Actions.CHUNK_REF -> Varints.read(buf);
                default -> throw new IOException("Unexpected snapshot action in segment " + index);
            }
        }
        throw new IOException("Missing snapshot end in segment " + index);
    }

    /** An encoded chunk packet from the chunk cache. */
    public byte[] chunk(int index) throws IOException {
        ByteBuffer packet = chunkView(index);
        byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes);
        return bytes;
    }

    /** A bounded view for the terrain decoder. Its content belongs to the archive. */
    public synchronized ByteBuffer chunkView(int index) throws IOException {
        if (index < 0 || index >= this.meta.chunkCount) {
            throw new IOException("Missing cached chunk " + index);
        }
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
        if (local >= file.offsets.size()) {
            throw new IOException("Missing chunk " + index + " in cache file " + fileIndex);
        }
        int start = file.offsets.getInt(local);
        return ByteBuffer.wrap(file.data).slice(start, file.lengths.getInt(local)).asReadOnlyBuffer();
    }

    @Override
    public void close() throws IOException {
        this.zip.close();
        for (Path pack : extractedPacks.values()) Files.deleteIfExists(pack);
        extractedPacks.clear();
    }
}
