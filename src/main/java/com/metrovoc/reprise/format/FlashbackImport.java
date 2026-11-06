package com.metrovoc.reprise.format;

import com.google.common.io.CountingInputStream;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metrovoc.reprise.record.PacketCodecs;
import com.metrovoc.reprise.util.Varints;
import io.netty.buffer.Unpooled;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Converts same-protocol Flashback packet recordings to standalone Reprise archives. */
public final class FlashbackImport {
    private static final int MAGIC = 0xD780E884;
    private final Map<PacketType<?>, Integer> packetIds = new HashMap<>();
    private final ReplayMeta meta = new ReplayMeta();
    private int playerId = -1;
    private int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;

    private FlashbackImport() {
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(packetIds::put);
    }

    public static Path convert(Path input, Path destination) throws IOException {
        return new FlashbackImport().read(input, destination);
    }

    private Path read(Path path, Path destination) throws IOException {
        try (var archive = new ZipFile(path.toFile())) {
            var metadata = archive.getEntry("metadata.json");
            if (metadata == null) throw new IOException("Missing Flashback metadata.json");
            if (metadata.getSize() > 16 * 1024 * 1024) throw new IOException("Flashback metadata exceeds 16 MiB");
            JsonObject header;
            try (var reader = new InputStreamReader(archive.getInputStream(metadata), StandardCharsets.UTF_8)) {
                header = JsonParser.parseReader(reader).getAsJsonObject();
            }
            meta.protocol = header.get("protocol_version").getAsInt();
            meta.dataVersion = header.get("data_version").getAsInt();
            if (meta.protocol != SharedConstants.getProtocolVersion() || meta.dataVersion != SharedConstants.getCurrentVersion().dataVersion().version()) {
                throw new IOException("Choose a Flashback recording from this Minecraft version.");
            }
            meta.mcVersion = header.get("version_string").getAsString();
            meta.name = header.get("name").getAsString();
            if (meta.name.isBlank() || meta.name.equals("Unnamed")) meta.name = path.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
            meta.server = header.has("world_name") ? header.get("world_name").getAsString() : "";
            meta.recordedAt = Math.max(0, metadata.getTime());
            if (header.has("modVersions")) header.getAsJsonObject("modVersions").entrySet().forEach(e -> meta.mods.put(e.getKey(), e.getValue().getAsString()));
            readMarkers(header);
            return ReplayOutput.write(destination, meta, out -> {
                copyTerrain(archive, out);
                for (var entry : header.getAsJsonObject("chunks").entrySet()) {
                    var chunk = archive.getEntry(entry.getKey());
                    if (chunk == null) throw new IOException("Missing Flashback segment " + entry.getKey());
                    try (var stream = archive.getInputStream(chunk)) {
                        convertSegment(stream, chunk.getSize(), entry.getValue().getAsJsonObject(), out);
                    }
                }
                if (meta.segments.isEmpty() || meta.durationTicks != header.get("total_ticks").getAsInt()) {
                    throw new IOException("Incomplete Flashback recording");
                }
            });
        } catch (RuntimeException e) {
            throw new IOException("Invalid Flashback recording: " + e.getMessage(), e);
        }
    }

    private void readMarkers(JsonObject header) {
        if (!header.has("markers")) return;
        for (var entry : header.getAsJsonObject("markers").entrySet()) {
            var m = entry.getValue().getAsJsonObject();
            var marker = new ReplayMeta.Marker();
            marker.tick = Integer.parseInt(entry.getKey());
            if (m.has("colour")) marker.color = m.get("colour").getAsInt();
            if (m.has("description") && !m.get("description").isJsonNull()) marker.name = m.get("description").getAsString();
            if (m.has("position") && !m.get("position").isJsonNull()) {
                var location = m.getAsJsonObject("position");
                var coords = location.getAsJsonArray("position");
                marker.x = coords.get(0).getAsDouble();
                marker.y = coords.get(1).getAsDouble();
                marker.z = coords.get(2).getAsDouble();
                marker.dimension = location.get("dimension").getAsString();
            }
            meta.markers.add(marker);
        }
    }

