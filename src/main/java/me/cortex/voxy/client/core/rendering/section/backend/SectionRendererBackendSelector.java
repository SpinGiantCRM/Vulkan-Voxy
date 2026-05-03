package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
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

    public static AbstractSectionRenderer.Factory<?, ?> getFactoryForActiveBackend() {
        return getFactory(getActiveBackend());
    }

    public static AbstractSectionRenderer.Factory<?, ?> getFactory(SectionRendererBackend backend) {
        return switch (backend) {
            case OPENGL_MDIC -> MDICSectionRenderer.FACTORY;
            case VULKANMOD_BERYL -> throw new UnsupportedOperationException("VULKANMOD_BERYL section renderer backend is not implemented yet");
        };
    }
}
