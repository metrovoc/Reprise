package com.metrovoc.reprise.format;

import com.metrovoc.reprise.record.PacketCodecs;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import io.netty.buffer.ByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Via translates the chunk protocol but leaves persisted item data in block entity NBT. */
final class LegacyBlockEntities {
    private static final int DATA_VERSION_1_21 = 3953;
    private static final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> CODEC = PacketCodecs.game(
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), ConnectionType.OTHER);

    static byte[] upgrade(byte[] bytes) {
        var packet = PacketCodecs.decode(CODEC, bytes);
        if (packet instanceof ClientboundBlockEntityDataPacket entity) {
            if (!upgrade(entity.getType(), entity.getTag())) return bytes;
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            boolean[] changed = {false};
            chunk.getChunkData().getBlockEntitiesTagsConsumer(chunk.getX(), chunk.getZ()).accept((pos, type, tag) -> {
                if (tag != null && upgrade(type, tag)) changed[0] = true;
            });
            if (!changed[0]) return bytes;
        } else {
            throw new IllegalArgumentException("Expected a chunk or block entity update");
        }
        return PacketCodecs.encode(CODEC, packet);
    }

    private static boolean upgrade(BlockEntityType<?> type, CompoundTag tag) {
        boolean changed = false;
        if (type == BlockEntityType.DECORATED_POT && tag.get("item") instanceof CompoundTag item) {
            if (item.contains("components")) changed = update(tag, "item", References.ITEM_STACK, item);
        }
        if (tag.get("components") instanceof CompoundTag components) {
            changed |= update(tag, "components", References.DATA_COMPONENTS, components);
        }
        return changed;
    }

    private static boolean update(CompoundTag parent, String key, DSL.TypeReference type, CompoundTag data) {
        var fixed = DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, data.copy()),
            DATA_VERSION_1_21, SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
        if (fixed.equals(data)) return false;
        parent.put(key, fixed);
        return true;
    }

    private LegacyBlockEntities() {}
}
