# Decision Log

## [DEC-001] LWJGL for Vulkan bindings
- **Context:** Need Java-compatible Vulkan API access
- **Decision:** LWJGL 3 for Vulkan native bindings
- **Alternatives:** Vulkan4J (less maintained), JNI wrappers (too much boilerplate)
- **Consequence:** Native resources must be explicitly managed; GC cannot free Vulkan objects

## [DEC-002] Voxy as voxel data source
- **Context:** Need voxel data for rendering and LOD pipeline
- **Decision:** Use Voxy as the voxel data source/format
- **Alternatives:** Custom voxel format (more work), MagicaVoxel (read-only)
- **Consequence:** Voxy integration is the data foundation for all 7 LOD stages

## [DEC-003] C++-style systems architecture
- **Context:** Performance-critical rendering pipeline
- **Decision:** Data-oriented systems architecture (matching C++ ECS patterns)
- **Alternatives:** OOP entity hierarchy (cache misses), pure ECS library (framework lock-in)
- **Consequence:** More verbose Java code but better cache locality and GPU alignment
