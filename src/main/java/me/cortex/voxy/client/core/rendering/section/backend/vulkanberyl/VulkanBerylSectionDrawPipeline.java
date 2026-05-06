package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import net.vulkanmod.vulkan.memory.MemoryTypes;

public final class VulkanBerylSectionDrawPipeline {
    public static final String DRAW_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw.vsh";
    public static final String DRAW_FRAGMENT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw.fsh";
    public static final String DRAW_DEBUG_FRAGMENT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw_debug.fsh";
    private static final String DRAW_SHADER_NAME = "vulkanberyl/section/draw";
    private static final String DRAW_DEBUG_FRAGMENT_SHADER_NAME = "vulkanberyl/section/draw_debug";
    private static final String DRAW_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/section/draw.json";
    private static final String CMDGEN_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen.comp";
    private static final String CMDGEN_SHADER_NAME = "vulkanberyl/section/cmdgen";
    private static final String CMDGEN_NOOP_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_dispatch_noop.comp";
    private static final String CMDGEN_NOOP_SHADER_NAME = "vulkanberyl/section/cmdgen_dispatch_noop";
    private static final String CMDGEN_MINIMAL_SSBO_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_minimal_ssbo_read.comp";
    private static final String CMDGEN_MINIMAL_SSBO_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_minimal_ssbo_read";
    private static final String CMDGEN_MINIMAL_CONFIG_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_minimal_config_read.comp";
    private static final String CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_minimal_config_read";
    private static final String CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_minimal_config_binding0_read.comp";
    private static final String CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_minimal_config_binding0_read";
    private static final String CMDGEN_HARDCODED_BINDING0_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_hardcoded_binding0_read.comp";
    private static final String CMDGEN_HARDCODED_BINDING0_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_hardcoded_binding0_read";
    private static final String CMDGEN_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/section/cmdgen.json";

    private static final int GEOMETRY_BINDING = 4;
    private static final int METADATA_BINDING = 5;
    private static final int RENDER_LIST_BINDING = 6;
    private static final int SCENE_UNIFORM_BINDING = 0;
    private static final int CMDGEN_RENDER_LIST_BINDING = 0;
    private static final int CMDGEN_METADATA_BINDING = 1;
    private static final int CMDGEN_BINDING2_PROBE_BINDING = 2;
    private static final int CMDGEN_DRAW_COMMAND_BINDING = 3;
    private static final int CMDGEN_DRAW_COUNT_BINDING = 4;
    private static final int CMDGEN_CONFIG_BINDING = 5;
    private static final int DRAW_COMMAND_STRIDE_BYTES = 16;
    private static final int CMDGEN_CONFIG_SIZE_BYTES = 24;
    private static final int CMDGEN_CONFIG_USAGE_FLAGS = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final int CMDGEN_FLAG_NOOP_SMOKE = 1;
    private static final int CMDGEN_FLAG_READ_RENDERLIST_ONLY = 1 << 1;
    private static final int CMDGEN_FLAG_READ_METADATA_ONLY = 1 << 2;
    private static final int CMDGEN_FLAG_WRITE_DRAWS_ONLY = 1 << 3;
    private static final int CMDGEN_FLAG_READ_RENDERLIST_HEADER_ONLY = 1 << 4;
    private static final int CMDGEN_FLAG_READ_RENDERLIST_COUNT_ONLY = 1 << 5;
    private static final int CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY = 1 << 6;
    private static final int CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY = 1 << 7;
    private static final int CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY = 1 << 8;
    private static final int CMDGEN_FLAG_READ_BINDING0_ONLY = 1 << 9;
    private static final int CMDGEN_FLAG_READ_BINDING1_ONLY = 1 << 10;
    private static final int CMDGEN_FLAG_READ_BINDING2_PROBE_ONLY = 1 << 11;
    private static final int DRAW_COMMAND_DEBUG_SAMPLE_LIMIT = 16;
    private static final boolean DEBUG_COLOUR_MODE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_DEBUG_COLOUR", "false"));
    private static final boolean ENABLE_CMDGEN_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_CMDGEN_DISPATCH", "false"));
    private static final boolean CMDGEN_CREATE_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_CREATE_ONLY", "false"));
    private static final boolean CMDGEN_DISPATCH_NOOP = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP", "false"));
    private static final boolean CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE", "false"));
    private static final boolean CMDGEN_UPLOAD_CONFIG_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_UPLOAD_CONFIG_ONLY", "false"));
    private static final boolean CMDGEN_CLEAR_OUTPUTS_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_CLEAR_OUTPUTS_ONLY", "false"));
    private static final boolean CMDGEN_BIND_FULL_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_BIND_FULL_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_RENDERLIST_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_METADATA_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_METADATA_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_WRITE_DRAWS_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_WRITE_DRAWS_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_BINDING0_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_BINDING1_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING1_ONLY", "false"));
    private static final boolean CMDGEN_SHADER_READ_BINDING2_PROBE_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING2_PROBE_ONLY", "false"));
    private static final boolean CMDGEN_RENDERLIST_ALT_BUFFER_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_RENDERLIST_ALT_BUFFER_PROBE", "false"));
    private static final boolean CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE", "false"));
    private static final boolean CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE", "false"));
    private static final boolean CMDGEN_MINIMAL_CONFIG_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_CONFIG_READ_PROBE", "false"));
    private static final boolean CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE", "false"));
    private static final boolean CMDGEN_HARDCODED_READ_BINDING0_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_HARDCODED_READ_BINDING0_ONLY", "false"));
    private static final boolean CMDGEN_DEBUG_READBACK = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK", "false"));
    private static final boolean ENABLE_INDIRECT_DRAW = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_DRAW", "false"));
    private static final boolean RENDERLIST_SMOKE_ONE_ENTRY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_RENDERLIST_SMOKE_ONE_ENTRY", "false"));

    private GraphicsPipeline graphicsPipeline;
    private ComputePipeline commandGenPipeline;
    private ComputePipeline commandGenNoopPipeline;
    private ComputePipeline commandGenMinimalTinySsboReadProbePipeline;
    private ComputePipeline commandGenMinimalRenderListReadProbePipeline;
    private ComputePipeline commandGenMinimalConfigReadProbePipeline;
    private ComputePipeline commandGenMinimalConfigBinding0ReadProbePipeline;
    private ComputePipeline commandGenHardcodedBinding0ReadPipeline;
    private Buffer drawCommandBuffer;
    private Buffer drawCountBuffer;
    private Buffer drawCommandDebugReadbackBuffer;
    private Buffer cmdGenConfigBuffer;
    private Buffer cmdGenBinding2ProbeBuffer;
    private Buffer cmdGenRenderListAltProbeBuffer;
    private Buffer cmdGenMinimalTinySsboReadProbeBuffer;
    private Buffer cmdGenMinimalConfigReadProbePlaceholderBuffer;
    private int lastCmdGenConfigMetadataSectionCapacity;
    private int lastCmdGenConfigRenderListCapacity;
    private int lastCmdGenConfigGeometryCapacityQuads;
    private int lastCmdGenConfigDrawCommandCapacity;
    private int lastCmdGenConfigDrawCountCapacityWords;
    private int lastCmdGenConfigFlags;
    private boolean cmdGenConfigUploaded;
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
    private long lastCmdgenRenderListBufferId;
    private long lastCmdgenRenderListRangeBytes;
    private boolean cmdgenDescriptorsReboundThisFrame;
    private String lastCmdgenDescriptorDiagnostic = "";
    private String lastCmdgenCommandBufferDiagnostic = "";
    private String lastControlledSmokeDiagnostic = "";
    private String lastControlledRenderListWordsDiagnostic = "";
    private boolean freed;

