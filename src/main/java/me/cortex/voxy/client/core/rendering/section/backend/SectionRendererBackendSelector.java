package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.RenderPipelineFactory;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.MDICSectionGeometrySyncBackend;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl.VulkanBerylSectionBackendContext;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL30C.glGetIntegeri;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43C.glBindBufferBase;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;

public final class SectionRendererBackendSelector {
    private static final String VULKANMOD_MOD_ID = "vulkanmod";
    private static final String BERYL_MOD_ID = "beryl";

    private SectionRendererBackendSelector() {
    }

    public static SectionRendererBackend getActiveBackend() {
        boolean vulkanModLoaded = FabricLoader.getInstance().isModLoaded(VULKANMOD_MOD_ID);
        boolean berylLoaded = FabricLoader.getInstance().isModLoaded(BERYL_MOD_ID);

        if (vulkanModLoaded && berylLoaded) {
            Logger.info("Detected VulkanMod and Beryl. Selecting required VULKANMOD_BERYL backend.");
            return SectionRendererBackend.VULKANMOD_BERYL;
        }

        throw new IllegalStateException("This fork requires both VulkanMod and Beryl mods to run the VULKANMOD_BERYL backend.");
    }

    public static SectionRendererBackendContext getContextForActiveBackend() {
        return getContext(getActiveBackend());
    }

    public static SectionRendererBackendContext getContext(SectionRendererBackend backend) {
        return switch (backend) {
            case OPENGL_MDIC -> new SectionRendererBackendContext() {
                @Override
                public RenderBackendStateGuard enterConstructionStateGuard() {
                    int[] oldBufferBindings = new int[10];
                    for (int i = 0; i < oldBufferBindings.length; i++) {
                        oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
                    }

                    glFinish();
                    glFinish();

                    return () -> {
                        for (int i = 0; i < oldBufferBindings.length; i++) {
                            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
                        }

                        for (int i = 0; i < 12; i++) {
                            GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
                            GlStateManager._bindTexture(0);
                            glBindSampler(i, 0);
                        }
                    };
                }

                @Override
                public boolean usesMeshlets() {
                    return IUsesMeshlets.class.isAssignableFrom(MDICSectionRenderer.FACTORY.clz());
                }

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
                public SectionRenderBackendRuntime createBackendRuntime(AsyncNodeManager nodeManager, RenderGenerationService renderGen) {
                    return new MDICRenderBackendRuntime(nodeManager, renderGen);
                }

                @Override
                public BooleanSupplier createFrexWorkSupplier(AsyncNodeManager nodeManager, RenderGenerationService renderGen, ModelBakerySubsystem modelService) {
                    return () -> {
                        if (!VoxyClient.isFrexActive()) {
                            return false;
                        }
                        UploadStream.INSTANCE.tick();
                        modelService.tick(100_000_000);
                        glFinish();
                        return nodeManager.hasWork() || renderGen.getTaskCount() != 0 || !modelService.areQueuesEmpty();
                    };
                }

                @Override
                public SectionRenderPipeline createPipeline(RenderProperties properties, SectionRenderBackendRuntime backendRuntime, BooleanSupplier frexSupplier) {
                    return RenderPipelineFactory.createPipeline(properties, backendRuntime, frexSupplier);
                }

                @Override
                public void releaseGeometryData(IGeometryData geometryData) {
                    var mdicGeometryData = (MDICSectionGeometryData) geometryData;
                    if (mdicGeometryData.isExternalGeometryBuffer) {
                        RenderResourceReuse.giveBackGeometryBuffer(mdicGeometryData.getGeometryBuffer());
                    }
                }
            };
            case VULKANMOD_BERYL -> new VulkanBerylSectionBackendContext();
        };
    }
}
