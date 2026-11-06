package com.metrovoc.reprise.mixin.export;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.metrovoc.reprise.export.VideoExporter;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import java.util.Map;
import java.util.WeakHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderSystem.class)
abstract class OdsPipelineMixin {
    @Unique private static final Map<RenderPipeline, RenderPipeline[]> reprise$odsVariants = new WeakHashMap<>();

    @ModifyReturnValue(method = "applyPipelineModifiers", at = @At("RETURN"))
    private static RenderPipeline reprise$odsPipeline(RenderPipeline original) {
        var job = VideoExporter.running();
        if (job == null || !job.capturingOds()) return original;
        // The sky dome and celestial billboards represent infinitely distant scenery.
        if (original == RenderPipelines.SKY || original == RenderPipelines.END_SKY || original == RenderPipelines.CELESTIAL
            || original == RenderPipelines.STARS || original == RenderPipelines.SUNRISE_SUNSET) return original;
        var face = job.panoramaFace();
        int index = face.ordinal() * 2 + (job.rightEye() ? 1 : 0);
        var variants = reprise$odsVariants.computeIfAbsent(original, ignored -> new RenderPipeline[12]);
        if (variants[index] == null) {
            if (original.getUniforms().stream().noneMatch(u -> u.name().equals("Projection"))) return original;
            variants[index] = original.toBuilder().withShaderDefine("REPRISE_ODS_" + face.name())
                .withShaderDefine("REPRISE_EYE", job.rightEye() ? 0.07f : -0.07f).build();
        }
        return variants[index];
    }
}
