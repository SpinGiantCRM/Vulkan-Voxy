package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import java.util.function.BooleanSupplier;

public interface SectionRendererBackendContext {
    boolean usesMeshlets();

    AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory();

    IGeometryData createGeometryData();

    SectionGeometrySyncBackend createGeometrySyncBackend();

    SectionRenderBackendRuntime createBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen);

    SectionRenderPipeline createPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier);

    void releaseGeometryData(IGeometryData geometryData);
}
