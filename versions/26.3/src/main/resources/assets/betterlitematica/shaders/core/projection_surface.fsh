#version 330
#extension GL_ARB_separate_shader_objects : require
#include <betterlitematica:projection.glsl>
uniform sampler2D Sampler0;



// x: near clear distance, y: near full distance. Zero disables the fade.

layout(location=0) in vec2 texCoord;
layout(location=1) in vec4 vertexColor;
layout(location=2) in float vertexDistance;
layout(location=3) in vec3 eyeOffset;

// x: layer axis 0..2 or negative when every layer shows, y/z: visible slab along it (continuous), w: soft edge.
// Meshes built for a previous range stay on screen while they rebuild; this keeps them inside the current one.

layout(location=0) out vec4 fragColor;
void main(){
    vec4 texel=texture(Sampler0,texCoord);
    if(Modes.x==1)texel=vec4(1.0,1.0,1.0,texel.r);
    vec4 t=texel*vertexColor;
    // Cutout precedes opacity: a 5% projection must not be discarded.
    if(t.a<0.1)discard;
    // w: soft edge width; a followed layer moves continuously, so its cut fades in over a fraction of a block.
    float layerFade=1.0;
    if(LayerClip.x>=0.0){int axis=int(LayerClip.x+0.5);float c=CameraPos[axis]+eyeOffset[axis];if(c<LayerClip.y-0.01||c>LayerClip.z+0.01)discard;
        if(LayerClip.w>0.0){if(LayerClip.y>-1e8)layerFade*=smoothstep(LayerClip.y,LayerClip.y+LayerClip.w,c);if(LayerClip.z<1e8)layerFade*=1.0-smoothstep(LayerClip.z-LayerClip.w,LayerClip.z,c);}
        if(layerFade<=0.0)discard;}
    // Surfaces at the eye are removed rather than dimmed: the composite keeps one surface per pixel,
    // so a discarded fragment lets the world and farther projection surfaces show through.
    float near=Fade.y>Fade.x?smoothstep(Fade.x,Fade.y,vertexDistance):1.0;
    if(near<=0.0)discard;
    // A restrained blue wash keeps textures readable while distinguishing the projection.
    vec3 hologram=Modes.y==1?mix(t.rgb,vec3(1.0,0.12,0.10),0.48):mix(t.rgb,vec3(0.32,0.65,1.0),0.18);
    vec3 rgb=hologram*ColorModulator.rgb;
    fragColor=vec4(rgb,ColorModulator.a*near*layerFade);
}
