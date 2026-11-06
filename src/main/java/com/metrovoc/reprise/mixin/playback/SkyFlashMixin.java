package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
public abstract class SkyFlashMixin {
    @ModifyReturnValue(method = "getSkyFlashTime", at = @At("RETURN"))
    private int reprise$skyFlash(int recorded) {
        var session = ReplaySession.active();
        return session != null && session.project().environment.weather.hidesLightning() ? 0 : recorded;
    }
}
