package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.Viewport;
import net.beryl.render.ComputePipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VulkanBerylTraversalExecutor {
    public static final String TRAVERSAL_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/hierarchical/traversal.comp";
    public static final String TRAVERSAL_SMOKE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/hierarchical/traversal_smoke.comp";
    private static final String TRAVERSAL_SHADER_NAME = "vulkanberyl/hierarchical/traversal";
    private static final String TRAVERSAL_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/hierarchical/traversal.json";
    private static final Pattern SHADER_LINE_PATTERN = Pattern.compile(":(\\d+):\\s+error:");
    private static final String FORCE_REAL_MAIN_RETURN_DEFINE = "VOXY_VULKAN_BERYL_TRAVERSAL_FORCE_REAL_MAIN_RETURN";
    private static final String TRAVERSAL_UNUSED_HIZ_PADDING_LABEL = "UnusedHiZPaddingBerylDenseLayout";
    private static final String STATIC_IMPORT_LEVEL_ENV = "VOXY_VULKAN_BERYL_TRAVERSAL_STATIC_IMPORT_LEVEL";
    private static final int STATIC_IMPORT_LEVEL = parseTraversalStaticImportLevel();
    private static final boolean FORCE_REAL_MAIN_RETURN = VulkanBerylEnvironment.flag(FORCE_REAL_MAIN_RETURN_DEFINE, false) || STATIC_IMPORT_LEVEL >= 0;

    public static final int HIZ_BINDING = 0;
    public static final int SCENE_UNIFORM_BINDING = 1;
    public static final int REQUEST_QUEUE_BINDING = 2;
    public static final int RENDER_QUEUE_BINDING = 3;
    public static final int NODE_DATA_BINDING = 4;
    public static final int NODE_QUEUE_INDEX_BINDING = 5;
    public static final int NODE_QUEUE_META_BINDING = 6;
    public static final int NODE_QUEUE_SOURCE_BINDING = 7;
    public static final int NODE_QUEUE_SINK_BINDING = 8;
    public static final int RENDER_TRACKER_BINDING = 9;
    private final VulkanBerylTraversalResources traversalResources;
    private final VulkanBerylNodeMetadataStore nodeMetadataStore;
    private final VulkanBerylTopLevelNodeStore topLevelNodeStore;
    private final VulkanBerylViewportRenderList renderList;
    private final Buffer sectionMetadataBuffer;
    private ComputePipeline traversalPipeline;
    private boolean descriptorsBound;
    private String descriptorCreationMode = "unknown";
    private String lastDescriptorFailure = "none";
    private boolean dispatchIterationZeroRan;
    private boolean dispatchIterationZeroSkipped;
    private int indirectDispatchIterationCount;
    private boolean descriptorDispatchDiagnosticsLogged;
    private boolean freed;

    public VulkanBerylTraversalExecutor(VulkanBerylTraversalResources traversalResources,
                                        VulkanBerylNodeMetadataStore nodeMetadataStore,
                                        VulkanBerylTopLevelNodeStore topLevelNodeStore,
                                        VulkanBerylViewportRenderList renderList,
                                        Buffer sectionMetadataBuffer) {
        this.traversalResources = Objects.requireNonNull(traversalResources, "traversalResources");
        this.nodeMetadataStore = Objects.requireNonNull(nodeMetadataStore, "nodeMetadataStore");
        this.topLevelNodeStore = Objects.requireNonNull(topLevelNodeStore, "topLevelNodeStore");
        this.renderList = Objects.requireNonNull(renderList, "renderList");
        this.sectionMetadataBuffer = sectionMetadataBuffer;

        requireLiveResources();
    }

    public void prepareTraversal(Viewport<?> viewport) {
        requireLiveResources();

        if (viewport == null) throw new IllegalArgumentException("viewport must not be null");
        if (viewport.width <= 0 || viewport.height <= 0) {
            throw new IllegalArgumentException("viewport extent must be positive: " + viewport.width + "x" + viewport.height);
        }

        requireBuffer("topLevelNodeStore.topNodeIdsBuffer", this.topLevelNodeStore.getTopNodeIdsBuffer());
        requireBuffer("nodeMetadataStore.nodeBuffer", this.nodeMetadataStore.getNodeBuffer());
        requireBuffer("traversalResources.requestBuffer", this.traversalResources.getRequestBuffer());
        requireBuffer("traversalResources.queueMetaBuffer", this.traversalResources.getQueueMetaBuffer());
        requireBuffer("traversalResources.scratchQueueA", this.traversalResources.getScratchQueueA());
        requireBuffer("traversalResources.scratchQueueB", this.traversalResources.getScratchQueueB());
        requireBuffer("traversalResources.uniformBuffer", this.traversalResources.getUniformBuffer());
        requireBuffer("renderList.buffer", this.renderList.getBuffer());
        if (this.sectionMetadataBuffer != null) {
            requireBuffer("sectionGeometryData.metadataBuffer", this.sectionMetadataBuffer);
        }

        if (this.renderList.getMaxEntryCount() <= 0) throw new IllegalStateException("renderList maxEntryCount must be > 0");
        if (this.topLevelNodeStore.getTopNodeCount() < 0) throw new IllegalStateException("topNodeCount must be non-negative");
    }

    public String getTraversalShaderResource(boolean smokeShader) {
        return smokeShader ? TRAVERSAL_SMOKE_SHADER_RESOURCE : TRAVERSAL_SHADER_RESOURCE;
    }


    public void ensureTraversalPipeline(boolean smokeShader) {
        if (this.freed) throw new IllegalStateException("traversal executor is freed");
        requireLiveResources();
        if (this.traversalPipeline != null) {
            return;
        }

        String shaderResource = getTraversalShaderResource(smokeShader);
        String shaderName = smokeShader
                ? "vulkanberyl/hierarchical/traversal_smoke"
                : TRAVERSAL_SHADER_NAME;

        URL configUrl = VulkanBerylTraversalExecutor.class.getResource(TRAVERSAL_SHADER_CONFIG);
        if (configUrl == null) throw new IllegalStateException("Missing traversal compute shader config: " + TRAVERSAL_SHADER_CONFIG);

        JsonObject config;
        try (InputStreamReader reader = new InputStreamReader(configUrl.openStream(), StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load traversal compute shader config: " + TRAVERSAL_SHADER_CONFIG, e);
        }

        validateTraversalBindings(config);

        ComputePipeline.Builder builder = new ComputePipeline.Builder(shaderResource);

        this.descriptorCreationMode = "failed-before-descriptor-create";
        int computeStage;
        try {
            computeStage = ComputePipeline.Builder.getStageFromString("compute");
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to resolve Beryl compute stage for manual traversal descriptors", e);
        }
        List<UBO> manualDescriptors = createTraversalManualDescriptors(computeStage, smokeShader);
        String manualDescriptorDiagnostics = describeDescriptorBindingLayout(manualDescriptors);
        VulkanBerylDebugLog.verboseOnce("traversal-manual-descriptor-layout", "Traversal manual descriptor layout: " + manualDescriptorDiagnostics);
        try {
            builder.setUniforms(manualDescriptors, List.of());
            this.descriptorCreationMode = "beryl-manual-descriptors";
        } catch (RuntimeException e) {
            this.lastDescriptorFailure = "binding=<pipeline-create>, method=ComputePipeline.Builder.setUniforms(manual ManualUBO list), reason=" + e.getMessage();
            throw new IllegalStateException("Failed to create manual traversal descriptor layout (traversal config is validation-only and is not fed to Beryl parseBindings): "
                    + describeTraversalBindings(config), e);
        }
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(shaderResource);
            if (!Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed traversal shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            if (TRAVERSAL_SHADER_RESOURCE.equals(shaderResource)) {
                applyTraversalStaticImportBisection(preprocessedShader.shaderPath());
            }
            if (FORCE_REAL_MAIN_RETURN && TRAVERSAL_SHADER_RESOURCE.equals(shaderResource)) {
                forceRealTraversalMainReturn(preprocessedShader.shaderPath());
            }
            VulkanBerylDebugLog.verboseOnce("traversal-compile-input-verified", "compileShader input verified: shader=" + preprocessedShader.shaderName()
                    + ", tempShaderRelativePath=" + preprocessedShader.tempShaderRelativePath()
                    + ", file=" + preprocessedShader.shaderPath()
                    + ", bytes=" + preprocessedShader.outputBytes());
            logTraversalPreprocessedShaderDiagnostics(shaderResource, shaderName, preprocessedShader, config);
            builder.compileShader(preprocessedShader.rootUrl(), shaderName);
        } catch (RuntimeException e) {
            logTraversalShaderCompileFailureDiagnostics(shaderResource, e);
            throw new IllegalStateException("Failed to compile traversal compute shader: " + shaderName + " from " + shaderResource, e);
        }

        ComputePipeline pipeline;
        try {
            pipeline = builder.createPipeline();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to create traversal compute pipeline for shader " + shaderName
                    + "; descriptorLayout={" + manualDescriptorDiagnostics + "}", e);
        }
        if (pipeline == null || pipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create traversal compute pipeline");
        }
        this.traversalPipeline = pipeline;
        this.descriptorsBound = false;
    }

    public void ensureTraversalDescriptorsBound() {
        if (this.freed) throw new IllegalStateException("traversal executor is freed");
        requireLiveResources();
        if (this.traversalPipeline == null) throw new IllegalStateException("traversal pipeline must be created before binding descriptors");

        bindUniformBinding(HIZ_BINDING, this.traversalResources.getUniformBuffer(), "traversalResources.uniformBuffer(unused Hi-Z padding / Beryl dense-layout compatibility)");
        bindUniformBinding(SCENE_UNIFORM_BINDING, this.traversalResources.getUniformBuffer(), "traversalResources.uniformBuffer");
        bindStorageBinding(REQUEST_QUEUE_BINDING, this.traversalResources.getRequestBuffer(), "traversalResources.requestBuffer");
        bindStorageBinding(RENDER_QUEUE_BINDING, this.renderList.getBuffer(), "renderList.buffer");
        bindStorageBinding(NODE_DATA_BINDING, this.nodeMetadataStore.getNodeBuffer(), "nodeMetadataStore.nodeBuffer");
        bindStorageBinding(NODE_QUEUE_INDEX_BINDING, this.traversalResources.getQueueIndexBuffer(), "traversalResources.queueIndexBuffer");
        bindStorageBinding(NODE_QUEUE_META_BINDING, this.traversalResources.getQueueMetaBuffer(), "traversalResources.queueMetaBuffer");
        bindStorageBinding(NODE_QUEUE_SOURCE_BINDING, this.traversalResources.getScratchQueueA(), "traversalResources.scratchQueueA");
        bindStorageBinding(NODE_QUEUE_SINK_BINDING, this.traversalResources.getScratchQueueB(), "traversalResources.scratchQueueB");
        bindStorageBinding(RENDER_TRACKER_BINDING, this.traversalResources.getRenderTrackerBuffer(), "traversalResources.renderTrackerBuffer");
        logTraversalBinding1DescriptorCheck();
        this.descriptorsBound = true;
        this.lastDescriptorFailure = "none";
    }


    public void dispatchFirstTraversalIteration(Renderer renderer) {
        if (this.freed) throw new IllegalStateException("traversal executor is freed");
        requireLiveResources();
        if (renderer == null) throw new IllegalArgumentException("renderer must not be null");
        if (this.traversalPipeline == null) throw new IllegalStateException("traversal pipeline is not initialized");
        if (!this.descriptorsBound) throw new IllegalStateException("traversal descriptors must be bound before dispatch");

        int topNodeCount = this.topLevelNodeStore.getTopNodeCount();
        if (topNodeCount < 0) throw new IllegalStateException("topNodeCount must be non-negative");
        if (topNodeCount == 0) {
            this.dispatchIterationZeroSkipped = true;
            this.dispatchIterationZeroRan = false;
            return;
        }

        int groupCountX = (topNodeCount + 31) >>> 5;
        if (groupCountX <= 0) {
            throw new IllegalStateException("Invalid traversal dispatch group count: " + groupCountX);
        }

        VkCommandBuffer commandBuffer = renderer.getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("Renderer returned null Vulkan command buffer");
        }

        VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.traversalPipeline.getId());
        logTraversalDescriptorBindingsBeforeDispatch();
        try {
            this.traversalPipeline.bindDescriptorSets(commandBuffer, 0);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to bind traversal descriptor sets for initial dispatch", e);
        }
        VK10.vkCmdDispatch(commandBuffer, groupCountX, 1, 1);
        this.dispatchIterationZeroRan = true;
        this.dispatchIterationZeroSkipped = false;
    }

    public void dispatchRemainingTraversalIterations(Renderer renderer, int maxTraversalIterations) {
        if (this.freed) throw new IllegalStateException("traversal executor is freed");
        requireLiveResources();
        if (renderer == null) throw new IllegalArgumentException("renderer must not be null");
        if (this.traversalPipeline == null) throw new IllegalStateException("traversal pipeline is not initialized");
        if (!this.descriptorsBound) throw new IllegalStateException("traversal descriptors must be bound before dispatch");

        Buffer queueMetaBuffer = this.traversalResources.getQueueMetaBuffer();
        requireBuffer("traversalResources.queueMetaBuffer", queueMetaBuffer);
        long requiredMetaSize = (long) this.traversalResources.getMaxIterations() * 16L;
        if (queueMetaBuffer.getBufferSize() < requiredMetaSize) {
            throw new IllegalStateException("queueMetaBuffer too small for dispatch metadata: " + queueMetaBuffer.getBufferSize() + " < " + requiredMetaSize);
        }

        VkCommandBuffer commandBuffer = renderer.getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("Renderer returned null Vulkan command buffer");
        }

        int maxIterations = Math.min(this.traversalResources.getMaxIterations(), Math.max(1, maxTraversalIterations));
        int iterations = 0;
        for (int iter = 1; iter < maxIterations; iter++) {
            this.traversalResources.recordQueueIndexUpdate(commandBuffer, iter);

            Buffer source = (iter & 1) == 0 ? this.traversalResources.getScratchQueueA() : this.traversalResources.getScratchQueueB();
            Buffer sink = (iter & 1) == 0 ? this.traversalResources.getScratchQueueB() : this.traversalResources.getScratchQueueA();
            bindStorageBinding(NODE_QUEUE_SOURCE_BINDING, source, "traversalResources.scratchQueueSource");
            bindStorageBinding(NODE_QUEUE_SINK_BINDING, sink, "traversalResources.scratchQueueSink");

            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.traversalPipeline.getId());
            logTraversalDescriptorBindingsBeforeDispatch();
            try {
                this.traversalPipeline.bindDescriptorSets(commandBuffer, 0);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to bind traversal descriptor sets for iteration " + iter, e);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer memoryBarrier = VkMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                VK10.vkCmdPipelineBarrier(
                        commandBuffer,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                        0,
                        memoryBarrier,
                        null,
                        null
                );
            }

            long indirectOffset = iter * 16L;
            if (indirectOffset + 16L > queueMetaBuffer.getBufferSize()) {
                throw new IllegalStateException("Indirect dispatch offset out of bounds: " + indirectOffset + " for queueMetaBuffer size " + queueMetaBuffer.getBufferSize());
            }
            VK10.vkCmdDispatchIndirect(commandBuffer, queueMetaBuffer.getId(), indirectOffset);
            iterations++;
        }
        this.indirectDispatchIterationCount = iterations;
    }

    public boolean isTraversalPipelineCreated() { return this.traversalPipeline != null; }
    public boolean areDescriptorsBound() { return this.descriptorsBound; }
    public boolean didDispatchIterationZeroRun() { return this.dispatchIterationZeroRan; }
    public boolean wasDispatchIterationZeroSkipped() { return this.dispatchIterationZeroSkipped; }
    public int getIndirectDispatchIterationCount() { return this.indirectDispatchIterationCount; }
    public String getDescriptorCreationMode() { return this.descriptorCreationMode; }
    public String getLastDescriptorFailure() { return this.lastDescriptorFailure; }
    public VulkanBerylViewportRenderList getRenderList() { return this.renderList; }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.traversalPipeline != null) {
            this.traversalPipeline.cleanUp();
            this.traversalPipeline = null;
        }
    }

    public void requireDispatchSupport() {
        List<String> missing = new ArrayList<>();
        requireMethod(Renderer.class, "getCommandBuffer", missing);
        requireMethod(ComputePipeline.class, "bindDescriptorSets", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class);
        requireMethod(VK10.class, "vkCmdBindPipeline", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class, long.class);
        requireMethod(VK10.class, "vkCmdDispatch", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class, int.class, int.class);
        requireMethod(VK10.class, "vkCmdPipelineBarrier", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class, int.class, int.class, org.lwjgl.vulkan.VkMemoryBarrier.Buffer.class, org.lwjgl.vulkan.VkBufferMemoryBarrier.Buffer.class, org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer.class);
        requireMethod(VK10.class, "vkCmdDispatchIndirect", missing, org.lwjgl.vulkan.VkCommandBuffer.class, long.class, long.class);
        requireMethod(VK10.class, "vkCmdUpdateBuffer", missing, org.lwjgl.vulkan.VkCommandBuffer.class, long.class, long.class, java.nio.IntBuffer.class);

        if (!missing.isEmpty()) {
            throw new UnsupportedOperationException("Vulkan/Beryl traversal compute dispatch integration missing required API: " + String.join(", ", missing));
        }
    }




    private void logTraversalDescriptorBindingsBeforeDispatch() {
        if (this.descriptorDispatchDiagnosticsLogged) {
            return;
        }
        this.descriptorDispatchDiagnosticsLogged = true;
        Buffer renderListBuffer = this.renderList.getBuffer();
        if (this.traversalPipeline.getUBO(candidate -> candidate.binding == HIZ_BINDING) != null) {
            logTraversalDescriptorBinding(HIZ_BINDING, TRAVERSAL_UNUSED_HIZ_PADDING_LABEL, this.traversalResources.getUniformBuffer(), renderListBuffer);
        }
        logTraversalDescriptorBinding(SCENE_UNIFORM_BINDING, "traversalResources.uniformBuffer", this.traversalResources.getUniformBuffer(), renderListBuffer);
        logTraversalDescriptorBinding(REQUEST_QUEUE_BINDING, "traversalResources.requestBuffer", this.traversalResources.getRequestBuffer(), renderListBuffer);
        logTraversalDescriptorBinding(RENDER_QUEUE_BINDING, "renderList.buffer", renderListBuffer, renderListBuffer);
        logTraversalDescriptorBinding(NODE_DATA_BINDING, "nodeMetadataStore.nodeBuffer", this.nodeMetadataStore.getNodeBuffer(), renderListBuffer);
        logTraversalDescriptorBinding(NODE_QUEUE_INDEX_BINDING, "traversalResources.queueIndexBuffer", this.traversalResources.getQueueIndexBuffer(), renderListBuffer);
        logTraversalDescriptorBinding(NODE_QUEUE_META_BINDING, "traversalResources.queueMetaBuffer", this.traversalResources.getQueueMetaBuffer(), renderListBuffer);
        logTraversalDescriptorBinding(NODE_QUEUE_SOURCE_BINDING, "traversalResources.scratchQueueSource", this.traversalResources.getScratchQueueA(), renderListBuffer);
        logTraversalDescriptorBinding(NODE_QUEUE_SINK_BINDING, "traversalResources.scratchQueueSink", this.traversalResources.getScratchQueueB(), renderListBuffer);
        logTraversalDescriptorBinding(RENDER_TRACKER_BINDING, "traversalResources.renderTrackerBuffer", this.traversalResources.getRenderTrackerBuffer(), renderListBuffer);
    }

    private static void logTraversalDescriptorBinding(int binding, String label, Buffer buffer, Buffer renderListBuffer) {
        requireBuffer(label, buffer);
        requireBuffer("renderList.buffer", renderListBuffer);
        VulkanBerylDebugLog.once("traversal-descriptor-dispatch-binding:" + binding + ":" + renderListBuffer.getId(),
                "Traversal descriptor before dispatch: traversalDescriptorBinding=" + binding
                        + " descriptorLabel=" + label
                        + " descriptorBufferId=" + buffer.getId()
                        + " descriptorSizeBytes=" + buffer.getBufferSize()
                        + " descriptorOffsetBytes=0"
                        + " renderListBufferId=" + renderListBuffer.getId()
                        + " renderListBufferSizeBytes=" + renderListBuffer.getBufferSize());
    }



    private static int parseTraversalStaticImportLevel() {
        String raw = System.getenv(STATIC_IMPORT_LEVEL_ENV);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String value = raw.trim();
        if ("full".equalsIgnoreCase(value)) {
            return 5;
        }
        try {
            int level = Integer.parseInt(value);
            if (level < 0) return -1;
            return Math.min(level, 5);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(STATIC_IMPORT_LEVEL_ENV + " must be an integer 0..5 or full, but was: " + raw, e);
        }
    }

    private static void applyTraversalStaticImportBisection(Path shaderPath) {
        if (STATIC_IMPORT_LEVEL < 0 || STATIC_IMPORT_LEVEL >= 5) {
            return;
        }
        try {
            String fullSource = Files.readString(shaderPath, StandardCharsets.UTF_8);
            Files.writeString(shaderPath, buildTraversalStaticImportSource(fullSource, STATIC_IMPORT_LEVEL), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply traversal static import bisection level " + STATIC_IMPORT_LEVEL + " to " + shaderPath, e);
        }
    }

    private static String buildTraversalStaticImportSource(String fullSource, int level) {
        String frustumImport = extractPreprocessedImport(fullSource, "voxy:lod/frustum.glsl");
        String queueImport = extractPreprocessedImport(fullSource, "voxy:lod/hierarchical/queue.glsl");
        String nodeImport = extractPreprocessedImport(fullSource, "voxy:lod/hierarchical/node.glsl");
        String screenspaceImport = extractPreprocessedImport(fullSource, "voxy:lod/hierarchical/screenspace.glsl");

        StringBuilder out = new StringBuilder(8192);
        out.append("#version 460\n");
        out.append("// Diagnostic traversal static import bisection source generated from real traversal.comp.\n");
        out.append("#define VOXY_VULKAN_BERYL_TRAVERSAL_STATIC_IMPORT_LEVEL ").append(level).append('\n');
        out.append("#define VOXY_VULKAN_BERYL_DISABLE_HIZ 1\n");
        out.append("#define VOXY_VULKAN_BERYL_QUEUE_INDEX_SSBO 1\n");
        out.append("#define MAX_ITERATIONS 17\n");
        out.append("#define LOCAL_SIZE_BITS 5\n");
        out.append("#define MAX_REQUEST_QUEUE_SIZE 50\n");
        out.append("#define MAX_QUEUE_SIZE 200000u\n");
        out.append("#define HIZ_BINDING 0\n");
        out.append("#define SCENE_UNIFORM_BINDING 1\n");
        out.append("#define REQUEST_QUEUE_BINDING 2\n");
        out.append("#define RENDER_QUEUE_BINDING 3\n");
        out.append("#define NODE_DATA_BINDING 4\n");
        out.append("#define NODE_QUEUE_INDEX_BINDING 5\n");
        out.append("#define NODE_QUEUE_META_BINDING 6\n");
        out.append("#define NODE_QUEUE_SOURCE_BINDING 7\n");
        out.append("#define NODE_QUEUE_SINK_BINDING 8\n");
        out.append("#define RENDER_TRACKER_BINDING 9\n");
        out.append("#define LOCAL_SIZE_MSK ((1u << LOCAL_SIZE_BITS) - 1u)\n");
        out.append("#define LOCAL_SIZE (1u << LOCAL_SIZE_BITS)\n");
        out.append("layout(local_size_x=LOCAL_SIZE) in;\n\n");

        if (level >= 1) out.append(frustumImport).append('\n');
        out.append("layout(binding = SCENE_UNIFORM_BINDING, std140) uniform SceneUniform {\n");
        out.append("    mat4 MVP;\n");
        out.append("    ivec3 camSecPos;\n");
        out.append("    uint packedHizSize;\n");
        out.append("    vec3 camSubSecPos;\n");
        out.append("    float minSSS;\n");
        out.append(level >= 1 ? "    Frustum frustum;\n" : "    vec4 frustumPlanes[6];\n");
        out.append("    uint renderQueueMaxSize;\n");
        out.append("    uint frameId;\n");
        out.append("    uint requestQueueSize;\n");
        out.append("    uint maxNodeCount;\n");
        out.append("    float renderDistance;\n");
        out.append("};\n\n");

        if (level >= 2) out.append(queueImport).append('\n');
        if (level >= 3) out.append(nodeImport).append('\n');
        if (level >= 4) out.append(screenspaceImport).append('\n');

        appendTraversalStaticManualBufferDeclarations(out, level);
        out.append("void main() {\n");
        out.append("    return;\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendTraversalStaticManualBufferDeclarations(StringBuilder out, int level) {
        out.append("layout(binding = REQUEST_QUEUE_BINDING, std430) restrict buffer requestQueueStruct {\n");
        out.append("    uvec2 requestQueueIndex;\n");
        out.append("    uvec2[] requestQueue;\n");
        out.append("};\n\n");
        out.append("layout(binding = RENDER_QUEUE_BINDING, std430) restrict buffer renderQueueStruct {\n");
        out.append("    uint renderQueueIndex;\n");
        out.append("    uint[] renderQueue;\n");
        out.append("};\n\n");
        if (level < 3) {
            out.append("layout(binding = NODE_DATA_BINDING, std430) restrict buffer NodeData {\n");
            out.append("    uvec4[] nodes;\n");
            out.append("};\n\n");
        }
        if (level < 2) {
            out.append("layout(binding = NODE_QUEUE_INDEX_BINDING, std430) restrict readonly buffer NodeQueueIndex {\n");
            out.append("    uint queueIdx;\n");
            out.append("};\n\n");
            out.append("layout(binding = NODE_QUEUE_META_BINDING, std430) restrict buffer NodeQueueMeta {\n");
            out.append("    uvec4 nodeQueueMetadata[MAX_ITERATIONS];\n");
            out.append("};\n\n");
            out.append("layout(binding = NODE_QUEUE_SOURCE_BINDING, std430) restrict readonly buffer NodeQueueSource {\n");
            out.append("    uint[] nodeQueueSource;\n");
            out.append("};\n\n");
            out.append("layout(binding = NODE_QUEUE_SINK_BINDING, std430) restrict writeonly buffer NodeQueueSink {\n");
            out.append("    uint[] nodeQueueSink;\n");
            out.append("};\n\n");
        }
        out.append("layout(binding = RENDER_TRACKER_BINDING, std430) restrict writeonly buffer renderTrackerArray {\n");
        out.append("    uint[] lastRenderFrame;\n");
        out.append("};\n\n");
    }

    private static String extractPreprocessedImport(String source, String importId) {
        String begin = "// begin import " + importId;
        String end = "// end import " + importId;
        int beginIndex = source.indexOf(begin);
        int endIndex = source.indexOf(end);
        if (beginIndex < 0 || endIndex < beginIndex) {
            throw new IllegalStateException("Preprocessed traversal shader is missing import block " + importId);
        }
        int lineEnd = source.indexOf('\n', endIndex);
        return source.substring(beginIndex, lineEnd < 0 ? source.length() : lineEnd + 1);
    }

    private static String traversalStaticImportLevelMeaning() {
        return switch (STATIC_IMPORT_LEVEL) {
            case 0 -> "minimal_layout_no_imports";
            case 1 -> "frustum_import_types";
            case 2 -> "frustum_plus_queue";
            case 3 -> "frustum_plus_queue_plus_node";
            case 4 -> "frustum_plus_queue_plus_node_plus_screenspace_hiz_disabled";
            case 5 -> "full_real_traversal";
            default -> "disabled";
        };
    }

    private static String traversalImportedModulesForLevel() {
        int level = STATIC_IMPORT_LEVEL;
        if (level < 0) {
            return "<real-default>";
        }
        List<String> modules = new ArrayList<>();
        if (level >= 1 || level >= 5) modules.add("frustum.glsl");
        if (level >= 2 || level >= 5) modules.add("queue.glsl");
        if (level >= 3 || level >= 5) modules.add("node.glsl");
        if (level >= 4 || level >= 5) modules.add("screenspace.glsl");
        if (level >= 5) modules.add("full-real-body");
        return modules.toString();
    }

    private static void forceRealTraversalMainReturn(Path shaderPath) {
        try {
            String source = Files.readString(shaderPath, StandardCharsets.UTF_8);
            if (isForceRealMainReturnDefined(source)) {
                return;
            }
            int versionEnd = source.indexOf('\n');
            if (versionEnd < 0 || !source.trim().startsWith("#version")) {
                Files.writeString(shaderPath, "#define " + FORCE_REAL_MAIN_RETURN_DEFINE + " 1\n" + source, StandardCharsets.UTF_8);
                return;
            }
            String forcedSource = source.substring(0, versionEnd + 1)
                    + "#define " + FORCE_REAL_MAIN_RETURN_DEFINE + " 1\n"
                    + source.substring(versionEnd + 1);
            Files.writeString(shaderPath, forcedSource, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to force real traversal main return in preprocessed shader: " + shaderPath, e);
        }
    }

    private static boolean isForceRealMainReturnDefined(String source) {
        return source.contains("#define " + FORCE_REAL_MAIN_RETURN_DEFINE);
    }

    private void logTraversalPreprocessedShaderDiagnostics(String shaderResource, String shaderName, VulkanBerylShaderImportPreprocessor.PreparedShader preprocessedShader, JsonObject config) {
        if (!TRAVERSAL_SHADER_RESOURCE.equals(shaderResource)) {
            logTraversalSmokeBindingDiagnostics(shaderResource, preprocessedShader);
            return;
        }

        try {
            String source = Files.readString(preprocessedShader.shaderPath(), StandardCharsets.UTF_8);
            MainSnippet snippet = extractMainSnippet(source);
            boolean forcedReturn = isForceRealMainReturnDefined(source);
            String mainReturnMode = forcedReturn ? "forced_unconditional_return" : "stage_limit_return";
            boolean verified = verifyTraversalMainEarlyReturn(snippet.body(), forcedReturn);
            Set<Integer> realBindings = declaredShaderBindings(source);
            String smokeBindings = forcedReturn ? "" : describeSmokeBindingsForComparison();
            VulkanBerylDebugLog.once("traversal-preprocessed-main-diagnostics", "Traversal preprocessed shader diagnostics:"
                    + " traversalShaderResource=" + shaderResource
                    + " traversalShaderName=" + shaderName
                    + " traversalPreprocessedPath=" + preprocessedShader.shaderPath()
                    + " traversalForceRealMainReturn=" + forcedReturn
                    + " traversalMainReturnMode=" + mainReturnMode
                    + " traversalMainEarlyReturnVerified=" + verified
                    + " traversalMainSnippetHash=" + sha256Hex(snippet.text())
                    + " traversalMainLineStart=" + snippet.startLine()
                    + " traversalMainSnippet=" + oneLineSnippet(snippet.text())
                    + " traversalStaticImportLevel=" + STATIC_IMPORT_LEVEL
                    + " traversalStaticImportLevelMeaning=" + traversalStaticImportLevelMeaning()
                    + " traversalImportedModules=" + sanitizeDiagnosticValue(traversalImportedModulesForLevel())
                    + traversalDisableHizDiagnostics(source)
                    + " traversalDeclaredBindings=" + realBindings
                    + traversalDescriptorKindDiagnostics(source, config)
                    + traversalBinding0DescriptorDiagnostics(source)
                    + smokeBindings);
        } catch (IOException e) {
            VulkanBerylDebugLog.error("Failed to inspect preprocessed traversal shader at " + preprocessedShader.shaderPath() + ": " + e);
        }
    }

    private void logTraversalSmokeBindingDiagnostics(String shaderResource, VulkanBerylShaderImportPreprocessor.PreparedShader preprocessedShader) {
        try {
            String source = Files.readString(preprocessedShader.shaderPath(), StandardCharsets.UTF_8);
            VulkanBerylDebugLog.once("traversal-smoke-layout-diagnostics", "Traversal smoke shader layout diagnostics:"
                    + " traversalShaderResource=" + shaderResource
                    + " traversalPreprocessedPath=" + preprocessedShader.shaderPath()
                    + " traversalSmokeDeclaredBindings=" + declaredShaderBindings(source)
                    + traversalBinding0DescriptorDiagnostics(source)
                    + " traversalSmokeRealLayoutImmediateReturn=" + verifyTraversalMainEarlyReturn(extractMainSnippet(source).body(), false));
        } catch (IOException e) {
            VulkanBerylDebugLog.error("Failed to inspect preprocessed traversal smoke shader at " + preprocessedShader.shaderPath() + ": " + e);
        }
    }

    private String describeSmokeBindingsForComparison() {
        try {
            VulkanBerylShaderImportPreprocessor.PreparedShader smokeShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(TRAVERSAL_SMOKE_SHADER_RESOURCE);
            String smokeSource = Files.readString(smokeShader.shaderPath(), StandardCharsets.UTF_8);
            return " traversalSmokeShaderResource=" + TRAVERSAL_SMOKE_SHADER_RESOURCE
                    + " traversalSmokePreprocessedPath=" + smokeShader.shaderPath()
                    + " traversalSmokeDeclaredBindings=" + declaredShaderBindings(smokeSource)
                    + traversalBinding0DescriptorDiagnostics(smokeSource)
                    + " traversalSmokeRealLayoutImmediateReturn=" + verifyTraversalMainEarlyReturn(extractMainSnippet(smokeSource).body(), false);
        } catch (RuntimeException | IOException e) {
            return " traversalSmokeCompareError=" + e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage()).replace(' ', '_');
        }
    }



    private String traversalDescriptorKindDiagnostics(String source, JsonObject config) {
        java.util.Map<Integer, String> configKinds = configDescriptorKinds(config);
        StringBuilder details = new StringBuilder();
        int mismatchCount = 0;
        String firstMismatch = "none";
        for (int binding = SCENE_UNIFORM_BINDING; binding <= RENDER_TRACKER_BINDING; binding++) {
            ShaderBindingDeclaration shaderDeclaration = findShaderBindingDeclaration(source, binding);
            String shaderKind = shaderDeclaration == null ? "unknown" : shaderDeclaration.expectedDescriptorType();
            String configKind = configKinds.getOrDefault(binding, "unknown");
            String javaKind = javaDescriptorKind(binding);
            boolean mismatch = !shaderKind.equals(configKind) || !shaderKind.equals(javaKind);
            if (mismatch) {
                mismatchCount++;
                if ("none".equals(firstMismatch)) {
                    firstMismatch = String.valueOf(binding);
                }
            }
            details.append(" traversalBinding=").append(binding)
                    .append(" shaderDescriptorKind=").append(shaderKind)
                    .append(" configDescriptorKind=").append(configKind)
                    .append(" javaDescriptorKind=").append(javaKind)
                    .append(" descriptorKindMismatch=").append(mismatch);
        }
        return " traversalDescriptorKindMismatchCount=" + mismatchCount
                + " traversalFirstDescriptorKindMismatch=" + firstMismatch
                + details;
    }

    private static java.util.Map<Integer, String> configDescriptorKinds(JsonObject config) {
        java.util.Map<Integer, String> kinds = new java.util.HashMap<>();
        if (config == null || !config.has("UBOs") || !config.get("UBOs").isJsonArray()) {
            return kinds;
        }
        config.getAsJsonArray("UBOs").forEach(node -> {
            if (!node.isJsonObject()) return;
            JsonObject binding = node.getAsJsonObject();
            if (!binding.has("binding")) return;
            kinds.put(binding.get("binding").getAsInt(), binding.has("type") ? binding.get("type").getAsString() : "unknown");
        });
        return kinds;
    }

    private String javaDescriptorKind(int binding) {
        UBO descriptor = null;
        if (this.traversalPipeline != null) {
            descriptor = this.traversalPipeline.getUBO(candidate -> candidate.binding == binding);
        }
        if (descriptor == null) {
            int computeStage = ComputePipeline.Builder.getStageFromString("compute");
            Buffer buffer = bufferForTraversalBinding(binding);
            if (buffer != null) {
                descriptor = createManualDescriptor(binding, computeStage, buffer, labelForTraversalBinding(binding), expectedJavaDescriptorKind(binding));
            }
        }
        if (descriptor == null) {
            return "unknown";
        }
        String reflected = reflectDescriptorKind(descriptor);
        if (!"unknown".equals(reflected)) {
            return reflected;
        }
        if (descriptor instanceof ManualUBO || descriptor.getClass().getSimpleName().contains("UBO")) {
            return "uniformBuffer";
        }
        return "unknown";
    }

    private Buffer bufferForTraversalBinding(int binding) {
        return switch (binding) {
            case HIZ_BINDING, SCENE_UNIFORM_BINDING -> this.traversalResources.getUniformBuffer();
            case REQUEST_QUEUE_BINDING -> this.traversalResources.getRequestBuffer();
            case RENDER_QUEUE_BINDING -> this.renderList.getBuffer();
            case NODE_DATA_BINDING -> this.nodeMetadataStore.getNodeBuffer();
            case NODE_QUEUE_INDEX_BINDING -> this.traversalResources.getQueueIndexBuffer();
            case NODE_QUEUE_META_BINDING -> this.traversalResources.getQueueMetaBuffer();
            case NODE_QUEUE_SOURCE_BINDING -> this.traversalResources.getScratchQueueA();
            case NODE_QUEUE_SINK_BINDING -> this.traversalResources.getScratchQueueB();
            case RENDER_TRACKER_BINDING -> this.traversalResources.getRenderTrackerBuffer();
            default -> null;
        };
    }

    private static String labelForTraversalBinding(int binding) {
        return switch (binding) {
            case HIZ_BINDING -> TRAVERSAL_UNUSED_HIZ_PADDING_LABEL;
            case SCENE_UNIFORM_BINDING -> "SceneUniform";
            case REQUEST_QUEUE_BINDING -> "RequestQueue";
            case RENDER_QUEUE_BINDING -> "RenderQueue";
            case NODE_DATA_BINDING -> "NodeData";
            case NODE_QUEUE_INDEX_BINDING -> "NodeQueueIndex";
            case NODE_QUEUE_META_BINDING -> "NodeQueueMeta";
            case NODE_QUEUE_SOURCE_BINDING -> "NodeQueueSource";
            case NODE_QUEUE_SINK_BINDING -> "NodeQueueSink";
            case RENDER_TRACKER_BINDING -> "RenderTracker";
            default -> "Unknown";
        };
    }

    private static String reflectDescriptorKind(UBO descriptor) {
        for (String methodName : List.of("getDescriptorType", "descriptorType", "getType", "type")) {
            try {
                Method method = descriptor.getClass().getMethod(methodName);
                Object value = method.invoke(descriptor);
                String kind = normalizeDescriptorKind(String.valueOf(value));
                if (!"unknown".equals(kind)) return kind;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        for (String fieldName : List.of("descriptorType", "type")) {
            try {
                var field = descriptor.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(descriptor);
                String kind = normalizeDescriptorKind(String.valueOf(value));
                if (!"unknown".equals(kind)) return kind;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return "unknown";
    }

    private static String normalizeDescriptorKind(String value) {
        if (value == null) return "unknown";
        String compact = value.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (compact.equals(String.valueOf(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER))
                || compact.equals(String.valueOf(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC))
                || compact.contains("uniformbuffer")
                || compact.equals("ubo")) {
            return "uniformBuffer";
        }
        if (compact.equals(String.valueOf(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER))
                || compact.equals(String.valueOf(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC))
                || compact.contains("storagebuffer")
                || compact.equals("ssbo")) {
            return "storageBuffer";
        }
        if (compact.contains("combinedimagesampler")) return "combinedImageSampler";
        if (compact.contains("storageimage")) return "storageImage";
        return "unknown";
    }

    private static String traversalDisableHizDiagnostics(String source) {
        boolean disableHizMacroPresent = source.contains("#define VOXY_VULKAN_BERYL_DISABLE_HIZ");
        boolean hizSamplerDeclarationPresent = source.contains("layout(binding = HIZ_BINDING) uniform sampler2D hizDepthSampler;")
                || source.contains("layout(binding=HIZ_BINDING) uniform sampler2D hizDepthSampler;");
        boolean hizGuardPresent = source.contains("#ifndef VOXY_VULKAN_BERYL_DISABLE_HIZ");
        boolean binding0DeclaredAfterDisableHiz = disableHizMacroPresent && findShaderBindingDeclaration(source, HIZ_BINDING) != null;
        return " traversalDisableHizMacroPresent=" + disableHizMacroPresent
                + " traversalHizSamplerDeclarationPresent=" + hizSamplerDeclarationPresent
                + " traversalHizGuardPresent=" + hizGuardPresent
                + " traversalBinding0DeclaredAfterDisableHiz=" + binding0DeclaredAfterDisableHiz;
    }


    private String traversalBinding0DescriptorDiagnostics(String source) {
        ShaderBindingDeclaration binding0 = findShaderBindingDeclaration(source, HIZ_BINDING);
        String declaration = binding0 == null ? "<none>" : binding0.declaration();
        String expectedDescriptorType = binding0 == null ? "none" : binding0.expectedDescriptorType();
        UBO javaDescriptor = this.traversalPipeline == null ? null : this.traversalPipeline.getUBO(candidate -> candidate.binding == HIZ_BINDING);
        String javaDescriptorLabel = javaDescriptor == null ? "<none>" : TRAVERSAL_UNUSED_HIZ_PADDING_LABEL;
        String javaDescriptorKind = javaDescriptor == null ? "none" : reflectDescriptorKind(javaDescriptor);
        Buffer buffer = javaDescriptor == null ? null : this.traversalResources.getUniformBuffer();
        long bufferId = buffer == null ? 0L : buffer.getId();
        boolean mismatch = binding0 != null && !javaDescriptorKind.equals(expectedDescriptorType);
        return " traversalBinding0Declared=" + (binding0 != null)
                + " traversalBinding0Declaration=" + sanitizeDiagnosticValue(declaration)
                + " traversalBinding0ExpectedDescriptorType=" + expectedDescriptorType
                + " traversalBinding0JavaDescriptorLabel=" + javaDescriptorLabel
                + " traversalBinding0JavaDescriptorKind=" + javaDescriptorKind
                + " traversalBinding0JavaBufferId=" + bufferId
                + " traversalBinding0Mismatch=" + mismatch;
    }

    private static ShaderBindingDeclaration findShaderBindingDeclaration(String source, int targetBinding) {
        Pattern pattern = Pattern.compile("layout\\s*\\(([^)]*binding\\s*=\\s*([A-Za-z0-9_]+)[^)]*)\\)\\s*([^;]+;)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            Integer binding = resolveBindingToken(matcher.group(2));
            if (binding != null && binding == targetBinding) {
                String declaration = ("layout(" + matcher.group(1).trim() + ") " + matcher.group(3).trim()).replaceAll("\\s+", " ");
                return new ShaderBindingDeclaration(declaration, expectedDescriptorTypeForDeclaration(declaration));
            }
        }
        return null;
    }

    private static String expectedDescriptorTypeForDeclaration(String declaration) {
        if (declaration.contains("sampler") || declaration.contains("texture")) return "combinedImageSampler";
        if (declaration.contains("image")) return "storageImage";
        if (declaration.contains(" buffer ")) return "storageBuffer";
        if (declaration.contains(" uniform ")) return "uniformBuffer";
        return "unknown";
    }

    private static String sanitizeDiagnosticValue(String value) {
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private record ShaderBindingDeclaration(String declaration, String expectedDescriptorType) {}

    private static MainSnippet extractMainSnippet(String source) {
        int mainIndex = source.indexOf("void main()");
        if (mainIndex < 0) {
            return new MainSnippet("<missing void main()>", "", -1);
        }
        int openBrace = source.indexOf('{', mainIndex);
        if (openBrace < 0) {
            String text = source.substring(mainIndex, Math.min(source.length(), mainIndex + 240));
            return new MainSnippet(text, text, lineNumber(source, mainIndex));
        }
        int depth = 0;
        int end = source.length();
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        String body = source.substring(openBrace + 1, Math.min(end, source.length()));
        int snippetEnd = Math.min(source.length(), mainIndex + 520);
        return new MainSnippet(source.substring(mainIndex, snippetEnd), body, lineNumber(source, mainIndex));
    }

    private static boolean verifyTraversalMainEarlyReturn(String mainBody, boolean forcedReturn) {
        int stageLimit = mainBody.indexOf("uint stageLimit = frameId;");
        int firstBlockedCall = firstNonNegative(
                mainBody.indexOf("getCurrentNode("),
                mainBody.indexOf("unpackNode("),
                mainBody.indexOf("setupScreenspace("),
                mainBody.indexOf("requestQueue["),
                mainBody.indexOf("renderQueue["),
                mainBody.indexOf("nodeQueueSink[")
        );
        if (forcedReturn) {
            int forcedReturnStatement = mainBody.indexOf("return;");
            return forcedReturnStatement >= 0
                    && (stageLimit < 0 || forcedReturnStatement < stageLimit)
                    && (firstBlockedCall < 0 || forcedReturnStatement < firstBlockedCall);
        }
        int earlyReturn = mainBody.indexOf("if (stageLimit <= 1u)");
        return stageLimit >= 0 && earlyReturn > stageLimit && (firstBlockedCall < 0 || earlyReturn < firstBlockedCall);
    }

    private static int firstNonNegative(int... values) {
        int best = -1;
        for (int value : values) {
            if (value >= 0 && (best < 0 || value < best)) {
                best = value;
            }
        }
        return best;
    }

    private static Set<Integer> declaredShaderBindings(String source) {
        Pattern pattern = Pattern.compile("layout\\s*\\([^)]*binding\\s*=\\s*([A-Za-z0-9_]+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(source);
        Set<Integer> bindings = new TreeSet<>();
        while (matcher.find()) {
            Integer binding = resolveBindingToken(matcher.group(1));
            if (binding != null) {
                bindings.add(binding);
            }
        }
        return bindings;
    }

    private static Integer resolveBindingToken(String token) {
        return switch (token) {
            case "HIZ_BINDING" -> HIZ_BINDING;
            case "SCENE_UNIFORM_BINDING" -> SCENE_UNIFORM_BINDING;
            case "REQUEST_QUEUE_BINDING" -> REQUEST_QUEUE_BINDING;
            case "RENDER_QUEUE_BINDING" -> RENDER_QUEUE_BINDING;
            case "NODE_DATA_BINDING" -> NODE_DATA_BINDING;
            case "NODE_QUEUE_INDEX_BINDING" -> NODE_QUEUE_INDEX_BINDING;
            case "NODE_QUEUE_META_BINDING" -> NODE_QUEUE_META_BINDING;
            case "NODE_QUEUE_SOURCE_BINDING" -> NODE_QUEUE_SOURCE_BINDING;
            case "NODE_QUEUE_SINK_BINDING" -> NODE_QUEUE_SINK_BINDING;
            case "RENDER_TRACKER_BINDING" -> RENDER_TRACKER_BINDING;
            default -> {
                try {
                    yield Integer.parseInt(token);
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
        };
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static String oneLineSnippet(String text) {
        String compact = text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() <= 360 ? compact : compact.substring(0, 360) + "...";
    }

    private static int lineNumber(String source, int index) {
        int line = 1;
        for (int i = 0; i < index && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private record MainSnippet(String text, String body, int startLine) {}

    private static void validateTraversalBindings(JsonObject config) {
        java.util.Map<Integer, String> expectedTypesByBinding = java.util.Map.of(
                SCENE_UNIFORM_BINDING, "uniformBuffer",
                REQUEST_QUEUE_BINDING, "storageBuffer",
                RENDER_QUEUE_BINDING, "storageBuffer",
                NODE_DATA_BINDING, "storageBuffer",
                NODE_QUEUE_INDEX_BINDING, "storageBuffer",
                NODE_QUEUE_META_BINDING, "storageBuffer",
                NODE_QUEUE_SOURCE_BINDING, "storageBuffer",
                NODE_QUEUE_SINK_BINDING, "storageBuffer",
                RENDER_TRACKER_BINDING, "storageBuffer"
        );

        if (config == null || !config.has("UBOs") || !config.get("UBOs").isJsonArray()) {
            throw new IllegalStateException("Traversal shader config must contain a UBOs array");
        }

        java.util.Map<Integer, String> foundTypesByBinding = new java.util.HashMap<>();
        config.getAsJsonArray("UBOs").forEach(node -> {
            if (!node.isJsonObject()) return;
            JsonObject binding = node.getAsJsonObject();
            if (!binding.has("binding")) return;
            int bindingIndex = binding.get("binding").getAsInt();

            String type = binding.has("type") ? binding.get("type").getAsString() : null;
            if (type == null) {
                throw new IllegalStateException("Traversal descriptor binding " + bindingIndex + " is missing descriptor type");
            }
            foundTypesByBinding.put(bindingIndex, type);

            if (binding.has("stages") && binding.get("stages").isJsonArray()) {
                binding.getAsJsonArray("stages").forEach(stageNode -> {
                    String stage = stageNode.getAsString();
                    if (!isAcceptedBerylStageName(stage)) {
                        throw new IllegalStateException("Traversal descriptor binding " + bindingIndex + " contains unsupported stage name " + stage + "; accepted stages are " + Arrays.toString(getAcceptedBerylStageNames()));
                    }
                });
            }
        });

        for (java.util.Map.Entry<Integer, String> expected : expectedTypesByBinding.entrySet()) {
            int binding = expected.getKey();
            String expectedType = expected.getValue();
            String actualType = foundTypesByBinding.get(binding);
            if (actualType == null) {
                throw new IllegalStateException("Traversal descriptor binding " + binding + " is missing from traversal config");
            }
            if (!expectedType.equals(actualType)) {
                throw new IllegalStateException("Traversal descriptor binding " + binding + " must be type " + expectedType + " but was " + actualType);
            }
        }
    }

    private static boolean isAcceptedBerylStageName(String stage) {
        if (stage == null) return false;
        try {
            ComputePipeline.Builder.getStageFromString(stage);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String[] getAcceptedBerylStageNames() {
        return new String[]{"vertex", "fragment", "all", "compute"};
    }

    private static String describeTraversalBindings(JsonObject config) {
        if (config == null || !config.has("UBOs") || !config.get("UBOs").isJsonArray()) {
            return "missing or invalid UBOs array";
        }

        StringBuilder builder = new StringBuilder();
        config.getAsJsonArray("UBOs").forEach(node -> {
            if (!node.isJsonObject()) {
                if (builder.length() != 0) builder.append("; ");
                builder.append("<non-object binding node>");
                return;
            }

            JsonObject binding = node.getAsJsonObject();
            if (builder.length() != 0) builder.append("; ");
            builder
                    .append("binding=")
                    .append(binding.has("binding") ? binding.get("binding").getAsString() : "<missing>")
                    .append(", name=")
                    .append(binding.has("name") ? binding.get("name").getAsString() : "<missing>")
                    .append(", type=")
                    .append(binding.has("type") ? binding.get("type").getAsString() : "<missing>")
                    .append(", stages=")
                    .append(binding.has("stages") ? binding.get("stages").toString() : "<missing>")
                    .append(", fields=")
                    .append(binding.has("fields") ? binding.get("fields").toString() : "<missing>");
        });

        return builder.toString();
    }

    private List<UBO> createTraversalManualDescriptors(int computeStage, boolean smokeShader) {
        List<UBO> descriptors = new ArrayList<>(10);
        // Binding 0 is not used by real traversal while Vulkan/Beryl Hi-Z is disabled,
        // but Beryl manual descriptor lists must be dense from binding 0. Keep this
        // harmless uniform-buffer padding descriptor bound to the existing scene UBO.
        descriptors.add(createManualUniformDescriptor(HIZ_BINDING, computeStage, this.traversalResources.getUniformBuffer(), smokeShader ? "SmokeHizDummy" : TRAVERSAL_UNUSED_HIZ_PADDING_LABEL));
        descriptors.add(createManualUniformDescriptor(SCENE_UNIFORM_BINDING, computeStage, this.traversalResources.getUniformBuffer(), "SceneUniform"));
        descriptors.add(createManualStorageDescriptor(REQUEST_QUEUE_BINDING, computeStage, this.traversalResources.getRequestBuffer(), "RequestQueue"));
        descriptors.add(createManualStorageDescriptor(RENDER_QUEUE_BINDING, computeStage, this.renderList.getBuffer(), "RenderQueue"));
        descriptors.add(createManualStorageDescriptor(NODE_DATA_BINDING, computeStage, this.nodeMetadataStore.getNodeBuffer(), "NodeData"));
        descriptors.add(createManualStorageDescriptor(NODE_QUEUE_INDEX_BINDING, computeStage, this.traversalResources.getQueueIndexBuffer(), "NodeQueueIndex"));
        descriptors.add(createManualStorageDescriptor(NODE_QUEUE_META_BINDING, computeStage, this.traversalResources.getQueueMetaBuffer(), "NodeQueueMeta"));
        descriptors.add(createManualStorageDescriptor(NODE_QUEUE_SOURCE_BINDING, computeStage, this.traversalResources.getScratchQueueA(), "NodeQueueSource"));
        descriptors.add(createManualStorageDescriptor(NODE_QUEUE_SINK_BINDING, computeStage, this.traversalResources.getScratchQueueB(), "NodeQueueSink"));
        descriptors.add(createManualStorageDescriptor(RENDER_TRACKER_BINDING, computeStage, this.traversalResources.getRenderTrackerBuffer(), "RenderTracker"));
        return descriptors;
    }


    private static String describeDescriptorBindingLayout(List<UBO> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return "count=0, minBinding=<none>, maxBinding=<none>, bindings=[], denseFromZero=false";
        }

        List<Integer> bindings = new ArrayList<>(descriptors.size());
        for (UBO descriptor : descriptors) {
            bindings.add(descriptor.binding);
        }
        bindings.sort(Integer::compareTo);

        int minBinding = bindings.get(0);
        int maxBinding = bindings.get(bindings.size() - 1);
        boolean denseFromZero = minBinding == 0;
        if (denseFromZero) {
            int expected = 0;
            for (int binding : bindings) {
                if (binding != expected) {
                    denseFromZero = false;
                    break;
                }
                expected++;
            }
        }

        return "count=" + bindings.size()
                + ", minBinding=" + minBinding
                + ", maxBinding=" + maxBinding
                + ", bindings=" + bindings
                + ", denseFromZero=" + denseFromZero
                + ", binding0=" + (bindings.contains(HIZ_BINDING) ? "unusedHiZPadding/BerylDenseLayoutDummy" : "absent");
    }

    private static UBO createManualDescriptor(int binding, int computeStage, Buffer buffer, String label, String descriptorKind) {
        return "storageBuffer".equals(descriptorKind)
                ? createManualStorageDescriptor(binding, computeStage, buffer, label)
                : createManualUniformDescriptor(binding, computeStage, buffer, label);
    }

    private static ManualUBO createManualUniformDescriptor(int binding, int computeStage, Buffer buffer, String label) {
        int requestedSize = descriptorSizeBytes(binding, label, buffer);
        int structSizeInts = Math.max(1, (requestedSize + Integer.BYTES - 1) / Integer.BYTES);
        VulkanBerylDebugLog.verboseOnce("traversal-manual-descriptor:" + label + ":" + binding, "Creating manual descriptor binding=" + binding + ", label=" + label + ", requestedBytes=" + requestedSize + ", descriptorKind=uniformBuffer, descriptorType=" + VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC + ", descriptorClass=ManualUBO, manualStructInts=" + structSizeInts);
        return new ManualUBO(binding, computeStage, structSizeInts);
    }

    private static ManualUBO createManualStorageDescriptor(int binding, int computeStage, Buffer buffer, String label) {
        int requestedSize = descriptorSizeBytes(binding, label, buffer);
        int structSizeInts = Math.max(1, (requestedSize + Integer.BYTES - 1) / Integer.BYTES);
        VulkanBerylDebugLog.verboseOnce("traversal-manual-descriptor:" + label + ":" + binding, "Creating manual descriptor binding=" + binding + ", label=" + label + ", requestedBytes=" + requestedSize + ", descriptorKind=storageBuffer, descriptorType=" + VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC + ", descriptorClass=ManualStorageBuffer, manualStructInts=" + structSizeInts);
        return new ManualStorageBuffer(binding, computeStage, structSizeInts);
    }

    private static String expectedJavaDescriptorKind(int binding) {
        return binding == HIZ_BINDING || binding == SCENE_UNIFORM_BINDING ? "uniformBuffer" : "storageBuffer";
    }

    private static int descriptorSizeBytes(int binding, String label, Buffer buffer) {
        if (buffer == null) throw new IllegalStateException("Descriptor buffer is null for binding " + binding + " (" + label + ")");
        long size = buffer.getBufferSize();
        if (size <= 0L || size > Integer.MAX_VALUE) {
            throw new IllegalStateException("Invalid descriptor size for binding " + binding + " (" + label + "): " + size + " bytes");
        }
        return (int) size;
    }
    private void requireLiveResources() {
        if (this.traversalResources.isFreed()) throw new IllegalStateException("traversalResources is freed");
        if (this.nodeMetadataStore.isFreed()) throw new IllegalStateException("nodeMetadataStore is freed");
        if (this.topLevelNodeStore.isFreed()) throw new IllegalStateException("topLevelNodeStore is freed");
        if (this.renderList.isFreed()) throw new IllegalStateException("renderList is freed");
    }

    private static void requireBuffer(String name, Buffer buffer) {
        if (buffer == null) throw new IllegalStateException(name + " must not be null");
        if (buffer.getBufferSize() <= 0L) throw new IllegalStateException(name + " must have positive size");
        if (buffer.getId() == 0L) throw new IllegalStateException(name + " must have a valid Vulkan buffer id");
    }

    private static final class ManualStorageBuffer extends ManualUBO {
        private ManualStorageBuffer(int binding, int stages, int size) {
            super(binding, stages, size);
        }

        @Override
        public int getType() {
            return VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
        }
    }

    private static void requireMethod(Class<?> owner, String methodName, List<String> missing, Class<?>... args) {
        try {
            Method method = owner.getMethod(methodName, args);
            if (method == null) {
                missing.add(owner.getName() + "#" + methodName);
            }
        } catch (NoSuchMethodException e) {
            missing.add(owner.getName() + "#" + methodName);
        }
    }

    private static void logTraversalShaderCompileFailureDiagnostics(String shaderResource, RuntimeException compileFailure) {
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(shaderResource);
            Path shaderPath = preprocessedShader.shaderPath();
            VulkanBerylDebugLog.error("Traversal shader compile failure diagnostics:");
            VulkanBerylDebugLog.alwaysRaw("  shaderResourceId=" + shaderResource);
            VulkanBerylDebugLog.alwaysRaw("  tempShaderRelativePath=" + preprocessedShader.tempShaderRelativePath());
            VulkanBerylDebugLog.alwaysRaw("  tempShaderPath=" + shaderPath);
            if (!Files.isRegularFile(shaderPath)) {
                VulkanBerylDebugLog.alwaysRaw("  temp shader file is missing; cannot print source context");
                return;
            }
            String message = compileFailure.getMessage();
            if (message == null || message.isBlank()) {
                VulkanBerylDebugLog.alwaysRaw("  compile error message was empty; cannot infer source line context");
                return;
            }
            List<String> sourceLines = Files.readAllLines(shaderPath, StandardCharsets.UTF_8);
            Matcher matcher = SHADER_LINE_PATTERN.matcher(message);
            boolean foundLine = false;
            while (matcher.find()) {
                foundLine = true;
                int lineNumber = Integer.parseInt(matcher.group(1));
                int start = Math.max(1, lineNumber - 2);
                int end = Math.min(sourceLines.size(), lineNumber + 2);
                VulkanBerylDebugLog.alwaysRaw("  source context around line " + lineNumber + ":");
                for (int i = start; i <= end; i++) {
                    String marker = i == lineNumber ? ">" : " ";
                    VulkanBerylDebugLog.alwaysRaw("    " + marker + String.format("%4d", i) + " | " + sourceLines.get(i - 1));
                }
            }
            if (!foundLine) {
                VulkanBerylDebugLog.alwaysRaw("  no shader line numbers were parsed from compile exception message");
            }
        } catch (RuntimeException | IOException diagnosticsFailure) {
            VulkanBerylDebugLog.error("Failed to capture traversal shader compile diagnostics: " + diagnosticsFailure);
        }
    }

    private void bindUniformBinding(int binding, Buffer buffer, String label) {
        bindDescriptorBinding(binding, buffer, label, "uniformBuffer");
    }

    private void bindStorageBinding(int binding, Buffer buffer, String label) {
        bindDescriptorBinding(binding, buffer, label, "storageBuffer");
    }

    private void bindDescriptorBinding(int binding, Buffer buffer, String label, String expectedDescriptorKind) {
        requireBuffer(label, buffer);
        UBO ubo = this.traversalPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) {
            throw new IllegalStateException("Traversal descriptor binding " + binding + " is missing from traversal.json");
        }
        String actualDescriptorKind = normalizeDescriptorKind(String.valueOf(ubo.getType()));
        if (!expectedDescriptorKind.equals(actualDescriptorKind)) {
            throw new IllegalStateException("Traversal descriptor binding " + binding + " (" + label + ") expected " + expectedDescriptorKind + " but was " + actualDescriptorKind);
        }
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > Integer.MAX_VALUE) {
            throw new IllegalStateException(label + " has invalid descriptor size: " + bufferSize);
        }
        try {
            ubo.getBufferSlice().set(buffer, 0L, (int) bufferSize);
        } catch (RuntimeException e) {
            this.lastDescriptorFailure = "binding=" + binding + ", method=UBO.getBufferSlice().set(Buffer,offset,size), label=" + label + ", reason=" + e.getMessage();
            throw new IllegalStateException("Failed to bind traversal descriptor binding " + binding + " (" + label + ")", e);
        }
    }

    private void logTraversalBinding1DescriptorCheck() {
        UBO sceneUniform = this.traversalPipeline.getUBO(candidate -> candidate.binding == SCENE_UNIFORM_BINDING);
        String actual = sceneUniform == null ? "missing" : normalizeDescriptorKind(String.valueOf(sceneUniform.getType()));
        VulkanBerylDebugLog.once("traversal-binding1-descriptor-check", "Traversal binding1 descriptor check: binding1 expected=uniformBuffer actual=" + actual);
    }
}
