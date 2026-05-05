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

    uint drawIndex = gl_InstanceIndex;
    uint sectionId = indirectLookup[drawIndex];
    SectionMeta meta = sectionData[sectionId];

    uint quadIndex = (uint(gl_VertexIndex) >> 2u);
    QuadData quad;
    setupQuad(quad, quadData[quadIndex], extractRawPos(meta), (gl_VertexIndex & 3u) == 1u);

    uint cornerId = uint(gl_VertexIndex) & 3u;
    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
    debugIds = uvec2(drawIndex, quadIndex);
}

#ifndef TAA_PATCH
vec2 taaShift() { return vec2(0.0); }
#endif
