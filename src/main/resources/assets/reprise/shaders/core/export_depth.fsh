#version 330

uniform sampler2D DepthSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    // With 2x supersampling keep the nearest covered surface at silhouettes.
    vec2 stepUV = 0.25 * fwidth(texCoord);
    float depth = min(min(texture(DepthSampler, texCoord - stepUV).r,
                          texture(DepthSampler, texCoord + stepUV).r),
                      min(texture(DepthSampler, texCoord + vec2(stepUV.x, -stepUV.y)).r,
                          texture(DepthSampler, texCoord + vec2(-stepUV.x, stepUV.y)).r));
    uint bits = floatBitsToUint(depth);
    fragColor = vec4(uvec4(bits, bits >> 8, bits >> 16, bits >> 24) & 255u) / 255.0;
}
