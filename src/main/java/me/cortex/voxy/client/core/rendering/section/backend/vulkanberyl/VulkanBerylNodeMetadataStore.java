package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.hierachical.NodeMetadataStore;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

public final class VulkanBerylNodeMetadataStore implements NodeMetadataStore {
    public static final long NODE_METADATA_SIZE_BYTES = 16L;

    private final int maxNodeCount;
    private final Buffer nodeBuffer;
    private boolean freed;

    public VulkanBerylNodeMetadataStore(int maxNodeCount) {
        if (maxNodeCount < 0) {
            throw new IllegalArgumentException("maxNodeCount must be non-negative");
        }

        long nodeCapacityBytes = Math.multiplyExact((long) maxNodeCount, NODE_METADATA_SIZE_BYTES);

        this.maxNodeCount = maxNodeCount;
        this.nodeBuffer = new Buffer("voxy_vulkanberyl_node_metadata", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.nodeBuffer.createBuffer(nodeCapacityBytes);
    }

    public Buffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public long getNodeCapacityBytes() {
        return this.nodeBuffer.getBufferSize();
    }

    public int getMaxNodeCount() {
        return this.maxNodeCount;
    }

    public boolean isFreed() {
        return this.freed;
    }

    public void free() {
        if (this.freed) {
            return;
        }
        this.nodeBuffer.scheduleFree();
        this.freed = true;
    }
}
