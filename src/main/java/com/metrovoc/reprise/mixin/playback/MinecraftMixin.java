package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** Replay speed is the client's tick rate; vanilla never lets the client tick faster than 20 per second. */
    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void reprise$replaySpeed(float defaultMillis, CallbackInfoReturnable<Float> cir) {
        ReplaySession session = ReplaySession.active();
        if (session != null) {
            cir.setReturnValue(session.millisPerTick());
        }
    }
}
