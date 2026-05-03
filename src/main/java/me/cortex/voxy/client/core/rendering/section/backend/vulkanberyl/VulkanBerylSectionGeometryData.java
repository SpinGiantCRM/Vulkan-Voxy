package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public final class VulkanBerylSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;

    private final int maxSectionCount;
    private final Buffer geometryBuffer;
    private final Buffer metadataBuffer;
    private int sectionCount;
    private boolean freed;

    public VulkanBerylSectionGeometryData(int maxSectionCount, long maxCapacity) {
        if (maxSectionCount < 0) {
            throw new IllegalArgumentException("maxSectionCount must be non-negative");
        }
        if (maxCapacity < 0) {
            throw new IllegalArgumentException("maxCapacity must be non-negative");
        }
        if ((maxCapacity & 7L) != 0L) {
            throw new IllegalArgumentException("maxCapacity must be 8-byte aligned");
        }
        long metadataCapacity = Math.multiplyExact((long) maxSectionCount, SECTION_METADATA_SIZE);

        this.maxSectionCount = maxSectionCount;
        this.geometryBuffer = new Buffer("voxy_vulkanberyl_geometry", VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.geometryBuffer.createBuffer(maxCapacity);
        this.metadataBuffer = new Buffer("voxy_vulkanberyl_metadata", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.metadataBuffer.createBuffer(metadataCapacity);
    }

    @Override
    public int getSectionCount() {
        return this.sectionCount;
    }

    @Override
    public void free() {
        if (this.freed) {
            return;
        }
        this.geometryBuffer.scheduleFree();
        this.metadataBuffer.scheduleFree();
        this.freed = true;
        this.sectionCount = 0;
    }

    @Override
    public long getMaxCapacity() {
        return this.getGeometryCapacityBytes();
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public Buffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public Buffer getMetadataBuffer() {
        return this.metadataBuffer;
    }

    public long getGeometryCapacityBytes() {
        return this.geometryBuffer.getBufferSize();
    }

    public void setSectionCount(int sectionCount) {
        if (this.freed) {
            throw new IllegalStateException("Cannot update sectionCount after free");
        }
        if (sectionCount < 0) {
            throw new IllegalArgumentException("sectionCount must be non-negative");
        }
        if (sectionCount > this.maxSectionCount) {
            throw new IllegalArgumentException("sectionCount must not exceed maxSectionCount");
        }
        this.sectionCount = sectionCount;
    }

    public boolean isFreed() {
        return this.freed;
    }
}
