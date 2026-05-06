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
    private static final boolean ENABLE_TRAVERSAL_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_TRAVERSAL_DISPATCH", "true"));
    private static final boolean ENABLE_INITIAL_TRAVERSAL_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INITIAL_TRAVERSAL_DISPATCH", "true"));
    private static final boolean ENABLE_INDIRECT_TRAVERSAL_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_TRAVERSAL_DISPATCH", "false"));
    private static final int TRAVERSAL_MAX_ITERATIONS = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_MAX_ITERATIONS", "1")));
    private static final boolean TRAVERSAL_SMOKE_NOOP = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SMOKE_NOOP", "false"));
    private static final boolean TRAVERSAL_SMOKE_WRITE_KNOWN = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SMOKE_WRITE_KNOWN", "false"));
    private static final boolean TRAVERSAL_SHADER_SMOKE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SHADER_SMOKE", "false"));
    private static final boolean TRAVERSAL_SHADER_UNIFORM_SMOKE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRAVERSAL_SHADER_UNIFORM_SMOKE", "false"));
    private static final boolean VERBOSE_LOGS = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_VERBOSE_LOGS", "false"));
    private static final int RENDER_LIST_SAMPLE_LIMIT = 64;
    private static final int RENDER_LIST_DEBUG_FIRST_IDS = 8;
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
    private int lastVisibleSectionCapacity = -1;
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
        if (VERBOSE_LOGS || this.frameSequence % 120 == 0) Logger.info("[Voxy][VulkanBeryl] Traversal frame init: mode=command_buffer topNodeCount=" + topNodeCount
                + " firstDispatchSize=" + frameInit.firstDispatchSize()
                + " queueMetaInitialized=" + frameInit.queueMetaInitialized()
                + " scratchQueueASeededCount=" + frameInit.scratchQueueASeededCount()
                + " requestCounterCleared=" + frameInit.requestCounterCleared()
                + " renderListCounterCleared=" + frameInit.renderListCounterCleared()
                + " transferToComputeBarrier=" + frameInit.transferToComputeBarrier());
        this.traversalResources.uploadTraversalUniforms(vulkanViewport, renderList, this.topLevelNodeStore, this.renderGen, this.nodeManager.maxNodeCount, TRAVERSAL_SHADER_UNIFORM_SMOKE);
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
        if (TRAVERSAL_SMOKE_NOOP) {
            this.scheduleRequestReadback(commandBuffer);
            this.scheduleRenderListCounterReadback(commandBuffer, renderList);
            this.scheduleRenderListSampleReadback(commandBuffer, renderList);
            traversalReadbacksScheduled = true;
            Logger.info("[Voxy][VulkanBeryl] Traversal smoke mode active: noop=true writeKnown=" + TRAVERSAL_SMOKE_WRITE_KNOWN + " traversal shader dispatch skipped");
        } else if (ENABLE_TRAVERSAL_DISPATCH) {
            this.traversalExecutor.prepareTraversal(vulkanViewport);
            this.traversalExecutor.ensureTraversalPipeline(TRAVERSAL_SHADER_SMOKE || TRAVERSAL_SHADER_UNIFORM_SMOKE);
            if (TRAVERSAL_SHADER_SMOKE || TRAVERSAL_SHADER_UNIFORM_SMOKE) { Logger.info("[Voxy][VulkanBeryl] Traversal shader smoke dispatch active"); }
            this.traversalExecutor.ensureTraversalDescriptorsBound();
            this.traversalExecutor.requireDispatchSupport();
            if (ENABLE_INITIAL_TRAVERSAL_DISPATCH) {
                this.traversalExecutor.dispatchFirstTraversalIteration(vulkanWorkContext.frame().renderer());
                initialTraversalDispatch = this.traversalExecutor.didDispatchIterationZeroRun();
            } else {
                Logger.info("[Voxy][VulkanBeryl] Initial traversal dispatch skipped by safety gate");
            }
            if (ENABLE_INDIRECT_TRAVERSAL_DISPATCH && TRAVERSAL_MAX_ITERATIONS > 1) {
                this.traversalExecutor.dispatchRemainingTraversalIterations(vulkanWorkContext.frame().renderer(), TRAVERSAL_MAX_ITERATIONS);
                remainingTraversalDispatchesRan = this.traversalExecutor.getIndirectDispatchIterationCount();
            } else {
                Logger.info("[Voxy][VulkanBeryl] Traversal remaining iterations skipped by safety gate");
            }
            if (initialTraversalDispatch || remainingTraversalDispatchesRan > 0) {
                this.scheduleRequestReadback(commandBuffer);
                this.scheduleRenderListCounterReadback(commandBuffer, renderList);
                this.scheduleRenderListSampleReadback(commandBuffer, renderList);
                traversalReadbacksScheduled = true;
            }
        } else {
            Logger.info("[Voxy][VulkanBeryl] Traversal dispatch disabled globally; skipping traversal dispatches and traversal readbacks");
        }
        if (VERBOSE_LOGS || this.frameSequence % 120 == 0) Logger.info("[Voxy][VulkanBeryl] Traversal dispatch status: initialTraversalDispatch=" + initialTraversalDispatch
                + " remainingTraversalDispatchesRan=" + remainingTraversalDispatchesRan
                + " traversalReadbacksScheduled=" + traversalReadbacksScheduled);
        this.frameSequence++;
        this.frameId++;
        updateFrameSafetyState(renderList.getMaxEntryCount());
        this.publishSmokeStatus();
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
        if (VERBOSE_LOGS || this.frameSequence % 120 == 0) Logger.info("[Voxy][VulkanBeryl] Traversal readback: frameId=" + this.requestReadbackFrameId + " gpuCompletionConfirmed=true rawRequestCount=" + rawCount);
        int maxByBuffer = (int) ((VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES - 8L) / 8L);
        int acceptedCount = rawCount;
        boolean discarded = false;
        if (rawCount < 0 || rawCount > this.traversalResources.getMaxRequestQueueSize() || rawCount > maxByBuffer) {
            Logger.error("Invalid/corrupt Vulkan/Beryl traversal request count, discarding readback batch: rawRequestCount=" + rawCount
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
        if (VERBOSE_LOGS || this.frameSequence % 120 == 0) Logger.info("[Voxy][VulkanBeryl] Request readback: rawRequestCount=" + rawCount + " acceptedRequestCount=" + acceptedCount
                + " requestBatchDiscarded=" + discarded);
        this.requestReadbackPending = false;
        this.requestReadbackCompleted = !discarded;
    }

    private void scheduleRenderListCounterReadback(VkCommandBuffer commandBuffer, VulkanBerylViewportRenderList renderList) {
        if (commandBuffer == null) {
            throw new IllegalStateException("Cannot read render-list counter without a valid command buffer");
        }
        if (renderList.getBuffer().getBufferSize() < Integer.BYTES) {
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

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack).srcOffset(0L).dstOffset(0L).size(Integer.BYTES);
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
        if (VERBOSE_LOGS || this.frameSequence % 120 == 0) Logger.info("[Voxy][VulkanBeryl] Traversal readback: frameId=" + this.renderListReadbackFrameId + " gpuCompletionConfirmed=true rawRenderListCount=" + rawCount);
        int maxEntryCount = renderList.getMaxEntryCount();
        int acceptedCount = rawCount;
        boolean discarded = false;
        if (rawCount < 0 || rawCount > maxEntryCount) {
            Logger.error("Invalid/corrupt Vulkan/Beryl render-list counter readback, discarding: rawRenderListCount=" + rawCount
                    + " maxEntryCount=" + maxEntryCount);
            acceptedCount = 0;
            discarded = true;
        }
        this.lastRenderListCounterDiscarded = discarded;
        renderList.setLastVisibleCount(acceptedCount);
        this.lastVisibleSectionCount = acceptedCount;
        this.lastVisibleSectionCapacity = maxEntryCount;
        if (VERBOSE_LOGS || this.frameSequence % 120 == 0) Logger.info("[Voxy][VulkanBeryl] Render-list counter readback: rawRenderListCount=" + rawCount
                + " acceptedRenderListCount=" + acceptedCount + " renderListCounterDiscarded=" + discarded);
        this.renderListCounterReadbackPending = false;
        this.pendingRenderListCounterSource = null;
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
            Logger.error("Invalid/corrupt Vulkan/Beryl render-list sample counter, skipping sample: visibleCount=" + visibleCount
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
            Logger.error("Vulkan/Beryl render-list sample validation found invalid entries: " + invalidCount + "/" + sampledCount +
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
        String reason = allowIndirect ? "ready" : (!goodCounter ? "waiting_for_valid_render_list_readback" : (!noCorruption ? "previous_frame_corruption_detected" : (this.frameSequence < 2 ? "frame_stage_wait_n1" : "frame_stage_wait_n2")));
        LAST_FRAME_SAFETY_STATE = new FrameSafetyState(allowCmdgen, allowIndirect, reason);
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl backend runtime: initialized (primary traversal work; node metadata + TLN stores active)");
        debug.add("Vulkan/Beryl TLN: " + this.topLevelNodeStore.getTopNodeCount() + "/" + this.topLevelNodeStore.getMaxTopLevelNodeCount());
        debug.add("Vulkan/Beryl render list visible sections: " + this.lastVisibleSectionCount + "/" + this.lastVisibleSectionCapacity);
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
