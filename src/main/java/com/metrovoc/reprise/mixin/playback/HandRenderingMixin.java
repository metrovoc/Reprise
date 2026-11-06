package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.PovHands;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the spectated player's hands in first person. */
@Mixin(GameRenderer.class)
public abstract class HandRenderingMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void reprise$beginPov(CameraRenderState cameraState, float partialTick, Matrix4fc modelView, CallbackInfo ci) {
        var session = ReplaySession.active();
        var camera = Minecraft.getInstance().getCameraEntity();
        var override = session == null || camera == null ? null : session.project().entityOverrides.get(camera.getUUID());
        if (override != null && override.hidden()) {
            ci.cancel();
            return;
        }
        PovHands.begin();
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void reprise$endPov(CameraRenderState cameraState, float partialTick, Matrix4fc modelView, CallbackInfo ci) {
        PovHands.end();
    }
}
