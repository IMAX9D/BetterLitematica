#version 330
#moj_import <betterlitematica:projection.glsl>
uniform sampler2D EntityDepthBefore;uniform sampler2D EntityDepthAfter;
in vec4 vertexColor;out vec4 fragColor;
in float vertexDistance;
void main(){if(Modes.z!=0){ivec2 pixel=ivec2(gl_FragCoord.xy);if(texelFetch(EntityDepthAfter,pixel,0).r>texelFetch(EntityDepthBefore,pixel,0).r)discard;}if(vertexColor.a==0.0)discard;fragColor=vertexColor*ColorModulator;if(Modes.w!=0)fragColor.a*=mix(0.18,1.0,smoothstep(1.0,3.0,vertexDistance));}
