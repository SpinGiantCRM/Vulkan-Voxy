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
    private final AsyncNodeManager nodeManager;
    private final RenderGenerationService renderGen;
    private final VulkanBerylNodeMetadataStore nodeMetadataStore;
    private final VulkanBerylTopLevelNodeStore topLevelNodeStore;
    private final VulkanBerylNodeCleanupSink nodeCleanupSink;
    private final VulkanBerylTraversalResources traversalResources;
    private final Buffer requestReadbackBuffer;
    private boolean requestReadbackPending;
    private VulkanBerylTraversalExecutor traversalExecutor;
    private boolean freed;

    public VulkanBerylRenderBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeManager = nodeManager;
        this.renderGen = renderGen;
        this.nodeMetadataStore = new VulkanBerylNodeMetadataStore(nodeManager.maxNodeCount);
        this.topLevelNodeStore = new VulkanBerylTopLevelNodeStore();
        this.nodeCleanupSink = new VulkanBerylNodeCleanupSink();
        this.traversalResources = new VulkanBerylTraversalResources();
        this.requestReadbackBuffer = new Buffer("voxy_vulkanberyl_traversal_request_readback", VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.HOST_MEM);
        this.requestReadbackBuffer.createBuffer(VulkanBerylTraversalResources.REQUEST_BUFFER_SIZE_BYTES);
        this.nodeManager.setTLNAddRemoveCallbacks(this.topLevelNodeStore::addTopLevelNode, this.topLevelNodeStore::removeTopLevelNode);
    }

    @Override
    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        // No-op until a Vulkan/Beryl section render pipeline exists.
    }

    @Override
    public void doPrimaryWork(Viewport<?> viewport, PrimaryRenderWorkContext workContext, BooleanSupplier frexStillHasWork) {
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
        this.resetRequestQueueCounter();
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
        this.freed = true;
    }
}
