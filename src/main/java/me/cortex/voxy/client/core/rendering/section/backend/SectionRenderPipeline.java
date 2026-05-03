package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;

import java.util.List;

public interface SectionRenderPipeline {
    void setupExtraModelBakeryData(ModelBakerySubsystem modelService);
    void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer);
    RenderProperties getRenderProperties();
    float[] getRenderScalingFactor();
    void preSetup(Viewport<?> viewport);
    void runPreMainDepthPass(Viewport<?> viewport, ChunkBoundRenderer chunkBoundRenderer);
    void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight);
    void addDebug(List<String> debug);
    void free();
}
