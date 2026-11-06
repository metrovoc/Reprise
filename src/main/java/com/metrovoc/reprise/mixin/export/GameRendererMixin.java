package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** Frames kept for the video carry no GUI at all, not even the export's progress screen. */
    @Inject(method = "extractGui", at = @At("HEAD"), cancellable = true)
    private void reprise$noGuiInExport(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo ci) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null && exporter.capturing()) {
            ci.cancel();
        }
    }
}
