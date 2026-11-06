package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The game renders at the export resolution, whatever the window's size. */
@Mixin(Window.class)
public abstract class WindowMixin {
    // Pick HUD scale at delivery resolution, then scale the same layout for the supersampled target.
    @ModifyExpressionValue(method = "calculateScale", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferWidth:I"))
    private int reprise$layoutWidth(int original) {
        var job = VideoExporter.running();
        return job != null && job.includesHud() ? job.hudWidth() : original;
    }

    @ModifyExpressionValue(method = "calculateScale", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferHeight:I"))
    private int reprise$layoutHeight(int original) {
        var job = VideoExporter.running();
        return job != null && job.includesHud() ? job.outputHeight() : original;
    }

    @ModifyReturnValue(method = "calculateScale", at = @At("RETURN"))
    private int reprise$scaleHud(int original) {
        var job = VideoExporter.running();
        return job != null && job.includesHud() ? original * (job.renderWidth() / job.hudWidth()) : original;
    }

    @ModifyExpressionValue(method = "setGuiScale", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferWidth:I"))
    private int reprise$scaledWidth(int original) {
        var job = VideoExporter.running();
        return job != null && job.includesHud() ? job.renderWidth() : original;
    }

    @ModifyExpressionValue(method = "setGuiScale", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferHeight:I"))
    private int reprise$scaledHeight(int original) {
        var job = VideoExporter.running();
        return job != null && job.includesHud() ? job.renderHeight() : original;
    }

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void reprise$exportWidth(CallbackInfoReturnable<Integer> cir) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null) {
            cir.setReturnValue(exporter.renderWidth());
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void reprise$exportHeight(CallbackInfoReturnable<Integer> cir) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null) {
            cir.setReturnValue(exporter.renderHeight());
        }
    }
}
