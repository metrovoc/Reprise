package com.metrovoc.reprise.record;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** The vanilla clientbound codecs, used to write and read packets exactly as they travel over the network. */
public final class PacketCodecs {
    private static final ScopedValue<Boolean> RECORDING_CHUNK = ScopedValue.newInstance();

    public static boolean recordingChunk() {
        return RECORDING_CHUNK.isBound();
    }

    public static final StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> CONFIG = ConfigurationProtocols.CLIENTBOUND.codec();

    public static StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> game(RegistryAccess registries, ConnectionType type) {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries, type)).codec();
    }

    public static <T> byte[] encode(StreamCodec<ByteBuf, T> codec, T packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket) {
            return ScopedValue.where(RECORDING_CHUNK, true).call(() -> encodeBytes(codec, packet));
        }
        return encodeBytes(codec, packet);
    }

    private static <T> byte[] encodeBytes(StreamCodec<ByteBuf, T> codec, T packet) {
        ByteBuf buf = Unpooled.buffer(256);
        try {
            codec.encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    public static <T> T decode(StreamCodec<ByteBuf, T> codec, byte[] bytes) {
        return decode(codec, ByteBuffer.wrap(bytes));
    }

    public static <T> T decode(StreamCodec<ByteBuf, T> codec, ByteBuffer bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            T packet = codec.decode(buf);
            if (buf.isReadable()) {
                throw new IllegalStateException(buf.readableBytes() + " bytes left after decoding " + packet);
            }
            return packet;
        } finally {
            buf.release();
        }
    }

    private PacketCodecs() {}
}
