package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRendererBackendContext;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

import java.util.function.BooleanSupplier;

public final class VulkanBerylSectionBackendContext implements SectionRendererBackendContext {
    private static UnsupportedOperationException notImplemented(String component) {
        return new UnsupportedOperationException("VULKANMOD_BERYL " + component + " is not implemented yet");
    }

    @Override
    public AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory() {
        throw notImplemented("renderer factory");
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
        throw notImplemented("backend runtime");
    }

    @Override
    public AbstractRenderPipeline createPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier) {
        throw notImplemented("pipeline");
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
