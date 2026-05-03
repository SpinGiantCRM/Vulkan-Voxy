package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeMetadataStore;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

public final class VulkanBerylSectionGeometrySyncBackend implements SectionGeometrySyncBackend {
    private static final long GEOMETRY_ELEMENT_SIZE_BYTES = 8L;
    private boolean freed;

    @Override
    public int getMaxSectionCount(IGeometryData geometryData) {
        return requireVulkanGeometryData(geometryData).getMaxSectionCount();
    }

    @Override
    public long getGeometryCapacityBytes(IGeometryData geometryData) {
        return requireVulkanGeometryData(geometryData).getGeometryCapacityBytes();
    }

    @Override
    public void applyGeometrySync(IGeometryData geometryData, AsyncNodeManager.SyncResults results) {
        VulkanBerylSectionGeometryData vulkanGeometryData = requireVulkanGeometryData(geometryData);
        vulkanGeometryData.setSectionCount(results.getGeometrySectionCount());

        if (results.getUsedGeometry() > vulkanGeometryData.getGeometryCapacityBytes()) {
            throw new IllegalStateException("Vulkan/Beryl geometry usage exceeds capacity: used=" + results.getUsedGeometry() + ", max=" + vulkanGeometryData.getGeometryCapacityBytes());
        }

        if (!results.hasGeometryUploadWork()) {
            return;
        }

        Buffer geometryBuffer = vulkanGeometryData.getGeometryBuffer();
        long geometryCapacityBytes = geometryBuffer.getBufferSize();
        long geometryElementCapacity = geometryCapacityBytes / GEOMETRY_ELEMENT_SIZE_BYTES;
        int copyCount = results.getGeometryUploadCopyCount();
        if (copyCount <= 0) {
            throw new IllegalStateException("Geometry upload work reported but copy count is " + copyCount);
        }

        int maxElementAccess = results.getGeometryUploadMaxElementAccess();
        if (maxElementAccess <= 0) {
            throw new IllegalStateException("Geometry upload work reported but max element access is non-positive");
        }
        if ((long) maxElementAccess > geometryElementCapacity) {
            throw new IllegalStateException("Geometry upload max element access exceeds geometry capacity: maxElementAccess=" + maxElementAccess + ", capacityElements=" + geometryElementCapacity);
        }

        long scratchDataAddress = results.getGeometryUploadScratchDataAddress();
        long scratchDataSizeBytes = results.getGeometryUploadScratchDataSizeBytes();
        if (scratchDataAddress == 0L || scratchDataSizeBytes <= 0L) {
            throw new IllegalStateException("Geometry upload scratch data buffer is unreadable");
        }
        if (vulkanGeometryData.isFreed()) {
            throw new IllegalStateException("Cannot apply geometry upload to freed Vulkan/Beryl geometry data");
        }

        VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
        results.forEachGeometryUploadCopy((destinationElementOffset, scratchDataElementOffset, elementCount) -> {
            if (destinationElementOffset < 0) {
                throw new IllegalStateException("Geometry upload destination offset is negative: " + destinationElementOffset);
            }
            if (scratchDataElementOffset < 0) {
                throw new IllegalStateException("Geometry upload scratch data offset is negative: " + scratchDataElementOffset);
            }
            if (elementCount <= 0) {
                throw new IllegalStateException("Geometry upload element count must be positive: " + elementCount);
            }

            long destinationOffsetBytes = Math.multiplyExact((long) destinationElementOffset, GEOMETRY_ELEMENT_SIZE_BYTES);
            long sourceOffsetBytes = Math.multiplyExact((long) scratchDataElementOffset, GEOMETRY_ELEMENT_SIZE_BYTES);
            long copySizeBytes = Math.multiplyExact((long) elementCount, GEOMETRY_ELEMENT_SIZE_BYTES);

            long sourceEndBytes = Math.addExact(sourceOffsetBytes, copySizeBytes);
            if (sourceEndBytes > scratchDataSizeBytes) {
                throw new IllegalStateException("Geometry upload source range exceeds scratch data size: end=" + sourceEndBytes + ", scratchSize=" + scratchDataSizeBytes);
            }

            long destinationEndBytes = Math.addExact(destinationOffsetBytes, copySizeBytes);
            if (destinationEndBytes > geometryCapacityBytes) {
                throw new IllegalStateException("Geometry upload destination range exceeds geometry capacity: end=" + destinationEndBytes + ", capacity=" + geometryCapacityBytes);
            }

            uploader.upload(geometryBuffer, destinationOffsetBytes, scratchDataAddress + sourceOffsetBytes, copySizeBytes);
        });
        uploader.flush();
    }

    @Override
    public void applyScatterWrites(IGeometryData geometryData, AsyncNodeManager.SyncResults results, NodeMetadataStore nodeMetadataStore) {
        requireVulkanGeometryData(geometryData);
        if (!results.hasScatterWriteWork()) {
            return;
        }
        throw new UnsupportedOperationException("Vulkan/Beryl node/metadata scatter writes are not implemented yet");
    }

    @Override
    public void free() {
        this.freed = true;
    }

    private static VulkanBerylSectionGeometryData requireVulkanGeometryData(IGeometryData geometryData) {
        if (geometryData instanceof VulkanBerylSectionGeometryData vulkanGeometryData) {
            return vulkanGeometryData;
        }
        throw new IllegalArgumentException("Expected VulkanBerylSectionGeometryData, got: " + geometryData.getClass().getName());
    }
}
