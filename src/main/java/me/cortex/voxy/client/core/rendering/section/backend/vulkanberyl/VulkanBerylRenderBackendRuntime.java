package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.backend.PrimaryRenderWorkContext;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
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
    private static final int TRAVERSAL_STAGE_LIMIT = Math.max(0, Math.min(6, Integer.parseInt(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_STAGE_LIMIT", "0"))));
    private static final boolean ENABLE_TRAVERSAL_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_TRAVERSAL_DISPATCH", "true"));
    private static final boolean ENABLE_INITIAL_TRAVERSAL_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INITIAL_TRAVERSAL_DISPATCH", "true"));
    private static final boolean ENABLE_INDIRECT_TRAVERSAL_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_TRAVERSAL_DISPATCH", "false"));
    private static final int TRAVERSAL_MAX_ITERATIONS = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_MAX_ITERATIONS", "1")));
    private static final boolean TRAVERSAL_SMOKE_NOOP = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SMOKE_NOOP", "false"));
    private static final boolean TRAVERSAL_SMOKE_WRITE_KNOWN = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SMOKE_WRITE_KNOWN", "false"));
    private static final boolean TRAVERSAL_SHADER_SMOKE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SHADER_SMOKE", "false"));
    private static final boolean TRAVERSAL_SHADER_UNIFORM_SMOKE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SHADER_UNIFORM_SMOKE", "false"));
    private static final boolean RENDERLIST_SMOKE_ONE_ENTRY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_RENDERLIST_SMOKE_ONE_ENTRY", "false"));
    private static final boolean ENABLE_CMDGEN_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_CMDGEN_DISPATCH", "false"));
    private static final boolean ENABLE_INDIRECT_DRAW = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_DRAW", "false"));
    private static final int FULL_TRAVERSAL_STAGE_LIMIT = 6;
    private static final int RENDER_LIST_SAMPLE_LIMIT = 64;
    private static final int RENDER_LIST_DEBUG_FIRST_IDS = 8;
    private static final String RENDER_LIST_COUNTER_SOURCE_BUFFER = "voxy_vulkanberyl_render_list";
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
    private String lastSampledRenderListFirstEntries = "[]";
    private VulkanBerylTraversalExecutor traversalExecutor;
    private boolean freed;
    private boolean runtimeEntered;
    private boolean requestReadbackScheduled;
    private boolean requestReadbackCompleted;
    private boolean lastRequestReadbackDiscarded;
    private boolean lastRenderListCounterDiscarded;
    private boolean lastRenderListSampleDiscarded;
    private int frameSequence;
    private long requestReadbackFrameId = -1;
    private long renderListReadbackFrameId = -1;
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
        this.renderListCounterReadbackBuffer = new Buffer("voxy_vulkanberyl_render_list_count_readback", VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.HOST_MEM);
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
        this.traversalResources.recordTraversalUniformUpload(commandBuffer, vulkanViewport, renderList, this.topLevelNodeStore, this.renderGen, this.nodeManager.maxNodeCount, TRAVERSAL_SHADER_UNIFORM_SMOKE, activeTraversalStageLimit);
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
        logRenderListPopulationDiagnostics(renderList, topNodeCount, frameInit, traversalDispatchAllowed, initialTraversalDispatch, remainingTraversalDispatchesRan, traversalReadbacksScheduled, renderListPopulationBlocker);
        this.frameSequence++;
        this.frameId++;
        updateFrameSafetyState(renderList.getMaxEntryCount());
        this.publishSmokeStatus();
        VulkanBerylLodBringupDiagnostics.updateRuntime(TRAVERSAL_SMOKE_NOOP, TRAVERSAL_SHADER_SMOKE, TRAVERSAL_SHADER_UNIFORM_SMOKE, activeTraversalStageLimit, RENDERLIST_SMOKE_ONE_ENTRY, ENABLE_CMDGEN_DISPATCH, ENABLE_INDIRECT_DRAW, this.lastVisibleSectionCount, LAST_FRAME_SAFETY_STATE.reason());
    }

    private static boolean isTraversalDispatchAllowed(boolean explicitNoGpuDrawCountCmdgenPath, int activeTraversalStageLimit) {
        return ENABLE_TRAVERSAL_DISPATCH
                && (TRAVERSAL_SHADER_SMOKE || TRAVERSAL_SHADER_UNIFORM_SMOKE || activeTraversalStageLimit > 0 || explicitNoGpuDrawCountCmdgenPath);
    }

    private static String traversalDispatchBlocker(boolean explicitNoGpuDrawCountCmdgenPath, int activeTraversalStageLimit) {
        if (!ENABLE_TRAVERSAL_DISPATCH) {
            return "traversal_dispatch_disabled";
        }
        if (!TRAVERSAL_SHADER_SMOKE && !TRAVERSAL_SHADER_UNIFORM_SMOKE && activeTraversalStageLimit <= 0 && !explicitNoGpuDrawCountCmdgenPath) {
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
        VulkanBerylDebugLog.trace("traversal-request-readback", "Traversal readback: frameId=" + this.requestReadbackFrameId + " gpuCompletionConfirmed=true rawRequestCount=" + rawCount);
        int maxByBuffer = (int) ((VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES - 8L) / 8L);
        int acceptedCount = rawCount;
        boolean discarded = false;
        if (rawCount < 0 || rawCount > this.traversalResources.getMaxRequestQueueSize() || rawCount > maxByBuffer) {
            VulkanBerylDebugLog.warnRateLimited("invalid-traversal-request-count", "Invalid/corrupt Vulkan/Beryl traversal request count, discarding readback batch: rawRequestCount=" + rawCount
                    + " maxRequestQueueSize=" + this.traversalResources.getMaxRequestQueueSize() + " maxByBuffer=" + maxByBuffer);
            acceptedCount = 0;
            discarded = true;
        }
        this.lastRequestReadbackDiscarded = discarded;

        if (!discarded && acceptedCount > 0) {
            long batchSize = 8L + (long) acceptedCount * 8L;
            MemoryBuffer batch = new MemoryBuffer(batchSize).cpyFrom(readbackPtr);
            this.nodeManager.submitRequestBatch(batch);
        }
        VulkanBerylDebugLog.trace("request-readback-status", "Request readback: rawRequestCount=" + rawCount + " acceptedRequestCount=" + acceptedCount
                + " requestBatchDiscarded=" + discarded);
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
                    .srcOffset(VulkanBerylViewportRenderList.COUNTER_OFFSET_BYTES)
                    .dstOffset(0L)
                    .size(VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES);
            VK10.vkCmdCopyBuffer(commandBuffer, renderList.getBuffer().getId(), this.renderListCounterReadbackBuffer.getId(), copyRegion);

            VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, toHost, null, null);
        }
        this.pendingRenderListCounterSource = renderList;
        this.renderListReadbackFrameId = this.frameId;
        this.renderListCounterReadbackPending = true;
        this.lastRenderListReadbackReason = "pending_gpu_readback";
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
            return;
        }
        int rawCount = MemoryUtil.memGetInt(readbackPtr);
        this.lastRawVisibleSectionCount = rawCount;
        VulkanBerylDebugLog.trace("traversal-render-list-readback", "Traversal readback: frameId=" + this.renderListReadbackFrameId + " gpuCompletionConfirmed=true rawRenderListCount=" + rawCount);
        int maxEntryCount = renderList.getMaxEntryCount();
        int acceptedCount = rawCount;
        boolean discarded = false;
        if (rawCount < 0 || rawCount > maxEntryCount) {
            String readbackReason = "counter_out_of_range";
            VulkanBerylDebugLog.warnRateLimited("invalid-render-list-counter-readback", "Invalid/corrupt Vulkan/Beryl render-list counter readback, discarding: rawRenderListVisibleCount=" + rawCount
                    + " maxEntryCount=" + maxEntryCount
                    + " counterReadbackOffset=" + VulkanBerylViewportRenderList.COUNTER_OFFSET_BYTES
                    + " counterReadbackBytes=" + VulkanBerylViewportRenderList.COUNTER_SIZE_BYTES
                    + " counterSourceBuffer=" + describeRenderListCounterSource(renderList)
                    + " readbackReason=" + readbackReason);
            acceptedCount = 0;
            discarded = true;
        }
        this.lastRenderListCounterDiscarded = discarded;
        this.lastRenderListReadbackValid = !discarded;
        this.lastRenderListReadbackReason = discarded ? "counter_out_of_range" : "valid_gpu_readback";
        renderList.setLastVisibleCount(acceptedCount);
        this.lastVisibleSectionCount = acceptedCount;
        this.lastVisibleSectionCapacity = maxEntryCount;
        VulkanBerylDebugLog.trace("render-list-counter-readback-status", "Render-list counter readback: rawRenderListCount=" + rawCount
                + " acceptedRenderListCount=" + acceptedCount + " renderListCounterDiscarded=" + discarded);
        logRenderListReadbackDiagnostics(renderList, "counter_readback_completed");
        this.renderListCounterReadbackPending = false;
        this.pendingRenderListCounterSource = null;
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
        this.pendingRenderListSampleGeometry = null;
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
        for (int i = 0; i < sampledCount; i++) {
            int sectionId = MemoryUtil.memGetInt(readbackPtr + Integer.BYTES + (long) i * Integer.BYTES);
            if (i < firstIds.length) {
                firstIds[i] = sectionId;
            }
            boolean valid = sectionId >= 0 && sectionId < renderList.getMaxEntryCount();
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
                    " (visible=" + visibleCount + ", renderListCapacity=" + maxEntryCount + ")");
        }

        this.renderListSampleReadbackPending = false;
        this.pendingRenderListSampleSource = null;
        this.pendingRenderListSampleGeometry = null;
        this.lastRenderListSampleDiscarded = invalidCount > 0;
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
                    + ";clampedVisibleCount=" + Math.max(0, Math.min(this.lastVisibleSectionCount, maxEntryCount))
                    + ";maxEntryCount=" + maxEntryCount
                    + ";populationThisFrame=" + this.lastRenderListPopulationThisFrame
                    + ";readbackScheduledThisFrame=" + this.lastRenderListReadbackScheduledThisFrame
                    + ";readbackPending=" + this.renderListCounterReadbackPending
                    + ";readbackValid=" + this.lastRenderListReadbackValid
                    + ";readbackReason=" + this.lastRenderListReadbackReason
                    + ";frameSafetyReason=" + reason
                    + ";reasonDetail=" + waitingReason;
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
                                                   String renderListPopulationBlocker) {
        String message = "Render-list population state: rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                + " renderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + " maxEntryCount=" + renderList.getMaxEntryCount()
                + " topNodeCount=" + topNodeCount
                + " scratchQueueASeededCount=" + frameInit.scratchQueueASeededCount()
                + " renderListCounterCleared=" + frameInit.renderListCounterCleared()
                + " renderListPopulationBlocker=" + renderListPopulationBlocker
                + " traversalDispatchAllowed=" + traversalDispatchAllowed
                + " initialTraversalDispatch=" + initialTraversalDispatch
                + " remainingTraversalDispatchesRan=" + remainingTraversalDispatchesRan
                + " populationThisFrame=" + this.lastRenderListPopulationThisFrame
                + " readbackScheduledThisFrame=" + traversalReadbacksScheduled
                + " readbackPending=" + this.renderListCounterReadbackPending
                + " readbackValid=" + this.lastRenderListReadbackValid
                + " readbackReason=" + this.lastRenderListReadbackReason;
        String stateSnapshot = "rawRenderListVisibleCount=" + this.lastRawVisibleSectionCount
                + ";renderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + ";clampedVisibleCount=" + Math.max(0, Math.min(renderList.getLastVisibleCount(), renderList.getMaxEntryCount()))
                + ";maxEntryCount=" + renderList.getMaxEntryCount()
                + ";topNodeCount=" + topNodeCount
                + ";scratchQueueASeededCount=" + frameInit.scratchQueueASeededCount()
                + ";renderListPopulationBlocker=" + renderListPopulationBlocker
                + ";traversalDispatchAllowed=" + traversalDispatchAllowed
                + ";initialTraversalDispatch=" + initialTraversalDispatch
                + ";remainingTraversalDispatchesRan=" + remainingTraversalDispatchesRan
                + ";populationThisFrame=" + this.lastRenderListPopulationThisFrame
                + ";readbackScheduledThisFrame=" + traversalReadbacksScheduled
                + ";readbackPending=" + this.renderListCounterReadbackPending
                + ";readbackValid=" + this.lastRenderListReadbackValid
                + ";readbackReason=" + this.lastRenderListReadbackReason;
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
