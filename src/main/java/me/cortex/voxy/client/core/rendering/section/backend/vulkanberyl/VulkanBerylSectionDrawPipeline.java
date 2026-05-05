package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.beryl.render.ComputePipeline;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import net.vulkanmod.vulkan.memory.MemoryTypes;

public final class VulkanBerylSectionDrawPipeline {
    public static final String DRAW_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw.vsh";
    private static final String DRAW_SHADER_NAME = "vulkanberyl/section/draw";
    private static final String DRAW_DEBUG_FRAGMENT_SHADER_NAME = "vulkanberyl/section/draw_debug";
    private static final String DRAW_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/section/draw.json";
    private static final String CMDGEN_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen.comp";
    private static final String CMDGEN_SHADER_NAME = "vulkanberyl/section/cmdgen";
    private static final String CMDGEN_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/section/cmdgen.json";

    private static final int GEOMETRY_BINDING = 4;
    private static final int METADATA_BINDING = 5;
    private static final int RENDER_LIST_BINDING = 6;
    private static final int SCENE_UNIFORM_BINDING = 0;
    private static final int CMDGEN_METADATA_BINDING = 1;
    private static final int CMDGEN_RENDER_LIST_BINDING = 2;
    private static final int CMDGEN_DRAW_COMMAND_BINDING = 3;
    private static final int CMDGEN_DRAW_COUNT_BINDING = 4;
    private static final int DRAW_COMMAND_STRIDE_BYTES = 16;
    private static final int DRAW_COMMAND_DEBUG_SAMPLE_LIMIT = 16;
    private static final boolean DEBUG_COLOUR_MODE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_DEBUG_COLOUR", "false"));

    private GraphicsPipeline graphicsPipeline;
    private ComputePipeline commandGenPipeline;
    private Buffer drawCommandBuffer;
    private Buffer drawCountBuffer;
    private Buffer drawCommandDebugReadbackBuffer;
    private int drawCommandBufferUsageFlags;
    private int drawCommandCapacity;
    private int pendingDebugSampleCommandCount;
    private int pendingDebugSampleVisibleCount;
    private long pendingDebugSampleGeometryBufferBytes;
    private boolean debugSamplePending;
    private DrawCommandDebugSample lastCompletedDebugSample = new DrawCommandDebugSample(0, 0, -1L);
    private boolean resourcesBound;
    private boolean sceneUniformBound;
    private boolean graphicsPipelineCreated;
    private boolean commandGenPipelineCreated;
    private boolean freed;

