#version 330
#moj_import <betterlitematica:projection.glsl>
in vec3 Position;in vec4 Color;in vec3 Normal;out vec4 vertexColor;
void main(){vec4 start=ProjMat*ModelViewMat*vec4(Position,1.0);vec4 end=ProjMat*ModelViewMat*vec4(Position+Normal,1.0);vec3 ndc=start.xyz/start.w;vec2 direction=(end.xy/end.w-ndc.xy)*ViewSize;float size=length(direction);vec2 offset=size>0.00001?vec2(-direction.y,direction.x)/size/ViewSize:vec2(0.0);if(offset.x<0.0)offset=-offset;ndc.xy+=gl_VertexID%2==0?offset:-offset;gl_Position=vec4(ndc*start.w,start.w);vertexColor=Color;}
