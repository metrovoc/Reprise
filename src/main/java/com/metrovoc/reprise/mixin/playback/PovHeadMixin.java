package com.metrovoc.reprise.mixin.playback;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
abstract class PovHeadMixin {
    @Shadow private static float solveBodyRot(LivingEntity entity, float yaw, float partial) { throw new AssertionError(); }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;xRot:F",
            opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
    private void reprise$sampleHead(LivingEntity entity, LivingEntityRenderState state, float partial, CallbackInfo ci) {
        var session = ReplaySession.active();
        if (session == null) return;
        var pose = session.povAt(entity.getId(), partial);
        if (pose != null) {
            state.bodyRot = solveBodyRot(entity, pose.yaw(), partial);
            state.yRot = Mth.wrapDegrees(pose.yaw() - state.bodyRot);
            state.xRot = pose.pitch();
        }
    }
}
