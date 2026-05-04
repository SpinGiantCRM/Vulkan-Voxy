#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#ifdef GL_ARB_gpu_shader_int64
#define Quad uint64_t
#else
#define Quad uvec2
#endif

struct SectionMeta {
    uvec4 a;
    uvec4 b;
};

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

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/section.glsl>
#import <voxy:lod/quad_util.glsl>

vec2 taaShift();

void main() {
    taaOffset = taaShift();

    uint drawIndex = gl_BaseInstance;
    uint sectionId = indirectLookup[drawIndex];
    SectionMeta meta = sectionData[sectionId];

    uint quadIndex = (uint(gl_VertexIndex) >> 2u);
    QuadData quad;
    setupQuad(quad, quadData[quadIndex], extractRawPos(meta), (gl_VertexIndex & 3u) == 1u);

    uint cornerId = gl_VertexIndex & 3u;
    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
}

#ifndef TAA_PATCH
vec2 taaShift() { return vec2(0.0); }
#endif
