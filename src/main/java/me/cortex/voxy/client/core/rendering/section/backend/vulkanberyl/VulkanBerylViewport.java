package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportRenderList;

public final class VulkanBerylViewport extends Viewport<VulkanBerylViewport> {
    public VulkanBerylViewport(RenderProperties properties) {
        super(properties);
    }

    @Override
    public ViewportRenderList getRenderList() {
        throw new UnsupportedOperationException("VULKANMOD_BERYL viewport render list is not implemented yet");
    }
}
