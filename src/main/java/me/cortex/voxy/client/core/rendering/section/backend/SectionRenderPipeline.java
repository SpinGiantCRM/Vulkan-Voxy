package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundsRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;

import java.util.List;

public interface SectionRenderPipeline {
    void setupExtraModelBakeryData(ModelBakerySubsystem modelService);
    void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer);
    RenderProperties getRenderProperties();
    RenderViewportSize getRenderViewportSize();
    float[] getRenderScalingFactor();
    void preSetup(Viewport<?> viewport);
    RenderFrameContext enterRenderFrame(Viewport<?> viewport);
    ChunkBoundsRenderer createChunkBoundsRenderer();
    void runPreMainDepthPass(Viewport<?> viewport, ChunkBoundsRenderer chunkBoundRenderer);
    void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight);
    void addDebug(List<String> debug);
    void free();
}
