package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

public interface SectionRendererBackendContext {
    AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory();

    IGeometryData createGeometryData();

    SectionGeometrySyncBackend createGeometrySyncBackend();

    SectionRenderBackendRuntime createBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen);

    void releaseGeometryData(IGeometryData geometryData);
}
