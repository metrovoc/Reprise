package com.metrovoc.reprise.mixin.export;

import com.metrovoc.reprise.export.AudioCapture;
import com.mojang.blaze3d.audio.Library;
import java.nio.IntBuffer;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Opens an OpenAL loopback device while an export records sound. */
@Mixin(Library.class)
public abstract class LibraryMixin {
    @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
    private static void reprise$loopbackDevice(String preferred, String systemDefault, CallbackInfoReturnable<Long> cir) {
        if (AudioCapture.loopback()) {
            long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((java.nio.ByteBuffer) null);
            if (device != 0) {
                cir.setReturnValue(device);
            }
        }
    }

    @Inject(method = "createAttributes", at = @At("HEAD"), cancellable = true)
    private void reprise$loopbackFormat(MemoryStack stack, boolean enableHrtf, CallbackInfoReturnable<IntBuffer> cir) {
        if (AudioCapture.loopback()) {
            IntBuffer attr = stack.callocInt(7);
            attr.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_FLOAT_SOFT)
                .put(ALC10.ALC_FREQUENCY).put(AudioCapture.RATE)
                .put(0);
            cir.setReturnValue(attr.flip());
        }
    }
}