    public void ensureDrawPipeline() {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (this.graphicsPipeline != null) return;

        URL shaderRootUrl = VulkanBerylSectionDrawPipeline.class.getResource("/assets/voxy/shaders");
        if (shaderRootUrl == null) throw new IllegalStateException("Unable to locate /assets/voxy/shaders for section draw pipeline");
        URL configUrl = VulkanBerylSectionDrawPipeline.class.getResource(DRAW_SHADER_CONFIG);
        if (configUrl == null) throw new IllegalStateException("Missing section draw shader config: " + DRAW_SHADER_CONFIG);

        JsonObject config;
        try (InputStreamReader reader = new InputStreamReader(configUrl.openStream(), StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load section draw shader config: " + DRAW_SHADER_CONFIG, e);
        }

        Pipeline.Builder builder = new Pipeline.Builder();
        List<UBO> drawDescriptors = createManualDrawDescriptors();
        System.out.println("[Voxy][VulkanBeryl] Section draw descriptor mode=manual_dense, bindings=[0,1,2,3,4,5,6], denseFromZero=true, vertexShader=" + DRAW_SHADER_NAME + ", fragmentShader=" + (DEBUG_COLOUR_MODE ? DRAW_DEBUG_FRAGMENT_SHADER_NAME : DRAW_SHADER_NAME) + ", debugColourMode=" + DEBUG_COLOUR_MODE);
        try {
            builder.setUniforms(drawDescriptors, List.of());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create manual section draw descriptor layout (config is validation-only and is not fed to Beryl parseBindings): " + DRAW_SHADER_CONFIG, e);
        }
        String fragmentShaderName = DEBUG_COLOUR_MODE ? DRAW_DEBUG_FRAGMENT_SHADER_NAME : DRAW_SHADER_NAME;
        try {
            builder.compileShaders(shaderRootUrl.toExternalForm(), DRAW_SHADER_NAME, fragmentShaderName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section draw shaders (vertex=" + DRAW_SHADER_NAME + ", fragment=" + fragmentShaderName + ", debugMode=" + DEBUG_COLOUR_MODE + ")", e);
        }
        GraphicsPipeline pipeline;
        try {
            pipeline = builder.createGraphicsPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section draw graphics pipeline (config=" + DRAW_SHADER_CONFIG + ", debugMode=" + DEBUG_COLOUR_MODE + ")", e);
        }
        if (pipeline == null) throw new IllegalStateException("Failed to create section draw graphics pipeline");
        this.graphicsPipeline = pipeline;
        this.graphicsPipelineCreated = true;
    }

    public void ensureDrawResourcesBound(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (geometryData == null) throw new IllegalArgumentException("geometryData must not be null");
        if (renderList == null) throw new IllegalArgumentException("renderList must not be null");
        if (this.graphicsPipeline == null) throw new IllegalStateException("graphics pipeline must be created before resources are bound");

        bindStorageBinding(GEOMETRY_BINDING, geometryData.getGeometryBuffer(), "geometryData.geometryBuffer");
        bindStorageBinding(METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindStorageBinding(RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        this.ensureCommandBuffers(renderList.getMaxEntryCount());
        this.ensureCommandGenPipeline();
        bindComputeStorageBinding(CMDGEN_METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindComputeStorageBinding(CMDGEN_RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        bindComputeStorageBinding(CMDGEN_DRAW_COMMAND_BINDING, this.drawCommandBuffer, "drawCommandBuffer");
        bindComputeStorageBinding(CMDGEN_DRAW_COUNT_BINDING, this.drawCountBuffer, "drawCountBuffer");
        this.resourcesBound = true;
    }

    public boolean isReady() {
        return this.graphicsPipeline != null && this.resourcesBound && !this.freed;
    }
    public void pollDebugReadback() { this.consumePendingDebugCommandSampleIfReady(); }
    public boolean isSceneUniformBound() { return this.sceneUniformBound; }
    public boolean isGraphicsPipelineCreated() { return this.graphicsPipelineCreated; }
    public boolean isCommandGenPipelineCreated() { return this.commandGenPipelineCreated; }
    public boolean isDebugColourModeEnabled() { return DEBUG_COLOUR_MODE; }
    public boolean isDepthSamplingEnabled() { return false; }
    public boolean isModelLightPathEnabled() { return false; }
    public boolean isDebugSamplePending() { return this.debugSamplePending; }

    public record OpaqueDrawSubmission(int submittedVisibleCount, String drawMode, long submittedQuadCount, int submittedDrawCommandCount, int sampledCommandCount, int invalidSampledCommandCount, long sampledQuadCount, boolean samplePending, String skippedReason) {}

    public OpaqueDrawSubmission renderOpaque(Renderer renderer,
                            VulkanBerylViewport viewport,
                            VulkanBerylSectionGeometryData geometryData,
                            VulkanBerylViewportRenderList renderList) {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (renderer == null) throw new IllegalArgumentException("renderer must not be null");
        if (viewport == null) throw new IllegalArgumentException("viewport must not be null");
        if (geometryData == null) throw new IllegalArgumentException("geometryData must not be null");
        if (renderList == null) throw new IllegalArgumentException("renderList must not be null");
        if (this.graphicsPipeline == null || !this.resourcesBound) {
            throw new IllegalStateException("section draw pipeline/resources are not initialized");
        }

        int maxEntryCount = renderList.getMaxEntryCount();
        int rawVisibleCount = renderList.getLastVisibleCount();
        int visibleCount = Math.max(0, Math.min(rawVisibleCount, maxEntryCount));
        if (visibleCount <= 0) {
            return new OpaqueDrawSubmission(0, "indirect_generated_per_section", 0L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "visible_count_zero_or_negative");
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL command buffer is unavailable");
        }

        validateDrawCommandBuffer(visibleCount);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.commandGenPipeline.getId());
        this.commandGenPipeline.bindDescriptorSets(commandBuffer, 0);
        int groupCountX = (visibleCount + 127) >>> 7;
        VK10.vkCmdDispatch(commandBuffer, groupCountX, 1, 1);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0,
                    barrier,
                    null,
                    null
            );
        }

        this.consumePendingDebugCommandSampleIfReady();
        int sampledCommandCount = Math.min(DRAW_COMMAND_DEBUG_SAMPLE_LIMIT, visibleCount);
        scheduleDebugCommandReadback(commandBuffer, sampledCommandCount, visibleCount, geometryData.getGeometryBuffer().getBufferSize());
        renderer.bindGraphicsPipeline(this.graphicsPipeline);
        this.bindSceneUniform(viewport);
        this.graphicsPipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDrawIndirect(commandBuffer, this.drawCommandBuffer.getId(), 0L, visibleCount, DRAW_COMMAND_STRIDE_BYTES);
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        long submittedQuadCount = sample.sampledQuadCount >= 0L ? sample.sampledQuadCount : -1L;
        return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", submittedQuadCount, visibleCount, sample.sampledCommandCount, sample.invalidSampledCommandCount, sample.sampledQuadCount, this.debugSamplePending, null);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.graphicsPipeline != null) {
            this.graphicsPipeline.cleanUp();
            this.graphicsPipeline = null;
        }
        if (this.commandGenPipeline != null) {
            this.commandGenPipeline.cleanUp();
            this.commandGenPipeline = null;
        }
        if (this.drawCommandBuffer != null) {
            this.drawCommandBuffer.scheduleFree();
            this.drawCommandBuffer = null;
        }
        if (this.drawCountBuffer != null) {
            this.drawCountBuffer.scheduleFree();
            this.drawCountBuffer = null;
        }
        if (this.drawCommandDebugReadbackBuffer != null) {
            this.drawCommandDebugReadbackBuffer.scheduleFree();
            this.drawCommandDebugReadbackBuffer = null;
        }
        this.resourcesBound = false;
    }



    private static List<UBO> createManualDrawDescriptors() {
        List<UBO> descriptors = new java.util.ArrayList<>(7);
        int vertexStage = VK10.VK_SHADER_STAGE_VERTEX_BIT;
        descriptors.add(new ManualUBO(0, vertexStage, 20)); // mat4 + ivec3 + frame + padding + vec3
        descriptors.add(new ManualUBO(1, vertexStage, 1));
        descriptors.add(new ManualUBO(2, vertexStage, 1));
        descriptors.add(new ManualUBO(3, vertexStage, 1));
        descriptors.add(new ManualUBO(4, vertexStage, 1));
        descriptors.add(new ManualUBO(5, vertexStage, 1));
        descriptors.add(new ManualUBO(6, vertexStage, 1));
        return descriptors;
    }

    private void ensureCommandBuffers(int maxEntryCount) {
        if (maxEntryCount <= 0) throw new IllegalArgumentException("maxEntryCount must be positive");
        if (this.drawCommandBuffer != null && this.drawCommandCapacity == maxEntryCount) return;
        if (this.drawCommandBuffer != null) this.drawCommandBuffer.scheduleFree();
        if (this.drawCountBuffer != null) this.drawCountBuffer.scheduleFree();
        long commandBytes = Math.multiplyExact((long) maxEntryCount, DRAW_COMMAND_STRIDE_BYTES);
        this.drawCommandBufferUsageFlags = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        this.drawCommandBuffer = new Buffer("voxy_vulkanberyl_opaque_draw_commands", this.drawCommandBufferUsageFlags, MemoryTypes.GPU_MEM);
        this.drawCommandBuffer.createBuffer(commandBytes);
        this.drawCountBuffer = new Buffer("voxy_vulkanberyl_opaque_draw_count", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.drawCountBuffer.createBuffer(4L);
        if (this.drawCommandDebugReadbackBuffer != null) this.drawCommandDebugReadbackBuffer.scheduleFree();
        this.drawCommandDebugReadbackBuffer = new Buffer("voxy_vulkanberyl_opaque_draw_commands_readback", VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.HOST_MEM);
        this.drawCommandDebugReadbackBuffer.createBuffer((long) DRAW_COMMAND_DEBUG_SAMPLE_LIMIT * DRAW_COMMAND_STRIDE_BYTES);
        this.drawCommandCapacity = maxEntryCount;
    }

    private void validateDrawCommandBuffer(int visibleCount) {
        if (this.drawCommandBuffer == null) throw new IllegalStateException("drawCommandBuffer must not be null");
        if (this.drawCommandBuffer.getId() == 0L) throw new IllegalStateException("drawCommandBuffer has invalid Vulkan buffer id");
        if ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) == 0) throw new IllegalStateException("drawCommandBuffer missing VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT");
        long requiredBytes = Math.multiplyExact((long) visibleCount, DRAW_COMMAND_STRIDE_BYTES);
        if (this.drawCommandBuffer.getBufferSize() < requiredBytes) throw new IllegalStateException("drawCommandBuffer is too small for visible draws");
    }

    private void scheduleDebugCommandReadback(VkCommandBuffer commandBuffer, int sampledCommandCount, int visibleCount, long geometryBufferBytes) {
        if (sampledCommandCount <= 0 || this.drawCommandDebugReadbackBuffer == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0L).dstOffset(0L).size((long) sampledCommandCount * DRAW_COMMAND_STRIDE_BYTES);
            VK10.vkCmdCopyBuffer(commandBuffer, this.drawCommandBuffer.getId(), this.drawCommandDebugReadbackBuffer.getId(), copyRegion);
        }
        this.pendingDebugSampleCommandCount = sampledCommandCount;
        this.pendingDebugSampleVisibleCount = visibleCount;
        this.pendingDebugSampleGeometryBufferBytes = geometryBufferBytes;
        this.debugSamplePending = true;
    }

