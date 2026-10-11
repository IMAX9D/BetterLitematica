layout(std140) uniform Projection {
 mat4 ModelViewMat;
 mat4 ProjMat;
 vec4 ColorModulator;
 ivec4 Modes;
 vec2 ViewSize;
 mat4 ViewInverse;
 vec4 CameraPos;
 vec4 LayerClip;
 vec4 Fade;
};
