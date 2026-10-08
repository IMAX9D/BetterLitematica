#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
layout(location=0) in vec3 Position;layout(location=1) in vec2 UV0;layout(location=2) in vec4 Color;
layout(location=0) out vec2 texCoord;layout(location=1) out vec4 vertexColor;
void main(){vec4 p=ModelViewMat*vec4(Position,1.0);if(Color.a<0.001){p.xy+=(Color.rg-vec2(0.5))*0.5;vertexColor=vec4(1.0);}else vertexColor=Color;gl_Position=ProjMat*p;texCoord=UV0;}
