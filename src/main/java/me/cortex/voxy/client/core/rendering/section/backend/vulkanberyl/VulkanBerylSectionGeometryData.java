package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public final class VulkanBerylSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    public static final long MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES = Integer.MAX_VALUE - 7L;

    private final int maxSectionCount;
    private final Buffer geometryBuffer;
    private final Buffer metadataBuffer;
    private int sectionCount;
    private long usedGeometryBytes;
    private final int[] sectionMetadataMirror;
    private final boolean geometryCapacityCapped;
    private final long requestedGeometryCapacityBytes;
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
        long descriptorCompatibleCapacity = capGeometryCapacityForDescriptorCompatibility(maxCapacity);

        this.maxSectionCount = maxSectionCount;
        this.requestedGeometryCapacityBytes = maxCapacity;
        this.geometryCapacityCapped = descriptorCompatibleCapacity != maxCapacity;
        this.geometryBuffer = new Buffer("voxy_vulkanberyl_geometry", VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.geometryBuffer.createBuffer(descriptorCompatibleCapacity);
        this.metadataBuffer = new Buffer("voxy_vulkanberyl_metadata", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.metadataBuffer.createBuffer(metadataCapacity);
        this.sectionMetadataMirror = new int[Math.multiplyExact(maxSectionCount, SECTION_METADATA_SIZE / Integer.BYTES)];
    }



    public static long capGeometryCapacityForDescriptorCompatibility(long requestedCapacityBytes) {
        if (requestedCapacityBytes < 0L) {
            throw new IllegalArgumentException("requestedCapacityBytes must be non-negative");
        }
        long capped = Math.min(requestedCapacityBytes, MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES);
        return capped & ~7L;
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
        this.usedGeometryBytes = 0L;
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

    public long getMetadataCapacityBytes() {
        return this.metadataBuffer.getBufferSize();
    }

    public long getUsedGeometryBytes() {
        return this.usedGeometryBytes;
    }

    public void setUsedGeometryBytes(long usedGeometryBytes) {
        if (this.freed) {
            throw new IllegalStateException("Cannot update used geometry after free");
        }
        if (usedGeometryBytes < 0L || usedGeometryBytes > this.getGeometryCapacityBytes()) {
            throw new IllegalArgumentException("usedGeometryBytes out of range: " + usedGeometryBytes);
        }
        this.usedGeometryBytes = usedGeometryBytes;
    }

    public void mirrorSectionMetadataUpload(long destinationOffsetBytes, long sourceAddress, long copySizeBytes) {
        if (this.freed) {
            throw new IllegalStateException("Cannot mirror section metadata after free");
        }
        if ((destinationOffsetBytes & 3L) != 0L || (copySizeBytes & 3L) != 0L) {
            throw new IllegalArgumentException("Section metadata mirror writes must be uint-aligned");
        }
        long destinationEndBytes = Math.addExact(destinationOffsetBytes, copySizeBytes);
        if (destinationOffsetBytes < 0L || destinationEndBytes > this.getMetadataCapacityBytes()) {
            throw new IllegalArgumentException("Section metadata mirror write out of bounds: offset=" + destinationOffsetBytes + ", size=" + copySizeBytes);
        }
        int intOffset = Math.toIntExact(destinationOffsetBytes / Integer.BYTES);
        int intCount = Math.toIntExact(copySizeBytes / Integer.BYTES);
        for (int i = 0; i < intCount; i++) {
            this.sectionMetadataMirror[intOffset + i] = org.lwjgl.system.MemoryUtil.memGetInt(sourceAddress + (long) i * Integer.BYTES);
        }
    }

    public int getSectionMetadataInt(int sectionId, int wordIndex) {
        if (sectionId < 0 || sectionId >= this.maxSectionCount) {
            throw new IllegalArgumentException("sectionId out of range: " + sectionId);
        }
        if (wordIndex < 0 || wordIndex >= SECTION_METADATA_SIZE / Integer.BYTES) {
            throw new IllegalArgumentException("wordIndex out of range: " + wordIndex);
        }
        return this.sectionMetadataMirror[sectionId * (SECTION_METADATA_SIZE / Integer.BYTES) + wordIndex];
    }


    public boolean wasGeometryCapacityCapped() {
        return this.geometryCapacityCapped;
    }

    public long getRequestedGeometryCapacityBytes() {
        return this.requestedGeometryCapacityBytes;
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
