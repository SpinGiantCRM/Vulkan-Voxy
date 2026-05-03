package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundsRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.RenderBackendStateGuard;
import me.cortex.voxy.client.core.rendering.section.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.section.backend.RenderViewportSize;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.lwjgl.vulkan.VkExtent2D;

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
        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        int width = extent.width();
        int height = extent.height();

        return new RenderViewportSize(0, 0, width, height);
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        if (this.freed) {
            throw new IllegalStateException("VULKANMOD_BERYL pipeline is freed");
        }
        if (this.sectionRenderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL section renderer is not set");
        }
        if (!(viewport instanceof VulkanBerylViewport)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL requires VulkanBerylViewport");
        }
        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        this.requireCompatibleViewport(viewport, extent.width(), extent.height());
    }

    @Override
    public RenderFrameContext enterRenderFrame(Viewport<?> viewport) {
        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        int width = extent.width();
        int height = extent.height();
        this.requireCompatibleViewport(viewport, width, height);

        return new VulkanBerylRenderFrameContext(renderer, swapChain, width, height);
    }


    @Override
    public RenderBackendStateGuard enterFrameStateGuard() {
        return RenderBackendStateGuard.NO_OP;
    }

    @Override
    public ChunkBoundsRenderer createChunkBoundsRenderer() {
        return new VulkanBerylChunkBoundsRenderer();
    }

    @Override
    public void runPreMainDepthPass(Viewport<?> viewport, ChunkBoundsRenderer chunkBoundRenderer) {
        if (this.freed) {
            throw new IllegalStateException("VULKANMOD_BERYL pipeline is freed");
        }
        if (this.sectionRenderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL section renderer is not set");
        }
        if (!(viewport instanceof VulkanBerylViewport)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL requires VulkanBerylViewport");
        }
        if (!(chunkBoundRenderer instanceof VulkanBerylChunkBoundsRenderer)) {
            throw new IllegalArgumentException("VULKANMOD_BERYL requires VulkanBerylChunkBoundsRenderer");
        }

        Renderer renderer = this.requireRenderer();
        SwapChain swapChain = this.requireSwapChain(renderer);
        VkExtent2D extent = this.requireExtent(swapChain);
        this.requireValidExtent(extent);
        this.requireCompatibleViewport(viewport, extent.width(), extent.height());

        // MDIC uses this hook for an OpenGL chunk-bound/depth-bound pass. Vulkan/Beryl does not yet have an
        // equivalent depth-bound resource, so this is intentionally a validated no-op until a real pre-pass exists.
    }

    @Override
    public void runPipeline(Viewport<?> viewport, RenderFrameContext frame) {
        if (!(frame instanceof VulkanBerylRenderFrameContext)) {
            throw new IllegalArgumentException("Expected VulkanBerylRenderFrameContext");
        }
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

    private Renderer requireRenderer() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL renderer is not initialized");
        }
        return renderer;
    }

    private SwapChain requireSwapChain(Renderer renderer) {
        SwapChain swapChain = renderer.getSwapChain();
        if (swapChain == null || !swapChain.hasImages()) {
            throw new IllegalStateException("VULKANMOD_BERYL swapchain is unavailable");
        }
        return swapChain;
    }

    private VkExtent2D requireExtent(SwapChain swapChain) {
        VkExtent2D extent = swapChain.getExtent();
        if (extent == null) {
            throw new IllegalStateException("VULKANMOD_BERYL swapchain extent is unavailable");
        }
        return extent;
    }

    private void requireValidExtent(VkExtent2D extent) {
        int width = extent.width();
        int height = extent.height();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("VULKANMOD_BERYL swapchain extent is invalid: " + width + "x" + height);
        }
    }

    private void requireCompatibleViewport(Viewport<?> viewport, int width, int height) {
        if (viewport.width != width || viewport.height != height) {
            throw new IllegalStateException("VULKANMOD_BERYL viewport/swapchain extent mismatch: viewport=" +
                    viewport.width + "x" + viewport.height + ", swapchain=" + width + "x" + height);
        }
    }
}
