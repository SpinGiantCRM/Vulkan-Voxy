package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import static me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl.VulkanBerylCmdgenDiagnostics.TRAVERSAL_STAGE_LIMIT;
import static me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl.VulkanBerylCmdgenDiagnostics.traversalStageMeaning;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.backend.PrimaryRenderWorkContext;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;

import java.util.List;
import java.util.function.BooleanSupplier;

public final class VulkanBerylRenderBackendRuntime implements SectionRenderBackendRuntime {
    public record FrameSafetyState(boolean allowCmdGen, boolean allowIndirectDraw, String reason) {}
    public record SmokeStatus(
            boolean runtimeEntered,
            boolean traversalPipelineCreated,
            boolean traversalDescriptorsBound,
            boolean traversalDispatchIterationZeroRan,
            boolean traversalDispatchIterationZeroSkipped,
            int traversalIndirectIterationsRan,
            boolean requestReadbackScheduled,
            boolean requestReadbackCompleted,
            int renderListVisibleCount,
            int renderListInvalidSampledEntries
    ) {}

    private static volatile SmokeStatus LAST_SMOKE_STATUS = new SmokeStatus(false, false, false, false, false, 0, false, false, -1, 0);
    private static volatile FrameSafetyState LAST_FRAME_SAFETY_STATE = new FrameSafetyState(false, false, "waiting_for_valid_render_list_readback");
    private static final boolean ENABLE_TRAVERSAL_DISPATCH = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_ENABLE_TRAVERSAL_DISPATCH", true);
    private static final boolean ENABLE_INITIAL_TRAVERSAL_DISPATCH = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_ENABLE_INITIAL_TRAVERSAL_DISPATCH", true);
    private static final boolean ENABLE_INDIRECT_TRAVERSAL_DISPATCH = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_TRAVERSAL_DISPATCH", false);
    private static final int TRAVERSAL_MAX_ITERATIONS = VulkanBerylEnvironment.intValue("VOXY_VULKAN_BERYL_TRAVERSAL_MAX_ITERATIONS", 1, 1, VulkanBerylTraversalResources.MAX_ITERATIONS);
    private static final boolean TRAVERSAL_SMOKE_NOOP = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_TRAVERSAL_SMOKE_NOOP", false);
    private static final boolean TRAVERSAL_SMOKE_WRITE_KNOWN = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_TRAVERSAL_SMOKE_WRITE_KNOWN", false);
    private static final boolean TRAVERSAL_SHADER_SMOKE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_TRAVERSAL_SHADER_SMOKE", false);
    private static final boolean TRAVERSAL_SHADER_UNIFORM_SMOKE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_TRAVERSAL_SHADER_UNIFORM_SMOKE", false);
    private static final boolean TRAVERSAL_FORCE_REAL_MAIN_RETURN = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_TRAVERSAL_FORCE_REAL_MAIN_RETURN", false);
    private static final boolean TRAVERSAL_STATIC_IMPORT_LEVEL_ACTIVE = isTraversalStaticImportLevelActive();
    private static final boolean RENDERLIST_SMOKE_ONE_ENTRY = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_RENDERLIST_SMOKE_ONE_ENTRY", false);
    private static final boolean ENABLE_CMDGEN_DISPATCH = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_ENABLE_CMDGEN_DISPATCH", false);
    private static final boolean ENABLE_INDIRECT_DRAW = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_DRAW", false);
    private static final int FULL_TRAVERSAL_STAGE_LIMIT = 7;
    private static final int RENDER_LIST_SAMPLE_LIMIT = 64;
    private static final int RENDER_LIST_DEBUG_FIRST_IDS = 8;
    private static final String RENDER_LIST_COUNTER_SOURCE_BUFFER = "voxy_vulkanberyl_render_list";
    private static final int RENDER_LIST_COUNTER_READBACK_SENTINEL = 0x7F51C0DE;
    private static final int RENDER_LIST_COUNTER_READBACK_USAGE = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final int RENDER_LIST_READBACK_DIAGNOSTIC_NONE = 0;
    private static final int RENDER_LIST_READBACK_DIAGNOSTIC_DESTINATION_FILL = 1;
    private static final int RENDER_LIST_READBACK_DIAGNOSTIC_SOURCE_COPY = 2;
    private static final int RENDER_LIST_READBACK_DESTINATION_FILL_EXPECTED = 0x12345678;
    private final AsyncNodeManager nodeManager;
    private final RenderGenerationService renderGen;
    private final VulkanBerylNodeMetadataStore nodeMetadataStore;
    private final VulkanBerylTopLevelNodeStore topLevelNodeStore;
    private final VulkanBerylNodeCleanupSink nodeCleanupSink;
    private final VulkanBerylTraversalResources traversalResources;
    private final Buffer requestReadbackBuffer;
    private final Buffer renderListCounterReadbackBuffer;
    private final Buffer renderListSampleReadbackBuffer;
    private boolean requestReadbackPending;
    private boolean renderListCounterReadbackPending;
    private boolean renderListSampleReadbackPending;
    private VulkanBerylViewportRenderList pendingRenderListCounterSource;
    private VulkanBerylViewportRenderList pendingRenderListSampleSource;
    private VulkanBerylSectionGeometryData pendingRenderListSampleGeometry;
    private int lastVisibleSectionCount = -1;
    private int lastRawVisibleSectionCount = -1;
    private int lastVisibleSectionCapacity = -1;
    private boolean lastRenderListPopulationThisFrame;
    private boolean lastRenderListReadbackScheduledThisFrame;
    private boolean lastRenderListReadbackValid;
    private String lastRenderListReadbackReason = "not_scheduled";
    private int lastSampledRenderListEntryCount;
    private int lastInvalidSampledRenderListEntryCount;
    private int lastSampledVisibleSectionCount = -1;
    private int previousRawRenderListVisibleCount = -1;
    private String lastSampledRenderListFirstEntries = "[]";
    private VulkanBerylTraversalExecutor traversalExecutor;
    private boolean freed;
    private boolean runtimeEntered;
    private boolean requestReadbackScheduled;
    private boolean requestReadbackCompleted;
    private boolean lastRequestReadbackDiscarded;
    private String lastRequestBatchDiscardReason = "none";
    private boolean lastRenderListCounterDiscarded;
    private boolean lastRenderListSampleDiscarded;
    private int frameSequence;
    private long requestReadbackFrameId = -1;
    private long renderListReadbackFrameId = -1;
    private int renderListReadbackRendererFrameSlot = -1;
    private long renderListReadbackRecordedCommandBufferAddress;
    private int renderListCounterReadbackDiagnosticMode = RENDER_LIST_READBACK_DIAGNOSTIC_NONE;
    private boolean renderListDestinationFillSmokeSucceeded;
    private boolean renderListSourceCopySmokeSucceeded;
    private int lastRenderListDestinationFillObserved = RENDER_LIST_COUNTER_READBACK_SENTINEL;
    private int lastRenderListSourceCopyObserved = RENDER_LIST_COUNTER_READBACK_SENTINEL;
    private long frameId;

