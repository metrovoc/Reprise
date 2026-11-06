package com.metrovoc.reprise.export;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;

/** Preserve world depth before Minecraft clears it for first-person hands and the GUI. */
final class DepthPass implements AutoCloseable {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("reprise", "pipeline/export_depth"))
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("reprise", "core/export_depth"))
        .withSampler("DepthSampler")
        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        .build();

    final TextureTarget target;

    DepthPass(int width, int height) {
        target = new TextureTarget("Reprise world depth", width, height, false);
    }

    void capture(RenderTarget world) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Reprise capture world depth", target.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.bindTexture("DepthSampler", world.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
    }

    @Override
    public void close() { target.destroyBuffers(); }
}
