package com.metrovoc.reprise.format;

import com.google.common.io.FileBackedOutputStream;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.metrovoc.reprise.record.PacketCodecs;
import com.metrovoc.reprise.util.Varints;
import io.netty.buffer.Unpooled;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.configuration.ConfigurationPacketTypes;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginPacketTypes;
import net.minecraft.network.protocol.login.LoginProtocols;

/** Imports the timestamped clientbound wire packets in a ReplayMod format 14 recording. */
public final class ReplayModImport {
    private enum Phase { LOGIN, CONFIGURATION, PLAY }

    private static JsonElement json(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > 16 * 1024 * 1024) throw new IOException("Replay metadata exceeds 16 MiB");
        try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    public static Path convert(Path source, Path destination) throws IOException {
        try (var zip = new ZipFile(source.toFile())) {
            var header = zip.getEntry("metaData.json");
            var packets = zip.getEntry("recording.tmcpr");
            if (header == null || packets == null) throw new IOException("Missing ReplayMod metadata or packet stream");
            var m = json(zip, header).getAsJsonObject();
            int protocol = m.get("protocol").getAsInt();
            boolean legacy = protocol == 767 && SharedConstants.getProtocolVersion() == 775;
            if (!"MCPR".equals(m.get("fileFormat").getAsString()) || m.get("fileFormatVersion").getAsInt() != 14) {
                throw new IOException("Choose a format 14 ReplayMod recording.");
            }
            if (!legacy && protocol != SharedConstants.getProtocolVersion()) {
                throw new IOException("Unsupported ReplayMod recording protocol " + protocol);
            }
            var meta = new ReplayMeta();
            meta.name = source.getFileName().toString().replaceFirst("(?i)\\.mcpr$", "");
            meta.protocol = SharedConstants.getProtocolVersion();
            meta.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
            meta.mcVersion = m.get("mcversion").getAsString();
            meta.recordedAt = m.get("date").getAsLong();
            meta.server = m.get("serverName").getAsString();
            meta.singleplayer = m.get("singleplayer").getAsBoolean();
            int duration = m.get("duration").getAsInt();
            var mods = zip.getEntry("mods.json");
            if (mods != null) {
                for (var element : json(zip, mods).getAsJsonObject().getAsJsonArray("requiredMods")) {
                    var mod = element.getAsJsonObject();
                    meta.mods.put(mod.get("modID").getAsString(), mod.get("modVersion").getAsString());
                }
            }
            var markers = zip.getEntry("markers.json");
            if (markers != null) {
                for (var element : json(zip, markers).getAsJsonArray()) {
                    var item = element.getAsJsonObject();
                    var value = item.getAsJsonObject("value");
                    var position = value.getAsJsonObject("position");
                    var marker = new ReplayMeta.Marker();
                    marker.tick = tickAt(item.get("realTimestamp").getAsInt());
                    if (value.has("name") && !value.get("name").isJsonNull()) marker.name = value.get("name").getAsString();
                    marker.x = position.get("x").getAsDouble();
                    marker.y = position.get("y").getAsDouble();
                    marker.z = position.get("z").getAsDouble();
                    marker.yaw = position.get("yaw").getAsFloat();
                    marker.pitch = position.get("pitch").getAsFloat();
                    meta.markers.add(marker);
                }
            }
            Map<String, String> packs = new HashMap<>();
            var packIndex = zip.getEntry("resourcepack/index.json");
            if (packIndex != null) json(zip, packIndex).getAsJsonObject().entrySet().forEach(entry ->
                packs.put("replay://" + entry.getKey(), entry.getValue().getAsString()));
            return ReplayOutput.write(destination, meta, out -> {
                for (String hash : new HashSet<>(packs.values())) EmbeddedPacks.copy(zip, out, hash);
                var paths = zip.getEntry("timelines.json");
                if (paths != null) {
                    out.putNextEntry(new ZipEntry("timelines.json"));
                    try (var input = zip.getInputStream(paths)) { input.transferTo(out); }
                }
                var segment = new ReplayMeta.Segment(Actions.segmentFile(0), 0, true);
                // MCPR interleaves terrain and game packets. Stream terrain straight into chunk-cache
                // entries while spooling the smaller timeline separately, then append it to the ZIP.
                Path timeline = Files.createTempFile(destination, ".timeline-", ".part");
                try {
                    try (var data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(timeline)));
                         var writer = new PacketWriter(data, out, meta, segment, packs)) {
                        if (legacy) {
                            LegacyMcpr.read(zip, packets, writer);
                        } else {
                            try (var input = new DataInputStream(new BufferedInputStream(zip.getInputStream(packets)))) {
                                readPackets(input, packets.getSize(), writer);
                            }
                        }
                        writer.finish(duration);
                    }
                    out.putNextEntry(new ZipEntry(segment.file));
                    Files.copy(timeline, out);
                } finally {
                    Files.deleteIfExists(timeline);
                }
                meta.segments.add(segment);
            });
        } catch (RuntimeException e) {
            throw new IOException("Invalid ReplayMod recording: " + e.getMessage(), e);
        }
    }

    static void checkFrame(int time, int size, long remaining) throws IOException {
        if (time < 0 || size < 0 || size > remaining || size > 64 * 1024 * 1024) {
            throw new IOException("Invalid ReplayMod packet header at " + time + " ms");
        }
    }

    private static void readPackets(DataInputStream in, long remaining, PacketWriter writer) throws IOException {
        while (remaining > 0) {
            int time = in.readInt();
            int size = in.readInt();
            remaining -= 8;
            checkFrame(time, size, remaining);
            byte[] bytes = new byte[size];
            in.readFully(bytes);
            remaining -= size;
            writer.accept(time, bytes);
        }
    }

    static final class PacketWriter implements AutoCloseable {
        private final DataOutputStream out, chunks;
        private final ZipOutputStream zip;
        private final ReplayMeta meta;
        private final ReplayMeta.Segment segment;
        private final Map<String, String> packs;
        private final Map<PacketType<?>, Integer> loginIds = new HashMap<>(), configIds = new HashMap<>(), ids = new HashMap<>();
        // Stage only the current bundle. Large bundles spill to disk until their closing delimiter arrives.
        private final FileBackedOutputStream bundle = new FileBackedOutputStream(2 * 1024 * 1024);
        private final DataOutputStream bundleOut = new DataOutputStream(bundle);
        private int bundleTime = -1, bundleSize, lastTimestamp;
        private Phase phase = Phase.LOGIN;
        private boolean joined;
        private int recorderId = -1, spawnTick = -1, terrainTick = -1, previous;

        PacketWriter(DataOutputStream out, ZipOutputStream zip, ReplayMeta meta, ReplayMeta.Segment segment,
                     Map<String, String> packs) throws IOException {
            this.out = out;
            this.zip = zip;
            this.meta = meta;
            this.segment = segment;
            this.packs = packs;
            this.chunks = new DataOutputStream(new BufferedOutputStream(zip));
            LoginProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(loginIds::put);
            ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(configIds::put);
            GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(ids::put);
            out.writeInt(Actions.MAGIC);
            out.writeInt(Actions.VERSION);
        }

        void accept(int time, byte[] bytes) throws IOException {
            lastTimestamp = Math.max(lastTimestamp, time);
            if (bytes.length == 0) return;
            var body = ByteBuffer.wrap(bytes);
            int id = Varints.read(body);
            if (phase == Phase.PLAY && id == ids.get(GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER)) {
                if (body.hasRemaining()) throw new IOException("Invalid bundle delimiter at " + time + " ms");
                if (bundleTime < 0) {
                    previous = Math.max(previous, time);
                    bundleTime = previous;
                } else {
                    flushBundle();
                }
                return;
            }
            if (bundleTime >= 0) {
                if (bundleSize == BundlerInfo.BUNDLE_SIZE_LIMIT) throw new IOException("Too many packets in bundle at " + bundleTime + " ms");
                bundleOut.writeInt(bytes.length);
                bundleOut.write(bytes);
                bundleSize++;
                return;
            }
            // A late packet whose time has already passed is due immediately, in file order.
            previous = Math.max(previous, time);
            writePacket(previous, bytes, body, id);
        }

        private void flushBundle() throws IOException {
            // ReplayMod reads through the closing delimiter as soon as the opening delimiter is due.
            try (var input = new DataInputStream(bundle.asByteSource().openStream())) {
                for (int i = 0; i < bundleSize; i++) {
                    byte[] bytes = new byte[input.readInt()];
                    input.readFully(bytes);
                    var body = ByteBuffer.wrap(bytes);
                    int id = Varints.read(body);
                    writePacket(bundleTime, bytes, body, id);
                }
            }
            bundle.reset();
            bundleTime = -1;
            bundleSize = 0;
        }

        @Override
        public void close() throws IOException {
            // An unfinished trailing bundle was never delivered by the network pipeline.
            bundle.reset();
        }

        private void writePacket(int time, byte[] bytes, ByteBuffer body, int id) throws IOException {
            int size = bytes.length;
            if (phase == Phase.LOGIN) {
                if (id == loginIds.get(LoginPacketTypes.CLIENTBOUND_LOGIN_FINISHED)) {
                    var buf = Unpooled.wrappedBuffer(body);
                    try {
                        var profile = ClientboundLoginFinishedPacket.STREAM_CODEC.decode(buf).gameProfile();
                        meta.recorderName = profile.name();
                        meta.recorderUuid = profile.id().toString();
                    } finally {
                        buf.release();
                    }
                    phase = Phase.CONFIGURATION;
                }
                return;
            }
            // ReplayMod rewrites pack URLs to its own archive-local scheme.
            var phaseIds = phase == Phase.PLAY ? ids : configIds;
            if (id == phaseIds.get(CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_PUSH)) {
                var packet = PacketCodecs.decode(ClientboundResourcePackPushPacket.STREAM_CODEC, body);
                String hash = packs.get(packet.url());
                if (hash == null) return; // ReplayMod omits the index entry for a declined or failed pack.
                var local = new ClientboundResourcePackPushPacket(packet.id(), EmbeddedPacks.URL_PREFIX + hash,
                    hash, packet.required(), packet.prompt());
                byte[] payload = PacketCodecs.encode(ClientboundResourcePackPushPacket.STREAM_CODEC, local);
                var framed = new ByteArrayOutputStream();
                Varints.write(new DataOutputStream(framed), id);
                framed.write(payload);
                bytes = framed.toByteArray();
                size = bytes.length;
            }
            if (phase == Phase.PLAY) {
                if (id == ids.get(GamePacketTypes.CLIENTBOUND_START_CONFIGURATION)) {
                    throw new IOException("Recording switches configuration during play at " + time + " ms");
                }
                if (joined && time > 0 && segment.actionsOffset == 0) endSnapshot(out, segment);
                if (segment.actionsOffset != 0) advance(out, segment, Math.max(0, (time - 1) / 50));
                if (id == ids.get(GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT)) {
                    if (meta.chunkCount % Actions.CHUNKS_PER_FILE == 0) {
                        chunks.flush();
                        zip.putNextEntry(new ZipEntry(Actions.chunkFile(meta.chunkCount / Actions.CHUNKS_PER_FILE)));
                    }
                    if (terrainTick < 0) terrainTick = tickAt(time);
                    Varints.write(chunks, size);
                    chunks.write(bytes);
                    out.writeByte(Actions.CHUNK_REF);
                    Varints.write(out, meta.chunkCount++);
                    return;
                }
                if (id == ids.get(GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES)) {
                    bytes = vanillaBlockParticle(bytes, body);
                    size = bytes.length;
                }
                if (id == ids.get(GamePacketTypes.CLIENTBOUND_LOGIN)) {
                    recorderId = body.getInt();
                    joined = true;
                } else if (id == ids.get(GamePacketTypes.CLIENTBOUND_ADD_ENTITY) && spawnTick < 0 && Varints.read(body) == recorderId) {
                    spawnTick = tickAt(time);
                }
            }
            out.writeByte(phase == Phase.CONFIGURATION ? Actions.CONFIG_PACKET : Actions.GAME_PACKET);
            Varints.write(out, size);
            out.write(bytes);
            if (phase == Phase.CONFIGURATION && id == configIds.get(ConfigurationPacketTypes.CLIENTBOUND_FINISH_CONFIGURATION)) {
                phase = Phase.PLAY;
            }
            if (out.size() == Integer.MAX_VALUE) throw new IOException("Recording exceeds the supported segment size");
        }

        void finish(int duration) throws IOException {
            chunks.flush();
            if (!joined) throw new IOException("ReplayMod recording contains no world login");
            if (segment.actionsOffset == 0) endSnapshot(out, segment);
            advance(out, segment, tickAt(Math.max(duration, lastTimestamp)));
            meta.durationTicks = segment.ticks;
            meta.openingTick = Math.max(0, Math.max(spawnTick, terrainTick));
        }
    }

    /** Fabric API can append a block position to block particles for tinting. Re-emit the vanilla wire form. */
    private static byte[] vanillaBlockParticle(byte[] bytes, ByteBuffer body) throws IOException {
        // Two flags, XYZ, three spreads, speed, count, then the particle type and its options.
        body.position(body.position() + 2 + 3 * Double.BYTES + 4 * Float.BYTES + Integer.BYTES);
        var type = BuiltInRegistries.PARTICLE_TYPE.byId(Varints.read(body));
        if (type != ParticleTypes.BLOCK && type != ParticleTypes.BLOCK_MARKER && type != ParticleTypes.BLOCK_CRUMBLE
            && type != ParticleTypes.DUST_PILLAR && type != ParticleTypes.FALLING_DUST) return bytes;
        int options = body.position();
        if (Varints.read(body) != -1) return bytes;
        int start = body.position();
        Varints.read(body); // encoded block state
        if (body.remaining() != Long.BYTES) throw new IOException("Invalid Fabric block particle position");
        int length = body.position() - start;
        byte[] result = Arrays.copyOf(bytes, options + length);
        System.arraycopy(bytes, start, result, options, length);
        return result;
    }

    private static void endSnapshot(DataOutputStream out, ReplayMeta.Segment segment) throws IOException {
        out.writeByte(Actions.SNAPSHOT_END);
        segment.actionsOffset = out.size();
    }

    private static int tickAt(int millis) {
        return (int) ((millis + 49L) / 50);
    }

    private static void advance(DataOutputStream out, ReplayMeta.Segment segment, int tick) throws IOException {
        while (segment.ticks < tick) {
            out.writeByte(Actions.TICK);
            segment.ticks++;
        }
    }

    private ReplayModImport() {}
}
