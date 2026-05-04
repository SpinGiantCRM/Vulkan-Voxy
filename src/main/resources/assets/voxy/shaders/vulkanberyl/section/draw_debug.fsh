#version 460 core

layout(location = 2) in flat uvec2 debugIds;
layout(location = 0) out vec4 outColour;

uint mixHash(uint value) {
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

void main() {
    uint hash = mixHash(debugIds.x * 1664525u + debugIds.y * 1013904223u);
    outColour = vec4(
            float((hash >> 0u) & 255u) / 255.0,
            float((hash >> 8u) & 255u) / 255.0,
            float((hash >> 16u) & 255u) / 255.0,
            1.0
    );
}
