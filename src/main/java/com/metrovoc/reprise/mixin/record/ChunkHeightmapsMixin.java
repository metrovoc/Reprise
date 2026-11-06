package com.metrovoc.reprise.mixin.record;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.metrovoc.reprise.record.PacketCodecs;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientboundLevelChunkPacketData.class)
abstract class ChunkHeightmapsMixin {
    @ModifyExpressionValue(method = "write", at = @At(value = "FIELD",
        target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;heightmaps:Ljava/util/Map;"))
    private Map<Heightmap.Types, long[]> reprise$orderedHeightmaps(Map<Heightmap.Types, long[]> original) {
        if (!PacketCodecs.recordingChunk() || original instanceof EnumMap) {
            return original;
        }
        // A locally constructed snapshot uses a HashMap. A decoded network packet uses enum order.
        var ordered = new EnumMap<Heightmap.Types, long[]>(Heightmap.Types.class);
        ordered.putAll(original);
        return ordered;
    }
}
