package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundsRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.RenderViewportSize;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class VulkanBerylSectionRenderPipeline implements SectionRenderPipeline {
    private static final float[] RENDER_SCALING_FACTOR = new float[] {1.0f, 1.0f};

    private final RenderProperties properties;
    private final SectionRenderBackendRuntime backendRuntime;
    private final BooleanSupplier frexSupplier;

    private AbstractSectionRenderer<?, ?> sectionRenderer;
    private boolean freed;

    public VulkanBerylSectionRenderPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier) {
        this.properties = properties;
        this.backendRuntime = backendRuntime;
        this.frexSupplier = frexSupplier;
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        // No-op for now until Vulkan/Beryl requires additional model bake data.
    }

    @Override
    public void setSectionRenderer(AbstractSectionRenderer<?, ?> sectionRenderer) {
        if (this.sectionRenderer != null) {
            throw new IllegalStateException("Section renderer is already set");
        }
        this.sectionRenderer = Objects.requireNonNull(sectionRenderer, "sectionRenderer");
    }

    @Override
    public float[] getRenderScalingFactor() {
        return RENDER_SCALING_FACTOR;
    }

    @Override
    public RenderProperties getRenderProperties() {
        return this.properties;
    }

    @Override
    public RenderViewportSize getRenderViewportSize() {
        throw new UnsupportedOperationException("VULKANMOD_BERYL render viewport size is not implemented yet");
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL pipeline pre-setup is not implemented yet");
    }

    @Override
    public ChunkBoundsRenderer createChunkBoundsRenderer() {
        return new VulkanBerylChunkBoundsRenderer();
    }

    @Override
    public void runPreMainDepthPass(Viewport<?> viewport, ChunkBoundsRenderer chunkBoundRenderer) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL depth pre-pass is not implemented yet");
    }

    @Override
    public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL pipeline rendering is not implemented yet");
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Vulkan/Beryl section pipeline: initialized (rendering unimplemented)");
    }

    @Override
    public void free() {
        this.freed = true;
    }
}