    public void ensureDrawPipeline() {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (this.graphicsPipeline != null) return;

        URL configUrl = VulkanBerylSectionDrawPipeline.class.getResource(DRAW_SHADER_CONFIG);
        if (configUrl == null) throw new IllegalStateException("Missing section draw shader config: " + DRAW_SHADER_CONFIG);

        JsonObject config;
        try (InputStreamReader reader = new InputStreamReader(configUrl.openStream(), StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load section draw shader config: " + DRAW_SHADER_CONFIG, e);
        }

        VertexFormat drawVertexFormat = resolveDummyVertexFormat();
        Pipeline.Builder builder = new Pipeline.Builder(drawVertexFormat);
        List<UBO> drawDescriptors = createManualDrawDescriptors();
        VulkanBerylDebugLog.verboseOnce("section-draw-descriptor-layout", "Section draw descriptor mode=manual_dense, bindings=[0,1,2,3,4,5,6], denseFromZero=true, vertexShader=" + DRAW_SHADER_NAME + ", fragmentShader=" + (DEBUG_COLOUR_MODE ? DRAW_DEBUG_FRAGMENT_SHADER_NAME : DRAW_SHADER_NAME) + ", debugColourMode=" + DEBUG_COLOUR_MODE);
        try {
            builder.setUniforms(drawDescriptors, List.of());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create manual section draw descriptor layout (config is validation-only and is not fed to Beryl parseBindings): " + DRAW_SHADER_CONFIG, e);
        }
        String fragmentShaderName = DEBUG_COLOUR_MODE ? DRAW_DEBUG_FRAGMENT_SHADER_NAME : DRAW_SHADER_NAME;
        String fragmentShaderResource = DEBUG_COLOUR_MODE ? DRAW_DEBUG_FRAGMENT_SHADER_RESOURCE : DRAW_FRAGMENT_SHADER_RESOURCE;
        var preprocessedShaders = VulkanBerylShaderImportPreprocessor.preprocessShaderSetToTemp(DRAW_SHADER_RESOURCE, fragmentShaderResource);
        String shaderCompileBase = preprocessedShaders.rootUrl() + DRAW_SHADER_NAME;
        String vertexClasspathPath = VulkanBerylShaderImportPreprocessor.classpathShaderAssetPath(net.minecraft.resources.Identifier.parse(DRAW_SHADER_RESOURCE));
        String fragmentClasspathPath = VulkanBerylShaderImportPreprocessor.classpathShaderAssetPath(net.minecraft.resources.Identifier.parse(fragmentShaderResource));
        URL expectedVertexResource = VulkanBerylSectionDrawPipeline.class.getResource(vertexClasspathPath);
        URL expectedFragmentResource = VulkanBerylSectionDrawPipeline.class.getResource(fragmentClasspathPath);
        VulkanBerylDebugLog.verboseOnce("section-draw-compile-diagnostics", "Section draw compile diagnostics: compileShaderBase=" + shaderCompileBase
                + ", vertexShaderName=" + DRAW_SHADER_NAME
                + ", fragmentShaderName=" + fragmentShaderName
                + ", expectedVertexResource=" + vertexClasspathPath
                + ", expectedFragmentResource=" + fragmentClasspathPath
                + ", vertexResourceExists=" + (expectedVertexResource != null)
                + ", fragmentResourceExists=" + (expectedFragmentResource != null)
                + ", usingPreprocessedTempFiles=true"
                + ", tempRootPath=" + preprocessedShaders.tempRootPath());
        var vertexPrepared = preprocessedShaders.shaders().stream().filter(s -> DRAW_SHADER_NAME.equals(s.shaderName()) && s.tempShaderRelativePath().endsWith(".vsh")).findFirst().orElse(null);
        var fragmentPrepared = preprocessedShaders.shaders().stream().filter(s -> fragmentShaderName.equals(s.shaderName()) && s.tempShaderRelativePath().endsWith(".fsh")).findFirst().orElse(null);
        Path expectedVertexTempPath = preprocessedShaders.tempRootPath().resolve(DRAW_SHADER_NAME + ".vsh");
        Path expectedFragmentTempPath = preprocessedShaders.tempRootPath().resolve(fragmentShaderName + ".fsh");
        VulkanBerylDebugLog.verboseOnce("section-draw-compile-file-diagnostics", "Section draw compile file diagnostics: compileShaders.firstArg=" + shaderCompileBase
                + ", expectedVertexTempPath=" + expectedVertexTempPath
                + ", expectedFragmentTempPath=" + expectedFragmentTempPath
                + ", vertexTempExists=" + java.nio.file.Files.exists(expectedVertexTempPath)
                + ", fragmentTempExists=" + java.nio.file.Files.exists(expectedFragmentTempPath)
                + ", vertexTempBytes=" + readTempFileSize(expectedVertexTempPath)
                + ", fragmentTempBytes=" + readTempFileSize(expectedFragmentTempPath)
                + ", vertexPreparedShaderName=" + (vertexPrepared == null ? "<missing>" : vertexPrepared.shaderName())
                + ", vertexPreparedTempPath=" + (vertexPrepared == null ? "<missing>" : vertexPrepared.tempShaderRelativePath())
                + ", fragmentPreparedShaderName=" + (fragmentPrepared == null ? "<missing>" : fragmentPrepared.shaderName())
                + ", fragmentPreparedTempPath=" + (fragmentPrepared == null ? "<missing>" : fragmentPrepared.tempShaderRelativePath()));
        String vertexPreview = readPreprocessedShaderPreview(expectedVertexTempPath, 220);
        String fragmentPreview = readPreprocessedShaderPreview(expectedFragmentTempPath, 220);
        VulkanBerylDebugLog.verbose("section-draw-vertex-preview", "Section draw preprocessed vertex shader first 220 lines (path=" + expectedVertexTempPath + "):\n" + vertexPreview);
        VulkanBerylDebugLog.verbose("section-draw-fragment-preview", "Section draw preprocessed fragment shader first 220 lines (path=" + expectedFragmentTempPath + "):\n" + fragmentPreview);
        byte[] vertexBytes = readShaderBytes(expectedVertexTempPath, "vertex");
        byte[] fragmentBytes = readShaderBytes(expectedFragmentTempPath, "fragment");
        verifyNoUtf8BomAndLogPrefix("vertex", expectedVertexTempPath, vertexBytes);
        verifyNoUtf8BomAndLogPrefix("fragment", expectedFragmentTempPath, fragmentBytes);
        String vertexSource = new String(vertexBytes, StandardCharsets.UTF_8);
        String fragmentSource = new String(fragmentBytes, StandardCharsets.UTF_8);
        boolean declaresVertexInputs = declaresVertexInputs(vertexSource);
        VulkanBerylDebugLog.verboseOnce("section-draw-vertex-input-diagnostics", "Section draw vertex input diagnostics: vertexFormatSet=" + (drawVertexFormat != null)
                + ", vertexFormatClass=" + (drawVertexFormat == null ? "<null>" : drawVertexFormat.getClass().getName())
                + ", vertexFormat=" + drawVertexFormat
                + ", vertexSize=" + (drawVertexFormat == null ? -1 : drawVertexFormat.getVertexSize())
                + ", shaderDeclaresVertexInputs=" + declaresVertexInputs
                + ", usingDummyVertexInputMode=true");
        VulkanBerylDebugLog.verboseOnce("section-draw-compile-shaders-contract", "Section draw compileShaders contract: Pipeline.Builder.compileShaders(name, vertexSource, fragmentSource) where args 2/3 are GLSL source text, not file paths. "
                + "Using name=" + DRAW_SHADER_NAME
                + ", vertexSourceLength=" + vertexSource.length()
                + ", fragmentSourceLength=" + fragmentSource.length());
        try {
            builder.compileShaders(DRAW_SHADER_NAME, vertexSource, fragmentSource);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section draw shaders (vertex=" + DRAW_SHADER_NAME + ", fragment=" + fragmentShaderName + ", debugMode=" + DEBUG_COLOUR_MODE + ")"
                    + "\ncompileShaders expected args: name + vertexSource + fragmentSource (GLSL text)"
                    + "\nProvided sources read from: " + expectedVertexTempPath + " and " + expectedFragmentTempPath
                    + "\nVertex first lines:\n" + vertexPreview
                    + "\nFragment first lines:\n" + fragmentPreview, e);
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

    private static VertexFormat resolveDummyVertexFormat() {
        VertexFormat positionFormat = tryGetVertexFormatField("POSITION");
        if (positionFormat != null) {
            return positionFormat;
        }
        VertexFormat positionColorFormat = tryGetVertexFormatField("POSITION_COLOR");
        if (positionColorFormat != null) {
            return positionColorFormat;
        }
        throw new IllegalStateException("Unable to resolve a non-null dummy vertex format for VulkanMod graphics pipeline creation");
    }

    private static VertexFormat tryGetVertexFormatField(String fieldName) {
        try {
            var field = DefaultVertexFormat.class.getDeclaredField(fieldName);
            Object value = field.get(null);
            if (value instanceof VertexFormat format) {
                return format;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean declaresVertexInputs(String vertexSource) {
        if (vertexSource == null || vertexSource.isEmpty()) return false;
        String[] lines = vertexSource.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) continue;
            if (trimmed.contains("layout") && trimmed.contains(" in ")) {
                return true;
            }
        }
        return false;
    }


    private static String readPreprocessedShaderPreview(Path shaderPath, int maxLines) {
        StringBuilder preview = new StringBuilder();
        try {
            List<String> lines = java.nio.file.Files.readAllLines(shaderPath, StandardCharsets.UTF_8);
            int limit = Math.min(maxLines, lines.size());
            for (int i = 0; i < limit; i++) {
                preview.append(i + 1).append(": ").append(lines.get(i)).append(System.lineSeparator());
            }
            if (limit == 0) {
                preview.append("<empty>");
            }
        } catch (Exception ex) {
            preview.append("<failed to read: ").append(ex.getMessage()).append(">");
        }
        return preview.toString();
    }

    private static byte[] readShaderBytes(Path shaderPath, String stage) {
        try {
            return Files.readAllBytes(shaderPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read preprocessed " + stage + " shader bytes: " + shaderPath, e);
        }
    }

    private static void verifyNoUtf8BomAndLogPrefix(String stage, Path shaderPath, byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalStateException("Preprocessed " + stage + " shader is empty: " + shaderPath);
        }
        boolean hasUtf8Bom = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
        if (hasUtf8Bom) {
            throw new IllegalStateException("Preprocessed " + stage + " shader starts with UTF-8 BOM (EF BB BF), expected first byte '#': " + shaderPath);
        }
        int previewLength = Math.min(32, bytes.length);
        StringBuilder hex = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < previewLength; i++) {
            int b = bytes[i] & 0xFF;
            if (i > 0) hex.append(' ');
            hex.append(String.format("%02X", b));
            text.append(b >= 32 && b <= 126 ? (char) b : '.');
        }
        VulkanBerylDebugLog.verbose("section-draw-" + stage + "-shader-byte-prefix", "Section draw " + stage + " shader byte prefix: path=" + shaderPath
                + ", firstByte=0x" + String.format("%02X", bytes[0] & 0xFF)
                + ", expectedFirstByte=0x23(#)"
                + ", previewHex=" + hex
                + ", previewText='" + text + "'");
    }


    public void ensureDrawResourcesBound(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (geometryData == null) throw new IllegalArgumentException("geometryData must not be null");
        if (renderList == null) throw new IllegalArgumentException("renderList must not be null");
        if (this.graphicsPipeline == null) throw new IllegalStateException("graphics pipeline must be created before resources are bound");

        long geometryBytes = geometryData.getGeometryBuffer().getBufferSize();
        long metadataBytes = geometryData.getMetadataBuffer().getBufferSize();
        long renderListBytes = renderList.getBuffer().getBufferSize();
        VulkanBerylDebugLog.trace("draw-descriptor-bind-preflight", "Draw descriptor bind preflight: geometryBytes=" + geometryBytes
                + ", metadataBytes=" + metadataBytes
                + ", renderListBytes=" + renderListBytes
                + ", descriptorStrategy=int_only_BufferSlice_set"
                + ", maxDescriptorRangeBytes=" + VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES
                + ", geometryCapacityCapped=" + geometryData.wasGeometryCapacityCapped()
                + ", requestedGeometryCapacityBytes=" + geometryData.getRequestedGeometryCapacityBytes()
                + ", actualGeometryCapacityBytes=" + geometryData.getGeometryCapacityBytes());

        bindStorageBinding(GEOMETRY_BINDING, geometryData.getGeometryBuffer(), "geometryData.geometryBuffer");
        bindStorageBinding(METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindStorageBinding(RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        this.ensureCommandBuffers(renderList.getMaxEntryCount());
        this.ensureCommandGenPipeline();
        if (CMDGEN_DISPATCH_NOOP || CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE) {
            this.ensureCommandGenNoopPipeline();
        }
        if (CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE) {
            this.ensureCommandGenMinimalTinySsboReadProbePipeline();
        }
        if (CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE) {
            this.ensureCommandGenMinimalRenderListReadProbePipeline(renderList.getBuffer());
        }
        if (CMDGEN_MINIMAL_CONFIG_READ_PROBE) {
            this.ensureCommandGenMinimalConfigReadProbePipeline();
        }
        if (CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE) {
            this.ensureCommandGenMinimalConfigBinding0ReadProbePipeline();
        }
        if (CMDGEN_HARDCODED_READ_BINDING0_ONLY) {
            this.ensureCommandGenHardcodedBinding0ReadPipeline(renderList.getBuffer());
        }
        bindComputeStorageBinding(CMDGEN_METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindComputeStorageBinding(CMDGEN_RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        ensureAndBindCmdgenBinding2ProbeBuffer();
        this.cmdgenDescriptorsReboundThisFrame = true;
        logCmdgenRenderListDescriptorState("main", renderList.getBuffer(), renderList.getBuffer().getBufferSize(), true);
        bindComputeStorageBinding(CMDGEN_DRAW_COMMAND_BINDING, this.drawCommandBuffer, "drawCommandBuffer");
        bindComputeStorageBinding(CMDGEN_DRAW_COUNT_BINDING, this.drawCountBuffer, "drawCountBuffer");
        bindComputeStorageBinding(CMDGEN_CONFIG_BINDING, this.cmdGenConfigBuffer, "cmdGenConfigBuffer");
        VulkanBerylDebugLog.once("cmdgen-descriptors-bound", "cmdgen descriptors bound");
        this.resourcesBound = true;
    }

    public boolean isReady() {
        return this.graphicsPipeline != null && this.resourcesBound && !this.freed;
    }
    public void pollDebugReadback() {
        this.consumePendingDebugCommandSampleIfReady();
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, null);
    }
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
        ControlledRenderListSmoke controlledSmoke = ControlledRenderListSmoke.disabled();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (RENDERLIST_SMOKE_ONE_ENTRY) {
            if (commandBuffer == null) {
                throw new IllegalStateException("VULKANMOD_BERYL command buffer is unavailable for controlled render-list smoke");
            }
            controlledSmoke = recordControlledRenderListSmoke(commandBuffer, geometryData, renderList);
        }
        int rawVisibleCount = controlledSmoke.enabled() ? (controlledSmoke.safe() ? 1 : 0) : renderList.getLastVisibleCount();
        int visibleCount = Math.max(0, Math.min(rawVisibleCount, maxEntryCount));
        VulkanBerylRenderBackendRuntime.FrameSafetyState frameSafety = VulkanBerylRenderBackendRuntime.getLastFrameSafetyState();
        boolean noOpCmdgenSmoke = ENABLE_CMDGEN_DISPATCH && !ENABLE_INDIRECT_DRAW;
        boolean cmdgenAllowed = ENABLE_CMDGEN_DISPATCH && (noOpCmdgenSmoke || frameSafety.allowCmdGen() || controlledSmoke.safe());
        boolean cmdgenSampleValid = this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0;
        boolean indirectSafetyAllowed = frameSafety.allowIndirectDraw() || controlledSmoke.safe();
        boolean indirectAllowed = ENABLE_INDIRECT_DRAW && cmdgenSampleValid && indirectSafetyAllowed;
        String gateReason = controlledSmoke.enabled() ? controlledSmoke.reason() : frameSafety.reason();
        String indirectGateReason = !ENABLE_INDIRECT_DRAW
                ? "indirect_draw_disabled"
                : (!cmdgenSampleValid ? "waiting_for_valid_cmdgen_sample" : (!indirectSafetyAllowed ? gateReason : "ready"));
        VulkanBerylDebugLog.trace("gpu-stage-gate", "GPU stage gate: traversalDispatch=true cmdgenDispatch=" + cmdgenAllowed + " indirectDraw=" + indirectAllowed + " reason=" + gateReason);
        if (visibleCount <= 0) {
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "visible_count_zero_or_negative");
            return new OpaqueDrawSubmission(0, "indirect_generated_per_section", 0L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "visible_count_zero_or_negative");
        }
        if (!cmdgenAllowed) {
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "cmdgen_gate:" + gateReason);
            return new OpaqueDrawSubmission(0, "indirect_generated_per_section", 0L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "cmdgen_gate:" + gateReason);
        }

        if (commandBuffer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL command buffer is unavailable");
        }

        CmdgenIsolationStage isolationStage = selectedCmdgenIsolationStage();

        if (CMDGEN_CREATE_ONLY) {
            VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.commandGenPipeline.getId());
            this.commandGenPipeline.bindDescriptorSets(commandBuffer, 0);
            logCmdgenCommandBufferUse(isolationStage == null ? "create_only" : isolationStage.envName(), commandBuffer, commandBuffer, true);
            VulkanBerylDebugLog.once("cmdgen-create-only-skipped", "cmdgen dispatch skipped/create-only");
            VulkanBerylDebugLog.once("cmdgen-debug-readback-state", "cmdgen debug readback " + (CMDGEN_DEBUG_READBACK ? "enabled" : "skipped"));
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "cmdgen_create_only");
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "cmdgen_create_only");
        }