    private void copyTerrain(ZipFile archive, ZipOutputStream zip) throws IOException {
        var out = new DataOutputStream(new BufferedOutputStream(zip));
        for (int part = 0; ; part++) {
            var entry = archive.getEntry("level_chunk_caches/" + part);
            if (entry == null && part == 0) entry = archive.getEntry("level_chunk_cache");
            if (entry == null) break;
            if (meta.chunkCount != (long) part * 10000) throw new IOException("Incomplete Flashback terrain cache");
            try (var in = new DataInputStream(new BufferedInputStream(archive.getInputStream(entry)))) {
                long remaining = entry.getSize();
                while (remaining > 0) {
                    int size = in.readInt();
                    if (size <= 0 || size > remaining - 4) throw new EOFException("Incomplete terrain packet");
                    if (meta.chunkCount % Actions.CHUNKS_PER_FILE == 0) {
                        out.flush();
                        zip.putNextEntry(new ZipEntry(Actions.chunkFile(meta.chunkCount / Actions.CHUNKS_PER_FILE)));
                    }
                    Varints.write(out, size);
                    if (com.google.common.io.ByteStreams.limit(in, size).transferTo(out) != size) throw new EOFException();
                    remaining -= 4L + size;
                    meta.chunkCount++;
                }
            }
        }
        out.flush();
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 127) << shift;
            if (b < 128) return value;
        }
        throw new IOException("Oversized Flashback VarInt");
    }

    private void convertSegment(InputStream stream, long length, JsonObject info, ZipOutputStream zip) throws IOException {
        var progress = new CountingInputStream(new BufferedInputStream(stream));
        var input = new DataInputStream(progress);
        if (input.readInt() != MAGIC) throw new IOException("Bad Flashback segment header");
        int count = readVarInt(input);
        if (count < 0 || count > 65536) throw new IOException("Invalid action table");
        Kind[] actions = new Kind[count];
        for (int i = 0; i < count; i++) {
            int bytes = readVarInt(input);
            if (bytes < 1 || bytes > 32767) throw new IOException("Invalid action name");
            byte[] name = new byte[bytes];
            input.readFully(name);
            actions[i] = Kind.from(new String(name, StandardCharsets.UTF_8));
        }
        int snapshotSize = input.readInt();
        long snapshotEnd = progress.getCount() + snapshotSize;
        if (snapshotSize < 0 || snapshotEnd > length) throw new IOException("Invalid snapshot length");
        String file = Actions.segmentFile(meta.segments.size());
        var segment = new ReplayMeta.Segment(file, meta.durationTicks, meta.segments.isEmpty()
            || info.has("forcePlaySnapshot") && info.get("forcePlaySnapshot").getAsBoolean());
        zip.putNextEntry(new ZipEntry(file));
        var out = new DataOutputStream(new BufferedOutputStream(zip));
        out.writeInt(Actions.MAGIC);
        out.writeInt(java.util.Arrays.asList(actions).contains(Kind.POV) ? Actions.POV_SAMPLES_VERSION : Actions.VERSION);
        playerId = -1;
        while (progress.getCount() < snapshotEnd) translate(input, progress, snapshotEnd, actions, out, true);
        if (playerId == -1) throw new IOException("Snapshot has no login packet");
        out.writeByte(Actions.SNAPSHOT_END);
        segment.actionsOffset = out.size();
        while (progress.getCount() < length) {
            if (translate(input, progress, length, actions, out, false)) segment.ticks++;
        }
        out.flush();
        if (segment.ticks != info.get("duration").getAsInt()) throw new IOException("Segment duration mismatch");
        meta.durationTicks = Math.addExact(meta.durationTicks, segment.ticks);
        meta.segments.add(segment);
    }

    private enum Kind {
        TICK, CONFIG, GAME, CHUNK, SPAWN, MOVE, POV, OPTIONAL;

        static Kind from(String name) throws IOException {
            return switch (name) {
                case "flashback:action/next_tick" -> TICK;
                case "flashback:action/configuration_packet" -> CONFIG;
                case "flashback:action/game_packet" -> GAME;
                case "flashback:action/level_chunk_cached" -> CHUNK;
                case "flashback:action/create_local_player" -> SPAWN;
                case "flashback:action/accurate_player_position_optional" -> POV;
                case "flashback:action/move_entities" -> MOVE;
                default -> {
                    if (!name.endsWith("optional")) throw new IOException("Unsupported Flashback action: " + name);
                    yield OPTIONAL;
                }
            };
        }
    }

    private boolean translate(DataInputStream input, CountingInputStream progress, long end,
                              Kind[] actions, DataOutputStream out, boolean snapshot) throws IOException {
        int action = readVarInt(input);
        int size = input.readInt();
        if (action < 0 || action >= actions.length || size < 0 || size > end - progress.getCount()) {
            throw new IOException("Invalid Flashback action header");
        }
        if (actions[action] == Kind.OPTIONAL) {
            input.skipNBytes(size);
            return false;
        }
        if (size > 64 * 1024 * 1024) throw new IOException("Flashback action exceeds 64 MiB");
        byte[] payload = new byte[size];
        input.readFully(payload);
        var data = ByteBuffer.wrap(payload);
        switch (actions[action]) {
            case TICK -> {
                if (snapshot || size != 0) throw new IOException("Invalid tick action");
                out.writeByte(Actions.TICK);
                return true;
            }
            case CONFIG -> {
                if (!snapshot) throw new IOException("Mid-segment configuration is unsupported.");
                write(out, Actions.CONFIG_PACKET, data);
            }
            case POV -> {
                new PovSamples(data.duplicate()); // Check the wire count before publishing the recording.
                out.writeByte(Actions.POV_SAMPLES);
                Varints.write(out, size);
                out.write(payload);
            }
            case GAME -> game(data, out);
            case CHUNK -> {
                int id = Varints.read(data);
                if (id < 0 || id >= meta.chunkCount) throw new IOException("Missing terrain packet " + id);
                out.writeByte(Actions.CHUNK_REF);
                Varints.write(out, id);
            }
            case SPAWN, MOVE -> {
                var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                try {
                    if (actions[action] == Kind.SPAWN) spawn(buf, out);
                    else move(buf, out);
                    if (buf.isReadable()) throw new IOException("Unexpected trailing movement data");
                } finally {
                    buf.release();
                }
            }
        }
        return false;
    }

    private void game(ByteBuffer data, DataOutputStream out) throws IOException {
        var body = data.duplicate();
        int id = Varints.read(body);
        if (id == packetIds.get(GamePacketTypes.CLIENTBOUND_LOGIN)) {
            playerId = body.getInt();
            centerX = Integer.MIN_VALUE;
        } else if (id == packetIds.get(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT)) {
            if (Varints.read(body) == 0 && Varints.read(body) == 0) {
                int slot = body.getShort();
                if (slot >= 0 && slot < 9) {
                    int packetId = packetIds.get(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY);
                    writeGamePrefix(out, packetId, slot, body.remaining());
                    out.write(body.array(), body.arrayOffset() + body.position(), body.remaining());
                }
            }
            return;
        }
        write(out, Actions.GAME_PACKET, data);
    }

    private final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, Packet<? super ClientGamePacketListener>> codec =
        PacketCodecs.game(RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY), ConnectionType.OTHER);

    private void packet(DataOutputStream out, Packet<? super ClientGamePacketListener> packet) throws IOException {
        write(out, Actions.GAME_PACKET, ByteBuffer.wrap(PacketCodecs.encode(codec, packet)));
    }

    private static void write(DataOutputStream out, int action, ByteBuffer data) throws IOException {
        out.writeByte(action);
        Varints.write(out, data.remaining());
        out.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
    }

    /** Emit a game packet whose first field is a VarInt, followed by the caller's remaining bytes. */
    private static void writeGamePrefix(DataOutputStream out, int packetId, int value, int remaining) throws IOException {
        out.writeByte(Actions.GAME_PACKET);
        Varints.write(out, VarInt.getByteSize(packetId) + VarInt.getByteSize(value) + remaining);
        Varints.write(out, packetId);
        Varints.write(out, value);
    }

    private void center(DataOutputStream out, double x, double z) throws IOException {
        int cx = Mth.floor(x) >> 4, cz = Mth.floor(z) >> 4;
        if (cx != centerX || cz != centerZ) {
            packet(out, new ClientboundSetChunkCacheCenterPacket(cx, cz));
            centerX = cx;
            centerZ = cz;
        }
    }

    private void spawn(FriendlyByteBuf buf, DataOutputStream out) throws IOException {
        if (playerId == -1) throw new IOException("Player spawn before login");
        var uuid = buf.readUUID();
        Vec3 pos = Vec3.STREAM_CODEC.decode(buf);
        float pitch = buf.readFloat(), yaw = buf.readFloat(), head = buf.readFloat();
        Vec3 velocity = Vec3.STREAM_CODEC.decode(buf);
        var profile = ByteBufCodecs.GAME_PROFILE.decode(buf);
        var mode = GameType.byId(buf.readVarInt());
        meta.recorderName = profile.name();
        meta.recorderUuid = uuid.toString();
        center(out, pos.x, pos.z);
        var info = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), List.of());
        info.entries = List.of(new ClientboundPlayerInfoUpdatePacket.Entry(uuid, profile, false, 0, mode, null, true, 0, null));
        packet(out, info);
        packet(out, new ClientboundAddEntityPacket(playerId, uuid, pos.x, pos.y, pos.z, pitch, yaw, EntityType.PLAYER, 0, velocity, head));
    }

    private void move(FriendlyByteBuf buf, DataOutputStream out) throws IOException {
        if (buf.readVarInt() != 1) throw new IOException("Expected a single client dimension in movement data");
        buf.readIdentifier();
        int count = buf.readVarInt();
        if (count < 0) throw new IOException("Invalid movement count");
        for (int i = 0; i < count; i++) {
            int id = buf.readVarInt();
            Vec3 pos = Vec3.STREAM_CODEC.decode(buf);
            float yaw = buf.readFloat(), pitch = buf.readFloat(), head = buf.readFloat();
            boolean onGround = buf.readBoolean();
            if (id == playerId) center(out, pos.x, pos.z);
            packet(out, new ClientboundEntityPositionSyncPacket(id, new PositionMoveRotation(pos, Vec3.ZERO, yaw, pitch), onGround));
            int headId = packetIds.get(GamePacketTypes.CLIENTBOUND_ROTATE_HEAD);
            writeGamePrefix(out, headId, id, 1);
            out.writeByte(Mth.packDegrees(head));
        }
    }
}
