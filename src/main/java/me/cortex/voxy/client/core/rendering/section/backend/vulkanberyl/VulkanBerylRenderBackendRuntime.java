package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.backend.PrimaryRenderWorkContext;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;

import java.util.List;
import java.util.function.BooleanSupplier;

public final class VulkanBerylRenderBackendRuntime implements SectionRenderBackendRuntime {
    private final AsyncNodeManager nodeManager;
    private final RenderGenerationService renderGen;
    private final VulkanBerylNodeMetadataStore nodeMetadataStore;
    private final VulkanBerylTopLevelNodeStore topLevelNodeStore;
    private final VulkanBerylNodeCleanupSink nodeCleanupSink;
    private final VulkanBerylTraversalResources traversalResources;
    private VulkanBerylTraversalExecutor traversalExecutor;
    private boolean freed;

    public VulkanBerylRenderBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeManager = nodeManager;
        this.renderGen = renderGen;
        this.nodeMetadataStore = new VulkanBerylNodeMetadataStore(nodeManager.maxNodeCount);
        this.topLevelNodeStore = new VulkanBerylTopLevelNodeStore();
        this.nodeCleanupSink = new VulkanBerylNodeCleanupSink();
        this.traversalResources = new VulkanBerylTraversalResources();
        this.nodeManager.setTLNAddRemoveCallbacks(this.topLevelNodeStore::addTopLevelNode, this.topLevelNodeStore::removeTopLevelNode);
    }

    @Override
    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        // No-op until a Vulkan/Beryl section render pipeline exists.
    }

    @Override
    public void doPrimaryWork(Viewport<?> viewport, PrimaryRenderWorkContext workContext, BooleanSupplier frexStillHasWork) {
        if (this.freed) {
            throw new IllegalStateException("Cannot execute runtime work after free");
        }
        if (!(workContext instanceof VulkanBerylPrimaryRenderWorkContext)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL runtime requires VulkanBerylPrimaryRenderWorkContext");
        }
        VulkanBerylViewport vulkanViewport = VulkanBerylViewport.require(viewport);
        VulkanBerylViewportRenderList renderList = VulkanBerylViewportRenderList.require(vulkanViewport.getRenderList());

        do {
            this.nodeManager.tick(this.nodeMetadataStore, this.nodeCleanupSink);
        } while (frexStillHasWork.getAsBoolean());

        this.traversalResources.initializeQueueMetadata(this.topLevelNodeStore.getTopNodeCount());
        this.traversalResources.uploadTraversalUniforms(vulkanViewport, renderList, this.topLevelNodeStore, this.renderGen);
        if (this.traversalExecutor == null) {
            this.traversalExecutor = new VulkanBerylTraversalExecutor(
                    this.traversalResources,
                    this.nodeMetadataStore,
                    this.topLevelNodeStore,
                    renderList,
                    null
            );
        }
        this.traversalExecutor.prepareTraversal(vulkanViewport);
        this.traversalExecutor.ensureTraversalPipeline();
        this.traversalExecutor.requireDispatchSupport();
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl backend runtime: initialized (sync-only primary work; node metadata + TLN stores active)");
        debug.add("Vulkan/Beryl TLN: " + this.topLevelNodeStore.getTopNodeCount() + "/" + this.topLevelNodeStore.getMaxTopLevelNodeCount());
    }

    VulkanBerylNodeMetadataStore getNodeMetadataStore() {
        return this.nodeMetadataStore;
    }

    VulkanBerylTopLevelNodeStore getTopLevelNodeStore() {
        return this.topLevelNodeStore;
    }

    VulkanBerylTraversalResources getTraversalResources() {
        return this.traversalResources;
    }

    @Override
    public void free() {
        if (this.freed) {
            return;
        }
        if (this.traversalExecutor != null) {
            this.traversalExecutor.free();
            this.traversalExecutor = null;
        }
        this.nodeMetadataStore.free();
        this.topLevelNodeStore.free();
        this.traversalResources.free();
        this.freed = true;
    }
}
