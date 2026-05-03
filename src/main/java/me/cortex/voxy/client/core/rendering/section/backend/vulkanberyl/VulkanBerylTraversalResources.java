package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.common.world.WorldEngine;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.system.MemoryUtil.memAddress;
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

    private final Buffer requestBuffer;
    private final Buffer queueMetaBuffer;
    private final Buffer scratchQueueA;
    private final Buffer scratchQueueB;
    private final Buffer uniformBuffer;
    private boolean freed;

    public VulkanBerylTraversalResources() {
        requirePositive("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE);
        requirePositive("MAX_QUEUE_SIZE", MAX_QUEUE_SIZE);
        requirePositive("MAX_ITERATIONS", MAX_ITERATIONS);
        requirePositive("REQUEST_BUFFER_SIZE_BYTES", REQUEST_BUFFER_SIZE_BYTES);
        requirePositive("QUEUE_META_BUFFER_SIZE_BYTES", QUEUE_META_BUFFER_SIZE_BYTES);
        requirePositive("SCRATCH_QUEUE_SIZE_BYTES", SCRATCH_QUEUE_SIZE_BYTES);
        requirePositive("UNIFORM_BUFFER_SIZE_BYTES", UNIFORM_BUFFER_SIZE_BYTES);

        this.requestBuffer = new Buffer("voxy_vulkanberyl_traversal_request", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.GPU_MEM);
        this.requestBuffer.createBuffer(REQUEST_BUFFER_SIZE_BYTES);

        this.queueMetaBuffer = new Buffer("voxy_vulkanberyl_traversal_queue_meta", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.queueMetaBuffer.createBuffer(QUEUE_META_BUFFER_SIZE_BYTES);

        this.scratchQueueA = new Buffer("voxy_vulkanberyl_traversal_scratch_a", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.scratchQueueA.createBuffer(SCRATCH_QUEUE_SIZE_BYTES);

        this.scratchQueueB = new Buffer("voxy_vulkanberyl_traversal_scratch_b", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.scratchQueueB.createBuffer(SCRATCH_QUEUE_SIZE_BYTES);

        this.uniformBuffer = new Buffer("voxy_vulkanberyl_traversal_uniform", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.uniformBuffer.createBuffer(UNIFORM_BUFFER_SIZE_BYTES);
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

    public Buffer getRequestBuffer() { return this.requestBuffer; }
    public Buffer getQueueMetaBuffer() { return this.queueMetaBuffer; }
    public Buffer getScratchQueueA() { return this.scratchQueueA; }
    public Buffer getScratchQueueB() { return this.scratchQueueB; }
    public Buffer getUniformBuffer() { return this.uniformBuffer; }

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