        boolean singleSsboReadProbe = CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE
                || CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE
                || CMDGEN_MINIMAL_CONFIG_READ_PROBE
                || CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE
                || CMDGEN_HARDCODED_READ_BINDING0_ONLY;
        String cmdgenBlocker = validateCmdgenDispatchInputs(geometryData, renderList, visibleCount, controlledSmoke, (noOpCmdgenSmoke && isolationStage == null) || singleSsboReadProbe, isolationStage);
        if (cmdgenBlocker != null) {
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "cmdgen_blocked:" + cmdgenBlocker);
            return new OpaqueDrawSubmission(0, "indirect_generated_per_section", 0L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "cmdgen_blocked:" + cmdgenBlocker);
        }

        if (CMDGEN_DISPATCH_NOOP || CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE) {
            if (this.commandGenNoopPipeline == null) {
                throw new IllegalStateException("cmdgen noop pipeline missing");
            }
            VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.commandGenNoopPipeline.getId());
            if (CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE) {
                this.commandGenNoopPipeline.bindDescriptorSets(commandBuffer, 0);
                logCmdgenCommandBufferUse("descriptor_noop_probe", commandBuffer, commandBuffer, true);
            }
            VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
            VulkanBerylDebugLog.once("cmdgen-noop-dispatch-submitted", "cmdgen noop dispatch submitted: descriptorBindProbe=" + CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE);
        } else {
            validateDrawCommandBuffer(visibleCount);
            int cmdgenFlags = isolationStage == null ? (noOpCmdgenSmoke ? CMDGEN_FLAG_NOOP_SMOKE : 0) : isolationStage.shaderFlag();
            boolean useAltRenderListBuffer = CMDGEN_RENDERLIST_ALT_BUFFER_PROBE && (isolationStage != null || CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE || CMDGEN_HARDCODED_READ_BINDING0_ONLY);
            if (useAltRenderListBuffer) {
                ensureAndBindCmdgenRenderListAltProbeBuffer();
            }
            updateAndBindCmdGenConfigBuffer(geometryData, useAltRenderListBuffer ? cmdGenRenderListAltProbeCapacityEntries() : renderList.getMaxEntryCount(), cmdgenFlags);
            if (CMDGEN_UPLOAD_CONFIG_ONLY) {
                return stopCmdgenIsolation(visibleCount, "cmdgen_upload_config_only");
            }
            clearDrawCommandState(commandBuffer);
            if (useAltRenderListBuffer) {
                recordCmdgenRenderListAltProbeUpload(commandBuffer);
            }
            if (CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenMinimalTinySsboReadProbePipeline, this.cmdGenMinimalTinySsboReadProbeBuffer, 0, CMDGEN_MINIMAL_SSBO_READ_SHADER_NAME, "minimal_tiny_ssbo_read_probe", true);
            }
            if (CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenMinimalRenderListReadProbePipeline, useAltRenderListBuffer ? this.cmdGenRenderListAltProbeBuffer : renderList.getBuffer(), CMDGEN_RENDER_LIST_BINDING, CMDGEN_MINIMAL_SSBO_READ_SHADER_NAME, "minimal_renderlist_manualubo_read_probe", false);
            }
            if (CMDGEN_MINIMAL_CONFIG_READ_PROBE) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenMinimalConfigReadProbePipeline, this.cmdGenConfigBuffer, CMDGEN_CONFIG_BINDING, CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME, "minimal_config_read_probe", false);
            }
            if (CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenMinimalConfigBinding0ReadProbePipeline, this.cmdGenConfigBuffer, CMDGEN_RENDER_LIST_BINDING, CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_NAME, "minimal_config_binding0_read_probe", false);
            }
            if (CMDGEN_HARDCODED_READ_BINDING0_ONLY) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenHardcodedBinding0ReadPipeline, useAltRenderListBuffer ? this.cmdGenRenderListAltProbeBuffer : renderList.getBuffer(), CMDGEN_RENDER_LIST_BINDING, CMDGEN_HARDCODED_BINDING0_READ_SHADER_NAME, "hardcoded_read_binding0_only", false);
            }
            barrierTransferToCompute(commandBuffer);
            if (CMDGEN_CLEAR_OUTPUTS_ONLY) {
                return stopCmdgenIsolation(visibleCount, "cmdgen_clear_outputs_only");
            }
            VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.commandGenPipeline.getId());
            this.commandGenPipeline.bindDescriptorSets(commandBuffer, 0);
            logCmdgenCommandBufferUse(useAltRenderListBuffer ? "alt_renderlist_probe" : (isolationStage == null ? "full" : isolationStage.envName()), commandBuffer, commandBuffer, true);
            if (CMDGEN_BIND_FULL_ONLY) {
                return stopCmdgenIsolation(visibleCount, "cmdgen_bind_full_only");
            }
            if (isolationStage == null && !ENABLE_INDIRECT_DRAW) {
                return stopCmdgenIsolation(visibleCount, "cmdgen_dispatch_blocked:select_isolation_stage_with_indirect_disabled");
            }
            int groupCountX = isolationStage == null ? (noOpCmdgenSmoke ? 1 : ((visibleCount + 127) >>> 7)) : 1;
            VK10.vkCmdDispatch(commandBuffer, groupCountX, 1, 1);
            VulkanBerylDebugLog.once("cmdgen-isolation-dispatch", "cmdgen dispatch submitted: stage=" + (isolationStage == null ? "full" : isolationStage.envName()) + ", groupsX=" + groupCountX);

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
        }

        this.consumePendingDebugCommandSampleIfReady();
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, null);
        int sampledCommandCount = (!CMDGEN_DEBUG_READBACK || CMDGEN_DISPATCH_NOOP || noOpCmdgenSmoke) ? 0 : Math.min(DRAW_COMMAND_DEBUG_SAMPLE_LIMIT, visibleCount);
        VulkanBerylDebugLog.once("cmdgen-debug-readback-state", "cmdgen debug readback " + (sampledCommandCount > 0 ? "enabled" : "skipped"));
        scheduleDebugCommandReadback(commandBuffer, sampledCommandCount, visibleCount, geometryData.getGeometryBuffer().getBufferSize());
        if (!indirectAllowed) {
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, "indirect_gate:" + indirectGateReason);
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "indirect_gate:" + indirectGateReason);
        }
        renderer.bindGraphicsPipeline(this.graphicsPipeline);
        this.bindSceneUniform(viewport);
        this.graphicsPipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDrawIndirect(commandBuffer, this.drawCommandBuffer.getId(), 0L, visibleCount, DRAW_COMMAND_STRIDE_BYTES);
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        long submittedQuadCount = sample.sampledQuadCount >= 0L ? sample.sampledQuadCount : -1L;
        return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", submittedQuadCount, visibleCount, sample.sampledCommandCount, sample.invalidSampledCommandCount, sample.sampledQuadCount, this.debugSamplePending, null);
    }


    private OpaqueDrawSubmission dispatchMinimalSsboReadProbe(VkCommandBuffer commandBuffer, int visibleCount, ComputePipeline pipeline, Buffer buffer, int descriptorBinding, String shaderName, String stage, boolean uploadTinyWord) {
        if (pipeline == null) throw new IllegalStateException("cmdgen SSBO read probe pipeline missing: stage=" + stage);
        if (buffer == null) throw new IllegalStateException("cmdgen SSBO read probe buffer missing: stage=" + stage);
        if (uploadTinyWord) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var words = stack.ints(1);
                VK10.vkCmdUpdateBuffer(commandBuffer, buffer.getId(), 0L, words);
            }
        }
        UBO ubo = pipeline.getUBO(candidate -> candidate.binding == descriptorBinding);
        if (ubo == null) throw new IllegalStateException("cmdgen SSBO read probe descriptor missing: stage=" + stage + ", binding=" + descriptorBinding);
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(descriptorBinding, stage, bufferSize);
        }
        if (buffer == this.cmdGenConfigBuffer) {
            if (bufferSize != CMDGEN_CONFIG_SIZE_BYTES) {
                throw new IllegalStateException("cmdGenConfigBuffer descriptor range mismatch: stage=" + stage + ", bufferBytes=" + bufferSize + ", expectedRangeBytes=" + CMDGEN_CONFIG_SIZE_BYTES);
            }
            if (!this.cmdGenConfigUploaded) {
                throw new IllegalStateException("cmdGenConfigBuffer read probe requested before config upload: stage=" + stage);
            }
            logCmdGenConfigBufferState("before_dispatch:" + stage);
        }
        ubo.getBufferSlice().set(buffer, 0L, (int) bufferSize);
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen SSBO read probe dispatch submitted: stage=" + stage
                + ", shader=" + shaderName
                + ", descriptorBinding=" + descriptorBinding
                + ", descriptorRangeBytes=" + bufferSize
                + ", uploadedTinyWord=" + uploadTinyWord);
        return stopCmdgenIsolation(visibleCount, "cmdgen_" + stage);
    }

    private OpaqueDrawSubmission stopCmdgenIsolation(int visibleCount, String reason) {
        VulkanBerylDebugLog.once(reason, "cmdgen isolation stop: " + reason);
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, reason);
        return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, reason);
    }

    private static CmdgenIsolationStage selectedCmdgenIsolationStage() {
        CmdgenIsolationStage selected = null;
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_BINDING0_ONLY, CmdgenIsolationStage.READ_BINDING0_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_BINDING1_ONLY, CmdgenIsolationStage.READ_BINDING1_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_BINDING2_PROBE_ONLY, CmdgenIsolationStage.READ_BINDING2_PROBE_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY, CmdgenIsolationStage.READ_RENDERLIST_HEADER_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY, CmdgenIsolationStage.READ_RENDERLIST_COUNT_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ENTRY0_QUAD_START_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_METADATA_ONLY, CmdgenIsolationStage.READ_METADATA_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_WRITE_DRAWS_ONLY, CmdgenIsolationStage.WRITE_DRAWS_ONLY);
        return selected;
    }

    private static CmdgenIsolationStage selectCmdgenIsolationStage(CmdgenIsolationStage current, boolean enabled, CmdgenIsolationStage candidate) {
        if (!enabled) return current;
        if (current != null) {
            VulkanBerylDebugLog.once("cmdgen-isolation-multiple", "multiple cmdgen shader isolation stages enabled; using earliest stage=" + current.envName());
            return current;
        }
        return candidate;
    }

    private enum CmdgenIsolationStage {
        READ_BINDING0_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY", CMDGEN_FLAG_READ_BINDING0_ONLY, Integer.BYTES),
        READ_BINDING1_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING1_ONLY", CMDGEN_FLAG_READ_BINDING1_ONLY, 0),
        READ_BINDING2_PROBE_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING2_PROBE_ONLY", CMDGEN_FLAG_READ_BINDING2_PROBE_ONLY, 0),
        READ_RENDERLIST_HEADER_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY", CMDGEN_FLAG_READ_RENDERLIST_HEADER_ONLY, Integer.BYTES),
        READ_RENDERLIST_COUNT_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY", CMDGEN_FLAG_READ_RENDERLIST_COUNT_ONLY, Integer.BYTES),
        READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY, 2 * Integer.BYTES),
        READ_RENDERLIST_ENTRY0_QUAD_START_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY, 3 * Integer.BYTES),
        READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY, 4 * Integer.BYTES),
        READ_RENDERLIST_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ONLY, 2 * Integer.BYTES),
        READ_METADATA_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_METADATA_ONLY", CMDGEN_FLAG_READ_METADATA_ONLY, 0),
        WRITE_DRAWS_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_WRITE_DRAWS_ONLY", CMDGEN_FLAG_WRITE_DRAWS_ONLY, 0);

        private final String envName;
        private final int shaderFlag;
        private final int minimumRenderListBytes;

        CmdgenIsolationStage(String envName, int shaderFlag, int minimumRenderListBytes) {
            this.envName = envName;
            this.shaderFlag = shaderFlag;
            this.minimumRenderListBytes = minimumRenderListBytes;
        }

        String envName() { return this.envName; }
        int shaderFlag() { return this.shaderFlag; }
        int minimumRenderListBytes() { return this.minimumRenderListBytes; }
        boolean requiresControlledSection() {
            return this == READ_METADATA_ONLY || this == WRITE_DRAWS_ONLY;
        }
    }

    private ControlledRenderListSmoke recordControlledRenderListSmoke(VkCommandBuffer commandBuffer, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        ControlledRenderListSmoke smoke = findControlledRenderListSmokeSection(geometryData, renderList);
        if (!smoke.safe()) {
            renderList.setLastVisibleCount(0);
            VulkanBerylLodBringupDiagnostics.updateControlledRenderList(false, -1, smoke.reason());
            logControlledSmokeDiagnosticIfChanged(geometryData, smoke);
            return smoke;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int uploadWordCount = Math.max(2, Math.min(5, Math.toIntExact(renderList.getBuffer().getBufferSize() / Integer.BYTES)));
            var renderListHeader = stack.mallocInt(uploadWordCount);
            renderListHeader.put(0, 1);
            renderListHeader.put(1, smoke.sectionId());
            if (uploadWordCount > 2) renderListHeader.put(2, smoke.quadStart());
            if (uploadWordCount > 3) renderListHeader.put(3, (int) Math.min(smoke.quadCount(), 0xffffffffL));
            if (uploadWordCount > 4) renderListHeader.put(4, 0);
            VK10.vkCmdUpdateBuffer(commandBuffer, renderList.getBuffer().getId(), 0L, renderListHeader);
            logControlledRenderListWordsIfChanged(
                    renderListHeader.get(0),
                    renderListHeader.get(1),
                    uploadWordCount > 2 ? renderListHeader.get(2) : 0,
                    uploadWordCount > 3 ? renderListHeader.get(3) : 0,
                    uploadWordCount > 4 ? renderListHeader.get(4) : 0,
                    Integer.BYTES);

            VkMemoryBarrier.Buffer transferToCompute = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, transferToCompute, null, null);
        }
        renderList.setLastVisibleCount(1);
        VulkanBerylLodBringupDiagnostics.updateControlledRenderList(true, smoke.sectionId(), smoke.reason());
        logControlledSmokeDiagnosticIfChanged(geometryData, smoke);
        return smoke;
    }

    private void logControlledRenderListWordsIfChanged(int word0, int word1, int word2, int word3, int word4, int entry0ByteOffset) {
        String diagnostic = "word0=" + Integer.toUnsignedLong(word0)
                + " word1=" + Integer.toUnsignedLong(word1)
                + " word2=" + Integer.toUnsignedLong(word2)
                + " word3=" + Integer.toUnsignedLong(word3)
                + " word4=" + Integer.toUnsignedLong(word4)
                + " entry0ByteOffset=" + entry0ByteOffset
                + " layout=word0_visibleCount_word1_entry0SectionId_word2_diagQuadStart_word3_diagQuadCount_word4_diagPadding";
        if (diagnostic.equals(this.lastControlledRenderListWordsDiagnostic)) {
            return;
        }
        this.lastControlledRenderListWordsDiagnostic = diagnostic;
        VulkanBerylDebugLog.always("Controlled render-list words before cmdgen: " + diagnostic);
    }

    private ControlledRenderListSmoke findControlledRenderListSmokeSection(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        if (renderList.getMaxEntryCount() <= 0) {
            return ControlledRenderListSmoke.failed("render-list capacity is zero");
        }
        if (geometryData.getMaxSectionCount() <= 0 || geometryData.getMetadataCapacityBytes() <= 0L) {
            return ControlledRenderListSmoke.failed("metadata_capacity_zero");
        }
        if (geometryData.getGeometryCapacityBytes() <= 0L) {
            return ControlledRenderListSmoke.failed("geometry_capacity_zero");
        }
        if (geometryData.getGeometrySyncGeneration() <= 0L) {
            return ControlledRenderListSmoke.failed("geometry_sync_not_seen");
        }
        int sectionCount = Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount());
        if (sectionCount <= 0) {
            return ControlledRenderListSmoke.failed("section_count_zero");
        }
        long usedGeometryBytes = geometryData.getUsedGeometryBytes();
        if (usedGeometryBytes <= 0L) {
            return ControlledRenderListSmoke.failed("geometry_used_bytes_zero");
        }
        int firstNonZeroMetadataSection = geometryData.findFirstNonZeroSectionMetadata();
        if (firstNonZeroMetadataSection < 0) {
            return ControlledRenderListSmoke.failed("metadata_mirror_empty");
        }

        boolean sawOpaqueQuads = false;
        String firstOutOfBounds = null;
        for (int sectionId = 0; sectionId < sectionCount; sectionId++) {
            if (!geometryData.hasNonZeroSectionMetadata(sectionId)) {
                continue;
            }
            int quadStart = geometryData.getSectionMetadataInt(sectionId, 3);
            long translucentQuadCount = extractTranslucentQuadCount(geometryData, sectionId);
            long opaqueQuadCount = extractOpaqueQuadCount(geometryData, sectionId);
            if (opaqueQuadCount <= 0L) {
                continue;
            }
            sawOpaqueQuads = true;
            long opaqueQuadStart = Integer.toUnsignedLong(quadStart) + translucentQuadCount;
            long requiredBytes = Math.addExact(Math.multiplyExact(opaqueQuadStart, 8L), Math.multiplyExact(opaqueQuadCount, 8L));
            if (requiredBytes > usedGeometryBytes || requiredBytes > geometryData.getGeometryCapacityBytes()) {
                if (firstOutOfBounds == null) {
                    firstOutOfBounds = "quadStart/quadCount out of bounds: sectionId=" + sectionId
                            + " quadStart=" + Integer.toUnsignedLong(quadStart)
                            + " translucentQuadCount=" + translucentQuadCount
                            + " opaqueQuadStart=" + opaqueQuadStart
                            + " opaqueQuadCount=" + opaqueQuadCount
                            + " requiredBytes=" + requiredBytes
                            + " usedGeometryBytes=" + usedGeometryBytes
                            + " geometryCapacityBytes=" + geometryData.getGeometryCapacityBytes();
                }
                continue;
            }
            return ControlledRenderListSmoke.safe(sectionId, (int) opaqueQuadStart, opaqueQuadCount);
        }
        if (!sawOpaqueQuads) {
            return ControlledRenderListSmoke.failed("opaque_quad_count_zero");
        }
        return ControlledRenderListSmoke.failed(firstOutOfBounds == null ? "quad_bounds_invalid" : firstOutOfBounds);
    }

    private void logControlledSmokeDiagnosticIfChanged(VulkanBerylSectionGeometryData geometryData, ControlledRenderListSmoke smoke) {
        int sectionCount = Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount());
        int firstNonZeroMetadataSection = geometryData.findFirstNonZeroSectionMetadata();
        String diagnostic = "sectionCount=" + sectionCount
                + " usedGeometryBytes=" + geometryData.getUsedGeometryBytes()
                + " metadataCapacityBytes=" + geometryData.getMetadataCapacityBytes()
                + " firstNonzeroMetadataSection=" + firstNonZeroMetadataSection
                + " blocker=" + smoke.reason();
        if (diagnostic.equals(this.lastControlledSmokeDiagnostic)) {
            return;
        }
        this.lastControlledSmokeDiagnostic = diagnostic;
        if (smoke.safe()) {
            VulkanBerylDebugLog.always("Controlled render-list smoke ready: " + diagnostic
                    + " sectionId=" + smoke.sectionId()
                    + " quadStart=" + smoke.quadStart()
                    + " quadCount=" + smoke.quadCount());
        } else {
            VulkanBerylDebugLog.always("Controlled render-list smoke blocked: " + diagnostic);
        }
    }

    private long extractTranslucentQuadCount(VulkanBerylSectionGeometryData geometryData, int sectionId) {
        return geometryData.getSectionMetadataInt(sectionId, 4) & 0xFFFFL;
    }

    private long extractOpaqueQuadCount(VulkanBerylSectionGeometryData geometryData, int sectionId) {
        long total = 0L;
        int b0 = geometryData.getSectionMetadataInt(sectionId, 4);
        int b1 = geometryData.getSectionMetadataInt(sectionId, 5);
        int b2 = geometryData.getSectionMetadataInt(sectionId, 6);
        int b3 = geometryData.getSectionMetadataInt(sectionId, 7);
        total += (b0 >>> 16) & 0xFFFFL;
        total += b1 & 0xFFFFL;
        total += (b1 >>> 16) & 0xFFFFL;
        total += b2 & 0xFFFFL;
        total += (b2 >>> 16) & 0xFFFFL;
        total += b3 & 0xFFFFL;
        total += (b3 >>> 16) & 0xFFFFL;
        return total;
    }

    private record ControlledRenderListSmoke(boolean enabled, boolean safe, int sectionId, int quadStart, long quadCount, String reason) {
        static ControlledRenderListSmoke disabled() { return new ControlledRenderListSmoke(false, false, -1, 0, 0L, "disabled"); }
        static ControlledRenderListSmoke failed(String reason) { return new ControlledRenderListSmoke(true, false, -1, 0, 0L, reason); }
        static ControlledRenderListSmoke safe(int sectionId, int quadStart, long quadCount) { return new ControlledRenderListSmoke(true, true, sectionId, quadStart, quadCount, "controlled_render_list_ready"); }
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
        if (this.commandGenNoopPipeline != null) {
            this.commandGenNoopPipeline.cleanUp();
            this.commandGenNoopPipeline = null;
        }
        if (this.commandGenMinimalTinySsboReadProbePipeline != null) {
            this.commandGenMinimalTinySsboReadProbePipeline.cleanUp();
            this.commandGenMinimalTinySsboReadProbePipeline = null;
        }
        if (this.commandGenMinimalRenderListReadProbePipeline != null) {
            this.commandGenMinimalRenderListReadProbePipeline.cleanUp();
            this.commandGenMinimalRenderListReadProbePipeline = null;
        }
        if (this.commandGenMinimalConfigReadProbePipeline != null) {
            this.commandGenMinimalConfigReadProbePipeline.cleanUp();
            this.commandGenMinimalConfigReadProbePipeline = null;
        }
        if (this.commandGenHardcodedBinding0ReadPipeline != null) {
            this.commandGenHardcodedBinding0ReadPipeline.cleanUp();
            this.commandGenHardcodedBinding0ReadPipeline = null;
        }
        if (this.drawCommandBuffer != null) {
            this.drawCommandBuffer.scheduleFree();
            this.drawCommandBuffer = null;
        }
        if (this.drawCountBuffer != null) {
            this.drawCountBuffer.scheduleFree();
            this.drawCountBuffer = null;
        }
        if (this.cmdGenConfigBuffer != null) {
            this.cmdGenConfigBuffer.scheduleFree();
            this.cmdGenConfigBuffer = null;
        }
        if (this.cmdGenBinding2ProbeBuffer != null) {
            this.cmdGenBinding2ProbeBuffer.scheduleFree();
            this.cmdGenBinding2ProbeBuffer = null;
        }
        if (this.cmdGenRenderListAltProbeBuffer != null) {
            this.cmdGenRenderListAltProbeBuffer.scheduleFree();
            this.cmdGenRenderListAltProbeBuffer = null;
        }
        if (this.cmdGenMinimalTinySsboReadProbeBuffer != null) {
            this.cmdGenMinimalTinySsboReadProbeBuffer.scheduleFree();
            this.cmdGenMinimalTinySsboReadProbeBuffer = null;
        }
        if (this.cmdGenMinimalConfigReadProbePlaceholderBuffer != null) {
            this.cmdGenMinimalConfigReadProbePlaceholderBuffer.scheduleFree();
            this.cmdGenMinimalConfigReadProbePlaceholderBuffer = null;
        }
        if (this.drawCommandDebugReadbackBuffer != null) {
            this.drawCommandDebugReadbackBuffer.scheduleFree();
            this.drawCommandDebugReadbackBuffer = null;
        }
        this.resourcesBound = false;
    }
    private static long readTempFileSize(Path path) {
        try {
            return java.nio.file.Files.exists(path) ? java.nio.file.Files.size(path) : -1L;
        } catch (Exception e) {
            VulkanBerylDebugLog.warnRateLimited("read-temp-shader-file-size", "Failed reading temp shader file size for " + path + ": " + e);
            return -1L;
        }
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
        ensureCmdGenConfigBuffer();
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
        if (this.cmdGenBinding2ProbeBuffer == null) {
            this.cmdGenBinding2ProbeBuffer = new Buffer("voxy_vulkanberyl_cmdgen_binding2_probe", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
            this.cmdGenBinding2ProbeBuffer.createBuffer(Integer.BYTES);
        }
        this.drawCommandCapacity = maxEntryCount;
    }

    private void ensureCmdGenConfigBuffer() {
        if (this.cmdGenConfigBuffer != null) {
            if (this.cmdGenConfigBuffer.getBufferSize() < CMDGEN_CONFIG_SIZE_BYTES) {
                throw new IllegalStateException("cmdGenConfigBuffer is too small: bufferBytes=" + this.cmdGenConfigBuffer.getBufferSize() + ", required=" + CMDGEN_CONFIG_SIZE_BYTES);
            }
            return;
        }
        this.cmdGenConfigBuffer = new Buffer("voxy_vulkanberyl_cmdgen_config", CMDGEN_CONFIG_USAGE_FLAGS, MemoryTypes.GPU_MEM);
        this.cmdGenConfigBuffer.createBuffer(CMDGEN_CONFIG_SIZE_BYTES);
        VulkanBerylDebugLog.once("cmdgen-config-buffer-created", "cmdGenConfigBuffer created: bufferId="
                + this.cmdGenConfigBuffer.getId()
                + ", capacityBytes=" + this.cmdGenConfigBuffer.getBufferSize()
                + ", usage=" + cmdGenConfigUsageString());
    }

    private void clearDrawCommandState(VkCommandBuffer commandBuffer) {
        VK10.vkCmdFillBuffer(commandBuffer, this.drawCountBuffer.getId(), 0L, 4L, 0);
        long clearBytes = Math.min(this.drawCommandBuffer.getBufferSize(), (long) DRAW_COMMAND_DEBUG_SAMPLE_LIMIT * DRAW_COMMAND_STRIDE_BYTES);
        if (clearBytes > 0L) {
            VK10.vkCmdFillBuffer(commandBuffer, this.drawCommandBuffer.getId(), 0L, clearBytes, 0);
        }
        if (this.cmdGenBinding2ProbeBuffer != null) {
            VK10.vkCmdFillBuffer(commandBuffer, this.cmdGenBinding2ProbeBuffer.getId(), 0L, Integer.BYTES, 0);
        }
    }


    private void ensureAndBindCmdgenBinding2ProbeBuffer() {
        if (this.cmdGenBinding2ProbeBuffer == null) {
            this.cmdGenBinding2ProbeBuffer = new Buffer("voxy_vulkanberyl_cmdgen_binding2_probe",
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    MemoryTypes.GPU_MEM);
            this.cmdGenBinding2ProbeBuffer.createBuffer(Integer.BYTES);
            VulkanBerylDebugLog.once("cmdgen-binding2-probe-created", "cmdgen binding-2 probe buffer created: bufferId="
                    + this.cmdGenBinding2ProbeBuffer.getId()
                    + ", capacityBytes=" + this.cmdGenBinding2ProbeBuffer.getBufferSize()
                    + ", usage=STORAGE|TRANSFER_DST");
        }
        bindComputeStorageBinding(CMDGEN_BINDING2_PROBE_BINDING, this.cmdGenBinding2ProbeBuffer, "cmdGenBinding2ProbeBuffer");
    }

    private void ensureAndBindCmdgenRenderListAltProbeBuffer() {
        if (this.cmdGenRenderListAltProbeBuffer == null) {
            this.cmdGenRenderListAltProbeBuffer = new Buffer("voxy_vulkanberyl_cmdgen_renderlist_alt_probe",
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    MemoryTypes.GPU_MEM);
            this.cmdGenRenderListAltProbeBuffer.createBuffer(cmdGenRenderListAltProbeSizeBytes());
            VulkanBerylDebugLog.once("cmdgen-renderlist-alt-probe-created", "cmdgen render-list alternate-buffer probe created: bufferId="
                    + this.cmdGenRenderListAltProbeBuffer.getId()
                    + ", capacityBytes=" + this.cmdGenRenderListAltProbeBuffer.getBufferSize()
                    + ", usage=STORAGE|TRANSFER_DST|TRANSFER_SRC");
        }
        bindComputeStorageBinding(CMDGEN_RENDER_LIST_BINDING, this.cmdGenRenderListAltProbeBuffer, "cmdGenRenderListAltProbeBuffer");
        this.cmdgenDescriptorsReboundThisFrame = true;
        logCmdgenRenderListDescriptorState("alt_probe", this.cmdGenRenderListAltProbeBuffer, this.cmdGenRenderListAltProbeBuffer.getBufferSize(), true);
    }

    private void recordCmdgenRenderListAltProbeUpload(VkCommandBuffer commandBuffer) {
        ensureAndBindCmdgenRenderListAltProbeBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int wordCount = (int) (cmdGenRenderListAltProbeSizeBytes() / Integer.BYTES);
            var words = stack.callocInt(wordCount);
            words.put(0, 1);
            VK10.vkCmdUpdateBuffer(commandBuffer, this.cmdGenRenderListAltProbeBuffer.getId(), 0L, words);
        }
        VulkanBerylDebugLog.once("cmdgen-renderlist-alt-probe-upload", "cmdgen render-list alternate-buffer probe upload recorded: word0=1, bufferId="
                + this.cmdGenRenderListAltProbeBuffer.getId()
                + ", capacityBytes=" + this.cmdGenRenderListAltProbeBuffer.getBufferSize()
                + ", sameCommandBufferAsDispatch=true");
    }

    private static int cmdGenRenderListAltProbeCapacityEntries() {
        return 4;
    }

    private static long cmdGenRenderListAltProbeSizeBytes() {
        return (long) (cmdGenRenderListAltProbeCapacityEntries() + 1) * Integer.BYTES;
    }

    private void logCmdgenRenderListDescriptorState(String source, Buffer buffer, long rangeBytes, boolean reboundThisFrame) {
        long bufferId = buffer == null ? 0L : buffer.getId();
        long capacityBytes = buffer == null ? 0L : buffer.getBufferSize();
        String diagnostic = "source=" + source
                + ", renderListBufferId=" + bufferId
                + ", renderListCapacityBytes=" + capacityBytes
                + ", descriptorBinding=" + CMDGEN_RENDER_LIST_BINDING
                + ", descriptorRangeBytes=" + rangeBytes
                + ", descriptorReboundThisFrame=" + reboundThisFrame;
        this.lastCmdgenRenderListBufferId = bufferId;
        this.lastCmdgenRenderListRangeBytes = rangeBytes;
        if (!cmdgenRenderListDescriptorDiagnosticsEnabled() || diagnostic.equals(this.lastCmdgenDescriptorDiagnostic)) {
            return;
        }
        this.lastCmdgenDescriptorDiagnostic = diagnostic;
        VulkanBerylDebugLog.always("cmdgen render-list descriptor state: " + diagnostic);
    }

    private void logCmdgenCommandBufferUse(String stage, VkCommandBuffer uploadCommandBuffer, VkCommandBuffer dispatchCommandBuffer, boolean descriptorReboundThisFrame) {
        boolean sameCommandBuffer = uploadCommandBuffer == dispatchCommandBuffer;
        String diagnostic = "stage=" + stage
                + ", renderListBufferId=" + this.lastCmdgenRenderListBufferId
                + ", descriptorBinding=" + CMDGEN_RENDER_LIST_BINDING
                + ", descriptorRangeBytes=" + this.lastCmdgenRenderListRangeBytes
                + ", descriptorReboundThisFrame=" + descriptorReboundThisFrame
                + ", controlledUploadAndComputeSameCommandBuffer=" + sameCommandBuffer;
        if (!cmdgenRenderListDescriptorDiagnosticsEnabled() || diagnostic.equals(this.lastCmdgenCommandBufferDiagnostic)) {
            return;
        }
        this.lastCmdgenCommandBufferDiagnostic = diagnostic;
        VulkanBerylDebugLog.always("cmdgen render-list command-buffer state: " + diagnostic);
    }

    private static boolean cmdgenRenderListDescriptorDiagnosticsEnabled() {
        return ENABLE_CMDGEN_DISPATCH
                || CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE
                || CMDGEN_SHADER_READ_BINDING0_ONLY
                || CMDGEN_SHADER_READ_BINDING1_ONLY
                || CMDGEN_SHADER_READ_BINDING2_PROBE_ONLY
                || CMDGEN_RENDERLIST_ALT_BUFFER_PROBE
                || RENDERLIST_SMOKE_ONE_ENTRY
                || VulkanBerylDebugLog.TRACE_LOGS
                || VulkanBerylDebugLog.VERBOSE_LOGS;
    }

    private String validateCmdgenDispatchInputs(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, int visibleCount, ControlledRenderListSmoke controlledSmoke, boolean noOpCmdgenSmoke, CmdgenIsolationStage isolationStage) {
        if (this.commandGenPipeline == null) return "cmdgen_pipeline_missing";
        if (this.drawCommandBuffer == null) return "draw_command_buffer_missing";
        if (this.drawCountBuffer == null) return "draw_count_buffer_missing";
        if (this.cmdGenConfigBuffer == null) return "cmdgen_config_buffer_missing";
        if (this.cmdGenBinding2ProbeBuffer == null) return "cmdgen_binding2_probe_buffer_missing";
        if (geometryData.isFreed()) return "geometry_data_freed";
        if (renderList.isFreed()) return "render_list_freed";
        if (visibleCount <= 0) return "visible_count_zero_or_negative";
        if (visibleCount > renderList.getMaxEntryCount()) return "visible_count_exceeds_render_list_capacity";
        if (CMDGEN_CONFIG_SIZE_BYTES != 24) return "cmdgen_config_layout_invalid: sizeBytes=" + CMDGEN_CONFIG_SIZE_BYTES + " expected=24";
        if (DRAW_COMMAND_STRIDE_BYTES != 16) return "draw_command_layout_invalid: strideBytes=" + DRAW_COMMAND_STRIDE_BYTES + " expected=16";
        if (VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE != 32) return "section_metadata_layout_invalid: sizeBytes=" + VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE + " expected=32";
        if ((geometryData.getGeometryCapacityBytes() & 7L) != 0L) return "geometry_capacity_unit_invalid: geometryCapacityBytes=" + geometryData.getGeometryCapacityBytes() + " unit=8_byte_quad";
        long expectedRenderListBytes = Math.multiplyExact(4L, (long) renderList.getMaxEntryCount() + 1L);
        if (renderList.getBuffer().getBufferSize() < expectedRenderListBytes) {
            return "render_list_header_layout_invalid: bufferBytes=" + renderList.getBuffer().getBufferSize() + " expectedAtLeast=" + expectedRenderListBytes;
        }
        if (renderList.getMaxEntryCount() < 1) {
            return "render_list_capacity_missing_entry0: maxEntryCount=" + renderList.getMaxEntryCount();
        }
        if (renderList.getBuffer().getBufferSize() < 2L * Integer.BYTES) {
            return "render_list_capacity_too_small_for_header_plus_entry0: bufferBytes=" + renderList.getBuffer().getBufferSize() + " required=" + (2 * Integer.BYTES);
        }
        if (isolationStage != null && isolationStage.minimumRenderListBytes() > 0 && renderList.getBuffer().getBufferSize() < isolationStage.minimumRenderListBytes()) {
            return "render_list_capacity_too_small_for_stage: stage=" + isolationStage.envName() + " bufferBytes=" + renderList.getBuffer().getBufferSize() + " required=" + isolationStage.minimumRenderListBytes();
        }
        if (geometryData.getMetadataCapacityBytes() != Math.multiplyExact((long) geometryData.getMaxSectionCount(), VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE)) {
            return "metadata_layout_invalid: metadataCapacityBytes=" + geometryData.getMetadataCapacityBytes() + " maxSectionCount=" + geometryData.getMaxSectionCount() + " sectionMetadataSize=" + VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE;
        }
        if (this.drawCommandBuffer.getBufferSize() < Math.multiplyExact((long) visibleCount, DRAW_COMMAND_STRIDE_BYTES)) {
            return "draw_command_capacity_exceeded: visibleCount=" + visibleCount + " bufferBytes=" + this.drawCommandBuffer.getBufferSize();
        }
        if (this.drawCountBuffer.getBufferSize() < Integer.BYTES) {
            return "draw_count_capacity_zero: bufferBytes=" + this.drawCountBuffer.getBufferSize();
        }
        if (this.cmdGenConfigBuffer.getBufferSize() < CMDGEN_CONFIG_SIZE_BYTES) {
            return "cmdgen_config_capacity_too_small: bufferBytes=" + this.cmdGenConfigBuffer.getBufferSize() + " required=" + CMDGEN_CONFIG_SIZE_BYTES;
        }
        if (this.cmdGenBinding2ProbeBuffer.getBufferSize() < Integer.BYTES) {
            return "cmdgen_binding2_probe_capacity_too_small: bufferBytes=" + this.cmdGenBinding2ProbeBuffer.getBufferSize() + " required=" + Integer.BYTES;
        }
        if (noOpCmdgenSmoke || (isolationStage != null && !isolationStage.requiresControlledSection())) {
            return null;
        }
        if (!controlledSmoke.safe()) {
            return "controlled_section_unavailable:" + controlledSmoke.reason();
        }
        int sectionId = controlledSmoke.sectionId();
        if (sectionId < 0 || sectionId >= geometryData.getMaxSectionCount()) {
            return "section_id_bounds: sectionId=" + sectionId + " metadataSectionCapacity=" + geometryData.getMaxSectionCount();
        }
        long metadataEndBytes = Math.multiplyExact((long) sectionId + 1L, VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE);
        if (metadataEndBytes > geometryData.getMetadataCapacityBytes()) {
            return "metadata_buffer_bounds: sectionId=" + sectionId + " metadataEndBytes=" + metadataEndBytes + " metadataCapacityBytes=" + geometryData.getMetadataCapacityBytes();
        }
        long opaqueQuadStart = Integer.toUnsignedLong(controlledSmoke.quadStart());
        long opaqueQuadCount = controlledSmoke.quadCount();
        if (opaqueQuadCount <= 0L) {
            return "quad_count_zero";
        }
        long requiredGeometryBytes = Math.addExact(Math.multiplyExact(opaqueQuadStart, 8L), Math.multiplyExact(opaqueQuadCount, 8L));
        if (requiredGeometryBytes > geometryData.getUsedGeometryBytes() || requiredGeometryBytes > geometryData.getGeometryCapacityBytes()) {
            return "quad_bounds_invalid: sectionId=" + sectionId + " opaqueQuadStart=" + opaqueQuadStart + " opaqueQuadCount=" + opaqueQuadCount + " requiredBytes=" + requiredGeometryBytes + " usedGeometryBytes=" + geometryData.getUsedGeometryBytes() + " geometryCapacityBytes=" + geometryData.getGeometryCapacityBytes();
        }
        return null;
    }

    private void barrierTransferToCompute(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer transferToCompute = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, transferToCompute, null, null);
        }
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
            VkMemoryBarrier.Buffer shaderToTransfer = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, shaderToTransfer, null, null);

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0L).dstOffset(0L).size((long) sampledCommandCount * DRAW_COMMAND_STRIDE_BYTES);
            VK10.vkCmdCopyBuffer(commandBuffer, this.drawCommandBuffer.getId(), this.drawCommandDebugReadbackBuffer.getId(), copyRegion);

            VkMemoryBarrier.Buffer transferToHost = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, transferToHost, null, null);
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
            boolean valid = vertexCount > 0 && (vertexCount & 3) == 0 && instanceCount == 1 && firstVertex >= 0 && (firstVertex & 3) == 0 && firstInstance == i && firstInstance < visibleCount;
            if (valid && firstVertex >= 0 && geometryBufferBytes > 0L) {
                long maxVertexExclusive = (geometryBufferBytes >>> 3) * 4L;
                long firstVertexUnsigned = Integer.toUnsignedLong(firstVertex);
                valid = firstVertexUnsigned < maxVertexExclusive
                        && firstVertexUnsigned + Integer.toUnsignedLong(vertexCount) <= maxVertexExclusive;
            }
            if (!valid) invalid++;
            if ((vertexCount & 3) == 0 && vertexCount >= 0) quadCount += (vertexCount >>> 2);
        }
        return new DrawCommandDebugSample(sampledCommandCount, invalid, quadCount);
    }

    private record DrawCommandDebugSample(int sampledCommandCount, int invalidSampledCommandCount, long sampledQuadCount) {}


    private void ensureCommandGenMinimalTinySsboReadProbePipeline() {
        if (this.commandGenMinimalTinySsboReadProbePipeline != null) return;
        if (this.cmdGenMinimalTinySsboReadProbeBuffer == null) {
            this.cmdGenMinimalTinySsboReadProbeBuffer = new Buffer("voxy_vulkanberyl_cmdgen_minimal_tiny_ssbo_read_probe",
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    MemoryTypes.GPU_MEM);
            this.cmdGenMinimalTinySsboReadProbeBuffer.createBuffer(Integer.BYTES);
            VulkanBerylDebugLog.once("cmdgen-minimal-tiny-ssbo-read-probe-buffer-created", "cmdgen minimal tiny SSBO read probe buffer created: bufferId="
                    + this.cmdGenMinimalTinySsboReadProbeBuffer.getId()
                    + ", capacityBytes=" + this.cmdGenMinimalTinySsboReadProbeBuffer.getBufferSize()
                    + ", usage=STORAGE|TRANSFER_DST");
        }
        this.commandGenMinimalTinySsboReadProbePipeline = createSingleSsboReadProbePipeline(CMDGEN_MINIMAL_SSBO_READ_SHADER_RESOURCE, CMDGEN_MINIMAL_SSBO_READ_SHADER_NAME, 0, this.cmdGenMinimalTinySsboReadProbeBuffer, "CmdGenMinimalTinySsboReadProbe");
    }

    private void ensureCommandGenMinimalRenderListReadProbePipeline(Buffer renderListBuffer) {
        if (this.commandGenMinimalRenderListReadProbePipeline != null) return;
        this.commandGenMinimalRenderListReadProbePipeline = createSingleSsboReadProbePipeline(CMDGEN_MINIMAL_SSBO_READ_SHADER_RESOURCE, CMDGEN_MINIMAL_SSBO_READ_SHADER_NAME, CMDGEN_RENDER_LIST_BINDING, renderListBuffer, "CmdGenMinimalRenderListReadProbe");
    }

    private void ensureCommandGenMinimalConfigReadProbePipeline() {
        if (this.commandGenMinimalConfigReadProbePipeline != null) return;
        ensureCmdGenConfigBuffer();
        ensureCommandGenMinimalConfigReadProbePlaceholderBuffer();
        this.commandGenMinimalConfigReadProbePipeline = createDenseConfigReadProbePipeline();
    }

    private void ensureCommandGenMinimalConfigBinding0ReadProbePipeline() {
        if (this.commandGenMinimalConfigBinding0ReadProbePipeline != null) return;
        ensureCmdGenConfigBuffer();
        this.commandGenMinimalConfigBinding0ReadProbePipeline = createSingleSsboReadProbePipeline(CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_RESOURCE, CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_NAME, CMDGEN_RENDER_LIST_BINDING, this.cmdGenConfigBuffer, "CmdGenMinimalConfigBinding0ReadProbe");
    }

    private void ensureCommandGenMinimalConfigReadProbePlaceholderBuffer() {
        if (this.cmdGenMinimalConfigReadProbePlaceholderBuffer != null) return;
        this.cmdGenMinimalConfigReadProbePlaceholderBuffer = new Buffer("voxy_vulkanberyl_cmdgen_minimal_config_read_probe_placeholder",
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                MemoryTypes.GPU_MEM);
        this.cmdGenMinimalConfigReadProbePlaceholderBuffer.createBuffer(Integer.BYTES);
        VulkanBerylDebugLog.once("cmdgen-minimal-config-read-probe-placeholder-created", "cmdgen minimal config read probe placeholder buffer created: bufferId="
                + this.cmdGenMinimalConfigReadProbePlaceholderBuffer.getId()
                + ", capacityBytes=" + this.cmdGenMinimalConfigReadProbePlaceholderBuffer.getBufferSize()
                + ", usage=STORAGE|TRANSFER_DST");
    }

    private void ensureCommandGenHardcodedBinding0ReadPipeline(Buffer renderListBuffer) {
        if (this.commandGenHardcodedBinding0ReadPipeline != null) return;
        this.commandGenHardcodedBinding0ReadPipeline = createSingleSsboReadProbePipeline(CMDGEN_HARDCODED_BINDING0_READ_SHADER_RESOURCE, CMDGEN_HARDCODED_BINDING0_READ_SHADER_NAME, CMDGEN_RENDER_LIST_BINDING, renderListBuffer, "CmdGenHardcodedBinding0ReadProbe");
    }

    private ComputePipeline createDenseConfigReadProbePipeline() {
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        List<UBO> descriptors = List.of(
                createManualDescriptor(0, computeStage, this.cmdGenMinimalConfigReadProbePlaceholderBuffer, "CmdGenMinimalConfigReadProbePlaceholder0"),
                createManualDescriptor(1, computeStage, this.cmdGenMinimalConfigReadProbePlaceholderBuffer, "CmdGenMinimalConfigReadProbePlaceholder1"),
                createManualDescriptor(2, computeStage, this.cmdGenMinimalConfigReadProbePlaceholderBuffer, "CmdGenMinimalConfigReadProbePlaceholder2"),
                createManualDescriptor(3, computeStage, this.cmdGenMinimalConfigReadProbePlaceholderBuffer, "CmdGenMinimalConfigReadProbePlaceholder3"),
                createManualDescriptor(4, computeStage, this.cmdGenMinimalConfigReadProbePlaceholderBuffer, "CmdGenMinimalConfigReadProbePlaceholder4"),
                createManualDescriptor(CMDGEN_CONFIG_BINDING, computeStage, this.cmdGenConfigBuffer, "CmdGenMinimalConfigReadProbe")
        );
        ComputePipeline.Builder builder = new ComputePipeline.Builder(CMDGEN_MINIMAL_CONFIG_READ_SHADER_RESOURCE);
        builder.setUniforms(descriptors, List.of());
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(CMDGEN_MINIMAL_CONFIG_READ_SHADER_RESOURCE);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen config read probe shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            builder.compileShader(preprocessedShader.rootUrl(), CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section cmdgen config read probe shader (compute=" + CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME + ")", e);
        }
        ComputePipeline pipeline;
        try {
            pipeline = builder.createPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section cmdgen config read probe compute pipeline (compute=" + CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME + ")", e);
        }
        if (pipeline == null || pipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create section cmdgen config read probe compute pipeline: " + CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME);
        }
        bindConfigReadProbePlaceholders(pipeline);
        VulkanBerylDebugLog.once("cmdgen-config-read-probe-pipeline-created", "cmdgen config read probe pipeline created: label=CmdGenMinimalConfigReadProbe"
                + ", shader=" + CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME
                + ", descriptorBinding=" + CMDGEN_CONFIG_BINDING
                + ", descriptorMode=manual_dense_placeholders_0_to_5");
        return pipeline;
    }

    private void bindConfigReadProbePlaceholders(ComputePipeline pipeline) {
        for (int binding = 0; binding < CMDGEN_CONFIG_BINDING; binding++) {
            final int targetBinding = binding;
            UBO ubo = pipeline.getUBO(candidate -> candidate.binding == targetBinding);
            if (ubo == null) throw new IllegalStateException("cmdgen config read probe placeholder descriptor missing: binding=" + binding);
            ubo.getBufferSlice().set(this.cmdGenMinimalConfigReadProbePlaceholderBuffer, 0L, Integer.BYTES);
        }
    }

    private ComputePipeline createSingleSsboReadProbePipeline(String shaderResource, String shaderName, int descriptorBinding, Buffer descriptorBuffer, String label) {
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        ComputePipeline.Builder builder = new ComputePipeline.Builder(shaderResource);
        builder.setUniforms(List.of(createManualDescriptor(descriptorBinding, computeStage, descriptorBuffer, label)), List.of());
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(shaderResource);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen single-SSBO read probe shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            builder.compileShader(preprocessedShader.rootUrl(), shaderName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section cmdgen single-SSBO read probe shader (compute=" + shaderName + ")", e);
        }
        ComputePipeline pipeline;
        try {
            pipeline = builder.createPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section cmdgen single-SSBO read probe compute pipeline (compute=" + shaderName + ")", e);
        }
        if (pipeline == null || pipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create section cmdgen single-SSBO read probe compute pipeline: " + shaderName);
        }
        VulkanBerylDebugLog.once("cmdgen-single-ssbo-read-probe-pipeline-created:" + label, "cmdgen single-SSBO read probe pipeline created: label=" + label
                + ", shader=" + shaderName
                + ", descriptorBinding=" + descriptorBinding
                + ", descriptorMode=manual_one_binding");
        return pipeline;
    }

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
        validateCmdgenLayoutContract(config);
        List<UBO> cmdGenDescriptors = createManualCmdGenDescriptors();
        builder.setUniforms(cmdGenDescriptors, List.of());
        List<Integer> cmdgenBindings = cmdGenDescriptors.stream().map(ubo -> ubo.binding).sorted().toList();
        boolean denseFromZero = !cmdgenBindings.isEmpty();
        for (int i = 0; i < cmdgenBindings.size(); i++) {
            if (cmdgenBindings.get(i) != i) {
                denseFromZero = false;
                break;
            }
        }
        int minBinding = cmdgenBindings.isEmpty() ? -1 : cmdgenBindings.get(0);
        int maxBinding = cmdgenBindings.isEmpty() ? -1 : cmdgenBindings.get(cmdgenBindings.size() - 1);
        boolean binding2ProbePresent = cmdgenBindings.contains(CMDGEN_BINDING2_PROBE_BINDING);
        VulkanBerylDebugLog.verboseOnce("section-cmdgen-descriptor-layout", "Section cmdgen descriptor layout: descriptorMode=manual_dense, count=" + cmdgenBindings.size()
                + ", bindings=" + cmdgenBindings
                + ", minBinding=" + minBinding
                + ", maxBinding=" + maxBinding
                + ", denseFromZero=" + denseFromZero
                + ", binding2ProbePresent=" + binding2ProbePresent);
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(CMDGEN_SHADER_RESOURCE);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            VulkanBerylDebugLog.verboseOnce("section-cmdgen-compile-input-verified", "compileShader input verified: shader=" + preprocessedShader.shaderName()
                    + ", tempShaderRelativePath=" + preprocessedShader.tempShaderRelativePath()
                    + ", file=" + preprocessedShader.shaderPath()
                    + ", bytes=" + preprocessedShader.outputBytes());
            try {
                builder.compileShader(preprocessedShader.rootUrl(), preprocessedShader.shaderName());
            } catch (Exception e) {
                logCmdGenCompileFailureDiagnostics(preprocessedShader);
                throw e;
            }
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
        VulkanBerylDebugLog.once("cmdgen-pipeline-created", "cmdgen pipeline created");
    }

    private void ensureCommandGenNoopPipeline() {
        if (this.commandGenNoopPipeline != null) return;
        ComputePipeline.Builder builder = new ComputePipeline.Builder(CMDGEN_NOOP_SHADER_RESOURCE);
        if (CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE) {
            builder.setUniforms(createManualCmdGenDescriptors(), List.of());
        } else {
            builder.setUniforms(List.of(), List.of());
        }
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(CMDGEN_NOOP_SHADER_RESOURCE);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen noop shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            builder.compileShader(preprocessedShader.rootUrl(), CMDGEN_NOOP_SHADER_NAME);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section cmdgen noop shader (compute=" + CMDGEN_NOOP_SHADER_NAME + ")", e);
        }
        try {
            this.commandGenNoopPipeline = builder.createPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section cmdgen noop compute pipeline", e);
        }
        if (this.commandGenNoopPipeline == null || this.commandGenNoopPipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create section cmdgen noop compute pipeline");
        }
        VulkanBerylDebugLog.once("cmdgen-noop-pipeline-created", "cmdgen noop pipeline created: descriptorBindProbe=" + CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE);
    }



    private static void logCmdGenCompileFailureDiagnostics(VulkanBerylShaderImportPreprocessor.PreparedShader shader) {
        final int maxLines = 120;
        try {
            List<String> lines = Files.readAllLines(shader.shaderPath(), StandardCharsets.UTF_8);
            int lineCount = Math.min(maxLines, lines.size());
            StringBuilder preview = new StringBuilder();
            for (int i = 0; i < lineCount; i++) {
                preview.append(String.format("%4d | %s%n", i + 1, lines.get(i)));
            }
            VulkanBerylDebugLog.error("Cmdgen compile failed. Preprocessed shader path=" + shader.shaderPath()
                    + ", showing first " + lineCount + " lines:\n" + preview);
        } catch (Exception readError) {
            VulkanBerylDebugLog.error("Cmdgen compile failed, and preprocessed shader preview could not be read: path="
                    + shader.shaderPath() + ", error=" + readError);
        }
    }

    private static void validateCmdgenLayoutContract(JsonObject config) {
        java.util.Map<String, Integer> expectedBindings = java.util.Map.of(
                "RenderListBuffer", CMDGEN_RENDER_LIST_BINDING,
                "MetadataBuffer", CMDGEN_METADATA_BINDING,
                "Binding2ProbeBuffer", CMDGEN_BINDING2_PROBE_BINDING,
                "DrawCommandsBuffer", CMDGEN_DRAW_COMMAND_BINDING,
                "DrawCountBuffer", CMDGEN_DRAW_COUNT_BINDING,
                "CmdGenConfigBuffer", CMDGEN_CONFIG_BINDING
        );
        java.util.Map<Integer, String> expectedShaderDeclarations = java.util.Map.of(
                CMDGEN_RENDER_LIST_BINDING, "RenderListBuffer",
                CMDGEN_METADATA_BINDING, "MetadataBuffer",
                CMDGEN_BINDING2_PROBE_BINDING, "Binding2ProbeBuffer",
                CMDGEN_DRAW_COMMAND_BINDING, "DrawCommandsBuffer",
                CMDGEN_DRAW_COUNT_BINDING, "DrawCountBuffer",
                CMDGEN_CONFIG_BINDING, "CmdGenConfigBuffer"
        );
        java.util.Set<Integer> jsonBindings = new java.util.HashSet<>();
        for (var element : config.getAsJsonArray("UBOs")) {
            JsonObject ubo = element.getAsJsonObject();
            String name = ubo.get("name").getAsString();
            int binding = ubo.get("binding").getAsInt();
            String type = ubo.get("type").getAsString();
            Integer expectedBinding = expectedBindings.get(name);
            if (expectedBinding == null) {
                throw new IllegalStateException("Unexpected cmdgen descriptor in " + CMDGEN_SHADER_CONFIG + ": name=" + name + ", binding=" + binding);
            }
            if (binding != expectedBinding) {
                throw new IllegalStateException("Cmdgen descriptor binding mismatch for " + name + ": json=" + binding + ", expected=" + expectedBinding);
            }
            if (!"storageBuffer".equals(type)) {
                throw new IllegalStateException("Cmdgen descriptor type mismatch for " + name + ": json=" + type + ", expected=storageBuffer");
            }
            jsonBindings.add(binding);
        }
        if (jsonBindings.size() != expectedBindings.size()) {
            throw new IllegalStateException("Cmdgen descriptor layout mismatch: jsonBindings=" + jsonBindings + ", expected=" + expectedBindings);
        }
        String shaderSource;
        try (var stream = VulkanBerylSectionDrawPipeline.class.getResourceAsStream("/assets/voxy/shaders/vulkanberyl/section/cmdgen.comp")) {
            if (stream == null) {
                throw new IllegalStateException("Missing cmdgen shader resource for layout validation: " + CMDGEN_SHADER_RESOURCE);
            }
            shaderSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read cmdgen shader for layout validation: " + CMDGEN_SHADER_RESOURCE, e);
        }
        for (var entry : expectedShaderDeclarations.entrySet()) {
            String declaration = "layout(binding = " + entry.getKey() + ", std430)";
            if (!shaderSource.contains(declaration) || !shaderSource.contains("buffer " + entry.getValue())) {
                throw new IllegalStateException("Cmdgen shader reflection mismatch: expected " + entry.getValue() + " at binding=" + entry.getKey());
            }
        }
        VulkanBerylDebugLog.verboseOnce("section-cmdgen-layout-contract", "Section cmdgen layout contract validated: renderListBinding=" + CMDGEN_RENDER_LIST_BINDING
                + ", metadataBinding=" + CMDGEN_METADATA_BINDING
                + ", binding2ProbeBinding=" + CMDGEN_BINDING2_PROBE_BINDING
                + ", bindings=" + jsonBindings);
    }

    private List<UBO> createManualCmdGenDescriptors() {
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        return List.of(
                createManualDescriptor(CMDGEN_RENDER_LIST_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == RENDER_LIST_BINDING).getBufferSlice().getBuffer(), "CmdGenRenderList"),
                createManualDescriptor(CMDGEN_METADATA_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == METADATA_BINDING).getBufferSlice().getBuffer(), "CmdGenMetadata"),
                createManualDescriptor(CMDGEN_BINDING2_PROBE_BINDING, computeStage, this.cmdGenBinding2ProbeBuffer, "CmdGenBinding2Probe"),
                createManualDescriptor(CMDGEN_DRAW_COMMAND_BINDING, computeStage, this.drawCommandBuffer, "CmdGenDrawCommand"),
                createManualDescriptor(CMDGEN_DRAW_COUNT_BINDING, computeStage, this.drawCountBuffer, "CmdGenDrawCount"),
                createManualDescriptor(CMDGEN_CONFIG_BINDING, computeStage, this.cmdGenConfigBuffer, "CmdGenConfig")
        );
    }

    private static ManualUBO createManualDescriptor(int binding, int computeStage, Buffer buffer, String label) {
        int requestedSize = descriptorSizeBytes(binding, label, buffer);
        int structSizeInts = Math.max(1, (requestedSize + Integer.BYTES - 1) / Integer.BYTES);
        VulkanBerylDebugLog.verboseOnce("manual-descriptor:" + label + ":" + binding, "Creating manual descriptor binding=" + binding + ", label=" + label + ", requestedBytes=" + requestedSize + ", descriptorClass=ManualUBO, manualStructInts=" + structSizeInts);
        return new ManualUBO(binding, computeStage, structSizeInts);
    }

    private static int descriptorSizeBytes(int binding, String label, Buffer buffer) {
        if (buffer == null) throw new IllegalStateException("Descriptor buffer is null for binding " + binding + " (" + label + ")");
        long size = buffer.getBufferSize();
        if (size <= 0L || size > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, size);
        }
        return (int) size;
    }

    private void bindStorageBinding(int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, bufferSize);
        }

        UBO ubo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) {
            throw new IllegalStateException("Section draw descriptor missing: name=" + label + ", binding=" + binding + ", config=" + DRAW_SHADER_CONFIG);
        }
        int rangeBytes = (int) bufferSize;
        VulkanBerylDebugLog.trace("binding-draw-descriptor:" + binding + ":" + label, "Binding draw descriptor: binding=" + binding + ", label=" + label + ", bufferBytes=" + bufferSize + ", finalRangeBytes=" + rangeBytes);
        ubo.getBufferSlice().set(buffer, 0L, rangeBytes);
    }

    private void bindComputeStorageBinding(int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, bufferSize);
        }
        UBO ubo = this.commandGenPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) throw new IllegalStateException("Section cmdgen descriptor missing: name=" + label + ", binding=" + binding + ", config=" + CMDGEN_SHADER_CONFIG);
        if (binding == CMDGEN_CONFIG_BINDING && buffer == this.cmdGenConfigBuffer && bufferSize != CMDGEN_CONFIG_SIZE_BYTES) {
            throw new IllegalStateException("cmdGenConfigBuffer descriptor range mismatch: binding=" + binding + ", bufferBytes=" + bufferSize + ", expectedRangeBytes=" + CMDGEN_CONFIG_SIZE_BYTES);
        }
        int rangeBytes = (int) bufferSize;
        VulkanBerylDebugLog.trace("binding-cmdgen-descriptor:" + binding + ":" + label, "Binding cmdgen descriptor: binding=" + binding + ", label=" + label + ", bufferBytes=" + bufferSize + ", finalRangeBytes=" + rangeBytes);
        ubo.getBufferSlice().set(buffer, 0L, rangeBytes);
    }

    private void updateAndBindCmdGenConfigBuffer(VulkanBerylSectionGeometryData geometryData, int renderListCapacity, int flags) {
        long scratch = MemoryUtil.nmemAlloc(CMDGEN_CONFIG_SIZE_BYTES);
        try {
            ensureCmdGenConfigBuffer();
            this.lastCmdGenConfigMetadataSectionCapacity = geometryData.getMaxSectionCount();
            this.lastCmdGenConfigRenderListCapacity = renderListCapacity;
            this.lastCmdGenConfigGeometryCapacityQuads = Math.toIntExact(geometryData.getGeometryCapacityBytes() / 8L);
            this.lastCmdGenConfigDrawCommandCapacity = this.drawCommandCapacity;
            this.lastCmdGenConfigDrawCountCapacityWords = (int) (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getBufferSize() / Integer.BYTES);
            this.lastCmdGenConfigFlags = flags;
            MemoryUtil.memPutInt(scratch, this.lastCmdGenConfigMetadataSectionCapacity);
            MemoryUtil.memPutInt(scratch + 4L, this.lastCmdGenConfigRenderListCapacity);
            MemoryUtil.memPutInt(scratch + 8L, this.lastCmdGenConfigGeometryCapacityQuads);
            MemoryUtil.memPutInt(scratch + 12L, this.lastCmdGenConfigDrawCommandCapacity);
            MemoryUtil.memPutInt(scratch + 16L, this.lastCmdGenConfigDrawCountCapacityWords);
            MemoryUtil.memPutInt(scratch + 20L, this.lastCmdGenConfigFlags);
            VulkanBerylGeometryUploader.get().upload(this.cmdGenConfigBuffer, 0L, scratch, CMDGEN_CONFIG_SIZE_BYTES);
            this.cmdGenConfigUploaded = true;
            logCmdGenConfigBufferState("uploaded");
            VulkanBerylGeometryUploader.get().flush();
        } finally {
            MemoryUtil.nmemFree(scratch);
        }
        bindComputeStorageBinding(CMDGEN_CONFIG_BINDING, this.cmdGenConfigBuffer, "cmdGenConfigBuffer");
    }

    private void logCmdGenConfigBufferState(String stage) {
        if (this.cmdGenConfigBuffer == null) {
            VulkanBerylDebugLog.always("cmdGenConfigBuffer state: stage=" + stage + ", buffer=null");
            return;
        }
        VulkanBerylDebugLog.always("cmdGenConfigBuffer state: stage=" + stage
                + ", bufferId=" + this.cmdGenConfigBuffer.getId()
                + ", capacityBytes=" + this.cmdGenConfigBuffer.getBufferSize()
                + ", usage=" + cmdGenConfigUsageString()
                + ", requiredRangeBytes=" + CMDGEN_CONFIG_SIZE_BYTES
                + ", uploaded=" + this.cmdGenConfigUploaded
                + ", words=[metadataSectionCapacity=" + this.lastCmdGenConfigMetadataSectionCapacity
                + ", renderListCapacity=" + this.lastCmdGenConfigRenderListCapacity
                + ", geometryCapacityQuads=" + this.lastCmdGenConfigGeometryCapacityQuads
                + ", drawCommandCapacity=" + this.lastCmdGenConfigDrawCommandCapacity
                + ", drawCountCapacityWords=" + this.lastCmdGenConfigDrawCountCapacityWords
                + ", flags=" + this.lastCmdGenConfigFlags + "]");
    }

    private static String cmdGenConfigUsageString() {
        return "STORAGE|TRANSFER_DST(" + CMDGEN_CONFIG_USAGE_FLAGS + ")";
    }

    private static IllegalStateException descriptorRangeException(int binding, String label, long sizeBytes) {
        return new IllegalStateException("Descriptor range unsupported for binding=" + binding
                + ", label=" + label
                + ", bufferSizeBytes=" + sizeBytes
                + ", maxSupportedBytes=" + VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES
                + ", strategy=BufferSlice.set(Buffer,long,int)"
                + ", fixHint=cap Vulkan/Beryl geometry capacity before buffer creation");
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
