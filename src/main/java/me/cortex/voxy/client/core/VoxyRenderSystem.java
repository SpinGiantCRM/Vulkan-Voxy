package me.cortex.voxy.client.core;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.ChunkBoundsRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.VoxyFogParameters;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderBackendRuntime;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRendererBackendContext;
import me.cortex.voxy.client.core.rendering.section.backend.SectionRendererBackendSelector;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;


public class VoxyRenderSystem {
    private final WorldEngine worldIn;


    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final SectionRenderBackendRuntime backendRuntime;


    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundsRenderer chunkBoundRenderer;

    private final ViewportSelector<?> viewportSelector;

    private final SectionRenderPipeline pipeline;
    private final RenderProperties properties;
    private final BooleanSupplier frexWorkSupplier;
    private long renderEntryCount;
    private long renderSkippedCount;
    private String lastRenderSkipReason = "none";
    private long viewportCreateSuccessCount;
    private long viewportCreateFailureCount;
    private long enterRenderFrameAttemptedCount;
    private long enterRenderFrameSucceededCount;
    private long runPipelineAttemptedCount;

    private static SectionRendererBackendContext getRenderBackendContext() {
        return SectionRendererBackendSelector.getContextForActiveBackend();
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        try {
            this.worldIn = world;

            this.properties = RenderProperties.getRenderProperties();
            var backendContext = getRenderBackendContext();
            try (var stateGuard = backendContext.enterConstructionStateGuard()) {
                this.modelService = new ModelBakerySubsystem(world.getMapper(), backendContext.supportsGlModelBaking());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, backendContext.usesMeshlets());

                this.geometryData = backendContext.createGeometryData();

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen, backendContext.createGeometrySyncBackend());
                this.backendRuntime = backendContext.createBackendRuntime(this.nodeManager, this.renderGen);
                this.frexWorkSupplier = backendContext.createFrexWorkSupplier(this.nodeManager, this.renderGen, this.modelService);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = backendContext.createPipeline(this.properties, this.backendRuntime, this.frexWorkSupplier);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service

            //Late stage traversal compile for shaders with taa
            if (this.pipeline instanceof AbstractRenderPipeline abstractPipeline) {
                this.backendRuntime.lateStageCompile(abstractPipeline);
            }

            var backendFactory = backendContext.getRendererFactory();
            var sectionRenderer = backendFactory.create(new AbstractSectionRenderer.CreateContext(this.pipeline, this.modelService.getStore(), this.geometryData));
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;

                //Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {//TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = this.pipeline.createChunkBoundsRenderer();

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }
    }


    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, VoxyFogParameters fogParameters, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            this.viewportCreateFailureCount++;
            this.lastRenderSkipReason = "viewport_selector_returned_null";
            return null;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        //cameraY += 100;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection);

        var viewportSize = this.pipeline.getRenderViewportSize();
        int width = viewportSize.width();
        int height = viewportSize.height();

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            this.viewportCreateFailureCount++;
            this.lastRenderSkipReason = "viewport_dimensions_zero";
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }
        this.viewportCreateSuccessCount++;

        return viewport;
    }



    public Viewport<?> setupViewportVanillaFallback(Matrix4fc vanillaProjection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        return this.setupViewport(vanillaProjection, modelView, createNeutralFogParameters(), cameraX, cameraY, cameraZ);
    }

    private static VoxyFogParameters createNeutralFogParameters() {
        return VoxyFogParameters.NEUTRAL;
    }
    public void renderOpaque(Viewport<?> viewport) {
        this.renderEntryCount++;
        if (viewport == null) {
            this.renderSkippedCount++;
            this.lastRenderSkipReason = "viewport_null";
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            this.renderSkippedCount++;
            this.lastRenderSkipReason = "viewport_invalid_dimensions";
            return;//Only render on valid viewport
        }
        this.lastRenderSkipReason = "none";

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();//Start marker
        TimingStatistics.main.start();

        this.enterRenderFrameAttemptedCount++;
        try (var stateGuard = this.pipeline.enterFrameStateGuard();
             var frame = this.pipeline.enterRenderFrame(viewport)) {
            this.enterRenderFrameSucceededCount++;
            //this.autoBalanceSubDivSize();

            this.pipeline.preSetup(viewport);

            TimingStatistics.E.start();
            this.pipeline.runPreMainDepthPass(viewport, this.chunkBoundRenderer);
            TimingStatistics.E.stop();


            GPUTiming.INSTANCE.marker();
            //The entire rendering pipeline (excluding the chunkbound thing)
            this.runPipelineAttemptedCount++;
            this.pipeline.runPipeline(viewport, frame);
            GPUTiming.INSTANCE.marker();
        }


        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        //As much dynamic runtime stuff here
        {
            this.pipeline.tickPostFrameUploads();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());//While FF is active, run until everything is processed
            TimingStatistics.H.start();
            //Done here as is allows less gl state resetup
            do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }
        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        TimingStatistics.all.stop();

        //TimingStatistics.I.start();
        //glFlush();
        //TimingStatistics.I.stop();

        /*
        TimingStatistics.F.start();
        this.postProcessing.setup(viewport.width, viewport.height, boundFB);
        TimingStatistics.F.stop();

        this.renderer.renderFarAwayOpaque(viewport, this.chunkBoundRenderer.getDepthBoundTexture());


        TimingStatistics.F.start();
        //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or breaking _something_ am not sure what
        this.postProcessing.computeSSAO(viewport.MVP);
        TimingStatistics.F.stop();

        TimingStatistics.G.start();
        //We can render the translucent directly after as it is the furthest translucent objects
        this.renderer.renderFarAwayTranslucent(viewport, this.chunkBoundRenderer.getDepthBoundTexture());
        TimingStatistics.G.stop();


        TimingStatistics.F.start();
        this.postProcessing.renderPost(viewport, matrices.projection(), boundFB);
        TimingStatistics.F.stop();
         */
    }



    private void autoBalanceSubDivSize() {
        //only increase quality while there are very few mesh queues, this stops,
        // e.g. while flying and is rendering alot of low quality chunks
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        //Auto fps targeting
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    /*
    private static float getGameFoV() {
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;
        return gameRenderer.getMainCamera().getFov();
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        //TODO: use the existing projection matrix use mulLocal by the inverse of the projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        projection.setPerspective(getGameFoV() * 0.01745329238474369f,
                (float) client.getWindow().getWidth() / (float)client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    //TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        //THis is a wild and insane problem to have
        // at short render distances the vanilla terrain doesnt end up covering the 16f near plane voxy uses
        // meaning that it explodes (due to near plane clipping).. _badly_ with the rastered culling being wrong in rare cases for the immediate
        // sections rendered after the vanilla render distance
        float nearVoxy = getRenderDistance()<=32.0f?8f:16f;
        nearVoxy = VoxyClient.disableSodiumChunkRender()?0.1f:nearVoxy;

        return base.mulLocal(
                Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix.invert(new Matrix4f()),
                new Matrix4f()
        ).mulLocal(makeProjectionMatrix(nearVoxy, 16*3000));
    }*/

    private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base) {

        //this jank is to capture the extra crap they inject like viewbobbing
        var rawMCProj = Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float near = getRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;

        float far = 16*3000;

        /* jank way of just modifying the base raw
        if (true) {
            return new Matrix4f(base)
                    .m22((far + near) / (near - far))
                    .m32((far+far) * near / (near - far));
        }*/

        //Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));//the +1 is to cover the outer ring of chunks when rendering a circle
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("VoxyRenderSystem render entry count: " + this.renderEntryCount);
        debug.add("VoxyRenderSystem render skipped count: " + this.renderSkippedCount);
        debug.add("VoxyRenderSystem last render skip reason: " + this.lastRenderSkipReason);
        debug.add("VoxyRenderSystem viewport creation success/failure: " + this.viewportCreateSuccessCount + "/" + this.viewportCreateFailureCount);
        debug.add("VoxyRenderSystem enterRenderFrame attempted/succeeded: " + this.enterRenderFrameAttemptedCount + "/" + this.enterRenderFrameSucceededCount);
        debug.add("VoxyRenderSystem runPipeline attempted: " + this.runPipelineAttemptedCount);
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        var backendContext = getRenderBackendContext();
        if (backendContext.supportsGlDownloadStream()) {
            Logger.info("Flushing download stream");
            DownloadStream.INSTANCE.flushWaitClear();
        }
        Logger.info("Shutting down rendering");
        try {
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.backendRuntime.free();
            this.geometryData.free();
            backendContext.releaseGeometryData(this.geometryData);

            this.chunkBoundRenderer.free();

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}



        if (backendContext.supportsGlDownloadStream()) {
            Logger.info("Flushing download stream");
            DownloadStream.INSTANCE.flushWaitClear();
        }

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
