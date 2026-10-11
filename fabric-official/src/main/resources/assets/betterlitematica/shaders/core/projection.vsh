#version 330
#moj_import <betterlitematica:projection.glsl>
in vec3 Position;
in vec2 UV0;
in vec4 Color;


out vec2 texCoord;
out vec4 vertexColor;
// Distance from the eye; ModelViewMat already carries the camera-relative section offset.
out float vertexDistance;
// Inverse of the camera rotation: recovers the world-aligned offset from the eye for sections and entities alike.

out vec3 eyeOffset;
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
    gl_Position = ProjMat * viewPosition;
    texCoord = UV0;
}
