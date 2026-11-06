package com.metrovoc.reprise.record;

import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.Actions;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.util.Varints;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Turns a finished recording folder into a single {@code .reprise} file. */
public final class ReplayPacker {
    public static final String EXTENSION = ".reprise";

    public static Path pack(Path dir, String name) throws IOException {
        Path target = uniqueName(ReprisePaths.replays(), name);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".tmp")).sorted().toList();
        }
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(dir.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(dir);
        return target;
    }

    /** Packs recordings left behind by a crash; returns how many were saved. */
    public static synchronized int recoverAll() {
        int count = 0;
        Recorder active = Recorder.active();
        try (Stream<Path> dirs = Files.list(ReprisePaths.recordings())) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (active != null && active.directory().equals(dir)) {
                    continue;
                }
                try {
                    if (recover(dir)) {
                        count++;
                    }
                } catch (Exception e) {
                    com.metrovoc.reprise.Reprise.LOGGER.warn("Could not recover {}", dir, e);
                }
            }
        } catch (IOException e) {
            com.metrovoc.reprise.Reprise.LOGGER.warn("Could not list recordings", e);
        }
        return count;
    }

    /** Repairs the metadata of an unfinished recording from what reached the disk, then packs it. */
    static boolean recover(Path dir) throws IOException {
        Path metaFile = dir.resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return false;
        }
        ReplayMeta meta = ReplayMeta.fromJson(Files.readString(metaFile));
        // Segments: every file present, cut after its last complete tick.
        java.util.List<ReplayMeta.Segment> written = new java.util.ArrayList<>(meta.segments);
        meta.segments.clear();
        meta.durationTicks = 0;
        for (int i = 0; Files.exists(dir.resolve(Actions.segmentFile(i))); i++) {
            Path file = dir.resolve(Actions.segmentFile(i));
            byte[] data = Files.readAllBytes(file);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
            int ticks = 0;
            int goodEnd = -1;
            int actionsOffset = 0;
            try {
                buf.getInt();
                buf.getInt();
                while (buf.hasRemaining()) {
                    int action = buf.get();
                    switch (action) {
                        case Actions.TICK -> ticks++;
                        case Actions.SNAPSHOT_TICK -> { }
                        case Actions.SNAPSHOT_END -> actionsOffset = buf.position();
                        case Actions.GAME_PACKET, Actions.CONFIG_PACKET, Actions.POV_SAMPLES, Actions.PACKED_POV, Actions.VIEW_MOTION -> {
                            int len = Varints.read(buf);
                            if (len > buf.remaining()) {
                                throw new java.nio.BufferUnderflowException();
                            }
                            buf.position(buf.position() + len);
                        }
                        case Actions.CHUNK_REF -> Varints.read(buf);
                        default -> throw new java.nio.BufferUnderflowException();
                    }
                    if (action == Actions.TICK || action == Actions.SNAPSHOT_END) {
                        goodEnd = buf.position();
                    }
                }
            } catch (java.nio.BufferUnderflowException | IllegalStateException truncated) {
                // The tail was being written when the game stopped.
            }
            if (actionsOffset == 0) {
                Files.delete(file);
                break;
            }
            if (goodEnd >= 0 && goodEnd < data.length) {
                Files.write(file, java.util.Arrays.copyOf(data, goodEnd));
            }
            ReplayMeta.Segment segment = new ReplayMeta.Segment(Actions.segmentFile(i), meta.durationTicks, i == 0 || (i < written.size() && written.get(i).reset));
            segment.actionsOffset = actionsOffset;
            segment.ticks = ticks;
            meta.segments.add(segment);
            meta.durationTicks += ticks;
        }
        if (meta.segments.isEmpty()) {
            deleteTree(dir);
            return false;
        }
        // Chunk cache: count complete entries.
        int chunks = 0;
        for (int f = 0; Files.exists(dir.resolve(Actions.chunkFile(f))); f++) {
            byte[] data = Files.readAllBytes(dir.resolve(Actions.chunkFile(f)));
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
            int end = 0;
            try {
                while (buf.hasRemaining()) {
                    int len = Varints.read(buf);
                    if (len > buf.remaining()) {
                        break;
                    }
                    buf.position(buf.position() + len);
                    end = buf.position();
                    chunks++;
                }
            } catch (RuntimeException ignored) {
            }
            if (end < data.length) {
                Files.write(dir.resolve(Actions.chunkFile(f)), java.util.Arrays.copyOf(data, end));
            }
        }
        meta.chunkCount = chunks;
        Files.writeString(metaFile, meta.toJson());
        pack(dir, meta.name + " (recovered)");
        return true;
    }

    public static Path uniqueName(Path folder, String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path candidate = folder.resolve(safe + EXTENSION);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = folder.resolve(safe + " (" + i + ")" + EXTENSION);
        }
        return candidate;
    }

    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private ReplayPacker() {}
}
