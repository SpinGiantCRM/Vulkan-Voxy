# Vulkan-Voxy

> **Status: Experimental / Unstable / Work in Progress**
>
> Vulkan-Voxy is currently an early experimental port and is not production-ready.

Vulkan-Voxy is an unofficial experimental fork of Voxy. Original Voxy is created by MCRcortex. This fork is not affiliated with, endorsed by, or supported by the original Voxy project.

## Purpose

This fork is focused on adapting Voxy toward VulkanMod/Beryl/no-Sodium testing workflows.

## Current runtime requirements

- Minecraft **26.1.2** instance using **Fabric Loader 0.18.6 or newer**
- **Fabric API 0.145.3+26.1.1 or newer**
- **VulkanMod 0.6.5 or newer**
- **Beryl 0.1.3-alpha+1 or newer** (if separate in your runtime/modpack)
- **Vulkan-Voxy** jar
- **No Sodium** for current Vulkan/Beryl testing

## Current known status

- Early Vulkan/Beryl port
- Rendering may crash or fail to display LoDs
- Not suitable as a stable replacement for Voxy

## Developer checks

- Run `scripts/check_voxy_shader_contracts.sh` after touching shared LoD shader imports. It pins original Voxy shader contracts that must remain exact matches for the MDIC/OpenGL reference path, and checks compatibility guards for shared files that intentionally differ for Vulkan/Beryl.

## Support and updates

This fork is experimental and may not receive future updates or support.

## Attribution

- Original project: [Voxy](https://github.com/MCRcortex/voxy)
- Original author: MCRcortex

## License and rights

See [LICENSE.md](LICENSE.md) and [NOTICE.md](NOTICE.md) for licensing, attribution, and rights boundaries.
