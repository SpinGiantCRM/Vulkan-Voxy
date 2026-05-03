package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;

public interface SectionRenderBackendRuntime {
    NodeCleaner getNodeCleaner();

    HierarchicalOcclusionTraverser getTraversal();

    void lateStageCompile(AbstractRenderPipeline pipeline);

    void free();
}
