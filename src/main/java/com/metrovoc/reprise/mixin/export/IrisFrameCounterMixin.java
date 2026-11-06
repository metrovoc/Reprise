package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", remap = false)
public abstract class IrisFrameCounterMixin {
    @Inject(method = "getAsInt", at = @At("HEAD"), cancellable = true, require = 0)
    private void reprise$getAsInt(CallbackInfoReturnable<Integer> result) {
        var exporter = VideoExporter.running();
        if (exporter != null) {
            result.setReturnValue(exporter.frame() % 720720);
        }
    }
}
