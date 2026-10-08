#version 150
uniform sampler2D Sampler0;
#moj_import <minecraft:dynamictransforms.glsl>
in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;
void main(){fragColor=texture(Sampler0,texCoord)*vertexColor*ColorModulator;}
