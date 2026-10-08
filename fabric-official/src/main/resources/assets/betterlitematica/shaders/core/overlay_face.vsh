#version 330
#moj_import <betterlitematica:projection.glsl>
in vec3 Position;in vec4 Color;out vec4 vertexColor;
void main(){gl_Position=ProjMat*ModelViewMat*vec4(Position,1.0);vertexColor=Color;}
