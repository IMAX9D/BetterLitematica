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
#define NearFade (NearEnabled!=0?vec3(1.0,3.0,0.18):vec3(0.0,0.0,1.0))
float nearFade(float distance){return NearFade.y>NearFade.x?mix(NearFade.z,1.0,smoothstep(NearFade.x,NearFade.y,distance)):1.0;}
void main() {
    if (EntityMaskEnabled != 0) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        if (texelFetch(EntityDepthAfter, pixel, 0).r < texelFetch(EntityDepthBefore, pixel, 0).r) discard;
    }
    vec4 color = vertexColor * ColorModulator;
    color.a *= nearFade(sphericalVertexDistance);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
