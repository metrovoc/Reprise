package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.metrovoc.reprise.editor.EnvironmentSettings.Weather;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class ReplayWeatherMixin {
    @ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
    private float reprise$rain(float recorded) {
        var session = ReplaySession.active();
        if (session == null || !((Level) (Object) this).isClientSide()) {
            return recorded;
        }
        return switch (session.project().environment.weather) {
            case RECORDED -> recorded;
            case CLEAR -> 0;
            default -> 1;
        };
    }

    @ModifyReturnValue(method = "getThunderLevel", at = @At("RETURN"))
    private float reprise$thunder(float recorded) {
        var session = ReplaySession.active();
        if (session == null || !((Level) (Object) this).isClientSide() || session.project().environment.weather == Weather.RECORDED) {
            return recorded;
        }
        return session.project().environment.weather == Weather.THUNDER ? 1 : 0;
    }
}
