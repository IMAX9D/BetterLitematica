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
#moj_import <fog.glsl>
// x: distance where only z of the opacity remains, y: distance of full opacity. y<=x disables.
uniform vec3 NearFade;
float nearFade(float distance){return NearFade.y>NearFade.x?mix(NearFade.z,1.0,smoothstep(NearFade.x,NearFade.y,distance)):1.0;}

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
void main() {
    rejectEntity();
    vec4 color = vertexColor * ColorModulator;
    color.a *= nearFade(vertexDistance);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
