package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.AudioCapture;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    /** The loopback device is not in the system's device list; vanilla would switch back to the speakers. */
    @Inject(method = "shouldChangeDevice", at = @At("HEAD"), cancellable = true)
    private void reprise$keepLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (AudioCapture.loopback()) {
            cir.setReturnValue(false);
        }
    }
}
