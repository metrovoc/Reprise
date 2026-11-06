package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.editor.EnvironmentSettings.Layer;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleLayerMixin {
    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void reprise$particles(CallbackInfo ci) {
        if (ReplaySession.hides(Layer.PARTICLES)) {
            ci.cancel();
        }
    }
}