    private void consumePendingDebugCommandSampleIfReady() {
        if (!this.debugSamplePending) return;
        if (Renderer.getInstance() == null || Renderer.getCommandBuffer() != null) return;
        this.lastCompletedDebugSample = readDebugCommandSample(this.pendingDebugSampleCommandCount, this.pendingDebugSampleVisibleCount, this.pendingDebugSampleGeometryBufferBytes);
        this.debugSamplePending = false;
    }

    private DrawCommandDebugSample readDebugCommandSample(int sampledCommandCount, int visibleCount, long geometryBufferBytes) {
        long readbackPtr = this.drawCommandDebugReadbackBuffer == null ? 0L : this.drawCommandDebugReadbackBuffer.getDataPtr();
        if (sampledCommandCount <= 0 || readbackPtr == 0L) return new DrawCommandDebugSample(0, 0, -1L);
        int invalid = 0;
        long quadCount = 0L;
        for (int i = 0; i < sampledCommandCount; i++) {
            long base = readbackPtr + (long) i * DRAW_COMMAND_STRIDE_BYTES;
            int vertexCount = MemoryUtil.memGetInt(base);
            int instanceCount = MemoryUtil.memGetInt(base + 4L);
            int firstVertex = MemoryUtil.memGetInt(base + 8L);
            int firstInstance = MemoryUtil.memGetInt(base + 12L);
            boolean valid = (vertexCount & 3) == 0 && instanceCount == 1 && (firstVertex & 3) == 0 && firstInstance >= 0 && firstInstance < visibleCount;
            if (valid && firstVertex >= 0 && geometryBufferBytes > 0L) {
                long maxFirstVertex = (geometryBufferBytes >>> 3) * 4L;
                valid = Integer.toUnsignedLong(firstVertex) < maxFirstVertex;
            }
            if (!valid) invalid++;
            if ((vertexCount & 3) == 0 && vertexCount >= 0) quadCount += (vertexCount >>> 2);
        }
        return new DrawCommandDebugSample(sampledCommandCount, invalid, quadCount);
    }

