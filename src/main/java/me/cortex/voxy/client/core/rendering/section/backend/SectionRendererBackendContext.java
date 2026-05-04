package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.SectionGeometrySyncBackend;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import java.util.function.BooleanSupplier;

public interface SectionRendererBackendContext {
    RenderBackendStateGuard enterConstructionStateGuard();

    default boolean supportsGlModelBaking() {
        return true;
    }

    default boolean supportsGlDownloadStream() {
        return true;
    }

    boolean usesMeshlets();

    AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory();

    IGeometryData createGeometryData();

    SectionGeometrySyncBackend createGeometrySyncBackend();

    SectionRenderBackendRuntime createBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen);

    BooleanSupplier createFrexWorkSupplier(AsyncNodeManager nodeManager, RenderGenerationService renderGen, ModelBakerySubsystem modelService);

    SectionRenderPipeline createPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier);

    void releaseGeometryData(IGeometryData geometryData);
}
