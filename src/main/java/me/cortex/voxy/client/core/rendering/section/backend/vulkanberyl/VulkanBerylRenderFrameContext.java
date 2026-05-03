package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.section.backend.RenderFrameContext;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;

public record VulkanBerylRenderFrameContext(
        Renderer renderer,
        SwapChain swapChain,
        int sourceWidth,
        int sourceHeight
) implements RenderFrameContext {
    @Override
    public void close() {
        // No-op: VulkanMod currently owns frame lifecycle management.
    }
}
