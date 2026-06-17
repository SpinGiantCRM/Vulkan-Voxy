# Plan: LOD visibility — Phase 1+2 diagnostic fix (OPEN)

## Context
- `ENABLE_LODS=true` now passes indirect draw gate (fix 1) and stale-sample check (fix 2)
- `vkCmdDrawIndirectCount` IS recorded every frame with `effectiveIndirectDrawCount=29`
- **BUT GPU draws 0 instances**: `gpuCountedIndirectDraw` reads `drawCountBuffer` which contains 0 commands because cmdgen compute reads metadata with 0 quad counts
- `geometryUpdateCpuMs=0.000` — no geometry updates happening
- `sectionMetadataWriteCount=0` — CPU mirror confirms no metadata uploaded

### Why metadata is 0: Request readback analysis
The async worker chain: **traversal → request readback → submitRequestBatch → workers → applyScatterWrites**

From logs: `requestReadbackDiscarded=false, acceptedRequestCount=0`. The traversal shader's `addRequest()` writes to the request buffer, BUT the readback shows 0 requests. The most likely causes:
1. `requestQueueIndex.x` (the atomic counter) starts from a non-zero value and overflows `MAX_REQUEST_QUEUE_SIZE(50)` immediately
2. `requestQueueSize` uniform = 0, so the limit check in `addRequest()` blocks all writes
3. The request buffer isn't properly cleared/reset between frames

## Phase 1: Add CPU mirror diagnostic + request readback diagnostic

### What to add

**A) CPU mirror metadata scan** — reads CPU mirror for ALL sections, logs total sections with non-zero metadata and opaque quad counts.

**B) Request readback raw buffer dump** — log the raw request buffer bytes (first 64 bytes) to see if the atomic counter or data is corrupt.

**C) Request queue index uniform value** — confirm what `requestQueueSize` is set to.

### Files to modify

#### 1. `VulkanBerylSectionDrawPipeline.java` (~line 1936)

Add method:
```java
private void logCpuMirrorMetadataDiagnostic(
    VulkanBerylSectionGeometryData geometryData,
    VulkanBerylViewportRenderList renderList,
    int visibleCount) {
    if (!ENABLE_LODS || !CMDGEN_DEBUG_READBACK) return;
    
    int sectionCount = geometryData.getSectionCount();
    int nonZeroMetaCount = 0;
    int nonZeroOpaqueQuadCount = 0;
    long totalOpaqueQuads = 0L;
    
    for (int i = 0; i < sectionCount; i++) {
        if (geometryData.hasNonZeroSectionMetadata(i)) {
            nonZeroMetaCount++;
            long opaqueQuads = geometryData.extractOpaqueQuadCount(i);
            if (opaqueQuads > 0) {
                nonZeroOpaqueQuadCount++;
                totalOpaqueQuads += opaqueQuads;
            }
        }
    }
    
    VulkanBerylDebugLog.rateLimited("cpu-mirror-metadata-state",
        "CPU mirror metadata state: sectionCount=" + sectionCount
        + " nonZeroMetaSections=" + nonZeroMetaCount
        + " nonZeroOpaqueQuadSections=" + nonZeroOpaqueQuadCount
        + " totalOpaqueQuads=" + totalOpaqueQuads
        + " visibleCount=" + visibleCount
        + " mirrorWriteCount=" + geometryData.getSectionMetadataWriteCount(), 60);
}
```

Call it right before line 1936 (`logRealLodSingleQuadGpuVertexProofDiagnostic`).

#### 2. `VulkanBerylRenderBackendRuntime.java` (~line 392)

In `submitPendingRequestReadback()`, after the trace log at line 392-396, add a raw diagnostic dump:

```java
// Raw request buffer diagnostic (first 64 bytes)
if (CMDGEN_DEBUG_READBACK && this.requestReadbackFrameId > 0) {
    StringBuilder hexDump = new StringBuilder();
    int dumpBytes = Math.min(64, (int) requestReadbackBufferSizeBytes);
    for (int i = 0; i < dumpBytes; i++) {
        hexDump.append(String.format("%02x", requestBytes.get(i) & 0xFF));
        if ((i + 1) % 16 == 0) hexDump.append(" | ");
        else if ((i + 1) % 4 == 0) hexDump.append(" ");
    }
    VulkanBerylDebugLog.rateLimited("request-buffer-raw-dump",
        "Request buffer raw (" + dumpBytes + " bytes): " + hexDump, 60);
}
```

#### 3. `VulkanBerylTraversalResources.java` (find where requestQueueSize uniform is set)

Add a diagnostic log when the uniform buffer is initialized/set:
```java
if (CMDGEN_DEBUG_READBACK) {
    VulkanBerylDebugLog.log("request-queue-uniform", "requestQueueSize=" + requestQueueSize);
}
```

### Verification
- `./gradlew compileJava` + `git diff --check`
- Game restart with `ENABLE_LODS=true CMDGEN_DEBUG_READBACK=true`:
  - Look for `[Voxy][VulkanBeryl] CPU mirror metadata state: ...`
  - Look for `[Voxy][VulkanBeryl] Request buffer raw (64 bytes): ...`
  - Look for `[Voxy][VulkanBeryl] request-queue-uniform: requestQueueSize=...`

