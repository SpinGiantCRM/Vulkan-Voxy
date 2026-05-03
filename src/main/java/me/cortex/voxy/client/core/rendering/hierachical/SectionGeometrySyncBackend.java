package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

public interface SectionGeometrySyncBackend {
    int getMaxSectionCount(IGeometryData geometryData);
    long getGeometryCapacityBytes(IGeometryData geometryData);
    void applyGeometrySync(IGeometryData geometryData, AsyncNodeManager.SyncResults results);
    void applyScatterWrites(IGeometryData geometryData, AsyncNodeManager.SyncResults results, GlBuffer nodeBuffer);
    void free();
}
