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
    uvec4 realLodProbeData;
    vec4 realLodProbeReplayCpuClip0;
    vec4 realLodProbeReplayCpuClip1;
    vec4 realLodProbeReplayCpuClip2;
    vec4 realLodProbeReplayCpuClip3;
    vec4 realLodProbeReplayCpuWorld0;
    vec4 realLodProbeReplayCpuWorld1;
    vec4 realLodProbeReplayCpuWorld2;
    vec4 realLodProbeReplayCpuWorld3;
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
#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE
layout(binding = 9, std430) buffer RealLodGpuDecodeParityBuffer {
    uint realLodGpuDecodeParity[];
};
#endif

layout(location = 0) out flat uvec4 interData;
layout(location = 1) out vec2 uv;
layout(location = 2) out flat uvec2 debugIds;

uint drawExtractTranslucentQuadCount(SectionMeta meta) {
    return meta.b.x & 0xFFFFu;
}

uint drawExtractPassQuadStart(SectionMeta meta) {
#ifdef VOXY_VULKAN_BERYL_TRANSLUCENT_PASS
    return extractQuadStart(meta);
#else
    return extractQuadStart(meta) + drawExtractTranslucentQuadCount(meta);
#endif
}

vec2 taaShift();

#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE
vec3 realLodProbeWorldCorner(in QuadData quad, uint cornerId) {
    vec2 cornerMask = vec2((cornerId >> 1u) & 1u, cornerId & 1u) * quad.lodScale;
    return quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * cornerMask, 0.0));
}

void realLodProbeWriteGpuDecodeParity(
        uint triVertex,
        uint normalDrawIndex,
        uint normalSectionId,
        uint normalPassQuadStart,
        uint normalLocalQuadIndex,
        uint normalAbsoluteQuadIndex,
        uint activeDecodeMode,
        uint activeSectionId,
        uint activePassQuadStart,
        uint activeLocalQuadIndex,
        uint activeAbsoluteQuadIndex,
        bool activeBypassedIndirectLookup,
        SectionMeta activeMeta,
        Quad rawQuad,
        QuadData quad) {
    if (triVertex != 0u) {
        return;
    }
    uint lodLevel = getLoDLevel(extractRawPos(activeMeta));
    realLodGpuDecodeParity[0] = 0x47504450u;
    realLodGpuDecodeParity[1] = uint(frameId);
    realLodGpuDecodeParity[2] = normalDrawIndex;
    realLodGpuDecodeParity[3] = normalSectionId;
    realLodGpuDecodeParity[4] = normalPassQuadStart;
    realLodGpuDecodeParity[5] = normalLocalQuadIndex;
    realLodGpuDecodeParity[6] = normalAbsoluteQuadIndex;
    realLodGpuDecodeParity[7] = activeDecodeMode;
    realLodGpuDecodeParity[8] = activeSectionId;
    realLodGpuDecodeParity[9] = activePassQuadStart;
    realLodGpuDecodeParity[10] = activeLocalQuadIndex;
    realLodGpuDecodeParity[11] = activeAbsoluteQuadIndex;
    realLodGpuDecodeParity[12] = activeBypassedIndirectLookup ? 1u : 0u;
    realLodGpuDecodeParity[13] = 0xffffffffu;
    realLodGpuDecodeParity[14] = uint(rawQuad & uint64_t(0xffffffffu));
    realLodGpuDecodeParity[15] = uint(rawQuad >> 32);
    realLodGpuDecodeParity[16] = lodLevel;
    realLodGpuDecodeParity[17] = quad.axis;
    realLodGpuDecodeParity[18] = floatBitsToUint(quad.basePoint.x);
    realLodGpuDecodeParity[19] = floatBitsToUint(quad.basePoint.y);
    realLodGpuDecodeParity[20] = floatBitsToUint(quad.basePoint.z);
    realLodGpuDecodeParity[21] = floatBitsToUint(quad.lodScale);
    realLodGpuDecodeParity[22] = floatBitsToUint(quad.quadSizeAddin.x);
    realLodGpuDecodeParity[23] = floatBitsToUint(quad.quadSizeAddin.y);
    realLodGpuDecodeParity[24] = extractRawPos(activeMeta).x;
    realLodGpuDecodeParity[25] = extractRawPos(activeMeta).y;
    for (uint corner = 0u; corner < 4u; corner++) {
        vec3 world = realLodProbeWorldCorner(quad, corner);
        uint base = 26u + corner * 4u;
        realLodGpuDecodeParity[base] = floatBitsToUint(world.x);
        realLodGpuDecodeParity[base + 1u] = floatBitsToUint(world.y);
        realLodGpuDecodeParity[base + 2u] = floatBitsToUint(world.z);
        realLodGpuDecodeParity[base + 3u] = floatBitsToUint(1.0);
    }
    realLodGpuDecodeParity[42] = activeMeta.a.x;
    realLodGpuDecodeParity[43] = activeMeta.a.y;
    realLodGpuDecodeParity[44] = activeMeta.a.z;
    realLodGpuDecodeParity[45] = activeMeta.a.w;
    realLodGpuDecodeParity[46] = activeMeta.b.x;
    realLodGpuDecodeParity[47] = activeMeta.b.y;
    realLodGpuDecodeParity[48] = activeMeta.b.z;
    realLodGpuDecodeParity[49] = activeMeta.b.w;
}
#endif

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

