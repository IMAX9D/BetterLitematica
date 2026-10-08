#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
uniform sampler2D EntityDepthBefore;uniform sampler2D EntityDepthAfter;
layout(location=0) in vec4 vertexColor;layout(location=0) out vec4 fragColor;
void main(){if(Modes.z!=0){ivec2 pixel=ivec2(gl_FragCoord.xy);if(texelFetch(EntityDepthAfter,pixel,0).r>texelFetch(EntityDepthBefore,pixel,0).r)discard;}if(vertexColor.a==0.0)discard;fragColor=vertexColor*ColorModulator;}
