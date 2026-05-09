# Runtime Debug Playbook

Diagnostic-first approach for Vulkan-Voxy issues.

## Before Adding New Diagnostics

1. Check existing diagnostics in `VulkanBeryl*Diagnostics.java` files
2. Check existing probe shaders in `shaders/vulkanberyl/section/cmdgen_*` variants
3. Check environment flags (search for `System.getenv` or `Boolean.getBoolean`)

## Diagnostic Files

| Class | Use Case |
|-------|----------|
| `VulkanBerylCmdgenDiagnostics.java` | Command generation issues |
| `VulkanBerylLodBringupDiagnostics.java` | LOD bringup problems |
| `VulkanBerylDebugLog.java` | General debug logging |

## Shader Probe Variants

Multiple `cmdgen_*.comp` variants exist for testing different buffer layouts:
- `cmdgen_binding0_uint_read.comp`
- `cmdgen_full_layout_binding1_uint_read.comp`
- `cmdgen_no_drawcount_write.comp`
- ...and others

Prefer updating or enabling an existing probe over creating new shader variants.

## Environment Flags

Search for runtime flags before adding new ones. Common patterns:
- `System.getenv("VOXY_DEBUG_...")`
- `Boolean.getBoolean("voxy.debug...")`

## When to Add New

Only add new environment flags or diagnostic shaders when:
- Existing diagnostics cannot expose the issue
- The issue is recurring and needs long-term tracking
- Explicitly requested
