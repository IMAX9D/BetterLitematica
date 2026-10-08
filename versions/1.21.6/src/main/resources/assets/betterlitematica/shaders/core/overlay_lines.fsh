#version 150
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <betterlitematica:projection_modes.glsl>
uniform sampler2D EntityDepthBefore;
uniform sampler2D EntityDepthAfter;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    if (EntityMaskEnabled != 0) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        if (texelFetch(EntityDepthAfter, pixel, 0).r < texelFetch(EntityDepthBefore, pixel, 0).r) discard;
    }
    fragColor = apply_fog(vertexColor * ColorModulator, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
