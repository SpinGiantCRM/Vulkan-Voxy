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

uint drawExtractTranslucentQuadCount(SectionMeta meta) {
    return meta.b.x & 0xFFFFu;
}

uint drawExtractOpaqueQuadStart(SectionMeta meta) {
    return extractQuadStart(meta) + drawExtractTranslucentQuadCount(meta);
}

vec2 taaShift();

void main() {
    taaOffset = taaShift();

#ifdef VOXY_VULKAN_BERYL_SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE
    if (uint(gl_InstanceIndex) == 0x6D515A7Au) {
        uint probeVertex = uint(gl_VertexIndex) % 3u;
        vec2 probePos = probeVertex == 0u
                ? vec2(-0.95, -0.95)
                : (probeVertex == 1u ? vec2(0.95, -0.95) : vec2(0.0, 0.95));
        gl_Position = vec4(probePos, 0.0, 1.0);
        uv = probePos * 0.5 + vec2(0.5);
        interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
        debugIds = uvec2(0x6D515A7Au, probeVertex);
        return;
    }
#endif

#ifdef VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE
    // Isolated graphics-pipeline smoke path: do not read section geometry, metadata,
    // render-list lookup entries, camera MVP, or depth-derived world positions.
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

    // Beryl/VulkanMod compiles graphics roots as GLSL 450 through shaderc.
    // gl_BaseInstance/gl_BaseVertex are not available in that runtime path, so
    // recover the indirect draw parameters from built-ins that are available
    // there.  In Vulkan, gl_InstanceIndex includes firstInstance and
    // gl_VertexIndex includes firstVertex for non-indexed draws.
    uint drawIndex = uint(gl_InstanceIndex);
    uint sectionId = indirectLookup[drawIndex];
    SectionMeta meta = sectionData[sectionId];

    uint opaqueQuadStart = drawExtractOpaqueQuadStart(meta);
    uint opaqueBaseVertex = opaqueQuadStart << 2u;
    uint vertexIndex = uint(gl_VertexIndex);
    uint localVertexIndex = vertexIndex - opaqueBaseVertex;
    uint quadIndex = opaqueQuadStart + (localVertexIndex >> 2u);
    QuadData quad;
    setupQuad(quad, quadData[quadIndex], extractRawPos(meta));

    uint cornerId = localVertexIndex & 3u;
    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
    debugIds = uvec2(drawIndex, quadIndex);
}

#ifndef TAA_PATCH
vec2 taaShift() { return vec2(0.0); }
#endif
