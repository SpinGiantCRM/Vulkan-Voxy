package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;

public final class VulkanBerylViewport extends Viewport<VulkanBerylViewport> {
    public VulkanBerylViewport(RenderProperties properties) {
        super(properties);
    }

    @Override
    public GlBuffer getRenderList() {
        throw new UnsupportedOperationException("VULKANMOD_BERYL viewport render list is not implemented yet");
    }
}
