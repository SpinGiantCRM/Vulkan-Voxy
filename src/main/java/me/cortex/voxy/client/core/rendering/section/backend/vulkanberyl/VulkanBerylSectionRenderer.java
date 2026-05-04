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
    private String lastOpaqueDrawMode = "none";
    private long lastSubmittedOpaqueQuadCount;
    private int lastSubmittedOpaqueDrawCommandCount;
    private int lastSampledOpaqueCommandCount;
    private int lastInvalidSampledOpaqueCommandCount;
    private long lastSampledOpaqueQuadCount = -1L;
    private boolean lastOpaqueSamplePending;
    private String lastOpaqueSkippedReason = "not_drawn";

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

        VulkanBerylSectionDrawPipeline.OpaqueDrawSubmission submission = this.drawPipeline.renderOpaque(renderer, viewport, this.geometryData, renderList);
        this.lastSubmittedOpaqueVisibleCount = submission.submittedVisibleCount();
        this.lastOpaqueDrawMode = submission.drawMode();
        this.lastSubmittedOpaqueQuadCount = submission.submittedQuadCount();
        this.lastSubmittedOpaqueDrawCommandCount = submission.submittedDrawCommandCount();
        this.lastSampledOpaqueCommandCount = submission.sampledCommandCount();
        this.lastInvalidSampledOpaqueCommandCount = submission.invalidSampledCommandCount();
        this.lastSampledOpaqueQuadCount = submission.sampledQuadCount();
        this.lastOpaqueSamplePending = submission.samplePending();
        this.lastOpaqueSkippedReason = submission.skippedReason() == null ? "none" : submission.skippedReason();
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
        this.drawPipeline.pollDebugReadback();
        this.lastOpaqueSamplePending = this.drawPipeline.isDebugSamplePending();
        lines.add("Vulkan/Beryl section renderer: opaque draw submission active");
        lines.add("Vulkan/Beryl section draw pipeline ready: " + this.drawPipeline.isReady());
        lines.add("Vulkan/Beryl opaque scene uniform bound: " + this.drawPipeline.isSceneUniformBound());
        lines.add("Vulkan/Beryl opaque depth sampling enabled: " + this.drawPipeline.isDepthSamplingEnabled());
        lines.add("Vulkan/Beryl opaque model/light path enabled: " + this.drawPipeline.isModelLightPathEnabled());
        lines.add("Vulkan/Beryl last opaque draw mode: " + this.lastOpaqueDrawMode);
        lines.add("Vulkan/Beryl last opaque submitted visible count: " + this.lastSubmittedOpaqueVisibleCount);
        lines.add("Vulkan/Beryl last opaque submitted draw command count: " + this.lastSubmittedOpaqueDrawCommandCount);
        lines.add("Vulkan/Beryl last opaque sampled command count: " + this.lastSampledOpaqueCommandCount);
        lines.add("Vulkan/Beryl last opaque invalid sampled commands: " + this.lastInvalidSampledOpaqueCommandCount);
        lines.add("Vulkan/Beryl last opaque sampled command pending: " + this.lastOpaqueSamplePending);
        lines.add("Vulkan/Beryl last opaque submitted quad count: " + this.lastSubmittedOpaqueQuadCount);
        lines.add("Vulkan/Beryl last opaque sampled quad count: " + this.lastSampledOpaqueQuadCount);
        lines.add("Vulkan/Beryl last opaque skipped reason: " + this.lastOpaqueSkippedReason);
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
