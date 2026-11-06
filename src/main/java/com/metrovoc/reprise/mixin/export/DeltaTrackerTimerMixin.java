package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** During export, game time follows the video's frame clock instead of the wall clock. */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
    @Shadow private float deltaTicks;
    @Shadow private float deltaTickResidual;
    @Shadow private float pausedDeltaTickResidual;
    @Shadow private long lastMs;

    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
    private void reprise$worldFraction(boolean ignoreFrozenGame, CallbackInfoReturnable<Float> cir) {
        var session = com.metrovoc.reprise.playback.ReplaySession.active();
        if (!ignoreFrozenGame && session != null && session.isPlaying() && !session.project().freezes.isEmpty()) {
            cir.setReturnValue(session.worldPartial(this.deltaTickResidual));
        }
    }

    @Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
    private void reprise$exportClock(long currentMs, CallbackInfoReturnable<Integer> cir) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null) {
            float[] partial = new float[1];
            int ticks = exporter.advance(partial);
            this.lastMs = currentMs;
            this.deltaTicks = ticks;
            this.deltaTickResidual = partial[0];
            this.pausedDeltaTickResidual = partial[0];
            cir.setReturnValue(ticks);
        }
    }
}
