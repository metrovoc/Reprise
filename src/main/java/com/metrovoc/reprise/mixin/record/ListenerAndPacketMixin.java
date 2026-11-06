package com.metrovoc.reprise.mixin.record;

import com.metrovoc.reprise.record.Recorder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packets are recorded where the client applies them on the game thread, so the order is the order they took effect. */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class ListenerAndPacketMixin {
    @Shadow @Final private PacketListener listener;
    @Shadow @Final private Packet<?> packet;

    @Inject(method = "handle", at = @At("HEAD"))
    private void reprise$record(CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && this.listener.shouldHandleMessage(this.packet)) {
            recorder.onPacket(this.packet, this.listener);
        }
    }
}
