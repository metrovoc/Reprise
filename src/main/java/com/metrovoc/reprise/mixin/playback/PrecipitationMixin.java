package com.metrovoc.reprise.mixin.playback;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WeatherEffectRenderer.class)
public abstract class PrecipitationMixin {
    @ModifyExpressionValue(method = "getPrecipitationAt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation reprise$precipitation(Biome.Precipitation recorded) {
        var session = ReplaySession.active();
        if (session == null) {
            return recorded;
        }
        return switch (session.project().environment.weather) {
            case CLEAR, OVERCAST -> Biome.Precipitation.NONE;
            case SNOW -> Biome.Precipitation.SNOW;
            case RAIN, THUNDER -> Biome.Precipitation.RAIN;
            case RECORDED -> recorded;
        };
    }
}
