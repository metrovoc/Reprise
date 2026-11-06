package com.metrovoc.reprise.mixin.export;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
abstract class OdsShaderMixin {
    @WrapOperation(method = "compileShader", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;injectDefines(Ljava/lang/String;Lnet/minecraft/client/renderer/ShaderDefines;)Ljava/lang/String;"))
    private String reprise$odsVertex(String source, ShaderDefines defines, Operation<String> inject) {
        if (defines.values().containsKey("REPRISE_EYE") && source.contains("gl_Position") && source.contains("ProjMat")) {
            // Keep lighting, materials, animation and modded vertex transforms in their original shader.
            source = source.replaceFirst("\\bvoid\\s+main\\s*\\(", "void repriseOriginalMain(") + """

                // Map cube-face camera coordinates into the shot's camera coordinates.
                vec3 repriseShot(vec3 p) {
                #if defined(REPRISE_ODS_BACK)
                    return vec3(-p.x, p.y, -p.z);
                #elif defined(REPRISE_ODS_RIGHT)
                    return vec3(-p.z, p.y, p.x);
                #elif defined(REPRISE_ODS_LEFT)
                    return vec3(p.z, p.y, -p.x);
                #elif defined(REPRISE_ODS_TOP)
                    return vec3(p.x, -p.z, p.y);
                #elif defined(REPRISE_ODS_BOTTOM)
                    return vec3(p.x, p.z, -p.y);
                #else
                    return p;
                #endif
                }

                void main() {
                    repriseOriginalMain();
                    if (ProjMat[3][3] != 0.0) return;
                    vec3 view = vec3(gl_Position.x / ProjMat[0][0], gl_Position.y / ProjMat[1][1], -gl_Position.w);
                    vec2 p = repriseShot(view).xz;
                    float radius = length(p);
                    if (radius < 0.000001) return;
                    // Find the eye tangent to the viewing circle through this vertex. Inside the
                    // circle no real tangent exists; collapse smoothly toward the vertical axis.
                    float sine = REPRISE_EYE / max(radius, abs(REPRISE_EYE));
                    vec2 delta = -p * sine * sine - vec2(-p.y, p.x) * sine * sqrt(max(0.0, 1.0 - sine * sine));
                    mat3 toShot = mat3(repriseShot(vec3(1,0,0)), repriseShot(vec3(0,1,0)), repriseShot(vec3(0,0,1)));
                    gl_Position += ProjMat * vec4(transpose(toShot) * vec3(delta.x, 0, delta.y), 0);
                }
                """;
        }
        return inject.call(source, defines);
    }
}
