package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.Panorama;
import com.metrovoc.reprise.export.VideoExporter;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class PanoramaCameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);

    @Shadow protected abstract void move(float forwards, float up, float right);

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void reprise$face(float partial, CallbackInfo ci) {
        var job = VideoExporter.running();
        var camera = (Camera) (Object) this;
        var session = ReplaySession.active();
        if (session != null && session.project().shakeEnabled && !session.project().shakeKeys.isEmpty()) {
            double tick = job == null ? session.visualTick(partial) : job.shotTick();
            var offset = session.project().shake(session.duration()).sample(tick);
            if (offset.x != 0 || offset.y != 0) {
                var rotation = new Quaternionf(camera.rotation()).rotateYXZ(
                    (float) Math.toRadians(-offset.x), (float) Math.toRadians(-offset.y), 0);
                var angles = Panorama.cameraAngles(rotation, (float) Math.toRadians(180 - camera.yRot()));
                setRotation(camera.yRot() + Mth.wrapDegrees(angles.x - camera.yRot()), angles.y,
                    camera.getRoll() + Mth.wrapDegrees(angles.z - camera.getRoll()));
            }
        }
        if (job == null) return;
        if (job.eyeOffset() != 0) {
            job.setStereoCenter(camera.position());
            move(0, 0, job.eyeOffset());
            return;
        }
        if (job.panoramaFace() == null) return;
        camera.enablePanoramicMode();
        var angles = Panorama.angles(job.panoramaFace(), camera.yRot(), camera.xRot(), camera.getRoll());
        setRotation(angles.x, angles.y, angles.z);
    }
}
