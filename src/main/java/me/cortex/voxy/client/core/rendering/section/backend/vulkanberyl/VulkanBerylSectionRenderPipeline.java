package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundsRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.RenderBackendStateGuard;
import me.cortex.voxy.client.core.rendering.section.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.section.backend.RenderViewportSize;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.lwjgl.vulkan.VkExtent2D;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class VulkanBerylSectionRenderPipeline implements SectionRenderPipeline {
    private static final float[] RENDER_SCALING_FACTOR = new float[] {1.0f, 1.0f};
    private static final boolean FORCE_SYNC_ONLY_SECTION_PIPELINE = Boolean.getBoolean("voxy.vulkanberyl.forceSyncOnlySectionPipeline");
    private static final boolean CPU_CLEAR_RENDER_LIST_COUNTER = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_CPU_CLEAR_RENDER_LIST_COUNTER", false);
    private static final long TIMING_LOG_INTERVAL_NANOS = 1_000_000_000L;

    private final RenderProperties properties;
    private final SectionRenderBackendRuntime backendRuntime;
    private final BooleanSupplier frexSupplier;

    private AbstractSectionRenderer<?, ?> sectionRenderer;
    private boolean freed;
    private long runPipelineEnterCount;
    private long doPrimaryWorkCompletedCount;
    private long buildDrawCallsAttemptedCount;
    private long renderOpaqueAttemptedCount;
    private String lastRenderPhaseReached = "none";
    private String lastRenderFailure = "none";
    private double lastTraversalCpuMs;
    private double lastRenderListCpuMs;
    private double lastSectionDrawCpuMs;
    private double lastDiagnosticCpuMs;
    private double lastGeometryUpdateCpuMs;

    public VulkanBerylSectionRenderPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier) {
        this.properties = properties;
        this.backendRuntime = backendRuntime;
        this.frexSupplier = frexSupplier;
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        // No-op for now until Vulkan/Beryl requires additional model bake data.
    }

    @Override
    public void setSectionRenderer(AbstractSectionRenderer<?, ?> sectionRenderer) {
        if (this.sectionRenderer != null) {
            throw new IllegalStateException("Section renderer is already set");
        }
        this.sectionRenderer = Objects.requireNonNull(sectionRenderer, "sectionRenderer");
    }

    @Override
    public float[] getRenderScalingFactor() {
        return RENDER_SCALING_FACTOR;
    }

    @Override
    public RenderProperties getRenderProperties() {
        return this.properties;
    }

    @Override
    public RenderViewportSize getRenderViewportSize() {
        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        int width = extent.width();
        int height = extent.height();

        return new RenderViewportSize(0, 0, width, height);
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        if (this.freed) {
            throw new IllegalStateException("VULKANMOD_BERYL pipeline is freed");
        }
        if (this.sectionRenderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL section renderer is not set");
        }
        VulkanBerylViewport vulkanViewport = VulkanBerylViewport.require(viewport);
        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        this.requireCompatibleViewport(vulkanViewport, extent.width(), extent.height());
    }

    @Override
    public RenderFrameContext enterRenderFrame(Viewport<?> viewport) {
        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        int width = extent.width();
        int height = extent.height();
        this.requireCompatibleViewport(viewport, width, height);

        return new VulkanBerylRenderFrameContext(renderer, swapChain, width, height);
    }


    @Override
    public RenderBackendStateGuard enterFrameStateGuard() {
        return RenderBackendStateGuard.NO_OP;
    }

    @Override
    public ChunkBoundsRenderer createChunkBoundsRenderer() {
        return new VulkanBerylChunkBoundsRenderer();
    }

    @Override
    public void runPreMainDepthPass(Viewport<?> viewport, ChunkBoundsRenderer chunkBoundRenderer) {
        if (this.freed) {
            throw new IllegalStateException("VULKANMOD_BERYL pipeline is freed");
        }
        if (this.sectionRenderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL section renderer is not set");
        }
        VulkanBerylViewport vulkanViewport = VulkanBerylViewport.require(viewport);
        if (!(chunkBoundRenderer instanceof VulkanBerylChunkBoundsRenderer)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL requires VulkanBerylChunkBoundsRenderer");
        }

        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        this.requireCompatibleViewport(vulkanViewport, extent.width(), extent.height());

        // MDIC uses this hook for an OpenGL chunk-bound/depth-bound pass. Vulkan/Beryl does not yet have an
        // equivalent depth-bound resource, so this is intentionally a validated no-op until a real pre-pass exists.
    }

    @Override
    public void runPipeline(Viewport<?> viewport, RenderFrameContext frame) {
        this.runPipelineEnterCount++;
        this.lastRenderPhaseReached = "runPipeline_enter";
        this.lastRenderFailure = "none";
        if (this.freed) {
            throw new IllegalStateException("VULKANMOD_BERYL pipeline is freed");
        }
        if (this.sectionRenderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL section renderer is not set");
        }
        VulkanBerylViewport vulkanViewport = VulkanBerylViewport.require(viewport);
        if (!(frame instanceof VulkanBerylRenderFrameContext vulkanFrame)) {
            throw new IllegalArgumentException("Expected VulkanBerylRenderFrameContext");
        }

        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        this.requireCompatibleViewport(vulkanViewport, extent.width(), extent.height());
        VulkanBerylViewportRenderList renderList = VulkanBerylViewportRenderList.require(vulkanViewport.getRenderList());
        long renderListStartNanos = System.nanoTime();
        if (CPU_CLEAR_RENDER_LIST_COUNTER) {
            renderList.clearCounter();
        } else {
            VulkanBerylDebugLog.once("render-list-cpu-clear-disabled",
                    "Vulkan/Beryl CPU render-list counter clear disabled: env=VOXY_VULKAN_BERYL_CPU_CLEAR_RENDER_LIST_COUNTER=false, traversal command buffer clear remains active");
        }
        long renderListEndNanos = System.nanoTime();
        this.lastRenderPhaseReached = "renderList_cleared";

        try {
            long traversalStartNanos = System.nanoTime();
            this.backendRuntime.doPrimaryWork(vulkanViewport, new VulkanBerylPrimaryRenderWorkContext(vulkanFrame), this.frexSupplier);
            long traversalEndNanos = System.nanoTime();
            this.doPrimaryWorkCompletedCount++;
            this.lastRenderPhaseReached = "doPrimaryWork_completed";

            long buildStartNanos = traversalEndNanos;
            long buildEndNanos = buildStartNanos;
            long opaqueStartNanos = buildEndNanos;
            long opaqueEndNanos = opaqueStartNanos;
            long translucentStartNanos = opaqueEndNanos;
            long translucentEndNanos = translucentStartNanos;
            long temporalStartNanos = translucentEndNanos;
            long temporalEndNanos = temporalStartNanos;
            if (!FORCE_SYNC_ONLY_SECTION_PIPELINE) {
                @SuppressWarnings("unchecked")
                AbstractSectionRenderer<VulkanBerylViewport, ?> activeSectionRenderer = (AbstractSectionRenderer<VulkanBerylViewport, ?>) this.sectionRenderer;
                this.buildDrawCallsAttemptedCount++;
                buildStartNanos = System.nanoTime();
                activeSectionRenderer.buildDrawCalls(vulkanViewport);
                buildEndNanos = System.nanoTime();
                this.lastRenderPhaseReached = "buildDrawCalls_completed";
                this.renderOpaqueAttemptedCount++;
                opaqueStartNanos = System.nanoTime();
                activeSectionRenderer.renderOpaque(vulkanViewport);
                opaqueEndNanos = System.nanoTime();
                this.lastRenderPhaseReached = "renderOpaque_completed";
                translucentStartNanos = System.nanoTime();
                activeSectionRenderer.renderTranslucent(vulkanViewport);
                translucentEndNanos = System.nanoTime();
                this.lastRenderPhaseReached = "renderTranslucent_completed";
                temporalStartNanos = System.nanoTime();
                activeSectionRenderer.renderTemporal(vulkanViewport);
                temporalEndNanos = System.nanoTime();
                this.lastRenderPhaseReached = "renderTemporal_completed";
            } else {
                this.lastRenderPhaseReached = "syncOnly_forced";
            }
            this.lastRenderListCpuMs = nanosToMillis((renderListEndNanos - renderListStartNanos) + (buildEndNanos - buildStartNanos));
            this.lastTraversalCpuMs = nanosToMillis(traversalEndNanos - traversalStartNanos);
            this.lastSectionDrawCpuMs = nanosToMillis((opaqueEndNanos - opaqueStartNanos) + (translucentEndNanos - translucentStartNanos) + (temporalEndNanos - temporalStartNanos));
            this.lastDiagnosticCpuMs = VulkanBerylSectionDrawPipeline.getLastDiagnosticCpuMs();
            this.lastGeometryUpdateCpuMs = VulkanBerylRenderBackendRuntime.getLastGeometryUpdateCpuMs();
            logFrameTimings();
        } catch (RuntimeException | Error ex) {
            this.lastRenderFailure = this.lastRenderPhaseReached + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            this.lastRenderPhaseReached = "failed";
            throw ex;
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl forceSyncOnlySectionPipeline: " + FORCE_SYNC_ONLY_SECTION_PIPELINE);
        debug.add("Vulkan/Beryl runPipeline enter count: " + this.runPipelineEnterCount);
        debug.add("Vulkan/Beryl doPrimaryWork completed count: " + this.doPrimaryWorkCompletedCount);
        debug.add("Vulkan/Beryl buildDrawCalls attempted count: " + this.buildDrawCallsAttemptedCount);
        debug.add("Vulkan/Beryl renderOpaque attempted count: " + this.renderOpaqueAttemptedCount);
        debug.add("Vulkan/Beryl last render phase reached: " + this.lastRenderPhaseReached);
        debug.add("Vulkan/Beryl last render exception/failure: " + this.lastRenderFailure);
        debug.add("Vulkan/Beryl CPU timings ms: traversal=" + formatMs(this.lastTraversalCpuMs)
                + ", renderList=" + formatMs(this.lastRenderListCpuMs)
                + ", sectionDraw=" + formatMs(this.lastSectionDrawCpuMs)
                + ", diagnostic=" + formatMs(this.lastDiagnosticCpuMs)
                + ", geometryUpdate=" + formatMs(this.lastGeometryUpdateCpuMs));
        if (FORCE_SYNC_ONLY_SECTION_PIPELINE) {
            debug.add("Vulkan/Beryl section pipeline: sync-only fallback forced via -Dvoxy.vulkanberyl.forceSyncOnlySectionPipeline=true");
        } else {
            this.sectionRenderer.addDebug(debug);
            this.backendRuntime.addDebug(debug);
            debug.add("Vulkan/Beryl section pipeline: primary traversal + section renderer draw path active");
        }
    }

    @Override
    public void tickPostFrameUploads() {
        // Vulkan/Beryl upload ticking will be implemented with the real Vulkan upload path later.
    }

    @Override
    public void free() {
        this.freed = true;
    }

    private Renderer requireRenderer() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL renderer is not initialized");
        }
        return renderer;
    }

    private SwapChain requireSwapChain(Renderer renderer) {
        SwapChain swapChain = renderer.getSwapChain();
        if (swapChain == null || !swapChain.hasImages()) {
            throw new IllegalStateException("VULKANMOD_BERYL swapchain is unavailable");
        }
        return swapChain;
    }

    private VkExtent2D requireExtent(SwapChain swapChain) {
        VkExtent2D extent = swapChain.getExtent();
        if (extent == null) {
            throw new IllegalStateException("VULKANMOD_BERYL swapchain extent is unavailable");
        }
        return extent;
    }

    private void requireValidExtent(VkExtent2D extent) {
        int width = extent.width();
        int height = extent.height();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("VULKANMOD_BERYL swapchain extent is invalid: " + width + "x" + height);
        }
    }

    private void requireCompatibleViewport(Viewport<?> viewport, int width, int height) {
        if (viewport.width != width || viewport.height != height) {
            throw new IllegalStateException("VULKANMOD_BERYL viewport/swapchain extent mismatch: viewport=" +
                    viewport.width + "x" + viewport.height + ", swapchain=" + width + "x" + height);
        }
    }

    private void logFrameTimings() {
        VulkanBerylDebugLog.rateLimited("vulkanberyl-frame-cpu-timings",
                "Vulkan/Beryl CPU timings: traversalCpuMs=" + formatMs(this.lastTraversalCpuMs)
                        + ", renderListCpuMs=" + formatMs(this.lastRenderListCpuMs)
                        + ", sectionDrawCpuMs=" + formatMs(this.lastSectionDrawCpuMs)
                        + ", diagnosticCpuMs=" + formatMs(this.lastDiagnosticCpuMs)
                        + ", geometryUpdateCpuMs=" + formatMs(this.lastGeometryUpdateCpuMs)
                        + ", cpuRenderListClearEnabled=" + CPU_CLEAR_RENDER_LIST_COUNTER
                        + ", forceSyncOnlySectionPipeline=" + FORCE_SYNC_ONLY_SECTION_PIPELINE,
                20, TIMING_LOG_INTERVAL_NANOS);
    }

    private static double nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000.0;
    }

    private static String formatMs(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
