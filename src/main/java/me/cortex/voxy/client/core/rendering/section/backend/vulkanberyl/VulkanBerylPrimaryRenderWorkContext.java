package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.section.backend.PrimaryRenderWorkContext;

import java.util.Objects;

public record VulkanBerylPrimaryRenderWorkContext(VulkanBerylRenderFrameContext frame) implements PrimaryRenderWorkContext {
    public VulkanBerylPrimaryRenderWorkContext {
        Objects.requireNonNull(frame, "frame");
    }
}
