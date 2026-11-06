package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** World replacement runs synchronously inside one replay tick. Keep the active editor or export screen. */
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(
        method = {"clearClientLevel", "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private boolean reprise$showConnectionScreen(Minecraft client, net.minecraft.client.gui.screens.Screen screen) {
        var session = ReplaySession.active();
        return session == null || !(session.isSeeking() || session.isSwitchingWorld());
    }

    /** The camera moves before the frame's state is extracted, so what is drawn is where it is now. */
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void reprise$moveCamera(boolean advanceGameTime, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.metrovoc.reprise.playback.PlaybackControls.frame(((Minecraft) (Object) this).getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }

    /** Replay speed is the client's tick rate; vanilla never lets the client tick faster than 20 per second. */
    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void reprise$replaySpeed(float defaultMillis, CallbackInfoReturnable<Float> cir) {
        ReplaySession session = ReplaySession.active();
        if (session != null) {
            cir.setReturnValue(session.millisPerTick());
        }
    }
}
