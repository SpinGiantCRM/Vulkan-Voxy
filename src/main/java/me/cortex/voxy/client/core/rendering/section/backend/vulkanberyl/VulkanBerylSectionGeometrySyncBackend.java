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
        return requireVulkanGeometryData(geometryData).getMaxCapacity();
    }

    @Override
    public void applyGeometrySync(IGeometryData geometryData, AsyncNodeManager.SyncResults results) {
        VulkanBerylSectionGeometryData vulkanGeometryData = requireVulkanGeometryData(geometryData);
        vulkanGeometryData.setSectionCount(results.getGeometrySectionCount());

        if (results.getUsedGeometry() > vulkanGeometryData.getMaxCapacity()) {
            throw new IllegalStateException("Vulkan/Beryl geometry usage exceeds capacity: used=" + results.getUsedGeometry() + ", max=" + vulkanGeometryData.getMaxCapacity());
        }

        if (results.hasGeometryUploadWork()) {
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