    private record DrawCommandDebugSample(int sampledCommandCount, int invalidSampledCommandCount, long sampledQuadCount) {}

    private void ensureCommandGenPipeline() {
        if (this.commandGenPipeline != null) return;
        URL configUrl = VulkanBerylSectionDrawPipeline.class.getResource(CMDGEN_SHADER_CONFIG);
        Objects.requireNonNull(configUrl, "Missing section cmdgen shader config: " + CMDGEN_SHADER_CONFIG);
        ComputePipeline.Builder builder = new ComputePipeline.Builder(CMDGEN_SHADER_RESOURCE);
        JsonObject config;
        try (InputStreamReader reader = new InputStreamReader(configUrl.openStream(), StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load section cmdgen shader config: " + CMDGEN_SHADER_CONFIG, e);
        }
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        builder.setUniforms(List.of(
                createManualDescriptor(CMDGEN_METADATA_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == METADATA_BINDING).getBufferSlice().getBuffer(), "CmdGenMetadata"),
                createManualDescriptor(CMDGEN_RENDER_LIST_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == RENDER_LIST_BINDING).getBufferSlice().getBuffer(), "CmdGenRenderList"),
                createManualDescriptor(CMDGEN_DRAW_COMMAND_BINDING, computeStage, this.drawCommandBuffer, "CmdGenDrawCommand"),
                createManualDescriptor(CMDGEN_DRAW_COUNT_BINDING, computeStage, this.drawCountBuffer, "CmdGenDrawCount")
        ), List.of());
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(CMDGEN_SHADER_RESOURCE);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            System.out.println("[Voxy][VulkanBeryl] compileShader input verified: shader=" + preprocessedShader.shaderName()
                    + ", tempShaderRelativePath=" + preprocessedShader.tempShaderRelativePath()
                    + ", file=" + preprocessedShader.shaderPath()
                    + ", bytes=" + preprocessedShader.outputBytes());
            builder.compileShader(preprocessedShader.rootUrl(), preprocessedShader.shaderName());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section cmdgen shader (compute=" + CMDGEN_SHADER_NAME + ", config=" + CMDGEN_SHADER_CONFIG + ")", e);
        }
        try {
            this.commandGenPipeline = builder.createPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section cmdgen compute pipeline (config=" + CMDGEN_SHADER_CONFIG + ")", e);
        }
        if (this.commandGenPipeline == null || this.commandGenPipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create section cmdgen compute pipeline");
        }
        this.commandGenPipelineCreated = true;
    }


    private static ManualUBO createManualDescriptor(int binding, int computeStage, Buffer buffer, String label) {
        int requestedSize = descriptorSizeBytes(binding, label, buffer);
        int structSizeInts = Math.max(1, (requestedSize + Integer.BYTES - 1) / Integer.BYTES);
        System.out.println("[Voxy][VulkanBeryl] Creating manual descriptor binding=" + binding + ", label=" + label + ", requestedBytes=" + requestedSize + ", descriptorClass=ManualUBO, manualStructInts=" + structSizeInts);
        return new ManualUBO(binding, computeStage, structSizeInts);
    }

    private static int descriptorSizeBytes(int binding, String label, Buffer buffer) {
        if (buffer == null) throw new IllegalStateException("Descriptor buffer is null for binding " + binding + " (" + label + ")");
        long size = buffer.getBufferSize();
        if (size <= 0L || size > Integer.MAX_VALUE) {
            throw new IllegalStateException("Invalid descriptor size for binding " + binding + " (" + label + "): " + size + " bytes");
        }
        return (int) size;
    }

    private void bindStorageBinding(int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > Integer.MAX_VALUE) {
            throw new IllegalStateException(label + " has invalid descriptor size: " + bufferSize);
        }

        UBO ubo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) {
            throw new IllegalStateException("Section draw descriptor missing: name=" + label + ", binding=" + binding + ", config=" + DRAW_SHADER_CONFIG);
        }
        ubo.getBufferSlice().set(buffer, 0L, (int) bufferSize);
    }

    private void bindComputeStorageBinding(int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > Integer.MAX_VALUE) throw new IllegalStateException(label + " has invalid descriptor size: " + bufferSize);
        UBO ubo = this.commandGenPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) throw new IllegalStateException("Section cmdgen descriptor missing: name=" + label + ", binding=" + binding + ", config=" + CMDGEN_SHADER_CONFIG);
        ubo.getBufferSlice().set(buffer, 0L, (int) bufferSize);
    }

    private void bindSceneUniform(VulkanBerylViewport viewport) {
        UBO ubo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == SCENE_UNIFORM_BINDING);
        if (ubo == null) throw new IllegalStateException("Section draw descriptor missing: name=SceneUniform, binding=0, config=" + DRAW_SHADER_CONFIG);
        Buffer uniformBuffer = ubo.getBufferSlice().getBuffer();
        if (uniformBuffer == null) throw new IllegalStateException("Section draw SceneUniform buffer is not bound");
        long ptr = uniformBuffer.getDataPtr() + ubo.getBufferSlice().getOffset();
        var mat = new org.joml.Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr);
        ptr += 4L * 4L * 4L;
        MemoryUtil.memPutInt(ptr, viewport.section.x);
        MemoryUtil.memPutInt(ptr + 4L, viewport.section.y);
        MemoryUtil.memPutInt(ptr + 8L, viewport.section.z);
        ptr += 16L;
        MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff);
        ptr += 4L;
        MemoryUtil.memPutFloat(ptr, viewport.innerTranslation.x);
        MemoryUtil.memPutFloat(ptr + 4L, viewport.innerTranslation.y);
        MemoryUtil.memPutFloat(ptr + 8L, viewport.innerTranslation.z);
        this.sceneUniformBound = true;
    }
}
