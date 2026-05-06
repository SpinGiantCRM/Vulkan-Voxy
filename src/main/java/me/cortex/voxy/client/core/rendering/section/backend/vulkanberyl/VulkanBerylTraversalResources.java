package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

public final class VulkanBerylTraversalResources {
    // Keep in sync with MDIC hierarchical traversal limits.
    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_QUEUE_SIZE = 200_000;
    public static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER + 1;

    public static final long REQUEST_BUFFER_SIZE_BYTES = MAX_REQUEST_QUEUE_SIZE * 8L + 8L;
    public static final long QUEUE_META_BUFFER_SIZE_BYTES = 4L * 4L * MAX_ITERATIONS;
    public static final long SCRATCH_QUEUE_SIZE_BYTES = MAX_QUEUE_SIZE * 4L;
    public static final long UNIFORM_BUFFER_SIZE_BYTES = 1024L;
    public static final long QUEUE_INDEX_BUFFER_SIZE_BYTES = Integer.BYTES;
    public static final long RENDER_TRACKER_BUFFER_SIZE_BYTES = MAX_QUEUE_SIZE * Integer.BYTES;

    private final Buffer requestBuffer;
    private final Buffer queueMetaBuffer;
    private final Buffer scratchQueueA;
    private final Buffer scratchQueueB;
    private final Buffer uniformBuffer;
    private final Buffer queueIndexBuffer;
    private final Buffer renderTrackerBuffer;
    private boolean freed;

