package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

public final class VulkanBerylSectionGeometryData implements IGeometryData {
    private final int maxSectionCount;
    private final long maxCapacity;
    private int sectionCount;
    private boolean freed;

    public VulkanBerylSectionGeometryData(int maxSectionCount, long maxCapacity) {
        if (maxSectionCount < 0) {
            throw new IllegalArgumentException("maxSectionCount must be non-negative");
        }
        if (maxCapacity < 0) {
            throw new IllegalArgumentException("maxCapacity must be non-negative");
        }
        this.maxSectionCount = maxSectionCount;
        this.maxCapacity = maxCapacity;
    }

    @Override
    public int getSectionCount() {
        return this.sectionCount;
    }

    @Override
    public void free() {
        this.freed = true;
        this.sectionCount = 0;
    }

    @Override
    public long getMaxCapacity() {
        return this.maxCapacity;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public boolean isFreed() {
        return this.freed;
    }
}
