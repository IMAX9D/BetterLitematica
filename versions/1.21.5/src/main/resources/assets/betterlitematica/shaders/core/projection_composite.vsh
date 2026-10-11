#version 150
in vec3 Position;
in vec2 UV0;
in vec4 Color;
uniform mat4 ProjectionView;
uniform mat4 ProjectionProjection;
out vec2 texCoord;
out vec4 vertexColor;
void main() {
    vec4 viewPosition = ProjectionView * vec4(Position, 1.0);
    // Ordinary mesh/composite vertices have alpha=1. Light marker vertices use alpha=0.
    if (Color.a < 0.001) {
        viewPosition.xy += (Color.rg - vec2(0.5)) * 0.5;
        vertexColor = vec4(1.0);
    } else {
        vertexColor = Color;
    }
    gl_Position = ProjectionProjection * viewPosition;
    texCoord = UV0;
}
