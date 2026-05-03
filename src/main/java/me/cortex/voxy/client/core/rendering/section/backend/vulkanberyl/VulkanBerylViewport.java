package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportRenderList;

public final class VulkanBerylViewport extends Viewport<VulkanBerylViewport> {
    private final VulkanBerylViewportRenderList renderList;

    public VulkanBerylViewport(RenderProperties properties) {
        super(properties);
        this.renderList = new VulkanBerylViewportRenderList();
    }

    public static VulkanBerylViewport require(Viewport<?> viewport) {
        if (!(viewport instanceof VulkanBerylViewport vulkanViewport)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL viewport required");
        }
        return vulkanViewport;
    }

    @Override
    protected void delete0() {
        this.renderList.free();
        super.delete0();
    }

    @Override
    public ViewportRenderList getRenderList() {
        return this.renderList;
    }
}
