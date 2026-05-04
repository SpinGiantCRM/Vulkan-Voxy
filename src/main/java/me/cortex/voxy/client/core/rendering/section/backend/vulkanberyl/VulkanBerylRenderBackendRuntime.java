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

        this.traversalResources.initializeQueueMetadata(this.topLevelNodeStore.getTopNodeCount());
        this.traversalResources.uploadTraversalUniforms(vulkanViewport, renderList, this.topLevelNodeStore, this.renderGen);
        this.traversalResources.seedInitialTraversalQueue(this.topLevelNodeStore);
        if (this.traversalExecutor == null) {
            this.traversalExecutor = new VulkanBerylTraversalExecutor(
                    this.traversalResources,
                    this.nodeMetadataStore,
                    this.topLevelNodeStore,
                    renderList,
                    null
            );
        }
        this.traversalExecutor.prepareTraversal(vulkanViewport);
        this.traversalExecutor.ensureTraversalPipeline();
        this.traversalExecutor.ensureTraversalDescriptorsBound();
        this.traversalExecutor.requireDispatchSupport();
        this.traversalExecutor.dispatchFirstTraversalIteration(vulkanWorkContext.frame().renderer());
        this.traversalExecutor.dispatchRemainingTraversalIterations(vulkanWorkContext.frame().renderer());
        this.scheduleRequestReadback(vulkanWorkContext.frame().renderer().getCommandBuffer());
        this.scheduleRenderListCounterReadback(vulkanWorkContext.frame().renderer().getCommandBuffer(), renderList);
        this.scheduleRenderListSampleReadback(vulkanWorkContext.frame().renderer().getCommandBuffer(), renderList);
        this.resetRequestQueueCounter();
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
        int count = requestBytes.getInt(0);
        if (count < 0) {
            Logger.error("Ignoring Vulkan/Beryl traversal request batch with negative count: " + count);
            this.requestReadbackPending = false;
            return;
        }

        int clampedCount = Math.min(count, this.traversalResources.getMaxRequestQueueSize());
        int maxByBuffer = (int) ((VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES - 8L) / 8L);
        clampedCount = Math.min(clampedCount, maxByBuffer);
        if (clampedCount != count) {
            Logger.error("Clamping Vulkan/Beryl traversal request count from " + count + " to " + clampedCount);
        }

        if (clampedCount > 0) {
            long batchSize = 8L + (long) clampedCount * 8L;
            MemoryBuffer batch = new MemoryBuffer(batchSize).cpyFrom(readbackPtr);
            this.nodeManager.submitRequestBatch(batch);
        }
        this.requestReadbackPending = false;
        this.requestReadbackCompleted = true;
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
        int count = MemoryUtil.memGetInt(readbackPtr);
        int maxEntryCount = renderList.getMaxEntryCount();
        if (count < 0) {
            Logger.error("Vulkan/Beryl render-list counter was negative, clamping to 0: " + count);
            count = 0;
        } else if (count > maxEntryCount) {
            Logger.error("Vulkan/Beryl render-list counter exceeded capacity, clamping: " + count + " > " + maxEntryCount);
            count = maxEntryCount;
        }
        renderList.setLastVisibleCount(count);
        this.lastVisibleSectionCount = count;
        this.lastVisibleSectionCapacity = maxEntryCount;
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
        if (visibleCount < 0) {
            Logger.error("Vulkan/Beryl render-list sample had negative visible count: " + visibleCount);
            visibleCount = 0;
        } else if (visibleCount > maxEntryCount) {
            Logger.error("Vulkan/Beryl render-list sample exceeded render-list capacity: " + visibleCount + " > " + maxEntryCount);
            visibleCount = maxEntryCount;
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
    }

    private void resetRequestQueueCounter() {
        ByteBuffer zeroCounter = MemoryUtil.memCalloc(4);
        try {
            VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
            uploader.upload(this.traversalResources.getRequestBuffer(), 0L, MemoryUtil.memAddress(zeroCounter), Integer.BYTES);
            uploader.flush();
        } finally {
            MemoryUtil.memFree(zeroCounter);
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl backend runtime: initialized (sync-only primary work; node metadata + TLN stores active)");
        debug.add("Vulkan/Beryl TLN: " + this.topLevelNodeStore.getTopNodeCount() + "/" + this.topLevelNodeStore.getMaxTopLevelNodeCount());
        debug.add("Vulkan/Beryl render list visible sections: " + this.lastVisibleSectionCount + "/" + this.lastVisibleSectionCapacity);
        debug.add("Vulkan/Beryl render-list sample: " + this.lastSampledRenderListEntryCount + "/" + this.lastSampledVisibleSectionCount
                + " entries, invalid=" + this.lastInvalidSampledRenderListEntryCount + ", first=" + this.lastSampledRenderListFirstEntries);
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
