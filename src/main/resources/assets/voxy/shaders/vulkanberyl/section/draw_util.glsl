#import <voxy:lod/pos_util.glsl>

vec2 taaOffset = vec2(0.0);

struct QuadData {
    uvec4 attributeData;
    float lodScale;
    uint axis;
    vec3 basePoint;
    vec2 quadSizeAddin;
    vec2 uvCorner;
};

vec3 swizzelDataAxis(uint axis, vec3 data) {
    return mix(mix(data.zxy, data.xzy, bvec3(axis == 0u)), data, bvec3(axis == 1u));
}

void setupQuad(out QuadData quad, const in Quad rawQuad, uvec2 sPos) {
    uint lodLevel = getLoDLevel(sPos);
    float lodScale = float(1u << lodLevel);
    ivec3 baseSection = (getLoDPosition(sPos) << int(lodLevel)) - baseSectionPos;

    uint face = extractFace(rawQuad);
    uint modelId = extractStateId(rawQuad);
    ivec2 quadSize = extractSize(rawQuad);

    uint flags = 0u;
    flags |= (modelId << 16u);
    flags |= (uint(quadSize.x - 1) << 8u) | (uint(quadSize.y - 1) << 12u);
    flags |= (face << 4u);
    quad.attributeData = uvec4(flags, 0xFFFFFFFFu, 0u, 0u);

    vec3 quadStart = extractPos(rawQuad);
    vec2 faceSpan = vec2(quadSize);

    quad.lodScale = lodScale;
    quad.axis = face >> 1u;
    quad.basePoint = (quadStart * lodScale) + vec3(baseSection << 5);
    // Quad sizes are encoded as block spans; a 1x1 face must still emit a
    // non-degenerate unit quad.  Using size-1 here collapsed every 1x1
    // diagnostic face to four identical clip-space corners, so real LOD draws
    // could submit valid commands and geometry while rasterizing no pixels.
    quad.quadSizeAddin = max(faceSpan, vec2(1.0));
    quad.uvCorner = vec2(0.0);
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {
    vec2 cornerMask = vec2((cornerId >> 1u) & 1u, cornerId & 1u) * quad.lodScale;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * cornerMask, 0.0));
    vec4 pos = MVP * vec4(point, 1.0);
    pos.xy += taaOffset * pos.w;
    return pos;
}

vec2 getCornerUV(const in QuadData quad, uint cornerId) {
    return quad.uvCorner + quad.quadSizeAddin * vec2((cornerId >> 1u) & 1u, cornerId & 1u);
}
