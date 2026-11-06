package com.metrovoc.reprise.format;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.metrovoc.reprise.util.Varints;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Joins two recordings without decoding their game packets or changing the source archives. */
public final class ReplayJoiner {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static Path join(Path firstPath, Path secondPath, String name) throws IOException {
        Path folder = firstPath.toAbsolutePath().getParent();
        try (ReplayFile first = new ReplayFile(firstPath); ReplayFile second = new ReplayFile(secondPath)) {
            ReplayMeta a = first.meta(), b = second.meta();
            if (a.format != ReplayMeta.FORMAT || b.format != ReplayMeta.FORMAT || a.protocol != b.protocol
                || a.dataVersion != b.dataVersion || !a.mcVersion.equals(b.mcVersion)) {
                throw new IOException("Choose recordings from the same Minecraft and replay format version.");
            }
            if (a.snapshotConnectionType != b.snapshotConnectionType || (!a.snapshotConnectionType && a.neoforgeConnection != b.neoforgeConnection)) {
                throw new IOException("These recordings use different connection settings.");
            }
            if (a.segments.isEmpty() || b.segments.isEmpty()) {
                throw new IOException("Both recordings need a world snapshot.");
            }
            ReplayMeta combined = ReplayMeta.fromJson(a.toJson());
            combined.uuid = UUID.randomUUID().toString();
            combined.name = name.isBlank() ? "Joined " + LocalDateTime.now().format(TIMESTAMP) : name.strip();
            combined.recordedAt = System.currentTimeMillis();
            combined.durationTicks = Math.addExact(a.durationTicks, b.durationTicks);
            combined.segments.clear();
            for (var marker : b.markers) {
                marker.tick = Math.addExact(marker.tick, a.durationTicks);
                combined.markers.add(marker);
            }
            for (var mod : b.mods.entrySet()) {
                String version = combined.mods.putIfAbsent(mod.getKey(), mod.getValue());
                if (version != null && !version.equals(mod.getValue())) {
                    throw new IOException("The recordings use different versions of " + mod.getKey() + ".");
                }
            }
            if (!a.server.equals(b.server)) combined.server = "";
            return ReplayOutput.write(folder, combined, zip -> {
                var packs = new HashSet<String>();
                first.copyPacks(zip, packs);
                second.copyPacks(zip, packs);
                Map<HashCode, Integer> chunks = new HashMap<>();
                var out = new DataOutputStream(new BufferedOutputStream(zip));
                int[] firstIds = copyChunks(first, zip, out, chunks);
                int[] secondIds = copyChunks(second, zip, out, chunks);
                out.flush();
                combined.chunkCount = chunks.size();
                copySegments(first, firstIds, combined, 0, zip);
                copySegments(second, secondIds, combined, a.durationTicks, zip);
            });
        }
    }

    private static int[] copyChunks(ReplayFile source, ZipOutputStream zip, DataOutputStream out,
                                    Map<HashCode, Integer> chunks) throws IOException {
        int[] ids = new int[source.meta().chunkCount];
        var channel = Channels.newChannel(out);
        for (int i = 0; i < ids.length; i++) {
            ByteBuffer packet = source.chunkView(i);
            HashCode hash = Hashing.sha256().hashBytes(packet.duplicate());
            int next = chunks.size();
            Integer existing = chunks.putIfAbsent(hash, next);
            if (existing != null) {
                ids[i] = existing;
                continue;
            }
            if (next % Actions.CHUNKS_PER_FILE == 0) {
                out.flush();
                zip.putNextEntry(new ZipEntry(Actions.chunkFile(next / Actions.CHUNKS_PER_FILE)));
            }
            Varints.write(out, packet.remaining());
            channel.write(packet);
            ids[i] = next;
        }
        return ids;
    }

    private static void copySegments(ReplayFile source, int[] ids, ReplayMeta combined, int tickOffset,
                                     ZipOutputStream zip) throws IOException {
        int ticks = 0;
        for (int i = 0; i < source.meta().segments.size(); i++) {
            var original = source.meta().segments.get(i);
            if (original.startTick != ticks) throw new IOException("Invalid start tick in " + original.file);
            String name = Actions.segmentFile(combined.segments.size());
            var segment = new ReplayMeta.Segment(name, Math.addExact(tickOffset, original.startTick), i == 0 || original.reset);
            ByteBuffer buf = source.segment(i);
            zip.putNextEntry(new ZipEntry(name));
            var out = new DataOutputStream(new BufferedOutputStream(zip));
            out.writeInt(Actions.MAGIC);
            out.writeInt(Actions.VERSION);
            while (buf.hasRemaining()) {
                int action = buf.get();
                out.writeByte(action);
                switch (action) {
                    case Actions.TICK -> segment.ticks++;
                    case Actions.SNAPSHOT_END -> segment.actionsOffset = out.size();
                    case Actions.CHUNK_REF -> {
                        int old = Varints.read(buf);
                        if (old < 0 || old >= ids.length) throw new IOException("Missing terrain packet " + old);
                        Varints.write(out, ids[old]);
                    }
                    case Actions.GAME_PACKET, Actions.CONFIG_PACKET -> {
                        int size = Varints.read(buf);
                        if (size < 0 || size > buf.remaining()) throw new IOException("Truncated packet in " + original.file);
                        Varints.write(out, size);
                        out.write(buf.array(), buf.arrayOffset() + buf.position(), size);
                        buf.position(buf.position() + size);
                    }
                    default -> throw new IOException("Unknown replay action " + action);
                }
            }
            out.flush();
            if (segment.actionsOffset == 0 || segment.ticks != original.ticks) {
                throw new IOException("Incomplete replay segment " + original.file);
            }
            combined.segments.add(segment);
            ticks = Math.addExact(ticks, segment.ticks);
        }
        if (ticks != source.meta().durationTicks) throw new IOException("Recording duration disagrees with its segments.");
    }

    private ReplayJoiner() {}
}
