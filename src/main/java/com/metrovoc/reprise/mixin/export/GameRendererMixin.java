package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.VideoExporter;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = {"bobHurt", "bobView"}, at = @At("HEAD"), cancellable = true)
    private void reprise$steadyCubeFaces(CameraRenderState camera, PoseStack poses, CallbackInfo ci) {
        if (camera.isPanoramicMode && VideoExporter.running() != null) ci.cancel();
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;screenEffectScale:F"))
    private float reprise$panoramaDistortion(float original) {
        var job = VideoExporter.running();
        return job != null && job.panoramaFace() != null ? 0 : original;
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"), index = 0)
    private boolean reprise$panoramaOverlays(boolean firstPerson) {
        var job = VideoExporter.running();
        return firstPerson && (job == null || job.panoramaFace() == null);
    }

    @ModifyExpressionValue(method = "renderItemInHand", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;hideGui:Z"))
    private boolean reprise$handsWithoutHud(boolean hidden) {
        var job = VideoExporter.running();
        return hidden && (job == null || !job.handsOnly());
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void reprise$captureWorldDepth(DeltaTracker delta, CallbackInfo ci) {
        var exporter = VideoExporter.running();
        if (exporter != null) exporter.captureWorldDepth();
    }

    /** Extract only the requested in-game layers. Export controls and viewer toasts belong to the live window. */
    @Inject(method = "extractGui", at = @At("HEAD"), cancellable = true)
    private void reprise$noGuiInExport(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo ci) {
        VideoExporter exporter = VideoExporter.running();
        if (exporter != null && exporter.capturing()) {
            var renderer = (GameRenderer) (Object) this;
            var state = renderer.getGameRenderState().guiRenderState;
            state.reset();
            if (exporter.includesHud() && shouldRenderLevel) {
                var mc = Minecraft.getInstance();
                var graphics = new GuiGraphicsExtractor(mc, state, -1, -1);
                mc.gui.extractRenderState(graphics, deltaTracker);
            }
            ci.cancel();
        }
    }
}
