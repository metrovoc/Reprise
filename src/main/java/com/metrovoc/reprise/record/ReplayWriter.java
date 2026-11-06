package com.metrovoc.reprise.record;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.format.Actions;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.util.Varints;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.resources.server.ServerPackManager;

/**
 * Writes a recording folder on its own thread. The game thread hands over already encoded packets, so this thread only
 * does file IO and chunk de-duplication; a slow disk never stalls the game.
 */
public final class ReplayWriter {
    private final Path dir;
    private final ReplayMeta meta;
    private final BlockingQueue<Consumer<ReplayWriter>> tasks = new ArrayBlockingQueue<>(4096);
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Throwable failure;

    private DataOutputStream segment;
    private ReplayMeta.Segment currentSegment;
    private DataOutputStream chunkOut;
    private int chunkOutFile = -1;
    private final Map<ServerPackManager.ServerPackData, String> packHashes = new HashMap<>();
    private final Map<HashCode, Integer> chunkIndex = new HashMap<>();

    public ReplayWriter(Path dir, ReplayMeta meta) throws IOException {
        this.dir = dir;
        this.meta = meta;
        Files.createDirectories(dir.resolve("segments"));
        Files.createDirectories(dir.resolve("chunks"));
        this.thread = new Thread(this::run, "Reprise Writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public Path dir() {
        return this.dir;
    }

    /** Runs {@code task} on the writer thread, in submission order. Blocks if the writer is far behind. */
    public void submit(Consumer<ReplayWriter> task) {
        if (this.failure != null) {
            return; // endTick stops the recording and reports the failure
        }
        try {
            this.tasks.put(task);
        } catch (InterruptedException e) {
            this.failure = e;
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (this.running || !this.tasks.isEmpty()) {
            try {
                Consumer<ReplayWriter> task = this.tasks.poll(100, TimeUnit.MILLISECONDS);
                if (task != null && this.failure == null) {
                    task.accept(this);
                }
            } catch (InterruptedException e) {
                recordFailure(e);
                break;
            } catch (Throwable t) {
                recordFailure(t);
            }
        }
        try (var segmentStream = this.segment; var chunks = this.chunkOut) {
            // Closing both streams flushes any recoverable prefix, including after a failed task.
        } catch (IOException e) {
            recordFailure(e);
        }
        if (this.failure == null) {
            try {
                writeMeta();
            } catch (RuntimeException e) {
                recordFailure(e);
            }
        }
    }

    private void recordFailure(Throwable cause) {
        Reprise.LOGGER.error("Replay writer failed", cause);
        if (this.failure == null) {
            this.failure = cause;
        }
    }

    /** Drains the queue and waits until files have closed, including after a disk error. */
    public void close() {
        this.running = false;
        boolean interrupted = false;
        while (this.thread.isAlive()) {
            try {
                this.thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public Throwable failure() {
        return this.failure;
    }

    // Everything below runs on the writer thread.

    public void beginSegment(boolean reset) {
        beginSegment(reset, Actions.VERSION);
    }

    void beginSegment(boolean reset, int version) {
        try {
            closeSegment();
            int index = this.meta.segments.size();
            this.currentSegment = new ReplayMeta.Segment(Actions.segmentFile(index), this.meta.durationTicks, reset);
            this.meta.segments.add(this.currentSegment);
            this.segment = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(this.dir.resolve(this.currentSegment.file)), 1 << 16));
            this.segment.writeInt(Actions.MAGIC);
            this.segment.writeInt(version);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void closeSegment() throws IOException {
        if (this.segment != null) {
            this.segment.close();
            this.segment = null;
        }
    }

    void viewMotion(byte[] bytes) { action(Actions.VIEW_MOTION, bytes); }

    void povSamples(byte[] bytes) { action(Actions.PACKED_POV, bytes); }

    public void gamePacket(byte[] bytes) {
        action(Actions.GAME_PACKET, bytes);
    }

    public void configPacket(byte[] bytes) {
        action(Actions.CONFIG_PACKET, bytes);
    }

    String storePack(ServerPackManager.ServerPackData pack, Path source) {
        String cached = packHashes.get(pack);
        if (cached != null) return cached;
        try {
            Path folder = Files.createDirectories(dir.resolve("resourcepack"));
            Path temp = Files.createTempFile(folder, "copy-", ".tmp");
            try {
                String hash;
                try (var input = new HashingInputStream(Hashing.sha1(), Files.newInputStream(source))) {
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                    hash = input.hash().toString();
                }
                Path target = folder.resolve(hash + ".zip");
                if (!Files.exists(target)) Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                packHashes.put(pack, hash);
                return hash;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not record applied server pack", e);
        }
    }

    /** A chunk packet: stored once in the chunk cache however often it is recorded. */
    public void chunkPacket(byte[] bytes) {
        try {
            HashCode hash = Hashing.sha256().hashBytes(bytes);
            Integer index = this.chunkIndex.get(hash);
            if (index == null) {
                index = this.meta.chunkCount++;
                this.chunkIndex.put(hash, index);
                int file = index / Actions.CHUNKS_PER_FILE;
                if (file != this.chunkOutFile) {
                    if (this.chunkOut != null) {
                        this.chunkOut.close();
                    }
                    this.chunkOut = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(this.dir.resolve(Actions.chunkFile(file)), StandardOpenOption.CREATE, StandardOpenOption.APPEND), 1 << 16));
                    this.chunkOutFile = file;
                }
                Varints.write(this.chunkOut, bytes.length);
                this.chunkOut.write(bytes);
                this.chunkOut.flush();
            }
            this.segment.writeByte(Actions.CHUNK_REF);
            Varints.write(this.segment, index);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void snapshotEnd() {
        action(Actions.SNAPSHOT_END, null);
        this.currentSegment.actionsOffset = this.segment.size();
        try {
            this.segment.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        writeMeta();
    }

    public void tick() {
        action(Actions.TICK, null);
        this.meta.durationTicks++;
        this.currentSegment.ticks++;
        // A crash loses at most a second: buffered packets reach the disk every 20 ticks.
        if (this.currentSegment.ticks % 20 == 0) {
            try {
                this.segment.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    void unrecorded(String type) {
        if (this.meta.unrecorded == null) this.meta.unrecorded = new java.util.LinkedHashMap<>();
        this.meta.unrecorded.merge(type, 1, Integer::sum);
    }

    public int segmentTicks() {
        return this.currentSegment == null ? 0 : this.currentSegment.ticks;
    }

    public void addMarker(ReplayMeta.Marker marker) {
        marker.tick = this.meta.durationTicks;
        this.meta.markers.add(marker);
    }

    private void action(int id, byte[] payload) {
        try {
            this.segment.writeByte(id);
            if (payload != null) {
                Varints.write(this.segment, payload.length);
                this.segment.write(payload);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeMeta() {
        try {
            Path tmp = this.dir.resolve("meta.json.tmp");
            Files.writeString(tmp, this.meta.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, this.dir.resolve("meta.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