    public VulkanBerylTraversalResources() {
        requirePositive("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE);
        requirePositive("MAX_QUEUE_SIZE", MAX_QUEUE_SIZE);
        requirePositive("MAX_ITERATIONS", MAX_ITERATIONS);
        requirePositive("REQUEST_BUFFER_SIZE_BYTES", REQUEST_BUFFER_SIZE_BYTES);
        requirePositive("QUEUE_META_BUFFER_SIZE_BYTES", QUEUE_META_BUFFER_SIZE_BYTES);
        requirePositive("SCRATCH_QUEUE_SIZE_BYTES", SCRATCH_QUEUE_SIZE_BYTES);
        requirePositive("UNIFORM_BUFFER_SIZE_BYTES", UNIFORM_BUFFER_SIZE_BYTES);
        requirePositive("QUEUE_INDEX_BUFFER_SIZE_BYTES", QUEUE_INDEX_BUFFER_SIZE_BYTES);
        requirePositive("RENDER_TRACKER_BUFFER_SIZE_BYTES", RENDER_TRACKER_BUFFER_SIZE_BYTES);

        this.requestBuffer = new Buffer("voxy_vulkanberyl_traversal_request", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.GPU_MEM);
        this.requestBuffer.createBuffer(REQUEST_BUFFER_SIZE_BYTES);

        this.queueMetaBuffer = new Buffer("voxy_vulkanberyl_traversal_queue_meta", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, MemoryTypes.GPU_MEM);
        this.queueMetaBuffer.createBuffer(QUEUE_META_BUFFER_SIZE_BYTES);

        this.scratchQueueA = new Buffer("voxy_vulkanberyl_traversal_scratch_a", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.scratchQueueA.createBuffer(SCRATCH_QUEUE_SIZE_BYTES);

        this.scratchQueueB = new Buffer("voxy_vulkanberyl_traversal_scratch_b", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.scratchQueueB.createBuffer(SCRATCH_QUEUE_SIZE_BYTES);

        this.uniformBuffer = new Buffer("voxy_vulkanberyl_traversal_uniform", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.uniformBuffer.createBuffer(UNIFORM_BUFFER_SIZE_BYTES);

        this.queueIndexBuffer = new Buffer("voxy_vulkanberyl_traversal_queue_index", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.queueIndexBuffer.createBuffer(QUEUE_INDEX_BUFFER_SIZE_BYTES);

        this.renderTrackerBuffer = new Buffer("voxy_vulkanberyl_traversal_render_tracker", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.renderTrackerBuffer.createBuffer(RENDER_TRACKER_BUFFER_SIZE_BYTES);
    }

    public void initializeQueueMetadata(int topNodeCount) {
        requireNotFreed();
        if (topNodeCount < 0) {
            throw new IllegalArgumentException("topNodeCount must be non-negative");
        }

        int firstDispatchSize = (topNodeCount + 31) >>> 5;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var queueMetadata = stack.mallocInt(MAX_ITERATIONS * 4);
            for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                int baseIndex = iteration * 4;
                queueMetadata.put(baseIndex, iteration == 0 ? firstDispatchSize : 0);
                queueMetadata.put(baseIndex + 1, 1);
                queueMetadata.put(baseIndex + 2, 1);
                queueMetadata.put(baseIndex + 3, iteration == 0 ? topNodeCount : 0);
            }

            VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
            uploader.upload(this.queueMetaBuffer, 0L, memAddress(queueMetadata), QUEUE_META_BUFFER_SIZE_BYTES);
            uploader.flush();
        }
    }

    public void seedInitialTraversalQueue(VulkanBerylTopLevelNodeStore topLevelNodeStore) {
        requireNotFreed();
        if (topLevelNodeStore == null) {
            throw new IllegalArgumentException("topLevelNodeStore must not be null");
        }
        if (topLevelNodeStore.isFreed()) {
            throw new IllegalStateException("topLevelNodeStore is freed");
        }

        int topNodeCount = topLevelNodeStore.getTopNodeCount();
        if (topNodeCount < 0) {
            throw new IllegalStateException("topNodeCount must be non-negative");
        }
        if (topNodeCount > MAX_QUEUE_SIZE) {
            throw new IllegalStateException("topNodeCount exceeds traversal queue capacity: " + topNodeCount + " > " + MAX_QUEUE_SIZE);
        }

        VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long queueIndexPtr = memAddress(stack.ints(0));
            uploader.upload(this.queueIndexBuffer, 0L, queueIndexPtr, QUEUE_INDEX_BUFFER_SIZE_BYTES);

            if (topNodeCount > 0) {
                var topNodeIds = stack.mallocInt(topNodeCount);
                topLevelNodeStore.copyTopNodeIdsToAddress(memAddress(topNodeIds), topNodeCount);
                uploader.upload(this.scratchQueueA, 0L, memAddress(topNodeIds), (long) topNodeCount * Integer.BYTES);
            }
            uploader.flush();
        }
    }


    public void clearRequestCounter() {
        requireNotFreed();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long zeroCounterPtr = memAddress(stack.callocInt(1));
            VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
            uploader.upload(this.requestBuffer, 0L, zeroCounterPtr, Integer.BYTES);
            uploader.flush();
        }
    }

    public FrameInitStats recordTraversalFrameInitialization(VkCommandBuffer commandBuffer,
                                                             VulkanBerylViewportRenderList renderList,
                                                             VulkanBerylTopLevelNodeStore topLevelNodeStore,
                                                             int topNodeCount,
                                                             boolean smokeWriteKnown) {
        requireNotFreed();
        if (commandBuffer == null) throw new IllegalArgumentException("commandBuffer must not be null");
        if (renderList == null) throw new IllegalArgumentException("renderList must not be null");
        if (topLevelNodeStore == null) throw new IllegalArgumentException("topLevelNodeStore must not be null");
        int firstDispatchSize = (topNodeCount + 31) >>> 5;

        VK10.vkCmdFillBuffer(commandBuffer, renderList.getBuffer().getId(), 0L, Integer.BYTES, 0);
        VK10.vkCmdFillBuffer(commandBuffer, this.requestBuffer.getId(), 0L, Integer.BYTES, 0);
        VK10.vkCmdFillBuffer(commandBuffer, this.queueIndexBuffer.getId(), 0L, QUEUE_INDEX_BUFFER_SIZE_BYTES, 0);
        VK10.vkCmdFillBuffer(commandBuffer, this.queueMetaBuffer.getId(), 0L, QUEUE_META_BUFFER_SIZE_BYTES, 0);
        VK10.vkCmdFillBuffer(commandBuffer, this.renderTrackerBuffer.getId(), 0L, RENDER_TRACKER_BUFFER_SIZE_BYTES, 0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var queueMetadata = stack.mallocInt(MAX_ITERATIONS * 4);
            for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                int baseIndex = iteration * 4;
                queueMetadata.put(baseIndex, iteration == 0 ? firstDispatchSize : 0);
                queueMetadata.put(baseIndex + 1, 1);
                queueMetadata.put(baseIndex + 2, 1);
                queueMetadata.put(baseIndex + 3, iteration == 0 ? topNodeCount : 0);
            }
            VK10.vkCmdUpdateBuffer(commandBuffer, this.queueMetaBuffer.getId(), 0L, queueMetadata);

            int seededCount = 0;
            if (topNodeCount > 0) {
                var topNodeIds = stack.mallocInt(topNodeCount);
                topLevelNodeStore.copyTopNodeIdsToAddress(memAddress(topNodeIds), topNodeCount);
                VK10.vkCmdUpdateBuffer(commandBuffer, this.scratchQueueA.getId(), 0L, topNodeIds);
                seededCount = topNodeCount;
            }
            if (smokeWriteKnown) {
                var known = stack.mallocInt(1);
                known.put(0, 0);
                VK10.vkCmdUpdateBuffer(commandBuffer, renderList.getBuffer().getId(), 0L, known);
                VK10.vkCmdUpdateBuffer(commandBuffer, this.requestBuffer.getId(), 0L, known);
            }

            VkMemoryBarrier.Buffer transferToCompute = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, transferToCompute, null, null);

            return new FrameInitStats(firstDispatchSize, true, seededCount, true, true, true);
        }
    }

