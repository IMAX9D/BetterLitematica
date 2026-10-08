#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
uniform sampler2D Sampler0;
layout(location=0) in vec2 texCoord;layout(location=1) in vec4 vertexColor;layout(location=0) out vec4 fragColor;
void main(){vec4 color=texture(Sampler0,texCoord);if(color.a==0.0)discard;fragColor=color*ColorModulator;}