## Phase 2: Fix based on diagnostic results

### Scenario A: CPU mirror has non-zero metadata (nonZeroMetaSections > 0)
**Progressive rendering fallback** — since metadata exists but cmdgen isn't picking it up, bypass GPU-counted draw and write DrawCommands from CPU metadata.

In `VulkanBerylSectionDrawPipeline.java`, around line 1938:

```java
if (gpuCountedIndirectDraw && CMDGEN_DEBUG_READBACK && ENABLE_LODS) {
    // Check if the GPU draw count buffer will be 0
    // If so, fall back to CPU-generated commands
    if (effectiveIndirectDrawCount > 0 && geometryData.getSectionMetadataWriteCount() > 0) {
        int cpuCommandCount = buildIndirectCommandsFromCpuMirror(
            commandBuffer, indirectDrawCommandBuffer, geometryData, renderList, effectiveIndirectDrawCount);
        if (cpuCommandCount > 0) {
            VK10.vkCmdDrawIndirect(commandBuffer, indirectDrawCommandBuffer.getId(), 0L, cpuCommandCount, DRAW_COMMAND_STRIDE_BYTES);
        }
    }
}
```

New method:
```java
private int buildIndirectCommandsFromCpuMirror(
    VkCommandBuffer commandBuffer,
    Buffer indirectBuffer,
    VulkanBerylSectionGeometryData geometryData,
    VulkanBerylViewportRenderList renderList,
    int maxCommands) {
    
    // Read visible section IDs from render list via staging buffer
    // For each section with non-zero opaque quads, write a DrawCommand
    // Return count of commands written
}
```

**Acceptance**: With `ENABLE_LODS=true CMDGEN_DEBUG_READBACK=true`, LOD sections with geometry data appear on screen.

### Scenario B: CPU mirror has zero metadata (nonZeroMetaSections == 0)
**Fix request pipeline** — diagnose and fix why requests aren't flowing from GPU traversal to workers.

Root cause candidates (in order of likelihood):
1. **`requestQueueSize` uniform = 0** — if set to 0 in the uniform buffer, `addRequest()`'s limit check becomes `min(0, 50) = 0` and no requests can be added. Fix: ensure `requestQueueSize > 0` when `ENABLE_LODS=true`.
2. **Request queue index not reset** — `requestQueueIndex.x` persists across frames. If it starts at a non-zero value (e.g., from a previous frame that used a larger queue), the atomic counter may overflow `MAX_REQUEST_QUEUE_SIZE(50)` immediately. Fix: reset `requestQueueIndex.x = 0` before traversal dispatch.
3. **Request buffer contents stale** — if the buffer isn't cleared before traversal, stale data may corrupt the readback. Fix: clear request buffer with vkCmdFillBuffer before traversal.

**Fix 1: Ensure requestQueueSize > 0**
In `VulkanBerylTraversalResources.java`, find where the scene uniform buffer is populated. The `requestQueueSize` field is at offset `scene_uniform.requestQueueSize = 4 * 12 = 48` (fields: MVP=4x4, camSecPos=3, packedHizSize=1, camSubSecPos=3, minSSS=1, frustum=24, renderQueueMaxSize=1, frameId=1, requestQueueSize=1, maxNodeCount=1, renderDistance=1). Ensure this is set to a positive value like 50.

**Fix 2: Reset request queue index**
In the traversal dispatch method, before calling `vkCmdDispatch`, add a buffer write barrier and a `vkCmdFillBuffer` to reset the request queue index to 0. Or use `vkCmdUpdateBuffer` to write `uvec2(0, 0)` to the first 8 bytes of the request buffer.

**Fix 3: Clear request buffer**
Same as Fix 2 — a `vkCmdFillBuffer` before traversal dispatch.

### Verification (Scenario B)
- After fix, log should show `acceptedRequestCount > 0` in request readback
- After fix, `geometryUpdateCpuMs > 0` and sections start getting metadata
- LODs should eventually appear as workers process sections

## Long-term
Once either Scenario A or B produces visible LODs:
- Optimize CPU fallback to be frame-coherent (only rebuild when sections change)
- Ensure proper buffer synchronization in the async chain (fences/semaphores for request readback completion)
- Add progressive rendering: render whatever sections are available each frame, even if not all visible sections have geometry yet

## Acceptance Criteria
- [ ] `./gradlew compileJava && git diff --check` passes
- [ ] With `ENABLE_LODS=true CMDGEN_DEBUG_READBACK=true`, diagnostic logs appear
- [ ] Visible LOD geometry on screen (or clear diagnostic of what's preventing it)
- [ ] Jar rebuilt and deployed to PrismLauncher mods folder

## freebuff invocation

```
Read .opencode/task-spec.md and implement all Phase 1 diagnostic items (A-C). When done, run `./gradlew compileJava && git diff --check`.
```
