package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;

import java.util.List;

public final class VulkanBerylSectionRenderer extends AbstractSectionRenderer<VulkanBerylViewport, VulkanBerylSectionGeometryData> {
    public static final Factory<VulkanBerylViewport, VulkanBerylSectionGeometryData> FACTORY = Factory.create(VulkanBerylSectionRenderer.class);

    private boolean freed;

    public VulkanBerylSectionRenderer(SectionRenderPipeline pipeline, ModelStore modelStore, VulkanBerylSectionGeometryData geometryData) {
        super(pipeline.getRenderProperties(), modelStore, geometryData);
    }

    @Override
    public VulkanBerylViewport createViewport() {
        return new VulkanBerylViewport(this.properties);
    }

    @Override
    public void buildDrawCalls(VulkanBerylViewport viewport) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL draw call build is not implemented yet");
    }

    @Override
    public void renderOpaque(VulkanBerylViewport viewport) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL opaque rendering is not implemented yet");
    }

    @Override
    public void renderTemporal(VulkanBerylViewport viewport) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL temporal rendering is not implemented yet");
    }

    @Override
    public void renderTranslucent(VulkanBerylViewport viewport) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL translucent rendering is not implemented yet");
    }

    @Override
    public void free() {
        this.freed = true;
    }

    @Override
    public void addDebug(List<String> lines) {
        lines.add("Vulkan/Beryl section renderer: initialized (rendering unimplemented)");
    }
}
