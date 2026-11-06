package com.metrovoc.reprise.mixin.record;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metrovoc.reprise.record.Recorder;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public abstract class BundleMixin {
    @WrapOperation(method = "handleBundlePacket", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void reprise$captureMember(Packet<?> packet, PacketListener listener, Operation<Void> handle) {
        Recorder recorder = Recorder.active();
        if (recorder != null) {
            recorder.onPacket(packet, listener);
        }
        handle.call(packet, listener);
    }
}
