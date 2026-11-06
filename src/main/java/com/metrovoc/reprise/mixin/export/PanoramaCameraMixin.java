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

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void reprise$face(float partial, CallbackInfo ci) {
        var job = VideoExporter.running();
        if (job == null || job.panoramaFace() == null) return;
        var camera = (Camera) (Object) this;
        camera.enablePanoramicMode();
        var angles = Panorama.angles(job.panoramaFace(), camera.yRot(), camera.xRot(), camera.getRoll());
        setRotation(angles.x, angles.y, angles.z);
    }
}
