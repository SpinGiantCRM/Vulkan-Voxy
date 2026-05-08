package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.ViewportRenderList;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

public final class VulkanBerylViewportRenderList implements ViewportRenderList {
    // Keep in sync with MDIC hierarchical traversal queue capacity.
    public static final int DEFAULT_MAX_ENTRY_COUNT = 200_000;
    public static final long COUNTER_OFFSET_BYTES = 0L;
    public static final long COUNTER_SIZE_BYTES = Integer.BYTES;

    private final int maxEntryCount;
    private final Buffer buffer;
    private int lastVisibleCount = -1;
    private boolean freed;

    public VulkanBerylViewportRenderList() {
        this(DEFAULT_MAX_ENTRY_COUNT);
    }

    public VulkanBerylViewportRenderList(int maxEntryCount) {
        if (maxEntryCount < 0) {
            throw new IllegalArgumentException("maxEntryCount must be non-negative");
        }

        long sizeBytes = Math.addExact(COUNTER_SIZE_BYTES, Math.multiplyExact(Integer.BYTES, (long) maxEntryCount));

        this.maxEntryCount = maxEntryCount;
        this.buffer = new Buffer("voxy_vulkanberyl_render_list", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.GPU_MEM);
        this.buffer.createBuffer(sizeBytes);
    }

    public Buffer getBuffer() {
        return this.buffer;
    }

    public int getMaxEntryCount() {
        return this.maxEntryCount;
    }

    @Override
    public long size() {
        return this.buffer.getBufferSize();
    }

    public boolean isFreed() {
        return this.freed;
    }

    public int getLastVisibleCount() {
        return this.lastVisibleCount;
    }

    void setLastVisibleCount(int lastVisibleCount) {
        this.lastVisibleCount = lastVisibleCount;
    }

    public void clearCounter() {
        if (this.freed) {
            throw new IllegalStateException("Render list is freed");
        }
        if (this.maxEntryCount < 0) {
            throw new IllegalStateException("maxEntryCount must be non-negative");
        }

        long sizeBytes = this.buffer.getBufferSize();
        if (sizeBytes < COUNTER_SIZE_BYTES) {
            throw new IllegalStateException("Render list buffer is too small to contain a counter: " + sizeBytes);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long zeroAddress = memAddress(stack.callocInt(1));
            VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
            uploader.upload(this.buffer, COUNTER_OFFSET_BYTES, zeroAddress, COUNTER_SIZE_BYTES);
            uploader.flush();
        }
    }

    public void free() {
        if (this.freed) {
            return;
        }
        this.buffer.scheduleFree();
        this.freed = true;
    }

    public static VulkanBerylViewportRenderList require(ViewportRenderList renderList) {
        if (renderList instanceof VulkanBerylViewportRenderList vulkanRenderList) {
            return vulkanRenderList;
        }
        throw new IllegalStateException("Viewport render list is not Vulkan/Beryl");
    }
}
