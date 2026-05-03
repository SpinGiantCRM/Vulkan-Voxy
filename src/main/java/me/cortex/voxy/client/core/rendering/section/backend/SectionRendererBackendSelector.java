package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.rendering.hierachical.MDICSectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

public final class SectionRendererBackendSelector {
    private static final String VULKANMOD_MOD_ID = "vulkanmod";
    private static final String BERYL_MOD_ID = "beryl";

    private SectionRendererBackendSelector() {
    }

    public static SectionRendererBackend getActiveBackend() {
        boolean vulkanModLoaded = FabricLoader.getInstance().isModLoaded(VULKANMOD_MOD_ID);
        boolean berylLoaded = FabricLoader.getInstance().isModLoaded(BERYL_MOD_ID);

        if (vulkanModLoaded && berylLoaded) {
            Logger.info("Detected VulkanMod and Beryl. Vulkan backend is not implemented yet; falling back to OPENGL_MDIC.");
        }

        return SectionRendererBackend.OPENGL_MDIC;
    }

    public static SectionRendererBackendContext getContextForActiveBackend() {
        return getContext(getActiveBackend());
    }

    public static SectionRendererBackendContext getContext(SectionRendererBackend backend) {
        return switch (backend) {
            case OPENGL_MDIC -> new SectionRendererBackendContext() {
                @Override
                public AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRendererFactory() {
                    return MDICSectionRenderer.FACTORY;
                }

                @Override
                public IGeometryData createGeometryData() {
                    return new MDICSectionGeometryData(1 << 20, RenderResourceReuse.getOrCreateGeometryBuffer());
                }

                @Override
                public MDICSectionGeometrySyncBackend createGeometrySyncBackend() {
                    return new MDICSectionGeometrySyncBackend();
                }

                @Override
                public void releaseGeometryData(IGeometryData geometryData) {
                    var mdicGeometryData = (MDICSectionGeometryData) geometryData;
                    if (mdicGeometryData.isExternalGeometryBuffer) {
                        RenderResourceReuse.giveBackGeometryBuffer(mdicGeometryData.getGeometryBuffer());
                    }
                }
            };
            case VULKANMOD_BERYL -> throw new UnsupportedOperationException("VULKANMOD_BERYL section renderer backend is not implemented yet");
        };
    }
}
