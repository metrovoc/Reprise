package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @WrapOperation(method = "replaceWithPacketData", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/LevelChunk;initializeLightSources()V"))
    private void reprise$initializeSkyLight(LevelChunk chunk, Operation<Void> original, FriendlyByteBuf input) {
        ReplaySession session = ReplaySession.active();
        if (session != null && chunk.getLevel().isClientSide()) {
            session.initializeSkyLight(chunk, input, () -> original.call(chunk));
        } else {
            original.call(chunk);
        }
    }
}
