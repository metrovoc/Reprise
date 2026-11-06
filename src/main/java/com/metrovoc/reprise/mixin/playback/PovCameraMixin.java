package com.metrovoc.reprise.mixin.playback;

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
abstract class PovCameraMixin {
    @Shadow private float eyeHeightOld, eyeHeight;
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);
    @Shadow protected abstract void setPosition(double x, double y, double z);

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
            setPosition(pose.x(), pose.y() + Mth.lerp(partial, eyeHeightOld, eyeHeight), pose.z());
        }
    }
}
