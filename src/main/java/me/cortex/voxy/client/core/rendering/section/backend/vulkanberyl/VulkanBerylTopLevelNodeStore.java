package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

public final class VulkanBerylTopLevelNodeStore {
    public static final int DEFAULT_MAX_TOP_LEVEL_NODE_COUNT = 200_000;

    private final int maxTopLevelNodeCount;
    private final Buffer topNodeIdsBuffer;
    private final Int2IntOpenHashMap topNodeToIndex;
    private final int[] indexToTopNode;
    private int topNodeCount;
    private boolean freed;

    public VulkanBerylTopLevelNodeStore() {
        this(DEFAULT_MAX_TOP_LEVEL_NODE_COUNT);
    }

    public VulkanBerylTopLevelNodeStore(int maxTopLevelNodeCount) {
        if (maxTopLevelNodeCount < 0) {
            throw new IllegalArgumentException("maxTopLevelNodeCount must be non-negative");
        }

        long bufferSizeBytes = Math.multiplyExact((long) maxTopLevelNodeCount, Integer.BYTES);

        this.maxTopLevelNodeCount = maxTopLevelNodeCount;
        this.topNodeIdsBuffer = new Buffer("voxy_vulkanberyl_top_level_node_ids", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.GPU_MEM);
        this.topNodeIdsBuffer.createBuffer(bufferSizeBytes);
        this.topNodeToIndex = new Int2IntOpenHashMap(maxTopLevelNodeCount);
        this.topNodeToIndex.defaultReturnValue(-1);
        this.indexToTopNode = new int[maxTopLevelNodeCount];
    }

    public void addTopLevelNode(int id) {
        this.requireNotFreed();
        if (this.topNodeToIndex.containsKey(id)) {
            throw new IllegalStateException("Top-level node already present: " + id);
        }
        int index = this.topNodeCount;
        if (index >= this.maxTopLevelNodeCount) {
            throw new IllegalStateException("Top-level node capacity exceeded: " + this.maxTopLevelNodeCount);
        }

        this.uploadNodeId(index, id);
        this.indexToTopNode[index] = id;
        this.topNodeToIndex.put(id, index);
        this.topNodeCount = index + 1;
    }

    public void removeTopLevelNode(int id) {
        this.requireNotFreed();

        int removeIndex = this.topNodeToIndex.remove(id);
        if (removeIndex < 0) {
            throw new IllegalStateException("Top-level node not present: " + id);
        }

        int newCount = this.topNodeCount - 1;
        if (newCount < 0) {
            throw new IllegalStateException("Top-level node count underflow");
        }

        if (removeIndex != newCount) {
            int movedId = this.indexToTopNode[newCount];
            this.indexToTopNode[removeIndex] = movedId;
            this.topNodeToIndex.put(movedId, removeIndex);
            this.uploadNodeId(removeIndex, movedId);
        }

        this.topNodeCount = newCount;
    }

    public Buffer getTopNodeIdsBuffer() {
        return this.topNodeIdsBuffer;
    }

    public int getTopNodeCount() {
        return this.topNodeCount;
    }

    public int getMaxTopLevelNodeCount() {
        return this.maxTopLevelNodeCount;
    }

    public void copyTopNodeIdsToAddress(long destinationAddress, int count) {
        this.requireNotFreed();
        if (destinationAddress == 0L) {
            throw new IllegalArgumentException("destinationAddress must be non-zero");
        }
        if (count < 0 || count > this.topNodeCount) {
            throw new IllegalArgumentException("count must be in range [0, topNodeCount]");
        }

        long ptr = destinationAddress;
        for (int i = 0; i < count; i++) {
            MemoryUtil.memPutInt(ptr, this.indexToTopNode[i]);
            ptr += Integer.BYTES;
        }
    }

    public boolean isFreed() {
        return this.freed;
    }

    public void free() {
        if (this.freed) {
            return;
        }
        this.topNodeIdsBuffer.scheduleFree();
        this.freed = true;
        this.topNodeCount = 0;
        this.topNodeToIndex.clear();
    }

    private void uploadNodeId(int index, int id) {
        long offsetBytes = Math.multiplyExact((long) index, Integer.BYTES);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long idAddress = memAddress(stack.ints(id));
            VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
            uploader.upload(this.topNodeIdsBuffer, offsetBytes, idAddress, Integer.BYTES);
            uploader.flush();
        }
    }

    private void requireNotFreed() {
        if (this.freed) {
            throw new IllegalStateException("Top-level node store is freed");
        }
    }
}
