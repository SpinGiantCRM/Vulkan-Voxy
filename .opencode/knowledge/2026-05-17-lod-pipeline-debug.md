---
date: 2026-05-17
tags: [lod, traversal, request-pipeline, gpu, debug]
project: Vulkan-Voxy
files-examined:
  - VulkanBerylSectionDrawPipeline.java
  - traversal.comp
  - Node (CPU-side class)
---

# LOD Pipeline: Architecture, Bug Analysis & Fix Options

## Pipeline Stages (7-Stage Model)

1. **CPU chunk loading** → LOD node tree construction (nodes with mesh pointers to CPU-side data)
2. **GPU traversal (compute shader)** → walks visible nodes, outputs render list + request buffer
3. **Request readback (GPU→CPU copy)** → reads request buffer, submits batches to async workers
4. **Async workers** → generate geometry data for requested sections
5. **Geometry upload (applyScatterWrites)** → uploads CPU geometry + metadata to GPU buffers
6. **Cmdgen (compute shader)** → reads section metadata, produces DrawCommands to indirect buffer
7. **Indirect draw (vkCmdDrawIndirectCount)** → reads draw count from GPU buffer, executes commands

## Current Bug: rawCount=0 / geometryUpdateCpuMs=0.000

The request pipeline breaks at stage 3: readback returns rawCount=0 consistently. No workers run (geometryUpdateCpuMs=0.000), sectionCount=0 in CPU mirror, draws record but 0 instances.

Render list has ~7 visible entries (traversal enqueued them for rendering) but 0 requests written. Likely all visible nodes have hasRequested=true or have CPU meshes but GPU geometry buffer has no data for those meshes.

### Request Buffer Mechanics

- reset to 0 every frame via vkCmdFillBuffer (offset 0, Integer.BYTES)
- Format: 8 bytes header (uvec2 requestQueueIndex) + 50 × 8 bytes entries = 408 bytes
  - requestQueueIndex.x (offset 0, 4 bytes) = atomic counter, reset to 0 every frame
  - requestQueueIndex.y (offset 4, 4 bytes) = unused in the counter path
  - requestQueue[i] (offset 8 + i×8, 8 bytes) = uvec2 position of requested node
- Shader addRequest() checks: frameId >= 7 (passes), frameId >= 4 (passes), !hasRequested(node), counter < limit
- requestQueueSize uniform = fillness × MAX_REQUEST_QUEUE_SIZE, where fillness = ((4000 - taskCount) / 4000)²

### FrameId Semantics

- `frameId` in shader = activeTraversalStageLimit = FULL_TRAVERSAL_STAGE_LIMIT = 7 (NOT real frame counter)
- isFullTraversalStage() returns frameId >= 7 → true at stage 7
- addRequest() only called when node is visible, LOD level > 0, shouldDecend() true, hasChildren() false (leaf), OR node has no mesh
- hasRequested() uses bitfield in node metadata

## hasRequested Flag Persistence Bug (Root Cause Candidate)

hasRequested() checks node.flags bit 0 → maps to nodes[nodeId].z bit 24. markRequested() sets node.flags |= 1u and nodes[nodeId].z |= 1u<<24 via atomic ops.

**CRITICAL**: This flag is NEVER cleared between frames or sessions. Once marked requested, it stays forever.

**Self-sustaining broken state**: traversal runs → finds nodes needing meshes → checks hasRequested → returns true from stale flag → writes 0 requests → no workers run → nodes never get geometry → hasRequested stays true forever.

### Fix Options

1. Clear requested flag at startup: vkCmdFillBuffer bitmask on the node data buffer
2. Clear requested flag per-frame (costly but robust)
3. Ignore hasRequested and always call addRequest for nodes without meshes (wastes atomics)
4. Reset the node data buffer entirely between sessions / ENABLE_LODS toggle

### Node Data Buffer Format

uvec4 per node, 16 bytes:
- xy: raw position (uvec2)
- z: [23:0] = meshPtr, [31:24] = flags byte 0 (bit 0 = hasRequested)
- w: [23:0] = childPtr, [31:24] = flags byte 1

### Hierarchical Traversal Behavior

- Stage 7 (frameId >= 7) = full traversal: all stages pass, traverse() runs
- traverse() processes each node:
  - if lodLevel != 0 && shouldDecend(): decend OR request + render
  - else: render if hasMesh, OR request if !hasMesh
- 'render' path: enqueueSelfForRender writes node to render list
- addRequest only called when node needs geometry AND !hasRequested

### Key Trade-off

The hasRequested flag prevents redundant writes but creates a deadlock when the request pipeline is broken. Fixing the pipeline for one frame should cascade: geometry arrives → nodes get hasMesh=true → they render directly.

## Likely Root Causes (in priority order)

1. Traversal writes requests but readback buffer copy misses them (timing/barrier)
2. Nodes already have hasRequested()=true from previous sessions
3. Traversal never enters addRequest() paths (all nodes have children or have meshes)
4. requestBuffer format mismatch between shader (uvec2) and Java readback

## Diagnostic Gaps

All critical pipeline state (request readback, traversal dispatch, LOD bringup summary) logs at `.trace()` level — invisible without TRACE_LOGS=true. CPU mirror diagnostic uses `.once()` which fires on frame 1 when sectionCount=0.

Prior session with CPU fallback (ENABLE_LODS=false) showed 18-19 visible sections with geometry. The geometry upload path works — it just doesn't receive requests via GPU traversal.
