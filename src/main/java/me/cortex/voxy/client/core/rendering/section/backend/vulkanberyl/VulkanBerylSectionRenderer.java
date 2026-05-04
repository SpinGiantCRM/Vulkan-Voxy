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

        VulkanBerylSectionDrawPipeline.OpaqueDrawSubmission submission = this.drawPipeline.renderOpaque(renderer, viewport, this.geometryManager, renderList);
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
        lines.add("Vulkan/Beryl opaque visual debug enabled: " + this.drawPipeline.isDebugColourModeEnabled());
        lines.add("Vulkan/Beryl section draw pipeline ready: " + this.drawPipeline.isReady());
        lines.add("Vulkan/Beryl graphics pipeline created: " + this.drawPipeline.isGraphicsPipelineCreated());
        lines.add("Vulkan/Beryl cmdgen pipeline created: " + this.drawPipeline.isCommandGenPipelineCreated());
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
        VulkanBerylRenderBackendRuntime.SmokeStatus runtimeSmoke = VulkanBerylRenderBackendRuntime.getLastSmokeStatus();
        boolean drawSubmitted = this.lastSubmittedOpaqueDrawCommandCount > 0;
        boolean drawCommandSampleCompleted = !this.lastOpaqueSamplePending && this.lastSampledOpaqueCommandCount > 0;
        String failReason = smokeFailReason(runtimeSmoke, drawSubmitted, drawCommandSampleCompleted);
        if (failReason == null) {
            lines.add("Vulkan/Beryl smoke: PASS_DRAW_SUBMITTED visible=" + this.lastSubmittedOpaqueVisibleCount
                    + " cmds=" + this.lastSubmittedOpaqueDrawCommandCount
                    + " invalidRenderList=" + runtimeSmoke.renderListInvalidSampledEntries()
                    + " invalidCmds=" + this.lastInvalidSampledOpaqueCommandCount
                    + " debugColour=" + this.drawPipeline.isDebugColourModeEnabled());
        } else {
            lines.add("Vulkan/Beryl smoke: FAIL_" + failReason
                    + " visible=" + this.lastSubmittedOpaqueVisibleCount
                    + " cmds=" + this.lastSubmittedOpaqueDrawCommandCount
                    + " invalidRenderList=" + runtimeSmoke.renderListInvalidSampledEntries()
                    + " invalidCmds=" + this.lastInvalidSampledOpaqueCommandCount
                    + " debugColour=" + this.drawPipeline.isDebugColourModeEnabled());
        }
        lines.add("Vulkan/Beryl smoke detail: runtimeEntered=" + runtimeSmoke.runtimeEntered()
                + ", traversalPipeline=" + runtimeSmoke.traversalPipelineCreated()
                + ", traversalDescriptors=" + runtimeSmoke.traversalDescriptorsBound()
                + ", iter0Ran/skipped=" + runtimeSmoke.traversalDispatchIterationZeroRan() + "/" + runtimeSmoke.traversalDispatchIterationZeroSkipped()
                + ", indirectIterations=" + runtimeSmoke.traversalIndirectIterationsRan()
                + ", requestReadbackScheduled/completed=" + runtimeSmoke.requestReadbackScheduled() + "/" + runtimeSmoke.requestReadbackCompleted()
                + ", drawCmdSamplePending/completed=" + this.lastOpaqueSamplePending + "/" + drawCommandSampleCompleted);
        // Smoke test quick-run:
        // 1) set VOXY_VULKAN_BERYL_DEBUG_COLOUR=true
        // 2) select/use Vulkan/Beryl backend
        // 3) confirm this "Vulkan/Beryl smoke" line in the debug overlay/log
    }

    private String smokeFailReason(VulkanBerylRenderBackendRuntime.SmokeStatus runtimeSmoke, boolean drawSubmitted, boolean drawCommandSampleCompleted) {
        if (!runtimeSmoke.runtimeEntered()) return "NO_RUNTIME_WORK";
        if (!runtimeSmoke.traversalPipelineCreated()) return "NO_TRAVERSAL_PIPELINE";
        if (runtimeSmoke.renderListVisibleCount() <= 0) return "NO_RENDER_LIST_VISIBLE_ENTRIES";
        if (runtimeSmoke.renderListInvalidSampledEntries() > 0) return "RENDER_LIST_SAMPLE_INVALID";
        if (!this.drawPipeline.isGraphicsPipelineCreated()) return "NO_GRAPHICS_PIPELINE";
        if (!this.drawPipeline.isCommandGenPipelineCreated()) return "NO_CMDGEN_PIPELINE";
        if (!this.drawPipeline.isSceneUniformBound()) return "NO_SCENE_UNIFORM";
        if (!drawSubmitted) return "DRAW_SKIPPED";
        if (drawCommandSampleCompleted && this.lastInvalidSampledOpaqueCommandCount > 0) return "DRAW_COMMAND_SAMPLE_INVALID";
        return null;
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
        if (this.geometryManager == null) {
            throw new IllegalStateException("Vulkan/Beryl section geometry data is missing");
        }
        this.drawPipeline.ensureDrawPipeline();
        this.drawPipeline.ensureDrawResourcesBound(this.geometryManager, renderList);
    }
}
