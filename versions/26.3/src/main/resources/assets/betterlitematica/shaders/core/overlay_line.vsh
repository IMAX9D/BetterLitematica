#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
// Same final camera transform as the terrain pass, including view bobbing.
layout(std140) uniform WorldProjection { mat4 WorldProjMat; };
layout(location=0) in vec3 Position;layout(location=1) in vec4 Color;layout(location=2) in vec3 Normal;layout(location=0) out vec4 vertexColor;
layout(location=1) out float vertexDistance;
void main(){vertexDistance=length((ModelViewMat*vec4(Position,1.0)).xyz);vec4 start=WorldProjMat*ModelViewMat*vec4(Position,1.0);vec4 end=WorldProjMat*ModelViewMat*vec4(Position+Normal,1.0);vec3 ndc=start.xyz/start.w;vec2 direction=(end.xy/end.w-ndc.xy)*ViewSize;float size=length(direction);vec2 offset=size>0.00001?vec2(-direction.y,direction.x)/size/ViewSize:vec2(0.0);if(offset.x<0.0)offset=-offset;ndc.xy+=gl_VertexIndex%2==0?offset:-offset;gl_Position=vec4(ndc*start.w,start.w);vertexColor=Color;}
