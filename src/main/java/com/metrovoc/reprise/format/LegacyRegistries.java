package com.metrovoc.reprise.format;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.core.RegistrySynchronization.PackedRegistryEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;

/** Restores known-pack entries in the source protocol before ViaVersion translates their contents. */
final class LegacyRegistries {
    // Protocol 767 wire IDs. Registry entries and the known-pack list use the same encoding as 775.
    private static final int LOGIN_SUCCESS = 2, CONFIG_PAYLOAD = 1, CONFIG_FINISH = 3, REGISTRY = 7, KNOWN_PACKS = 14;
    private static final int MAX_CONFIG_BYTES = 64 * 1024 * 1024;
    private final CompoundTag archived = new CompoundTag();
    private CompoundTag vanilla;
    private boolean core;

    // ReplayStudio allocates from frame lengths. Validate the entire stream before giving it the input.
    void scan(DataInputStream in, long remaining) throws IOException {
        int previous = 0;
        long configBytes = 0;
        boolean config = false, play = false;
        while (remaining > 0) {
            int time = in.readInt(), size = in.readInt();
            remaining -= 8;
            ReplayModImport.checkFrame(time, previous, size, remaining);
            if (!play && (configBytes += size + 8L) > MAX_CONFIG_BYTES) throw new IOException("Replay configuration exceeds 64 MiB");
            if (size > 0 && !play) {
                int id = in.readUnsignedByte();
                if (config && id == CONFIG_PAYLOAD) {
                    var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(in.readNBytes(size - 1)));
                    try {
                        if (buf.readUtf().equals("replaymod:enabled_packs_data")) readPackData(buf);
                    } finally {
                        buf.release();
                    }
                } else {
                    in.skipNBytes(size - 1);
                }
                if (config && id == CONFIG_FINISH) play = true;
                if (!config && id == LOGIN_SUCCESS) config = true;
            } else {
                in.skipNBytes(size);
            }
            remaining -= size;
            previous = time;
        }
        if (!play) throw new IOException("Recording contains no completed configuration");
    }

    private void readPackData(FriendlyByteBuf buf) throws IOException {
        for (int registries = buf.readVarInt(); registries > 0; registries--) {
            String name = buf.readUtf();
            var entries = archived.getCompoundOrEmpty(name);
            for (int count = buf.readVarInt(); count > 0; count--) {
                String key = buf.readUtf();
                var tag = buf.readNbt();
                if (tag == null) throw new IOException("Missing archived registry value for " + key);
                entries.put(key, tag);
            }
            archived.put(name, entries);
        }
    }

    InputStream expand(DataInputStream in) throws IOException {
        var prefix = new ByteArrayOutputStream();
        var out = new DataOutputStream(prefix);
        boolean config = false;
        while (true) {
            int time = in.readInt(), size = in.readInt();
            byte[] bytes = in.readNBytes(size);
            int id = size == 0 ? -1 : Byte.toUnsignedInt(bytes[0]);
            if (config && (id == REGISTRY || id == KNOWN_PACKS)) {
                var body = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes, 1, size - 1));
                try {
                    if (id == KNOWN_PACKS) {
                        core = ClientboundSelectKnownPacks.STREAM_CODEC.decode(body).knownPacks().stream()
                            .anyMatch(pack -> pack.isVanilla() && pack.id().equals("core")
                                && (pack.version().equals("1.21") || pack.version().equals("1.21.1")));
                    } else {
                        var packet = ClientboundRegistryDataPacket.STREAM_CODEC.decode(body);
                        if (packet.entries().stream().anyMatch(entry -> entry.data().isEmpty())) bytes = expandRegistry(packet);
                    }
                } finally {
                    body.release();
                }
            }
            if ((long) prefix.size() + bytes.length + 8 > MAX_CONFIG_BYTES) throw new IOException("Expanded configuration exceeds 64 MiB");
            out.writeInt(time);
            out.writeInt(bytes.length);
            out.write(bytes);
            if (config && id == CONFIG_FINISH) break;
            if (id == LOGIN_SUCCESS) config = true;
        }
        return new SequenceInputStream(new ByteArrayInputStream(prefix.toByteArray()), in);
    }

    private byte[] expandRegistry(ClientboundRegistryDataPacket packet) throws IOException {
        if (core && vanilla == null) {
            try (var data = LegacyRegistries.class.getResourceAsStream("registry-767.nbt.gz")) {
                if (data == null) throw new IOException("Missing bundled protocol 767 registry data");
                vanilla = NbtIo.readCompressed(data, NbtAccounter.defaultQuota());
            }
        }
        String registry = packet.registry().identifier().toString();
        var recorded = archived.getCompoundOrEmpty(registry);
        var defaults = core ? vanilla.getCompoundOrEmpty(registry) : new CompoundTag();
        var entries = new ArrayList<PackedRegistryEntry>(packet.entries().size());
        for (var entry : packet.entries()) {
            if (entry.data().isPresent()) {
                entries.add(entry);
                continue;
            }
            String key = entry.id().toString();
            var value = recorded.get(key);
            if (value == null) value = defaults.get(key);
            if (value == null) throw new IOException("Recording omits the known-pack value for " + registry + " / " + key);
            entries.add(new PackedRegistryEntry(entry.id(), Optional.of(value)));
        }
        var out = new FriendlyByteBuf(Unpooled.buffer(256, MAX_CONFIG_BYTES));
        try {
            out.writeVarInt(REGISTRY);
            ClientboundRegistryDataPacket.STREAM_CODEC.encode(out, new ClientboundRegistryDataPacket(packet.registry(), entries));
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }
}
