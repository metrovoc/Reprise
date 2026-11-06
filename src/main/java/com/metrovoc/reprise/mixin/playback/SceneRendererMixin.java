package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.editor.EnvironmentSettings.Layer;
import com.metrovoc.reprise.playback.ReplaySession;
import com.metrovoc.reprise.export.VideoExporter;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import com.mojang.blaze3d.textures.GpuSampler;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class SceneRendererMixin {
    @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vector4f reprise$background(Vector4f original) {
        var session = ReplaySession.active();
        int rgb = VideoExporter.matteColor();
        if (rgb < 0) {
            if (session == null || session.project().environment.skyColor == null) {
                return original;
            }
            rgb = session.project().environment.skyColor;
        }
        return new Vector4f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, original.w);
    }

    @Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Matrix4fc;)V", at = @At("HEAD"), cancellable = true)
    private void reprise$solidSky(CallbackInfo ci) {
        var session = ReplaySession.active();
        if (VideoExporter.matteColor() >= 0 || session != null && session.project().environment.skyColor != null) {
            ci.cancel();
        }
    }

    @Inject(method = "addCloudsPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/CloudStatus;Lnet/minecraft/world/phys/Vec3;JFIFILorg/joml/Matrix4fc;)V", at = @At("HEAD"), cancellable = true)
    private void reprise$clouds(CallbackInfo ci) {
        if (ReplaySession.hides(Layer.CLOUDS)) {
            ci.cancel();
        }
    }

    @WrapWithCondition(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"), require = 2)
    private boolean reprise$drawTerrain(ChunkSectionsToRender sections, ChunkSectionLayerGroup group, GpuSampler sampler) {
        return !ReplaySession.hides(Layer.TERRAIN);
    }

    @Inject(method = {"submitBlockEntities", "submitBlockDestroyAnimation", "renderBlockOutline"}, at = @At("HEAD"), cancellable = true)
    private void reprise$terrainDetails(CallbackInfo ci) {
        if (ReplaySession.hides(Layer.TERRAIN)) {
            ci.cancel();
        }
    }
}
