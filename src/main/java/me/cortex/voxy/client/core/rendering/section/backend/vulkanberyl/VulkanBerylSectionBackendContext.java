package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRendererBackendContext;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

import java.util.function.BooleanSupplier;

public final class VulkanBerylSectionBackendContext implements SectionRendererBackendContext {
    private static UnsupportedOperationException notImplemented(String component) {
        return new UnsupportedOperationException("VULKANMOD_BERYL " + component + " is not implemented yet");
    }

    @Override
    // Vulkan/Beryl upload/render wiring is currently quad-section based, and no meshlet renderer path exists here yet.
    // Keep RenderDataFactory output in the default section-geometry layout until Vulkan/Beryl meshlet consumers are implemented.
    public boolean usesMeshlets() {
        return false;
    }

    @Override
    public AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory() {
        return VulkanBerylSectionRenderer.FACTORY;
    }

    @Override
    public IGeometryData createGeometryData() {
        return new VulkanBerylSectionGeometryData(1 << 20, 1L << 32);
    }

    @Override
    public SectionGeometrySyncBackend createGeometrySyncBackend() {
        return new VulkanBerylSectionGeometrySyncBackend();
    }

    @Override
    public SectionRenderBackendRuntime createBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
        return new VulkanBerylRenderBackendRuntime(nodeManager, renderGen);
    }

    @Override
    public SectionRenderPipeline createPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier) {
        return new VulkanBerylSectionRenderPipeline(properties, backendRuntime, frexSupplier);
    }

    @Override
    public void releaseGeometryData(IGeometryData geometryData) {
        if (geometryData instanceof VulkanBerylSectionGeometryData vulkanGeometryData) {
            vulkanGeometryData.free();
            return;
        }
        throw new IllegalArgumentException("Expected VulkanBerylSectionGeometryData, got: " + geometryData.getClass().getName());
    }
}
