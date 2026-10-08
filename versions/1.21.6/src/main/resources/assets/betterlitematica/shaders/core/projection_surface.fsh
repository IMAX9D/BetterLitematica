#version 150
uniform sampler2D Sampler0;
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <betterlitematica:projection_modes.glsl>

in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;
void main(){
    vec4 texel=texture(Sampler0,texCoord);
    if(TextureMode==1)texel=vec4(1.0,1.0,1.0,texel.r);
    vec4 t=texel*vertexColor;
    // Cutout precedes opacity: a 5% projection must not be discarded.
    if(t.a<0.1)discard;
    // A restrained blue wash keeps textures readable while distinguishing the projection.
    vec3 hologram=SurfaceTint==1?mix(t.rgb,vec3(1.0,0.12,0.10),0.48):mix(t.rgb,vec3(0.32,0.65,1.0),0.18);
    fragColor=vec4(hologram*ColorModulator.rgb,ColorModulator.a);
}
