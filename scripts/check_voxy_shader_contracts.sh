#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

failures=0

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    failures=$((failures + 1))
}

pass() {
    printf 'PASS: %s\n' "$*"
}

check_sha256() {
    local file="$1"
    local expected="$2"

    if [[ ! -f "$file" ]]; then
        fail "$file is missing"
        return
    fi

    local actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [[ "$actual" != "$expected" ]]; then
        fail "$file differs from the pinned upstream/reference shader contract (expected $expected, got $actual)"
        return
    fi

    pass "$file matches pinned upstream/reference shader contract"
}

require_literal() {
    local file="$1"
    local text="$2"
    local description="$3"

    if grep -Fq "$text" "$file"; then
        pass "$description"
    else
        fail "$description missing in $file"
    fi
}

require_regex() {
    local file="$1"
    local pattern="$2"
    local description="$3"

    if grep -Eq "$pattern" "$file"; then
        pass "$description"
    else
        fail "$description missing in $file"
    fi
}

check_sha256 "src/main/resources/assets/voxy/shaders/lod/quad_format.glsl" "26f4dc6e0bba703ae3424ff2ba3d0dee94199b0df0c24908e44e8c4d18b12a11"
check_sha256 "src/main/resources/assets/voxy/shaders/lod/section.glsl" "3403c36c45b0929f9f9dc7293b3bdef688ff384985622e8994a5af8d1103d762"
check_sha256 "src/main/resources/assets/voxy/shaders/lod/pos_util.glsl" "a444b9cd0fee530f9c35657d20fe2ec8dbe3d58fec082f12977529c3c4ca53be"

queue_shader="src/main/resources/assets/voxy/shaders/lod/hierarchical/queue.glsl"
require_literal "$queue_shader" "layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;" "queue.glsl keeps default OpenGL uniform queueIdx contract"

if grep -Fq "VOXY_VULKAN_BERYL_QUEUE_INDEX_SSBO" "$queue_shader"; then
    require_literal "$queue_shader" "#ifdef VOXY_VULKAN_BERYL_QUEUE_INDEX_SSBO" "queue.glsl gates Vulkan/Beryl queue index SSBO behind VOXY_VULKAN_BERYL_QUEUE_INDEX_SSBO"
    require_regex "$queue_shader" "layout\\(binding = NODE_QUEUE_INDEX_BINDING, std430\\).*buffer NodeQueueIndex" "queue.glsl Vulkan/Beryl queue index SSBO contract"
fi

screenspace_shader="src/main/resources/assets/voxy/shaders/lod/hierarchical/screenspace.glsl"
require_literal "$screenspace_shader" "layout(binding = HIZ_BINDING) uniform sampler2D hizDepthSampler;" "screenspace.glsl keeps default Hi-Z sampler contract"

if grep -Fq "VOXY_VULKAN_BERYL_DISABLE_HIZ" "$screenspace_shader"; then
    require_literal "$screenspace_shader" "#ifndef VOXY_VULKAN_BERYL_DISABLE_HIZ" "screenspace.glsl gates Vulkan/Beryl Hi-Z suppression behind VOXY_VULKAN_BERYL_DISABLE_HIZ"
fi

if (( failures > 0 )); then
    printf '\nShader contract check failed with %d issue(s).\n' "$failures" >&2
    exit 1
fi

printf '\nShader contract check passed.\n'
