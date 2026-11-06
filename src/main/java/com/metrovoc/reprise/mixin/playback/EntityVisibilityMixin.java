package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.List;
import com.metrovoc.reprise.editor.EnvironmentSettings.Layer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class EntityVisibilityMixin {
    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private boolean reprise$includeEntity(List<?> states, Object state, Operation<Boolean> add, @Local Entity entity) {
        ReplaySession session = ReplaySession.active();
        if (session != null && entity instanceof net.minecraft.world.entity.LightningBolt && session.project().environment.weather.hidesLightning()) {
            return false;
        }
        if (session != null) {
            var hidden = session.project().environment.hidden;
            if (hidden.contains(entity instanceof Player ? Layer.PLAYERS : Layer.ENTITIES)) {
                return false;
            }
            if (hidden.contains(Layer.TERRAIN)) {
                var renderState = (EntityRenderState) state;
                renderState.shadowRadius = 0;
                renderState.shadowPieces.clear();
            }
        }
        var override = session == null ? null : session.project().entityOverrides.get(entity.getUUID());
        if (override != null) {
            if (override.hidden()) {
                return false;
            }
            if (override.outlineColor() != null) {
                ((EntityRenderState) state).outlineColor = 0xFF000000 | override.outlineColor();
            }
        }
        return add.call(states, state);
    }
}
