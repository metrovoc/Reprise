package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
public abstract class NightVisionMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void reprise$nightVision(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        var session = ReplaySession.active();
        if (session != null && session.project().environment.nightVision) {
            state.nightVisionEffectIntensity = 1;
        }
    }
}
