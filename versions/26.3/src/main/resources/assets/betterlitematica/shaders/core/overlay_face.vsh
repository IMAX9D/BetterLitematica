#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
// Same final camera transform as the terrain pass, including view bobbing.
layout(std140) uniform WorldProjection { mat4 WorldProjMat; };
layout(location=0) in vec3 Position;layout(location=1) in vec4 Color;layout(location=0) out vec4 vertexColor;
layout(location=1) out float vertexDistance;
void main(){vertexDistance=length((ModelViewMat*vec4(Position,1.0)).xyz);gl_Position=WorldProjMat*ModelViewMat*vec4(Position,1.0);vertexColor=Color;}
