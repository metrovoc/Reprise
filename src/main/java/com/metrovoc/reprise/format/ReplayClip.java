package com.metrovoc.reprise.format;

import com.metrovoc.reprise.util.Varints;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Saves the state at start and the recorded ticks (start, end] as a standalone recording. */
public final class ReplayClip {
    public static Path save(Path source, int start, int end) throws IOException {
        try (ReplayFile replay = new ReplayFile(source)) {
            var original = replay.meta();
            if (original.format != ReplayMeta.FORMAT || original.segments.isEmpty()
                || start < 0 || start >= end || end > original.durationTicks) {
                throw new IOException("Choose a replay range with at least one tick.");
            }
            var clip = ReplayMeta.fromJson(original.toJson());
            clip.name = "Clip " + start + "-" + end + " " + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            clip.uuid = UUID.randomUUID().toString();
            clip.recordedAt = System.currentTimeMillis();
            clip.durationTicks = end - start;
            clip.openingTick = Math.clamp(original.openingTick - start, 0, clip.durationTicks);
            clip.markers.removeIf(m -> m.tick < start || m.tick > end);
            clip.markers.forEach(m -> m.tick -= start);
            clip.worldSwitchTicks.removeIf(t -> t <= start || t > end);
            clip.worldSwitchTicks.replaceAll(t -> t - start);
            clip.segments.clear();
            return ReplayOutput.write(source.toAbsolutePath().getParent(), clip, zip -> {
                replay.copyPacks(zip, new HashSet<>());
                var chunkIds = new LinkedHashMap<Integer, Integer>();
                int first = 0;
                while (first + 1 < original.segments.size() && original.segments.get(first + 1).startTick <= start) first++;
                for (int i = first; i < original.segments.size(); i++) {
                    var segment = original.segments.get(i);
                    if (segment.startTick > end) break;
                    boolean hasNext = i + 1 < original.segments.size() && original.segments.get(i + 1).startTick <= end;
                    copySegment(replay, i, start, end, hasNext, clip, chunkIds, zip);
                }
                var out = new DataOutputStream(new BufferedOutputStream(zip));
                var channel = Channels.newChannel(out);
                for (var entry : chunkIds.entrySet()) {
                    if (entry.getValue() % Actions.CHUNKS_PER_FILE == 0) {
                        out.flush();
                        zip.putNextEntry(new ZipEntry(Actions.chunkFile(entry.getValue() / Actions.CHUNKS_PER_FILE)));
                    }
                    var packet = replay.chunkView(entry.getKey());
                    Varints.write(out, packet.remaining());
                    channel.write(packet);
                }
                out.flush();
                clip.chunkCount = chunkIds.size();
            });
        }
    }

    private static void copySegment(ReplayFile replay, int index, int start, int end, boolean hasNext, ReplayMeta clip,
                                    Map<Integer, Integer> chunkIds, ZipOutputStream zip) throws IOException {
        var original = replay.meta().segments.get(index);
        var segment = new ReplayMeta.Segment(Actions.segmentFile(clip.segments.size()), Math.max(0, original.startTick - start),
            clip.segments.isEmpty() || original.reset);
        zip.putNextEntry(new ZipEntry(segment.file));
        var out = new DataOutputStream(new BufferedOutputStream(zip));
        out.writeInt(Actions.MAGIC);
        ByteBuffer data = replay.segment(index);
        int version = data.getInt(Integer.BYTES);
        out.writeInt(start > original.startTick ? Math.max(version, Actions.SNAPSHOT_TICK_VERSION) : version);
        int tick = original.startTick;
        // Before the in-point, fold packet history into the snapshot in its original order. This also
        // preserves state from mod payloads whose effects Reprise cannot reconstruct independently.
        while (data.hasRemaining() && (hasNext || tick < end || segment.actionsOffset == 0)) {
            int action = data.get();
            switch (action) {
                case Actions.SNAPSHOT_END -> {
                    if (tick >= start) {
                        out.writeByte(action);
                        segment.actionsOffset = out.size();
                    }
                }
                case Actions.SNAPSHOT_TICK -> out.writeByte(action);
                case Actions.TICK -> {
                    tick++;
                    if (tick <= start) out.writeByte(Actions.SNAPSHOT_TICK);
                    if (tick == start) {
                        out.writeByte(Actions.SNAPSHOT_END);
                        segment.actionsOffset = out.size();
                    } else if (tick > start) {
                        out.writeByte(action);
                        segment.ticks++;
                    }
                }
                case Actions.CHUNK_REF -> {
                    int id = Varints.read(data);
                    int mapped = chunkIds.computeIfAbsent(id, key -> chunkIds.size());
                    out.writeByte(action);
                    Varints.write(out, mapped);
                }
                case Actions.CONFIG_PACKET, Actions.GAME_PACKET, Actions.POV_SAMPLES -> {
                    int size = Varints.read(data);
                    if (size < 0 || size > data.remaining()) throw new IOException("Truncated packet in " + original.file);
                    out.writeByte(action);
                    Varints.write(out, size);
                    out.write(data.array(), data.arrayOffset() + data.position(), size);
                    data.position(data.position() + size);
                }
                default -> throw new IOException("Unknown replay action " + action);
            }
        }
        out.flush();
        if (segment.actionsOffset == 0 || tick != Math.min(end, original.startTick + original.ticks)) {
            throw new IOException("Incomplete segment " + original.file);
        }
        clip.segments.add(segment);
    }

    private ReplayClip() {}
}