    public record FrameInitStats(int firstDispatchSize,
                                 boolean queueMetaInitialized,
                                 int scratchQueueASeededCount,
                                 boolean requestCounterCleared,
                                 boolean renderListCounterCleared,
                                 boolean transferToComputeBarrier) {}

    public void uploadQueueIndex(int queueIndex, VulkanBerylGeometryUploader uploader) {
        requireNotFreed();
        if (queueIndex < 0 || queueIndex >= MAX_ITERATIONS) {
            throw new IllegalArgumentException("queueIndex out of range: " + queueIndex);
        }
        if (uploader == null) {
            throw new IllegalArgumentException("uploader must not be null");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long queueIndexPtr = memAddress(stack.ints(queueIndex));
            uploader.upload(this.queueIndexBuffer, 0L, queueIndexPtr, QUEUE_INDEX_BUFFER_SIZE_BYTES);
            uploader.flush();
        }
    }

    public void recordTraversalUniformUpload(VkCommandBuffer commandBuffer, Viewport<?> viewport, VulkanBerylViewportRenderList renderList, VulkanBerylTopLevelNodeStore topLevelNodeStore, RenderGenerationService renderGen, int maxNodeCount, boolean uniformSmokeMode, int traversalStageLimit) {
        requireNotFreed();
        if (commandBuffer == null) {
            throw new IllegalArgumentException("commandBuffer must not be null");
        }
        if (viewport == null) {
            throw new IllegalArgumentException("viewport must not be null");
        }
        if (renderList == null) {
            throw new IllegalArgumentException("renderList must not be null");
        }
        if (renderList.isFreed()) {
            throw new IllegalStateException("renderList is freed");
        }
        if (topLevelNodeStore == null) {
            throw new IllegalArgumentException("topLevelNodeStore must not be null");
        }
        if (topLevelNodeStore.isFreed()) {
            throw new IllegalStateException("topLevelNodeStore is freed");
        }
        if (renderGen == null) {
            throw new IllegalArgumentException("renderGen must not be null");
        }
        if (this.uniformBuffer.getBufferSize() < UNIFORM_BUFFER_SIZE_BYTES) {
            throw new IllegalStateException("uniformBuffer must be at least 1024 bytes");
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            throw new IllegalStateException("viewport width/height must be positive for traversal uniform upload: " + viewport.width + "x" + viewport.height);
        }
        if (renderList.getMaxEntryCount() <= 0) {
            throw new IllegalStateException("renderQueueMaxSize must be positive");
        }
        if (maxNodeCount <= 0) {
            throw new IllegalStateException("maxNodeCount must be positive");
        }
        float sectionRenderDistance = VoxyConfig.CONFIG.sectionRenderDistance;
        if (!Float.isFinite(sectionRenderDistance) || sectionRenderDistance <= 0.0f) {
            throw new IllegalStateException("section render distance must be finite and positive: " + sectionRenderDistance);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var uniformData = stack.calloc((int) UNIFORM_BUFFER_SIZE_BYTES);
            long ptr = memAddress(uniformData);

            viewport.MVP.getToAddress(ptr); ptr += 4L * 4L * 4L;
            viewport.section.getToAddress(ptr); ptr += 4L * 3L;

            // Hi-Z packed levels is 0 until Vulkan/Beryl depth pre-pass resources exist.
            MemoryUtil.memPutInt(ptr, 0); ptr += Integer.BYTES;

            viewport.innerTranslation.getToAddress(ptr); ptr += 4L * 3L;

            final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;
            MemoryUtil.memPutFloat(ptr, screenspaceAreaDecreasingSize / (viewport.width * (float) viewport.height)); ptr += Float.BYTES;

            for (int i = 0; i < 6; i++) {
                viewport.frustumPlanes[i].getToAddress(ptr);
                ptr += 4L * 4L;
            }

            MemoryUtil.memPutInt(ptr, renderList.getMaxEntryCount()); ptr += Integer.BYTES;

            // frameId doubles as a controlled bring-up word: uniform smoke uses sentinel; real traversal uses stage limit.
            MemoryUtil.memPutInt(ptr, uniformSmokeMode ? 0x53554D4B : Math.max(0, traversalStageLimit)); ptr += Integer.BYTES;

            final double targetCount = 4000.0;
            double fillness = Math.max(0.0, (targetCount - renderGen.getTaskCount()) / targetCount);
            fillness *= fillness;
            final int requestSize = (int) Math.ceil(fillness * MAX_REQUEST_QUEUE_SIZE);
            int finalRequestSize = Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize));
            MemoryUtil.memPutInt(ptr, finalRequestSize); ptr += Integer.BYTES;