    public VulkanBerylRenderBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeManager = nodeManager;
        this.renderGen = renderGen;
        this.nodeMetadataStore = new VulkanBerylNodeMetadataStore(nodeManager.maxNodeCount);
        this.topLevelNodeStore = new VulkanBerylTopLevelNodeStore();
        this.nodeCleanupSink = new VulkanBerylNodeCleanupSink();
        this.traversalResources = new VulkanBerylTraversalResources();
        this.requestReadbackBuffer = new Buffer("voxy_vulkanberyl_traversal_request_readback", VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.HOST_MEM);
        this.requestReadbackBuffer.createBuffer(VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES);
        this.renderListCounterReadbackBuffer = new Buffer("voxy_vulkanberyl_render_list_count_readback", RENDER_LIST_COUNTER_READBACK_USAGE, MemoryTypes.HOST_MEM);
        this.renderListCounterReadbackBuffer.createBuffer(Integer.BYTES);
        this.renderListSampleReadbackBuffer = new Buffer("voxy_vulkanberyl_render_list_sample_readback", VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.HOST_MEM);
        this.renderListSampleReadbackBuffer.createBuffer(Integer.BYTES + (long) RENDER_LIST_SAMPLE_LIMIT * Integer.BYTES);
        this.nodeManager.setTLNAddRemoveCallbacks(this.topLevelNodeStore::addTopLevelNode, this.topLevelNodeStore::removeTopLevelNode);
    }

    @Override
    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        // No-op until a Vulkan/Beryl section render pipeline exists.
    }

    @Override
    public void doPrimaryWork(Viewport<?> viewport, PrimaryRenderWorkContext workContext, BooleanSupplier frexStillHasWork) {
        this.runtimeEntered = true;
        if (this.freed) {
            throw new IllegalStateException("Cannot execute runtime work after free");
        }
        if (!(workContext instanceof VulkanBerylPrimaryRenderWorkContext)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL runtime requires VulkanBerylPrimaryRenderWorkContext");
        }
        VulkanBerylViewport vulkanViewport = VulkanBerylViewport.require(viewport);
        VulkanBerylViewportRenderList renderList = VulkanBerylViewportRenderList.require(vulkanViewport.getRenderList());
        VulkanBerylPrimaryRenderWorkContext vulkanWorkContext = (VulkanBerylPrimaryRenderWorkContext) workContext;

        this.submitPendingRequestReadback();
        this.submitPendingRenderListCounterReadback();
        this.submitPendingRenderListSampleReadback();

        do {
            this.nodeManager.tick(this.nodeMetadataStore, this.nodeCleanupSink);
        } while (frexStillHasWork.getAsBoolean());

        int topNodeCount = this.topLevelNodeStore.getTopNodeCount();
        VkCommandBuffer commandBuffer = vulkanWorkContext.frame().renderer().getCommandBuffer();
        VulkanBerylTraversalResources.FrameInitStats frameInit = this.traversalResources.recordTraversalFrameInitialization(
                commandBuffer,
                renderList,
                this.topLevelNodeStore,
                topNodeCount,
                TRAVERSAL_SMOKE_WRITE_KNOWN
        );
        VulkanBerylDebugLog.trace("traversal-frame-init", "Traversal frame init: mode=command_buffer topNodeCount=" + topNodeCount
                + " firstDispatchSize=" + frameInit.firstDispatchSize()
                + " queueMetaInitialized=" + frameInit.queueMetaInitialized()
                + " scratchQueueASeededCount=" + frameInit.scratchQueueASeededCount()
                + " requestCounterCleared=" + frameInit.requestCounterCleared()
                + " renderListCounterCleared=" + frameInit.renderListCounterCleared()
                + " transferToComputeBarrier=" + frameInit.transferToComputeBarrier());
        boolean explicitNoGpuDrawCountCmdgenPath = ENABLE_CMDGEN_DISPATCH && VulkanBerylCmdgenDiagnostics.CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER;
        int activeTraversalStageLimit = explicitNoGpuDrawCountCmdgenPath && TRAVERSAL_STAGE_LIMIT == 0 ? FULL_TRAVERSAL_STAGE_LIMIT : TRAVERSAL_STAGE_LIMIT;
        logTraversalUniformUploadDiagnostics(vulkanViewport, renderList, this.renderGen, this.nodeManager.maxNodeCount, TRAVERSAL_SHADER_UNIFORM_SMOKE, activeTraversalStageLimit);
        this.traversalResources.recordTraversalUniformUpload(commandBuffer, vulkanViewport, renderList, this.topLevelNodeStore, this.renderGen, this.nodeManager.maxNodeCount, TRAVERSAL_SHADER_UNIFORM_SMOKE, activeTraversalStageLimit);
        if (this.traversalExecutor != null && this.traversalExecutor.getRenderList() != renderList) {
            this.traversalExecutor.free();
            this.traversalExecutor = null;
            VulkanBerylDebugLog.once("traversal-render-list-descriptor-recreated", "Recreated traversal executor after render-list buffer changed: renderListBufferId=" + renderList.getBuffer().getId() + " renderListBufferSizeBytes=" + renderList.getBuffer().getBufferSize());
        }
        if (this.traversalExecutor == null) {
            this.traversalExecutor = new VulkanBerylTraversalExecutor(
                    this.traversalResources,
                    this.nodeMetadataStore,
                    this.topLevelNodeStore,
                    renderList,
                    null
            );
        }
        boolean initialTraversalDispatch = false;
        int remainingTraversalDispatchesRan = 0;
        boolean traversalReadbacksScheduled = false;
        boolean traversalDispatchAllowed = false;
        String renderListPopulationBlocker = "not_evaluated";
        this.lastRenderListPopulationThisFrame = false;
        this.lastRenderListReadbackScheduledThisFrame = false;
        if (TRAVERSAL_SMOKE_NOOP) {
            renderListPopulationBlocker = "traversal_smoke_noop";
            this.scheduleRequestReadback(commandBuffer);
            this.scheduleRenderListCounterReadback(commandBuffer, renderList);
            this.scheduleRenderListSampleReadback(commandBuffer, renderList);
            traversalReadbacksScheduled = true;
            this.lastRenderListPopulationThisFrame = TRAVERSAL_SMOKE_WRITE_KNOWN;
            VulkanBerylDebugLog.once("traversal-smoke-noop-active", "Traversal smoke mode active: noop=true writeKnown=" + TRAVERSAL_SMOKE_WRITE_KNOWN + " traversal shader dispatch skipped");
        } else if ((traversalDispatchAllowed = isTraversalDispatchAllowed(explicitNoGpuDrawCountCmdgenPath, activeTraversalStageLimit))) {
            renderListPopulationBlocker = "ready";
            this.traversalExecutor.prepareTraversal(vulkanViewport);
            this.traversalExecutor.ensureTraversalPipeline(TRAVERSAL_SHADER_SMOKE || TRAVERSAL_SHADER_UNIFORM_SMOKE);
            if (TRAVERSAL_SHADER_SMOKE || TRAVERSAL_SHADER_UNIFORM_SMOKE) { VulkanBerylDebugLog.once("traversal-shader-smoke-active", "Traversal shader smoke dispatch active"); }
            this.traversalExecutor.ensureTraversalDescriptorsBound();
            this.traversalExecutor.requireDispatchSupport();
            if (ENABLE_INITIAL_TRAVERSAL_DISPATCH) {
                this.traversalExecutor.dispatchFirstTraversalIteration(vulkanWorkContext.frame().renderer());
                initialTraversalDispatch = this.traversalExecutor.didDispatchIterationZeroRun();
            } else {
                renderListPopulationBlocker = firstRenderListPopulationBlocker(renderListPopulationBlocker, "initial_traversal_dispatch_disabled");
                VulkanBerylDebugLog.once("initial-traversal-dispatch-skipped", "Initial traversal dispatch skipped by safety gate");
            }
            if (ENABLE_INDIRECT_TRAVERSAL_DISPATCH && TRAVERSAL_MAX_ITERATIONS > 1) {
                this.traversalExecutor.dispatchRemainingTraversalIterations(vulkanWorkContext.frame().renderer(), TRAVERSAL_MAX_ITERATIONS);
                remainingTraversalDispatchesRan = this.traversalExecutor.getIndirectDispatchIterationCount();
            } else {
                VulkanBerylDebugLog.once("traversal-remaining-iterations-skipped", "Traversal remaining iterations skipped by safety gate");
            }
            if (topNodeCount <= 0) {
                renderListPopulationBlocker = firstRenderListPopulationBlocker(renderListPopulationBlocker, "no_top_nodes");
            } else if (frameInit.scratchQueueASeededCount() <= 0) {
                renderListPopulationBlocker = firstRenderListPopulationBlocker(renderListPopulationBlocker, "scratch_queue_not_seeded");
            }
            if (initialTraversalDispatch || remainingTraversalDispatchesRan > 0) {
                this.scheduleRequestReadback(commandBuffer);
                this.scheduleRenderListCounterReadback(commandBuffer, renderList);
                this.scheduleRenderListSampleReadback(commandBuffer, renderList);
                traversalReadbacksScheduled = true;
                this.lastRenderListPopulationThisFrame = true;
                renderListPopulationBlocker = "none";
            } else {
                renderListPopulationBlocker = firstRenderListPopulationBlocker(renderListPopulationBlocker, "dispatch_not_recorded");
            }
        } else {
            renderListPopulationBlocker = traversalDispatchBlocker(explicitNoGpuDrawCountCmdgenPath, activeTraversalStageLimit);
            VulkanBerylDebugLog.traceOnce("traversal-dispatch-disabled", "Traversal dispatch disabled by safety gate/stage limit; skipping real traversal dispatches and traversal readbacks: renderListPopulationBlocker=" + renderListPopulationBlocker);
        }
        this.lastRenderListReadbackScheduledThisFrame = traversalReadbacksScheduled;
        VulkanBerylDebugLog.trace("traversal-dispatch-status", "Traversal dispatch status: traversalDispatchAllowed=" + traversalDispatchAllowed
                + " initialTraversalDispatch=" + initialTraversalDispatch
                + " remainingTraversalDispatchesRan=" + remainingTraversalDispatchesRan
                + " populationThisFrame=" + this.lastRenderListPopulationThisFrame
                + " readbackScheduledThisFrame=" + traversalReadbacksScheduled
                + " readbackReason=" + this.lastRenderListReadbackReason
                + " renderListPopulationBlocker=" + renderListPopulationBlocker);
        logRenderListPopulationDiagnostics(renderList, topNodeCount, frameInit, traversalDispatchAllowed, initialTraversalDispatch, remainingTraversalDispatchesRan, traversalReadbacksScheduled, renderListPopulationBlocker, activeTraversalStageLimit, this.traversalExecutor);
        this.frameSequence++;
        this.frameId++;
        updateFrameSafetyState(renderList.getMaxEntryCount());
        this.publishSmokeStatus();
        VulkanBerylLodBringupDiagnostics.updateRuntime(TRAVERSAL_SMOKE_NOOP, TRAVERSAL_SHADER_SMOKE, TRAVERSAL_SHADER_UNIFORM_SMOKE, activeTraversalStageLimit, RENDERLIST_SMOKE_ONE_ENTRY, ENABLE_CMDGEN_DISPATCH, ENABLE_INDIRECT_DRAW, this.lastVisibleSectionCount, LAST_FRAME_SAFETY_STATE.reason());
    }


    private static void logTraversalUniformUploadDiagnostics(Viewport<?> viewport, VulkanBerylViewportRenderList renderList, RenderGenerationService renderGen, int maxNodeCount, boolean uniformSmokeMode, int traversalStageLimit) {
        final int renderQueueMaxSizeOffset = 192;
        final int frameIdOffset = 196;
        final int requestQueueSizeOffset = 200;
        final int maxNodeCountOffset = 204;
        final int renderDistanceOffset = 208;
        final int renderQueueMaxSize = renderList.getMaxEntryCount();
        final int frameIdValue = uniformSmokeMode ? 0x53554D4B : Math.max(0, traversalStageLimit);
        final double targetCount = 4000.0;
        double fillness = Math.max(0.0, (targetCount - renderGen.getTaskCount()) / targetCount);
        fillness *= fillness;
        final int requestQueueSize = Math.max(0, Math.min(VulkanBerylTraversalResources.MAX_REQUEST_QUEUE_SIZE, (int) Math.ceil(fillness * VulkanBerylTraversalResources.MAX_REQUEST_QUEUE_SIZE)));
        final float sectionRenderDistance = VoxyConfig.CONFIG.sectionRenderDistance;
        final float renderDistance = (float) Math.pow(sectionRenderDistance * 16 * 32, 2);

        VulkanBerylDebugLog.once("traversal-uniform-upload-layout", "Traversal uniform upload layout:"
                + " traversalUniformStd140=true"
                + " viewport=" + viewport.width + "x" + viewport.height
                + " renderQueueMaxSizeOffset=" + renderQueueMaxSizeOffset
                + " renderQueueMaxSizeValue=" + renderQueueMaxSize
                + " frameIdOffset=" + frameIdOffset
                + " frameIdValue=" + frameIdValue
                + " requestQueueSizeOffset=" + requestQueueSizeOffset
                + " requestQueueSizeValue=" + requestQueueSize
                + " maxNodeCountOffset=" + maxNodeCountOffset
                + " maxNodeCountValue=" + Math.max(0, maxNodeCount)
                + " renderDistanceOffset=" + renderDistanceOffset
                + " renderDistanceValue=" + renderDistance
                + " traversalUniformStageLimitWritten=" + frameIdValue);
    }


    private static boolean isTraversalStaticImportLevelActive() {
        String raw = System.getenv("VOXY_VULKAN_BERYL_TRAVERSAL_STATIC_IMPORT_LEVEL");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        if ("full".equalsIgnoreCase(value)) {
            return true;
        }
        try {
            return Integer.parseInt(value) >= 0;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("VOXY_VULKAN_BERYL_TRAVERSAL_STATIC_IMPORT_LEVEL must be an integer 0..5 or full, but was: " + raw, e);
        }
    }

    private static boolean isTraversalDispatchAllowed(boolean explicitNoGpuDrawCountCmdgenPath, int activeTraversalStageLimit) {
        return ENABLE_TRAVERSAL_DISPATCH
                && (TRAVERSAL_SHADER_SMOKE || TRAVERSAL_SHADER_UNIFORM_SMOKE || TRAVERSAL_FORCE_REAL_MAIN_RETURN || TRAVERSAL_STATIC_IMPORT_LEVEL_ACTIVE || activeTraversalStageLimit > 0 || explicitNoGpuDrawCountCmdgenPath);
    }

    private static String traversalDispatchBlocker(boolean explicitNoGpuDrawCountCmdgenPath, int activeTraversalStageLimit) {
        if (!ENABLE_TRAVERSAL_DISPATCH) {
            return "traversal_dispatch_disabled";
        }
        if (!TRAVERSAL_SHADER_SMOKE && !TRAVERSAL_SHADER_UNIFORM_SMOKE && !TRAVERSAL_FORCE_REAL_MAIN_RETURN && !TRAVERSAL_STATIC_IMPORT_LEVEL_ACTIVE && activeTraversalStageLimit <= 0 && !explicitNoGpuDrawCountCmdgenPath) {
            return "waiting_for_traversal_stage_or_no_gpu_drawcount_cmdgen_path";
        }
        return "unknown_traversal_dispatch_blocker";
    }

    private static String firstRenderListPopulationBlocker(String current, String candidate) {
        return "ready".equals(current) || "not_evaluated".equals(current) ? candidate : current;
    }



    private void scheduleRequestReadback(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) {
            throw new IllegalStateException("Cannot read traversal requests without a valid command buffer");
        }

        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer toTransfer = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, toTransfer, null, null);

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0L)
                    .dstOffset(0L)
                    .size(VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES);
            VK10.vkCmdCopyBuffer(commandBuffer, this.traversalResources.getRequestBuffer().getId(), this.requestReadbackBuffer.getId(), copyRegion);

            VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, toHost, null, null);
        }
        this.requestReadbackPending = true;
        this.requestReadbackFrameId = this.frameId;
        this.requestReadbackScheduled = true;
        this.requestReadbackCompleted = false;
    }

    private void submitPendingRequestReadback() {
        if (!this.requestReadbackPending) {
            return;
        }
        long readbackPtr = this.requestReadbackBuffer.getDataPtr();
        if (readbackPtr == 0L) {
            Logger.error("Vulkan/Beryl request readback buffer has no mapped data pointer");
            this.requestReadbackPending = false;
            return;
        }

        ByteBuffer requestBytes = MemoryUtil.memByteBuffer(readbackPtr, (int) VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES);
        int rawCount = requestBytes.getInt(0);
        int maxRequestQueueSize = this.traversalResources.getMaxRequestQueueSize();
        long requestReadbackBufferSizeBytes = VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES;
        int maxByBuffer = (int) ((requestReadbackBufferSizeBytes - 8L) / 8L);

        VulkanBerylDebugLog.trace("traversal-request-readback", "Traversal readback: frameId=" + this.requestReadbackFrameId
                + " gpuCompletionConfirmed=true rawRequestCount=" + rawCount
                + " maxRequestQueueSize=" + maxRequestQueueSize
                + " maxRequestCountByBuffer=" + maxByBuffer
                + " requestReadbackBufferSizeBytes=" + requestReadbackBufferSizeBytes);

        int acceptedCount = rawCount;
        boolean discarded = false;
        String discardReason = "none";

        // Validate request count
        if (rawCount < 0) {
            discardReason = "rawRequestCount_negative";
            discarded = true;
        } else if (rawCount > maxRequestQueueSize) {
            discardReason = "rawRequestCount_exceeds_maxRequestQueueSize";
            discarded = true;
        } else if (rawCount > maxByBuffer) {
            discardReason = "rawRequestCount_exceeds_maxByBuffer";
            discarded = true;
        }

        long requiredRequestBatchBytes = 0L;
        if (!discarded && rawCount > 0) {
            long countTimes8 = (long) rawCount * 8L;
            if (countTimes8 / 8L != (long) rawCount) {
                discardReason = "requiredRequestBatchBytes_overflow";
                discarded = true;
            } else {
                requiredRequestBatchBytes = 8L + countTimes8;
                if (requiredRequestBatchBytes < 0L) {
                    discardReason = "requiredRequestBatchBytes_negative";
                    discarded = true;
                } else if (requiredRequestBatchBytes > requestReadbackBufferSizeBytes) {
                    discardReason = "requiredRequestBatchBytes_exceeds_buffer";
                    discarded = true;
                }
            }
        }

        // Decode preview of first few positions for diagnostics
        String firstPositionsPreview = "[]";
        if (!discarded && rawCount > 0) {
            int previewLimit = Math.min(rawCount, 8);
            StringBuilder sb = new StringBuilder("[");
            int ptr = 8; // Skip count (4 bytes) + padding (4 bytes)
            for (int i = 0; i < previewLimit; i++) {
                if (i > 0) sb.append(", ");
                long upper = Integer.toUnsignedLong(requestBytes.getInt(ptr)); ptr += 4;
                long lower = Integer.toUnsignedLong(requestBytes.getInt(ptr)); ptr += 4;
                long pos = (upper << 32) | lower;
                sb.append(WorldEngine.pprintPos(pos));
            }
            sb.append("]");
            firstPositionsPreview = sb.toString();
        }

        if (discarded) {
            acceptedCount = 0;
            VulkanBerylDebugLog.warnRateLimited("invalid-traversal-request-count",
                    "Invalid/corrupt Vulkan/Beryl traversal request count, discarding readback batch:"
                    + " rawRequestCount=" + rawCount
                    + " acceptedRequestCount=" + acceptedCount
                    + " requestBatchDiscarded=true"
                    + " requestBatchDiscardReason=" + discardReason
                    + " requestBatchSizeBytes=0"
                    + " requiredRequestBatchBytes=" + requiredRequestBatchBytes
                    + " requestReadbackBufferSizeBytes=" + requestReadbackBufferSizeBytes
                    + " maxRequestQueueSize=" + maxRequestQueueSize
                    + " maxRequestCountByBuffer=" + maxByBuffer
                    + " requestReadbackFrameId=" + this.requestReadbackFrameId
                    + " firstPositionsPreview=" + firstPositionsPreview);
        }

        this.lastRequestReadbackDiscarded = discarded;
        this.lastRequestBatchDiscardReason = discardReason;

        long batchSize = 0L;
        if (!discarded && acceptedCount > 0) {
            batchSize = 8L + (long) acceptedCount * 8L;
            MemoryBuffer batch = new MemoryBuffer(batchSize).cpyFrom(readbackPtr);

            // Verify batch header integrity before submission
            int requestBatchHeaderCountBeforeSubmit = -1;
            int requestBatchHeaderPaddingOrReservedWord = 0;
            if (batch.address != 0L && batch.size >= 8L) {
                requestBatchHeaderCountBeforeSubmit = MemoryUtil.memGetInt(batch.address);
                requestBatchHeaderPaddingOrReservedWord = MemoryUtil.memGetInt(batch.address + 4L);
            }

            if (batchSize != requiredRequestBatchBytes) {
                Logger.warn("[Voxy][VulkanBerylRuntime] Producer size mismatch, discarding request batch before submit:"
                        + " rawRequestCount=" + rawCount
                        + " acceptedRequestCount=" + acceptedCount
                        + " requestBatchDiscarded=true"
                        + " requestBatchDiscardReason=producer_size_mismatch"
                        + " requestBatchSizeBytes=" + batchSize
                        + " requiredRequestBatchBytes=" + requiredRequestBatchBytes
                        + " requestReadbackFrameId=" + this.requestReadbackFrameId
                        + " requestBatchHeaderCountBeforeSubmit=" + requestBatchHeaderCountBeforeSubmit
                        + " requestBatchHeaderPaddingOrReservedWord=" + requestBatchHeaderPaddingOrReservedWord
                        + " firstPositionsPreview=" + firstPositionsPreview);
                batch.free();
                discarded = true;
                this.lastRequestReadbackDiscarded = true;
                this.lastRequestBatchDiscardReason = "producer_size_mismatch";
                acceptedCount = 0;
            } else {
                try {
                    this.nodeManager.submitRequestBatch(batch);
                } catch (Exception e) {
                    batch.free();
                    throw e;
                }
            }
        }

        VulkanBerylDebugLog.trace("request-readback-status", "Request readback:"
                + " rawRequestCount=" + rawCount
                + " acceptedRequestCount=" + acceptedCount
                + " requestBatchDiscarded=" + discarded
                + " requestBatchDiscardReason=" + discardReason
                + " requestBatchSizeBytes=" + batchSize
                + " requiredRequestBatchBytes=" + requiredRequestBatchBytes
                + " requestReadbackBufferSizeBytes=" + requestReadbackBufferSizeBytes
                + " maxRequestQueueSize=" + maxRequestQueueSize
                + " maxRequestCountByBuffer=" + maxByBuffer
                + " requestReadbackFrameId=" + this.requestReadbackFrameId
                + " firstPositionsPreview=" + firstPositionsPreview);

        this.requestReadbackPending = false;
        this.requestReadbackCompleted = !discarded;
    }

    private void scheduleRenderListCounterReadback(VkCommandBuffer commandBuffer, VulkanBerylViewportRenderList renderList) {
        if (commandBuffer == null) {
            throw new IllegalStateException("Cannot read render-list counter without a valid command buffer");
        }
        if (renderList.getBuffer().getBufferSize() < VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES) {
            throw new IllegalStateException("Render list buffer is structurally invalid for count readback");
        }
        if (this.renderListCounterReadbackPending) {
            VulkanBerylDebugLog.trace("render-list-counter-readback-pending", "Render-list counter readback still pending; skipping overwrite: readbackFrameId=" + this.renderListReadbackFrameId
                    + " currentFrameId=" + this.frameId
                    + " readbackAgeFrames=" + Math.max(0L, this.frameId - this.renderListReadbackFrameId)
                    + " readbackRendererFrameSlot=" + this.renderListReadbackRendererFrameSlot
                    + " currentRendererFrameSlot=" + safeRendererFrameSlot()
                    + " gpuCompletionKnown=false");
            return;
        }
        long readbackBufferId = this.renderListCounterReadbackBuffer.getId();
        long readbackBufferSizeBytes = this.renderListCounterReadbackBuffer.getBufferSize();
        boolean readbackBufferWritable = readbackBufferId != 0L
                && readbackBufferSizeBytes >= VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES
                && (RENDER_LIST_COUNTER_READBACK_USAGE & VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0;
        int diagnosticMode = nextRenderListCounterReadbackDiagnosticMode();
        boolean readbackDestinationFillRecorded = diagnosticMode == RENDER_LIST_READBACK_DIAGNOSTIC_DESTINATION_FILL;
        boolean renderListSourceCopyRecorded = diagnosticMode == RENDER_LIST_READBACK_DIAGNOSTIC_SOURCE_COPY;
        long readbackPtr = this.renderListCounterReadbackBuffer.getDataPtr();
        if (readbackPtr != 0L) {
            MemoryUtil.memPutInt(readbackPtr, RENDER_LIST_COUNTER_READBACK_SENTINEL);
        }
        if (!readbackBufferWritable) {
            VulkanBerylDebugLog.warnRateLimited("render-list-counter-readback-buffer-invalid", "Render-list counter readback buffer invalid at scheduling: readbackBufferId=" + readbackBufferId
                    + " readbackBufferSizeBytes=" + readbackBufferSizeBytes
                    + " readbackBufferUsage=" + bufferUsageString(RENDER_LIST_COUNTER_READBACK_USAGE)
                    + " readbackDestinationFillRecorded=false"
                    + " renderListSourceCopyRecorded=false"
                    + " gpuCompletionKnown=false");
            return;
        }
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            if (readbackDestinationFillRecorded) {
                VK10.vkCmdFillBuffer(commandBuffer, readbackBufferId, 0L, VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES, RENDER_LIST_READBACK_DESTINATION_FILL_EXPECTED);
                recordReadbackToHostBarrier(commandBuffer, stack);
            } else {
                int readbackSrcAccess = VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT;
                int readbackDstAccess = VK10.VK_ACCESS_TRANSFER_READ_BIT;
                int readbackSrcStages = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
                int readbackDstStages = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                if (renderListSourceCopyRecorded) {
                    VK10.vkCmdFillBuffer(commandBuffer, renderList.getBuffer().getId(), VulkanBerylViewportRenderList.COUNTER_OFFSET_BYTES, VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES, 0);
                    readbackSrcAccess = VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
                    readbackSrcStages = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                }
                VkBufferMemoryBarrier.Buffer toTransfer = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(readbackSrcAccess)
                        .dstAccessMask(readbackDstAccess)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(renderList.getBuffer().getId())
                        .offset(VulkanBerylViewportRenderList.COUNTER_OFFSET_BYTES)
                        .size(VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES);
                VK10.vkCmdPipelineBarrier(commandBuffer,
                        readbackSrcStages,
                        readbackDstStages,
                        0, null, toTransfer, null);
                VulkanBerylDebugLog.once("render-list-counter-readback-barrier", "Render-list counter readback barrier: counterClearToReadbackBarrier=true"
                        + " readbackBarrierSrcAccess=TRANSFER_WRITE|SHADER_WRITE"
                        + " readbackBarrierDstAccess=TRANSFER_READ"
                        + " readbackBarrierSrcStages=TRANSFER|COMPUTE_SHADER"
                        + " readbackBarrierDstStages=TRANSFER");

                VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                        .srcOffset(VulkanBerylViewportRenderList.COUNTER_OFFSET_BYTES)
                        .dstOffset(0L)
                        .size(VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES);
                VK10.vkCmdCopyBuffer(commandBuffer, renderList.getBuffer().getId(), readbackBufferId, copyRegion);
                recordReadbackToHostBarrier(commandBuffer, stack);
            }
        }
        this.pendingRenderListCounterSource = renderList;
        this.renderListReadbackFrameId = this.frameId;
        this.renderListReadbackRendererFrameSlot = safeRendererFrameSlot();
        this.renderListReadbackRecordedCommandBufferAddress = commandBuffer.address();
        this.renderListCounterReadbackDiagnosticMode = diagnosticMode;
        this.renderListCounterReadbackPending = true;
        this.lastRenderListReadbackReason = "pending_gpu_readback";
        VulkanBerylDebugLog.trace("render-list-counter-readback-scheduled", "Render-list counter readback scheduled: readbackFrameId=" + this.renderListReadbackFrameId
                + " readbackRendererFrameSlot=" + this.renderListReadbackRendererFrameSlot
                + " readbackCommandBufferAddress=0x" + Long.toHexString(this.renderListReadbackRecordedCommandBufferAddress)
                + " renderListBufferId=" + renderList.getBuffer().getId()
                + " copyDestinationBufferId=" + readbackBufferId
                + " readbackBufferId=" + readbackBufferId
                + " readbackBufferSizeBytes=" + readbackBufferSizeBytes
                + " readbackBufferUsage=" + bufferUsageString(RENDER_LIST_COUNTER_READBACK_USAGE)
                + " readbackDestinationFillRecorded=" + readbackDestinationFillRecorded
                + " readbackDestinationFillExpected=0x" + Integer.toHexString(RENDER_LIST_READBACK_DESTINATION_FILL_EXPECTED)
                + " renderListSourceCopyRecorded=" + renderListSourceCopyRecorded
                + " renderListSourceCopyExpected=0"
                + " hostMemoryCoherent=" + isHostMemoryCoherent()
                + " hostMemoryInvalidatedBeforeRead=false");
    }

    private int nextRenderListCounterReadbackDiagnosticMode() {
        if (TRAVERSAL_STAGE_LIMIT != 1) {
            return RENDER_LIST_READBACK_DIAGNOSTIC_NONE;
        }
        if (!this.renderListDestinationFillSmokeSucceeded) {
            return RENDER_LIST_READBACK_DIAGNOSTIC_DESTINATION_FILL;
        }
        if (!this.renderListSourceCopySmokeSucceeded) {
            return RENDER_LIST_READBACK_DIAGNOSTIC_SOURCE_COPY;
        }
        return RENDER_LIST_READBACK_DIAGNOSTIC_NONE;
    }

    private static void recordReadbackToHostBarrier(VkCommandBuffer commandBuffer, org.lwjgl.system.MemoryStack stack) {
        VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
        VK10.vkCmdPipelineBarrier(commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0, toHost, null, null);
    }

    private void submitPendingRenderListCounterReadback() {
        if (!this.renderListCounterReadbackPending) {
            return;
        }
        long readbackPtr = this.renderListCounterReadbackBuffer.getDataPtr();
        if (readbackPtr == 0L) {
            Logger.error("Vulkan/Beryl render-list counter readback buffer has no mapped data pointer");
            this.renderListCounterReadbackPending = false;
            this.pendingRenderListCounterSource = null;
            return;
        }
        VulkanBerylViewportRenderList renderList = this.pendingRenderListCounterSource;
        if (renderList == null) {
            this.renderListCounterReadbackPending = false;
            this.renderListReadbackRendererFrameSlot = -1;
            return;
        }
        long readbackAgeFrames = Math.max(0L, this.frameId - this.renderListReadbackFrameId);
        int currentRendererFrameSlot = safeRendererFrameSlot();
        boolean gpuCompletionKnown = this.renderListReadbackRendererFrameSlot >= 0
                && currentRendererFrameSlot == this.renderListReadbackRendererFrameSlot
                && readbackAgeFrames > 0L;
        boolean hostMemoryCoherent = isHostMemoryCoherent();
        if (!gpuCompletionKnown) {
            this.lastRenderListReadbackReason = "pending_gpu_completion";
            VulkanBerylDebugLog.trace("traversal-render-list-readback", "Traversal readback pending: readbackFrameId=" + this.renderListReadbackFrameId
                    + " currentFrameId=" + this.frameId
                    + " readbackAgeFrames=" + readbackAgeFrames
                    + " readbackRendererFrameSlot=" + this.renderListReadbackRendererFrameSlot
                    + " currentRendererFrameSlot=" + currentRendererFrameSlot
                    + " gpuCompletionKnown=false"
                    + " hostMemoryCoherent=" + hostMemoryCoherent
                    + " hostMemoryInvalidatedBeforeRead=false");
            return;
        }
        int rawCount = MemoryUtil.memGetInt(readbackPtr);
        boolean sentinelRemained = rawCount == RENDER_LIST_COUNTER_READBACK_SENTINEL;
        int completedDiagnosticMode = this.renderListCounterReadbackDiagnosticMode;
        boolean readbackDestinationFillRecorded = completedDiagnosticMode == RENDER_LIST_READBACK_DIAGNOSTIC_DESTINATION_FILL;
        boolean renderListSourceCopyRecorded = completedDiagnosticMode == RENDER_LIST_READBACK_DIAGNOSTIC_SOURCE_COPY;
        if (readbackDestinationFillRecorded) {
            this.lastRenderListDestinationFillObserved = rawCount;
            this.renderListDestinationFillSmokeSucceeded = rawCount == RENDER_LIST_READBACK_DESTINATION_FILL_EXPECTED;
        }
        if (renderListSourceCopyRecorded) {
            this.lastRenderListSourceCopyObserved = rawCount;
            this.renderListSourceCopySmokeSucceeded = rawCount == 0;
        }
        this.lastRawVisibleSectionCount = rawCount;
        if (this.previousRawRenderListVisibleCount != rawCount) {
            VulkanBerylDebugLog.alwaysRaw("[Voxy][VulkanBeryl][RENDERLIST_TRANSITION]"
                    + " previousRawRenderListVisibleCount=" + this.previousRawRenderListVisibleCount
                    + ", newRawRenderListVisibleCount=" + rawCount
                    + ", frameSequence=" + this.frameSequence
                    + ", traversalStageLimit=" + TRAVERSAL_STAGE_LIMIT
                    + ", stageMeaning=" + traversalStageMeaning(TRAVERSAL_STAGE_LIMIT));
            this.previousRawRenderListVisibleCount = rawCount;
        }
        VulkanBerylDebugLog.trace("traversal-render-list-readback", "Traversal readback: readbackFrameId=" + this.renderListReadbackFrameId
                + " currentFrameId=" + this.frameId
                + " readbackAgeFrames=" + readbackAgeFrames
                + " readbackRendererFrameSlot=" + this.renderListReadbackRendererFrameSlot
                + " currentRendererFrameSlot=" + currentRendererFrameSlot
                + " readbackCommandBufferAddress=0x" + Long.toHexString(this.renderListReadbackRecordedCommandBufferAddress)
                + " gpuCompletionKnown=true rawRenderListCount=" + rawCount
                + " readbackBufferId=" + this.renderListCounterReadbackBuffer.getId()
                + " readbackBufferSizeBytes=" + this.renderListCounterReadbackBuffer.getBufferSize()
                + " readbackBufferUsage=" + bufferUsageString(RENDER_LIST_COUNTER_READBACK_USAGE)
                + " readbackDestinationFillRecorded=" + readbackDestinationFillRecorded
                + " readbackDestinationFillExpected=0x" + Integer.toHexString(RENDER_LIST_READBACK_DESTINATION_FILL_EXPECTED)
                + " readbackDestinationFillObserved=" + hexAndInt(this.lastRenderListDestinationFillObserved)
                + " readbackDestinationFillSucceeded=" + this.renderListDestinationFillSmokeSucceeded
                + " renderListSourceCopyRecorded=" + renderListSourceCopyRecorded
                + " renderListSourceCopyExpected=0"
                + " renderListSourceCopyObserved=" + hexAndInt(this.lastRenderListSourceCopyObserved)
                + " renderListSourceCopySucceeded=" + this.renderListSourceCopySmokeSucceeded
                + " hostMemoryCoherent=" + hostMemoryCoherent
                + " hostMemoryInvalidatedBeforeRead=false"
                + " readbackSentinelRemained=" + sentinelRemained);
        if (readbackDestinationFillRecorded || renderListSourceCopyRecorded) {
            this.lastRenderListCounterDiscarded = true;
            this.lastRenderListReadbackValid = false;
            this.lastRenderListReadbackReason = readbackDestinationFillRecorded ? "destination_fill_smoke_completed" : "source_copy_smoke_completed";
            VulkanBerylDebugLog.trace("render-list-counter-readback-status", "Render-list counter readback smoke completed without accepting a visible count: rawRenderListCount=" + rawCount
                    + " readbackDestinationFillRecorded=" + readbackDestinationFillRecorded
                    + " readbackDestinationFillSucceeded=" + this.renderListDestinationFillSmokeSucceeded
                    + " renderListSourceCopyRecorded=" + renderListSourceCopyRecorded
                    + " renderListSourceCopySucceeded=" + this.renderListSourceCopySmokeSucceeded);
            logRenderListReadbackDiagnostics(renderList, "counter_readback_smoke_completed");
            this.renderListCounterReadbackPending = false;
            this.pendingRenderListCounterSource = null;
            this.renderListReadbackRendererFrameSlot = -1;
            this.renderListReadbackRecordedCommandBufferAddress = 0L;
            this.renderListCounterReadbackDiagnosticMode = RENDER_LIST_READBACK_DIAGNOSTIC_NONE;
            return;
        }
        int maxEntryCount = renderList.getMaxEntryCount();
        int acceptedCount = rawCount;
        boolean discarded = false;
        if (rawCount < 0 || rawCount > maxEntryCount) {
            String readbackReason = "counter_out_of_range";
            VulkanBerylDebugLog.warnRateLimited("invalid-render-list-counter-readback", "Invalid/corrupt Vulkan/Beryl render-list counter readback, discarding: rawRenderListVisibleCount=" + rawCount
                    + " maxEntryCount=" + maxEntryCount
                    + " counterReadbackOffset=" + VulkanBerylViewportRenderList.COUNTER_OFFSET_BYTES
                    + " counterReadbackBytes=" + VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES
                    + " renderListBufferId=" + renderList.getBuffer().getId()
                    + " renderListBufferSizeBytes=" + renderList.getBuffer().getBufferSize()
                    + " counterSourceBuffer=" + describeRenderListCounterSource(renderList)
                    + " gpuCompletionKnown=true"
                    + " hostMemoryCoherent=" + hostMemoryCoherent
                    + " hostMemoryInvalidatedBeforeRead=false"
                    + " readbackFrameId=" + this.renderListReadbackFrameId
                    + " currentFrameId=" + this.frameId
                    + " readbackAgeFrames=" + readbackAgeFrames
                    + " readbackSentinelRemained=" + sentinelRemained
                    + " readbackReason=" + readbackReason);
            acceptedCount = 0;
            discarded = true;
        }
        this.lastRenderListCounterDiscarded = discarded;
        this.lastRenderListReadbackValid = !discarded;
        this.lastRenderListReadbackReason = discarded ? "counter_out_of_range" : "valid_gpu_readback";
        renderList.setLastVisibleCount(acceptedCount, this.frameId);
        this.lastVisibleSectionCount = acceptedCount;
        this.lastVisibleSectionCapacity = maxEntryCount;
        VulkanBerylDebugLog.trace("render-list-counter-readback-status", "Render-list counter readback: rawRenderListCount=" + rawCount
                + " acceptedRenderListCount=" + acceptedCount + " renderListCounterDiscarded=" + discarded);
        logRenderListReadbackDiagnostics(renderList, "counter_readback_completed");
        this.renderListCounterReadbackPending = false;
        this.pendingRenderListCounterSource = null;
        this.renderListReadbackRendererFrameSlot = -1;
        this.renderListReadbackRecordedCommandBufferAddress = 0L;
        this.renderListCounterReadbackDiagnosticMode = RENDER_LIST_READBACK_DIAGNOSTIC_NONE;
    }

    private static String hexAndInt(int value) {
        return "0x" + Integer.toHexString(value) + "/" + value;
    }

    private static String bufferUsageString(int usageFlags) {
        java.util.ArrayList<String> usages = new java.util.ArrayList<>();
        if ((usageFlags & VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0) usages.add("STORAGE");
        if ((usageFlags & VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0) usages.add("INDIRECT");
        if ((usageFlags & VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0) usages.add("TRANSFER_DST");
        if ((usageFlags & VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0) usages.add("TRANSFER_SRC");
        return usages.isEmpty() ? "0" : String.join("|", usages);
    }

    private static int safeRendererFrameSlot() {
        try {
            return Renderer.getCurrentFrame();
        } catch (RuntimeException | Error ignored) {
            return -1;
        }
    }

    private static boolean isHostMemoryCoherent() {
        return MemoryTypes.HOST_MEM != null
                && MemoryTypes.HOST_MEM.vkMemoryType != null
                && (MemoryTypes.HOST_MEM.vkMemoryType.propertyFlags() & VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    }

    private static String describeRenderListCounterSource(VulkanBerylViewportRenderList renderList) {
        return RENDER_LIST_COUNTER_SOURCE_BUFFER + "#" + renderList.getBuffer().getId();
    }

    private void scheduleRenderListSampleReadback(VkCommandBuffer commandBuffer, VulkanBerylViewportRenderList renderList) {
        if (commandBuffer == null) {
            throw new IllegalStateException("Cannot read render-list sample without a valid command buffer");
        }
        long sampleRegionSize = Integer.BYTES + (long) RENDER_LIST_SAMPLE_LIMIT * Integer.BYTES;
        if (renderList.getBuffer().getBufferSize() < sampleRegionSize) {
            throw new IllegalStateException("Render list buffer is structurally invalid for sample readback");
        }
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer toTransfer = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, toTransfer, null, null);

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack).srcOffset(0L).dstOffset(0L).size(sampleRegionSize);
            VK10.vkCmdCopyBuffer(commandBuffer, renderList.getBuffer().getId(), this.renderListSampleReadbackBuffer.getId(), copyRegion);

            VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, toHost, null, null);
        }
        this.pendingRenderListSampleSource = renderList;
        this.pendingRenderListSampleGeometry = this.nodeManager.getGeometryData() instanceof VulkanBerylSectionGeometryData geometryData ? geometryData : null;
        this.renderListSampleReadbackPending = true;
    }

    private void submitPendingRenderListSampleReadback() {
        if (!this.renderListSampleReadbackPending) {
            return;
        }
        long readbackPtr = this.renderListSampleReadbackBuffer.getDataPtr();
        VulkanBerylViewportRenderList renderList = this.pendingRenderListSampleSource;
        if (readbackPtr == 0L || renderList == null) {
            this.renderListSampleReadbackPending = false;
            this.pendingRenderListSampleSource = null;
            this.pendingRenderListSampleGeometry = null;
            return;
        }

        int visibleCount = MemoryUtil.memGetInt(readbackPtr);
        int maxEntryCount = renderList.getMaxEntryCount();
        if (visibleCount < 0 || visibleCount > maxEntryCount) {
            VulkanBerylDebugLog.warnRateLimited("invalid-render-list-sample-counter", "Invalid/corrupt Vulkan/Beryl render-list sample counter, skipping sample: visibleCount=" + visibleCount
                    + " maxEntryCount=" + maxEntryCount);
            this.lastSampledVisibleSectionCount = 0;
            this.lastSampledRenderListEntryCount = 0;
            this.lastInvalidSampledRenderListEntryCount = 0;
            this.lastSampledRenderListFirstEntries = "[]";
            this.renderListSampleReadbackPending = false;
            this.pendingRenderListSampleSource = null;
            this.pendingRenderListSampleGeometry = null;
            this.lastRenderListSampleDiscarded = true;
            return;
        }

        int sampledCount = Math.min(visibleCount, RENDER_LIST_SAMPLE_LIMIT);
        int invalidCount = 0;
        int[] firstIds = new int[Math.min(sampledCount, RENDER_LIST_DEBUG_FIRST_IDS)];
        VulkanBerylSectionGeometryData geometryData = this.pendingRenderListSampleGeometry;
        int metadataSectionCapacity = geometryData == null ? renderList.getMaxEntryCount() : geometryData.getMaxSectionCount();
        for (int i = 0; i < sampledCount; i++) {
            int sectionId = MemoryUtil.memGetInt(readbackPtr + Integer.BYTES + (long) i * Integer.BYTES);
            if (i < firstIds.length) {
                firstIds[i] = sectionId;
            }
            boolean valid = sectionId >= 0 && sectionId < metadataSectionCapacity;
            if (!valid) {
                invalidCount++;
            }
        }

        this.lastSampledVisibleSectionCount = visibleCount;
        this.lastSampledRenderListEntryCount = sampledCount;
        this.lastInvalidSampledRenderListEntryCount = invalidCount;
        this.lastSampledRenderListFirstEntries = java.util.Arrays.toString(firstIds);
        if (invalidCount > 0) {
            VulkanBerylDebugLog.warnRateLimited("invalid-render-list-sample-entries", "Vulkan/Beryl render-list sample validation found invalid entries: " + invalidCount + "/" + sampledCount +
                    " (visible=" + visibleCount + ", metadataSectionCapacity=" + metadataSectionCapacity + ", renderListCapacity=" + maxEntryCount + ")");
        }
        logFirstRenderListEntryMetadataSample(firstIds, geometryData, visibleCount, sampledCount, metadataSectionCapacity);

        this.renderListSampleReadbackPending = false;
        this.pendingRenderListSampleSource = null;
        this.pendingRenderListSampleGeometry = null;
        this.lastRenderListSampleDiscarded = invalidCount > 0;
    }

    private void logFirstRenderListEntryMetadataSample(int[] firstIds, VulkanBerylSectionGeometryData geometryData, int visibleCount, int sampledCount, int metadataSectionCapacity) {
        if (firstIds.length == 0 || geometryData == null) {
            return;
        }
        int sectionId = firstIds[0];
        boolean inBounds = sectionId >= 0 && sectionId < metadataSectionCapacity;
        boolean mirrorNonZero = inBounds && geometryData.hasNonZeroSectionMetadata(sectionId);
        int a0 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 0) : 0;
        int a1 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 1) : 0;
        int a2 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 2) : 0;
        int a3 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 3) : 0;
        int b0 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 4) : 0;
        int b1 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 5) : 0;
        int b2 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 6) : 0;
        int b3 = inBounds ? geometryData.getSectionMetadataInt(sectionId, 7) : 0;
        long translucentQuadCount = b0 & 0xFFFFL;
        long opaqueQuadCount = ((b0 >>> 16) & 0xFFFFL)
                + (b1 & 0xFFFFL) + ((b1 >>> 16) & 0xFFFFL)
                + (b2 & 0xFFFFL) + ((b2 >>> 16) & 0xFFFFL)
                + (b3 & 0xFFFFL) + ((b3 >>> 16) & 0xFFFFL);
        VulkanBerylDebugLog.rateLimited("render-list-entry0-metadata-contract", "render-list entry0 metadata contract: visibleCount=" + visibleCount
                + ", sampledCount=" + sampledCount
                + ", entry0SectionId=" + sectionId
                + ", metadataSectionCapacity=" + metadataSectionCapacity
                + ", inBounds=" + inBounds
                + ", metadataMirrorNonZero=" + mirrorNonZero
                + ", meta.a=[" + Integer.toUnsignedLong(a0) + "," + Integer.toUnsignedLong(a1) + "," + Integer.toUnsignedLong(a2) + "," + Integer.toUnsignedLong(a3) + "]"
                + ", meta.b=[" + Integer.toUnsignedLong(b0) + "," + Integer.toUnsignedLong(b1) + "," + Integer.toUnsignedLong(b2) + "," + Integer.toUnsignedLong(b3) + "]"
                + ", decodedQuadStart=" + Integer.toUnsignedLong(a3)
                + ", decodedTranslucentQuadCount=" + translucentQuadCount
                + ", javaDecodedOpaqueQuadCount=" + opaqueQuadCount
                + ", firstInstanceConvention=render_list_draw_index", 120);
    }

    static FrameSafetyState getLastFrameSafetyState() {
        return LAST_FRAME_SAFETY_STATE;
    }

    private void updateFrameSafetyState(int maxEntryCount) {
        boolean goodCounter = !this.lastRenderListCounterDiscarded && this.lastVisibleSectionCount >= 0 && this.lastVisibleSectionCount <= maxEntryCount;
        boolean noCorruption = !this.lastRequestReadbackDiscarded && !this.lastRenderListSampleDiscarded;
        boolean allowCmdgen = this.frameSequence >= 2 && goodCounter && noCorruption;
        boolean allowIndirect = this.frameSequence >= 3 && allowCmdgen && this.lastInvalidSampledRenderListEntryCount == 0;
        String waitingReason = renderListReadbackWaitingReason(maxEntryCount, goodCounter);
        String reason = allowIndirect ? "ready" : (!goodCounter ? "waiting_for_valid_render_list_readback" : (!noCorruption ? "previous_frame_corruption_detected" : (this.frameSequence < 2 ? "frame_stage_wait_n1" : "frame_stage_wait_n2")));
        LAST_FRAME_SAFETY_STATE = new FrameSafetyState(allowCmdgen, allowIndirect, reason);
        if (!goodCounter || this.lastVisibleSectionCount <= 0) {
            String message = "Render-list safety state: reason=" + reason
                    + " waitingDetail=" + waitingReason
                    + " rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                    + " acceptedRenderListVisibleCount=" + this.lastVisibleSectionCount
                    + " renderListLastVisibleCount=" + this.lastVisibleSectionCount
                    + " maxEntryCount=" + maxEntryCount
                    + " populationThisFrame=" + this.lastRenderListPopulationThisFrame
                    + " readbackScheduledThisFrame=" + this.lastRenderListReadbackScheduledThisFrame
                    + " readbackPending=" + this.renderListCounterReadbackPending
                    + " readbackValid=" + this.lastRenderListReadbackValid
                    + " readbackReason=" + this.lastRenderListReadbackReason
                    + " frameSequence=" + this.frameSequence;
            String stateSnapshot = "rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                    + ";renderListLastVisibleCount=" + this.lastVisibleSectionCount
                    + ";maxEntryCount=" + maxEntryCount
                    + ";frameSafetyReason=" + reason;
            VulkanBerylDebugLog.stateLimited("render-list-safety-state", message, stateSnapshot);
        }
    }

    private String renderListReadbackWaitingReason(int maxEntryCount, boolean goodCounter) {
        if (goodCounter) {
            return this.lastVisibleSectionCount <= 0 ? "valid_readback_visible_count_zero" : "valid_readback_nonzero";
        }
        if (this.lastRenderListCounterDiscarded) {
            return "last_counter_readback_discarded:" + this.lastRenderListReadbackReason;
        }
        if (this.renderListCounterReadbackPending) {
            return "counter_readback_pending";
        }
        if (!this.lastRenderListReadbackScheduledThisFrame && this.lastVisibleSectionCount < 0) {
            return "no_render_list_readback_scheduled_yet";
        }
        if (this.lastVisibleSectionCount < 0) {
            return "no_completed_render_list_readback";
        }
        if (this.lastVisibleSectionCount > maxEntryCount) {
            return "accepted_count_exceeds_capacity";
        }
        return "unknown_counter_state";
    }

    private void logRenderListPopulationDiagnostics(VulkanBerylViewportRenderList renderList,
                                                   int topNodeCount,
                                                   VulkanBerylTraversalResources.FrameInitStats frameInit,
                                                   boolean traversalDispatchAllowed,
                                                   boolean initialTraversalDispatch,
                                                   int remainingTraversalDispatchesRan,
                                                   boolean traversalReadbacksScheduled,
                                                   String renderListPopulationBlocker,
                                                   int traversalStageLimit,
                                                   VulkanBerylTraversalExecutor traversalExecutor) {
        String stageMeaning = traversalStageMeaning(traversalStageLimit);
        String traversalErrorState = traversalExecutor == null ? "traversal_executor_unavailable" : traversalExecutor.getLastDescriptorFailure();
        String deviceLossOrErrorState = !"none".equals(traversalErrorState)
                ? traversalErrorState
                : (this.lastRenderListCounterDiscarded ? "render_list_readback_discarded:" + this.lastRenderListReadbackReason : "none_observed");
        String message = "Render-list population state: traversalStageLimit=" + traversalStageLimit
                + " stageMeaning=" + stageMeaning
                + " topNodeCount=" + topNodeCount
                + " scratchQueueASeededCount=" + frameInit.scratchQueueASeededCount()
                + " traversalDispatchAllowed=" + traversalDispatchAllowed
                + " initialTraversalDispatch=" + initialTraversalDispatch
                + " remainingTraversalDispatchesRan=" + remainingTraversalDispatchesRan
                + " traversalReadbacksScheduled=" + traversalReadbacksScheduled
                + " renderListPopulationBlocker=" + renderListPopulationBlocker
                + " rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                + " renderListVisibleCount=" + this.lastVisibleSectionCount
                + " readbackValid=" + this.lastRenderListReadbackValid
                + " readbackReason=" + this.lastRenderListReadbackReason
                + " traversalErrorState=" + traversalErrorState
                + " deviceLossOrErrorState=" + deviceLossOrErrorState;
        String stateSnapshot = "traversalStageLimit=" + traversalStageLimit
                + ";stageMeaning=" + stageMeaning
                + ";traversalDispatchAllowed=" + traversalDispatchAllowed
                + ";initialTraversalDispatch=" + initialTraversalDispatch
                + ";renderListPopulationBlocker=" + renderListPopulationBlocker
                + ";rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                + ";renderListVisibleCount=" + this.lastVisibleSectionCount
                + ";deviceLossOrErrorState=" + deviceLossOrErrorState;
        VulkanBerylDebugLog.stateLimited("render-list-population-state", message, stateSnapshot);
    }

    private void logRenderListReadbackDiagnostics(VulkanBerylViewportRenderList renderList, String stage) {
        VulkanBerylDebugLog.rateLimited("render-list-readback-state", "Render-list readback state: stage=" + stage
                + " rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                + " acceptedRenderListVisibleCount=" + this.lastVisibleSectionCount
                + " renderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + " maxEntryCount=" + renderList.getMaxEntryCount()
                + " readbackValid=" + this.lastRenderListReadbackValid
                + " readbackReason=" + this.lastRenderListReadbackReason
                + " discarded=" + this.lastRenderListCounterDiscarded, 120);
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl backend runtime: initialized (primary traversal work; node metadata + TLN stores active)");
        debug.add("Vulkan/Beryl TLN: " + this.topLevelNodeStore.getTopNodeCount() + "/" + this.topLevelNodeStore.getMaxTopLevelNodeCount());
        debug.add("Vulkan/Beryl render list visible sections: " + this.lastVisibleSectionCount + "/" + this.lastVisibleSectionCapacity);
        debug.add("Vulkan/Beryl render list raw visible sections: " + this.lastRawVisibleSectionCount);
        debug.add("Vulkan/Beryl render list population this frame: " + this.lastRenderListPopulationThisFrame);
        debug.add("Vulkan/Beryl render list readback: scheduledThisFrame=" + this.lastRenderListReadbackScheduledThisFrame
                + " pending=" + this.renderListCounterReadbackPending
                + " valid=" + this.lastRenderListReadbackValid
                + " reason=" + this.lastRenderListReadbackReason);
        debug.add("Vulkan/Beryl render-list sample: " + this.lastSampledRenderListEntryCount + "/" + this.lastSampledVisibleSectionCount
                + " entries, invalid=" + this.lastInvalidSampledRenderListEntryCount + ", first=" + this.lastSampledRenderListFirstEntries);
        VulkanBerylTraversalExecutor traversal = this.traversalExecutor;
        debug.add("Vulkan/Beryl traversal descriptor creation mode: " + (traversal == null ? "unavailable" : traversal.getDescriptorCreationMode()));
        debug.add("Vulkan/Beryl traversal pipeline created: " + (traversal != null && traversal.isTraversalPipelineCreated()));
        debug.add("Vulkan/Beryl traversal descriptors bound: " + (traversal != null && traversal.areDescriptorsBound()));
        debug.add("Vulkan/Beryl traversal last descriptor failure: " + (traversal == null ? "unavailable" : traversal.getLastDescriptorFailure()));
        this.publishSmokeStatus();
    }

    static SmokeStatus getLastSmokeStatus() {
        return LAST_SMOKE_STATUS;
    }

    private void publishSmokeStatus() {
        VulkanBerylTraversalExecutor traversal = this.traversalExecutor;
        LAST_SMOKE_STATUS = new SmokeStatus(
                this.runtimeEntered,
                traversal != null && traversal.isTraversalPipelineCreated(),
                traversal != null && traversal.areDescriptorsBound(),
                traversal != null && traversal.didDispatchIterationZeroRun(),
                traversal != null && traversal.wasDispatchIterationZeroSkipped(),
                traversal == null ? 0 : traversal.getIndirectDispatchIterationCount(),
                this.requestReadbackScheduled,
                this.requestReadbackCompleted,
                this.lastVisibleSectionCount,
                this.lastInvalidSampledRenderListEntryCount
        );
    }

    VulkanBerylNodeMetadataStore getNodeMetadataStore() {
        return this.nodeMetadataStore;
    }

    VulkanBerylTopLevelNodeStore getTopLevelNodeStore() {
        return this.topLevelNodeStore;
    }

    VulkanBerylTraversalResources getTraversalResources() {
        return this.traversalResources;
    }

    @Override
    public void free() {
        if (this.freed) {
            return;
        }
        if (this.traversalExecutor != null) {
            this.traversalExecutor.free();
            this.traversalExecutor = null;
        }
        this.nodeMetadataStore.free();
        this.topLevelNodeStore.free();
        this.traversalResources.free();
        this.requestReadbackBuffer.scheduleFree();
        this.renderListCounterReadbackBuffer.scheduleFree();
        this.renderListSampleReadbackBuffer.scheduleFree();
        this.freed = true;
    }
}
