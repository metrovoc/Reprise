package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.AudioCapture;
import com.metrovoc.reprise.export.VideoExporter;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @ModifyExpressionValue(method = "updateSource", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 reprise$stereoListener(Vec3 eye) {
        var job = VideoExporter.running();
        return job != null && job.stereoCenter() != null ? job.stereoCenter() : eye;
    }

    /** The loopback device is not in the system's device list; vanilla would switch back to the speakers. */
    @Inject(method = "shouldChangeDevice", at = @At("HEAD"), cancellable = true)
    private void reprise$keepLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (AudioCapture.loopback()) {
            cir.setReturnValue(false);
        }
    }
}