#ifdef VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER
    if (uint(gl_InstanceIndex) == 0x464D4152u) {
        uint markerVertex = uint(gl_VertexIndex) % 3u;
        vec2 markerPos = markerVertex == 0u
                ? vec2(-1.0, -1.0)
                : (markerVertex == 1u ? vec2(3.0, -1.0) : vec2(-1.0, 3.0));
        gl_Position = vec4(markerPos, 0.0, 1.0);
        uv = markerPos * 0.5 + vec2(0.5);
        interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
        debugIds = uvec2(0x464D4152u, markerVertex);
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

    // Vulkan/Beryl section draw contract: cmdgen emits one triangle-list
    // command per visible render-list entry with firstVertex = 0 and
    // firstInstance = render-list draw index. Beryl's GLSL path does not expose
    // gl_BaseInstance, but gl_InstanceIndex includes firstInstance for these
    // single-instance indirect draws, so use it to resolve indirectLookup.
    uint normalDrawIndex = uint(gl_InstanceIndex);
    uint normalSectionId = indirectLookup[normalDrawIndex];
    SectionMeta normalMeta = sectionData[normalSectionId];
    uint normalPassQuadStart = drawExtractPassQuadStart(normalMeta);
    uint localVertexIndex = uint(gl_VertexIndex);
    uint normalLocalQuadIndex = localVertexIndex / 6u;
    uint normalAbsoluteQuadIndex = normalPassQuadStart + normalLocalQuadIndex;
    uint triVertex = localVertexIndex % 6u;
    uint drawIndex = normalDrawIndex;
    uint sectionId = normalSectionId;
    SectionMeta meta = normalMeta;
    uint passQuadStart = normalPassQuadStart;
    uint localQuadIndex = normalLocalQuadIndex;
    uint cornerId = triVertex == 0u
            ? 0u
            : (triVertex == 1u ? 1u : (triVertex == 2u ? 2u : (triVertex == 3u ? 2u : (triVertex == 4u ? 1u : 3u))));

#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE
    if (localQuadIndex != 0u) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        uv = vec2(0.0);
        interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
        debugIds = uvec2(drawIndex, 0x51444153u);
        return;
    }
#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE
    vec2 realLodProofCorner = cornerId == 0u
            ? vec2(-0.18, -0.18)
            : (cornerId == 1u ? vec2(0.18, -0.18) : (cornerId == 2u ? vec2(-0.18, 0.18) : vec2(0.18, 0.18)));
    gl_Position = vec4(realLodProofCorner, 0.0, 1.0);
    uv = realLodProofCorner * 0.5 + vec2(0.5);
    interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
    debugIds = uvec2(drawIndex, 0x48435052u | triVertex);
    return;
#endif
#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP
    if ((realLodProbeData.w & 2u) != 0u) {
        gl_Position = cornerId == 0u
                ? realLodProbeReplayCpuClip0
                : (cornerId == 1u ? realLodProbeReplayCpuClip1 : (cornerId == 2u ? realLodProbeReplayCpuClip2 : realLodProbeReplayCpuClip3));
        uv = cornerId == 0u
                ? vec2(0.0, 0.0)
                : (cornerId == 1u ? vec2(1.0, 0.0) : (cornerId == 2u ? vec2(0.0, 1.0) : vec2(1.0, 1.0)));
        interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
        debugIds = uvec2(drawIndex, 0x52435043u | triVertex);
        return;
    }
#endif
#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD
    if ((realLodProbeData.w & 4u) != 0u) {
        vec4 cpuWorldCorner = cornerId == 0u
                ? realLodProbeReplayCpuWorld0
                : (cornerId == 1u ? realLodProbeReplayCpuWorld1 : (cornerId == 2u ? realLodProbeReplayCpuWorld2 : realLodProbeReplayCpuWorld3));
        gl_Position = MVP * vec4(cpuWorldCorner.xyz, 1.0);
        uv = cornerId == 0u
                ? vec2(0.0, 0.0)
                : (cornerId == 1u ? vec2(1.0, 0.0) : (cornerId == 2u ? vec2(0.0, 1.0) : vec2(1.0, 1.0)));
        interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
        debugIds = uvec2(drawIndex, 0x52435744u | triVertex);
        return;
    }
