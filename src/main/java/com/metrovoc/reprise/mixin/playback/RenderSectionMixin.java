package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    /**
     * Chunks fade in over wall-clock time, which means nothing in a replay: every seek would fade the world in again,
     * and an export, which renders faster than real time, would keep half-transparent chunks for many frames.
     */
    @Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
    private void reprise$noFadeInReplay(long now, CallbackInfoReturnable<Float> cir) {
        if (ReplaySession.isActive()) {
            cir.setReturnValue(1.0F);
        }
    }
}
