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
        this.requireActive();
        VulkanBerylViewportRenderList renderList = this.requireRenderList(viewport);
        this.validateRenderListLayout(renderList);
    }

    @Override
    public void renderOpaque(VulkanBerylViewport viewport) {
        this.requireActive();
        this.validateRenderListLayout(this.requireRenderList(viewport));
    }

    @Override
    public void renderTemporal(VulkanBerylViewport viewport) {
        this.requireActive();
        this.requireRenderList(viewport);
    }

    @Override
    public void renderTranslucent(VulkanBerylViewport viewport) {
        this.requireActive();
        this.requireRenderList(viewport);
    }

    @Override
    public void free() {
        this.freed = true;
    }

    @Override
    public void addDebug(List<String> lines) {
        lines.add("Vulkan/Beryl section renderer: initialized (draw submission pending)");
    }

    private void requireActive() {
        if (this.freed) {
            throw new IllegalStateException("Cannot render with a freed Vulkan/Beryl section renderer");
        }
    }

    private VulkanBerylViewportRenderList requireRenderList(VulkanBerylViewport viewport) {
        if (viewport == null) {
            throw new IllegalArgumentException("Viewport must not be null");
        }
        return VulkanBerylViewportRenderList.require(viewport.getRenderList());
    }

    private void validateRenderListLayout(VulkanBerylViewportRenderList renderList) {
        long sizeBytes = renderList.getBuffer().getBufferSize();
        if (sizeBytes < Integer.BYTES) {
            throw new IllegalStateException("Vulkan/Beryl render list buffer is structurally invalid (" + sizeBytes + " bytes)");
        }
    }
}