#endif
#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD
    if ((realLodProbeData.w & 8u) != 0u) {
        uint forcedSectionId = realLodProbeData.y;
        SectionMeta forcedMeta = sectionData[forcedSectionId];
        uint forcedPassQuadStart = drawExtractPassQuadStart(forcedMeta);
        uint forcedQuadIndex = realLodProbeData.x;
        uint forcedLocalQuadIndex = forcedQuadIndex >= forcedPassQuadStart ? forcedQuadIndex - forcedPassQuadStart : 0xffffffffu;
        Quad forcedRawQuad = quadData[forcedQuadIndex];
        QuadData forcedQuad;
        setupQuad(forcedQuad, forcedRawQuad, extractRawPos(forcedMeta));
        realLodProbeWriteGpuDecodeParity(
                triVertex,
                normalDrawIndex, normalSectionId, normalPassQuadStart, normalLocalQuadIndex, normalAbsoluteQuadIndex,
                2u, forcedSectionId, forcedPassQuadStart, forcedLocalQuadIndex, forcedQuadIndex, true,
                forcedMeta, forcedRawQuad, forcedQuad);
        gl_Position = getQuadCornerPos(forcedQuad, cornerId);
        uv = getCornerUV(forcedQuad, cornerId);
        interData = forcedQuad.attributeData;
        debugIds = uvec2(forcedSectionId, forcedQuadIndex);
        return;
    }
#endif
#endif

#if defined(VOXY_VULKAN_BERYL_REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) && !defined(VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE)
    // Normal real-LOD indirect vertex-path probe: keep the actual
    // gl_InstanceIndex -> render-list -> section metadata path and the actual
    // triangle-list gl_VertexIndex path, but remove quad decode/world transform
    // from the equation.  If this fixed clip-space quad is visible, the real
    // indirect vertex shader invocation and triangle-list topology are executing
    // and the remaining bug is in geometry indexing/transform.
    // The center varies by drawIndex so multi-command captures can distinguish
    // whether gl_InstanceIndex includes indirect firstInstance or is stuck at 0.
    vec2 probeCenter = vec2((float(int(drawIndex & 7u)) - 3.5) * 0.12, 0.0);
    vec2 probeCorner = cornerId == 0u
            ? vec2(-0.22, -0.22)
            : (cornerId == 1u ? vec2(0.22, -0.22) : (cornerId == 2u ? vec2(-0.22, 0.22) : vec2(0.22, 0.22)));
    vec2 probePos = probeCenter + probeCorner;
    gl_Position = vec4(probePos, 0.0, 1.0);
    uv = probeCorner * 0.5 + vec2(0.5);
    interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
    debugIds = uvec2(drawIndex, 0xB4536000u | triVertex);
    return;
#endif

    uint quadIndex =
#if defined(VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE) && defined(VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD)
            realLodProbeData.x;
#else
            passQuadStart + localQuadIndex;
#endif
    uint activeLocalQuadIndex = quadIndex >= passQuadStart ? quadIndex - passQuadStart : 0xffffffffu;
    Quad rawQuad = quadData[quadIndex];
    QuadData quad;
    setupQuad(quad, rawQuad, extractRawPos(meta));

#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE
    realLodProbeWriteGpuDecodeParity(
            triVertex,
            normalDrawIndex, normalSectionId, normalPassQuadStart, normalLocalQuadIndex, normalAbsoluteQuadIndex,
#ifdef VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD
            1u,
#else
            0u,
#endif
            sectionId, passQuadStart, activeLocalQuadIndex, quadIndex, false,
            meta, rawQuad, quad);
#endif

#ifdef VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE
    // Real quad-read clip-space probe: keep the submitted indirect draw,
    // render-list section lookup, pass quad start, quadData indexing, and
    // setupQuad decode live, then replace only the final world/MVP projection.
    uint decodedHash = uint(quad.axis)
            ^ (uint(quad.basePoint.x) << 1u)
            ^ (uint(quad.basePoint.y) << 5u)
            ^ (uint(quad.basePoint.z) << 9u)
            ^ (uint(quad.quadSizeAddin.x) << 13u)
            ^ (uint(quad.quadSizeAddin.y) << 17u)
            ^ uint(rawQuad & uint64_t(0xffffffffu));
    vec2 probeCenter = vec2(
            (float(int(decodedHash & 3u)) - 1.5) * 0.08,
            (float(int((decodedHash >> 2u) & 3u)) - 1.5) * 0.08);
    vec2 probeCorner = cornerId == 0u
            ? vec2(-0.20, -0.20)
            : (cornerId == 1u ? vec2(0.20, -0.20) : (cornerId == 2u ? vec2(-0.20, 0.20) : vec2(0.20, 0.20)));
    gl_Position = vec4(probeCenter + probeCorner, float((decodedHash >> 4u) & 7u) * 0.005, 1.0);
    uv = getCornerUV(quad, cornerId);
    interData = uvec4(0u, 0xffffffffu, 0xffffffffu, 0u);
    debugIds = uvec2(drawIndex, 0x51554144u ^ uint(quadIndex));
    return;
#endif

    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
    debugIds = uvec2(drawIndex, quadIndex);
}

#ifndef TAA_PATCH
vec2 taaShift() { return vec2(0.0); }
#endif
