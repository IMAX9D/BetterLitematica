#version 330
#moj_import <betterlitematica:projection.glsl>
in vec3 Position;in vec2 UV0;in vec4 Color;
out vec2 texCoord;out vec4 vertexColor;
void main(){vec4 p=ModelViewMat*vec4(Position,1.0);if(Color.a<0.001){p.xy+=(Color.rg-vec2(0.5))*0.5;vertexColor=vec4(1.0);}else vertexColor=Color;gl_Position=ProjMat*p;texCoord=UV0;}
