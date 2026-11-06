package com.metrovoc.reprise.mixin.record;

import com.metrovoc.reprise.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What the client predicts for its own player never comes back from the server: sounds, level events, block changes
 * and block breaking. All sounds are recorded here, so server sound packets are left out of the stream.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void reprise$recordSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                                     float volume, float pitch, long seed, CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && except == Minecraft.getInstance().player) {
            recorder.recordLocal(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed));
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void reprise$recordEntitySound(@Nullable Entity except, Entity sourceEntity, Holder<SoundEvent> sound, SoundSource source,
                                           float volume, float pitch, long seed, CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && except == Minecraft.getInstance().player) {
            recorder.recordLocal(new ClientboundSoundEntityPacket(sound, source, sourceEntity, volume, pitch, seed));
        }
    }

    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void reprise$recordLevelEvent(@Nullable Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && source != null && source == Minecraft.getInstance().player) {
            recorder.recordLocal(new ClientboundLevelEventPacket(type, pos, data, false));
        }
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void reprise$recordBreaking(int id, BlockPos pos, int progress, CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && Minecraft.getInstance().player != null && id == Minecraft.getInstance().player.getId()) {
            recorder.recordLocal(new ClientboundBlockDestructionPacket(id, pos, progress));
        }
    }

    @Inject(method = "setBlock", at = @At("RETURN"))
    private void reprise$recordPrediction(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
        Recorder recorder = Recorder.active();
        ClientLevel self = (ClientLevel) (Object) this;
        if (recorder != null && cir.getReturnValueZ() && self.getBlockStatePredictionHandler().isPredicting()) {
            recorder.recordLocal(new ClientboundBlockUpdatePacket(pos.immutable(), state));
        }
    }
}
