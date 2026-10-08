#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
uniform sampler2D Sampler0;
layout(location=0) in vec2 texCoord;layout(location=1) in vec4 vertexColor;layout(location=0) out vec4 fragColor;
void main(){vec4 texel=texture(Sampler0,texCoord);if(Modes.x==1)texel=vec4(1.0,1.0,1.0,texel.r);vec4 t=texel*vertexColor;if(t.a<0.1)discard;vec3 hologram=Modes.y==1?mix(t.rgb,vec3(1.0,0.12,0.10),0.48):mix(t.rgb,vec3(0.32,0.65,1.0),0.18);fragColor=vec4(hologram*ColorModulator.rgb,ColorModulator.a);}
