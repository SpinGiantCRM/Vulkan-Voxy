#version 460
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_DATA_USE_64_BIT

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/section.glsl>

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec3 baseSectionPos;
    int frameId;
    vec3 innerTranslation;
    float _scenePadding0;
};

#import <voxy:vulkanberyl/section/draw_util.glsl>

layout(binding = 4, std430) readonly buffer GeometryBuffer {
    Quad quadData[];
};
layout(binding = 5, std430) readonly buffer MetadataBuffer {
    SectionMeta sectionData[];
};
layout(binding = 6, std430) readonly buffer RenderListBuffer {
    uint visibleCount;
    uint indirectLookup[];
};

layout(location = 0) out flat uvec4 interData;
layout(location = 1) out vec2 uv;
layout(location = 2) out flat uvec2 debugIds;

vec2 taaShift();

void main() {
    taaOffset = taaShift();

#ifdef VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE
    uint smokeVertex = uint(gl_VertexIndex) % 3u;
    vec2 smokePos = smokeVertex == 0u
            ? vec2(-0.75, -0.75)
            : (smokeVertex == 1u ? vec2(0.75, -0.75) : vec2(0.0, 0.75));
    gl_Position = vec4(smokePos, 0.0, 1.0);
    uv = smokePos * 0.5 + vec2(0.5);
    interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
    debugIds = uvec2(uint(gl_InstanceIndex), uint(gl_VertexIndex));
    return;
#endif

#ifdef VOXY_VULKAN_BERYL_DRAW_WORLDSPACE_SMOKE_INDIRECT
    uint smokeVertex = uint(gl_VertexIndex) % 3u;
    vec3 worldSmokePoint = innerTranslation + (smokeVertex == 0u
            ? vec3(-0.75, -0.50, -3.0)
            : (smokeVertex == 1u ? vec3(0.75, -0.50, -3.0) : vec3(0.0, 0.75, -3.0)));
    gl_Position = MVP * vec4(worldSmokePoint, 1.0);
    uv = worldSmokePoint.xy * 0.5 + vec2(0.5);
    interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
    debugIds = uvec2(uint(gl_InstanceIndex), uint(gl_VertexIndex));
    return;
#endif

    uint drawIndex = gl_InstanceIndex;
    uint sectionId = indirectLookup[drawIndex];
    SectionMeta meta = sectionData[sectionId];

    uint quadIndex = (uint(gl_VertexIndex) >> 2u);
    QuadData quad;
    setupQuad(quad, quadData[quadIndex], extractRawPos(meta));

    uint cornerId = uint(gl_VertexIndex) & 3u;
    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
    debugIds = uvec2(drawIndex, quadIndex);
}

#ifndef TAA_PATCH
vec2 taaShift() { return vec2(0.0); }
#endif
