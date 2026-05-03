package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

public interface SectionRendererBackendContext {
    AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory();

    IGeometryData createGeometryData();

    SectionGeometrySyncBackend createGeometrySyncBackend();

    void releaseGeometryData(IGeometryData geometryData);
}
