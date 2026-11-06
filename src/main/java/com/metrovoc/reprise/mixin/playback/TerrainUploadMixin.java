package com.metrovoc.reprise.mixin.playback;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UberGpuBuffer.class)
public abstract class TerrainUploadMixin {
    @Unique private boolean reprise$closed;

    // The section dispatcher locks both close and addAllocation, but a worker that
    // started before a world switch can acquire the lock after disposal.
    @Inject(method = "close", at = @At("HEAD"))
    private void reprise$markClosed(CallbackInfo ci) {
        this.reprise$closed = true;
    }

    @Inject(method = "addAllocation", at = @At("HEAD"), cancellable = true)
    private void reprise$discardLateUpload(CallbackInfoReturnable<Boolean> cir) {
        // False asks the worker to retry the same upload.
        if (this.reprise$closed) cir.setReturnValue(true);
    }
}
