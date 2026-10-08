#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec4 Color;
in vec3 Normal;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
void main() {
    // One physical pixel, matching the existing overlay width without a new per-vertex field.
    vec4 start = ModelViewMat * vec4(Position, 1.0);
    vec4 end = ModelViewMat * vec4(Position + Normal, 1.0);
    start.xyz *= 1.0 - 1.0 / 256.0;
    end.xyz *= 1.0 - 1.0 / 256.0;
    start = ProjMat * start;
    end = ProjMat * end;
    vec3 a = start.xyz / start.w;
    vec3 b = end.xyz / end.w;
    vec2 direction = (b.xy - a.xy) * ScreenSize;
    float lengthSquared = dot(direction, direction);
    direction = lengthSquared > 0.0 ? direction * inversesqrt(lengthSquared) : vec2(1.0, 0.0);
    vec2 offset = vec2(-direction.y, direction.x) / ScreenSize;
    if (offset.x < 0.0) offset = -offset;
    if (gl_VertexID % 2 != 0) offset = -offset;
    gl_Position = vec4((a + vec3(offset, 0.0)) * start.w, start.w);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;
}
