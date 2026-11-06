package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The game renders at the export resolution, whatever the window's size. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void reprise$exportWidth(CallbackInfoReturnable<Integer> cir) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null) {
            cir.setReturnValue(exporter.width());
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void reprise$exportHeight(CallbackInfoReturnable<Integer> cir) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null) {
            cir.setReturnValue(exporter.height());
        }
    }
}
