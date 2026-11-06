package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.metrovoc.reprise.editor.EnvironmentSettings;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientClockManager.class)
public abstract class ReplayClockMixin {
    @ModifyReturnValue(method = "getPartialTick", at = @At("RETURN"))
    private float reprise$clockFraction(float recorded, Holder<WorldClock> clock) {
        var session = ReplaySession.active();
        if (session != null && clock.is(WorldClocks.OVERWORLD)) {
            double override = session.project().environment.timeAt(session.recordedTick());
            if (override >= 0) {
                return (float) (override - Math.floor(override));
            }
        }
        return recorded;
    }

    @ModifyReturnValue(method = "getTotalTicks", at = @At("RETURN"))
    private long reprise$daylight(long recorded, Holder<WorldClock> clock) {
        ReplaySession session = ReplaySession.active();
        if (session != null && clock.is(WorldClocks.OVERWORLD)) {
            double override = session.project().environment.timeAt(session.recordedTick());
            if (override >= 0) {
                return recorded - Math.floorMod(recorded, EnvironmentSettings.DAY) + (long) override;
            }
        }
        return recorded;
    }
}
