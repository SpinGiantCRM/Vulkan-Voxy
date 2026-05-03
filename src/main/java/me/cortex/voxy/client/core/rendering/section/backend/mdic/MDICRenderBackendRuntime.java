package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL42.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_PIXEL_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;

public class MDICRenderBackendRuntime implements SectionRenderBackendRuntime {
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    public MDICRenderBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeManager = nodeManager;
        this.nodeCleaner = new NodeCleaner(nodeManager);
        this.traversal = new HierarchicalOcclusionTraverser(nodeManager, this.nodeCleaner, renderGen);
    }

    @Override
    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        this.traversal.lateStageCompile(pipeline);
    }

    @Override
    public void doPrimaryWork(Viewport<?> viewport, int depthBuffer, BooleanSupplier frexStillHasWork) {
        MDICViewport.require(viewport).depthResources.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeMetadataStore(), this.nodeCleaner);
            this.nodeCleaner.tick(this.traversal.getNodeBuffer());

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (frexStillHasWork.getAsBoolean());
    }

    @Override
    public void addDebug(List<String> debug) {
        this.traversal.addDebug(debug);
    }

    @Override
    public void free() {
        this.traversal.free();
        this.nodeCleaner.free();
    }
}
