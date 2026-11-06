package com.metrovoc.reprise.format;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.metrovoc.reprise.util.Varints;
import io.netty.buffer.Unpooled;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.PacketType;
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
            if (!"MCPR".equals(m.get("fileFormat").getAsString()) || m.get("fileFormatVersion").getAsInt() != 14
                || m.get("protocol").getAsInt() != SharedConstants.getProtocolVersion()) {
                throw new IOException("Choose a format 14 ReplayMod recording from this Minecraft protocol version.");
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
            return ReplayOutput.write(destination, meta, out -> {
                var segment = new ReplayMeta.Segment(Actions.segmentFile(0), 0, true);
                // MCPR interleaves terrain and game packets. Stream terrain straight into chunk-cache
                // entries while spooling the smaller timeline separately, then append it to the ZIP.
                Path timeline = Files.createTempFile(destination, ".timeline-", ".part");
                try {
                    try (var in = new DataInputStream(new BufferedInputStream(zip.getInputStream(packets)));
                         var data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(timeline)))) {
                        readPackets(in, packets.getSize(), data, out, meta, segment, duration);
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

    private static void readPackets(DataInputStream in, long remaining, DataOutputStream out, ZipOutputStream zip, ReplayMeta meta,
                                    ReplayMeta.Segment segment, int duration) throws IOException {
        Map<PacketType<?>, Integer> loginIds = new HashMap<>(), configIds = new HashMap<>(), ids = new HashMap<>();
        LoginProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(loginIds::put);
        ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(configIds::put);
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(ids::put);
        out.writeInt(Actions.MAGIC);
        out.writeInt(Actions.VERSION);
        var chunks = new DataOutputStream(new BufferedOutputStream(zip));
        Phase phase = Phase.LOGIN;
        boolean joined = false;
        int recorderId = -1, spawnTick = -1, terrainTick = -1;
        int previous = 0;
        while (remaining > 0) {
            int time = in.readInt();
            int size = in.readInt();
            remaining -= 8;
            if (time < previous || size < 0 || size > remaining || size > 64 * 1024 * 1024) {
                throw new IOException("Invalid ReplayMod packet header at " + time + " ms");
            }
            previous = time;
            if (size == 0) continue;
            byte[] bytes = new byte[size];
            in.readFully(bytes);
            remaining -= size;
            var body = ByteBuffer.wrap(bytes);
            int id = Varints.read(body);
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
                continue;
            }
            // ReplayMod rewrites pack URLs to its own archive-local scheme.
            var phaseIds = phase == Phase.PLAY ? ids : configIds;
            if (id == phaseIds.get(CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_PUSH)
                || id == phaseIds.get(CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_POP)) continue;
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
                    continue;
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
        chunks.flush();
        if (!joined) throw new IOException("ReplayMod recording contains no world login");
        if (segment.actionsOffset == 0) endSnapshot(out, segment);
        advance(out, segment, tickAt(Math.max(duration, previous)));
        meta.durationTicks = segment.ticks;
        meta.openingTick = Math.max(0, Math.max(spawnTick, terrainTick));
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
