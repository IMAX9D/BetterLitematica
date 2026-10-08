#version 150
uniform sampler2D EntityDepthBefore;
uniform sampler2D EntityDepthAfter;
uniform int EntityMaskEnabled;
uniform vec4 ColorModulator;
in vec4 vertexColor;
out vec4 fragColor;
void rejectEntity() {
    if (EntityMaskEnabled != 0) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        float before = texelFetch(EntityDepthBefore, pixel, 0).r;
        float after = texelFetch(EntityDepthAfter, pixel, 0).r;
        if (after < before) discard;
    }
}
#moj_import <minecraft:fog.glsl>
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
void main() {
    rejectEntity();
    fragColor = linear_fog(vertexColor * ColorModulator, vertexDistance, FogStart, FogEnd, FogColor);
}
