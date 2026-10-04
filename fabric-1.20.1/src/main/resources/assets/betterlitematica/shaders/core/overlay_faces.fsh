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
void main() {
    rejectEntity();
    if (vertexColor.a == 0.0) discard;
    fragColor = vertexColor * ColorModulator;
}
