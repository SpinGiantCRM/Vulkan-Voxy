package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleanupSink;

import java.util.Objects;

final class VulkanBerylNodeCleanupSink implements NodeCleanupSink {
    @Override
    public void updateIds(IntOpenHashSet ids) {
        Objects.requireNonNull(ids, "ids");
        // Vulkan visibility/cleanup will be handled by the Vulkan traversal/visibility buffer path.
    }
}