            MemoryUtil.memPutInt(ptr, Math.max(0, maxNodeCount)); ptr += Integer.BYTES;

            MemoryUtil.memPutFloat(ptr, (float) Math.pow(sectionRenderDistance * 16 * 32, 2));

            VK10.vkCmdUpdateBuffer(commandBuffer, this.uniformBuffer.getId(), 0L, uniformData);

            VkMemoryBarrier.Buffer transferToCompute = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, transferToCompute, null, null);
        }
    }


    public Buffer getRequestBuffer() { return this.requestBuffer; }
    public Buffer getQueueMetaBuffer() { return this.queueMetaBuffer; }
    public Buffer getScratchQueueA() { return this.scratchQueueA; }
    public Buffer getScratchQueueB() { return this.scratchQueueB; }
    public Buffer getUniformBuffer() { return this.uniformBuffer; }
    public Buffer getQueueIndexBuffer() { return this.queueIndexBuffer; }
    public Buffer getRenderTrackerBuffer() { return this.renderTrackerBuffer; }

    public int getMaxRequestQueueSize() { return MAX_REQUEST_QUEUE_SIZE; }
    public int getMaxQueueSize() { return MAX_QUEUE_SIZE; }
    public int getMaxIterations() { return MAX_ITERATIONS; }

    public boolean isFreed() { return this.freed; }

    public void free() {
        if (this.freed) {
            return;
        }
        this.requestBuffer.scheduleFree();
        this.queueMetaBuffer.scheduleFree();
        this.scratchQueueA.scheduleFree();
        this.scratchQueueB.scheduleFree();
        this.uniformBuffer.scheduleFree();
        this.queueIndexBuffer.scheduleFree();
        this.renderTrackerBuffer.scheduleFree();
        this.freed = true;
    }

    private static void requirePositive(String label, long value) {
        if (value <= 0L) {
            throw new IllegalStateException(label + " must be positive");
        }
    }

    private void requireNotFreed() {
        if (this.freed) {
            throw new IllegalStateException("Traversal resources are freed");
        }
    }
}
