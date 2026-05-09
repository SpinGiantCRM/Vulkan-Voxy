# Repo Map

Quick navigation for Vulkan-Voxy.

## Hot Vulkan/Beryl Files

### Core Runtime
| File | Purpose |
|------|---------|
| `src/main/java/.../vulkanberyl/VulkanBerylRenderBackendRuntime.java` | Main backend runtime initialization and frame lifecycle |
| `src/main/java/.../vulkanberyl/VulkanBerylTraversalExecutor.java` | GPU traversal dispatch and synchronization |
| `src/main/java/.../vulkanberyl/VulkanBerylSectionDrawPipeline.java` | Draw command pipeline setup |
| `src/main/java/.../vulkanberyl/VulkanBerylCmdgenDiagnostics.java` | Command generation diagnostics |

### Compute Shaders
| File | Purpose |
|------|---------|
| `src/main/resources/assets/voxy/shaders/vulkanberyl/hierarchical/traversal.comp` | Main hierarchy traversal kernel |
| `src/main/resources/assets/voxy/shaders/vulkanberyl/section/cmdgen.comp` | Command buffer generation |
| `src/main/resources/assets/voxy/shaders/vulkanberyl/section/cmdgen_no_drawcount_write.comp` | Cmdgen variant without draw count write |

### Draw Shaders
| File | Purpose |
|------|---------|
| `src/main/resources/assets/voxy/shaders/vulkanberyl/section/draw.vsh` | Vertex shader for section rendering |
| `src/main/resources/assets/voxy/shaders/vulkanberyl/section/draw.fsh` | Fragment shader for section rendering |
| `src/main/resources/assets/voxy/shaders/vulkanberyl/section/draw_debug.fsh` | Debug variant of fragment shader |

## Reference Code (Keep Intact)
- MDIC/OpenGL backend code is reference material for the Vulkan port
- Do not delete or refactor unless explicitly requested
