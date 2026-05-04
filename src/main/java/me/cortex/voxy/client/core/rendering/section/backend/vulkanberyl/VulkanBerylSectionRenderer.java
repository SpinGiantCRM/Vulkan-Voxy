package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;
import net.vulkanmod.vulkan.Renderer;

import java.util.List;

public final class VulkanBerylSectionRenderer extends AbstractSectionRenderer<VulkanBerylViewport, VulkanBerylSectionGeometryData> {
    public static final Factory<VulkanBerylViewport, VulkanBerylSectionGeometryData> FACTORY = Factory.create(VulkanBerylSectionRenderer.class);

    private final VulkanBerylSectionDrawPipeline drawPipeline;
    private boolean freed;
    private int lastSubmittedOpaqueVisibleCount;
    private boolean lastOpaqueDrawSkippedZeroCount = true;

    public VulkanBerylSectionRenderer(SectionRenderPipeline pipeline, ModelStore modelStore, VulkanBerylSectionGeometryData geometryData) {
        super(pipeline.getRenderProperties(), modelStore, geometryData);
        this.drawPipeline = new VulkanBerylSectionDrawPipeline();
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
        this.ensureDrawResources(renderList);
    }

    @Override
    public void renderOpaque(VulkanBerylViewport viewport) {
        this.requireActive();
        VulkanBerylViewportRenderList renderList = this.requireRenderList(viewport);
        this.validateRenderListLayout(renderList);
        this.ensureDrawResources(renderList);

        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL renderer is not initialized");
        }

        int submittedVisibleCount = this.drawPipeline.renderOpaque(renderer, viewport, this.geometryData, renderList);
        this.lastSubmittedOpaqueVisibleCount = submittedVisibleCount;
        this.lastOpaqueDrawSkippedZeroCount = submittedVisibleCount <= 0;
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
        this.drawPipeline.free();
        this.freed = true;
    }

    @Override
    public void addDebug(List<String> lines) {
        lines.add("Vulkan/Beryl section renderer: opaque draw submission active");
        lines.add("Vulkan/Beryl section draw pipeline ready: " + this.drawPipeline.isReady());
        lines.add("Vulkan/Beryl last opaque submitted visible count: " + this.lastSubmittedOpaqueVisibleCount);
        lines.add("Vulkan/Beryl last opaque draw skipped (count<=0): " + this.lastOpaqueDrawSkippedZeroCount);
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

    private void ensureDrawResources(VulkanBerylViewportRenderList renderList) {
        if (this.geometryData == null) {
            throw new IllegalStateException("Vulkan/Beryl section geometry data is missing");
        }
        this.drawPipeline.ensureDrawPipeline();
        this.drawPipeline.ensureDrawResourcesBound(this.geometryData, renderList);
    }
}
