package com.metrovoc.reprise.mixin.record;

import com.metrovoc.reprise.record.Recorder;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientNetworkRegistry.class)
public abstract class ModPayloadMixin {
    @Inject(method = "handleModdedPayload", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/network/handling/IPayloadHandler;handle(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V"))
    private static void reprise$capture(ClientCommonPacketListener listener, ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && listener instanceof ClientPacketListener play) {
            recorder.capturePayload(play, packet);
        }
    }
}
