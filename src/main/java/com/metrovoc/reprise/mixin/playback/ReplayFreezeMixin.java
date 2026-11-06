package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A paused replay freezes everything but the camera. Vanilla's tick freeze keeps players, and whatever they ride,
 * ticking, so recorded players would keep swinging, walking in place and turning their heads.
 */
@Mixin(TickRateManager.class)
public abstract class ReplayFreezeMixin {
    @Shadow public abstract boolean runsNormally();

    @ModifyReturnValue(method = "isEntityFrozen", at = @At("RETURN"))
    private boolean reprise$freezePlayers(boolean frozen, Entity entity) {
        if (frozen || ReplaySession.active() == null || (Object) this instanceof ServerTickRateManager) return frozen;
        return !runsNormally() && entity != Minecraft.getInstance().player;
    }
}
