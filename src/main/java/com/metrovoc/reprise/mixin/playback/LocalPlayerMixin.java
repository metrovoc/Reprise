package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    /** The replay camera moves every frame in real time (see CameraController), not with game ticks. */
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void reprise$noTickMovement(CallbackInfo ci) {
        if (ReplaySession.isActive()) {
            ci.cancel();
        }
    }
}
