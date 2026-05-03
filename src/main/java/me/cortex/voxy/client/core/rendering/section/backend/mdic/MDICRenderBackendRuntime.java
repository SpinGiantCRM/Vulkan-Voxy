package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;

public class MDICRenderBackendRuntime implements SectionRenderBackendRuntime {
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    public MDICRenderBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeCleaner = new NodeCleaner(nodeManager);
        this.traversal = new HierarchicalOcclusionTraverser(nodeManager, this.nodeCleaner, renderGen);
    }

    @Override
    public NodeCleaner getNodeCleaner() {
        return this.nodeCleaner;
    }

    @Override
    public HierarchicalOcclusionTraverser getTraversal() {
        return this.traversal;
    }

    @Override
    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        this.traversal.lateStageCompile(pipeline);
    }

    @Override
    public void free() {
        this.traversal.free();
        this.nodeCleaner.free();
    }
}
