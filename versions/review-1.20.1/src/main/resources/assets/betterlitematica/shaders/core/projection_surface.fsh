#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform int TextureMode;
uniform int SurfaceTint;
// x,y: near clear and near full distance; z,w: far fade start and end. All zero disables both.
uniform vec4 Fade;
// Opacity factor and saturation kept at the far end.
uniform vec2 FarFloor;
in vec2 texCoord;
in vec4 vertexColor;
in float vertexDistance;
in vec3 eyeOffset;
uniform vec3 CameraPos;
// x: layer axis 0..2 or negative when every layer shows, y: first cell, z: one past the last cell.
// Meshes built for a previous range stay on screen while they rebuild; this keeps them inside the current one.
uniform vec4 LayerClip;
out vec4 fragColor;
void main(){
    vec4 texel=texture(Sampler0,texCoord);
    if(TextureMode==1)texel=vec4(1.0,1.0,1.0,texel.r);
    vec4 t=texel*vertexColor;
    // Cutout precedes opacity: a 5% projection must not be discarded.
    if(t.a<0.1)discard;
    if(LayerClip.x>=0.0){int axis=int(LayerClip.x+0.5);float c=CameraPos[axis]+eyeOffset[axis];if(c<LayerClip.y-0.01||c>LayerClip.z+0.01)discard;}
    // Surfaces at the eye are removed rather than dimmed: the composite keeps one surface per pixel,
    // so a discarded fragment lets the world and farther projection surfaces show through.
    float near=Fade.y>Fade.x?smoothstep(Fade.x,Fade.y,vertexDistance):1.0;
    if(near<=0.0)discard;
    float far=Fade.w>Fade.z?smoothstep(Fade.z,Fade.w,vertexDistance):0.0;
    // A restrained blue wash keeps textures readable while distinguishing the projection.
    vec3 hologram=SurfaceTint==1?mix(t.rgb,vec3(1.0,0.12,0.10),0.48):mix(t.rgb,vec3(0.32,0.65,1.0),0.18);
    vec3 rgb=hologram*ColorModulator.rgb;
    // Distance recedes gently; wrong-block red keeps its full saturation.
    float grey=dot(rgb,vec3(0.2126,0.7152,0.0722));
    if(SurfaceTint!=1)rgb=mix(rgb,vec3(grey),far*(1.0-FarFloor.y));
    fragColor=vec4(rgb,ColorModulator.a*near*mix(1.0,FarFloor.x,far));
}
