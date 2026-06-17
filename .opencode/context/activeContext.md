# Active Context

## Current Focus
Vulkan-Voxy — Vulkan 3D voxel engine with Voxy. LWJGL-based, 7-stage LOD pipeline.

## What Exists
- LOD pipeline with compute-based mesh generation
- GPU synchronization with explicit barriers
- C++-style systems architecture
- LWJGL Vulkan bindings
- Gradle build system

## Active Risks
- GPU synchronization bugs cause device lost errors
- Memory barriers between compute and graphics stages must be correct
- Native resource leaks if LWJGL resources not explicitly freed
