package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeMetadataStore;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

public final class VulkanBerylSectionGeometrySyncBackend implements SectionGeometrySyncBackend {
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

        if (results.hasGeometryUploadWork()) {
            int copyCount = results.getGeometryUploadCopyCount();
            if (copyCount <= 0) {
                throw new IllegalStateException("Geometry upload work reported but copy count is " + copyCount);
            }
            if (results.getGeometryUploadMaxElementAccess() <= 0) {
                throw new IllegalStateException("Geometry upload work reported but max element access is non-positive");
            }
            if (results.getGeometryUploadScratchHeaderAddress() == 0L || results.getGeometryUploadScratchHeaderSizeBytes() <= 0L) {
                throw new IllegalStateException("Geometry upload scratch header buffer is unreadable");
            }
            if (results.getGeometryUploadScratchDataAddress() == 0L || results.getGeometryUploadScratchDataSizeBytes() <= 0L) {
                throw new IllegalStateException("Geometry upload scratch data buffer is unreadable");
            }
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
            });
            throw new UnsupportedOperationException("Vulkan/Beryl geometry upload is not implemented yet");
        }
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
