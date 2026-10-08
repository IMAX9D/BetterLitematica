#version 330
#moj_import <betterlitematica:projection.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;in vec4 vertexColor;out vec4 fragColor;
void main(){vec4 color=texture(Sampler0,texCoord);if(color.a==0.0)discard;fragColor=color*ColorModulator;}
