#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
// Same final camera transform as the terrain pass, including view bobbing.
layout(std140) uniform WorldProjection { mat4 WorldProjMat; };
layout(location=0) in vec3 Position;
layout(location=1) in vec2 UV0;
layout(location=2) in vec4 Color;


layout(location=0) out vec2 texCoord;
layout(location=1) out vec4 vertexColor;
// Distance from the eye; ModelViewMat already carries the camera-relative section offset.
layout(location=2) out float vertexDistance;
// Inverse of the camera rotation: recovers the world-aligned offset from the eye for sections and entities alike.

layout(location=3) out vec3 eyeOffset;
void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    vertexDistance = length(viewPosition.xyz);
    eyeOffset = (ViewInverse * vec4(viewPosition.xyz, 0.0)).xyz;
    // Ordinary mesh/composite vertices have alpha=1. Light marker vertices use alpha=0.
    if (Color.a < 0.001) {
        viewPosition.xy += (Color.rg - vec2(0.5)) * 0.5;
        vertexColor = vec4(1.0);
    } else {
        vertexColor = Color;
    }
    gl_Position = WorldProjMat * viewPosition;
    texCoord = UV0;
}
