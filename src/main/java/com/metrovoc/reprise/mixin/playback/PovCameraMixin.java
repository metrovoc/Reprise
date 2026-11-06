package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.format.ViewMotion;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class PovCameraMixin implements ViewMotion.Source {
    @Shadow private float oldFovModifier, fovModifier;
    @Shadow private float eyeHeightOld, eyeHeight;
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Override
    public ViewMotion reprise$captureView(net.minecraft.client.entity.ClientAvatarState a) {
        return new ViewMotion(a.getBackwardsInterpolatedWalkDistance(0), a.getBackwardsInterpolatedWalkDistance(1),
            a.getInterpolatedBob(0), a.getInterpolatedBob(1), eyeHeightOld, eyeHeight, oldFovModifier, fovModifier);
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "calculateFov", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    private float reprise$recordedFov(float vanilla, float partial) {
        var session = ReplaySession.active();
        var view = session == null ? null : session.recordedView(((Camera)(Object)this).entity());
        return view != null && Minecraft.getInstance().options.getCameraType().isFirstPerson()
            ? view.fov(session.povFraction(partial)) : vanilla;
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void reprise$recordedWalk(net.minecraft.client.renderer.state.level.CameraRenderState state, float partial, CallbackInfo ci) {
        var session = ReplaySession.active();
        var entity = ((Camera)(Object)this).entity();
        if (session != null && entity != null && state.entityRenderState.isPlayer) session.applyWalk(state, entity, partial);
    }

    /** Apply recorded angles before vanilla places the third-person camera and export rotates cube faces. */
    @Inject(method = "alignWithEntity", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;detached:Z", opcode = Opcodes.PUTFIELD))
    private void reprise$samplePov(float partial, CallbackInfo ci) {
        var recorder = com.metrovoc.reprise.record.Recorder.active();
        if (recorder != null) recorder.sampleView(partial);
        var session = ReplaySession.active();
        var camera = (Camera)(Object)this;
        var entity = camera.entity();
        if (session == null || entity == null || entity == Minecraft.getInstance().player) return;
        var pose = session.povAt(entity.getId(), partial);
        if (pose == null) return;
        setRotation(pose.yaw(), pose.pitch(), camera.getRoll());
        if (!entity.isPassenger() && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            var view = session.recordedView(entity);
            float eye = view == null ? Mth.lerp(partial, eyeHeightOld, eyeHeight) : view.eye(session.povFraction(partial));
            setPosition(pose.x(), pose.y() + eye, pose.z());
        }
    }
}
