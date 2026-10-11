#version 150
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec4 Color;
out vec4 vertexColor;
out float vertexDistance;
void main() {
    vertexDistance=length((ModelViewMat*vec4(Position,1.0)).xyz);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
}
