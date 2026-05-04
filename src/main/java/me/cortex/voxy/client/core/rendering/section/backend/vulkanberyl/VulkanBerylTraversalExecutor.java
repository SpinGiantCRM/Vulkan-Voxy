package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.Viewport;
import net.beryl.render.ComputePipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VulkanBerylTraversalExecutor {
    private final VulkanBerylTraversalResources traversalResources;
    private final VulkanBerylNodeMetadataStore nodeMetadataStore;
    private final VulkanBerylTopLevelNodeStore topLevelNodeStore;
    private final VulkanBerylViewportRenderList renderList;
    private final Buffer sectionMetadataBuffer;

    public VulkanBerylTraversalExecutor(VulkanBerylTraversalResources traversalResources,
                                        VulkanBerylNodeMetadataStore nodeMetadataStore,
                                        VulkanBerylTopLevelNodeStore topLevelNodeStore,
                                        VulkanBerylViewportRenderList renderList,
                                        Buffer sectionMetadataBuffer) {
        this.traversalResources = Objects.requireNonNull(traversalResources, "traversalResources");
        this.nodeMetadataStore = Objects.requireNonNull(nodeMetadataStore, "nodeMetadataStore");
        this.topLevelNodeStore = Objects.requireNonNull(topLevelNodeStore, "topLevelNodeStore");
        this.renderList = Objects.requireNonNull(renderList, "renderList");
        this.sectionMetadataBuffer = sectionMetadataBuffer;

        requireLiveResources();
    }

    public void prepareTraversal(Viewport<?> viewport) {
        requireLiveResources();

        if (viewport == null) throw new IllegalArgumentException("viewport must not be null");
        if (viewport.width <= 0 || viewport.height <= 0) {
            throw new IllegalArgumentException("viewport extent must be positive: " + viewport.width + "x" + viewport.height);
        }

        requireBuffer("topLevelNodeStore.topNodeIdsBuffer", this.topLevelNodeStore.getTopNodeIdsBuffer());
        requireBuffer("nodeMetadataStore.nodeBuffer", this.nodeMetadataStore.getNodeBuffer());
        requireBuffer("traversalResources.requestBuffer", this.traversalResources.getRequestBuffer());
        requireBuffer("traversalResources.queueMetaBuffer", this.traversalResources.getQueueMetaBuffer());
        requireBuffer("traversalResources.scratchQueueA", this.traversalResources.getScratchQueueA());
        requireBuffer("traversalResources.scratchQueueB", this.traversalResources.getScratchQueueB());
        requireBuffer("traversalResources.uniformBuffer", this.traversalResources.getUniformBuffer());
        requireBuffer("renderList.buffer", this.renderList.getBuffer());
        if (this.sectionMetadataBuffer != null) {
            requireBuffer("sectionGeometryData.metadataBuffer", this.sectionMetadataBuffer);
        }

        if (this.renderList.getMaxEntryCount() <= 0) throw new IllegalStateException("renderList maxEntryCount must be > 0");
        if (this.topLevelNodeStore.getTopNodeCount() < 0) throw new IllegalStateException("topNodeCount must be non-negative");
    }

    public void requireDispatchSupport() {
        List<String> missing = new ArrayList<>();
        requireMethod(Renderer.class, "getCommandBuffer", missing);
        requireMethod(ComputePipeline.class, "bindDescriptorSets", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class);
        requireMethod(VK10.class, "vkCmdDispatch", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class, int.class, int.class);

        if (!missing.isEmpty()) {
            throw new UnsupportedOperationException("Vulkan/Beryl traversal compute dispatch integration missing required API: " + String.join(", ", missing));
        }
    }

    private void requireLiveResources() {
        if (this.traversalResources.isFreed()) throw new IllegalStateException("traversalResources is freed");
        if (this.nodeMetadataStore.isFreed()) throw new IllegalStateException("nodeMetadataStore is freed");
        if (this.topLevelNodeStore.isFreed()) throw new IllegalStateException("topLevelNodeStore is freed");
        if (this.renderList.isFreed()) throw new IllegalStateException("renderList is freed");
    }

    private static void requireBuffer(String name, Buffer buffer) {
        if (buffer == null) throw new IllegalStateException(name + " must not be null");
        if (buffer.getBufferSize() <= 0L) throw new IllegalStateException(name + " must have positive size");
        if (buffer.getId() == 0L) throw new IllegalStateException(name + " must have a valid Vulkan buffer id");
    }

    private static void requireMethod(Class<?> owner, String methodName, List<String> missing, Class<?>... args) {
        try {
            Method method = owner.getMethod(methodName, args);
            if (method == null) {
                missing.add(owner.getName() + "#" + methodName);
            }
        } catch (NoSuchMethodException e) {
            missing.add(owner.getName() + "#" + methodName);
        }
    }
}
