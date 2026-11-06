package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class IrisTimerMixin {
    @Inject(method = "getFrameTimeCounter", at = @At("HEAD"), cancellable = true, require = 0)
    private void reprise$getFrameTimeCounter(CallbackInfoReturnable<Float> result) {
        var exporter = VideoExporter.running();
        if (exporter != null) {
            result.setReturnValue(exporter.shaderTimeSeconds());
        }
    }

    @Inject(method = "getLastFrameTime", at = @At("HEAD"), cancellable = true, require = 0)
    private void reprise$getLastFrameTime(CallbackInfoReturnable<Float> result) {
        var exporter = VideoExporter.running();
        if (exporter != null) {
            result.setReturnValue(exporter.frameDurationSeconds());
        }
    }
}
