package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;

import java.util.List;
import java.util.function.BooleanSupplier;

public final class VulkanBerylRenderBackendRuntime implements SectionRenderBackendRuntime {
    private final AsyncNodeManager nodeManager;
    private final RenderGenerationService renderGen;
    private final VulkanBerylNodeMetadataStore nodeMetadataStore;
    private boolean freed;

    public VulkanBerylRenderBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeManager = nodeManager;
        this.renderGen = renderGen;
        this.nodeMetadataStore = new VulkanBerylNodeMetadataStore(nodeManager.maxNodeCount);
    }

    @Override
    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        // No-op until a Vulkan/Beryl section render pipeline exists.
    }

    @Override
    public void doPrimaryWork(Viewport<?> viewport, int depthBuffer, BooleanSupplier frexStillHasWork) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL primary render work is not implemented yet");
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl backend runtime: initialized");
    }

    VulkanBerylNodeMetadataStore getNodeMetadataStore() {
        return this.nodeMetadataStore;
    }

    @Override
    public void free() {
        if (this.freed) {
            return;
        }
        this.nodeMetadataStore.free();
        this.freed = true;
    }
}
