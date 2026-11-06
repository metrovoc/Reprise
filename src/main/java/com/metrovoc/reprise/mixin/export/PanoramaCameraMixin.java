package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.Panorama;
import com.metrovoc.reprise.export.VideoExporter;
import net.minecraft.client.Camera;
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
        if (job == null) return;
        var camera = (Camera) (Object) this;
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
