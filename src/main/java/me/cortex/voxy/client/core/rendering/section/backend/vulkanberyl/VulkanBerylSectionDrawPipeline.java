package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.beryl.render.ComputePipeline;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl.VulkanBerylCmdgenDiagnostics.*;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import net.vulkanmod.vulkan.memory.MemoryTypes;

public final class VulkanBerylSectionDrawPipeline {
    public static final String DRAW_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw.vsh";
    public static final String DRAW_FRAGMENT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw.fsh";
    public static final String DRAW_DEBUG_FRAGMENT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/draw_debug.fsh";
    private static final String DRAW_SHADER_NAME = "vulkanberyl/section/draw";
    private static final String DRAW_DEBUG_FRAGMENT_SHADER_NAME = "vulkanberyl/section/draw_debug";
    private static final String DRAW_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/section/draw.json";
    private static final int SCENE_UNIFORM_SIZE_BYTES = 240;
    private static final int SCENE_UNIFORM_REAL_LOD_PROBE_DATA_OFFSET_BYTES = 96;
    private static final int SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_CLIP_OFFSET_BYTES = 112;
    private static final int SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_WORLD_OFFSET_BYTES = 176;
    private static final int REAL_LOD_GPU_DECODE_PARITY_BINDING = 9;
    private static final int REAL_LOD_GPU_DECODE_PARITY_WORDS = 50;
    private static final int REAL_LOD_GPU_DECODE_PARITY_BYTES = REAL_LOD_GPU_DECODE_PARITY_WORDS * Integer.BYTES;
    private static final int REAL_LOD_GPU_DECODE_PARITY_MAGIC = 0x47504450;

    static {
        VulkanBerylCmdgenDiagnostics.ensureLoaded();
    }


    private GraphicsPipeline graphicsPipeline;
    private GraphicsPipeline translucentGraphicsPipeline;
    private DrawPass activeDrawPass = DrawPass.OPAQUE;
    private static final ThreadLocal<String> SECTION_DRAW_PASS_CONTEXT = ThreadLocal.withInitial(() -> "unknown");
    private static long nextGraphicsPipelineGeneration;

    private enum DrawPass {
        OPAQUE,
        TRANSLUCENT
    }

    private ComputePipeline commandGenPipeline;
    private ComputePipeline commandGenNoopPipeline;
    private ComputePipeline commandGenMinimalTinySsboReadProbePipeline;
    private ComputePipeline commandGenMinimalRenderListReadProbePipeline;
    private ComputePipeline commandGenMinimalConfigReadProbePipeline;
    private ComputePipeline commandGenMinimalConfigBinding0ReadProbePipeline;
    private ComputePipeline commandGenHardcodedBinding0ReadPipeline;
    private ComputePipeline commandGenFullLayoutNoopProbePipeline;
    private ComputePipeline commandGenFullLayoutHardcodedBinding0ReadProbePipeline;
    private ComputePipeline commandGenFullLayoutConfigBinding0ReadProbePipeline;
    private ComputePipeline commandGenNoImportProbePipeline;
    private ComputePipeline commandGenNoImportReadMetadata0OnlyProbePipeline;
    private ComputePipeline commandGenNoImportRawMetadataUvec4Binding1ProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1UintReadProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1UintReadConstProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1TinyUintReadNoConfigProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1AndBinding2UintReadProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding2ProbeBufferUintReadConstProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbePipeline;
    private ComputePipeline commandGenSingleBinding1UintReadProbePipeline;
    private ComputePipeline commandGenBinding0UintReadProbePipeline;
    private ComputePipeline commandGenFullLayoutBinding2UintReadProbePipeline;
    private ComputePipeline commandGenRawMetadataUvec4Binding0ProbePipeline;
    private ComputePipeline commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline;
    private ComputePipeline commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline;
    private ComputePipeline commandGenNoImportAtomicDrawcountOnlyProbePipeline;
    private ComputePipeline commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline;
    private ComputePipeline commandGenDenseLayoutNoopProbePipeline;
    private ComputePipeline commandGenReadRenderlistMetadataNoWritePipeline;
    private Buffer drawCommandBuffer;
    private Buffer controlledSmokeKnownCommandBuffer;
    private int controlledSmokeKnownCommandBufferUsageFlags;
    private Buffer drawCountBuffer;
    private Buffer sceneUniformBuffer;
    private Buffer cmdgenDrawCountScratchBuffer;
    private Buffer drawCommandDebugReadbackBuffer;
    private Buffer drawCountDebugReadbackBuffer;
    private Buffer geometryQuadDebugReadbackBuffer;
    private Buffer realLodGpuDecodeParityBuffer;
    private Buffer realLodGpuDecodeParityReadbackBuffer;
    private Buffer cmdGenConfigBuffer;
    private Buffer cmdGenUnusedBinding2Buffer;
    private Buffer cmdGenBinding2ProbeBuffer;
    private Buffer cmdGenRenderListAltProbeBuffer;
    private Buffer cmdGenMinimalTinySsboReadProbeBuffer;
    private Buffer cmdGenTinyMetadataProbeBuffer;
    private Buffer cmdGenMinimalConfigReadProbePlaceholderBuffer;
    private int lastCmdGenConfigMetadataSectionCapacity;
    private int lastCmdGenConfigRenderListCapacity;
    private int lastCmdGenConfigGeometryCapacityQuads;
    private int lastCmdGenConfigDrawCommandCapacity;
    private int lastCmdGenConfigDrawCountCapacityWords;
    private int lastCmdGenConfigFlags;
    private boolean cmdGenConfigUploaded;
    private boolean drawCountClearedThisFrame;
    private int drawCommandBufferUsageFlags;
    private int drawCountBufferUsageFlags;
    private int cmdgenDrawCountScratchBufferUsageFlags;
    private boolean drawCountClearCommandRecordedThisFrame;
    private boolean drawCountUsedRealClearPathThisFrame;
    private boolean drawCountUsedScratchClearPathThisFrame;
    private int drawCommandDebugReadbackBufferUsageFlags;
    private int drawCountDebugReadbackBufferUsageFlags;
    private int geometryQuadDebugReadbackBufferUsageFlags;
    private boolean geometryDiagnosticReadbackScheduled;
    private boolean geometryDiagnosticReadbackCompleted;
    private long geometryDiagnosticPendingQuadIndex = -1L;
    private long geometryDiagnosticPendingByteOffset = -1L;
    private long geometryDiagnosticPendingFrameId = -1L;
    private int geometryDiagnosticPendingRendererFrameSlot = -1;
    private long geometryDiagnosticPendingCommandBufferAddress;
    private long geometryDiagnosticPendingSourceBufferId;
    private long geometryDiagnosticPendingGeometrySyncGeneration;
    private DrawPass geometryDiagnosticPendingDrawPass = DrawPass.OPAQUE;
    private boolean geometryDiagnosticReadbackCopyRecorded;
    private long geometryDiagnosticCompletedQuadIndex = -1L;
    private long geometryDiagnosticCompletedByteOffset = -1L;
    private long geometryDiagnosticCompletedFrameId = -1L;
    private int geometryDiagnosticCompletedRendererFrameSlot = -1;
    private long geometryDiagnosticCompletedCommandBufferAddress;
    private long geometryDiagnosticCompletedSourceBufferId;
    private long geometryDiagnosticCompletedGeometrySyncGeneration;
    private DrawPass geometryDiagnosticCompletedDrawPass = DrawPass.OPAQUE;
    private long geometryDiagnosticCompletedRaw;
    private String geometryDiagnosticRejectReason = "not_scheduled";
    private boolean geometryDiagnosticStaleCleared;
    private String geometryDiagnosticStaleClearReason = "none";
    private boolean geometryDiagnosticScheduledAfterStaleClear;
    private int drawCommandCapacity;
    private int pendingDebugSampleCommandCount;
    private int pendingDebugSampleVisibleCount;
    private long pendingDebugSampleGeometryBufferBytes;
    private long pendingDebugSampleFrameId = -1L;
    private int pendingDebugSampleRendererFrameSlot = -1;
    private long pendingDebugSampleRecordedCommandBufferAddress;
    private long pendingDebugSampleSourceBufferId;
    private DrawPass pendingDebugSampleDrawPass = DrawPass.OPAQUE;
    private CmdgenCommandSnapshot pendingDebugSampleSnapshot = CmdgenCommandSnapshot.unavailable();
    private long completedDebugSampleSourceBufferId;
    private long completedDebugSampleFrameId = -1L;
    private DrawPass completedDebugSampleDrawPass = DrawPass.OPAQUE;
    private CmdgenCommandSnapshot completedDebugSampleSnapshot = CmdgenCommandSnapshot.unavailable();
    private String pendingDebugSampleCompletionStrategy = "unknown";
    private boolean pendingDebugSampleGpuCompletionKnown;
    private boolean debugSamplePending;
    private boolean cmdgenSampleScheduled;
    private String cmdgenSampleScheduleReason = "not_requested";
    private DrawCommandDebugSample lastCompletedDebugSample = DrawCommandDebugSample.empty();
    private DrawCommandDebugSample lastCompletedOpaqueDebugSample = DrawCommandDebugSample.empty();
    private DrawCommandDebugSample lastCompletedTranslucentDebugSample = DrawCommandDebugSample.empty();
    private long completedOpaqueDebugSampleSourceBufferId;
    private long completedTranslucentDebugSampleSourceBufferId;
    private long completedOpaqueDebugSampleFrameId = -1L;
    private long completedTranslucentDebugSampleFrameId = -1L;
    private CmdgenCommandSnapshot completedOpaqueDebugSampleSnapshot = CmdgenCommandSnapshot.unavailable();
    private CmdgenCommandSnapshot completedTranslucentDebugSampleSnapshot = CmdgenCommandSnapshot.unavailable();
    private DrawPass lastObservedCompletedDebugSampleDrawPass;
    private String lastObservedCompletedDebugSampleRejectReason = "none";
    private boolean controlledSmokeCommandReadbackScheduled;
    private String controlledSmokeCommandReadbackScheduleReason = "not_requested";
    private boolean controlledSmokeCommandReadbackAlreadyPending;
    private boolean controlledSmokeCommandReadbackScheduleSkipped;
    private String controlledSmokeCommandReadbackSkipReason = "none";
    private int controlledSmokeCommandReadbackFrameId = -1;
    private int controlledSmokeCommandReadbackCompletedFrameId = -1;
    private int controlledSmokeCommandReadbackRendererFrameSlot = -1;
    private long controlledSmokeCommandReadbackRecordedCommandBufferAddress;
    private String controlledSmokeCommandReadbackCompletionStrategy = "unknown";
    private boolean controlledSmokeCommandReadbackCompleted;
    private boolean controlledSmokeCommandReadbackGpuCompletionKnown;
    private long controlledSmokeCommandGeneration;
    private long controlledSmokeCommandUploadGeneration = -1L;
    private int controlledSmokeCommandUploadFrameId = -1;
    private long controlledSmokeCommandUploadExpectedVertexCount = -1L;
    private int controlledSmokeCommandUploadExpectedInstanceCount = -1;
    private long controlledSmokeCommandUploadExpectedFirstVertex = -1L;
    private int controlledSmokeCommandUploadExpectedFirstInstance = -1;
    private boolean controlledSmokeKnownCommandUploadQueued;
    private boolean controlledSmokeKnownCommandUploadFlushed;
    private String controlledSmokeKnownCommandUploadCompletionStrategy = "unknown";
    private boolean controlledSmokeKnownCommandUploadSkipped;
    private String controlledSmokeKnownCommandUploadSkipReason = "none";
    private boolean controlledSmokeKnownCommandUploadTupleChanged;
    private String controlledSmokeCommandValidationState = "not_applicable";
    private boolean controlledSmokeCommandCanSubmit = true;
    private String controlledSmokeCommandDrawSubmitReason = "submitted";
    private long controlledSmokeKnownCommandReadbackAfterUploadGeneration = -1L;
    private long controlledSmokeCommandReadbackScheduledGeneration = -1L;
    private int controlledSmokeCommandReadbackScheduledSectionId = -1;
    private long controlledSmokeCommandReadbackScheduledExpectedVertexCount = -1L;
    private int controlledSmokeCommandReadbackScheduledExpectedInstanceCount = -1;
    private long controlledSmokeCommandReadbackScheduledExpectedFirstVertex = -1L;
    private int controlledSmokeCommandReadbackScheduledExpectedFirstInstance = -1;
    private long controlledSmokeCommandReadbackScheduledBufferId;
    private long controlledSmokeCommandReadbackCompletedGeneration = -1L;
    private int controlledSmokeCommandExpectedSectionId = -1;
    private long controlledSmokeCommandExpectedVertexCount = -1L;
    private int controlledSmokeCommandExpectedInstanceCount = -1;
    private long controlledSmokeCommandExpectedFirstVertex = -1L;
    private int controlledSmokeCommandExpectedFirstInstance = -1;
    private long controlledSmokeCommandExpectedBufferId;
    private int controlledSmokeCommandReadbackCompletedSectionId = -1;
    private long controlledSmokeCommandReadbackCompletedExpectedVertexCount = -1L;
    private int controlledSmokeCommandReadbackCompletedExpectedInstanceCount = -1;
    private long controlledSmokeCommandReadbackCompletedExpectedFirstVertex = -1L;
    private int controlledSmokeCommandReadbackCompletedExpectedFirstInstance = -1;
    private long controlledSmokeCommandReadbackCompletedBufferId;
    private boolean javaKnownControlledSmokeCommandWrittenThisFrame;
    private int javaKnownControlledSmokeCommandSectionId = -1;
    private long javaKnownControlledSmokeCommandVertexCount;
    private int javaKnownControlledSmokeCommandInstanceCount;
    private long javaKnownControlledSmokeCommandFirstVertex;
    private int javaKnownControlledSmokeCommandFirstInstance;
    private boolean javaKnownControlledSmokeCommandBarrierRecordedThisFrame;
    private long javaKnownControlledSmokeCommandTargetBufferId;
    private String javaKnownControlledSmokeCommandTargetMatchesDrawBuffer = "unknown";
    private String javaKnownControlledSmokeCommandActualOrder = "not_recorded";
    private String commandBufferSubmitRisk = "none";
    private String controlledSmokeIndirectCommandMode = "update_buffer";
    private String controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
    private String controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
    private String controlledSmokeKnownCommandWriteMethod = "vkCmdUpdateBuffer";
    private boolean controlledSmokeKnownCommandBufferHostVisible;
    private String controlledSmokeKnownCommandBufferHostCoherent = "unknown";
    private String controlledSmokeKnownCommandBufferFlushed = "false";
    private boolean cmdgenDispatchRecordedThisFrame;
    private boolean controlledSmokeKnownCommandUploadRecordedThisFrame;
    private String controlledSmokeKnownCommandUploadMethodThisFrame = "none";
    private boolean controlledSmokeReadbackCopyRecordedThisFrame;
    private boolean controlledSmokeReadbackBarrierRecordedThisFrame;
    private boolean anyVkCmdDrawIndirectRecordedThisFrame;
    private boolean anyVkCmdDrawRecordedThisFrame;
    private String drawRecordedReasonThisFrame = "none";
    private boolean resourcesBound;
    private boolean sceneUniformBound;
    private String sectionDrawBinding0DescriptorKind = "unknown";
    private boolean graphicsPipelineCreated;
    private long graphicsPipelineGeneration;
    private String sectionDrawFragmentSourceHash = "uncompiled";
    private boolean sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine;
    private boolean sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch;
    private boolean sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine;
    private boolean sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeBranch;
    private boolean sectionDrawFragmentSourceContainsMagentaDefine;
    private boolean sectionDrawFragmentSourceContainsMagentaBranch;
    private boolean sectionDrawVertexSourceContainsFinalPassMarkerDefine;
    private boolean sectionDrawVertexSourceContainsFinalPassMarkerBranch;
    private boolean sectionDrawFragmentSourceContainsFinalPassMarkerDefine;
    private boolean sectionDrawFragmentSourceContainsFinalPassMarkerBranch;
    private boolean commandGenPipelineCreated;
    private long lastCmdgenRenderListBufferId;
    private long lastCmdgenRenderListRangeBytes;
    private boolean cmdgenDescriptorsReboundThisFrame;
    private long drawCommandBufferAllocationGeneration;
    private long lastCmdgenBinding3ReboundGeneration;
    private String lastCmdgenDescriptorDiagnostic = "";
    private String lastCmdgenCommandBufferDiagnostic = "";
    private String lastSmokeDrawOutputDiagnostic = "";
    private String lastControlledSmokeDiagnostic = "";
    private String lastWorldDrawMappingDiagnostic = "";
    private String lastControlledRenderListWordsDiagnostic = "";
    private static final String EXPENSIVE_WORLD_DRAW_DIAGNOSTICS_ENV = "VOXY_VULKAN_BERYL_ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS";
    private static final int EXPENSIVE_DIAGNOSTIC_MAX_CANDIDATE_TESTS = 2048;
    private static final long EXPENSIVE_DIAGNOSTIC_CACHE_NANOS = 5_000_000_000L;
    private static final long TEST_STATUS_INTERVAL_NANOS = 1_000_000_000L;
    private static volatile double lastDiagnosticCpuMs;
    private ControlledRenderListSmoke cachedExpensiveDiagnosticSmoke;
    private long cachedExpensiveDiagnosticGeometrySyncGeneration = -1L;
    private long cachedExpensiveDiagnosticTimestampNanos;
    private long lastExpensiveDiagnosticScanNanos;
    private long lastWorldDrawMappingDiagnosticLogNanos;
    private long lastDrawCountAllocationBufferId;
    private long lastDrawCountRenderFrameBufferId;
    private int drawCountAllocationGeneration;
    private boolean lastDrawCountAllocationUsedScratchPath;
    private boolean lastOldRealDrawCountBufferStillExists;
    private boolean freed;
    private int rawVisibleCountForTestStatus = -1;
    private long diagnosticCpuNanosThisPass;
    private int realLodProbeSceneUniformForcedQuadIndex;
    private long realLodProbeCpuSelectedQuadIndex = -1L;
    private int realLodProbeCpuSelectedSectionId = -1;
    private long realLodProbeSelectedSectionPassQuadStart = -1L;
    private boolean realLodProbeReplayCpuClipAvailable;
    private final float[] realLodProbeReplayCpuClip = new float[16];
    private String realLodProbeReplayCpuClip0 = "unavailable";
    private String realLodProbeReplayCpuClip1 = "unavailable";
    private String realLodProbeReplayCpuClip2 = "unavailable";
    private String realLodProbeReplayCpuClip3 = "unavailable";
    private String realLodProbeReplayCpuClipUnavailableReason = "not_requested";
    private boolean realLodProbeReplayCpuWorldAvailable;
    private final float[] realLodProbeReplayCpuWorld = new float[16];
    private String realLodProbeReplayCpuWorld0 = "unavailable";
    private String realLodProbeReplayCpuWorld1 = "unavailable";
    private String realLodProbeReplayCpuWorld2 = "unavailable";
    private String realLodProbeReplayCpuWorld3 = "unavailable";
    private String realLodProbeReplayCpuWorldUnavailableReason = "not_requested";
    private String realLodProbeReplayCpuWorldCoordinateSpace = "unavailable";
    private boolean realLodGpuDecodeParityPending;
    private boolean realLodGpuDecodeParityCompleted;
    private boolean realLodGpuDecodeParityCopyRecorded;
    private long realLodGpuDecodeParityPendingFrameId = -1L;
    private int realLodGpuDecodeParityPendingRendererFrameSlot = -1;
    private int realLodGpuDecodeParityPendingSectionId = -1;
    private long realLodGpuDecodeParityPendingQuadIndex = -1L;
    private CpuDecodeParitySnapshot realLodGpuDecodeParityCurrentCpuSnapshot = CpuDecodeParitySnapshot.unavailable("not_scheduled");
    private CpuDecodeParitySnapshot realLodGpuDecodeParityPendingCpuSnapshot = CpuDecodeParitySnapshot.unavailable("not_scheduled");
    private GpuDecodeParitySnapshot realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("not_scheduled");
    private boolean realLodProbeBoundMetadataReadbackAvailable;
    private int realLodProbeBoundMetadataSectionMetaA0;
    private int realLodProbeBoundMetadataSectionMetaA1;
    private int realLodProbeBoundMetadataSectionMetaA2;
    private int realLodProbeBoundMetadataSectionMetaA3;
    private int realLodProbeBoundMetadataSectionMetaB0;
    private int realLodProbeBoundMetadataSectionMetaB1;
    private int realLodProbeBoundMetadataSectionMetaB2;
    private int realLodProbeBoundMetadataSectionMetaB3;
    private boolean realLodProbeBoundGeometryReadbackAvailable;
    private long realLodProbeBoundGeometryRawQuadData;
    private String realLodProbeBoundMetadataMatchesCpuSnapshot = "unknown";
    private String realLodProbeShaderMetadataMatchesBoundMetadata = "unknown";
    private String realLodProbeBoundGeometryRawQuadMatchesCpuSnapshot = "unknown";
    private String realLodProbeShaderRawQuadMatchesBoundGeometry = "unknown";
    private String realLodProbeBufferContentMismatchReason = "not_computed";

    private static final boolean DRAW_SCREENSPACE_SMOKE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE");
    private static final boolean DRAW_SCREENSPACE_SMOKE_INDIRECT = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE_INDIRECT");
    private static final boolean DRAW_WORLDSPACE_SMOKE_INDIRECT = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_DRAW_WORLDSPACE_SMOKE_INDIRECT");
    private static final boolean REAL_LOD_VISIBILITY_DIAGNOSTIC = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_VISIBILITY_DIAGNOSTIC");
    private static final boolean REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE");
    private static final boolean ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS = VulkanBerylEnvironment.flag(EXPENSIVE_WORLD_DRAW_DIAGNOSTICS_ENV, false);
    private static final boolean ENABLE_LODS = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_ENABLE_LODS", false);
    private static final boolean REAL_QUAD_READ_CLIPSPACE_PROBE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE");
    private static final boolean REAL_LOD_SINGLE_QUAD_WORLD_PROBE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE");
    private static final boolean REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE = REAL_LOD_SINGLE_QUAD_WORLD_PROBE && VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE");
    private static final boolean REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP = REAL_LOD_SINGLE_QUAD_WORLD_PROBE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP");
    private static final boolean REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD = REAL_LOD_SINGLE_QUAD_WORLD_PROBE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD");
    private static final boolean REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD = REAL_LOD_SINGLE_QUAD_WORLD_PROBE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD && VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD");
    private static final boolean REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD = REAL_LOD_SINGLE_QUAD_WORLD_PROBE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD && VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD");
    private static final boolean SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE");
    private static final boolean SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER", false);
    private static final boolean DISABLE_CONTROLLED_SMOKE_READBACK_COPY = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_DISABLE_READBACK_COPY", false);
    private static final boolean DISABLE_CONTROLLED_SMOKE_KNOWN_COMMAND_UPLOAD = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_DISABLE_KNOWN_COMMAND_UPLOAD", false);
    private static final boolean CONTROLLED_SMOKE_USE_MAIN_DRAW_COMMAND_BUFFER_FOR_KNOWN_COMMAND = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_USE_MAIN_DRAW_COMMAND_BUFFER_FOR_KNOWN_COMMAND", false);
    private static final boolean CONTROLLED_SMOKE_MAIN_BUFFER_SKIP_KNOWN_COMMAND_UPLOAD = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_MAIN_BUFFER_SKIP_KNOWN_COMMAND_UPLOAD", false);
    private static final boolean DRAW_DEBUG_FRAGMENT_DIAGNOSTIC = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_DRAW_DEBUG_FRAGMENT");
    private static final boolean DRAW_ENABLE_DEPTH_TEX = VulkanBerylEnvironment.flag("VOXY_VULKAN_BERYL_SECTION_DRAW_ENABLE_DEPTH_TEX");
    private static final int SCREENSPACE_SMOKE_VERTEX_COUNT = 3;
    private static final int SCREENSPACE_SMOKE_INSTANCE_COUNT = 1;
    private static final int SCREENSPACE_SMOKE_FIRST_VERTEX = 0;
    private static final int SCREENSPACE_SMOKE_FIRST_INSTANCE = 0;
    private static final int SAME_PASS_SCREENSPACE_PROBE_VERTEX_COUNT = 3;
    private static final int SAME_PASS_SCREENSPACE_PROBE_INSTANCE_COUNT = 1;
    private static final int SAME_PASS_SCREENSPACE_PROBE_FIRST_VERTEX = 0;
    private static final int SAME_PASS_SCREENSPACE_PROBE_FIRST_INSTANCE = 0x6D515A7A;
    private static final int FINAL_PASS_SCREENSPACE_MARKER_VERTEX_COUNT = 3;
    private static final int FINAL_PASS_SCREENSPACE_MARKER_INSTANCE_COUNT = 1;
    private static final int FINAL_PASS_SCREENSPACE_MARKER_FIRST_VERTEX = 0;
    private static final int FINAL_PASS_SCREENSPACE_MARKER_FIRST_INSTANCE = 0x464D4152;
    private boolean finalPassScreenspaceMarkerRecorded;
    private long finalPassScreenspaceMarkerFrameCounter;

    public static void setSectionDrawPassContext(String passContext) {
        SECTION_DRAW_PASS_CONTEXT.set(passContext == null || passContext.isBlank() ? "unknown" : passContext);
    }


    public static void clearSectionDrawPassContext() {
        SECTION_DRAW_PASS_CONTEXT.remove();
    }



    private static boolean screenspaceSmokeShaderEnabled() {
        return DRAW_SCREENSPACE_SMOKE || DRAW_SCREENSPACE_SMOKE_INDIRECT || (SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE && !REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) || SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER;
    }


    private static boolean screenspaceSmokeDirectDrawEnabled() {
        return DRAW_SCREENSPACE_SMOKE && !DRAW_SCREENSPACE_SMOKE_INDIRECT;
    }


    private static String screenspaceSmokeEnvName() {
        if (DRAW_SCREENSPACE_SMOKE_INDIRECT) return "VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE_INDIRECT";
        if (REAL_QUAD_READ_CLIPSPACE_PROBE) return "VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE";
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return "VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE";
        if (REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) return "VOXY_VULKAN_BERYL_REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE";
        if (SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE) return "VOXY_VULKAN_BERYL_SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE";
        if (SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER) return "VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER";
        if (DRAW_WORLDSPACE_SMOKE_INDIRECT) return "VOXY_VULKAN_BERYL_DRAW_WORLDSPACE_SMOKE_INDIRECT";
        return "VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE";
    }


    private static boolean useDebugFragmentShader() {
        return DRAW_DEBUG_FRAGMENT_DIAGNOSTIC || DEBUG_COLOUR_MODE || REAL_LOD_VISIBILITY_DIAGNOSTIC || REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE || REAL_QUAD_READ_CLIPSPACE_PROBE || REAL_LOD_SINGLE_QUAD_WORLD_PROBE || SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER;
    }


    private static boolean controlledRenderListDiagnosticEnabled() {
        return RENDERLIST_SMOKE_ONE_ENTRY || REAL_QUAD_READ_CLIPSPACE_PROBE || REAL_LOD_SINGLE_QUAD_WORLD_PROBE;
    }


    private static boolean realLodMagentaDiagnosticEnabled() {
        return REAL_LOD_VISIBILITY_DIAGNOSTIC || REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE || REAL_QUAD_READ_CLIPSPACE_PROBE || REAL_LOD_SINGLE_QUAD_WORLD_PROBE || SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER;
    }


    private static String realLodProbeGpuVertexMode() {
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE) return "hardcoded_clipspace_probe";
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP) return "replay_cpu_clip";
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD) return "replay_cpu_world";
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) return "force_cpu_section_and_quad";
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD) return "forced_cpu_selected_quad";
        return "real_decoded_quad";
    }


    private static boolean finalPassScreenspaceMarkerFragmentEnabled() {
        return SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER;
    }


    static double getLastDiagnosticCpuMs() {
        return lastDiagnosticCpuMs;
    }


    public void ensureDrawPipeline() {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (this.graphicsPipeline != null) return;
        this.graphicsPipeline = this.createDrawPipeline(false);
        this.graphicsPipelineGeneration = ++nextGraphicsPipelineGeneration;
        this.graphicsPipelineCreated = true;
    }


    private void ensureTranslucentDrawPipeline() {
        if (this.freed) throw new IllegalStateException("section draw pipeline is freed");
        if (this.translucentGraphicsPipeline != null) return;
        this.translucentGraphicsPipeline = this.createDrawPipeline(true);
    }


    private GraphicsPipeline createDrawPipeline(boolean translucent) {

        URL configUrl = VulkanBerylSectionDrawPipeline.class.getResource(DRAW_SHADER_CONFIG);
        if (configUrl == null) throw new IllegalStateException("Missing section draw shader config: " + DRAW_SHADER_CONFIG);

        JsonObject config;
        try (InputStreamReader reader = new InputStreamReader(configUrl.openStream(), StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load section draw shader config: " + DRAW_SHADER_CONFIG, e);
        }

        VertexFormat drawVertexFormat = resolveDummyVertexFormat();
        configureSectionDrawPrimitiveTopologyTriangleList("pipeline_creation");
        Pipeline.Builder builder = new Pipeline.Builder(drawVertexFormat);
        List<UBO> drawDescriptors = createManualDrawDescriptors();
        boolean debugFragmentShader = useDebugFragmentShader();
        String drawPassName = translucent ? "translucent" : "opaque";
        VulkanBerylDebugLog.verboseOnce("section-draw-descriptor-layout:" + drawPassName, "Section draw descriptor mode=manual_dense, pass=" + drawPassName + ", bindings=[0,1,2,3,4,5,6,7,8,9], denseFromZero=true, vertexShader=" + DRAW_SHADER_NAME + ", fragmentShader=" + (debugFragmentShader ? DRAW_DEBUG_FRAGMENT_SHADER_NAME : DRAW_SHADER_NAME) + ", debugColourMode=" + debugFragmentShader + ", debugColourRequested=" + DEBUG_COLOUR_MODE + ", debugFragmentRequested=" + DRAW_DEBUG_FRAGMENT_DIAGNOSTIC + ", depthTextureSamplingEnabled=" + DRAW_ENABLE_DEPTH_TEX + ", realLodGpuDecodeParityBinding=" + REAL_LOD_GPU_DECODE_PARITY_BINDING + ", normalTexturedDrawWired=true, shaderIndexingMode=instanceIndex_drawIndex_metadataPassBaseQuad_plus_vertexIndex_div_6_triangle_list, shaderDrawIndexBuiltin=gl_InstanceIndex, shaderVertexBuiltin=gl_VertexIndex, shaderBaseVertexBuiltin=unused_firstVertex_zero, primitiveTopology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, shaderDrawParametersBuiltins=unavailable_in_runtime_glsl450_shaderc_path");
        try {
            builder.setUniforms(drawDescriptors, createManualDrawImageDescriptors());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create manual section draw descriptor layout (config is validation-only and is not fed to Beryl parseBindings): " + DRAW_SHADER_CONFIG, e);
        }
        String fragmentShaderName = debugFragmentShader ? DRAW_DEBUG_FRAGMENT_SHADER_NAME : DRAW_SHADER_NAME;
        String fragmentShaderResource = debugFragmentShader ? DRAW_DEBUG_FRAGMENT_SHADER_RESOURCE : DRAW_FRAGMENT_SHADER_RESOURCE;
        var preprocessedShaders = VulkanBerylShaderImportPreprocessor.preprocessShaderSetToTemp(DRAW_SHADER_RESOURCE, fragmentShaderResource);
        applyDrawVisibilityDiagnosticDefines(preprocessedShaders);
        if (translucent) {
            applyTranslucentDrawDefine(preprocessedShaders);
        }
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
        this.sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine = vertexSource.contains("#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE 1");
        this.sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch = vertexSource.contains("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE");
        this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine = vertexSource.contains("#define VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE 1");
        this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeBranch = vertexSource.contains("VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE");
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE && !this.sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine) {
            throw new IllegalStateException("VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE parsed true but preprocessed section draw vertex shader is missing its define: " + expectedVertexTempPath);
        }
        if (REAL_QUAD_READ_CLIPSPACE_PROBE && !this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine) {
            throw new IllegalStateException("VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE parsed true but preprocessed section draw vertex shader is missing its define: " + expectedVertexTempPath);
        }
        this.sectionDrawFragmentSourceHash = shortSha256(fragmentSource);
        this.sectionDrawFragmentSourceContainsMagentaDefine = fragmentSource.contains("#define VOXY_VULKAN_BERYL_REAL_LOD_VISIBILITY_DIAGNOSTIC 1");
        this.sectionDrawFragmentSourceContainsMagentaBranch = fragmentSource.contains("outColour = vec4(1.0, 0.0, 1.0, 1.0)");
        this.sectionDrawVertexSourceContainsFinalPassMarkerDefine = vertexSource.contains("#define VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER 1");
        this.sectionDrawVertexSourceContainsFinalPassMarkerBranch = vertexSource.contains("VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER");
        this.sectionDrawFragmentSourceContainsFinalPassMarkerDefine = fragmentSource.contains("#define VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER 1");
        this.sectionDrawFragmentSourceContainsFinalPassMarkerBranch = fragmentSource.contains("VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER");
        if (SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER && !this.sectionDrawFragmentSourceContainsFinalPassMarkerDefine) {
            throw new IllegalStateException("VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER parsed true but preprocessed section draw fragment shader is missing its define: " + expectedFragmentTempPath);
        }
        VulkanBerylDebugLog.verboseOnce("section-draw-vertex-source-diagnostics", "Section draw vertex source diagnostics: realLodSingleQuadWorldProbe=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                + ", sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine=" + this.sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine
                + ", sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch=" + this.sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch
                + ", realQuadReadClipspaceProbe=" + REAL_QUAD_READ_CLIPSPACE_PROBE
                + ", sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine=" + this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine
                + ", sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeBranch=" + this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeBranch
                + ", vertexTempPath=" + expectedVertexTempPath);
        VulkanBerylDebugLog.verboseOnce("section-draw-fragment-source-diagnostics", "Section draw fragment source diagnostics: sectionDrawFragmentSourceContainsMagentaDefine=" + this.sectionDrawFragmentSourceContainsMagentaDefine
                + ", sectionDrawFragmentSourceContainsMagentaBranch=" + this.sectionDrawFragmentSourceContainsMagentaBranch
                + ", sectionDrawFragmentSourceHash=" + this.sectionDrawFragmentSourceHash
                + ", fragmentShaderName=" + fragmentShaderName
                + ", fragmentTempPath=" + expectedFragmentTempPath);
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
            throw new IllegalStateException("Failed to compile section draw shaders (vertex=" + DRAW_SHADER_NAME + ", fragment=" + fragmentShaderName + ", debugMode=" + debugFragmentShader + ")"
                    + "\ncompileShaders expected args: name + vertexSource + fragmentSource (GLSL text)"
                    + "\nProvided sources read from: " + expectedVertexTempPath + " and " + expectedFragmentTempPath
                    + "\nVertex first lines:\n" + vertexPreview
                    + "\nFragment first lines:\n" + fragmentPreview, e);
        }
        GraphicsPipeline pipeline;
        try {
            pipeline = builder.createGraphicsPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section draw graphics pipeline (config=" + DRAW_SHADER_CONFIG + ", debugMode=" + debugFragmentShader + ")", e);
        }
        if (pipeline == null) throw new IllegalStateException("Failed to create section draw graphics pipeline");
        return pipeline;
    }


    private static VertexFormat resolveDummyVertexFormat() {
        return DefaultVertexFormat.EMPTY;
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


    private static void applyDrawVisibilityDiagnosticDefines(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        applyDrawScreenspaceSmokeDefine(preprocessedShaders);
        applyRealLodVertexPathClipspaceProbeDefine(preprocessedShaders);
        applyRealQuadReadClipspaceProbeDefine(preprocessedShaders);
        applyRealLodSingleQuadWorldProbeDefine(preprocessedShaders);
        applyRealLodVisibilityDiagnosticFragmentDefine(preprocessedShaders);
        applyFinalPassScreenspaceMarkerFragmentDefine(preprocessedShaders);
        applyDepthTextureSamplingDefine(preprocessedShaders);
    }


    private static void applyDrawScreenspaceSmokeDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!screenspaceSmokeShaderEnabled() && !DRAW_WORLDSPACE_SMOKE_INDIRECT) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader vertexShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".vsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw vertex shader for screenspace smoke define"));
        try {
            String source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw vertex shader has no #version line: " + vertexShader.shaderPath());
            }
            StringBuilder defines = new StringBuilder();
            if (DRAW_SCREENSPACE_SMOKE || DRAW_SCREENSPACE_SMOKE_INDIRECT) {
                defines.append("#define VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE 1\n");
            }
            if (DRAW_WORLDSPACE_SMOKE_INDIRECT) {
                defines.append("#define VOXY_VULKAN_BERYL_DRAW_WORLDSPACE_SMOKE_INDIRECT 1\n");
            }
            if (SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE && !REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) {
                defines.append("#define VOXY_VULKAN_BERYL_SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE 1\n");
            }
            if (SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER) {
                defines.append("#define VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER 1\n");
            }
            String defineBlock = defines.toString();
            if (!defineBlock.isEmpty() && !source.contains(defineBlock)) {
                Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + defineBlock + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-screenspace-smoke-enabled", "section draw smoke diagnostic enabled: env=" + screenspaceSmokeEnvName() + ", samePassScreenspaceProbe=" + (SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE && !REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) + ", finalPassScreenspaceMarker=" + SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER + ", samePassSuppressedByRealLodVertexPathClipspaceProbe=" + (SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE && REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) + ", vertexShader=" + vertexShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw screenspace smoke diagnostic", e);
        }
    }


    private static void applyTranslucentDrawDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        VulkanBerylShaderImportPreprocessor.PreparedShader vertexShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".vsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw vertex shader for translucent define"));
        VulkanBerylShaderImportPreprocessor.PreparedShader fragmentShader = preprocessedShaders.shaders().stream()
                .filter(shader -> shader.tempShaderRelativePath().endsWith(".fsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw fragment shader for translucent define"));
        try {
            String source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw vertex shader has no #version line: " + vertexShader.shaderPath());
            }
            String define = "#define VOXY_VULKAN_BERYL_TRANSLUCENT_PASS 1\n";
            if (!source.contains(define)) {
                Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-translucent-variant-enabled", "section draw translucent shader variant enabled: vertexShader=" + vertexShader.shaderPath());

            source = Files.readString(fragmentShader.shaderPath(), StandardCharsets.UTF_8);
            firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw fragment shader has no #version line: " + fragmentShader.shaderPath());
            }
            define = "#define TRANSLUCENT 1\n";
            if (!source.contains(define)) {
                Files.writeString(fragmentShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-translucent-fragment-variant-enabled", "section draw translucent fragment shader define injected: fragmentShader=" + fragmentShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw translucent variant", e);
        }
    }


    private static void applyRealLodVertexPathClipspaceProbeDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader vertexShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".vsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw vertex shader for real LOD vertex path clip-space probe"));
        try {
            String source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw vertex shader has no #version line: " + vertexShader.shaderPath());
            }
            String define = "#define VOXY_VULKAN_BERYL_REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE 1\n";
            if (!source.contains(define)) {
                Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-real-lod-vertex-path-clipspace-probe-enabled", "section draw real LOD vertex path clip-space probe enabled: env=VOXY_VULKAN_BERYL_REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE, normalIndirectDrawPath=true, separateVkCmdDrawProbe=false, samePassScreenspaceProbeSuppressed=" + SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE + ", vertexShader=" + vertexShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw real LOD vertex path clip-space probe", e);
        }
    }


    private static void applyRealQuadReadClipspaceProbeDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!REAL_QUAD_READ_CLIPSPACE_PROBE) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader vertexShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".vsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw vertex shader for real quad-read clip-space probe"));
        try {
            String source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw vertex shader has no #version line: " + vertexShader.shaderPath());
            }
            String define = "#define VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE 1\n";
            if (!source.contains(define)) {
                Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-real-quad-read-clipspace-probe-enabled", "section draw real_quad_read_clipspace_probe enabled: env=VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE, normalIndirectDrawPath=true, readsRenderListSectionId=true, computesPassQuadStart=true, readsQuadData=true, callsSetupQuad=true, forcedClipspaceOutput=true, vertexShader=" + vertexShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw real quad-read clip-space probe", e);
        }
    }


    private static void applyRealLodSingleQuadWorldProbeDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader vertexShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".vsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw vertex shader for real LOD single-quad world probe"));
        try {
            String source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw vertex shader has no #version line: " + vertexShader.shaderPath());
            }
            String define = "#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE 1\n";
            if (!source.contains(define)) {
                Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
                source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
                firstLineEnd = source.indexOf('\n');
            }
            if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE) {
                define = "#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE 1\n";
                if (!source.contains(define)) {
                    Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
                    source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
                    firstLineEnd = source.indexOf('\n');
                }
            }
            if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP) {
                define = "#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP 1\n";
                if (!source.contains(define)) {
                    Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
                    source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
                    firstLineEnd = source.indexOf('\n');
                }
            }
            if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD) {
                define = "#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD 1\n";
                if (!source.contains(define)) {
                    Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
                    source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
                    firstLineEnd = source.indexOf('\n');
                }
            }
            if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) {
                define = "#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD 1\n";
                if (!source.contains(define)) {
                    Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
                    source = Files.readString(vertexShader.shaderPath(), StandardCharsets.UTF_8);
                    firstLineEnd = source.indexOf('\n');
                }
            }
            if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD) {
                define = "#define VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD 1\n";
                if (!source.contains(define)) {
                    Files.writeString(vertexShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
                }
            }
            VulkanBerylDebugLog.once("section-draw-real-lod-single-quad-world-probe-enabled", "section draw real LOD single-quad world probe enabled: env=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE"
                    + ", hardcodedClipspaceEnv=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE"
                    + ", replayCpuClipEnv=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP"
                    + ", replayCpuWorldEnv=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD"
                    + ", forceCpuSectionAndQuadEnv=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD"
                    + ", forceCpuSelectedQuadEnv=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD"
                    + ", realLodProbeGpuVertexMode=" + realLodProbeGpuVertexMode()
                    + ", forcedClipspaceOutput=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE
                    + ", replayCpuClipOutput=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP
                    + ", replayCpuWorldOutput=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD
                    + ", realLodProbeForceCpuSectionAndQuadEnabled=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD
                    + ", usesGetQuadCornerPos=" + (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD)
                    + ", realLodProbeShaderStillUsesRealDecodePath=" + (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD)
                    + ", suppressesLocalQuadIndexGreaterThanZero=true"
                    + ", vertexShader=" + vertexShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw real LOD single-quad world probe", e);
        }
    }


    private static void applyRealLodVisibilityDiagnosticFragmentDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!realLodMagentaDiagnosticEnabled()) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader fragmentShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_DEBUG_FRAGMENT_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".fsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw debug fragment shader for real LOD visibility diagnostic"));
        try {
            String source = Files.readString(fragmentShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw debug fragment shader has no #version line: " + fragmentShader.shaderPath());
            }
            String define = "#define VOXY_VULKAN_BERYL_REAL_LOD_VISIBILITY_DIAGNOSTIC 1\n";
            if (!source.contains(define)) {
                Files.writeString(fragmentShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-real-lod-visibility-diagnostic-enabled", "section draw real LOD visibility diagnostic enabled: env=" + (REAL_QUAD_READ_CLIPSPACE_PROBE ? "VOXY_VULKAN_BERYL_REAL_QUAD_READ_CLIPSPACE_PROBE" : (REAL_LOD_SINGLE_QUAD_WORLD_PROBE ? "VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE" : (REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE ? "VOXY_VULKAN_BERYL_REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE" : "VOXY_VULKAN_BERYL_REAL_LOD_VISIBILITY_DIAGNOSTIC"))) + ", fragmentShader=" + fragmentShader.shaderPath() + ", forcedFragmentColour=magenta, depthCullOverride=true");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw real LOD visibility diagnostic", e);
        }
    }



    private static void applyDepthTextureSamplingDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!DRAW_ENABLE_DEPTH_TEX || useDebugFragmentShader()) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader fragmentShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".fsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw fragment shader for depth texture sampling define"));
        try {
            String source = Files.readString(fragmentShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw fragment shader has no #version line: " + fragmentShader.shaderPath());
            }
            String define = "#define VOXY_ENABLE_DEPTH_TEX 1\n";
            if (!source.contains(define)) {
                Files.writeString(fragmentShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-depth-texture-sampling-enabled", "section draw depth texture sampling enabled: env=VOXY_VULKAN_BERYL_SECTION_DRAW_ENABLE_DEPTH_TEX, fragmentShader=" + fragmentShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw depth texture sampling", e);
        }
    }


    private static void applyFinalPassScreenspaceMarkerFragmentDefine(VulkanBerylShaderImportPreprocessor.PreparedShaderSet preprocessedShaders) {
        if (!finalPassScreenspaceMarkerFragmentEnabled()) return;
        VulkanBerylShaderImportPreprocessor.PreparedShader fragmentShader = preprocessedShaders.shaders().stream()
                .filter(shader -> DRAW_DEBUG_FRAGMENT_SHADER_NAME.equals(shader.shaderName()) && shader.tempShaderRelativePath().endsWith(".fsh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing preprocessed section draw debug fragment shader for final-pass screenspace marker define"));
        try {
            String source = Files.readString(fragmentShader.shaderPath(), StandardCharsets.UTF_8);
            int firstLineEnd = source.indexOf('\n');
            if (firstLineEnd < 0) {
                throw new IllegalStateException("Preprocessed section draw debug fragment shader has no #version line: " + fragmentShader.shaderPath());
            }
            String define = "#define VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER 1\n";
            if (!source.contains(define)) {
                Files.writeString(fragmentShader.shaderPath(), source.substring(0, firstLineEnd + 1) + define + source.substring(firstLineEnd + 1), StandardCharsets.UTF_8);
            }
            VulkanBerylDebugLog.once("section-draw-final-pass-screenspace-marker-fragment-enabled", "section draw final-pass screenspace marker fragment define injected: fragmentShader=" + fragmentShader.shaderPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enable section draw final-pass screenspace marker fragment define", e);
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
        guardCmdgenProbeExclusivity();
        VulkanBerylCmdgenDiagnostics.logActiveEnvSummaryOnce();
        this.cmdgenDescriptorsReboundThisFrame = false;

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

        ensureSceneUniformBuffer();
        bindUniformBinding(SCENE_UNIFORM_BINDING, this.sceneUniformBuffer, "sceneUniformBuffer");
        bindStorageBinding(GEOMETRY_BINDING, geometryData.getGeometryBuffer(), "geometryData.geometryBuffer");
        bindStorageBinding(METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindStorageBinding(RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE) {
            ensureRealLodGpuDecodeParityBuffers();
            bindStorageBinding(REAL_LOD_GPU_DECODE_PARITY_BINDING, this.realLodGpuDecodeParityBuffer, "realLodGpuDecodeParityBuffer");
        }
        logSectionDrawDescriptorParityBindings(geometryData, renderList);
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
        if (CMDGEN_FULL_LAYOUT_NOOP_PROBE || CMDGEN_DISPATCH_NOOP_SAME_LAYOUT) {
            this.ensureCommandGenFullLayoutNoopProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE) {
            this.ensureCommandGenFullLayoutHardcodedBinding0ReadProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE) {
            this.ensureCommandGenFullLayoutConfigBinding0ReadProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_PROBE) {
            this.ensureCommandGenNoImportProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE) {
            this.ensureCommandGenNoImportReadMetadata0OnlyProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE || CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE) {
            this.ensureCommandGenNoImportRawMetadataUvec4Binding1ProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE) {
            this.ensureCommandGenFullLayoutNoopProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE) {
            this.ensureCommandGenFullLayoutBinding1UintReadProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE) {
            this.ensureCommandGenFullLayoutBinding1UintReadConstProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE) {
            this.ensureCommandGenFullLayoutBinding1TinyUintReadNoConfigProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE) {
            this.ensureCommandGenFullLayoutBinding1AndBinding2UintReadProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE) {
            this.ensureCommandGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE) {
            this.ensureCommandGenFullLayoutBinding2ProbeBufferUintReadConstProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE) {
            this.ensureCommandGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE) {
            this.ensureCommandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE) {
            this.ensureCommandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbePipeline();
        }
        if (CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE) {
            this.ensureCommandGenSingleBinding1UintReadProbePipeline();
        }
        if (CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE) {
            this.ensureCommandGenBinding0UintReadProbePipeline();
        }
        if (CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE) {
            this.ensureCommandGenFullLayoutBinding2UintReadProbePipeline();
        }
        if (CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE) {
            this.ensureCommandGenRawMetadataUvec4Binding0ProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE
                || CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE
                || CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE) {
            this.ensureCmdgenTinyMetadataProbeBuffer();
        }
        if (CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE) {
            this.ensureCommandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE) {
            this.ensureCommandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE) {
            this.ensureCommandGenNoImportAtomicDrawcountOnlyProbePipeline();
        }
        if (CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE) {
            this.ensureCommandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline();
        }
        if (CMDGEN_DENSE_LAYOUT_NOOP_PROBE) {
            this.ensureCommandGenDenseLayoutNoopProbePipeline();
        }
        bindComputeStorageBinding(CMDGEN_METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindComputeStorageBinding(CMDGEN_RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        bindComputeStorageBinding(CMDGEN_UNUSED_BINDING2_BINDING, this.cmdGenUnusedBinding2Buffer, "cmdGenUnusedBinding2Buffer");
        logCmdgenRenderListDescriptorState("main", renderList.getBuffer(), renderList.getBuffer().getBufferSize(), true);
        bindComputeStorageBinding(CMDGEN_DRAW_COMMAND_BINDING, this.drawCommandBuffer, "drawCommandBuffer");
        Buffer drawCountDescriptorBuffer = cmdgenDrawCountDescriptorBuffer();
        bindComputeStorageBinding(CMDGEN_DRAW_COUNT_BINDING, drawCountDescriptorBuffer, drawCountDescriptorLabel());
        logCmdgenDrawCountDescriptorOverride(drawCountDescriptorBuffer, "resource_bind");
        this.cmdgenDescriptorsReboundThisFrame = true;
        logDrawCountBufferDiagnostics("descriptor_bind", true);
        bindComputeStorageBinding(CMDGEN_CONFIG_BINDING, this.cmdGenConfigBuffer, "cmdGenConfigBuffer");
        VulkanBerylDebugLog.once("cmdgen-descriptors-bound", "cmdgen descriptors bound");
        this.resourcesBound = true;
        logSectionDrawBindingState("resource_bind");
    }


    public boolean isReady() {
        return this.graphicsPipeline != null && this.resourcesBound && !this.freed;
    }

    public void pollDebugReadback() {
        if (!CMDGEN_DEBUG_READBACK_LOG_ONLY) {
            this.consumePendingDebugCommandSampleIfReady(this.controlledSmokeCommandReadbackFrameId < 0 ? -1 : this.controlledSmokeCommandReadbackFrameId + 1);
        }
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, null);
    }

    public boolean isSceneUniformBound() { return this.sceneUniformBound; }
    public boolean isGraphicsPipelineCreated() { return this.graphicsPipelineCreated; }
    public boolean isCommandGenPipelineCreated() { return this.commandGenPipelineCreated; }
    public boolean isDebugColourModeEnabled() { return useDebugFragmentShader(); }
    public boolean isDepthSamplingEnabled() { return DRAW_ENABLE_DEPTH_TEX && !useDebugFragmentShader(); }
    public boolean isModelLightPathEnabled() { return !useDebugFragmentShader(); }
    public boolean isDebugSamplePending() { return this.debugSamplePending; }

    public record OpaqueDrawSubmission(int submittedVisibleCount, String drawMode, long submittedQuadCount, int submittedDrawCommandCount, int sampledCommandCount, int invalidSampledCommandCount, long sampledQuadCount, boolean samplePending, String skippedReason) {}

    private record NoDrawCountDiagnosticIndirectGate(boolean allowed, String blocker) {}
    private record JavaDrawCountDiagnostic(int drawCount, String source) {}
    private record IndirectDrawGate(boolean allowed, String reason) {}

    public OpaqueDrawSubmission renderOpaque(Renderer renderer,
                            VulkanBerylViewport viewport,
                            VulkanBerylSectionGeometryData geometryData,
                            VulkanBerylViewportRenderList renderList) {
        this.activeDrawPass = DrawPass.OPAQUE;
        refreshActiveCompletedDebugSample();
        applyOpaquePipelineState();
        return this.renderSectionPass(renderer, viewport, geometryData, renderList);
    }


    public OpaqueDrawSubmission renderTranslucent(Renderer renderer,
                            VulkanBerylViewport viewport,
                            VulkanBerylSectionGeometryData geometryData,
                            VulkanBerylViewportRenderList renderList) {
        this.ensureTranslucentDrawPipeline();
        GraphicsPipeline opaquePipeline = this.graphicsPipeline;
        this.graphicsPipeline = this.translucentGraphicsPipeline;
        try {
            this.ensureDrawResourcesBound(geometryData, renderList);
            this.activeDrawPass = DrawPass.TRANSLUCENT;
            refreshActiveCompletedDebugSample();
            applyTranslucentPipelineState();
            OpaqueDrawSubmission submission = this.renderSectionPass(renderer, viewport, geometryData, renderList);
            return new OpaqueDrawSubmission(
                    submission.submittedVisibleCount(),
                    "translucent_" + submission.drawMode(),
                    submission.submittedQuadCount(),
                    submission.submittedDrawCommandCount(),
                    submission.sampledCommandCount(),
                    submission.invalidSampledCommandCount(),
                    submission.sampledQuadCount(),
                    submission.samplePending(),
                    submission.skippedReason());
        } finally {
            this.activeDrawPass = DrawPass.OPAQUE;
            refreshActiveCompletedDebugSample();
            this.graphicsPipeline = opaquePipeline;
            applyOpaquePipelineState();
        }
    }


    private OpaqueDrawSubmission renderSectionPass(Renderer renderer,
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
        guardCmdgenProbeExclusivity();

        // TEST_STATUS common fields
        int testTraversalStageLimit = VulkanBerylCmdgenDiagnostics.TRAVERSAL_STAGE_LIMIT;
        String testStageMeaning = VulkanBerylCmdgenDiagnostics.traversalStageMeaning(testTraversalStageLimit);
        String testProbeEnvName = VulkanBerylCmdgenDiagnostics.selectedProbeEnvVarName();
        String testSelectedShader = VulkanBerylCmdgenDiagnostics.selectedProbeShaderName();
        String testIsolationMode = VulkanBerylCmdgenDiagnostics.selectedProbeIsolationModeName();
        boolean testProbeSelected = VulkanBerylCmdgenDiagnostics.isProbeSelected();
        String testProbeSelectionReason = VulkanBerylCmdgenDiagnostics.probeSelectionReason();
        logProbeSelectionLine(testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode, testProbeSelectionReason);

        this.drawCountClearedThisFrame = false;
        this.drawCountClearCommandRecordedThisFrame = false;
        this.drawCountUsedRealClearPathThisFrame = false;
        this.javaKnownControlledSmokeCommandWrittenThisFrame = false;
        this.javaKnownControlledSmokeCommandSectionId = -1;
        this.javaKnownControlledSmokeCommandVertexCount = 0L;
        this.javaKnownControlledSmokeCommandInstanceCount = 0;
        this.javaKnownControlledSmokeCommandFirstVertex = 0L;
        this.javaKnownControlledSmokeCommandFirstInstance = 0;
        this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame = false;
        this.javaKnownControlledSmokeCommandTargetBufferId = 0L;
        this.javaKnownControlledSmokeCommandTargetMatchesDrawBuffer = "unknown";
        this.javaKnownControlledSmokeCommandActualOrder = "not_recorded";
        this.commandBufferSubmitRisk = "none";
        this.controlledSmokeIndirectCommandMode = "update_buffer";
        this.controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
        this.controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
        this.controlledSmokeKnownCommandWriteMethod = "vkCmdUpdateBuffer";
        this.controlledSmokeKnownCommandBufferHostVisible = false;
        this.controlledSmokeKnownCommandBufferHostCoherent = "unknown";
        this.controlledSmokeKnownCommandBufferFlushed = "false";
        this.controlledSmokeKnownCommandUploadQueued = false;
        this.controlledSmokeKnownCommandUploadFlushed = false;
        this.controlledSmokeKnownCommandUploadSkipped = false;
        this.controlledSmokeKnownCommandUploadSkipReason = "none";
        this.controlledSmokeKnownCommandUploadTupleChanged = false;
        this.controlledSmokeCommandValidationState = "not_applicable";
        this.controlledSmokeCommandCanSubmit = true;
        this.controlledSmokeCommandDrawSubmitReason = "submitted";
        this.drawCountUsedScratchClearPathThisFrame = false;
        this.cmdgenDispatchRecordedThisFrame = false;
        this.diagnosticCpuNanosThisPass = 0L;
        this.controlledSmokeKnownCommandUploadRecordedThisFrame = false;
        this.controlledSmokeKnownCommandUploadMethodThisFrame = "none";
        this.controlledSmokeReadbackCopyRecordedThisFrame = false;
        this.controlledSmokeReadbackBarrierRecordedThisFrame = false;
        this.anyVkCmdDrawIndirectRecordedThisFrame = false;
        this.anyVkCmdDrawRecordedThisFrame = false;
        this.drawRecordedReasonThisFrame = "none";
        if (CMDGEN_SKIP_RENDER_DRAW_SUBMIT_AFTER_CMDGEN && !isExplicitCmdgenDiagnosticEnvActive()) {
            VulkanBerylDebugLog.once("cmdgen-skip-render-draw-submit-inactive", "VOXY_VULKAN_BERYL_CMDGEN_SKIP_RENDER_DRAW_SUBMIT_AFTER_CMDGEN ignored because no explicit cmdgen diagnostic env var is active");
        }
        if (CMDGEN_WAIT_IDLE_AFTER_DISPATCH && !isExplicitCmdgenDiagnosticEnvActive()) {
            VulkanBerylDebugLog.once("cmdgen-wait-idle-after-dispatch-inactive", "VOXY_VULKAN_BERYL_CMDGEN_WAIT_IDLE_AFTER_DISPATCH ignored because no explicit cmdgen diagnostic env var is active");
        }
        if ((CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT || CMDGEN_USE_PASSING_SCRATCH_BINDING_AS_REAL_DRAWCOUNT_DESCRIPTOR || CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH || CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH) && !isExplicitCmdgenShaderSelectionDiagnosticActive()) {
            logInactiveDrawCountDiagnosticEnvVars();
        }

        int maxEntryCount = renderList.getMaxEntryCount();
        ControlledRenderListSmoke controlledSmoke = ControlledRenderListSmoke.disabled();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (commandBuffer == null) {
            throw new IllegalStateException("VULKANMOD_BERYL command buffer is unavailable");
        }
        if (DRAW_SCREENSPACE_SMOKE_INDIRECT) {
            return renderScreenspaceSmokeIndirectIsolated(renderer, viewport, commandBuffer);
        }
        if (controlledRenderListDiagnosticEnabled()) {
            long diagnosticStartNanos = System.nanoTime();
            controlledSmoke = recordControlledRenderListSmoke(commandBuffer, viewport, geometryData, renderList);
            addDiagnosticCpuNanos(System.nanoTime() - diagnosticStartNanos);
        }
        int rawVisibleCount = controlledSmoke.enabled() ? (controlledSmoke.safe() ? 1 : 0) : renderList.getLastVisibleCount();
        int visibleCount = Math.max(0, Math.min(rawVisibleCount, maxEntryCount));
        this.rawVisibleCountForTestStatus = rawVisibleCount;
        recordFinalPassScreenspaceMarker(renderer, viewport, commandBuffer, rawVisibleCount, visibleCount);
        VulkanBerylRenderBackendRuntime.FrameSafetyState frameSafety = VulkanBerylRenderBackendRuntime.getLastFrameSafetyState();
        CmdgenIsolationStage isolationStage = selectedCmdgenIsolationStage();
        boolean noDrawCountFullCmdgen = CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER;
        ControlledRenderListSmoke cpuSelectionSmoke = controlledSmoke.enabled()
                ? controlledSmoke
                : (cpuRenderListSelectionDiagnosticEnabled()
                ? timedFindControlledRenderListSmokeSection(viewport, geometryData, renderList)
                : ControlledRenderListSmoke.disabled());
        updateRealLodProbeSceneUniformSelection(viewport, geometryData, cpuSelectionSmoke);
        logRenderListVisibilityDiagnostics(renderList, geometryData, controlledSmoke, cpuSelectionSmoke, rawVisibleCount, visibleCount, noDrawCountFullCmdgen, "frame_gate");
        logCpuMirrorMetadataDiagnostic(geometryData, "frame_gate");
        boolean realRenderListCmdgenAllowed = ENABLE_CMDGEN_DISPATCH && !controlledSmoke.enabled() && visibleCount > 0 && (ENABLE_LODS || frameSafety.allowCmdGen());
        boolean noOpCmdgenSmoke = ENABLE_CMDGEN_DISPATCH && !ENABLE_INDIRECT_DRAW && !CMDGEN_DEBUG_READBACK && !noDrawCountFullCmdgen && !realRenderListCmdgenAllowed;
        boolean fullCmdgenDispatchAllowed = ENABLE_LODS || (ENABLE_CMDGEN_DISPATCH && (realRenderListCmdgenAllowed || isolationStage != null || ENABLE_INDIRECT_DRAW || noOpCmdgenSmoke || noDrawCountFullCmdgen || FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED));
        String fullCmdgenDispatchBlocker = fullCmdgenDispatchAllowed ? "ready" : (!ENABLE_CMDGEN_DISPATCH ? "cmdgen_dispatch_disabled" : "select_isolation_stage_or_force_full_cmdgen_dispatch_with_indirect_disabled");
        boolean cmdgenAllowed = ENABLE_LODS || (ENABLE_CMDGEN_DISPATCH && (realRenderListCmdgenAllowed || noOpCmdgenSmoke || isolationStage != null || noDrawCountFullCmdgen || FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED || frameSafety.allowCmdGen() || controlledSmoke.safe()));
        String cmdgenAllowReason = realRenderListCmdgenAllowed ? "real_render_list_cmdgen_allowed" : (controlledSmoke.safe() ? "controlled_smoke_section_available" : (noOpCmdgenSmoke ? "noop_cmdgen_smoke_allowed" : "ready"));
        CmdgenCommandSnapshot currentCmdgenSnapshot = cmdgenCommandSnapshot(geometryData, renderList, controlledSmoke);
        boolean cmdgenSampleValid = completedCmdgenSampleSnapshotMatches(currentCmdgenSnapshot);
        boolean indirectSafetyAllowed = frameSafety.allowIndirectDraw() || controlledSmoke.safe();
        boolean indirectAllowed = ENABLE_LODS || (ENABLE_INDIRECT_DRAW && indirectSafetyAllowed);
        boolean cmdgenDispatchSubmitted = false;
        int cmdgenDispatchGroupCount = 0;
        boolean cmdgenDispatchCallRecorded = false;
        boolean cmdgenPostDispatchBarrierRecorded = false;
        String gateReason = controlledSmoke.enabled() ? controlledSmoke.reason() : frameSafety.reason();
        String indirectGateReason = !ENABLE_INDIRECT_DRAW
                ? "indirect_draw_disabled"
                : (!indirectSafetyAllowed ? gateReason : "ready");
        String cmdgenGateReason = !ENABLE_CMDGEN_DISPATCH ? "cmdgen_dispatch_disabled" : (!cmdgenAllowed ? gateReason : "ready");
        logDrawCountAliasAndLifetimeDiagnostics(geometryData, renderList, "frame_gate", indirectAllowed);
        VulkanBerylDebugLog.trace("gpu-stage-gate", "GPU stage gate: traversalDispatch=true cmdgenDispatch=" + cmdgenAllowed + " indirectDraw=" + indirectAllowed + " reason=" + gateReason);
        if (FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED) {
            VulkanBerylDebugLog.once("cmdgen-force-full-with-indirect-disabled", "forced full cmdgen dispatch with indirect draw disabled is active: env=VOXY_VULKAN_BERYL_FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED");
        }
        VulkanBerylDebugLog.once("cmdgen-dispatch-gate-state", "cmdgen dispatch gate: enableCmdgenDispatch=" + ENABLE_CMDGEN_DISPATCH
                + ", enableIndirectDraw=" + ENABLE_INDIRECT_DRAW
                + ", debugReadback=" + CMDGEN_DEBUG_READBACK
                + ", forceFullCmdgenDispatchWithIndirectDisabled=" + FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED
                + ", noDrawCountFullCmdgen=" + noDrawCountFullCmdgen
                + ", fullCmdgenDispatchAllowed=" + fullCmdgenDispatchAllowed
                + ", realRenderListCmdgenAllowed=" + realRenderListCmdgenAllowed
                + ", cmdgenAllowReason=" + cmdgenAllowReason
                + ", finalGateReason=" + cmdgenGateReason
                + ", finalBlockerReason=" + fullCmdgenDispatchBlocker);
        JavaDrawCountDiagnostic javaDrawCountForNoDrawCountCmdgen = new JavaDrawCountDiagnostic(visibleCount, "render_list_visible_count");
        if (visibleCount <= 0) {
            logVisibleCountZeroReason(renderList, rawVisibleCount, visibleCount, frameSafety, controlledSmoke, cpuSelectionSmoke, noDrawCountFullCmdgen);
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    false, false, false, 0,
                    true, "no_visible_render_list_entries",
                    false, false, 0, "no_visible_render_list_entries");
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "no_visible_render_list_entries");
            return new OpaqueDrawSubmission(0, "indirect_generated_per_section", 0L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "no_visible_render_list_entries");
        }
        if (!cmdgenAllowed) {
            if (CMDGEN_DEBUG_READBACK && !fullCmdgenDispatchAllowed) {
                VulkanBerylDebugLog.once("cmdgen-debug-readback-full-dispatch-gated", "cmdgen debug readback requested but skipped because full cmdgen dispatch was gated: reason=" + fullCmdgenDispatchBlocker);
                logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), false, false, false);
            }
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    false, false, false, 0,
                    true, "cmdgen_gate:" + gateReason,
                    false, false, 0, "cmdgen_gate:" + gateReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, false, 0, false, false, 0, "cmdgen_gate:" + gateReason, noDrawCountFullCmdgen);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "cmdgen_gate:" + gateReason);
            return new OpaqueDrawSubmission(0, "indirect_generated_per_section", 0L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "cmdgen_gate:" + gateReason);
        }

        if (CMDGEN_CREATE_ONLY) {
            if (activeCmdgenShaderSelectionEnvVar() != null) {
                VulkanBerylDebugLog.once("cmdgen-standalone-binding0-config-dispatch-path", "selected normal-cmdgen shader uses same dispatch path as normal cmdgen");
            }
            VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.commandGenPipeline.getId());
            this.commandGenPipeline.bindDescriptorSets(commandBuffer, 0);
            logCmdgenCommandBufferUse(isolationStage == null ? "create_only" : isolationStage.envName(), commandBuffer, commandBuffer, true);
            VulkanBerylDebugLog.once("cmdgen-create-only-skipped", "cmdgen dispatch skipped/create-only");
            VulkanBerylDebugLog.once("cmdgen-debug-readback-state", "cmdgen debug readback " + (CMDGEN_DEBUG_READBACK ? "enabled" : "skipped"));
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    false, false, false, 0,
                    true, "cmdgen_create_only",
                    false, false, 0, "cmdgen_create_only");
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, false, 0, false, false, 0, "cmdgen_create_only", noDrawCountFullCmdgen);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, "cmdgen_create_only");
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "cmdgen_create_only");
        }

        boolean singleSsboReadProbe = CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE
                || CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE
                || CMDGEN_MINIMAL_CONFIG_READ_PROBE
                || CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE
                || CMDGEN_HARDCODED_READ_BINDING0_ONLY
                || CMDGEN_FULL_LAYOUT_NOOP_PROBE
                || CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE
                || CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE
                || CMDGEN_NO_IMPORT_PROBE
                || CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE
                || CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE
                || CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE
                || CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE
                || CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE
                || CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE
                || CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE
                || CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE
                || CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE
                || CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE
                || CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE
                || CMDGEN_DENSE_LAYOUT_NOOP_PROBE;
        String cmdgenBlocker = validateCmdgenDispatchInputs(geometryData, renderList, visibleCount, controlledSmoke, ENABLE_LODS || realRenderListCmdgenAllowed || (noOpCmdgenSmoke && isolationStage == null) || singleSsboReadProbe, isolationStage);
        if (cmdgenBlocker != null) {
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    false, false, false, 0,
                    true, "cmdgen_blocked:" + cmdgenBlocker,
                    false, false, 0, "cmdgen_blocked:" + cmdgenBlocker);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, false, 0, false, false, 0, "cmdgen_blocked:" + cmdgenBlocker, noDrawCountFullCmdgen);
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
            cmdgenDispatchSubmitted = true;
            cmdgenDispatchGroupCount = 1;
            this.cmdgenDispatchRecordedThisFrame = true;
            VulkanBerylDebugLog.once("cmdgen-noop-dispatch-submitted", "cmdgen noop dispatch submitted: descriptorBindProbe=" + CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE);
        } else {
            validateDrawCommandBuffer(visibleCount);
            int cmdgenFlags = isolationStage == null ? (noOpCmdgenSmoke ? CMDGEN_FLAG_NOOP_SMOKE : 0) : isolationStage.shaderFlag();
            if (this.activeDrawPass == DrawPass.TRANSLUCENT && isolationStage == null && !noOpCmdgenSmoke) {
                cmdgenFlags |= CMDGEN_FLAG_TRANSLUCENT_PASS;
            }
            boolean useAltRenderListBuffer = CMDGEN_RENDERLIST_ALT_BUFFER_PROBE && (isolationStage != null || CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE || CMDGEN_HARDCODED_READ_BINDING0_ONLY);
            if (useAltRenderListBuffer) {
                ensureAndBindCmdgenRenderListAltProbeBuffer();
            }
            updateAndBindCmdGenConfigBuffer(commandBuffer, geometryData, useAltRenderListBuffer ? cmdGenRenderListAltProbeCapacityEntries() : renderList.getMaxEntryCount(), cmdgenFlags);
            logMetadataBufferState(geometryData, isolationStage == null ? "before_dispatch:full" : "before_dispatch:" + isolationStage.envName(), geometryData.getMetadataBuffer(), CMDGEN_METADATA_BINDING);
            if (CMDGEN_UPLOAD_CONFIG_ONLY) {
                return stopCmdgenIsolation(visibleCount, "cmdgen_upload_config_only");
            }
            clearDrawCommandState(commandBuffer);
            logPassingScratchAsRealComparison();
            logDrawCountBufferDiagnostics(isolationStage == null ? "before_dispatch:full" : "before_dispatch:" + isolationStage.envName(), true);
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
            if (CMDGEN_FULL_LAYOUT_NOOP_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutNoopProbePipeline, "full_layout_noop_probe");
            }
            if (CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline, "full_layout_hardcoded_binding0_read_probe");
            }
            if (CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutConfigBinding0ReadProbePipeline, "full_layout_config_binding0_read_probe");
            }
            if (CMDGEN_NO_IMPORT_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportProbePipeline, "no_import_probe");
            }
            if (CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportReadMetadata0OnlyProbePipeline, "no_import_read_metadata0_only_probe");
            }
            if (CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportRawMetadataUvec4Binding1ProbePipeline, geometryData.getMetadataBuffer(), CMDGEN_METADATA_BINDING, "no_import_raw_metadata_uvec4_binding1_probe");
            }
            if (CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportRawMetadataUvec4Binding1ProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, "no_import_raw_metadata_uvec4_binding1_tiny_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutNoopProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, "full_layout_binding1_no_read_tiny_bind_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1UintReadProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, "full_layout_binding1_tiny_uint_read_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1UintReadConstProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, "full_layout_binding1_tiny_uint_read_const_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1TinyUintReadNoConfigProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, "full_layout_binding1_tiny_uint_read_no_config_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1AndBinding2UintReadProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, "full_layout_binding1_and_binding2_uint_read_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE) {
                return dispatchBinding2ProbeBufferNoConfigProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbePipeline, "full_layout_binding2_probe_buffer_uint_read_no_config_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE) {
                return dispatchBinding2ProbeBufferConstProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding2ProbeBufferUintReadConstProbePipeline, "full_layout_binding2_probe_buffer_uint_read_const_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE) {
                return dispatchBinding1AndBinding2NoConfigProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbePipeline, this.cmdGenTinyMetadataProbeBuffer, "full_layout_binding1_tiny_and_binding2_tiny_uint_read_no_config_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE) {
                return dispatchBinding1AndBinding2NoConfigProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbePipeline, this.cmdGenBinding2ProbeBuffer, "full_layout_binding1_tiny_and_binding2_probe_buffer_uint_read_no_config_probe");
            }
            if (CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE) {
                return dispatchBinding1AndBinding2ConstProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbePipeline, "full_layout_binding1_tiny_and_binding2_probe_buffer_uint_read_const_probe");
            }
            if (CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenSingleBinding1UintReadProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING, CMDGEN_SINGLE_BINDING1_UINT_READ_SHADER_NAME, "single_binding1_tiny_uint_read_probe", true);
            }
            if (CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE) {
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenBinding0UintReadProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_RENDER_LIST_BINDING, CMDGEN_BINDING0_UINT_READ_SHADER_NAME, "binding1_as_binding0_tiny_uint_read_probe", true);
            }
            if (CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutBinding2UintReadProbePipeline, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_BINDING2_PROBE_BINDING, "full_layout_binding2_tiny_uint_read_probe");
            }
            if (CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE) {
                return dispatchMetadataBindingProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenRawMetadataUvec4Binding0ProbePipeline, geometryData.getMetadataBuffer(), CMDGEN_RENDER_LIST_BINDING, "raw_metadata_uvec4_binding0_real_probe");
            }
            if (CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline, "no_import_compute_quad_counts_only_no_write_probe");
            }
            if (CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline, "no_import_write_command0_only_no_atomic_probe");
            }
            if (CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportAtomicDrawcountOnlyProbePipeline, "no_import_atomic_drawcount_only_probe");
            }
            if (CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE) {
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline, "no_import_single_invocation_real_command_no_atomic_probe");
            }
            if (CMDGEN_DENSE_LAYOUT_NOOP_PROBE) {
                return dispatchDenseLayoutNoopProbe(commandBuffer, visibleCount, geometryData, renderList);
            }
            if (skipDrawCountClearBeforeDispatchActive() && !this.drawCountClearCommandRecordedThisFrame) {
                barrierTransferToComputeForCmdgenNonDrawCountTransfers(commandBuffer, useAltRenderListBuffer ? this.cmdGenRenderListAltProbeBuffer : null);
                VulkanBerylDebugLog.once("cmdgen-drawcount-before-barrier-skipped", "cmdgen drawCount barrier skipped: stage=before_cmdgen_dispatch, reason=drawCount clear/initialise was skipped"
                        + ", clearCommandRecorded=" + this.drawCountClearCommandRecordedThisFrame
                        + ", skippedByEnv=" + skipDrawCountClearBeforeDispatchActive());
                logDrawCountBarrierDiagnostic("before_cmdgen_dispatch", false, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            } else {
                barrierTransferToCompute(commandBuffer);
                logDrawCountBarrierDiagnostic("before_cmdgen_dispatch", true, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            }
            if (CMDGEN_CLEAR_OUTPUTS_ONLY) {
                return stopCmdgenIsolation(visibleCount, "cmdgen_clear_outputs_only");
            }
            // Diagnostic isolation mode routing: use dedicated shaders to avoid binding5+binding0 dual-access crash
            if (isolationStage == CmdgenIsolationStage.READ_BINDING0_ONLY_NO_OUTPUT_WRITE) {
                this.ensureCommandGenFullLayoutHardcodedBinding0ReadProbePipeline();
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline, "read_binding0_only_no_output_write");
            }
            if (isolationStage == CmdgenIsolationStage.READ_CONFIG_ONLY) {
                this.ensureCommandGenMinimalConfigReadProbePipeline();
                return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, this.commandGenMinimalConfigReadProbePipeline, this.cmdGenConfigBuffer, CMDGEN_CONFIG_BINDING, CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME, "read_config_only", false);
            }
            if (isolationStage == CmdgenIsolationStage.READ_RENDERLIST_METADATA_NO_WRITE) {
                this.ensureCommandGenReadRenderlistMetadataNoWritePipeline();
                return dispatchFullLayoutProbe(commandBuffer, visibleCount, geometryData, renderList, this.commandGenReadRenderlistMetadataNoWritePipeline, "read_renderlist_metadata_no_write");
            }
            if (activeCmdgenShaderSelectionEnvVar() != null) {
                VulkanBerylDebugLog.once("cmdgen-standalone-binding0-config-dispatch-path", "selected normal-cmdgen shader uses same dispatch path as normal cmdgen");
            }
            boolean cmdgenSameLayoutNoopActive = CMDGEN_DISPATCH_NOOP_SAME_LAYOUT && isolationStage == null;
            ComputePipeline cmdgenDispatchPipeline = this.commandGenPipeline;
            String cmdgenDispatchShaderName;
            String cmdgenDispatchShaderResource;
            if (cmdgenSameLayoutNoopActive) {
                if (this.commandGenFullLayoutNoopProbePipeline == null) {
                    throw new IllegalStateException("cmdgen full-layout noop pipeline missing for same-layout noop dispatch: env=VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP_SAME_LAYOUT");
                }
                cmdgenDispatchPipeline = this.commandGenFullLayoutNoopProbePipeline;
                cmdgenDispatchShaderName = CMDGEN_FULL_LAYOUT_NOOP_SHADER_NAME;
                cmdgenDispatchShaderResource = CMDGEN_FULL_LAYOUT_NOOP_SHADER_RESOURCE;
                bindFullLayoutProbeDescriptors(cmdgenDispatchPipeline, geometryData, renderList);
                VulkanBerylDebugLog.once("cmdgen-dispatch-noop-same-layout-active", "cmdgen dispatch noop-same-layout active: env=VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP_SAME_LAYOUT=true, pipeline=" + cmdgenDispatchShaderName + ", reason=substituting same-descriptor-set-layout noop shader to isolate shader-memory-access vs descriptor/pipeline scope");
            } else {
                cmdgenDispatchShaderName = safeActiveCmdgenShaderName();
                cmdgenDispatchShaderResource = safeActiveCmdgenShaderResource();
            }
            String cmdgenDispatchStageName = isolationStage == null ? (cmdgenSameLayoutNoopActive ? "noop_same_layout" : activeCmdgenShaderMode()) : isolationStage.envName();
            String cmdgenDispatchValidation = validateCmdgenDispatchDescriptorBindings(cmdgenDispatchPipeline, cmdgenDispatchStageName);
            boolean cmdgenPipelineBound = false;
            boolean cmdgenDescriptorsBound = false;
            boolean cmdgenDispatchSkippedByEnv = false;
            String cmdgenPostDispatchBarrierSkippedReason = null;
            if (CMDGEN_DISABLE_BIND_PIPELINE) {
                VulkanBerylDebugLog.once("cmdgen-disable-bind-pipeline", "cmdgen vkCmdBindPipeline skipped: env=VOXY_VULKAN_BERYL_CMDGEN_DISABLE_BIND_PIPELINE=true, stage=" + cmdgenDispatchStageName);
            } else {
                VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, cmdgenDispatchPipeline.getId());
                cmdgenPipelineBound = true;
            }
            if (CMDGEN_DISABLE_BIND_DESCRIPTORS) {
                VulkanBerylDebugLog.once("cmdgen-disable-bind-descriptors", "cmdgen bindDescriptorSets skipped: env=VOXY_VULKAN_BERYL_CMDGEN_DISABLE_BIND_DESCRIPTORS=true, stage=" + cmdgenDispatchStageName);
            } else {
                cmdgenDispatchPipeline.bindDescriptorSets(commandBuffer, 0);
                cmdgenDescriptorsBound = true;
            }
            logCmdgenCommandBufferUse(useAltRenderListBuffer ? "alt_renderlist_probe" : cmdgenDispatchStageName, commandBuffer, commandBuffer, true);
            int groupCountX = isolationStage == null ? (noOpCmdgenSmoke ? 1 : ((visibleCount + 127) >>> 7)) : 1;
            if (CMDGEN_BIND_FULL_ONLY) {
                logCmdgenDispatchPathDiagnostic(cmdgenDispatchPipeline, cmdgenDispatchShaderName, cmdgenDispatchShaderResource, cmdgenDispatchStageName, cmdgenPipelineBound, cmdgenDescriptorsBound, false, false, false, null, 0, cmdgenSameLayoutNoopActive, cmdgenDispatchValidation, "cmdgen_bind_full_only");
                return stopCmdgenIsolation(visibleCount, "cmdgen_bind_full_only");
            }
            if (isolationStage == null && !fullCmdgenDispatchAllowed) {
                if (CMDGEN_DEBUG_READBACK) {
                    VulkanBerylDebugLog.once("cmdgen-debug-readback-full-dispatch-gated", "cmdgen debug readback requested but skipped because full cmdgen dispatch was gated: reason=" + fullCmdgenDispatchBlocker);
                    logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), false, false, false);
                }
                logCmdgenDispatchPathDiagnostic(cmdgenDispatchPipeline, cmdgenDispatchShaderName, cmdgenDispatchShaderResource, cmdgenDispatchStageName, cmdgenPipelineBound, cmdgenDescriptorsBound, false, false, false, null, 0, cmdgenSameLayoutNoopActive, cmdgenDispatchValidation, "cmdgen_dispatch_blocked:" + fullCmdgenDispatchBlocker);
                return stopCmdgenIsolation(visibleCount, "cmdgen_dispatch_blocked:" + fullCmdgenDispatchBlocker);
            }
            boolean cmdgenDispatchSafeToRecord = cmdgenPipelineBound && cmdgenDescriptorsBound;
            if (CMDGEN_DISABLE_DISPATCH_CALL) {
                VulkanBerylDebugLog.once("cmdgen-disable-dispatch-call", "cmdgen vkCmdDispatch skipped: env=VOXY_VULKAN_BERYL_CMDGEN_DISABLE_DISPATCH_CALL=true, stage=" + cmdgenDispatchStageName + ", intendedGroupsX=" + groupCountX);
                cmdgenDispatchSkippedByEnv = true;
            } else if (!cmdgenDispatchSafeToRecord) {
                VulkanBerylDebugLog.once("cmdgen-dispatch-skipped-no-bind", "cmdgen vkCmdDispatch skipped because pipeline or descriptors were not bound: stage=" + cmdgenDispatchStageName + ", pipelineBound=" + cmdgenPipelineBound + ", descriptorsBound=" + cmdgenDescriptorsBound + ", intendedGroupsX=" + groupCountX);
            } else {
                VK10.vkCmdDispatch(commandBuffer, groupCountX, 1, 1);
                cmdgenDispatchSubmitted = true;
                cmdgenDispatchGroupCount = groupCountX;
                this.cmdgenDispatchRecordedThisFrame = true;
                cmdgenDispatchCallRecorded = true;
                VulkanBerylDebugLog.once("cmdgen-isolation-dispatch", "cmdgen dispatch submitted: stage=" + cmdgenDispatchStageName + ", groupsX=" + groupCountX);
                logCmdgenWaitIdleAfterDispatchState(cmdgenDispatchStageName);
            }

            if (disableAnyDrawCountConsumerPathActive()) {
                VulkanBerylDebugLog.once("cmdgen-drawcount-after-barrier-skipped", "cmdgen drawCount barrier skipped: stage=after_cmdgen_dispatch, reason=drawCount consumer path is disabled, drawCountConsumers=disabled_by_env");
                logDrawCountBarrierDiagnostic("after_cmdgen_dispatch", false, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
                cmdgenPostDispatchBarrierSkippedReason = "drawcount_consumers_disabled_by_env";
            } else if (cmdgenDispatchSkippedByEnv) {
                VulkanBerylDebugLog.once("cmdgen-post-dispatch-barrier-skipped-dispatch-disabled", "cmdgen post-dispatch barrier skipped: dispatch was disabled by env, stage=after_cmdgen_dispatch");
                logDrawCountBarrierDiagnostic("after_cmdgen_dispatch", false, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
                cmdgenPostDispatchBarrierSkippedReason = "dispatch_call_disabled_by_env";
            } else if (CMDGEN_DISABLE_POST_DISPATCH_BARRIER) {
                VulkanBerylDebugLog.once("cmdgen-disable-post-dispatch-barrier", "cmdgen post-dispatch barrier skipped: env=VOXY_VULKAN_BERYL_CMDGEN_DISABLE_POST_DISPATCH_BARRIER=true, stage=after_cmdgen_dispatch");
                logDrawCountBarrierDiagnostic("after_cmdgen_dispatch", false, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
                cmdgenPostDispatchBarrierSkippedReason = "disabled_by_env";
            } else {
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
                    cmdgenPostDispatchBarrierRecorded = true;
                    logDrawCountBarrierDiagnostic("after_cmdgen_dispatch", true, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
                }
            }
            logCmdgenDispatchPathDiagnostic(cmdgenDispatchPipeline, cmdgenDispatchShaderName, cmdgenDispatchShaderResource, cmdgenDispatchStageName, cmdgenPipelineBound, cmdgenDescriptorsBound, cmdgenDispatchCallRecorded, cmdgenDispatchSkippedByEnv, cmdgenPostDispatchBarrierRecorded, cmdgenPostDispatchBarrierSkippedReason, cmdgenDispatchGroupCount, cmdgenSameLayoutNoopActive, cmdgenDispatchValidation, "dispatch_path_recorded");
            boolean testCmdgenDispatchRecorded = cmdgenPipelineBound && cmdgenDescriptorsBound && cmdgenDispatchCallRecorded;
            String testCmdgenSkipReason = testCmdgenDispatchRecorded ? null : (cmdgenDispatchSkippedByEnv ? "dispatch_call_disabled_by_env" : (cmdgenPipelineBound ? (cmdgenDescriptorsBound ? "dispatch_call_not_recorded" : "descriptors_not_bound") : "pipeline_not_bound"));
            boolean testCmdgenSkipped = !testCmdgenDispatchRecorded;
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    cmdgenDispatchCallRecorded, cmdgenPostDispatchBarrierRecorded,
                    testCmdgenDispatchRecorded, cmdgenDispatchGroupCount,
                    testCmdgenSkipped, testCmdgenSkipReason,
                    indirectAllowed, false, 0, indirectGateReason);
            recordJavaKnownControlledSmokeCommand(commandBuffer, controlledSmoke, viewport.frameId);
            recordScreenspaceSmokeIndirectKnownCommand(viewport.frameId);
            javaDrawCountForNoDrawCountCmdgen = recordJavaDrawCountForNoDrawCountCmdgen(commandBuffer, controlledSmoke, visibleCount);
        }

        NoDrawCountDiagnosticIndirectGate noDrawCountDiagnosticIndirectGate = evaluateNoDrawCountDiagnosticIndirectGate(javaDrawCountForNoDrawCountCmdgen.drawCount(), cmdgenDispatchSubmitted);
        if (CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) {
            indirectAllowed = noDrawCountDiagnosticIndirectGate.allowed();
            indirectGateReason = noDrawCountDiagnosticIndirectGate.allowed() ? "ready" : noDrawCountDiagnosticIndirectGate.blocker();
        }
        logNoDrawCountDiagnosticIndirectGate(noDrawCountDiagnosticIndirectGate, indirectAllowed);

        if (disableAnyDrawCountConsumerPathActive()) {
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    cmdgenDispatchCallRecorded, cmdgenPostDispatchBarrierRecorded,
                    cmdgenDispatchSubmitted, cmdgenDispatchGroupCount,
                    !cmdgenDispatchSubmitted, "drawcount_consumer_path_disabled",
                    indirectAllowed, false, 0, "drawcount_consumer_path_disabled");
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, "drawcount_consumer_path_disabled", noDrawCountFullCmdgen);
            logDrawCountConsumerPathDisabled(visibleCount, indirectAllowed, CMDGEN_DEBUG_READBACK);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, "drawcount_consumer_path_disabled");
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "drawcount_consumer_path_disabled");
        }

        if (!CMDGEN_DEBUG_READBACK_LOG_ONLY) {
            this.consumePendingDebugCommandSampleIfReady(viewport.frameId);
        }
        currentCmdgenSnapshot = cmdgenCommandSnapshot(geometryData, renderList, controlledSmoke);
        cmdgenSampleValid = completedCmdgenSampleSnapshotMatches(currentCmdgenSnapshot);
        IndirectDrawGate postCmdgenIndirectGate = evaluatePostCmdgenIndirectDrawGate(cmdgenSampleValid, indirectSafetyAllowed, gateReason, cmdgenDispatchSubmitted, cmdgenPostDispatchBarrierRecorded, javaDrawCountForNoDrawCountCmdgen, -1);
        indirectAllowed = postCmdgenIndirectGate.allowed();
        indirectGateReason = postCmdgenIndirectGate.reason();
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(cmdgenSampleValid, null);
        boolean controlledSmokeCommandReadback = controlledSmoke.safe() && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && ENABLE_INDIRECT_DRAW && cmdgenDispatchSubmitted;
        if (controlledSmokeCommandReadback && !diagnosticReadbackAllowedForActivePass()) {
            controlledSmokeCommandReadback = false;
        }
        ControlledSmokeCommandValidation preScheduleControlledSmokeCommandValidation = controlledSmokeCommandReadback ? validateControlledSmokeCommandReadback(controlledSmoke) : null;
        boolean controlledSmokeCommandCompletedInvalidBeforeFinalReadback = preScheduleControlledSmokeCommandValidation != null
                && this.controlledSmokeCommandReadbackCompleted
                && !preScheduleControlledSmokeCommandValidation.valid();
        boolean controlledSmokeCommandAlreadyObserved = preScheduleControlledSmokeCommandValidation != null
                && this.controlledSmokeCommandReadbackCompleted
                && preScheduleControlledSmokeCommandValidation.valid();
        long currentDiagnosticFrameId = currentCmdgenDiagnosticFrameId(viewport, renderList);
        boolean completedCmdgenSampleMatchesCurrentSnapshot = completedCmdgenSampleSnapshotMatches(currentCmdgenSnapshot);
        boolean pendingCmdgenSampleMatchesCurrentSnapshot = pendingCmdgenSampleSnapshotMatches(currentCmdgenSnapshot);
        boolean normalCmdgenSafetySampleReadback = false;
        int sampledCommandCount = ((!CMDGEN_DEBUG_READBACK && !controlledSmokeCommandReadback && !normalCmdgenSafetySampleReadback) || CMDGEN_DISPATCH_NOOP || noOpCmdgenSmoke) ? 0 : (controlledSmokeCommandReadback || normalCmdgenSafetySampleReadback ? Math.min(1, visibleCount) : Math.min(DRAW_COMMAND_DEBUG_SAMPLE_LIMIT, visibleCount));
        boolean controlledSmokeUploadAgeReady = !controlledSmokeCommandReadback || controlledSmokeKnownCommandUploadAgeReady(viewport.frameId);
        if (controlledSmokeCommandReadback) {
            this.controlledSmokeKnownCommandUploadCompletionStrategy = controlledSmokeUploadAgeReady ? "frame_age_fallback" : "frame_age_pending";
        }
        boolean debugReadbackRequested = diagnosticReadbackAllowedForActivePass() && (CMDGEN_DEBUG_READBACK || controlledSmokeCommandReadback || normalCmdgenSafetySampleReadback) && sampledCommandCount > 0 && !this.debugSamplePending && !(controlledSmokeCommandReadback && controlledSmokeCommandAlreadyObserved) && controlledSmokeUploadAgeReady;
        VulkanBerylDebugLog.once("cmdgen-debug-readback-state", "cmdgen debug readback " + (debugReadbackRequested ? "requested" : "skipped")
                + ", controlledSmokeCommandReadback=" + controlledSmokeCommandReadback);
        ScheduledDebugReadback scheduledDebugReadback;
        if ((controlledSmokeCommandReadback || normalCmdgenSafetySampleReadback) && this.debugSamplePending) {
            scheduledDebugReadback = new ScheduledDebugReadback(false, "pending_readback_in_flight", DRAW_COMMAND_STRIDE_BYTES, 0L, true, true, "pending_readback_in_flight", -1, 0L, this.controlledSmokeCommandReadbackScheduledGeneration, this.pendingDebugSampleSourceBufferId);
        } else if (controlledSmokeCommandAlreadyObserved) {
            scheduledDebugReadback = new ScheduledDebugReadback(false, preScheduleControlledSmokeCommandValidation.valid() ? "completed_valid" : "completed_invalid", DRAW_COMMAND_STRIDE_BYTES, 0L, false, false, "none", -1, 0L, this.controlledSmokeCommandGeneration, this.completedDebugSampleSourceBufferId);
        } else if (controlledSmokeCommandReadback && !controlledSmokeUploadAgeReady) {
            scheduledDebugReadback = new ScheduledDebugReadback(false, "upload_pending_frame_age_fallback", DRAW_COMMAND_STRIDE_BYTES, 0L, false, true, "upload_pending_frame_age_fallback", -1, 0L, this.controlledSmokeCommandGeneration, this.pendingDebugSampleSourceBufferId);
        } else if (!diagnosticReadbackAllowedForActivePass()) {
            scheduledDebugReadback = new ScheduledDebugReadback(false, "diagnostic_prefers_" + diagnosticReadbackPassName(preferredDiagnosticReadbackPass()), DRAW_COMMAND_STRIDE_BYTES, 0L, false, true, "diagnostic_prefers_" + diagnosticReadbackPassName(preferredDiagnosticReadbackPass()), -1, 0L, this.controlledSmokeCommandGeneration, this.pendingDebugSampleSourceBufferId);
        } else if (CMDGEN_DEBUG_READBACK_LOG_ONLY && !controlledSmokeCommandReadback) {
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), false, false, false);
            scheduledDebugReadback = new ScheduledDebugReadback(false, "log_only", 0L, 0L);
        } else {
            long geometryDiagnosticQuadIndex = selectedGeometryDiagnosticQuadIndex(controlledSmoke, currentCmdgenSnapshot);
            scheduledDebugReadback = scheduleDebugCommandReadback(commandBuffer, sampledCommandCount, visibleCount, geometryData, geometryDiagnosticQuadIndex, viewport.frameId);
        }
        if (scheduledDebugReadback.scheduled()) {
            recordCmdgenSampleSchedule(scheduledDebugReadback, viewport.frameId, currentCmdgenSnapshot);
        } else if (scheduledDebugReadback.alreadyPending()) {
            this.cmdgenSampleScheduleReason = scheduledDebugReadback.skipReason();
        }
        cmdgenSampleValid = completedCmdgenSampleSnapshotMatches(currentCmdgenSnapshot);
        postCmdgenIndirectGate = evaluatePostCmdgenIndirectDrawGate(cmdgenSampleValid, indirectSafetyAllowed, gateReason, cmdgenDispatchSubmitted, cmdgenPostDispatchBarrierRecorded, javaDrawCountForNoDrawCountCmdgen, -1);
        indirectAllowed = postCmdgenIndirectGate.allowed();
        indirectGateReason = postCmdgenIndirectGate.reason();
        if (controlledSmokeCommandReadback) {
            recordControlledSmokeCommandReadbackSchedule(scheduledDebugReadback, viewport.frameId);
        }
        int submittedDrawCount = CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER ? javaDrawCountForNoDrawCountCmdgen.drawCount() : Math.min(visibleCount, this.drawCommandCapacity);
        postCmdgenIndirectGate = evaluatePostCmdgenIndirectDrawGate(cmdgenSampleValid, indirectSafetyAllowed, gateReason, cmdgenDispatchSubmitted, cmdgenPostDispatchBarrierRecorded, javaDrawCountForNoDrawCountCmdgen, submittedDrawCount);
        indirectAllowed = postCmdgenIndirectGate.allowed();
        indirectGateReason = postCmdgenIndirectGate.reason();

        if (CMDGEN_SKIP_RENDER_DRAW_SUBMIT_AFTER_CMDGEN && isExplicitCmdgenDiagnosticEnvActive()) {
            VulkanBerylDebugLog.once("cmdgen-render-draw-submit-skipped", "render draw submit intentionally skipped after cmdgen dispatch for this frame: env=VOXY_VULKAN_BERYL_CMDGEN_SKIP_RENDER_DRAW_SUBMIT_AFTER_CMDGEN, diagnosticEnv=" + explicitCmdgenDiagnosticEnvSummary());
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, "render_draw_submit_skipped_after_cmdgen");
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, "render_draw_submit_skipped_after_cmdgen", noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, "render_draw_submit_skipped_after_cmdgen", null);
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, "render_draw_submit_skipped_after_cmdgen");
        }
        String zeroValidCommandReason = zeroValidCommandSkipReason(visibleCount, cmdgenSampleValid);
        if (zeroValidCommandReason != null && !screenspaceSmokeDirectDrawEnabled()) {
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    cmdgenDispatchCallRecorded, cmdgenPostDispatchBarrierRecorded,
                    cmdgenDispatchSubmitted, cmdgenDispatchGroupCount,
                    !cmdgenDispatchSubmitted, cmdgenDispatchSubmitted ? null : zeroValidCommandReason,
                    false, false, 0, zeroValidCommandReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, false, false, 0, zeroValidCommandReason, noDrawCountFullCmdgen);
            logCmdgenZeroCommandReasonSplit(visibleCount);
            logScreenspaceSmokeSubmitDiagnostics(false, zeroValidCommandReason, null);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, zeroValidCommandReason);
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, zeroValidCommandReason);
        }
        if (normalCmdgenSafetySampleReadback && this.debugSamplePending && !screenspaceSmokeDirectDrawEnabled()) {
            String submitReason = "skipped_pending_gpu_completion";
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    cmdgenDispatchCallRecorded, cmdgenPostDispatchBarrierRecorded,
                    cmdgenDispatchSubmitted, cmdgenDispatchGroupCount,
                    !cmdgenDispatchSubmitted, cmdgenDispatchSubmitted ? null : submitReason,
                    false, false, 0, submitReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, false, false, 0, submitReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, submitReason, null);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, submitReason);
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, submitReason);
        }
        if (!indirectAllowed && !screenspaceSmokeDirectDrawEnabled()) {
            logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                    testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                    cmdgenDispatchCallRecorded, cmdgenPostDispatchBarrierRecorded,
                    cmdgenDispatchSubmitted, cmdgenDispatchGroupCount,
                    !cmdgenDispatchSubmitted, cmdgenDispatchSubmitted ? null : indirectGateReason,
                    false, false, 0, indirectGateReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, false, false, 0, indirectGateReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, indirectGateReason, null);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.lastCompletedDebugSample.invalidSampledCommandCount() == 0, indirectGateReason);
            return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, indirectGateReason);
        }
        ControlledSmokeCommandValidation controlledSmokeCommandValidation = controlledSmokeCommandAlreadyObserved ? preScheduleControlledSmokeCommandValidation : validateControlledSmokeCommandReadback(controlledSmoke);
        boolean controlledSmokeGatingActive = controlledSmoke.enabled() && controlledSmoke.safe() && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && ENABLE_INDIRECT_DRAW;
        if (controlledSmokeGatingActive) {
            this.controlledSmokeCommandValidationState = computeControlledSmokeCommandValidationState(controlledSmoke, viewport.frameId);
            this.controlledSmokeCommandCanSubmit = "readback_valid".equals(this.controlledSmokeCommandValidationState);
            this.controlledSmokeCommandDrawSubmitReason = drawSubmitReasonForControlledSmokeState(this.controlledSmokeCommandValidationState);
            if (!this.controlledSmokeCommandCanSubmit) {
                if (DISABLE_CONTROLLED_SMOKE_KNOWN_COMMAND_UPLOAD) {
                    this.controlledSmokeCommandDrawSubmitReason = "diagnostic_known_command_upload_disabled";
                } else if (DISABLE_CONTROLLED_SMOKE_READBACK_COPY) {
                    this.controlledSmokeCommandDrawSubmitReason = "diagnostic_readback_copy_disabled";
                }
            }
            if (!this.controlledSmokeCommandCanSubmit && controlledSmokeDiagnosticBypassAllowed(controlledSmoke, viewport.frameId)) {
                this.controlledSmokeCommandCanSubmit = true;
                this.controlledSmokeCommandDrawSubmitReason = "diagnostic_readback_disabled_submit_allowed";
            }
        } else {
            this.controlledSmokeCommandValidationState = "not_applicable";
            this.controlledSmokeCommandCanSubmit = true;
            this.controlledSmokeCommandDrawSubmitReason = "submitted";
        }
        int effectiveSubmittedDrawCount = this.controlledSmokeCommandCanSubmit ? submittedDrawCount : 0;
        logSmokeDrawOutputDiagnostics(viewport, geometryData, renderList, controlledSmoke, visibleCount, effectiveSubmittedDrawCount, controlledSmokeCommandValidation);
        if (controlledSmokeCommandReadback) {
            logControlledSmokeCommandReadbackLifecycle(viewport.frameId, controlledSmokeCommandValidation);
        }
        if (controlledSmokeGatingActive && !this.controlledSmokeCommandCanSubmit) {
            String submitReason = this.controlledSmokeCommandDrawSubmitReason;
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, submitReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, submitReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, submitReason, controlledSmokeCommandValidation);
            DrawCommandDebugSample blockedSample = this.lastCompletedDebugSample;
            return new OpaqueDrawSubmission(visibleCount, DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "indirect_generated_per_section"), -1L, 0, blockedSample.sampledCommandCount(), blockedSample.invalidSampledCommandCount(), blockedSample.sampledQuadCount(), this.debugSamplePending, submitReason);
        }
        if (!DRAW_SCREENSPACE_SMOKE_INDIRECT && controlledSmokeCommandReadback && !controlledSmokeCommandValidation.valid()) {
            String waitingReason = (this.controlledSmokeCommandReadbackCompleted && controlledSmokeCommandReadbackMatchesCurrentExpected()) || controlledSmokeCommandCompletedInvalidBeforeFinalReadback ? "diagnostic_invalid_command" : "diagnostic_waiting_for_current_command_readback";
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, waitingReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, waitingReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, waitingReason, controlledSmokeCommandValidation);
            return new OpaqueDrawSubmission(visibleCount, DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, waitingReason);
        }
        if (controlledSmoke.enabled() && this.controlledSmokeCommandReadbackCompleted && controlledSmokeCommandValidation != null && !controlledSmokeCommandValidation.valid() && !screenspaceSmokeShaderEnabled()) {
            String submitReason = "diagnostic_invalid_command";
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, submitReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, submitReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, submitReason, controlledSmokeCommandValidation);
            return new OpaqueDrawSubmission(visibleCount, DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "indirect_generated_per_section"), -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, submitReason);
        }
        if (DRAW_SCREENSPACE_SMOKE_INDIRECT && (this.controlledSmokeKnownCommandBuffer == null || this.controlledSmokeKnownCommandBuffer.getId() == 0L)) {
            String submitReason = "screenspace_smoke_indirect_command_buffer_missing";
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, submitReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, submitReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, submitReason, controlledSmokeCommandValidation);
            return new OpaqueDrawSubmission(visibleCount, "screenspace_smoke_indirect_draw", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, submitReason);
        }
        applyRealLodVisibilityDiagnosticPipelineStateOverride();
        configureSectionDrawPrimitiveTopologyTriangleList("draw_submit");
        logSectionDrawGraphicsPipelineState(renderer, viewport, screenspaceSmokeDirectDrawEnabled() ? "screenspace_smoke_direct_draw" : (DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "submitted")));
        renderer.bindGraphicsPipeline(this.graphicsPipeline);
        this.bindSceneUniform(commandBuffer, viewport);
        logSectionDrawBindingState(screenspaceSmokeDirectDrawEnabled() ? "screenspace_smoke_direct_draw" : (DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "submitted")));
        this.bindDrawTextureDescriptors();
        this.graphicsPipeline.bindDescriptorSets(commandBuffer, 0);
        if (screenspaceSmokeDirectDrawEnabled()) {
            VK10.vkCmdDraw(commandBuffer, SCREENSPACE_SMOKE_VERTEX_COUNT, SCREENSPACE_SMOKE_INSTANCE_COUNT, SCREENSPACE_SMOKE_FIRST_VERTEX, SCREENSPACE_SMOKE_FIRST_INSTANCE);
            this.anyVkCmdDrawRecordedThisFrame = true;
            this.drawRecordedReasonThisFrame = "screenspace_smoke_direct_draw";
            if (controlledSmokeGatingActive && !this.controlledSmokeCommandCanSubmit) {
                VulkanBerylDebugLog.warnRateLimited("controlled-smoke-stale-direct-draw-detected", "stale direct draw recorded while controlled smoke validation incomplete: anyVkCmdDrawRecorded=true, anyVkCmdDrawIndirectRecorded=" + this.anyVkCmdDrawIndirectRecordedThisFrame
                        + ", drawRecordedReason=" + this.drawRecordedReasonThisFrame
                        + ", controlledSmokeCommandCanSubmit=false"
                        + ", controlledSmokeCommandValidationState=" + this.controlledSmokeCommandValidationState
                        + ", drawSubmitReason=" + this.controlledSmokeCommandDrawSubmitReason);
            }
            logScreenspaceSmokeSubmitDiagnostics(true, indirectAllowed ? "submitted_direct_draw" : "indirect_gate_bypassed_for_direct_draw:" + indirectGateReason, controlledSmokeCommandValidation);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, true, SCREENSPACE_SMOKE_INSTANCE_COUNT, "screenspace_smoke_direct_draw", noDrawCountFullCmdgen);
            DrawCommandDebugSample sample = this.lastCompletedDebugSample;
            return new OpaqueDrawSubmission(visibleCount, "screenspace_smoke_direct_draw", -1L, SCREENSPACE_SMOKE_INSTANCE_COUNT, sample.sampledCommandCount, sample.invalidSampledCommandCount, sample.sampledQuadCount, this.debugSamplePending, null);
        }
        Buffer indirectDrawCommandBuffer = DRAW_SCREENSPACE_SMOKE_INDIRECT ? this.controlledSmokeKnownCommandBuffer : controlledSmokeDrawCommandBuffer(controlledSmoke);
        boolean gpuCountedIndirectDraw = !DRAW_SCREENSPACE_SMOKE_INDIRECT && !DRAW_WORLDSPACE_SMOKE_INDIRECT && !CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER;
        boolean forceSingleSubmittedCommand = REAL_QUAD_READ_CLIPSPACE_PROBE || REAL_LOD_SINGLE_QUAD_WORLD_PROBE;
        int effectiveIndirectDrawCount = DRAW_SCREENSPACE_SMOKE_INDIRECT ? 1 : (forceSingleSubmittedCommand ? Math.min(submittedDrawCount, 1) : submittedDrawCount);
        if (forceSingleSubmittedCommand && submittedDrawCount > effectiveIndirectDrawCount) {
            VulkanBerylDebugLog.rateLimited("section-draw-real-lod-probe-drawcount-capped", "section draw real LOD probe draw count capped: forceSingleSubmittedCommand=true"
                    + ", realQuadReadClipspaceProbe=" + REAL_QUAD_READ_CLIPSPACE_PROBE
                    + ", realLodSingleQuadWorldProbe=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                    + ", originalSubmittedDrawCount=" + submittedDrawCount
                    + ", cappedSubmittedDrawCount=" + effectiveIndirectDrawCount
                    + ", controlledRenderListDiagnosticEnabled=" + controlledRenderListDiagnosticEnabled()
                    + ", renderListSmokeOneEntry=" + RENDERLIST_SMOKE_ONE_ENTRY, 60);
        }
        if (!DRAW_SCREENSPACE_SMOKE_INDIRECT && controlledSmoke.safe() && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && controlledRenderListDiagnosticEnabled()) {
            boolean readbackGenMatches = controlledSmokeCommandReadbackMatchesCurrentGeneration();
            boolean observedMatchesExpected = controlledSmokeCommandReadbackObservedMatchesCurrentExpected();
            boolean drawableForSafety = controlledSmokeDrawCommandLooksDrawable(controlledSmoke, geometryData, controlledSmokeCommandValidation);
            if (!readbackGenMatches || !observedMatchesExpected || !drawableForSafety) {
                String submitReason = "diagnostic_invalid_command";
                VulkanBerylDebugLog.warnRateLimited("controlled-smoke-indirect-draw-hard-safety", "controlled smoke indirect draw blocked by hard safety gate: drawSubmitReason=" + submitReason
                        + ", readbackGenerationMatches=" + readbackGenMatches
                        + ", observedMatchesExpected=" + observedMatchesExpected
                        + ", smokeDrawCommandLooksDrawable=" + drawableForSafety
                        + ", controlledSmokeCommandReadbackStaleReason=" + controlledSmokeCommandReadbackStaleReason()
                        + ", controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
                        + ", controlledSmokeCommandReadbackGeneration=" + this.controlledSmokeCommandReadbackCompletedGeneration
                        + ", controlledSmokeCommandUploadGeneration=" + this.controlledSmokeCommandUploadGeneration
                        + ", controlledSmokeCommandUploadFrameId=" + this.controlledSmokeCommandUploadFrameId
                        + ", currentFrameId=" + viewport.frameId
                        + ", controlledSmokeKnownCommandUploadCompletionKnown=" + controlledSmokeKnownCommandUploadCompletionKnownString(viewport.frameId)
                        + ", controlledSmokeKnownCommandUploadCompletionStrategy=" + this.controlledSmokeKnownCommandUploadCompletionStrategy
                        + ", indirectDrawRecorded=false"
                        + ", submittedDrawCount=0");
                VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, submitReason);
                logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, submitReason, noDrawCountFullCmdgen);
                logScreenspaceSmokeSubmitDiagnostics(false, submitReason, controlledSmokeCommandValidation);
                DrawCommandDebugSample blockedSample = this.lastCompletedDebugSample;
                return new OpaqueDrawSubmission(visibleCount, DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "indirect_generated_per_section"), -1L, 0, blockedSample.sampledCommandCount(), blockedSample.invalidSampledCommandCount(), blockedSample.sampledQuadCount(), this.debugSamplePending, submitReason);
            }
        }
        boolean controlledSmokeIndirectDrawPath = controlledSmoke.safe() && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && controlledRenderListDiagnosticEnabled();
        if (controlledSmokeGatingActive && !this.controlledSmokeCommandCanSubmit) {
            VulkanBerylDebugLog.warnRateLimited("controlled-smoke-stale-indirect-draw-blocked", "stale indirect draw blocked while controlled smoke validation incomplete: anyVkCmdDrawIndirectRecorded=false"
                    + ", anyVkCmdDrawRecorded=" + this.anyVkCmdDrawRecordedThisFrame
                    + ", drawRecordedReason=stale_indirect_draw_blocked"
                    + ", controlledSmokeCommandCanSubmit=false"
                    + ", controlledSmokeCommandValidationState=" + this.controlledSmokeCommandValidationState
                    + ", drawSubmitReason=" + this.controlledSmokeCommandDrawSubmitReason);
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, this.controlledSmokeCommandDrawSubmitReason);
            logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, false, 0, this.controlledSmokeCommandDrawSubmitReason, noDrawCountFullCmdgen);
            logScreenspaceSmokeSubmitDiagnostics(false, this.controlledSmokeCommandDrawSubmitReason, controlledSmokeCommandValidation);
            DrawCommandDebugSample blockedSample = this.lastCompletedDebugSample;
            return new OpaqueDrawSubmission(visibleCount, DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "indirect_generated_per_section"), -1L, 0, blockedSample.sampledCommandCount(), blockedSample.invalidSampledCommandCount(), blockedSample.sampledQuadCount(), this.debugSamplePending, this.controlledSmokeCommandDrawSubmitReason);
        }
        logSectionDrawRenderTargetPathDiagnostics(renderer, viewport, commandBuffer, gpuCountedIndirectDraw ? "before_indirect_draw_count" : "before_indirect_draw", indirectDrawCommandBuffer, effectiveIndirectDrawCount);
        long realQuadDiagnosticStartNanos = System.nanoTime();
        logRealLodVertexPathClipspaceProbeDiagnostic(controlledSmoke, effectiveIndirectDrawCount);
        ControlledRenderListSmoke diagnosticSmoke = controlledSmoke.safe() ? controlledSmoke : cpuSelectionSmoke;
        logRealLodSingleQuadWorldProbeDiagnostic(viewport, geometryData, renderList, diagnosticSmoke, effectiveIndirectDrawCount);
        logRealQuadReadClipspaceProbeDiagnostic(viewport, geometryData, renderList, diagnosticSmoke, effectiveIndirectDrawCount);
        logFirstSubmittedRealQuadDiagnostics(viewport, geometryData, renderList, diagnosticSmoke, effectiveIndirectDrawCount);
        addDiagnosticCpuNanos(System.nanoTime() - realQuadDiagnosticStartNanos);
        applyRealLodSingleQuadWorldProbeSafeState(renderer, viewport, commandBuffer);
        logRealLodSingleQuadGpuVertexProofDiagnostic(diagnosticSmoke, indirectDrawCommandBuffer, effectiveIndirectDrawCount);
        prepareRealLodGpuDecodeParityWrite(commandBuffer, diagnosticSmoke);
        boolean cpuMirrorDrawCommands = false;
        int cpuMirrorCommandCount = 0;
        if (ENABLE_LODS && CMDGEN_DEBUG_READBACK && gpuCountedIndirectDraw
                && effectiveIndirectDrawCount > 0
                && geometryData.getSectionMetadataMirrorWriteCount() > 0L) {
            cpuMirrorCommandCount = buildIndirectCommandsFromCpuMirror(
                    commandBuffer, this.drawCommandBuffer, geometryData, renderList, effectiveIndirectDrawCount);
            cpuMirrorDrawCommands = cpuMirrorCommandCount > 0;
        }
        if (cpuMirrorDrawCommands) {
            VK10.vkCmdDrawIndirect(commandBuffer, this.drawCommandBuffer.getId(), 0L, cpuMirrorCommandCount, DRAW_COMMAND_STRIDE_BYTES);
        } else if (gpuCountedIndirectDraw) {
            VK12.vkCmdDrawIndirectCount(commandBuffer, indirectDrawCommandBuffer.getId(), 0L, this.drawCountBuffer.getId(), 0L, effectiveIndirectDrawCount, DRAW_COMMAND_STRIDE_BYTES);
        } else {
            VK10.vkCmdDrawIndirect(commandBuffer, indirectDrawCommandBuffer.getId(), 0L, effectiveIndirectDrawCount, DRAW_COMMAND_STRIDE_BYTES);
        }
        int activeDrawCount = cpuMirrorDrawCommands ? cpuMirrorCommandCount : effectiveIndirectDrawCount;
        scheduleRealLodGpuDecodeParityReadback(commandBuffer, diagnosticSmoke, viewport.frameId, activeDrawCount);
        this.anyVkCmdDrawIndirectRecordedThisFrame = true;
        this.drawRecordedReasonThisFrame = cpuMirrorDrawCommands ? "cpu_mirror_indirect_draw" : (DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (controlledSmokeIndirectDrawPath ? "controlled_smoke_indirect_draw" : (gpuCountedIndirectDraw ? "normal_indirect_count_draw" : "normal_indirect_draw")));
        if (SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE && !REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) {
            VK10.vkCmdDraw(commandBuffer, SAME_PASS_SCREENSPACE_PROBE_VERTEX_COUNT, SAME_PASS_SCREENSPACE_PROBE_INSTANCE_COUNT, SAME_PASS_SCREENSPACE_PROBE_FIRST_VERTEX, SAME_PASS_SCREENSPACE_PROBE_FIRST_INSTANCE);
            this.anyVkCmdDrawRecordedThisFrame = true;
            VulkanBerylDebugLog.rateLimited("section-draw-same-pass-screenspace-probe-recorded", "Section draw same-pass screenspace probe recorded: sectionDrawSamePassScreenspaceProbe=true"
                    + ", probeVertexCount=" + SAME_PASS_SCREENSPACE_PROBE_VERTEX_COUNT
                    + ", probeInstanceCount=" + SAME_PASS_SCREENSPACE_PROBE_INSTANCE_COUNT
                    + ", probeFirstInstance=0x" + Integer.toHexString(SAME_PASS_SCREENSPACE_PROBE_FIRST_INSTANCE)
                    + ", samePassSamePipelineSameDescriptors=true"
                    + ", expectedColour=" + (realLodMagentaDiagnosticEnabled() ? "magenta" : "debug_hash_colour"), 60);
        }
        if (SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER && !this.finalPassScreenspaceMarkerRecorded) {
            applyFinalPassScreenspaceMarkerStateOverride();
            VK10.vkCmdDraw(commandBuffer, FINAL_PASS_SCREENSPACE_MARKER_VERTEX_COUNT, FINAL_PASS_SCREENSPACE_MARKER_INSTANCE_COUNT, FINAL_PASS_SCREENSPACE_MARKER_FIRST_VERTEX, FINAL_PASS_SCREENSPACE_MARKER_FIRST_INSTANCE);
            this.anyVkCmdDrawRecordedThisFrame = true;
            this.finalPassScreenspaceMarkerRecorded = true;
            VulkanBerylDebugLog.always("Section draw final-pass screenspace marker recorded: env=VOXY_VULKAN_BERYL_SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER, oneFrame=true"
                    + ", sectionDrawFinalPassMarkerPersistent=true"
                    + ", markerRecordedAfterNormalSectionDraw=true"
                    + ", markerVertexCount=" + FINAL_PASS_SCREENSPACE_MARKER_VERTEX_COUNT
                    + ", markerInstanceCount=" + FINAL_PASS_SCREENSPACE_MARKER_INSTANCE_COUNT
                    + ", markerFirstInstance=0x" + Integer.toHexString(FINAL_PASS_SCREENSPACE_MARKER_FIRST_INSTANCE)
                    + ", expectedColour=magenta"
                    + ", passContext=" + SECTION_DRAW_PASS_CONTEXT.get());
        }
        String productionSubmitReason = productionDrawSubmitReason(cmdgenSampleValid, visibleCount);
        logScreenspaceSmokeSubmitDiagnostics(true, productionSubmitReason, controlledSmokeCommandValidation);
        logDrawSubmitHandoffDiagnostic(visibleCount, javaDrawCountForNoDrawCountCmdgen, cmdgenDispatchSubmitted, cmdgenDispatchGroupCount, indirectAllowed, true, effectiveIndirectDrawCount, productionSubmitReason, noDrawCountFullCmdgen);
        logTestStatus(testTraversalStageLimit, testStageMeaning, rawVisibleCount, visibleCount,
                testProbeSelected, testProbeEnvName, testSelectedShader, testIsolationMode,
                cmdgenDispatchCallRecorded, cmdgenPostDispatchBarrierRecorded,
                cmdgenDispatchSubmitted, cmdgenDispatchGroupCount,
                !cmdgenDispatchSubmitted, cmdgenDispatchSubmitted ? null : "cmdgen_dispatch_not_submitted",
                indirectAllowed, true, effectiveIndirectDrawCount, productionSubmitReason);
        publishDiagnosticCpuTiming();
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        long submittedQuadCount = sample.sampledQuadCount >= 0L ? sample.sampledQuadCount : -1L;
        return new OpaqueDrawSubmission(visibleCount, DRAW_SCREENSPACE_SMOKE_INDIRECT ? "screenspace_smoke_indirect_draw" : (DRAW_WORLDSPACE_SMOKE_INDIRECT ? "worldspace_smoke_indirect_draw" : "indirect_generated_per_section"), submittedQuadCount, effectiveIndirectDrawCount, sample.sampledCommandCount, sample.invalidSampledCommandCount, sample.sampledQuadCount, this.debugSamplePending, null);
    }


    private void prepareRealLodGpuDecodeParityWrite(VkCommandBuffer commandBuffer, ControlledRenderListSmoke diagnosticSmoke) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE || !diagnosticSmoke.safe() || this.realLodGpuDecodeParityPending) return;
        ensureRealLodGpuDecodeParityBuffers();
        if (this.realLodGpuDecodeParityBuffer == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdFillBuffer(commandBuffer, this.realLodGpuDecodeParityBuffer.getId(), 0L, REAL_LOD_GPU_DECODE_PARITY_BYTES, 0);
            VkMemoryBarrier.Buffer transferToVertex = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0, transferToVertex, null, null);
        }
    }


    private void scheduleRealLodGpuDecodeParityReadback(VkCommandBuffer commandBuffer, ControlledRenderListSmoke diagnosticSmoke, int frameId, int effectiveIndirectDrawCount) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE || !diagnosticSmoke.safe() || effectiveIndirectDrawCount <= 0 || this.realLodGpuDecodeParityPending) return;
        ensureRealLodGpuDecodeParityBuffers();
        if (this.realLodGpuDecodeParityBuffer == null || this.realLodGpuDecodeParityReadbackBuffer == null || this.realLodGpuDecodeParityReadbackBuffer.getDataPtr() == 0L) {
            this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("readback_buffer_unavailable");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer shaderToTransfer = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, shaderToTransfer, null, null);

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0L).dstOffset(0L).size(REAL_LOD_GPU_DECODE_PARITY_BYTES);
            VK10.vkCmdCopyBuffer(commandBuffer, this.realLodGpuDecodeParityBuffer.getId(), this.realLodGpuDecodeParityReadbackBuffer.getId(), copyRegion);

            VkMemoryBarrier.Buffer transferToHost = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, transferToHost, null, null);
        }
        this.realLodGpuDecodeParityPending = true;
        this.realLodGpuDecodeParityCompleted = false;
        this.realLodGpuDecodeParityCopyRecorded = true;
        long shaderFrameId = Integer.toUnsignedLong(frameId & 0x7fffffff);
        this.realLodGpuDecodeParityPendingFrameId = shaderFrameId;
        this.realLodGpuDecodeParityPendingRendererFrameSlot = safeRendererFrameSlot();
        this.realLodGpuDecodeParityPendingSectionId = diagnosticSmoke.selectedVisibilityCandidateSectionId() >= 0
                ? diagnosticSmoke.selectedVisibilityCandidateSectionId()
                : diagnosticSmoke.sectionId();
        this.realLodGpuDecodeParityPendingQuadIndex = diagnosticSmoke.selectedVisibilityCandidateQuadIndex() >= 0L
                ? diagnosticSmoke.selectedVisibilityCandidateQuadIndex()
                : Integer.toUnsignedLong(diagnosticSmoke.quadStart());
        CpuDecodeParitySnapshot cpuSnapshot = this.realLodGpuDecodeParityCurrentCpuSnapshot;
        if (cpuSnapshot == null || !cpuSnapshot.available()
                || cpuSnapshot.frameId() != shaderFrameId
                || cpuSnapshot.selectedSectionId() != this.realLodGpuDecodeParityPendingSectionId
                || cpuSnapshot.selectedQuadIndex() != this.realLodGpuDecodeParityPendingQuadIndex) {
            cpuSnapshot = CpuDecodeParitySnapshot.unavailable("cpu_snapshot_unavailable_or_changed");
        }
        this.realLodGpuDecodeParityPendingCpuSnapshot = cpuSnapshot;
        this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("pending_gpu_completion");
    }


    private void consumeRealLodGpuDecodeParityReadbackIfReady(long currentFrameId) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE || !this.realLodGpuDecodeParityPending || this.realLodGpuDecodeParityCompleted) return;
        if (!this.realLodGpuDecodeParityCopyRecorded) {
            this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("copy_not_recorded");
            return;
        }
        long ageFrames = this.realLodGpuDecodeParityPendingFrameId < 0L || currentFrameId < 0L ? -1L : Math.max(0L, currentFrameId - this.realLodGpuDecodeParityPendingFrameId);
        if (ageFrames == 0L) {
            this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("too_young_same_frame");
            return;
        }
        if (ageFrames < 0L) {
            this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("pending_gpu_completion");
            return;
        }
        long ptr = this.realLodGpuDecodeParityReadbackBuffer == null ? 0L : this.realLodGpuDecodeParityReadbackBuffer.getDataPtr();
        if (ptr == 0L) {
            this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.unavailable("readback_buffer_unavailable");
            return;
        }
        int[] words = new int[REAL_LOD_GPU_DECODE_PARITY_WORDS];
        for (int i = 0; i < words.length; i++) {
            words[i] = MemoryUtil.memGetInt(ptr + (long) i * Integer.BYTES);
        }
        this.realLodGpuDecodeParitySnapshot = GpuDecodeParitySnapshot.fromWords(words);
        this.realLodGpuDecodeParityCompleted = true;
        this.realLodGpuDecodeParityPending = false;
    }


    private static void applyOpaquePipelineState() {
        VRenderSystem.disableBlend();
        VRenderSystem.disableCull();
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.depthFunc(515);
    }


    private static void applyTranslucentPipelineState() {
        VRenderSystem.enableBlend();
        VRenderSystem.blendFuncSeparate(770, 771, 1, 771);
        VRenderSystem.disableCull();
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.depthFunc(515);
    }


    private void logRealLodVertexPathClipspaceProbeDiagnostic(ControlledRenderListSmoke controlledSmoke, int effectiveIndirectDrawCount) {
        if (!REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE) return;
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        boolean commandSampleAvailable = sample.sampledCommandCount() > 0;
        long commandVertexCount = commandSampleAvailable ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L;
        long commandFirstVertex = commandSampleAvailable ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L;
        long commandFirstInstance = commandSampleAvailable ? Integer.toUnsignedLong(sample.firstFirstInstance()) : -1L;
        long expectedOpaqueBaseVertex = 0L;
        long expectedVertexCount = controlledSmoke.safe() ? controlledSmoke.quadCount() * 6L : -1L;
        String vertexIndexSemanticsProbe = controlledSmoke.safe()
                ? "visible_left_means_gl_VertexIndex_includes_firstVertex;visible_right_means_gl_VertexIndex_local_zero_based;no_magenta_means_real_lod_indirect_vertex_path_or_topology_or_builtin_semantics_still_not_producing_pixels"
                : "controlled_render_list_not_safe";
        String instanceIndexMapping = commandSampleAvailable && commandFirstInstance == 0L
                ? "expected_render_list_entry0"
                : (commandSampleAvailable ? "unexpected_firstInstance_" + commandFirstInstance : "command_sample_unavailable");
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-vertex-path-clipspace-probe", "Section draw real LOD vertex path clip-space probe: enabled=true"
                + ", normalIndirectDrawPath=true"
                + ", separateVkCmdDrawProbe=false"
                + ", samePassScreenspaceProbeRecorded=false"
                + ", forcedClipspaceOutput=small_magenta_quad_or_triangle_spread_by_gl_InstanceIndex_low3_bits"
                + ", expectedIfVisible=real_lod_vertex_shader_invocation_executed_remaining_bug_geometry_transform_or_indexing"
                + ", expectedIfNotVisible=real_lod_indirect_vertex_path_primitive_topology_vertex_count_or_builtin_semantics_issue"
                + ", firstInstanceConventionProbe=multi_command_probe_centers_should_spread_horizontally_when_gl_InstanceIndex_includes_firstInstance"
                + ", firstInstanceConventionProbeIfStacked=gl_InstanceIndex_may_be_zero_for_all_indirect_commands_or_only_one_command_was_submitted"
                + ", glVertexIndexSemanticsVisualCue=" + vertexIndexSemanticsProbe
                + ", glInstanceIndexMapping=" + instanceIndexMapping
                + ", controlledSmokeSafe=" + controlledSmoke.safe()
                + ", controlledSmokeSectionId=" + controlledSmoke.sectionId()
                + ", controlledSmokeQuadStart=" + (controlledSmoke.safe() ? Integer.toUnsignedLong(controlledSmoke.quadStart()) : -1L)
                + ", controlledSmokeQuadCount=" + controlledSmoke.quadCount()
                + ", expectedOpaqueBaseVertex=" + expectedOpaqueBaseVertex
                + ", expectedVertexCount=" + expectedVertexCount
                + ", submittedDrawCount=" + effectiveIndirectDrawCount
                + ", sampledCommandAvailable=" + commandSampleAvailable
                + ", sampledCommand0.vertexCount=" + commandVertexCount
                + ", sampledCommand0.firstVertex=" + commandFirstVertex
                + ", sampledCommand0.firstInstance=" + commandFirstInstance
                + ", sampledCommand0.firstVertexMatchesExpectedOpaqueBaseVertex=" + (commandSampleAvailable && expectedOpaqueBaseVertex >= 0L && commandFirstVertex == expectedOpaqueBaseVertex)
                + ", sampledCommand0.firstInstanceExpectedRenderListEntry0=" + (commandSampleAvailable && commandFirstInstance == 0L), 60);
    }


    private void logRealQuadReadClipspaceProbeDiagnostic(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke, int effectiveIndirectDrawCount) {
        if (!REAL_QUAD_READ_CLIPSPACE_PROBE) return;
        RealQuadDiagnostic diagnostic = firstSubmittedRealQuadDiagnostic(viewport, geometryData, renderList, controlledSmoke, false);
        VulkanBerylDebugLog.rateLimited("section-draw-real-quad-read-clipspace-probe", "section draw real_quad_read_clipspace_probe: enabled=true"
                + ", preprocessedShaderDefinePresent=" + this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine
                + ", preprocessedShaderBranchPresent=" + this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeBranch
                + ", normalIndirectDrawPath=true"
                + ", readsSectionIdViaFirstInstanceRenderListConvention=true"
                + ", computesPassQuadStart=true"
                + ", readsQuadData=true"
                + ", callsSetupQuad=true"
                + ", forcedClipspaceOutput=fixed_quad_varied_by_decoded_quad_data"
                + ", expectedIfVisible=quadData_binding_and_decode_ok_remaining_bug_world_transform_mvp_or_depth_cull"
                + ", expectedIfNotVisible=quadData_indexing_decode_or_descriptor_binding_issue"
                + ", submittedDrawCount=" + effectiveIndirectDrawCount
                + diagnostic.logFields(), 60);
    }


    private void logRealLodSingleQuadWorldProbeDiagnostic(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke, int effectiveIndirectDrawCount) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return;
        RealQuadDiagnostic diagnostic = firstSubmittedRealQuadDiagnostic(viewport, geometryData, renderList, controlledSmoke, true);
        this.realLodGpuDecodeParityCurrentCpuSnapshot = CpuDecodeParitySnapshot.fromDiagnostic(viewport.frameId, diagnostic, geometryData, this.realLodProbeSelectedSectionPassQuadStart);
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-single-quad-world-probe", "section draw real LOD single-quad world probe: enabled=true"
                + ", normalIndirectDrawPath=true"
                + ", forceSingleSubmittedCommand=true"
                + ", forceSingleDecodedQuad=true"
                + ", realLodProbeGpuVertexMode=" + realLodProbeGpuVertexMode()
                + ", realLodProbeGpuVertexProofAvailable=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE
                + ", realLodProbeShaderExpectedFirstVertex=0"
                + ", realLodProbeShaderExpectedFirstInstance=0"
                + ", realLodProbeShaderExpectedQuadIndexFormula=" + (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? "sectionId=realLodProbeData.y_cpu_selected_section;quadIndex=realLodProbeData.x_cpu_selected_absolute_quad" : (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD ? "quadIndex=realLodProbeData.x_cpu_selected_absolute_quad" : "passQuadStart_plus_gl_VertexIndex_div_6"))
                + ", realLodProbeCpuSelectedQuadIndex=" + diagnostic.quadIndex()
                + ", realLodProbeCpuSelectedQuadIndexSpace=absolute_geometry_quad_index"
                + ", realLodProbeCpuSelectedSectionId=" + diagnostic.sectionId()
                + ", realLodProbeSelectedSectionPassQuadStart=" + this.realLodProbeSelectedSectionPassQuadStart
                + ", realLodProbeForceCpuSectionAndQuadEnabled=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD
                + ", realLodProbeShaderForcedSectionId=" + (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? this.realLodProbeCpuSelectedSectionId : -1)
                + ", realLodProbeShaderForcedQuadIndex=" + ((REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD || REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) ? Integer.toUnsignedLong(this.realLodProbeSceneUniformForcedQuadIndex) : -1L)
                + ", realLodProbeShaderForcedPassQuadStart=" + (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? this.realLodProbeSelectedSectionPassQuadStart : -1L)
                + ", realLodProbeShaderForcedQuadIndexSpace=" + ((REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD || REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) ? "absolute_geometry_quad_index" : "not_applicable")
                + ", realLodProbeShaderStillUsesRealDecodePath=" + (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD)
                + ", realLodProbeShaderBypassesIndirectLookup=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD
                + ", realLodProbeHardcodedClipspaceEnabled=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE
                + ", realLodProbeReplayCpuClipAvailable=" + this.realLodProbeReplayCpuClipAvailable
                + ", realLodProbeReplayCpuClip0=" + this.realLodProbeReplayCpuClip0
                + ", realLodProbeReplayCpuClip1=" + this.realLodProbeReplayCpuClip1
                + ", realLodProbeReplayCpuClip2=" + this.realLodProbeReplayCpuClip2
                + ", realLodProbeReplayCpuClip3=" + this.realLodProbeReplayCpuClip3
                + ", realLodProbeReplayCpuClipSource=cpu_final_clip"
                + ", realLodProbeReplayCpuClipUsesSameSubmittedDrawCount=true"
                + ", realLodProbeReplayCpuClipUsesSamePipeline=true"
                + ", realLodProbeReplayCpuClipUsesSameDescriptorSets=true"
                + ", realLodProbeReplayCpuClipUniformUploadPath=SceneUniform.binding0.vkCmdUpdateBuffer"
                + ", realLodProbeReplayCpuClipUniformLayout=std140 SceneUniform offsets: realLodProbeData@" + SCENE_UNIFORM_REAL_LOD_PROBE_DATA_OFFSET_BYTES + " uvec4 flags.w bit1=replay_available, replayCpuClip0..3@" + SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_CLIP_OFFSET_BYTES + "/128/144/160 vec4, sizeBytes=" + SCENE_UNIFORM_SIZE_BYTES
                + ", realLodProbeReplayCpuWorldAvailable=" + this.realLodProbeReplayCpuWorldAvailable
                + ", realLodProbeReplayCpuWorld0=" + this.realLodProbeReplayCpuWorld0
                + ", realLodProbeReplayCpuWorld1=" + this.realLodProbeReplayCpuWorld1
                + ", realLodProbeReplayCpuWorld2=" + this.realLodProbeReplayCpuWorld2
                + ", realLodProbeReplayCpuWorld3=" + this.realLodProbeReplayCpuWorld3
                + ", realLodProbeReplayCpuWorldCoordinateSpace=" + this.realLodProbeReplayCpuWorldCoordinateSpace
                + ", realLodProbeReplayCpuWorldUnavailableReason=" + this.realLodProbeReplayCpuWorldUnavailableReason
                + ", realLodProbeReplayCpuWorldUsesSameMvp=true"
                + ", realLodProbeReplayCpuWorldUsesSameSubmittedDrawCount=true"
                + ", realLodProbeReplayCpuWorldUsesSamePipeline=true"
                + ", realLodProbeReplayCpuWorldUsesSameDescriptorSets=true"
                + ", realLodProbeReplayCpuWorldUniformLayout=" + realLodProbeUniformLayoutSummary()
                + realLodGpuDecodeParityLogFields(viewport.frameId, diagnostic, geometryData)
                + ", usesGetQuadCornerPosPath=" + (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD)
                + ", cullDisabled=true"
                + ", depthDisabled=true"
                + ", forcedFragmentColour=magenta"
                + ", preprocessedShaderDefinePresent=" + this.sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine
                + ", preprocessedShaderBranchPresent=" + this.sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch
                + ", submittedDrawCount=" + effectiveIndirectDrawCount
                + ", selectedSectionId=" + diagnostic.sectionId()
                + ", selectedQuadIndex=" + diagnostic.quadIndex()
                + ", selectedReason=" + controlledSmoke.selectionStrategy()
                + ", finalGlPosition0=" + diagnostic.clip0()
                + ", finalGlPosition1=" + diagnostic.clip1()
                + ", finalGlPosition2=" + diagnostic.clip2()
                + ", finalGlPosition3=" + diagnostic.clip3()
                + ", finalClipLooksVisible=" + diagnostic.intersectsClipSpace()
                + ", renderPassFailureClaim=" + (diagnostic.intersectsClipSpace() ? "not_applicable" : "not_claimed_final_clip_not_visible")
                + ", visibilityCandidateCount=" + controlledSmoke.visibilityCandidateCount()
                + ", visibilityCandidateTestedCount=" + controlledSmoke.visibilityCandidateTestedCount()
                + ", visibilityCandidateClipVisibleCount=" + controlledSmoke.visibilityCandidateClipVisibleCount()
                + ", selectedVisibilityCandidateSectionId=" + controlledSmoke.selectedVisibilityCandidateSectionId()
                + ", selectedVisibilityCandidateQuadIndex=" + controlledSmoke.selectedVisibilityCandidateQuadIndex()
                + ", selectedVisibilityCandidateClipLooksVisible=" + controlledSmoke.selectedVisibilityCandidateClipLooksVisible()
                + ", selectedVisibilityCandidateRejectReason=" + controlledSmoke.selectedVisibilityCandidateRejectReason()
                + ", fallbackUsedOnlyAfterNoClipVisibleCandidate=" + controlledSmoke.fallbackUsedOnlyAfterNoClipVisibleCandidate()
                + diagnostic.logFields(true), 60);
    }


    private void logRealLodSingleQuadGpuVertexProofDiagnostic(ControlledRenderListSmoke controlledSmoke, Buffer indirectDrawCommandBuffer, int effectiveIndirectDrawCount) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return;
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        boolean commandSampleAvailable = sample.sampledCommandCount() > 0;
        long sampledVertexCount = commandSampleAvailable ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L;
        long sampledFirstVertex = commandSampleAvailable ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L;
        long sampledFirstInstance = commandSampleAvailable ? Integer.toUnsignedLong(sample.firstFirstInstance()) : -1L;
        long cpuSelectedQuadIndex = controlledSmoke.safe() && controlledSmoke.selectedVisibilityCandidateQuadIndex() >= 0L
                ? controlledSmoke.selectedVisibilityCandidateQuadIndex()
                : (controlledSmoke.safe() ? Integer.toUnsignedLong(controlledSmoke.quadStart()) : -1L);
        int cpuSelectedSectionId = controlledSmoke.safe() && controlledSmoke.selectedVisibilityCandidateSectionId() >= 0
                ? controlledSmoke.selectedVisibilityCandidateSectionId()
                : controlledSmoke.sectionId();
        boolean proofAvailable = REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE
                && this.sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine
                && this.sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch
                && effectiveIndirectDrawCount > 0;
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-gpu-vertex-proof", "section draw real LOD single-quad GPU vertex proof:"
                + " realLodProbeGpuVertexMode=" + realLodProbeGpuVertexMode()
                + ", realLodProbeGpuVertexProofAvailable=" + proofAvailable
                + ", realLodProbeShaderExpectedFirstVertex=0"
                + ", realLodProbeShaderExpectedFirstInstance=0"
                + ", realLodProbeShaderExpectedQuadIndexFormula=" + (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? "sectionId=realLodProbeData.y_cpu_selected_section;quadIndex=realLodProbeData.x_cpu_selected_absolute_quad;triVertex=gl_VertexIndex%6u;cornerId=0,1,2,2,1,3" : (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD ? "quadIndex=realLodProbeData.x_cpu_selected_absolute_quad;triVertex=gl_VertexIndex%6u;cornerId=0,1,2,2,1,3" : "quadIndex=drawExtractPassQuadStart(sectionData[indirectLookup[gl_InstanceIndex]])+(gl_VertexIndex/6u);triVertex=gl_VertexIndex%6u;cornerId=0,1,2,2,1,3"))
                + ", realLodProbeCpuSelectedQuadIndex=" + cpuSelectedQuadIndex
                + ", realLodProbeCpuSelectedQuadIndexSpace=absolute_geometry_quad_index"
                + ", realLodProbeCpuSelectedSectionId=" + cpuSelectedSectionId
                + ", realLodProbeSelectedSectionPassQuadStart=" + this.realLodProbeSelectedSectionPassQuadStart
                + ", realLodProbeForceCpuSectionAndQuadEnabled=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD
                + ", realLodProbeShaderForcedSectionId=" + (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? this.realLodProbeCpuSelectedSectionId : -1)
                + ", realLodProbeShaderForcedQuadIndex=" + ((REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD || REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) ? Integer.toUnsignedLong(this.realLodProbeSceneUniformForcedQuadIndex) : -1L)
                + ", realLodProbeShaderForcedPassQuadStart=" + (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? this.realLodProbeSelectedSectionPassQuadStart : -1L)
                + ", realLodProbeShaderForcedQuadIndexSpace=" + ((REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD || REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) ? "absolute_geometry_quad_index" : "not_applicable")
                + ", realLodProbeShaderStillUsesRealDecodePath=" + (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD)
                + ", realLodProbeShaderBypassesIndirectLookup=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD
                + ", realLodProbeHardcodedClipspaceEnv=VOXY_VULKAN_BERYL_REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE"
                + ", realLodProbeHardcodedClipspaceEnabled=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE_HARDCODED_CLIPSPACE
                + ", realLodProbeReplayCpuClipAvailable=" + this.realLodProbeReplayCpuClipAvailable
                + ", realLodProbeReplayCpuClip0=" + this.realLodProbeReplayCpuClip0
                + ", realLodProbeReplayCpuClip1=" + this.realLodProbeReplayCpuClip1
                + ", realLodProbeReplayCpuClip2=" + this.realLodProbeReplayCpuClip2
                + ", realLodProbeReplayCpuClip3=" + this.realLodProbeReplayCpuClip3
                + ", realLodProbeReplayCpuClipSource=cpu_final_clip"
                + ", realLodProbeReplayCpuClipUsesSameSubmittedDrawCount=true"
                + ", realLodProbeReplayCpuClipUsesSamePipeline=true"
                + ", realLodProbeReplayCpuClipUsesSameDescriptorSets=true"
                + ", realLodProbeReplayCpuClipUniformUploadPath=SceneUniform.binding0.vkCmdUpdateBuffer"
                + ", realLodProbeReplayCpuClipUniformLayout=std140 SceneUniform offsets: realLodProbeData@" + SCENE_UNIFORM_REAL_LOD_PROBE_DATA_OFFSET_BYTES + " uvec4 flags.w bit1=replay_available, replayCpuClip0..3@" + SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_CLIP_OFFSET_BYTES + "/128/144/160 vec4, sizeBytes=" + SCENE_UNIFORM_SIZE_BYTES
                + ", realLodProbeReplayCpuWorldAvailable=" + this.realLodProbeReplayCpuWorldAvailable
                + ", realLodProbeReplayCpuWorld0=" + this.realLodProbeReplayCpuWorld0
                + ", realLodProbeReplayCpuWorld1=" + this.realLodProbeReplayCpuWorld1
                + ", realLodProbeReplayCpuWorld2=" + this.realLodProbeReplayCpuWorld2
                + ", realLodProbeReplayCpuWorld3=" + this.realLodProbeReplayCpuWorld3
                + ", realLodProbeReplayCpuWorldCoordinateSpace=" + this.realLodProbeReplayCpuWorldCoordinateSpace
                + ", realLodProbeReplayCpuWorldUnavailableReason=" + this.realLodProbeReplayCpuWorldUnavailableReason
                + ", realLodProbeReplayCpuWorldUsesSameMvp=true"
                + ", realLodProbeReplayCpuWorldUsesSameSubmittedDrawCount=true"
                + ", realLodProbeReplayCpuWorldUsesSamePipeline=true"
                + ", realLodProbeReplayCpuWorldUsesSameDescriptorSets=true"
                + ", realLodProbeReplayCpuWorldUniformLayout=" + realLodProbeUniformLayoutSummary()
                + ", realLodProbeUsesSamePipelineAsRealDecodedQuadPath=true"
                + ", realLodProbeUsesSameDescriptorSetsAsRealDecodedQuadPath=true"
                + ", realLodProbeUsesSameIndirectCommandBufferAsRealDecodedQuadPath=true"
                + ", realLodProbeUsesSameSubmittedDrawCountAsRealDecodedQuadPath=true"
                + ", realLodProbePipelineMatchesBoundPipeline=" + (this.graphicsPipeline != null)
                + ", realLodProbeDescriptorSetsBound=" + (this.graphicsPipeline != null && this.resourcesBound)
                + ", realLodProbeIndirectCommandBufferId=" + (indirectDrawCommandBuffer == null ? 0L : indirectDrawCommandBuffer.getId())
                + ", submittedDrawCount=" + effectiveIndirectDrawCount
                + ", sampledCommandAvailable=" + commandSampleAvailable
                + ", sampledCommand0.vertexCount=" + sampledVertexCount
                + ", sampledCommand0.firstVertex=" + sampledFirstVertex
                + ", sampledCommand0.firstInstance=" + sampledFirstInstance
                + ", sampledCommand0.firstVertexMatchesShaderExpected=" + (commandSampleAvailable && sampledFirstVertex == 0L)
                + ", sampledCommand0.firstInstanceMatchesShaderExpected=" + (commandSampleAvailable && sampledFirstInstance == 0L)
                + ", hardcodedClipspaceProofExpectedIfVisible=raster_output_fragment_ok_bug_is_real_decoded_vertex_position_or_quad_index_logic"
                + ", hardcodedClipspaceProofExpectedIfInvisible=single_quad_shader_branch_or_bound_draw_variant_not_executing", 60);
    }


    private void logFirstSubmittedRealQuadDiagnostics(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke, int effectiveIndirectDrawCount) {
        if (!REAL_LOD_VISIBILITY_DIAGNOSTIC && !REAL_QUAD_READ_CLIPSPACE_PROBE && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return;
        RealQuadDiagnostic diagnostic = firstSubmittedRealQuadDiagnostic(viewport, geometryData, renderList, controlledSmoke, REAL_LOD_SINGLE_QUAD_WORLD_PROBE);
        VulkanBerylDebugLog.rateLimited("section-draw-first-submitted-real-quad", "section draw first submitted real quad diagnostics:"
                + ", diagnosticOnly=true"
                + ", submittedDrawCount=" + effectiveIndirectDrawCount
                + diagnostic.logFields(REAL_LOD_SINGLE_QUAD_WORLD_PROBE), 60);
    }


    private RealQuadDiagnostic firstSubmittedRealQuadDiagnostic(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke, boolean geometryDiagnosticMode) {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        long sampledFirstInstance = sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstFirstInstance()) : 0L;
        long sampledVertexCount = sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L;
        long sampledFirstVertex = sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L;
        RenderListEntry0Diagnostics entry0 = renderListEntry0Diagnostics(geometryData, controlledSmoke);
        if (!entry0.valid()) {
            GeometryQuadDiagnostics geometryDiagnostic = geometryQuadDiagnostics(geometryData, -1L, viewport.frameId);
            return RealQuadDiagnostic.unavailable("render_list_entry0_unavailable", sampledFirstInstance, sampledVertexCount, sampledFirstVertex, geometryDiagnostic.logFields());
        }
        int sectionId = entry0.sectionId();
        int rawPosA = geometryData.getSectionMetadataInt(sectionId, 0);
        int rawPosB = geometryData.getSectionMetadataInt(sectionId, 1);
        int decodedLod = rawPosA >>> 28;
        long quadIndex = selectedGeometryDiagnosticQuadIndex(controlledSmoke, entry0);
        GeometryQuadDiagnostics geometryDiagnostic = geometryQuadDiagnostics(geometryData, quadIndex, viewport.frameId);
        QuadSample quad = sampleQuad(geometryData, quadIndex);
        ClipDiagnostics clip = clipDiagnostics(viewport, rawPosA, rawPosB, quad);
        ClipDiagnostics relativeClip = clipDiagnosticsForMode(viewport, rawPosA, rawPosB, quad, true);
        ClipDiagnostics absoluteClip = clipDiagnosticsForMode(viewport, rawPosA, rawPosB, quad, false);
        WorldCornerDiagnostics world = worldCornerDiagnostics(viewport, rawPosA, rawPosB, quad);
        String basePoint = quad.available() ? basePointDiagnostic(viewport, rawPosA, rawPosB, quad) : "unavailable";
        String sampleState = sample.sampledCommandCount() > 0 ? "completed" : (this.debugSamplePending ? "pending" : "unavailable");
        String status = quad.available() || !geometryDiagnosticMode ? "ok" : "geometry_quad_unavailable";
        return new RealQuadDiagnostic(status, sectionId, sampledFirstInstance, sampledVertexCount, sampledFirstVertex, quadIndex, quad.rawString(), decodedLod,
                controlledSmoke.selectionStrategy(), viewport.section.x + "," + viewport.section.y + "," + viewport.section.z,
                formatFloat(viewport.innerTranslation.x) + "," + formatFloat(viewport.innerTranslation.y) + "," + formatFloat(viewport.innerTranslation.z),
                basePoint, quad.available() ? formatFloat((float) (1 << decodedLod)) : "unavailable",
                quad.available() ? Integer.toString(quad.face() >> 1) : "unavailable",
                quad.available() ? Math.max(quad.sizeX(), 1) + "," + Math.max(quad.sizeY(), 1) : "unavailable",
                clip.clip0(), clip.clip1(), clip.clip2(), clip.clip3(), clip.anyFiniteW(), clip.looksVisible(), clip.behindCamera(), sampleState,
                relativeClip.clip0(), relativeClip.clip1(), relativeClip.clip2(), relativeClip.clip3(), relativeClip.looksVisible(),
                absoluteClip.clip0(), absoluteClip.clip1(), absoluteClip.clip2(), absoluteClip.clip3(), absoluteClip.looksVisible(),
                "relative_baseSection_innerTranslation", world.corner0(), world.corner1(), world.corner2(), world.corner3(), geometryDiagnostic.logFields());
    }


    private long selectedGeometryDiagnosticQuadIndex(ControlledRenderListSmoke controlledSmoke, CmdgenCommandSnapshot snapshot) {
        if (controlledSmoke.safe()) {
            return selectedGeometryDiagnosticQuadIndex(controlledSmoke, new RenderListEntry0Diagnostics(controlledSmoke.sectionId(), controlledSmoke.quadStart(), 0L, Integer.toUnsignedLong(controlledSmoke.quadStart()), controlledSmoke.quadCount(), true));
        }
        return snapshot.available() && snapshot.expectedFirstVertex() >= 0L ? snapshot.expectedFirstVertex() / 4L : -1L;
    }


    private static long selectedGeometryDiagnosticQuadIndex(ControlledRenderListSmoke controlledSmoke, RenderListEntry0Diagnostics entry0) {
        if (!entry0.valid()) return -1L;
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE && controlledSmoke.safe() && controlledSmoke.selectedVisibilityCandidateQuadIndex() >= 0L) {
            return controlledSmoke.selectedVisibilityCandidateQuadIndex();
        }
        return entry0.opaqueQuadStart();
    }


    private void updateRealLodProbeSceneUniformSelection(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, ControlledRenderListSmoke controlledSmoke) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE) {
            return;
        }
        this.realLodProbeCpuSelectedQuadIndex = -1L;
        this.realLodProbeCpuSelectedSectionId = -1;
        this.realLodProbeSelectedSectionPassQuadStart = -1L;
        this.realLodProbeSceneUniformForcedQuadIndex = 0;
        clearRealLodProbeReplayCpuClip(REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP ? "selection_unavailable" : "not_requested");
        clearRealLodProbeReplayCpuWorld(REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD ? "selection_unavailable" : "not_requested");
        if (!controlledSmoke.safe()) {
            return;
        }
        int sectionId = controlledSmoke.selectedVisibilityCandidateSectionId() >= 0
                ? controlledSmoke.selectedVisibilityCandidateSectionId()
                : controlledSmoke.sectionId();
        long quadIndex = controlledSmoke.selectedVisibilityCandidateQuadIndex() >= 0L
                ? controlledSmoke.selectedVisibilityCandidateQuadIndex()
                : Integer.toUnsignedLong(controlledSmoke.quadStart());
        this.realLodProbeCpuSelectedSectionId = sectionId;
        this.realLodProbeCpuSelectedQuadIndex = quadIndex;
        this.realLodProbeSelectedSectionPassQuadStart = selectedSectionPassQuadStart(geometryData, sectionId);
        if ((REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD || REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD) && quadIndex >= 0L && quadIndex <= 0xffffffffL) {
            this.realLodProbeSceneUniformForcedQuadIndex = (int) quadIndex;
        }
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP) {
            updateRealLodProbeReplayCpuClip(viewport, geometryData, controlledSmoke, sectionId, quadIndex);
        }
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD) {
            updateRealLodProbeReplayCpuWorld(viewport, geometryData, controlledSmoke, sectionId, quadIndex);
        }
    }


    private void clearRealLodProbeReplayCpuClip(String reason) {
        this.realLodProbeReplayCpuClipAvailable = false;
        java.util.Arrays.fill(this.realLodProbeReplayCpuClip, 0.0F);
        this.realLodProbeReplayCpuClip0 = "unavailable";
        this.realLodProbeReplayCpuClip1 = "unavailable";
        this.realLodProbeReplayCpuClip2 = "unavailable";
        this.realLodProbeReplayCpuClip3 = "unavailable";
        this.realLodProbeReplayCpuClipUnavailableReason = reason;
    }


    private void updateRealLodProbeReplayCpuClip(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, ControlledRenderListSmoke controlledSmoke, int sectionId, long quadIndex) {
        if (!controlledSmoke.safe()) {
            clearRealLodProbeReplayCpuClip("controlled_selection_unavailable");
            return;
        }
        if (sectionId < 0 || sectionId >= Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount()) || !geometryData.hasNonZeroSectionMetadata(sectionId)) {
            clearRealLodProbeReplayCpuClip("section_metadata_unavailable");
            return;
        }
        QuadSample quad = sampleQuad(geometryData, quadIndex);
        if (!quad.available()) {
            clearRealLodProbeReplayCpuClip("geometry_quad_unavailable_to_java_diagnostics");
            return;
        }
        ClipDiagnostics clip = clipDiagnostics(
                viewport,
                geometryData.getSectionMetadataInt(sectionId, 0),
                geometryData.getSectionMetadataInt(sectionId, 1),
                quad);
        if (!clip.hasRawClipValues()) {
            clearRealLodProbeReplayCpuClip("clip_values_unavailable");
            return;
        }
        for (int corner = 0; corner < 4; corner++) {
            org.joml.Vector4f vec = clip.clip(corner);
            int base = corner * 4;
            this.realLodProbeReplayCpuClip[base] = vec.x;
            this.realLodProbeReplayCpuClip[base + 1] = vec.y;
            this.realLodProbeReplayCpuClip[base + 2] = vec.z;
            this.realLodProbeReplayCpuClip[base + 3] = vec.w;
        }
        this.realLodProbeReplayCpuClipAvailable = true;
        this.realLodProbeReplayCpuClip0 = clip.clip0();
        this.realLodProbeReplayCpuClip1 = clip.clip1();
        this.realLodProbeReplayCpuClip2 = clip.clip2();
        this.realLodProbeReplayCpuClip3 = clip.clip3();
        this.realLodProbeReplayCpuClipUnavailableReason = "none";
    }


    private void clearRealLodProbeReplayCpuWorld(String reason) {
        this.realLodProbeReplayCpuWorldAvailable = false;
        java.util.Arrays.fill(this.realLodProbeReplayCpuWorld, 0.0F);
        this.realLodProbeReplayCpuWorld0 = "unavailable";
        this.realLodProbeReplayCpuWorld1 = "unavailable";
        this.realLodProbeReplayCpuWorld2 = "unavailable";
        this.realLodProbeReplayCpuWorld3 = "unavailable";
        this.realLodProbeReplayCpuWorldUnavailableReason = reason;
        this.realLodProbeReplayCpuWorldCoordinateSpace = "unavailable";
    }


    private void updateRealLodProbeReplayCpuWorld(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, ControlledRenderListSmoke controlledSmoke, int sectionId, long quadIndex) {
        if (!controlledSmoke.safe()) {
            clearRealLodProbeReplayCpuWorld("controlled_selection_unavailable");
            return;
        }
        if (sectionId < 0 || sectionId >= Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount()) || !geometryData.hasNonZeroSectionMetadata(sectionId)) {
            clearRealLodProbeReplayCpuWorld("section_metadata_unavailable");
            return;
        }
        QuadSample quad = sampleQuad(geometryData, quadIndex);
        if (!quad.available()) {
            clearRealLodProbeReplayCpuWorld("geometry_quad_unavailable_to_java_diagnostics");
            return;
        }
        WorldCornerDiagnostics world = worldCornerDiagnostics(
                viewport,
                geometryData.getSectionMetadataInt(sectionId, 0),
                geometryData.getSectionMetadataInt(sectionId, 1),
                quad);
        if (!world.hasRawWorldValues()) {
            clearRealLodProbeReplayCpuWorld("world_values_unavailable");
            return;
        }
        for (int corner = 0; corner < 4; corner++) {
            org.joml.Vector4f vec = world.corner(corner);
            int base = corner * 4;
            this.realLodProbeReplayCpuWorld[base] = vec.x;
            this.realLodProbeReplayCpuWorld[base + 1] = vec.y;
            this.realLodProbeReplayCpuWorld[base + 2] = vec.z;
            this.realLodProbeReplayCpuWorld[base + 3] = vec.w;
        }
        this.realLodProbeReplayCpuWorldAvailable = true;
        this.realLodProbeReplayCpuWorld0 = world.corner0();
        this.realLodProbeReplayCpuWorld1 = world.corner1();
        this.realLodProbeReplayCpuWorld2 = world.corner2();
        this.realLodProbeReplayCpuWorld3 = world.corner3();
        this.realLodProbeReplayCpuWorldCoordinateSpace = world.coordinateSpace();
        this.realLodProbeReplayCpuWorldUnavailableReason = "none";
    }


    private long selectedSectionPassQuadStart(VulkanBerylSectionGeometryData geometryData, int sectionId) {
        if (sectionId < 0 || sectionId >= Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount()) || !geometryData.hasNonZeroSectionMetadata(sectionId)) {
            return -1L;
        }
        return Integer.toUnsignedLong(geometryData.getSectionMetadataInt(sectionId, 3)) + extractTranslucentQuadCount(geometryData, sectionId);
    }



    private OpaqueDrawSubmission renderScreenspaceSmokeIndirectIsolated(Renderer renderer, VulkanBerylViewport viewport, VkCommandBuffer commandBuffer) {
        recordScreenspaceSmokeIndirectKnownCommand(viewport.frameId);
        VulkanBerylDebugLog.rateLimited("screenspace-smoke-normal-lod-skipped", "screenspace smoke indirect selected before normal render-list draw submission: normalLodDrawSkippedForSmoke=true"
                + ", screenspaceSmokeIndirect=true"
                + ", drawSubmitReason=screenspace_smoke_indirect_draw", 1);
        if (this.controlledSmokeKnownCommandBuffer == null || this.controlledSmokeKnownCommandBuffer.getId() == 0L) {
            String submitReason = "screenspace_smoke_indirect_command_buffer_missing";
            VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, submitReason);
            logScreenspaceSmokeSubmitDiagnostics(false, submitReason, null);
            logDrawSubmitHandoffDiagnostic(0, new JavaDrawCountDiagnostic(0, "screenspace_smoke_indirect_known_command_missing"), false, 0, false, false, 0, submitReason, false);
            return new OpaqueDrawSubmission(0, "screenspace_smoke_indirect_draw", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, submitReason);
        }

        applyRealLodVisibilityDiagnosticPipelineStateOverride();
        configureSectionDrawPrimitiveTopologyTriangleList("screenspace_smoke_indirect_draw");
        logSectionDrawGraphicsPipelineState(renderer, viewport, "screenspace_smoke_indirect_draw");
        renderer.bindGraphicsPipeline(this.graphicsPipeline);
        this.bindSceneUniform(commandBuffer, viewport);
        logSectionDrawBindingState("screenspace_smoke_indirect_draw");
        this.bindDrawTextureDescriptors();
        this.graphicsPipeline.bindDescriptorSets(commandBuffer, 0);

        int smokeSubmittedDrawCount = 1;
        logScreenspaceSmokeSubmittedDrawCountGuard(smokeSubmittedDrawCount, "isolated_smoke_path_before_record");
        VK10.vkCmdDrawIndirect(commandBuffer, this.controlledSmokeKnownCommandBuffer.getId(), 0L, smokeSubmittedDrawCount, DRAW_COMMAND_STRIDE_BYTES);
        this.anyVkCmdDrawIndirectRecordedThisFrame = true;
        this.drawRecordedReasonThisFrame = "screenspace_smoke_indirect_draw";
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(true, "screenspace_smoke_indirect_draw");
        logScreenspaceSmokeSubmitDiagnostics(true, "screenspace_smoke_indirect_draw", null);
        logDrawSubmitHandoffDiagnostic(0, new JavaDrawCountDiagnostic(smokeSubmittedDrawCount, "screenspace_smoke_indirect_known_command"), false, 0, true, true, smokeSubmittedDrawCount, "screenspace_smoke_indirect_draw", false);
        return new OpaqueDrawSubmission(0, "screenspace_smoke_indirect_draw", -1L, smokeSubmittedDrawCount, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, null);
    }


    private static void configureSectionDrawPrimitiveTopologyTriangleList(String stage) {
        int previousTopology = VRenderSystem.topology;
        VRenderSystem.topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VulkanBerylDebugLog.verboseOnce("section-draw-primitive-topology", "Section draw primitive topology configured: stage=" + stage
                + ", previousTopology=" + vulkanPrimitiveTopologyName(previousTopology) + "(" + previousTopology + ")"
                + ", currentTopology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST(" + VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST + ")");
    }


    private static void applyRealLodVisibilityDiagnosticPipelineStateOverride() {
        if (!realLodMagentaDiagnosticEnabled()) return;
        VRenderSystem.disableCull();
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthFunc(519);
        VRenderSystem.depthMask(false);
        VRenderSystem.colorMask(true, true, true, true);
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-diagnostic-state-override", "section draw real LOD diagnostic state override applied: diagnosticOnly=true"
                + ", realLodVisibilityDiagnostic=" + REAL_LOD_VISIBILITY_DIAGNOSTIC
                + ", realLodVertexPathClipspaceProbe=" + REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE
                + ", realQuadReadClipspaceProbe=" + REAL_QUAD_READ_CLIPSPACE_PROBE
                + ", realLodSingleQuadWorldProbe=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                + ", cull=disabled"
                + ", depthTest=disabled"
                + ", depthFunc=GL_ALWAYS(519)"
                + ", depthMask=false"
                + ", topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST"
                + ", forcedFragmentColour=magenta", 60);
    }


    private static void applyFinalPassScreenspaceMarkerStateOverride() {
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.depthFunc(519);
        VRenderSystem.disableCull();
        VRenderSystem.disableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        configureSectionDrawPrimitiveTopologyTriangleList("final_pass_screenspace_marker");
    }


    private void applyRealLodSingleQuadWorldProbeSafeState(Renderer renderer, VulkanBerylViewport viewport, VkCommandBuffer commandBuffer) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return;

        Framebuffer framebuffer;
        try {
            framebuffer = renderer.getBoundFramebuffer();
        } catch (Exception e) {
            framebuffer = null;
        }

        int targetWidth = framebuffer != null ? framebuffer.getWidth() : viewport.width;
        int targetHeight = framebuffer != null ? framebuffer.getHeight() : viewport.height;
        boolean safeStateApplied = false;
        boolean pipelineBound = false;
        boolean descriptorsBound = false;
        boolean viewportForcedFullExtent = false;
        boolean scissorForcedFullExtent = false;
        String viewportExtent = "unavailable";
        String scissorExtent = "unavailable";

        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.depthFunc(519);
        VRenderSystem.disableCull();
        VRenderSystem.disableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        configureSectionDrawPrimitiveTopologyTriangleList("real_lod_single_quad_world_probe");
        safeStateApplied = true;

        if (this.graphicsPipeline != null) {
            renderer.bindGraphicsPipeline(this.graphicsPipeline);
            pipelineBound = true;
        }
        if (commandBuffer != null && this.graphicsPipeline != null && this.resourcesBound) {
            this.bindSceneUniform(commandBuffer, viewport);
            this.bindDrawTextureDescriptors();
            this.graphicsPipeline.bindDescriptorSets(commandBuffer, 0);
            descriptorsBound = true;
        }
        if (targetWidth > 0 && targetHeight > 0) {
            try {
                Renderer.setViewport(0, 0, targetWidth, targetHeight);
                viewportForcedFullExtent = true;
                viewportExtent = targetWidth + "x" + targetHeight;
            } catch (Exception e) {
                viewportExtent = "unavailable:" + e.getClass().getSimpleName();
            }
            try {
                Renderer.setScissor(0, 0, targetWidth, targetHeight);
                scissorForcedFullExtent = true;
                scissorExtent = targetWidth + "x" + targetHeight;
            } catch (Exception e) {
                scissorExtent = "unavailable:" + e.getClass().getSimpleName();
            }
        } else {
            viewportExtent = "invalid:" + targetWidth + "x" + targetHeight;
            scissorExtent = "invalid:" + targetWidth + "x" + targetHeight;
        }

        int depthState = PipelineState.getDepthState();
        int assemblyRasterState = PipelineState.getAssemblyRasterState();
        int blendState = PipelineState.getBlendState();
        int colorMask = VRenderSystem.getColorMask();
        boolean depthTestDisabled = !PipelineState.DepthState.depthTest(depthState);
        boolean depthWriteDisabled = !PipelineState.DepthState.depthMask(depthState);
        boolean cullDisabled = PipelineState.AssemblyRasterState.decodeCullMode(assemblyRasterState) == VK10.VK_CULL_MODE_NONE;
        boolean blendDisabled = !PipelineState.BlendState.enable(blendState);
        boolean colourWriteMaskEnabled = rgbaColourWriteMaskEnabled(colorMask);
        boolean recordedInPresentedWorldTarget = framebuffer != null && targetWidth > 0 && targetHeight > 0;

        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-probe-safe-state", "section draw real LOD single-quad world probe safe state:"
                + " realLodProbeSafeStateApplied=" + safeStateApplied
                + ", realLodProbeDepthTestDisabled=" + depthTestDisabled
                + ", realLodProbeDepthWriteDisabled=" + depthWriteDisabled
                + ", realLodProbeCullDisabled=" + cullDisabled
                + ", realLodProbeBlendDisabled=" + blendDisabled
                + ", realLodProbeColourWriteMaskEnabled=" + colourWriteMaskEnabled
                + ", realLodProbeViewportForcedFullExtent=" + viewportForcedFullExtent
                + ", realLodProbeScissorForcedFullExtent=" + scissorForcedFullExtent
                + ", realLodProbeViewportExtent=" + viewportExtent
                + ", realLodProbeScissorExtent=" + scissorExtent
                + ", realLodProbePipelineMatchesBoundPipeline=" + pipelineBound
                + ", realLodProbeDescriptorSetsBound=" + descriptorsBound
                + ", realLodProbeRecordedInPresentedWorldTarget=" + recordedInPresentedWorldTarget
                + ", realLodProbePrimitiveTopology=" + vulkanPrimitiveTopologyName(PipelineState.AssemblyRasterState.decodeTopology(assemblyRasterState)) + "(" + PipelineState.AssemblyRasterState.decodeTopology(assemblyRasterState) + ")"
                + ", realLodProbeColorWriteMask=" + colorMaskName(colorMask) + "(" + colorMask + ")"
                + ", realLodProbePipelineId=0x" + Integer.toHexString(System.identityHashCode(this.graphicsPipeline))
                + ", realLodProbePipelineHash=0x" + Integer.toHexString(Objects.hash(System.identityHashCode(this.graphicsPipeline), this.graphicsPipelineGeneration, this.sectionDrawFragmentSourceHash))
                + ", passContext=" + SECTION_DRAW_PASS_CONTEXT.get(), 60);
    }


    private void logSectionDrawGraphicsPipelineState(Renderer renderer, VulkanBerylViewport viewport, String stage) {
        Framebuffer framebuffer = renderer.getBoundFramebuffer();
        int assemblyRasterState = PipelineState.getAssemblyRasterState();
        int depthState = PipelineState.getDepthState();
        int blendState = PipelineState.getBlendState();
        int colorMask = VRenderSystem.getColorMask();
        int cullMode = PipelineState.AssemblyRasterState.decodeCullMode(assemblyRasterState);
        int depthCompareOp = PipelineState.DepthState.decodeDepthFun(depthState);
        VulkanBerylDebugLog.rateLimited("section-draw-graphics-pipeline-state", "Section draw graphics pipeline state: stage=" + stage
                + ", realLodVisibilityDiagnostic=" + REAL_LOD_VISIBILITY_DIAGNOSTIC
                + ", realLodVertexPathClipspaceProbe=" + REAL_LOD_VERTEX_PATH_CLIPSPACE_PROBE
                + ", realQuadReadClipspaceProbe=" + REAL_QUAD_READ_CLIPSPACE_PROBE
                + ", realLodSingleQuadWorldProbe=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                + ", debugFragmentShaderUsed=" + useDebugFragmentShader()
                + ", debugFragmentShaderResource=" + DRAW_DEBUG_FRAGMENT_SHADER_RESOURCE
                + ", forcedFragmentColour=" + (realLodMagentaDiagnosticEnabled() ? "magenta" : "hash_from_draw_and_quad_id")
                + ", depthTestEnabled=" + PipelineState.DepthState.depthTest(depthState)
                + ", depthWriteEnabled=" + PipelineState.DepthState.depthMask(depthState)
                + ", depthCompareOp=" + vulkanCompareOpName(depthCompareOp) + "(" + depthCompareOp + ")"
                + ", cullMode=" + vulkanCullModeName(cullMode) + "(" + cullMode + ")"
                + ", primitiveTopology=" + vulkanPrimitiveTopologyName(PipelineState.AssemblyRasterState.decodeTopology(assemblyRasterState)) + "(" + PipelineState.AssemblyRasterState.decodeTopology(assemblyRasterState) + ")"
                + ", frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE(0)"
                + ", colorWriteMask=" + colorMaskName(colorMask) + "(" + colorMask + ")"
                + ", blendEnabled=" + PipelineState.BlendState.enable(blendState)
                + ", viewport=" + viewport.width + "x" + viewport.height
                + ", scissor=" + viewport.width + "x" + viewport.height
                + ", renderTargetFormat=" + (framebuffer == null ? "unbound" : framebuffer.getFormat())
                + ", renderTargetDepthFormat=" + (framebuffer == null ? "unbound" : framebuffer.getDepthFormat())
                + ", renderTargetExtent=" + (framebuffer == null ? "unbound" : framebuffer.getWidth() + "x" + framebuffer.getHeight())
                + ", berylDynamicViewportScissor=true", 60);
    }


    private void logSectionDrawRenderTargetPathDiagnostics(Renderer renderer, VulkanBerylViewport viewport, VkCommandBuffer commandBuffer, String stage, Buffer indirectDrawCommandBuffer, int submittedDrawCount) {
        Framebuffer framebuffer = renderer.getBoundFramebuffer();
        String passContext = SECTION_DRAW_PASS_CONTEXT.get();
        String dynamicRenderingActive = reflectBooleanState(renderer, "isDynamicRenderingActive", "dynamicRenderingActive", "inDynamicRendering");
        String renderPassActive = reflectBooleanState(renderer, "isRenderPassActive", "renderPassActive", "inRenderPass", "isRendering");
        if ("unknown".equals(renderPassActive) && framebuffer != null) {
            renderPassActive = "bound_framebuffer_present_unknown_begin_state";
        }
        long framebufferId = reflectLong(framebuffer, "getId", "id", "framebuffer", "frameBuffer", "handle");
        long colorImageId = reflectNestedLong(framebuffer, "image", "colorImage", "colorAttachment", "colorAttachmentImage", "mainColorImage");
        long colorViewId = reflectNestedLong(framebuffer, "imageView", "view", "colorView", "colorAttachmentView", "mainColorView");
        VulkanBerylDebugLog.rateLimited("section-draw-render-target-path-diagnostics", "Section draw render target/path diagnostics: stage=" + stage
                + ", renderPassActiveForSectionDraw=" + renderPassActive
                + ", dynamicRenderingActiveForSectionDraw=" + dynamicRenderingActive
                + ", sectionDrawCommandBufferAddress=0x" + Long.toHexString(commandBuffer == null ? 0L : commandBuffer.address())
                + ", sectionDrawRenderTargetImageId=" + formatHandle(colorImageId)
                + ", sectionDrawRenderTargetViewId=" + formatHandle(colorViewId)
                + ", sectionDrawFramebufferId=" + formatHandle(framebufferId)
                + ", sectionDrawPassName=" + passContext
                + ", sectionDrawStage=" + stage
                + ", sectionDrawPipelineId=0x" + Integer.toHexString(System.identityHashCode(this.graphicsPipeline))
                + ", sectionDrawPipelineHash=0x" + Integer.toHexString(Objects.hash(System.identityHashCode(this.graphicsPipeline), this.graphicsPipelineGeneration, this.sectionDrawFragmentSourceHash))
                + ", sectionDrawPipelineGeneration=" + this.graphicsPipelineGeneration
                + ", sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine=" + this.sectionDrawVertexSourceContainsSingleQuadWorldProbeDefine
                + ", sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch=" + this.sectionDrawVertexSourceContainsSingleQuadWorldProbeBranch
                + ", sectionDrawFragmentSourceContainsMagentaDefine=" + this.sectionDrawFragmentSourceContainsMagentaDefine
                + ", sectionDrawFragmentSourceContainsMagentaBranch=" + this.sectionDrawFragmentSourceContainsMagentaBranch
                + ", sectionDrawFragmentSourceHash=" + this.sectionDrawFragmentSourceHash
                + ", sectionDrawRecordedAfterMainClear=" + inferRecordedAfterMainClear(passContext)
                + ", sectionDrawMayBeOverwrittenByLaterPass=" + inferMayBeOverwrittenByLaterPass(passContext)
                + ", sectionDrawSamePassScreenspaceProbeAvailable=" + SECTION_DRAW_SAME_PASS_SCREENSPACE_PROBE
                + ", sectionDrawIndirectCommandBufferId=" + (indirectDrawCommandBuffer == null ? 0L : indirectDrawCommandBuffer.getId())
                + ", submittedDrawCount=" + submittedDrawCount
                + ", boundFramebufferFormat=" + (framebuffer == null ? "unbound" : framebuffer.getFormat())
                + ", boundFramebufferExtent=" + (framebuffer == null ? "unbound" : framebuffer.getWidth() + "x" + framebuffer.getHeight()), 60);
    }


    private void recordFinalPassScreenspaceMarker(Renderer renderer, VulkanBerylViewport viewport,
            VkCommandBuffer commandBuffer, int rawVisibleCount, int visibleCount) {
        if (!SECTION_DRAW_FINAL_PASS_SCREENSPACE_MARKER) return;

        Framebuffer framebuffer;
        try {
            framebuffer = renderer.getBoundFramebuffer();
        } catch (Exception e) {
            framebuffer = null;
        }
        String dynamicRenderingActive = reflectBooleanState(renderer, "isDynamicRenderingActive", "dynamicRenderingActive", "inDynamicRendering");
        String renderPassActive = reflectBooleanState(renderer, "isRenderPassActive", "renderPassActive", "inRenderPass", "isRendering");
        if ("unknown".equals(renderPassActive) && framebuffer != null) {
            renderPassActive = "bound_framebuffer_present_unknown_begin_state";
        }

        boolean hasActiveRenderTarget = framebuffer != null
                || "true".equals(renderPassActive)
                || "true".equals(dynamicRenderingActive);

        String skipReason;
        boolean recorded;
        boolean pipelineBound = false;
        boolean descriptorsBound = false;
        boolean viewportForcedFullExtent = false;
        boolean scissorForcedFullExtent = false;
        String viewportExtent = "unavailable";
        String scissorExtent = "unavailable";
        String markerReadbackSupported = "false";
        String markerReadbackSkipReason = "not_implemented_no_safe_render_pass_copy_or_readback_path";
        int targetWidth = framebuffer != null ? framebuffer.getWidth() : viewport.width;
        int targetHeight = framebuffer != null ? framebuffer.getHeight() : viewport.height;
        long markerFrameCounter = ++this.finalPassScreenspaceMarkerFrameCounter;

        if (commandBuffer == null) {
            recorded = false;
            skipReason = "no_command_buffer";
        } else if (this.graphicsPipeline == null) {
            recorded = false;
            skipReason = "no_graphics_pipeline";
        } else if (!this.resourcesBound) {
            recorded = false;
            skipReason = "resources_not_bound";
        } else if (!hasActiveRenderTarget) {
            recorded = false;
            skipReason = "no_active_render_target";
        } else if (targetWidth <= 0 || targetHeight <= 0) {
            recorded = false;
            skipReason = "invalid_framebuffer_extent";
        } else {
            applyFinalPassScreenspaceMarkerStateOverride();
            renderer.bindGraphicsPipeline(this.graphicsPipeline);
            pipelineBound = true;
            this.bindSceneUniform(commandBuffer, viewport);
            this.bindDrawTextureDescriptors();
            this.graphicsPipeline.bindDescriptorSets(commandBuffer, 0);
            descriptorsBound = true;
            try {
                Renderer.setViewport(0, 0, targetWidth, targetHeight);
                viewportForcedFullExtent = true;
                viewportExtent = targetWidth + "x" + targetHeight;
            } catch (Exception e) {
                viewportExtent = "unavailable:" + e.getClass().getSimpleName();
            }
            try {
                Renderer.setScissor(0, 0, targetWidth, targetHeight);
                scissorForcedFullExtent = true;
                scissorExtent = targetWidth + "x" + targetHeight;
            } catch (Exception e) {
                scissorExtent = "unavailable:" + e.getClass().getSimpleName();
            }
            VK10.vkCmdDraw(commandBuffer, FINAL_PASS_SCREENSPACE_MARKER_VERTEX_COUNT,
                    FINAL_PASS_SCREENSPACE_MARKER_INSTANCE_COUNT,
                    FINAL_PASS_SCREENSPACE_MARKER_FIRST_VERTEX,
                    FINAL_PASS_SCREENSPACE_MARKER_FIRST_INSTANCE);
            this.anyVkCmdDrawRecordedThisFrame = true;
            this.finalPassScreenspaceMarkerRecorded = true;
            recorded = true;
            skipReason = "none";
        }

        String passContext = SECTION_DRAW_PASS_CONTEXT.get();
        long framebufferId = reflectLong(framebuffer, "getId", "id", "framebuffer", "frameBuffer", "handle");
        long colorImageId = reflectNestedLong(framebuffer, "image", "colorImage", "colorAttachment", "colorAttachmentImage", "mainColorImage");
        long colorViewId = reflectNestedLong(framebuffer, "imageView", "view", "colorView", "colorAttachmentView", "mainColorView");
        int markerDepthState = PipelineState.getDepthState();
        int markerAssemblyRasterState = PipelineState.getAssemblyRasterState();
        int markerBlendState = PipelineState.getBlendState();
        int markerColorMask = VRenderSystem.getColorMask();
        boolean markerDepthTestDisabled = !PipelineState.DepthState.depthTest(markerDepthState);
        boolean markerDepthWriteDisabled = !PipelineState.DepthState.depthMask(markerDepthState);
        boolean markerCullDisabled = PipelineState.AssemblyRasterState.decodeCullMode(markerAssemblyRasterState) == VK10.VK_CULL_MODE_NONE;
        boolean markerBlendDisabled = !PipelineState.BlendState.enable(markerBlendState);
        boolean markerColourWriteMaskEnabled = markerColorMask == VRenderSystem.getColorMask();
        VulkanBerylDebugLog.always("Section draw final-pass screenspace marker diagnostic:"
                + " sectionDrawFinalPassScreenspaceMarkerEnabled=true"
                + ", sectionDrawFinalPassScreenspaceMarkerRecorded=" + recorded
                + ", sectionDrawFinalPassScreenspaceMarkerSkipReason=" + skipReason
                + ", sectionDrawFinalPassMarkerPersistent=true"
                + ", sectionDrawFinalPassMarkerFrameCounter=" + markerFrameCounter
                + ", renderListVisibleCountForDraw=" + visibleCount
                + ", rawRenderListVisibleCount=" + rawVisibleCount
                + ", cmdgenSkipped=not_reached_at_marker_point"
                + ", cmdgenSkipReason=not_reached_at_marker_point"
                + ", indirectDrawRecorded=not_reached_at_marker_point"
                + ", submittedDrawCount=not_reached_at_marker_point"
                + ", drawSubmitReason=final_pass_screenspace_marker"
                + ", renderPassActiveForSectionDraw=" + renderPassActive
                + ", dynamicRenderingActiveForSectionDraw=" + dynamicRenderingActive
                + ", sectionDrawRenderTargetImageId=" + formatHandle(colorImageId)
                + ", sectionDrawRenderTargetViewId=" + formatHandle(colorViewId)
                + ", sectionDrawFramebufferId=" + formatHandle(framebufferId)
                + ", boundFramebufferExtent=" + (framebuffer == null ? "unbound" : framebuffer.getWidth() + "x" + framebuffer.getHeight())
                + ", boundFramebufferFormat=" + (framebuffer == null ? "unbound" : framebuffer.getFormat())
                + ", sectionDrawPipelineId=0x" + Integer.toHexString(System.identityHashCode(this.graphicsPipeline))
                + ", sectionDrawPipelineHash=0x" + Integer.toHexString(Objects.hash(System.identityHashCode(this.graphicsPipeline), this.graphicsPipelineGeneration, this.sectionDrawFragmentSourceHash))
                + ", sectionDrawRecordedAfterMainClear=" + inferRecordedAfterMainClear(passContext)
                + ", sectionDrawMayBeOverwrittenByLaterPass=" + inferMayBeOverwrittenByLaterPass(passContext)
                + ", oneFrame=true"
                + ", markerVertexCount=" + FINAL_PASS_SCREENSPACE_MARKER_VERTEX_COUNT
                + ", markerInstanceCount=" + FINAL_PASS_SCREENSPACE_MARKER_INSTANCE_COUNT
                + ", markerFirstInstance=0x" + Integer.toHexString(FINAL_PASS_SCREENSPACE_MARKER_FIRST_INSTANCE)
                + ", expectedColour=magenta"
                + ", passContext=" + passContext
                + ", sectionDrawFinalPassMarkerVertexShaderDefinePresent=" + this.sectionDrawVertexSourceContainsFinalPassMarkerDefine
                + ", sectionDrawFinalPassMarkerVertexShaderBranchPresent=" + this.sectionDrawVertexSourceContainsFinalPassMarkerBranch
                + ", sectionDrawFinalPassMarkerFragmentShaderDefinePresent=" + this.sectionDrawFragmentSourceContainsFinalPassMarkerDefine
                + ", sectionDrawFinalPassMarkerFragmentShaderBranchPresent=" + this.sectionDrawFragmentSourceContainsFinalPassMarkerBranch
                + ", sectionDrawFinalPassMarkerUsesSceneMvp=false"
                + ", sectionDrawFinalPassMarkerUsesSectionMetadata=false"
                + ", sectionDrawFinalPassMarkerUsesQuadData=false"
                + ", sectionDrawFinalPassMarkerClipMode=hardcoded_clipspace"
                + ", sectionDrawFinalPassMarkerPipelineId=0x" + Integer.toHexString(System.identityHashCode(this.graphicsPipeline))
                + ", sectionDrawFinalPassMarkerPipelineHash=0x" + Integer.toHexString(Objects.hash(System.identityHashCode(this.graphicsPipeline), this.graphicsPipelineGeneration, this.sectionDrawFragmentSourceHash))
                + ", sectionDrawFinalPassMarkerPipelineMatchesBoundPipeline=" + pipelineBound
                + ", sectionDrawFinalPassMarkerDescriptorSetsBound=" + descriptorsBound
                + ", sectionDrawFinalPassMarkerDepthTestDisabled=" + markerDepthTestDisabled
                + ", sectionDrawFinalPassMarkerDepthWriteDisabled=" + markerDepthWriteDisabled
                + ", sectionDrawFinalPassMarkerStencilDisabled=unavailable:no_vrendersystem_or_pipeline_state_api"
                + ", sectionDrawFinalPassMarkerCullDisabled=" + markerCullDisabled
                + ", sectionDrawFinalPassMarkerBlendDisabled=" + markerBlendDisabled
                + ", sectionDrawFinalPassMarkerColourWriteMaskEnabled=" + markerColourWriteMaskEnabled
                + ", sectionDrawFinalPassMarkerViewportForcedFullExtent=" + viewportForcedFullExtent
                + ", sectionDrawFinalPassMarkerScissorForcedFullExtent=" + scissorForcedFullExtent
                + ", sectionDrawFinalPassMarkerViewportExtent=" + viewportExtent
                + ", sectionDrawFinalPassMarkerScissorExtent=" + scissorExtent
                + ", sectionDrawFinalPassMarkerTriangleCoverage=fullscreen"
                + ", sectionDrawFinalPassMarkerRecordedBeforeRenderListGates=true"
                + ", sectionDrawFinalPassMarkerRecordedAfterMainClear=" + inferRecordedAfterMainClear(passContext)
                + ", sectionDrawFinalPassMarkerMayBeOverwrittenByLaterPass=" + inferMayBeOverwrittenByLaterPass(passContext)
                + ", markerReadbackSupported=" + markerReadbackSupported
                + ", markerReadbackSkipReason=" + markerReadbackSkipReason);
    }


    private static String inferRecordedAfterMainClear(String passContext) {
        if (passContext != null && passContext.contains("before_shader_end")) return "likely_true_sodium_terrain_pass_already_begun";
        return "unknown";
    }


    private static String inferMayBeOverwrittenByLaterPass(String passContext) {
        if (passContext != null && passContext.contains("cutout")) return "possible_later_translucent_weather_hand_sodium_passes";
        return "unknown";
    }


    private static String shortSha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(8, digest.length); i++) {
                out.append(String.format("%02x", digest[i] & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            return "hash_failed:" + e.getClass().getSimpleName();
        }
    }


    private static String formatHandle(long handle) {
        if (handle == Long.MIN_VALUE) return "unavailable";
        if (handle == 0L) return "0";
        return "0x" + Long.toHexString(handle);
    }


    private static String reflectBooleanState(Object target, String... names) {
        if (target == null) return "unavailable";
        for (String name : names) {
            Object value = reflectValue(target, name);
            if (value instanceof Boolean bool) return Boolean.toString(bool);
        }
        return "unknown";
    }


    private static long reflectLong(Object target, String... names) {
        if (target == null) return 0L;
        for (String name : names) {
            Object value = reflectValue(target, name);
            long handle = valueToHandle(value);
            if (handle != Long.MIN_VALUE) return handle;
        }
        return Long.MIN_VALUE;
    }


    private static long reflectNestedLong(Object target, String... names) {
        if (target == null) return 0L;
        for (String name : names) {
            Object value = reflectValue(target, name);
            long direct = valueToHandle(value);
            if (direct != Long.MIN_VALUE) return direct;
            if (value != null && value.getClass().isArray() && Array.getLength(value) > 0) {
                long nested = reflectLong(Array.get(value, 0), "getId", "id", "handle", "image", "view");
                if (nested != Long.MIN_VALUE) return nested;
            } else if (value != null) {
                long nested = reflectLong(value, "getId", "id", "handle", "image", "view");
                if (nested != Long.MIN_VALUE) return nested;
            }
        }
        return Long.MIN_VALUE;
    }


    private static long valueToHandle(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || value instanceof String || value instanceof Boolean) return Long.MIN_VALUE;
        Object id = reflectValue(value, "getId");
        if (id instanceof Number number) return number.longValue();
        Object handle = reflectValue(value, "handle");
        if (handle instanceof Number number) return number.longValue();
        return Long.MIN_VALUE;
    }


    private static Object reflectValue(Object target, String name) {
        if (target == null || name == null || name.isBlank()) return null;
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        Object receiver = target instanceof Class<?> ? null : target;
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
        }
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }


    private static String vulkanPrimitiveTopologyName(int topology) {
        return switch (topology) {
            case VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST -> "VK_PRIMITIVE_TOPOLOGY_POINT_LIST";
            case VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST -> "VK_PRIMITIVE_TOPOLOGY_LINE_LIST";
            case VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP -> "VK_PRIMITIVE_TOPOLOGY_LINE_STRIP";
            case VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST -> "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST";
            case VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP -> "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP";
            case VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN -> "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN";
            case VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY -> "VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY";
            case VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY -> "VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY";
            case VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY -> "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY";
            case VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY -> "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY";
            case VK10.VK_PRIMITIVE_TOPOLOGY_PATCH_LIST -> "VK_PRIMITIVE_TOPOLOGY_PATCH_LIST";
            default -> "unknown";
        };
    }


    private static String vulkanCullModeName(int cullMode) {
        return switch (cullMode) {
            case VK10.VK_CULL_MODE_NONE -> "VK_CULL_MODE_NONE";
            case VK10.VK_CULL_MODE_FRONT_BIT -> "VK_CULL_MODE_FRONT_BIT";
            case VK10.VK_CULL_MODE_BACK_BIT -> "VK_CULL_MODE_BACK_BIT";
            case VK10.VK_CULL_MODE_FRONT_AND_BACK -> "VK_CULL_MODE_FRONT_AND_BACK";
            default -> "unknown";
        };
    }


    private static String vulkanCompareOpName(int compareOp) {
        return switch (compareOp) {
            case VK10.VK_COMPARE_OP_NEVER -> "VK_COMPARE_OP_NEVER";
            case VK10.VK_COMPARE_OP_LESS -> "VK_COMPARE_OP_LESS";
            case VK10.VK_COMPARE_OP_EQUAL -> "VK_COMPARE_OP_EQUAL";
            case VK10.VK_COMPARE_OP_LESS_OR_EQUAL -> "VK_COMPARE_OP_LESS_OR_EQUAL";
            case VK10.VK_COMPARE_OP_GREATER -> "VK_COMPARE_OP_GREATER";
            case VK10.VK_COMPARE_OP_NOT_EQUAL -> "VK_COMPARE_OP_NOT_EQUAL";
            case VK10.VK_COMPARE_OP_GREATER_OR_EQUAL -> "VK_COMPARE_OP_GREATER_OR_EQUAL";
            case VK10.VK_COMPARE_OP_ALWAYS -> "VK_COMPARE_OP_ALWAYS";
            default -> "unknown";
        };
    }


    private static String colorMaskName(int colorMask) {
        List<String> channels = new ArrayList<>(4);
        if ((colorMask & VK10.VK_COLOR_COMPONENT_R_BIT) != 0) channels.add("R");
        if ((colorMask & VK10.VK_COLOR_COMPONENT_G_BIT) != 0) channels.add("G");
        if ((colorMask & VK10.VK_COLOR_COMPONENT_B_BIT) != 0) channels.add("B");
        if ((colorMask & VK10.VK_COLOR_COMPONENT_A_BIT) != 0) channels.add("A");
        return channels.isEmpty() ? "none" : String.join("", channels);
    }


    private static boolean rgbaColourWriteMaskEnabled(int colorMask) {
        int rgbaMask = VK10.VK_COLOR_COMPONENT_R_BIT
                | VK10.VK_COLOR_COMPONENT_G_BIT
                | VK10.VK_COLOR_COMPONENT_B_BIT
                | VK10.VK_COLOR_COMPONENT_A_BIT;
        return (colorMask & rgbaMask) == rgbaMask;
    }


    private record ScheduledDebugReadback(boolean scheduled, String reason, long commandCopyBytes, long countCopyBytes, boolean alreadyPending, boolean scheduleSkipped, String skipReason, int rendererFrameSlot, long commandBufferAddress, long generation, long sourceBufferId) {
        private ScheduledDebugReadback(boolean scheduled, String reason, long commandCopyBytes, long countCopyBytes) {
            this(scheduled, reason, commandCopyBytes, countCopyBytes, false, false, "none", -1, 0L, -1L, 0L);
        }

        private ScheduledDebugReadback(boolean scheduled, String reason, long commandCopyBytes, long countCopyBytes, boolean alreadyPending, boolean scheduleSkipped, String skipReason) {
            this(scheduled, reason, commandCopyBytes, countCopyBytes, alreadyPending, scheduleSkipped, skipReason, -1, 0L, -1L, 0L);
        }
    }


    private record ControlledSmokeCommandValidation(boolean valid, String waitReason, String mismatch) {}

    private ControlledSmokeCommandValidation validateControlledSmokeCommandReadback(ControlledRenderListSmoke controlledSmoke) {
        if (!this.controlledSmokeCommandReadbackScheduled) return new ControlledSmokeCommandValidation(false, "not_scheduled", "not_scheduled");
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (this.controlledSmokeCommandReadbackCompleted && sample.sampledCommandCount() > 0) {
            if (!controlledSmokeCommandReadbackMatchesCurrentGeneration() || !controlledSmokeCommandReadbackMatchesCurrentExpected()) {
                return new ControlledSmokeCommandValidation(false, "pending_current_readback", controlledSmokeCommandReadbackStaleReason());
            }
            String mismatch = controlledSmokeCommandMismatch(controlledSmoke, sample);
            if (mismatch != null) return new ControlledSmokeCommandValidation(false, "completed_but_invalid", mismatch);
            return new ControlledSmokeCommandValidation(true, "completed_valid", "none");
        }
        if (this.debugSamplePending) return new ControlledSmokeCommandValidation(false, this.controlledSmokeCommandReadbackGpuCompletionKnown ? "pending_host_read" : "pending_gpu_completion", "pending");
        return new ControlledSmokeCommandValidation(false, "pending_host_read", "not_observed");
    }


    private void recordControlledSmokeCommandReadbackSchedule(ScheduledDebugReadback scheduledDebugReadback, int frameId) {
        this.controlledSmokeCommandReadbackAlreadyPending = scheduledDebugReadback.alreadyPending();
        this.controlledSmokeCommandReadbackScheduleSkipped = scheduledDebugReadback.scheduleSkipped();
        this.controlledSmokeCommandReadbackSkipReason = scheduledDebugReadback.skipReason();
        if (!scheduledDebugReadback.scheduleSkipped()) {
            this.controlledSmokeCommandReadbackScheduled = scheduledDebugReadback.scheduled() || this.controlledSmokeCommandReadbackScheduled;
            this.controlledSmokeCommandReadbackScheduleReason = scheduledDebugReadback.reason();
        }
        if (scheduledDebugReadback.scheduled()) {
            this.controlledSmokeCommandReadbackFrameId = frameId;
            this.controlledSmokeCommandReadbackScheduledGeneration = scheduledDebugReadback.generation();
            this.controlledSmokeCommandReadbackScheduledSectionId = this.controlledSmokeCommandExpectedSectionId;
            this.controlledSmokeCommandReadbackScheduledExpectedVertexCount = this.controlledSmokeCommandExpectedVertexCount;
            this.controlledSmokeCommandReadbackScheduledExpectedInstanceCount = this.controlledSmokeCommandExpectedInstanceCount;
            this.controlledSmokeCommandReadbackScheduledExpectedFirstVertex = this.controlledSmokeCommandExpectedFirstVertex;
            this.controlledSmokeCommandReadbackScheduledExpectedFirstInstance = this.controlledSmokeCommandExpectedFirstInstance;
            this.controlledSmokeCommandReadbackScheduledBufferId = this.controlledSmokeCommandExpectedBufferId;
            this.controlledSmokeCommandReadbackRendererFrameSlot = scheduledDebugReadback.rendererFrameSlot();
            this.controlledSmokeCommandReadbackRecordedCommandBufferAddress = scheduledDebugReadback.commandBufferAddress();
            this.controlledSmokeCommandReadbackCompletionStrategy = "unknown";
            this.controlledSmokeCommandReadbackGpuCompletionKnown = false;
            this.controlledSmokeCommandReadbackCompleted = false;
        }
        VulkanBerylDebugLog.rateLimited("controlled-smoke-command-readback-schedule", "controlled smoke command readback schedule: controlledSmokeCommandReadbackScheduled=" + scheduledDebugReadback.scheduled()
                + ", controlledSmokeCommandReadbackAlreadyPending=" + scheduledDebugReadback.alreadyPending()
                + ", controlledSmokeCommandReadbackScheduleSkipped=" + scheduledDebugReadback.scheduleSkipped()
                + ", controlledSmokeCommandReadbackSkipReason=" + scheduledDebugReadback.skipReason()
                + ", controlledSmokeCommandReadbackScheduleReason=" + scheduledDebugReadback.reason()
                + ", controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
                + ", controlledSmokeCommandReadbackGeneration=" + this.controlledSmokeCommandReadbackScheduledGeneration
                + ", controlledSmokeCommandExpectedSectionId=" + this.controlledSmokeCommandExpectedSectionId
                + ", controlledSmokeCommandExpectedVertexCount=" + this.controlledSmokeCommandExpectedVertexCount
                + ", controlledSmokeCommandExpectedInstanceCount=" + this.controlledSmokeCommandExpectedInstanceCount
                + ", controlledSmokeCommandExpectedFirstVertex=" + this.controlledSmokeCommandExpectedFirstVertex
                + ", controlledSmokeCommandExpectedFirstInstance=" + this.controlledSmokeCommandExpectedFirstInstance
                + ", controlledSmokeCommandReadbackSourceBufferId=" + controlledSmokeActiveReadbackSourceBufferId()
                + ", drawCommandsBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId())
                + ", controlledSmokeDrawUsesDedicatedCommandBuffer=" + (this.javaKnownControlledSmokeCommandWrittenThisFrame && this.controlledSmokeKnownCommandBuffer != null)
                + ", controlledSmokeDrawCommandBufferId=" + controlledSmokeActiveReadbackSourceBufferId()
                + ", controlledSmokeReadbackBufferId=" + controlledSmokeActiveReadbackSourceBufferId()
                + ", controlledSmokeReadbackMatchesDrawBuffer=true"
                + ", javaKnownControlledSmokeCommandTargetBufferId=" + this.javaKnownControlledSmokeCommandTargetBufferId
                + ", javaKnownControlledSmokeCommandTargetMatchesReadback=" + (this.javaKnownControlledSmokeCommandTargetBufferId != 0L && this.javaKnownControlledSmokeCommandTargetBufferId == controlledSmokeActiveReadbackSourceBufferId())
                + ", controlledSmokeCommandReadbackSourceOffset=0"
                + ", controlledSmokeCommandReadbackBytes=" + scheduledDebugReadback.commandCopyBytes()
                + ", controlledSmokeCommandReadbackRendererFrameSlot=" + this.controlledSmokeCommandReadbackRendererFrameSlot
                + ", controlledSmokeCommandReadbackRecordedCommandBufferAddress=0x" + Long.toHexString(this.controlledSmokeCommandReadbackRecordedCommandBufferAddress)
                + ", currentRendererFrameSlot=" + safeRendererFrameSlot()
                + ", currentCommandBufferAddress=0x" + Long.toHexString(safeCurrentCommandBufferAddress())
                + ", controlledSmokeCommandReadbackBufferId=" + (this.drawCommandDebugReadbackBuffer == null ? 0L : this.drawCommandDebugReadbackBuffer.getId())
                + ", controlledSmokeCommandReadbackBufferSizeBytes=" + (this.drawCommandDebugReadbackBuffer == null ? 0L : this.drawCommandDebugReadbackBuffer.getBufferSize())
                + ", controlledSmokeCommandReadbackBufferHostVisible=" + hostVisibleString(this.drawCommandDebugReadbackBuffer)
                + ", controlledSmokeCommandReadbackVkDrawIndirectCommandBytes=" + DRAW_COMMAND_STRIDE_BYTES
                + ", javaKnownControlledSmokeCommandActualOrder=" + this.javaKnownControlledSmokeCommandActualOrder, 30);
    }


    private void logControlledSmokeCommandReadbackLifecycle(int currentFrameId, ControlledSmokeCommandValidation validation) {
        int readbackFrameId = this.controlledSmokeCommandReadbackFrameId;
        int ageFrames = readbackFrameId < 0 ? -1 : Math.max(0, currentFrameId - readbackFrameId);
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        int currentRendererFrameSlot = safeRendererFrameSlot();
        long currentCommandBufferAddress = safeCurrentCommandBufferAddress();
        String diagnostic = "controlledSmokeCommandReadbackPending=" + this.debugSamplePending
                + ", controlledSmokeCommandReadbackAlreadyPending=" + this.controlledSmokeCommandReadbackAlreadyPending
                + ", controlledSmokeCommandReadbackScheduleSkipped=" + this.controlledSmokeCommandReadbackScheduleSkipped
                + ", controlledSmokeCommandReadbackSkipReason=" + this.controlledSmokeCommandReadbackSkipReason
                + ", controlledSmokeCommandReadbackCompleted=" + this.controlledSmokeCommandReadbackCompleted
                + ", controlledSmokeCommandReadbackFrameId=" + readbackFrameId
                + ", currentFrameId=" + currentFrameId
                + ", controlledSmokeCommandReadbackAgeFrames=" + ageFrames
                + ", controlledSmokeCommandReadbackRendererFrameSlot=" + this.controlledSmokeCommandReadbackRendererFrameSlot
                + ", currentRendererFrameSlot=" + currentRendererFrameSlot
                + ", controlledSmokeCommandReadbackRecordedCommandBufferAddress=0x" + Long.toHexString(this.controlledSmokeCommandReadbackRecordedCommandBufferAddress)
                + ", currentCommandBufferAddress=0x" + Long.toHexString(currentCommandBufferAddress)
                + ", controlledSmokeCommandReadbackCompletionStrategy=" + this.controlledSmokeCommandReadbackCompletionStrategy
                + ", controlledSmokeCommandReadbackGpuCompletionKnown=" + this.controlledSmokeCommandReadbackGpuCompletionKnown
                + ", controlledSmokeCommandReadbackScheduleReason=" + this.controlledSmokeCommandReadbackScheduleReason
                + ", controlledSmokeCommandReadbackCompletedFrameId=" + this.controlledSmokeCommandReadbackCompletedFrameId
                + ", controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
                + ", controlledSmokeCommandExpectedSectionId=" + this.controlledSmokeCommandExpectedSectionId
                + ", controlledSmokeCommandExpectedVertexCount=" + this.controlledSmokeCommandExpectedVertexCount
                + ", controlledSmokeCommandExpectedInstanceCount=" + this.controlledSmokeCommandExpectedInstanceCount
                + ", controlledSmokeCommandExpectedFirstVertex=" + this.controlledSmokeCommandExpectedFirstVertex
                + ", controlledSmokeCommandExpectedFirstInstance=" + this.controlledSmokeCommandExpectedFirstInstance
                + ", controlledSmokeCommandReadbackGeneration=" + this.controlledSmokeCommandReadbackCompletedGeneration
                + ", controlledSmokeCommandReadbackMatchesCurrentGeneration=" + controlledSmokeCommandReadbackMatchesCurrentGeneration()
                + ", controlledSmokeCommandReadbackMatchesCurrentExpected=" + controlledSmokeCommandReadbackMatchesCurrentExpected()
                + ", controlledSmokeCommandReadbackStale=" + (this.controlledSmokeCommandReadbackCompleted && !controlledSmokeCommandReadbackMatchesCurrentExpected())
                + ", controlledSmokeCommandReadbackStaleReason=" + controlledSmokeCommandReadbackStaleReason()
                + ", controlledSmokeCommandReadbackObservedVertexCount=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L)
                + ", controlledSmokeCommandReadbackObservedInstanceCount=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstInstanceCount()) : -1L)
                + ", controlledSmokeCommandReadbackObservedFirstVertex=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L)
                + ", controlledSmokeCommandReadbackObservedFirstInstance=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstFirstInstance()) : -1L)
                + ", controlledSmokeCommandUploadGeneration=" + this.controlledSmokeCommandUploadGeneration
                + ", controlledSmokeCommandUploadExpectedVertexCount=" + this.controlledSmokeCommandUploadExpectedVertexCount
                + ", controlledSmokeCommandUploadExpectedFirstVertex=" + this.controlledSmokeCommandUploadExpectedFirstVertex
                + ", controlledSmokeCommandUploadFrameId=" + this.controlledSmokeCommandUploadFrameId
                + ", controlledSmokeKnownCommandUploadQueued=" + this.controlledSmokeKnownCommandUploadQueued
                + ", controlledSmokeKnownCommandUploadFlushed=" + this.controlledSmokeKnownCommandUploadFlushed
                + ", controlledSmokeKnownCommandUploadAgeFrames=" + controlledSmokeKnownCommandUploadAgeFrames(currentFrameId)
                + ", controlledSmokeKnownCommandUploadSkipped=" + this.controlledSmokeKnownCommandUploadSkipped
                + ", controlledSmokeKnownCommandUploadSkipReason=" + this.controlledSmokeKnownCommandUploadSkipReason
                + ", controlledSmokeKnownCommandUploadTupleChanged=" + this.controlledSmokeKnownCommandUploadTupleChanged
                + ", controlledSmokeKnownCommandUploadCompletionKnown=" + controlledSmokeKnownCommandUploadCompletionKnownString(currentFrameId)
                + ", controlledSmokeKnownCommandUploadCompletionStrategy=" + this.controlledSmokeKnownCommandUploadCompletionStrategy
                + ", controlledSmokeKnownCommandReadbackAfterUploadGeneration=" + this.controlledSmokeKnownCommandReadbackAfterUploadGeneration
                + ", controlledSmokeCommandValidationState=" + this.controlledSmokeCommandValidationState
                + ", controlledSmokeCommandCanSubmit=" + this.controlledSmokeCommandCanSubmit
                + ", drawSubmitReason=" + this.controlledSmokeCommandDrawSubmitReason
                + ", indirectDrawRecorded=" + this.controlledSmokeCommandCanSubmit
                + ", smokeDrawCommandLooksDrawable=" + validation.valid()
                + ", diagnosticWaitReason=" + validation.waitReason()
                + ", smokeDrawInvisibleReason=" + (validation.valid() ? "none" : validation.mismatch())
                + ", controlledSmokeCommandReadbackMismatch=" + validation.mismatch();
        VulkanBerylDebugLog.stateLimited("controlled-smoke-command-readback-lifecycle", "controlled smoke command readback lifecycle: " + diagnostic, diagnostic);
    }


    private static String hostVisibleString(Buffer buffer) {
        if (buffer == null) return "unknown";
        return buffer.getDataPtr() == 0L ? "false" : "true";
    }


    private long controlledSmokeActiveReadbackSourceBufferId() {
        if (this.javaKnownControlledSmokeCommandWrittenThisFrame && this.controlledSmokeKnownCommandBuffer != null) {
            return this.controlledSmokeKnownCommandBuffer.getId();
        }
        return this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId();
    }


    private void updateControlledSmokeCommandGeneration(int sectionId, long vertexCount, int instanceCount, long firstVertex, int firstInstance, long bufferId) {
        boolean changed = this.controlledSmokeCommandExpectedSectionId != sectionId
                || this.controlledSmokeCommandExpectedVertexCount != vertexCount
                || this.controlledSmokeCommandExpectedInstanceCount != instanceCount
                || this.controlledSmokeCommandExpectedFirstVertex != firstVertex
                || this.controlledSmokeCommandExpectedFirstInstance != firstInstance
                || this.controlledSmokeCommandExpectedBufferId != bufferId;
        if (changed) {
            this.controlledSmokeCommandGeneration++;
            this.controlledSmokeCommandExpectedSectionId = sectionId;
            this.controlledSmokeCommandExpectedVertexCount = vertexCount;
            this.controlledSmokeCommandExpectedInstanceCount = instanceCount;
            this.controlledSmokeCommandExpectedFirstVertex = firstVertex;
            this.controlledSmokeCommandExpectedFirstInstance = firstInstance;
            this.controlledSmokeCommandExpectedBufferId = bufferId;
            this.controlledSmokeCommandReadbackCompleted = false;
            if (this.debugSamplePending && this.controlledSmokeCommandReadbackScheduledGeneration != this.controlledSmokeCommandGeneration) {
                if (this.pendingDebugSampleGpuCompletionKnown) {
                    this.debugSamplePending = false;
                    this.controlledSmokeCommandReadbackScheduleSkipped = false;
                    this.controlledSmokeCommandReadbackSkipReason = "stale_pending_readback_ignored";
                } else {
                    this.controlledSmokeCommandReadbackScheduleSkipped = true;
                    this.controlledSmokeCommandReadbackSkipReason = "stale_pending_readback_waiting_for_gpu_completion";
                }
            }
        }
    }


    private boolean controlledSmokeCommandReadbackMatchesCurrentGeneration() {
        return this.controlledSmokeCommandReadbackCompleted && this.controlledSmokeCommandReadbackCompletedGeneration == this.controlledSmokeCommandGeneration;
    }


    private boolean controlledSmokeCommandReadbackScheduledMetadataMatchesCurrentExpected() {
        return this.controlledSmokeCommandReadbackCompletedSectionId == this.controlledSmokeCommandExpectedSectionId
                && this.controlledSmokeCommandReadbackCompletedExpectedVertexCount == this.controlledSmokeCommandExpectedVertexCount
                && this.controlledSmokeCommandReadbackCompletedExpectedInstanceCount == this.controlledSmokeCommandExpectedInstanceCount
                && this.controlledSmokeCommandReadbackCompletedExpectedFirstVertex == this.controlledSmokeCommandExpectedFirstVertex
                && this.controlledSmokeCommandReadbackCompletedExpectedFirstInstance == this.controlledSmokeCommandExpectedFirstInstance
                && this.controlledSmokeCommandReadbackCompletedBufferId == this.controlledSmokeCommandExpectedBufferId;
    }


    private boolean controlledSmokeCommandReadbackObservedMatchesCurrentExpected() {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (sample.sampledCommandCount() <= 0) return false;
        return Integer.toUnsignedLong(sample.firstVertexCount()) == this.controlledSmokeCommandExpectedVertexCount
                && sample.firstInstanceCount() == this.controlledSmokeCommandExpectedInstanceCount
                && Integer.toUnsignedLong(sample.firstFirstVertex()) == this.controlledSmokeCommandExpectedFirstVertex
                && sample.firstFirstInstance() == this.controlledSmokeCommandExpectedFirstInstance;
    }


    private boolean controlledSmokeCommandReadbackMatchesCurrentExpected() {
        return controlledSmokeCommandReadbackMatchesCurrentGeneration()
                && controlledSmokeCommandReadbackScheduledMetadataMatchesCurrentExpected()
                && controlledSmokeCommandReadbackObservedMatchesCurrentExpected();
    }


    private String controlledSmokeCommandReadbackStaleReason() {
        if (!this.controlledSmokeCommandReadbackCompleted || controlledSmokeCommandReadbackMatchesCurrentExpected()) return "none";
        if (!controlledSmokeCommandReadbackMatchesCurrentGeneration()) return "expected_command_changed";
        if (this.controlledSmokeCommandReadbackCompletedSectionId != this.controlledSmokeCommandExpectedSectionId) return "section_changed";
        if (this.controlledSmokeCommandReadbackCompletedExpectedVertexCount != this.controlledSmokeCommandExpectedVertexCount
                || this.controlledSmokeCommandReadbackCompletedExpectedInstanceCount != this.controlledSmokeCommandExpectedInstanceCount
                || this.controlledSmokeCommandReadbackCompletedExpectedFirstVertex != this.controlledSmokeCommandExpectedFirstVertex
                || this.controlledSmokeCommandReadbackCompletedExpectedFirstInstance != this.controlledSmokeCommandExpectedFirstInstance) return "expected_command_changed";
        if (this.controlledSmokeCommandReadbackCompletedBufferId != this.controlledSmokeCommandExpectedBufferId) return "buffer_changed";
        if (!controlledSmokeCommandReadbackObservedMatchesCurrentExpected()) return "observed_command_mismatch";
        return "expected_command_changed";
    }


    private boolean controlledSmokeKnownCommandUploadCurrentGenerationQueued() {
        return this.controlledSmokeCommandUploadGeneration == this.controlledSmokeCommandGeneration && this.controlledSmokeCommandUploadGeneration >= 0L;
    }


    private boolean controlledSmokeKnownCommandUploadAgeReady(int currentFrameId) {
        if (!controlledSmokeKnownCommandUploadCurrentGenerationQueued()) return false;
        if (this.controlledSmokeCommandUploadFrameId < 0) return false;
        return (currentFrameId - this.controlledSmokeCommandUploadFrameId) >= 2;
    }


    private int controlledSmokeKnownCommandUploadAgeFrames(int currentFrameId) {
        if (!controlledSmokeKnownCommandUploadCurrentGenerationQueued()) return -1;
        if (this.controlledSmokeCommandUploadFrameId < 0) return -1;
        return Math.max(0, currentFrameId - this.controlledSmokeCommandUploadFrameId);
    }


    private String controlledSmokeKnownCommandUploadCompletionKnownString(int currentFrameId) {
        if (!controlledSmokeKnownCommandUploadCurrentGenerationQueued()) return "false";
        if (controlledSmokeKnownCommandUploadAgeReady(currentFrameId)) return "true";
        return "unknown";
    }


    private String computeControlledSmokeCommandValidationState(ControlledRenderListSmoke controlledSmoke, int currentFrameId) {
        if (!controlledSmokeKnownCommandUploadCurrentGenerationQueued() || !controlledSmokeKnownCommandUploadAgeReady(currentFrameId)) {
            return "upload_pending";
        }
        if (!this.controlledSmokeCommandReadbackScheduled
                || this.controlledSmokeCommandReadbackScheduledGeneration != this.controlledSmokeCommandGeneration) {
            return "readback_not_scheduled";
        }
        if (!this.controlledSmokeCommandReadbackCompleted
                || this.controlledSmokeCommandReadbackCompletedGeneration != this.controlledSmokeCommandGeneration) {
            return "readback_pending";
        }
        if (!controlledSmokeCommandReadbackMatchesCurrentExpected()) {
            return "readback_invalid";
        }
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (sample.sampledCommandCount() <= 0) return "readback_pending";
        String mismatch = controlledSmokeCommandMismatch(controlledSmoke, sample);
        if (mismatch != null) return "readback_invalid";
        return "readback_valid";
    }


    private static String drawSubmitReasonForControlledSmokeState(String state) {
        return switch (state) {
            case "upload_pending" -> "diagnostic_waiting_for_upload_completion";
            case "readback_not_scheduled" -> "diagnostic_waiting_for_current_command_readback";
            case "readback_pending" -> "diagnostic_waiting_for_current_command_readback";
            case "readback_invalid" -> "diagnostic_invalid_command";
            case "readback_valid" -> "submitted";
            default -> "submitted";
        };
    }


    private boolean controlledSmokeDiagnosticBypassAllowed(ControlledRenderListSmoke controlledSmoke, int currentFrameId) {
        if (!DISABLE_CONTROLLED_SMOKE_READBACK_COPY) return false;
        if (!DRAW_SCREENSPACE_SMOKE_INDIRECT) return false;
        if (!controlledRenderListDiagnosticEnabled()) return false;
        if (!controlledSmoke.safe()) return false;
        if (!controlledSmokeKnownCommandUploadCurrentGenerationQueued()) return false;
        if (!controlledSmokeKnownCommandUploadAgeReady(currentFrameId)) return false;
        return true;
    }


    private boolean controlledSmokeDrawCommandLooksDrawable(ControlledRenderListSmoke controlledSmoke, VulkanBerylSectionGeometryData geometryData, ControlledSmokeCommandValidation commandValidation) {
        if (!controlledSmoke.safe()) return false;
        if (commandValidation == null || !commandValidation.valid()) return false;
        long smokeSectionFirstQuad = Integer.toUnsignedLong(controlledSmoke.quadStart());
        long smokeSectionQuadCount = controlledSmoke.quadCount();
        long geometryByteStart = smokeSectionFirstQuad * 8L;
        long geometryByteEnd = geometryByteStart + smokeSectionQuadCount * 8L;
        return smokeSectionQuadCount > 0L
                && geometryByteStart >= 0L
                && geometryByteEnd <= geometryData.getUsedGeometryBytes();
    }


    private String controlledSmokeCommandMismatch(ControlledRenderListSmoke controlledSmoke, DrawCommandDebugSample sample) {
        if (sample.firstVertexCount() == 0) return "vertexCount_zero";
        if (sample.firstInstanceCount() == 0) return "instanceCount_zero";
        if (!controlledSmoke.safe()) return controlledSmoke.reason();
        long expectedVertexCount = controlledSmoke.quadCount() * 6L;
        long expectedFirstVertex = 0L;
        if (Integer.toUnsignedLong(sample.firstVertexCount()) != expectedVertexCount) return "vertexCount_mismatch_expected_section_quad_count";
        if (Integer.toUnsignedLong(sample.firstFirstVertex()) != expectedFirstVertex) return "firstVertex_mismatch_expected_section_first_quad";
        if (sample.firstFirstInstance() != 0) return "firstInstance_unexpected";
        if (sample.firstInstanceCount() != 1) return "instanceCount_unexpected";
        return null;
    }



    private void logScreenspaceSmokeSubmittedDrawCountGuard(int submittedDrawCount, String stage) {
        if (!DRAW_SCREENSPACE_SMOKE_INDIRECT) return;
        if (submittedDrawCount <= 1) return;
        VulkanBerylDebugLog.warnRateLimited("screenspace-smoke-submitted-draw-count-guard", "screenspace smoke indirect submitted more than one draw command: screenspaceSmokeIndirect=true"
                + ", submittedDrawCount=" + submittedDrawCount
                + ", expectedSubmittedDrawCount=1"
                + ", normalLodDrawSkippedForSmoke=" + ("screenspace_smoke_indirect_draw".equals(this.drawRecordedReasonThisFrame) || "none".equals(this.drawRecordedReasonThisFrame))
                + ", drawRecordedReason=" + this.drawRecordedReasonThisFrame
                + ", guardStage=" + stage
                + ", drawSubmitReason=screenspace_smoke_indirect_draw");
    }


    private void logScreenspaceSmokeSubmitDiagnostics(boolean submitted, String reason, ControlledSmokeCommandValidation commandValidation) {
        if (!screenspaceSmokeShaderEnabled()) return;
        if (DRAW_SCREENSPACE_SMOKE_INDIRECT) {
            String submitReason = submitted ? "screenspace_smoke_indirect_draw" : reason;
            String diagnostic = "screenspaceSmokeIndirect=true"
                    + ", smokeUsesKnownCommandBuffer=" + (this.controlledSmokeKnownCommandBuffer != null && this.controlledSmokeKnownCommandBuffer.getId() != 0L)
                    + ", smokeSubmittedDrawCount=" + (submitted ? 1 : 0)
                    + ", smokeCommand.vertexCount=" + SCREENSPACE_SMOKE_VERTEX_COUNT
                    + ", smokeCommand.instanceCount=" + SCREENSPACE_SMOKE_INSTANCE_COUNT
                    + ", smokeCommand.firstVertex=" + SCREENSPACE_SMOKE_FIRST_VERTEX
                    + ", smokeCommand.firstInstance=" + SCREENSPACE_SMOKE_FIRST_INSTANCE
                    + ", normalLodDrawSkippedForSmoke=true"
                    + ", indirectDrawRecorded=" + this.anyVkCmdDrawIndirectRecordedThisFrame
                    + ", drawSubmitReason=" + submitReason;
            VulkanBerylDebugLog.rateLimited("screenspace-smoke-indirect-draw-isolation", "screenspace smoke indirect draw isolation: " + diagnostic, 1);
            return;
        }
        String diagnostic = "screenspaceSmokeEnabled=true"
                + ", screenspaceSmokeDrawMode=direct_draw"
                + ", screenspaceSmokeVertexCount=" + SCREENSPACE_SMOKE_VERTEX_COUNT
                + ", screenspaceSmokeInstanceCount=" + SCREENSPACE_SMOKE_INSTANCE_COUNT
                + ", screenspaceSmokeFirstVertex=" + SCREENSPACE_SMOKE_FIRST_VERTEX
                + ", screenspaceSmokeFirstInstance=" + SCREENSPACE_SMOKE_FIRST_INSTANCE
                + ", screenspaceSmokeSubmitted=" + submitted
                + ", screenspaceSmokeSubmitReason=" + reason;
        VulkanBerylDebugLog.stateLimited("screenspace-smoke-draw-isolation", "screenspace smoke draw isolation: " + diagnostic, diagnostic);
    }


    private void logSmokeDrawOutputDiagnostics(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke, int visibleCount, int submittedDrawCount, ControlledSmokeCommandValidation commandValidation) {
        if (!controlledSmoke.enabled()) return;
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        boolean readbackMatchesCurrentExpected = controlledSmokeCommandReadbackMatchesCurrentExpected();
        boolean useCurrentReadbackValues = sample.sampledCommandCount() > 0 && (!controlledSmoke.safe() || readbackMatchesCurrentExpected);
        int commandIndex = useCurrentReadbackValues ? 0 : -1;
        long smokeDrawCommandVertexCount = useCurrentReadbackValues ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L;
        long smokeDrawCommandInstanceCount = useCurrentReadbackValues ? Integer.toUnsignedLong(sample.firstInstanceCount()) : -1L;
        long smokeDrawCommandFirstVertex = useCurrentReadbackValues ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L;
        long smokeDrawCommandFirstInstance = useCurrentReadbackValues ? Integer.toUnsignedLong(sample.firstFirstInstance()) : -1L;
        long smokeRenderListEntry0 = controlledSmoke.safe() ? Integer.toUnsignedLong(controlledSmoke.sectionId()) : -1L;
        long smokeSelectedSectionId = smokeRenderListEntry0;
        long smokeSectionFirstQuad = controlledSmoke.safe() ? Integer.toUnsignedLong(controlledSmoke.quadStart()) : -1L;
        long smokeSectionQuadCount = controlledSmoke.safe() ? controlledSmoke.quadCount() : -1L;
        long smokeExpectedVertexCount = controlledSmoke.safe() ? smokeSectionQuadCount * 6L : -1L;
        long smokeExpectedFirstVertex = controlledSmoke.safe() ? 0L : -1L;
        long smokeGeometryBytesUsed = controlledSmoke.safe() ? smokeSectionQuadCount * 8L : -1L;
        long geometryByteStart = controlledSmoke.safe() ? smokeSectionFirstQuad * 8L : -1L;
        long geometryByteEnd = controlledSmoke.safe() ? geometryByteStart + smokeGeometryBytesUsed : -1L;
        String rawPosition = "unavailable";
        if (controlledSmoke.safe()) {
            rawPosition = Integer.toUnsignedLong(geometryData.getSectionMetadataInt(controlledSmoke.sectionId(), 0))
                    + "," + Integer.toUnsignedLong(geometryData.getSectionMetadataInt(controlledSmoke.sectionId(), 1));
        }
        boolean commandLooksDrawable = controlledSmokeDrawCommandLooksDrawable(controlledSmoke, geometryData, commandValidation);
        boolean javaKnownControlledCommandActive = CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && controlledRenderListDiagnosticEnabled() && controlledSmoke.safe();
        String cmdgenCommandWriteSkippedReason;
        if (javaKnownControlledCommandActive) {
            cmdgenCommandWriteSkippedReason = "dedicated_known_buffer_overrides_shader_command";
        } else if (!useCurrentReadbackValues) {
            cmdgenCommandWriteSkippedReason = controlledSmokeCommandReadbackStaleReason().equals("none") ? (this.debugSamplePending ? "waiting_for_command_readback" : "command_not_sampled") : controlledSmokeCommandReadbackStaleReason();
        } else if (commandValidation.valid()) {
            cmdgenCommandWriteSkippedReason = "not_skipped";
        } else {
            cmdgenCommandWriteSkippedReason = commandValidation.mismatch();
        }
        String invisibleReason;
        if (!useCurrentReadbackValues) {
            invisibleReason = controlledSmokeCommandReadbackStaleReason().equals("none") ? (this.debugSamplePending ? "waiting_for_indirect_command_readback" : "indirect_command_not_sampled") : controlledSmokeCommandReadbackStaleReason();
        } else if (sample.firstVertexCount() == 0 || sample.firstInstanceCount() == 0) {
            invisibleReason = "generated_indirect_command_zero_draw";
        } else if (!controlledSmoke.safe()) {
            invisibleReason = controlledSmoke.reason();
        } else if (Integer.toUnsignedLong(sample.firstVertexCount()) != smokeExpectedVertexCount) {
            invisibleReason = "vertexCount_mismatch_expected_section_quad_count";
        } else if (Integer.toUnsignedLong(sample.firstFirstVertex()) != smokeExpectedFirstVertex) {
            invisibleReason = "firstVertex_mismatch_expected_section_first_quad";
        } else if (Integer.toUnsignedLong(sample.firstFirstInstance()) != 0L) {
            invisibleReason = "firstInstance_unexpected";
        } else if (geometryByteEnd > geometryData.getUsedGeometryBytes()) {
            invisibleReason = "section_geometry_range_outside_used_geometry";
        } else if (!screenspaceSmokeShaderEnabled() && !DRAW_WORLDSPACE_SMOKE_INDIRECT) {
            invisibleReason = "command_and_metadata_look_drawable_try_VOXY_VULKAN_BERYL_DRAW_SCREENSPACE_SMOKE_INDIRECT_or_VOXY_VULKAN_BERYL_DRAW_WORLDSPACE_SMOKE_INDIRECT";
        } else {
            invisibleReason = "screenspace_smoke_enabled_if_still_invisible_check_pipeline_renderpass_depth_output";
        }
        logWorldDrawMappingDiagnostics(viewport, geometryData, renderList, controlledSmoke, submittedDrawCount, smokeDrawCommandVertexCount, smokeDrawCommandFirstVertex, invisibleReason);
        String diagnostic = "submittedDrawCount=" + submittedDrawCount
                + ", smokeDrawCommandVertexCount=" + smokeDrawCommandVertexCount
                + ", smokeDrawCommandInstanceCount=" + smokeDrawCommandInstanceCount
                + ", smokeDrawCommandFirstVertex=" + smokeDrawCommandFirstVertex
                + ", smokeDrawCommandFirstInstance=" + smokeDrawCommandFirstInstance
                + ", controlledSmokeCommandReadbackObservedVertexCount=" + smokeDrawCommandVertexCount
                + ", controlledSmokeCommandReadbackObservedInstanceCount=" + smokeDrawCommandInstanceCount
                + ", controlledSmokeCommandReadbackObservedFirstVertex=" + smokeDrawCommandFirstVertex
                + ", controlledSmokeCommandReadbackObservedFirstInstance=" + smokeDrawCommandFirstInstance
                + ", controlledSmokeIndirectCommandMode=" + this.controlledSmokeIndirectCommandMode
                + ", controlledSmokeKnownCommandWriteInsideRenderPass=" + this.controlledSmokeKnownCommandWriteInsideRenderPass
                + ", controlledSmokeKnownCommandWriteInsideDynamicRendering=" + this.controlledSmokeKnownCommandWriteInsideDynamicRendering
                + ", controlledSmokeKnownCommandWriteMethod=" + this.controlledSmokeKnownCommandWriteMethod
                + ", controlledSmokeKnownCommandBufferId=" + (this.controlledSmokeKnownCommandBuffer == null ? 0L : this.controlledSmokeKnownCommandBuffer.getId())
                + ", controlledSmokeKnownCommandBufferHostVisible=" + this.controlledSmokeKnownCommandBufferHostVisible
                + ", controlledSmokeKnownCommandBufferHostCoherent=" + this.controlledSmokeKnownCommandBufferHostCoherent
                + ", controlledSmokeKnownCommandBufferFlushed=" + this.controlledSmokeKnownCommandBufferFlushed
                + ", controlledSmokeKnownCommandBufferBytes=" + (this.controlledSmokeKnownCommandBuffer == null ? 0L : this.controlledSmokeKnownCommandBuffer.getBufferSize())
                + ", controlledSmokeKnownCommandVertexCount=" + this.javaKnownControlledSmokeCommandVertexCount
                + ", controlledSmokeKnownCommandInstanceCount=" + this.javaKnownControlledSmokeCommandInstanceCount
                + ", controlledSmokeKnownCommandFirstVertex=" + this.javaKnownControlledSmokeCommandFirstVertex
                + ", controlledSmokeKnownCommandFirstInstance=" + this.javaKnownControlledSmokeCommandFirstInstance
                + ", controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
                + ", controlledSmokeCommandExpectedSectionId=" + this.controlledSmokeCommandExpectedSectionId
                + ", controlledSmokeCommandExpectedVertexCount=" + this.controlledSmokeCommandExpectedVertexCount
                + ", controlledSmokeCommandExpectedInstanceCount=" + this.controlledSmokeCommandExpectedInstanceCount
                + ", controlledSmokeCommandExpectedFirstVertex=" + this.controlledSmokeCommandExpectedFirstVertex
                + ", controlledSmokeCommandExpectedFirstInstance=" + this.controlledSmokeCommandExpectedFirstInstance
                + ", controlledSmokeCommandReadbackGeneration=" + this.controlledSmokeCommandReadbackCompletedGeneration
                + ", controlledSmokeCommandReadbackMatchesCurrentGeneration=" + controlledSmokeCommandReadbackMatchesCurrentGeneration()
                + ", controlledSmokeCommandReadbackMatchesCurrentExpected=" + readbackMatchesCurrentExpected
                + ", controlledSmokeCommandReadbackStale=" + (this.controlledSmokeCommandReadbackCompleted && !readbackMatchesCurrentExpected)
                + ", controlledSmokeCommandReadbackStaleReason=" + controlledSmokeCommandReadbackStaleReason()
                + ", controlledSmokeDrawUsesDedicatedCommandBuffer=" + (this.javaKnownControlledSmokeCommandWrittenThisFrame && this.controlledSmokeKnownCommandBuffer != null)
                + ", controlledSmokeDrawCommandBufferId=" + controlledSmokeActiveReadbackSourceBufferId()
                + ", controlledSmokeReadbackBufferId=" + controlledSmokeActiveReadbackSourceBufferId()
                + ", controlledSmokeReadbackMatchesDrawBuffer=true"
                + ", smokeDrawCommandIndex=" + commandIndex
                + ", renderListVisibleCount=" + visibleCount
                + ", smokeRenderListEntry0=" + smokeRenderListEntry0
                + ", smokeSelectedSectionId=" + smokeSelectedSectionId
                + ", smokeSectionRawPosition=" + rawPosition
                + ", smokeSectionQuadCount=" + smokeSectionQuadCount
                + ", smokeSectionFirstQuad=" + smokeSectionFirstQuad
                + ", smokeExpectedVertexCount=" + smokeExpectedVertexCount
                + ", smokeExpectedFirstVertex=" + smokeExpectedFirstVertex
                + ", smokeGeometryBytesUsed=" + smokeGeometryBytesUsed
                + ", smokeGeometryByteRange=" + geometryByteStart + ".." + geometryByteEnd
                + ", smokeDrawCommandLooksDrawable=" + commandLooksDrawable
                + ", smokeDrawCommandMismatch=" + commandValidation.mismatch()
                + ", diagnosticWaitReason=" + commandValidation.waitReason()
                + ", smokeDrawInvisibleReason=" + invisibleReason
                + ", drawShaderIndexing=drawIndex_gl_InstanceIndex_firstInstance_selects_indirectLookup_quadData_metadataPassQuadStart_plus_gl_VertexIndex_div_6"
                + ", drawShaderVertexIndexSemantics=Vulkan_VertexIndex_includes_firstVertex_for_non_indexed_indirect_draws"
                + ", screenspaceSmokeEnabled=" + screenspaceSmokeShaderEnabled()
                + ", cmdgenSelectedShader=" + activeCmdgenShaderName()
                + ", cmdgenCommandWritePathEnabled=" + (CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER || !CMDGEN_DISPATCH_NOOP)
                + ", cmdgenCommandWriteBinding=" + CMDGEN_DRAW_COMMAND_BINDING
                + ", cmdgenCommandWriteOffsetBytes=0"
                + ", cmdgenCommand0BeforeDispatchVertexCount=0"
                + ", cmdgenCommand0AfterDispatchVertexCount=" + smokeDrawCommandVertexCount
                + ", cmdgenCommand0AfterDispatchInstanceCount=" + smokeDrawCommandInstanceCount
                + ", cmdgenCommand0AfterDispatchFirstVertex=" + smokeDrawCommandFirstVertex
                + ", cmdgenCommand0AfterDispatchFirstInstance=" + smokeDrawCommandFirstInstance
                + ", cmdgenControlledSmokeSectionId=" + smokeSelectedSectionId
                + ", cmdgenControlledSmokeSectionQuadCount=" + smokeSectionQuadCount
                + ", cmdgenControlledSmokeExpectedVertexCount=" + smokeExpectedVertexCount
                + ", cmdgenControlledSmokeExpectedFirstVertex=" + smokeExpectedFirstVertex
                + ", cmdgenCommandWriteSkippedReason=" + cmdgenCommandWriteSkippedReason
                + ", javaKnownControlledSmokeCommandWritten=" + this.javaKnownControlledSmokeCommandWrittenThisFrame
                + ", javaKnownControlledSmokeCommandSectionId=" + this.javaKnownControlledSmokeCommandSectionId
                + ", javaKnownControlledSmokeCommandVertexCount=" + this.javaKnownControlledSmokeCommandVertexCount
                + ", javaKnownControlledSmokeCommandInstanceCount=" + this.javaKnownControlledSmokeCommandInstanceCount
                + ", javaKnownControlledSmokeCommandFirstVertex=" + this.javaKnownControlledSmokeCommandFirstVertex
                + ", javaKnownControlledSmokeCommandFirstInstance=" + this.javaKnownControlledSmokeCommandFirstInstance
                + ", javaKnownControlledSmokeCommandWriteOrder=dedicated_known_buffer_cpu_upload_before_readback_before_draw"
                + ", javaKnownControlledSmokeCommandBarrierRecorded=" + this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame
                + ", javaKnownControlledSmokeCommandTargetBufferId=" + this.javaKnownControlledSmokeCommandTargetBufferId
                + ", controlledSmokeCommandReadbackSourceBufferId=" + controlledSmokeActiveReadbackSourceBufferId()
                + ", drawCommandsBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId())
                + ", javaKnownControlledSmokeCommandTargetMatchesReadback=" + (this.javaKnownControlledSmokeCommandTargetBufferId != 0L && this.javaKnownControlledSmokeCommandTargetBufferId == controlledSmokeActiveReadbackSourceBufferId())
                + ", javaKnownControlledSmokeCommandActualOrder=" + this.javaKnownControlledSmokeCommandActualOrder
                + ", drawCommandBufferUsageFlags=" + this.drawCommandBufferUsageFlags
                + ", drawCommandBufferHasTransferDst=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0)
                + ", drawCommandBufferHasTransferSrc=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0)
                + ", drawCommandBufferHasIndirect=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0)
                + ", drawCommandBufferHasStorage=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0)
                + ", controlledSmokeKnownCommandUploadAgeFrames=" + controlledSmokeKnownCommandUploadAgeFrames(viewport.frameId)
                + ", controlledSmokeKnownCommandUploadSkipped=" + this.controlledSmokeKnownCommandUploadSkipped
                + ", controlledSmokeKnownCommandUploadSkipReason=" + this.controlledSmokeKnownCommandUploadSkipReason
                + ", controlledSmokeKnownCommandUploadTupleChanged=" + this.controlledSmokeKnownCommandUploadTupleChanged
                + ", controlledSmokeKnownCommandUploadCompletionKnown=" + controlledSmokeKnownCommandUploadCompletionKnownString(viewport.frameId)
                + ", controlledSmokeKnownCommandUploadCompletionStrategy=" + this.controlledSmokeKnownCommandUploadCompletionStrategy
                + ", controlledSmokeCommandValidationState=" + this.controlledSmokeCommandValidationState
                + ", controlledSmokeCommandCanSubmit=" + this.controlledSmokeCommandCanSubmit
                + ", drawSubmitReason=" + this.controlledSmokeCommandDrawSubmitReason
                + ", indirectDrawRecorded=" + this.controlledSmokeCommandCanSubmit;
        if (diagnostic.equals(this.lastSmokeDrawOutputDiagnostic)) return;
        this.lastSmokeDrawOutputDiagnostic = diagnostic;
        VulkanBerylDebugLog.rateLimited("controlled-smoke-draw-output-diagnostics", "Controlled smoke draw-output diagnostics: " + diagnostic, 1);
    }


    private void logWorldDrawMappingDiagnostics(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke, int submittedDrawCount, long commandVertexCount, long commandFirstVertex, String priorInvisibleReason) {
        if (!controlledSmoke.enabled()) return;
        if (!ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS && !REAL_LOD_SINGLE_QUAD_WORLD_PROBE && !REAL_QUAD_READ_CLIPSPACE_PROBE) return;
        long now = System.nanoTime();
        if (this.lastWorldDrawMappingDiagnosticLogNanos != 0L && (now - this.lastWorldDrawMappingDiagnosticLogNanos) < TEST_STATUS_INTERVAL_NANOS) {
            return;
        }
        this.lastWorldDrawMappingDiagnosticLogNanos = now;
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        boolean sampleCompleted = sample.sampledCommandCount() > 0;
        Buffer indirectDrawCommandBuffer = controlledSmokeDrawCommandBuffer(controlledSmoke);
        long indirectDrawCommandBufferId = indirectDrawCommandBuffer == null ? 0L : indirectDrawCommandBuffer.getId();
        long diagnosticFrameId = currentCmdgenDiagnosticFrameId(viewport, renderList);
        boolean sampleFrameMatchesDiagnosticFrame = cmdgenSampleFrameMatches(diagnosticFrameId);
        boolean sampleBufferMatchesActiveDrawCommandBuffer = cmdgenSampleMatchesActiveDrawCommandBuffer(indirectDrawCommandBufferId);
        CmdgenCommandSnapshot currentSnapshot = cmdgenCommandSnapshot(geometryData, renderList, controlledSmoke);
        boolean completedSampleSnapshotMatchesCurrent = completedCmdgenSampleSnapshotMatches(currentSnapshot);
        boolean pendingSampleSnapshotMatchesCurrent = pendingCmdgenSampleSnapshotMatches(currentSnapshot);
        boolean sampleValid = completedSampleSnapshotMatchesCurrent && sampleBufferMatchesActiveDrawCommandBuffer;
        String sampleRejectReason = cmdgenSampleDiagnosticReason(currentSnapshot, indirectDrawCommandBufferId);
        long sampledVertexCount = sampleCompleted ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L;
        long sampledInstanceCount = sampleCompleted ? Integer.toUnsignedLong(sample.firstInstanceCount()) : -1L;
        long sampledFirstVertex = sampleCompleted ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L;
        long sampledFirstInstance = sampleCompleted ? Integer.toUnsignedLong(sample.firstFirstInstance()) : -1L;
        commandVertexCount = sampledVertexCount;
        commandFirstVertex = sampledFirstVertex;
        RenderListEntry0Diagnostics renderListEntry0 = renderListEntry0Diagnostics(geometryData, controlledSmoke);
        int sectionId = renderListEntry0.sectionId();
        int rawPosA = renderListEntry0.valid() ? geometryData.getSectionMetadataInt(sectionId, 0) : 0;
        int rawPosB = renderListEntry0.valid() ? geometryData.getSectionMetadataInt(sectionId, 1) : 0;
        int decodedLod = rawPosA >>> 28;
        int[] decodedPos = decodeLodPosition(rawPosA, rawPosB);
        long sectionQuadStart = renderListEntry0.opaqueQuadStart();
        long sectionQuadCount = renderListEntry0.opaqueQuadCount();
        long expectedFirstVertex = renderListEntry0.expectedFirstVertex();
        long expectedVertexCount = renderListEntry0.expectedVertexCount();
        boolean commandAvailable = sampleValid && commandVertexCount >= 0L && commandFirstVertex >= 0L;
        boolean firstVertexMatches = commandAvailable && commandFirstVertex == expectedFirstVertex;
        boolean commandMatchesRenderListEntry0 = commandAvailable
                && commandVertexCount == expectedVertexCount
                && commandFirstVertex == expectedFirstVertex;
        String commandMatchesRenderListEntry0Diagnostic = commandAvailable ? Boolean.toString(commandMatchesRenderListEntry0) : "unavailable";
        String mismatchReason = commandMismatchReason(commandAvailable, commandVertexCount, commandFirstVertex, expectedVertexCount, expectedFirstVertex, sampleRejectReason);
        long cmdgenBinding0BufferId = pipelineBindingBufferId(this.commandGenPipeline, CMDGEN_RENDER_LIST_BINDING);
        long cmdgenBinding1BufferId = pipelineBindingBufferId(this.commandGenPipeline, CMDGEN_METADATA_BINDING);
        long cmdgenBinding3BufferId = pipelineBindingBufferId(this.commandGenPipeline, CMDGEN_DRAW_COMMAND_BINDING);
        long renderListDiagnosticBufferId = renderList == null || renderList.getBuffer() == null ? 0L : renderList.getBuffer().getId();
        long metadataDiagnosticBufferId = geometryData.getMetadataBuffer() == null ? 0L : geometryData.getMetadataBuffer().getId();
        long sampledCommandBufferId = this.completedDebugSampleSourceBufferId;
        GeometryQuadDiagnostics geometryQuadDiagnostics = geometryQuadDiagnostics(geometryData, sectionQuadStart, viewport.frameId);
        QuadSample quad0 = sampleQuad(geometryData, sectionQuadStart);
        QuadSample quad1 = sampleQuad(geometryData, sectionQuadStart + 1L);
        SceneDiagnostics scene = sceneDiagnostics(viewport);
        ClipDiagnostics clip = clipDiagnostics(viewport, rawPosA, rawPosB, quad0);
        ClipDiagnostics relativeClip = clipDiagnosticsForMode(viewport, rawPosA, rawPosB, quad0, true);
        ClipDiagnostics absoluteClip = clipDiagnosticsForMode(viewport, rawPosA, rawPosB, quad0, false);
        String invisibleReason = worldInvisibleReason(commandAvailable, commandMatchesRenderListEntry0, scene.finite(), quad0, clip, priorInvisibleReason, sampleRejectReason);
        double nearestDistance = nearestSectionDistanceToBase(geometryData, viewport);
        String diagnostic = "worldDrawSelectedSectionId=" + sectionId
                + ", worldDrawSectionRawPosA=" + Integer.toUnsignedLong(rawPosA)
                + ", worldDrawSectionRawPosB=" + Integer.toUnsignedLong(rawPosB)
                + ", worldDrawSectionDecodedLod=" + decodedLod
                + ", worldDrawSectionDecodedPos=" + decodedPos[0] + "," + decodedPos[1] + "," + decodedPos[2]
                + ", worldDrawSectionQuadStart=" + sectionQuadStart
                + ", worldDrawSectionQuadCount=" + sectionQuadCount
                + ", worldDrawCommandVertexCount=" + commandVertexCount
                + ", worldDrawCommandFirstVertex=" + commandFirstVertex
                + ", worldDrawExpectedFirstVertex=" + expectedFirstVertex
                + ", worldDrawFirstVertexMatchesSectionQuadStart=" + firstVertexMatches
                + ", cmdgenBinding0BufferId=" + cmdgenBinding0BufferId
                + ", renderListDiagnosticBufferId=" + renderListDiagnosticBufferId
                + ", cmdgenBinding1BufferId=" + cmdgenBinding1BufferId
                + ", metadataDiagnosticBufferId=" + metadataDiagnosticBufferId
                + ", cmdgenBinding3BufferId=" + cmdgenBinding3BufferId
                + ", sampledCommandBufferId=" + sampledCommandBufferId
                + ", indirectDrawCommandBufferId=" + indirectDrawCommandBufferId
                + ", commandBufferGeneration=" + this.drawCommandBufferAllocationGeneration
                + ", renderListFrameId=" + (renderList == null ? -1L : renderList.getLastVisibleFrameId())
                + ", cmdgenSampleExpectedFrameId=" + diagnosticFrameId
                + ", sampledCommandFrameId=" + this.completedDebugSampleFrameId
                + ", cmdgenSampleFrameMatchesRenderListFrame=" + sampleFrameMatchesDiagnosticFrame
                + ", cmdgenSampleBufferMatchesActiveDrawCommandBuffer=" + sampleBufferMatchesActiveDrawCommandBuffer
                + ", completedSampleFrameId=" + this.completedDebugSampleFrameId
                + ", pendingSampleFrameId=" + this.pendingDebugSampleFrameId
                + ", opaquePendingSampleFrameId=" + pendingDebugSampleFrameIdForPass(DrawPass.OPAQUE)
                + ", translucentPendingSampleFrameId=" + pendingDebugSampleFrameIdForPass(DrawPass.TRANSLUCENT)
                + ", opaqueCompletedSampleFrameId=" + completedDebugSampleFrameIdForPass(DrawPass.OPAQUE)
                + ", translucentCompletedSampleFrameId=" + completedDebugSampleFrameIdForPass(DrawPass.TRANSLUCENT)
                + ", completedSampleSnapshotMatchesCurrent=" + completedSampleSnapshotMatchesCurrent
                + ", pendingSampleSnapshotMatchesCurrent=" + pendingSampleSnapshotMatchesCurrent
                + ", scheduledRenderListEntry0SectionId=" + this.completedDebugSampleSnapshot.scheduledRenderListEntry0SectionId()
                + ", scheduledExpectedFirstVertex=" + this.completedDebugSampleSnapshot.expectedFirstVertex()
                + ", scheduledExpectedVertexCount=" + this.completedDebugSampleSnapshot.expectedVertexCount()
                + ", scheduledExpectedInstanceCount=" + this.completedDebugSampleSnapshot.expectedInstanceCount()
                + ", scheduledExpectedFirstInstance=" + this.completedDebugSampleSnapshot.expectedFirstInstance()
                + ", scheduledDrawCommandBufferId=" + this.completedDebugSampleSnapshot.drawCommandBufferId()
                + ", scheduledCommandBufferGeneration=" + this.completedDebugSampleSnapshot.commandBufferGeneration()
                + ", scheduledRenderListBufferId=" + this.completedDebugSampleSnapshot.renderListBufferId()
                + ", scheduledMetadataBufferId=" + this.completedDebugSampleSnapshot.metadataBufferId()
                + ", renderListEntry0SectionId=" + renderListEntry0.sectionId()
                + ", renderListEntry0QuadStart=" + renderListEntry0.quadStart()
                + ", renderListEntry0TranslucentQuadCount=" + renderListEntry0.translucentQuadCount()
                + ", renderListEntry0OpaqueQuadStart=" + renderListEntry0.opaqueQuadStart()
                + ", renderListEntry0OpaqueQuadCount=" + renderListEntry0.opaqueQuadCount()
                + ", renderListEntry0ExpectedFirstVertex=" + renderListEntry0.expectedFirstVertex()
                + ", renderListEntry0ExpectedVertexCount=" + expectedVertexCount
                + ", cmdgenSampleScheduled=" + this.cmdgenSampleScheduled
                + ", cmdgenSamplePending=" + this.debugSamplePending
                + ", cmdgenSampleCompleted=" + sampleCompleted
                + ", cmdgenSampleValid=" + sampleValid
                + ", activeDrawPass=" + this.activeDrawPass
                + ", completedSampleDrawPass=" + this.completedDebugSampleDrawPass
                + ", pendingSampleDrawPass=" + this.pendingDebugSampleDrawPass
                + ", sampleAcceptedForActivePass=" + sampleAcceptedForActivePass()
                + ", sampleRejectedReason=" + sampleRejectedReasonForActivePass()
                + ", opaquePassQuadCount=" + passDebugSampleQuadCount(DrawPass.OPAQUE)
                + ", translucentPassQuadCount=" + passDebugSampleQuadCount(DrawPass.TRANSLUCENT)
                + ", cmdgenSampleGpuCompletionKnown=" + this.pendingDebugSampleGpuCompletionKnown
                + ", cmdgenSampleRejectReason=" + sampleRejectReason
                + ", sampledCommand0.firstVertex=" + sampledFirstVertex
                + ", sampledCommand0.vertexCount=" + sampledVertexCount
                + ", sampledCommand0.instanceCount=" + sampledInstanceCount
                + ", sampledCommand0.firstInstance=" + sampledFirstInstance
                + ", expectedFirstVertex=" + expectedFirstVertex
                + ", expectedVertexCount=" + expectedVertexCount
                + ", commandMatchesRenderListEntry0=" + commandMatchesRenderListEntry0Diagnostic
                + ", mismatchReason=" + mismatchReason
                + geometryQuadDiagnostics.logFields()
                + quadDiagnostic(0, quad0)
                + quadDiagnostic(1, quad1)
                + ", worldDrawSceneUniformMvpFinite=" + scene.finite()
                + ", worldDrawSceneUniformMvpSummary=" + scene.summary()
                + ", worldDrawBaseSectionPos=" + viewport.section.x + "," + viewport.section.y + "," + viewport.section.z
                + ", worldDrawInnerTranslation=" + formatFloat(viewport.innerTranslation.x) + "," + formatFloat(viewport.innerTranslation.y) + "," + formatFloat(viewport.innerTranslation.z)
                + ", worldDrawFrameId=" + (viewport.frameId & 0x7fffffff)
                + ", worldDrawQuad0Clip0=" + clip.clip0()
                + ", worldDrawQuad0Clip1=" + clip.clip1()
                + ", worldDrawQuad0Clip2=" + clip.clip2()
                + ", worldDrawQuad0Clip3=" + clip.clip3()
                + ", worldDrawQuad0ClipLooksVisible=" + clip.looksVisible()
                + ", relativeClip0=" + relativeClip.clip0()
                + ", relativeClip1=" + relativeClip.clip1()
                + ", relativeClip2=" + relativeClip.clip2()
                + ", relativeClip3=" + relativeClip.clip3()
                + ", relativeAnyCornerIntersectsClipSpace=" + relativeClip.looksVisible()
                + ", absoluteClip0=" + absoluteClip.clip0()
                + ", absoluteClip1=" + absoluteClip.clip1()
                + ", absoluteClip2=" + absoluteClip.clip2()
                + ", absoluteClip3=" + absoluteClip.clip3()
                + ", absoluteAnyCornerIntersectsClipSpace=" + absoluteClip.looksVisible()
                + ", selectedTransformMode=relative_baseSection_innerTranslation"
                + ", worldDrawInvisibleReason=" + invisibleReason
                + ", worldspaceSmokeIndirectEnabled=" + DRAW_WORLDSPACE_SMOKE_INDIRECT
                + ", worldspaceSmokeIndirectPath=vkCmdDrawIndirect"
                + ", controlledSmokeSelectionStrategy=" + controlledSmoke.selectionStrategy()
                + ", controlledSmokeCandidateCount=" + controlledSmoke.candidateCount()
                + ", controlledSmokeSelectedDistanceToBase=" + formatDouble(controlledSmoke.distanceToBase())
                + ", visibilityCandidateCount=" + controlledSmoke.visibilityCandidateCount()
                + ", visibilityCandidateTestedCount=" + controlledSmoke.visibilityCandidateTestedCount()
                + ", visibilityCandidateClipVisibleCount=" + controlledSmoke.visibilityCandidateClipVisibleCount()
                + ", selectedVisibilityCandidateSectionId=" + controlledSmoke.selectedVisibilityCandidateSectionId()
                + ", selectedVisibilityCandidateQuadIndex=" + controlledSmoke.selectedVisibilityCandidateQuadIndex()
                + ", selectedVisibilityCandidateClipLooksVisible=" + controlledSmoke.selectedVisibilityCandidateClipLooksVisible()
                + ", selectedVisibilityCandidateRejectReason=" + controlledSmoke.selectedVisibilityCandidateRejectReason()
                + ", fallbackUsedOnlyAfterNoClipVisibleCandidate=" + controlledSmoke.fallbackUsedOnlyAfterNoClipVisibleCandidate()
                + ", voxyImportedSectionCount=unavailable"
                + ", geometrySectionCount=" + Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount())
                + ", usedGeometryBytes=" + geometryData.getUsedGeometryBytes()
                + ", nearestSectionDistanceToCamera=" + formatDouble(nearestDistance)
                + ", submittedDrawCount=" + submittedDrawCount;
        if (diagnostic.equals(this.lastWorldDrawMappingDiagnostic)) return;
        this.lastWorldDrawMappingDiagnostic = diagnostic;
        VulkanBerylDebugLog.rateLimited("controlled-world-draw-mapping-diagnostics", "Controlled world draw mapping diagnostics: " + diagnostic, 1);
    }


    private RenderListEntry0Diagnostics renderListEntry0Diagnostics(VulkanBerylSectionGeometryData geometryData, ControlledRenderListSmoke controlledSmoke) {
        if (!controlledSmoke.safe()) {
            return RenderListEntry0Diagnostics.unavailable(controlledSmoke.sectionId());
        }
        int sectionId = controlledSmoke.sectionId();
        long quadStart = Integer.toUnsignedLong(geometryData.getSectionMetadataInt(sectionId, 3));
        long translucentQuadCount = extractTranslucentQuadCount(geometryData, sectionId);
        long opaqueQuadStart = quadStart + translucentQuadCount;
        long opaqueQuadCount = extractOpaqueQuadCount(geometryData, sectionId);
        return new RenderListEntry0Diagnostics(sectionId, quadStart, translucentQuadCount, opaqueQuadStart, opaqueQuadCount, true);
    }


    private static String commandMismatchReason(boolean commandAvailable, long commandVertexCount, long commandFirstVertex, long expectedVertexCount, long expectedFirstVertex, String sampleUnavailableReason) {
        if (!commandAvailable) return sampleUnavailableReason;
        boolean firstVertexMatches = commandFirstVertex == expectedFirstVertex;
        boolean vertexCountMatches = commandVertexCount == expectedVertexCount;
        if (firstVertexMatches && vertexCountMatches) return "none";
        if (!firstVertexMatches && !vertexCountMatches) return "firstVertex_and_vertexCount_mismatch";
        if (!firstVertexMatches) return "firstVertex_mismatch";
        return "vertexCount_mismatch";
    }


    private static String worldInvisibleReason(boolean commandAvailable, boolean commandMatchesRenderListEntry0, boolean mvpFinite, QuadSample quad0, ClipDiagnostics clip, String priorInvisibleReason, String sampleUnavailableReason) {
        if (commandAvailable && !commandMatchesRenderListEntry0) return "firstVertex_or_vertexCount_mismatch";
        if (!commandAvailable) return sampleUnavailableReason;
        if (!mvpFinite) return "bad_mvp";
        if (!quad0.available()) return "geometry_quad_decode";
        if (quad0.empty()) return "geometry_quad_decode_empty_quad";
        if (quad0.sizeX() <= 0 || quad0.sizeY() <= 0) return "geometry_quad_decode_zero_quad";
        if (clip.behindCamera()) return "clip_depth_cull_behind_camera";
        if (!clip.looksVisible()) return "clip_depth_cull_offscreen_clip";
        if (priorInvisibleReason != null && priorInvisibleReason.contains("baseSection")) return "position_transform_bad_baseSectionPos";
        return "shader_indexing_or_clip_depth_cull_or_geometry_quad_decode_or_position_transform_or_debug_fragment_pipeline_state";
    }


    private static double nearestSectionDistanceToBase(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewport viewport) {
        int sectionCount = Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount());
        double nearest = Double.POSITIVE_INFINITY;
        for (int sectionId = 0; sectionId < sectionCount; sectionId++) {
            if (!geometryData.hasNonZeroSectionMetadata(sectionId)) continue;
            nearest = Math.min(nearest, distanceSectionToBase(geometryData, sectionId, viewport));
        }
        return nearest;
    }


    private GeometryQuadDiagnostics geometryQuadDiagnostics(VulkanBerylSectionGeometryData geometryData, long quadIndex, long currentFrameId) {
        long byteOffset = quadIndex < 0L ? -1L : Math.multiplyExact(quadIndex, Long.BYTES);
        long requiredBytes = byteOffset < 0L ? -1L : Math.addExact(byteOffset, Long.BYTES);
        consumePendingGeometryQuadReadbackIfReady(currentFrameId, geometryData, quadIndex, byteOffset);
        long bufferSizeBytes = geometryData.getGeometryBuffer().getBufferSize();
        long usedBytes = geometryData.getUsedGeometryBytes();
        long currentSourceBufferId = geometryData.getGeometryBuffer().getId();
        long currentGeometrySyncGeneration = geometryData.getGeometrySyncGeneration();
        boolean completedForSelectedQuad = this.geometryDiagnosticReadbackCompleted
                && this.geometryDiagnosticCompletedQuadIndex == quadIndex
                && this.geometryDiagnosticCompletedByteOffset == byteOffset
                && this.geometryDiagnosticCompletedSourceBufferId == currentSourceBufferId
                && this.geometryDiagnosticCompletedGeometrySyncGeneration == currentGeometrySyncGeneration
                && this.geometryDiagnosticCompletedDrawPass == preferredDiagnosticReadbackPass();
        long diagnosticFrameId = completedForSelectedQuad ? this.geometryDiagnosticCompletedFrameId : (this.geometryDiagnosticReadbackScheduled ? this.geometryDiagnosticPendingFrameId : -1L);
        int diagnosticRendererFrameSlot = completedForSelectedQuad ? this.geometryDiagnosticCompletedRendererFrameSlot : this.geometryDiagnosticPendingRendererFrameSlot;
        long diagnosticCommandBufferAddress = completedForSelectedQuad ? this.geometryDiagnosticCompletedCommandBufferAddress : this.geometryDiagnosticPendingCommandBufferAddress;
        DrawPass diagnosticDrawPass = completedForSelectedQuad ? this.geometryDiagnosticCompletedDrawPass : this.geometryDiagnosticPendingDrawPass;
        long diagnosticSourceBufferId = completedForSelectedQuad ? this.geometryDiagnosticCompletedSourceBufferId : this.geometryDiagnosticPendingSourceBufferId;
        long diagnosticGeometrySyncGeneration = completedForSelectedQuad ? this.geometryDiagnosticCompletedGeometrySyncGeneration : this.geometryDiagnosticPendingGeometrySyncGeneration;
        boolean inUsedRange = byteOffset >= 0L && requiredBytes <= usedBytes && requiredBytes <= bufferSizeBytes;
        String rejectReason;
        if (quadIndex < 0L) {
            rejectReason = "quad_index_negative";
        } else if (!inUsedRange) {
            rejectReason = "quad_index_out_of_used_range";
        } else if (completedForSelectedQuad) {
            rejectReason = "none";
        } else if (geometryData.getGeometryBuffer().getDataPtr() != 0L) {
            rejectReason = "host_visible_geometry_buffer";
        } else if (this.geometryDiagnosticReadbackScheduled && !this.geometryDiagnosticReadbackCopyRecorded) {
            rejectReason = "readback_staging_unavailable";
        } else if (this.geometryDiagnosticReadbackScheduled
                && this.geometryDiagnosticPendingGeometrySyncGeneration != 0L
                && this.geometryDiagnosticPendingGeometrySyncGeneration != currentGeometrySyncGeneration) {
            rejectReason = "geometry_sync_generation_changed";
        } else if (this.geometryDiagnosticReadbackScheduled
                && this.geometryDiagnosticPendingSourceBufferId != 0L
                && this.geometryDiagnosticPendingSourceBufferId != currentSourceBufferId) {
            rejectReason = "source_buffer_changed";
        } else if (this.geometryDiagnosticReadbackScheduled
                && this.geometryDiagnosticPendingDrawPass != preferredDiagnosticReadbackPass()) {
            rejectReason = "command_buffer_slot_mismatch";
        } else if (this.geometryDiagnosticReadbackScheduled
                && (this.geometryDiagnosticPendingQuadIndex != quadIndex || this.geometryDiagnosticPendingByteOffset != byteOffset)) {
            rejectReason = "selected_quad_changed";
        } else if (this.geometryDiagnosticReadbackScheduled) {
            rejectReason = this.geometryDiagnosticRejectReason == null || "scheduled".equals(this.geometryDiagnosticRejectReason) || "pending".equals(this.geometryDiagnosticRejectReason)
                    ? "pending_gpu_completion"
                    : this.geometryDiagnosticRejectReason;
        } else if (this.geometryDiagnosticReadbackCompleted
                && this.geometryDiagnosticCompletedGeometrySyncGeneration != 0L
                && this.geometryDiagnosticCompletedGeometrySyncGeneration != currentGeometrySyncGeneration) {
            rejectReason = "geometry_sync_generation_changed";
        } else if (this.geometryDiagnosticReadbackCompleted
                && this.geometryDiagnosticCompletedSourceBufferId != 0L
                && this.geometryDiagnosticCompletedSourceBufferId != currentSourceBufferId) {
            rejectReason = "source_buffer_changed";
        } else if (this.geometryDiagnosticReadbackCompleted
                && this.geometryDiagnosticCompletedDrawPass != preferredDiagnosticReadbackPass()) {
            rejectReason = "command_buffer_slot_mismatch";
        } else if (this.geometryDiagnosticReadbackCompleted
                && (this.geometryDiagnosticCompletedQuadIndex != quadIndex || this.geometryDiagnosticCompletedByteOffset != byteOffset)) {
            rejectReason = "selected_quad_changed";
        } else if (!this.geometryDiagnosticReadbackScheduled && !this.geometryDiagnosticReadbackCompleted) {
            rejectReason = "readback_not_scheduled";
        } else {
            rejectReason = this.geometryDiagnosticRejectReason;
        }
        return new GeometryQuadDiagnostics(quadIndex, byteOffset, requiredBytes, bufferSizeBytes, usedBytes, inUsedRange,
                this.geometryDiagnosticReadbackScheduled, this.geometryDiagnosticReadbackCopyRecorded, completedForSelectedQuad, rejectReason,
                diagnosticFrameId, currentFrameId, diagnosticRendererFrameSlot, diagnosticCommandBufferAddress,
                diagnosticDrawPass, diagnosticSourceBufferId, currentSourceBufferId,
                diagnosticGeometrySyncGeneration, currentGeometrySyncGeneration,
                this.geometryDiagnosticStaleCleared, this.geometryDiagnosticStaleClearReason, this.geometryDiagnosticScheduledAfterStaleClear);
    }


    private QuadSample sampleQuadFromJavaDiagnostics(VulkanBerylSectionGeometryData geometryData, long quadIndex) {
        if (quadIndex < 0L) return QuadSample.unavailable();
        long byteOffset = quadIndex * 8L;
        if (byteOffset < 0L || byteOffset + 8L > geometryData.getUsedGeometryBytes() || byteOffset + 8L > geometryData.getGeometryBuffer().getBufferSize()) {
            return QuadSample.unavailable();
        }
        long ptr = geometryData.getGeometryBuffer().getDataPtr();
        if (ptr != 0L) {
            return QuadSample.of(MemoryUtil.memGetLong(ptr + byteOffset));
        }
        if (geometryData.hasMirroredGeometryQuad(quadIndex)) {
            return QuadSample.of(geometryData.getMirroredGeometryQuad(quadIndex));
        }
        return QuadSample.unavailable();
    }


    private QuadSample sampleQuad(VulkanBerylSectionGeometryData geometryData, long quadIndex) {
        if (quadIndex < 0L) return QuadSample.unavailable();
        long byteOffset = quadIndex * 8L;
        if (byteOffset < 0L || byteOffset + 8L > geometryData.getUsedGeometryBytes() || byteOffset + 8L > geometryData.getGeometryBuffer().getBufferSize()) {
            return QuadSample.unavailable();
        }
        QuadSample javaDiagnosticSample = sampleQuadFromJavaDiagnostics(geometryData, quadIndex);
        if (javaDiagnosticSample.available()) {
            return javaDiagnosticSample;
        }
        if (this.geometryDiagnosticReadbackCompleted
                && this.geometryDiagnosticCompletedQuadIndex == quadIndex
                && this.geometryDiagnosticCompletedByteOffset == byteOffset
                && this.geometryDiagnosticCompletedSourceBufferId == geometryData.getGeometryBuffer().getId()
                && this.geometryDiagnosticCompletedGeometrySyncGeneration == geometryData.getGeometrySyncGeneration()
                && this.geometryDiagnosticCompletedDrawPass == preferredDiagnosticReadbackPass()) {
            return QuadSample.of(this.geometryDiagnosticCompletedRaw);
        }
        return QuadSample.unavailable();
    }


    private static String quadDiagnostic(int index, QuadSample quad) {
        String prefix = ", worldDrawQuad" + index;
        return prefix + "Raw=" + quad.rawString()
                + prefix + "Empty=" + quad.empty()
                + prefix + "Face=" + quad.face()
                + prefix + "Pos=" + quad.posX() + "," + quad.posY() + "," + quad.posZ()
                + prefix + "Size=" + quad.sizeX() + "," + quad.sizeY()
                + prefix + "StateId=" + quad.stateId();
    }


    private static SceneDiagnostics sceneDiagnostics(VulkanBerylViewport viewport) {
        org.joml.Matrix4f mat = new org.joml.Matrix4f(viewport.MVP);
        float[] values = new float[16];
        mat.get(values);
        boolean finite = true;
        for (float value : values) {
            finite &= Float.isFinite(value);
        }
        String summary = "m00=" + formatFloat(values[0])
                + "/m11=" + formatFloat(values[5])
                + "/m22=" + formatFloat(values[10])
                + "/m33=" + formatFloat(values[15])
                + "/m30=" + formatFloat(values[12])
                + "/m31=" + formatFloat(values[13])
                + "/m32=" + formatFloat(values[14]);
        return new SceneDiagnostics(finite, summary, mat);
    }


    private static ClipDiagnostics clipDiagnostics(VulkanBerylViewport viewport, int rawPosA, int rawPosB, QuadSample quad) {
        return clipDiagnosticsForMode(viewport, rawPosA, rawPosB, quad, true);
    }


    private static ClipDiagnostics clipDiagnosticsForMode(VulkanBerylViewport viewport, int rawPosA, int rawPosB, QuadSample quad, boolean relativeToCameraSection) {
        if (!quad.available()) return ClipDiagnostics.unavailable();
        org.joml.Matrix4f matrix = new org.joml.Matrix4f(viewport.MVP);
        if (relativeToCameraSection) {
            matrix.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        }
        int lodLevel = rawPosA >>> 28;
        float lodScale = (float) (1 << lodLevel);
        int[] lodPos = decodeLodPosition(rawPosA, rawPosB);
        int baseX = lodPos[0] << lodLevel;
        int baseY = lodPos[1] << lodLevel;
        int baseZ = lodPos[2] << lodLevel;
        if (relativeToCameraSection) {
            baseX -= viewport.section.x;
            baseY -= viewport.section.y;
            baseZ -= viewport.section.z;
        }
        float quadSizeX = Math.max(quad.sizeX(), 1.0F);
        float quadSizeY = Math.max(quad.sizeY(), 1.0F);
        org.joml.Vector4f[] clips = new org.joml.Vector4f[4];
        boolean anyVisible = false;
        boolean anyFiniteW = false;
        boolean allBehind = true;
        for (int corner = 0; corner < 4; corner++) {
            float maskX = ((corner >> 1) & 1) * lodScale;
            float maskY = (corner & 1) * lodScale;
            float dataX = quadSizeX * maskX;
            float dataY = quadSizeY * maskY;
            float sx;
            float sy;
            float sz;
            int axis = quad.face() >> 1;
            if (axis == 0) {
                sx = dataX;
                sy = 0.0F;
                sz = dataY;
            } else if (axis == 1) {
                sx = dataX;
                sy = dataY;
                sz = 0.0F;
            } else {
                sx = 0.0F;
                sy = dataX;
                sz = dataY;
            }
            float px = quad.posX() * lodScale + (baseX << 5) + sx;
            float py = quad.posY() * lodScale + (baseY << 5) + sy;
            float pz = quad.posZ() * lodScale + (baseZ << 5) + sz;
            org.joml.Vector4f clip = matrix.transform(new org.joml.Vector4f(px, py, pz, 1.0F));
            clips[corner] = clip;
            anyFiniteW |= Float.isFinite(clip.w);
            allBehind &= clip.w <= 0.0F;
            anyVisible |= clip.w > 0.0F && Math.abs(clip.x) <= Math.abs(clip.w) && Math.abs(clip.y) <= Math.abs(clip.w) && clip.z >= -Math.abs(clip.w) && clip.z <= Math.abs(clip.w);
        }
        return new ClipDiagnostics(formatVec4(clips[0]), formatVec4(clips[1]), formatVec4(clips[2]), formatVec4(clips[3]), clips, anyFiniteW, anyVisible, allBehind);
    }


    private static WorldCornerDiagnostics worldCornerDiagnostics(VulkanBerylViewport viewport, int rawPosA, int rawPosB, QuadSample quad) {
        if (!quad.available()) return WorldCornerDiagnostics.unavailable();
        int lodLevel = rawPosA >>> 28;
        float lodScale = (float) (1 << lodLevel);
        int[] lodPos = decodeLodPosition(rawPosA, rawPosB);
        int baseX = (lodPos[0] << lodLevel) - viewport.section.x;
        int baseY = (lodPos[1] << lodLevel) - viewport.section.y;
        int baseZ = (lodPos[2] << lodLevel) - viewport.section.z;
        float quadSizeX = Math.max(quad.sizeX(), 1.0F);
        float quadSizeY = Math.max(quad.sizeY(), 1.0F);
        org.joml.Vector4f[] corners = new org.joml.Vector4f[4];
        boolean finite = true;
        for (int corner = 0; corner < 4; corner++) {
            float maskX = ((corner >> 1) & 1) * lodScale;
            float maskY = (corner & 1) * lodScale;
            float dataX = quadSizeX * maskX;
            float dataY = quadSizeY * maskY;
            float sx;
            float sy;
            float sz;
            int axis = quad.face() >> 1;
            if (axis == 0) {
                sx = dataX;
                sy = 0.0F;
                sz = dataY;
            } else if (axis == 1) {
                sx = dataX;
                sy = dataY;
                sz = 0.0F;
            } else {
                sx = 0.0F;
                sy = dataX;
                sz = dataY;
            }
            float px = quad.posX() * lodScale + (baseX << 5) + sx;
            float py = quad.posY() * lodScale + (baseY << 5) + sy;
            float pz = quad.posZ() * lodScale + (baseZ << 5) + sz;
            corners[corner] = new org.joml.Vector4f(px, py, pz, 1.0F);
            finite &= Float.isFinite(px) && Float.isFinite(py) && Float.isFinite(pz);
        }
        return new WorldCornerDiagnostics(formatVec4(corners[0]), formatVec4(corners[1]), formatVec4(corners[2]), formatVec4(corners[3]),
                corners, finite, "relative_baseSection_innerTranslation");
    }


    private static String basePointDiagnostic(VulkanBerylViewport viewport, int rawPosA, int rawPosB, QuadSample quad) {
        if (!quad.available()) return "unavailable";
        int lodLevel = rawPosA >>> 28;
        float lodScale = (float) (1 << lodLevel);
        int[] lodPos = decodeLodPosition(rawPosA, rawPosB);
        int baseX = (lodPos[0] << lodLevel) - viewport.section.x;
        int baseY = (lodPos[1] << lodLevel) - viewport.section.y;
        int baseZ = (lodPos[2] << lodLevel) - viewport.section.z;
        float px = quad.posX() * lodScale + (baseX << 5);
        float py = quad.posY() * lodScale + (baseY << 5);
        float pz = quad.posZ() * lodScale + (baseZ << 5);
        return formatFloat(px) + "," + formatFloat(py) + "," + formatFloat(pz);
    }


    private static String formatVec4(org.joml.Vector4f vec) {
        return formatFloat(vec.x) + "," + formatFloat(vec.y) + "," + formatFloat(vec.z) + "," + formatFloat(vec.w);
    }


    private static String formatFloat(float value) {
        if (!Float.isFinite(value)) return Float.toString(value);
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }


    private record SceneDiagnostics(boolean finite, String summary, org.joml.Matrix4f matrix) {}

    private record RenderListEntry0Diagnostics(int sectionId, long quadStart, long translucentQuadCount, long opaqueQuadStart, long opaqueQuadCount, boolean valid) {
        static RenderListEntry0Diagnostics unavailable(int sectionId) {
            return new RenderListEntry0Diagnostics(sectionId, -1L, -1L, -1L, -1L, false);
        }

        long expectedFirstVertex() {
            return this.valid ? 0L : -1L;
        }

        long expectedVertexCount() {
            return this.valid ? this.opaqueQuadCount * 6L : -1L;
        }
    }


    private record ClipDiagnostics(String clip0, String clip1, String clip2, String clip3, org.joml.Vector4f[] rawClips, boolean anyFiniteW, boolean looksVisible, boolean behindCamera) {
        static ClipDiagnostics unavailable() { return new ClipDiagnostics("unavailable", "unavailable", "unavailable", "unavailable", null, false, false, false); }
        boolean hasRawClipValues() {
            return this.rawClips != null && this.rawClips.length == 4
                    && this.rawClips[0] != null && this.rawClips[1] != null && this.rawClips[2] != null && this.rawClips[3] != null;
        }
        org.joml.Vector4f clip(int index) {
            if (!hasRawClipValues() || index < 0 || index >= 4) throw new IllegalArgumentException("clip index unavailable: " + index);
            return this.rawClips[index];
        }
    }


    private record WorldCornerDiagnostics(String corner0, String corner1, String corner2, String corner3, org.joml.Vector4f[] rawCorners, boolean finite, String coordinateSpace) {
        static WorldCornerDiagnostics unavailable() {
            return new WorldCornerDiagnostics("unavailable", "unavailable", "unavailable", "unavailable", null, false, "unavailable");
        }
        boolean hasRawWorldValues() {
            return this.finite && this.rawCorners != null && this.rawCorners.length == 4
                    && this.rawCorners[0] != null && this.rawCorners[1] != null && this.rawCorners[2] != null && this.rawCorners[3] != null;
        }
        org.joml.Vector4f corner(int index) {
            if (!hasRawWorldValues() || index < 0 || index >= 4) throw new IllegalArgumentException("world corner index unavailable: " + index);
            return this.rawCorners[index];
        }
    }


    private record RealQuadDiagnostic(String status, int sectionId, long drawIndex, long vertexCount, long firstVertex, long quadIndex, String rawQuad, int decodedLod, String selectedReason, String baseSectionPos, String innerTranslation, String basePoint, String lodScale, String axis, String quadSizeAddin, String clip0, String clip1, String clip2, String clip3, boolean anyFiniteW, boolean intersectsClipSpace, boolean behindCamera, String commandSampleState,
                                      String relativeClip0, String relativeClip1, String relativeClip2, String relativeClip3, boolean relativeAnyCornerIntersectsClipSpace,
                                      String absoluteClip0, String absoluteClip1, String absoluteClip2, String absoluteClip3, boolean absoluteAnyCornerIntersectsClipSpace,
                                      String selectedTransformMode, String worldCorner0, String worldCorner1, String worldCorner2, String worldCorner3, String geometryDiagnosticFields) {
        static RealQuadDiagnostic unavailable(String status, long drawIndex, long vertexCount, long firstVertex, String geometryDiagnosticFields) {
            return new RealQuadDiagnostic(status, -1, drawIndex, vertexCount, firstVertex, -1L, "unavailable", -1, "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", "unavailable", false, false, false, "unavailable",
                    "unavailable", "unavailable", "unavailable", "unavailable", false,
                    "unavailable", "unavailable", "unavailable", "unavailable", false,
                    "relative_baseSection_innerTranslation", "unavailable", "unavailable", "unavailable", "unavailable", geometryDiagnosticFields);
        }

        String logFields() {
            return logFields(false);
        }

        String logFields(boolean includeGeometryDiagnosticFields) {
            return ", status=" + status
                    + ", sectionId=" + sectionId
                    + ", selectedReason=" + selectedReason
                    + ", drawIndex=" + drawIndex
                    + ", firstInstanceConvention=render_list_draw_index"
                    + ", expectedFirstCommandFirstInstance=0"
                    + ", sampledRenderListEntryForFirstInstance=" + (drawIndex == 0L ? Integer.toString(sectionId) : "unavailable_nonzero_firstInstance_requires_render_list_entry_readback")
                    + ", sampledCommand.vertexCount=" + vertexCount
                    + ", sampledCommand.firstVertex=" + firstVertex
                    + ", quadIndex=" + quadIndex
                    + ", rawQuadData=" + rawQuad
                    + ", decodedLodLevel=" + decodedLod
                    + ", baseSectionPos=" + baseSectionPos
                    + ", innerTranslation=" + innerTranslation
                    + ", basePoint=" + basePoint
                    + ", lodScale=" + lodScale
                    + ", quadSizeAddin=" + quadSizeAddin
                    + ", axis=" + axis
                    + ", clipBeforeDivide0=" + clip0
                    + ", clipBeforeDivide1=" + clip1
                    + ", clipBeforeDivide2=" + clip2
                    + ", clipBeforeDivide3=" + clip3
                    + ", finalClip0=" + clip0
                    + ", finalClip1=" + clip1
                    + ", finalClip2=" + clip2
                    + ", finalClip3=" + clip3
                    + ", finalClipLooksVisible=" + intersectsClipSpace
                    + ", relativeClip0=" + relativeClip0
                    + ", relativeClip1=" + relativeClip1
                    + ", relativeClip2=" + relativeClip2
                    + ", relativeClip3=" + relativeClip3
                    + ", relativeAnyCornerIntersectsClipSpace=" + relativeAnyCornerIntersectsClipSpace
                    + ", absoluteClip0=" + absoluteClip0
                    + ", absoluteClip1=" + absoluteClip1
                    + ", absoluteClip2=" + absoluteClip2
                    + ", absoluteClip3=" + absoluteClip3
                    + ", absoluteAnyCornerIntersectsClipSpace=" + absoluteAnyCornerIntersectsClipSpace
                    + ", selectedTransformMode=" + selectedTransformMode
                    + ", worldCorner0=" + worldCorner0
                    + ", worldCorner1=" + worldCorner1
                    + ", worldCorner2=" + worldCorner2
                    + ", worldCorner3=" + worldCorner3
                    + ", anyCornerFiniteW=" + anyFiniteW
                    + ", anyCornerIntersectsClipSpace=" + intersectsClipSpace
                    + ", allCornersBehindCamera=" + behindCamera
                    + ", commandSampleState=" + commandSampleState
                    + (includeGeometryDiagnosticFields ? geometryDiagnosticFields : "");
        }
    }


    private String realLodGpuDecodeParityLogFields(long currentFrameId, RealQuadDiagnostic cpu, VulkanBerylSectionGeometryData geometryData) {
        consumeRealLodGpuDecodeParityReadbackIfReady(currentFrameId);
        GpuDecodeParitySnapshot gpu = this.realLodGpuDecodeParitySnapshot;
        CpuDecodeParitySnapshot cpuSnapshot = this.realLodGpuDecodeParityPendingCpuSnapshot;
        logRealLodProbeBoundBufferContentDiagnostics(geometryData, cpuSnapshot, gpu);
        boolean activeSectionIdMatchesCpu = gpu.available()
                && cpuSnapshot != null
                && cpuSnapshot.available()
                && gpu.frameId() == cpuSnapshot.frameId()
                && gpu.activeSectionId() == cpuSnapshot.selectedSectionId();
        boolean activeAbsoluteQuadIndexMatchesCpu = gpu.available()
                && cpuSnapshot != null
                && cpuSnapshot.available()
                && gpu.frameId() == cpuSnapshot.frameId()
                && gpu.activeAbsoluteQuadIndex() == cpuSnapshot.selectedQuadIndex();
        boolean activePassQuadStartMatchesCpu = gpu.available()
                && cpuSnapshot != null
                && cpuSnapshot.available()
                && gpu.frameId() == cpuSnapshot.frameId()
                && gpu.activePassQuadStart() == cpuSnapshot.selectedSectionPassQuadStart();
        boolean activeSectionMetaMatchesCpu = gpu.available()
                && cpuSnapshot != null
                && cpuSnapshot.available()
                && gpu.frameId() == cpuSnapshot.frameId()
                && gpu.activeSectionMetaMatches(cpuSnapshot);
        boolean activeAbsoluteDecodeIdentityMatchesCpu = activeSectionIdMatchesCpu && activeAbsoluteQuadIndexMatchesCpu;
        long cpuRenderListEntryForDrawIndex = gpu.magicValid() && gpu.normalDrawIndex() == 0L && cpuSnapshot != null && cpuSnapshot.available()
                ? cpuSnapshot.selectedSectionId()
                : -1L;
        boolean renderListIdentityMatchesCpu = gpu.magicValid()
                && cpuSnapshot != null
                && cpuSnapshot.available()
                && gpu.normalDrawIndex() == 0L
                && gpu.normalSectionId() == cpuSnapshot.selectedSectionId();
        boolean normalIdentityWriteVerified = gpu.magicValid()
                && gpu.normalAbsoluteQuadIndex() == gpu.normalPassQuadStart() + gpu.normalLocalQuadIndex();
        boolean activeIdentityWriteVerified = gpu.magicValid()
                && (gpu.activeLocalQuadIndex() == 0xffffffffL || gpu.activeAbsoluteQuadIndex() == gpu.activePassQuadStart() + gpu.activeLocalQuadIndex());
        boolean available = gpu.available() && activeAbsoluteDecodeIdentityMatchesCpu;
        String unavailableReason = available ? "none"
                : (!gpu.magicValid() ? gpu.reason()
                : (cpuSnapshot == null || !cpuSnapshot.available() ? "cpu_snapshot_unavailable"
                : "stale_or_unmatched_gpu_record"));
        String mismatchReason = realLodProbeDecodeMismatchReason(cpuSnapshot, gpu, available, unavailableReason, activePassQuadStartMatchesCpu, activeSectionMetaMatchesCpu);
        return ", realLodProbeGpuDecodeParityAvailable=" + available
                + ", realLodProbeGpuParityRecordMatchesCpuSnapshot=" + activeAbsoluteDecodeIdentityMatchesCpu
                + ", realLodProbeGpuActiveDecodeIdentityMatchesCpuSnapshot=" + activeAbsoluteDecodeIdentityMatchesCpu
                + ", realLodProbeGpuActiveSectionIdMatchesCpu=" + activeSectionIdMatchesCpu
                + ", realLodProbeGpuActiveAbsoluteQuadIndexMatchesCpu=" + activeAbsoluteQuadIndexMatchesCpu
                + ", realLodProbeGpuActivePassQuadStartMatchesCpu=" + activePassQuadStartMatchesCpu
                + ", realLodProbeGpuActiveSectionMetaMatchesCpu=" + activeSectionMetaMatchesCpu
                + ", realLodProbeGpuActiveAbsoluteDecodeIdentityMatchesCpu=" + activeAbsoluteDecodeIdentityMatchesCpu
                + ", realLodProbeGpuParityMagicValid=" + gpu.magicValid()
                + ", realLodProbeGpuParityFrameId=" + (gpu.magicValid() ? gpu.frameId() : -1L)
                + ", realLodProbeCpuSnapshotFrameId=" + (cpuSnapshot != null && cpuSnapshot.available() ? cpuSnapshot.frameId() : -1L)
                + ", realLodProbeGpuResolvedDrawIndex=" + (gpu.magicValid() ? gpu.normalDrawIndex() : -1L)
                + ", realLodProbeGpuResolvedSectionId=" + (gpu.magicValid() ? gpu.normalSectionId() : -1)
                + ", realLodProbeGpuNormalResolvedDrawIndex=" + (gpu.magicValid() ? gpu.normalDrawIndex() : -1L)
                + ", realLodProbeGpuNormalResolvedSectionId=" + (gpu.magicValid() ? gpu.normalSectionId() : -1)
                + ", realLodProbeCpuRenderListEntryForDrawIndex=" + cpuRenderListEntryForDrawIndex
                + ", realLodProbeGpuResolvedPassQuadStart=" + (gpu.magicValid() ? gpu.normalPassQuadStart() : -1L)
                + ", realLodProbeGpuNormalResolvedPassQuadStart=" + (gpu.magicValid() ? gpu.normalPassQuadStart() : -1L)
                + ", realLodProbeCpuSelectedSectionPassQuadStart=" + (cpuSnapshot != null && cpuSnapshot.available() ? cpuSnapshot.selectedSectionPassQuadStart() : -1L)
                + ", realLodProbeGpuResolvedLocalQuadIndex=" + (gpu.magicValid() ? gpu.normalLocalQuadIndex() : -1L)
                + ", realLodProbeGpuNormalResolvedLocalQuadIndex=" + (gpu.magicValid() ? gpu.normalLocalQuadIndex() : -1L)
                + ", realLodProbeGpuResolvedAbsoluteQuadIndex=" + (gpu.magicValid() ? gpu.normalAbsoluteQuadIndex() : -1L)
                + ", realLodProbeGpuNormalResolvedAbsoluteQuadIndex=" + (gpu.magicValid() ? gpu.normalAbsoluteQuadIndex() : -1L)
                + ", realLodProbeCpuSelectedQuadIndex=" + (cpuSnapshot != null && cpuSnapshot.available() ? cpuSnapshot.selectedQuadIndex() : -1L)
                + ", realLodProbeRenderListIdentityMatchesCpu=" + renderListIdentityMatchesCpu
                + ", realLodProbeParityIdentityWriteVerified=" + activeIdentityWriteVerified
                + ", realLodProbeNormalParityIdentityWriteVerified=" + normalIdentityWriteVerified
                + ", realLodProbeActiveParityIdentityWriteVerified=" + activeIdentityWriteVerified
                + ", realLodProbeGpuActiveDecodeMode=" + (gpu.magicValid() ? gpu.activeDecodeModeName() : "unavailable")
                + ", realLodProbeGpuActiveSectionId=" + (gpu.magicValid() ? gpu.activeSectionId() : -1)
                + ", realLodProbeGpuActivePassQuadStart=" + (gpu.magicValid() ? gpu.activePassQuadStart() : -1L)
                + ", realLodProbeGpuActiveLocalQuadIndex=" + (gpu.magicValid() ? gpu.activeLocalQuadIndexString() : "unavailable")
                + ", realLodProbeGpuActiveAbsoluteQuadIndex=" + (gpu.magicValid() ? gpu.activeAbsoluteQuadIndex() : -1L)
                + ", realLodProbeGpuActiveBypassedIndirectLookup=" + (gpu.magicValid() && gpu.activeBypassedIndirectLookup())
                + ", realLodProbeGpuParitySelectedSectionId=" + (gpu.magicValid() ? gpu.activeSectionId() : -1)
                + ", realLodProbeCpuSnapshotSelectedSectionId=" + (cpuSnapshot != null && cpuSnapshot.available() ? cpuSnapshot.selectedSectionId() : -1)
                + ", realLodProbeGpuParitySelectedQuadIndex=" + (gpu.magicValid() ? gpu.activeAbsoluteQuadIndex() : -1L)
                + ", realLodProbeCpuSnapshotSelectedQuadIndex=" + (cpuSnapshot != null && cpuSnapshot.available() ? cpuSnapshot.selectedQuadIndex() : -1L)
                + ", realLodProbeGpuParityPassQuadStart=" + (gpu.magicValid() ? gpu.activePassQuadStart() : -1L)
                + ", realLodProbeCpuSnapshotSelectedSectionPassQuadStart=" + (cpuSnapshot != null && cpuSnapshot.available() ? cpuSnapshot.selectedSectionPassQuadStart() : -1L)
                + ", gpuActiveSectionMetaA0=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaA0()) : "unavailable")
                + ", gpuActiveSectionMetaA1=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaA1()) : "unavailable")
                + ", gpuActiveSectionMetaA2=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaA2()) : "unavailable")
                + ", gpuActiveSectionMetaA3=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaA3()) : "unavailable")
                + ", gpuActiveSectionMetaB0=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaB0()) : "unavailable")
                + ", gpuActiveSectionMetaB1=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaB1()) : "unavailable")
                + ", gpuActiveSectionMetaB2=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaB2()) : "unavailable")
                + ", gpuActiveSectionMetaB3=" + (gpu.magicValid() ? Integer.toUnsignedString(gpu.activeSectionMetaB3()) : "unavailable")
                + ", cpuSelectedSectionMetaA0=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaA0()) : "unavailable")
                + ", cpuSelectedSectionMetaA1=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaA1()) : "unavailable")
                + ", cpuSelectedSectionMetaA2=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaA2()) : "unavailable")
                + ", cpuSelectedSectionMetaA3=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaA3()) : "unavailable")
                + ", cpuSelectedSectionMetaB0=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaB0()) : "unavailable")
                + ", cpuSelectedSectionMetaB1=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaB1()) : "unavailable")
                + ", cpuSelectedSectionMetaB2=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaB2()) : "unavailable")
                + ", cpuSelectedSectionMetaB3=" + (cpuSnapshot != null && cpuSnapshot.available() ? Integer.toUnsignedString(cpuSnapshot.selectedSectionMetaB3()) : "unavailable")
                + ", realLodProbeGpuParityDrawIndex=" + (gpu.magicValid() ? gpu.normalDrawIndex() : -1L)
                + ", realLodProbeGpuParityLocalQuadIndex=" + (gpu.magicValid() ? gpu.activeLocalQuadIndexString() : "unavailable")
                + ", realLodProbeGpuParityCommandBufferFrameSlot=" + (gpu.magicValid() ? gpu.commandBufferFrameSlot() : -1L)
                + ", realLodProbeDecodeMismatchReason=" + mismatchReason
                + ", realLodProbeGpuDecodeParityUnavailableReason=" + unavailableReason
                + ", realLodProbeGpuDecodeParityFrameId=" + (gpu.available() ? gpu.frameId() : -1L)
                + ", realLodProbeGpuDecodeParityPending=" + this.realLodGpuDecodeParityPending
                + (available ? realLodGpuDecodeParityMatchedLogFields(cpuSnapshot, gpu) : "");
    }


    private static String realLodGpuDecodeParityMatchedLogFields(CpuDecodeParitySnapshot cpu, GpuDecodeParitySnapshot gpu) {
        return ", realLodProbeCpuRawQuadData=" + cpu.rawQuad()
                + ", realLodProbeGpuRawQuadData=" + gpu.rawQuadString()
                + ", realLodProbeCpuDecodedLodLevel=" + cpu.decodedLod()
                + ", realLodProbeGpuDecodedLodLevel=" + Integer.toUnsignedString(gpu.lodLevel())
                + ", realLodProbeCpuBasePoint=" + cpu.basePoint()
                + ", realLodProbeGpuBasePoint=" + gpu.basePoint()
                + ", realLodProbeCpuLodScale=" + cpu.lodScale()
                + ", realLodProbeGpuLodScale=" + gpu.lodScaleString()
                + ", realLodProbeCpuQuadSizeAddin=" + cpu.quadSizeAddin()
                + ", realLodProbeGpuQuadSizeAddin=" + gpu.quadSizeAddin()
                + ", realLodProbeCpuAxis=" + cpu.axis()
                + ", realLodProbeGpuAxis=" + Integer.toUnsignedString(gpu.axis())
                + ", realLodProbeCpuWorldCorner0=" + cpu.worldCorner0()
                + ", realLodProbeCpuWorldCorner1=" + cpu.worldCorner1()
                + ", realLodProbeCpuWorldCorner2=" + cpu.worldCorner2()
                + ", realLodProbeCpuWorldCorner3=" + cpu.worldCorner3()
                + ", realLodProbeGpuWorldCorner0=" + gpu.worldCorner0()
                + ", realLodProbeGpuWorldCorner1=" + gpu.worldCorner1()
                + ", realLodProbeGpuWorldCorner2=" + gpu.worldCorner2()
                + ", realLodProbeGpuWorldCorner3=" + gpu.worldCorner3();
    }


    private static String realLodProbeDecodeMismatchReason(CpuDecodeParitySnapshot cpu, GpuDecodeParitySnapshot gpu, boolean available, String unavailableReason, boolean activePassQuadStartMatchesCpu, boolean activeSectionMetaMatchesCpu) {
        if (!available) return "gpu_decode_parity_unavailable:" + unavailableReason;
        if (!activePassQuadStartMatchesCpu) return "gpu_section_meta_pass_quad_start_mismatch";
        if (!activeSectionMetaMatchesCpu) return "gpu_section_meta_raw_fields_mismatch";
        if (!Objects.equals(cpu.rawQuad(), gpu.rawQuadString())) return "gpu_raw_quad_mismatch";
        if (cpu.decodedLod() != gpu.lodLevel()
                || !Objects.equals(cpu.basePoint(), gpu.basePoint())
                || !Objects.equals(cpu.lodScale(), gpu.lodScaleString())
                || !Objects.equals(cpu.quadSizeAddin(), gpu.quadSizeAddin())
                || !Objects.equals(cpu.axis(), Integer.toUnsignedString(gpu.axis()))
                || !Objects.equals(cpu.worldCorner0(), gpu.worldCorner0())
                || !Objects.equals(cpu.worldCorner1(), gpu.worldCorner1())
                || !Objects.equals(cpu.worldCorner2(), gpu.worldCorner2())
                || !Objects.equals(cpu.worldCorner3(), gpu.worldCorner3())) {
            return "gpu_setup_quad_decode_mismatch";
        }
        return "gpu_decode_matches_but_not_visible";
    }


    private record CpuDecodeParitySnapshot(boolean available, String reason, long frameId, int selectedSectionId, long selectedQuadIndex, long selectedSectionPassQuadStart,
                                           int selectedSectionMetaA0, int selectedSectionMetaA1, int selectedSectionMetaA2, int selectedSectionMetaA3,
                                           int selectedSectionMetaB0, int selectedSectionMetaB1, int selectedSectionMetaB2, int selectedSectionMetaB3,
                                           String rawQuad, int decodedLod, String basePoint, String lodScale, String quadSizeAddin, String axis,
                                           String worldCorner0, String worldCorner1, String worldCorner2, String worldCorner3) {
        static CpuDecodeParitySnapshot unavailable(String reason) {
            return new CpuDecodeParitySnapshot(false, reason, -1L, -1, -1L, -1L,
                    0, 0, 0, 0, 0, 0, 0, 0,
                    "unavailable", -1, "unavailable", "unavailable", "unavailable", "unavailable",
                    "unavailable", "unavailable", "unavailable", "unavailable");
        }

        static CpuDecodeParitySnapshot fromDiagnostic(int frameId, RealQuadDiagnostic diagnostic, VulkanBerylSectionGeometryData geometryData, long selectedSectionPassQuadStart) {
            if (diagnostic == null || diagnostic.sectionId() < 0 || diagnostic.quadIndex() < 0L) {
                return unavailable("diagnostic_unavailable");
            }
            int sectionId = diagnostic.sectionId();
            if (geometryData == null || sectionId >= Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount()) || !geometryData.hasNonZeroSectionMetadata(sectionId)) {
                return unavailable("section_metadata_unavailable");
            }
            return new CpuDecodeParitySnapshot(true, "none", Integer.toUnsignedLong(frameId & 0x7fffffff), diagnostic.sectionId(), diagnostic.quadIndex(), selectedSectionPassQuadStart,
                    geometryData.getSectionMetadataInt(sectionId, 0), geometryData.getSectionMetadataInt(sectionId, 1),
                    geometryData.getSectionMetadataInt(sectionId, 2), geometryData.getSectionMetadataInt(sectionId, 3),
                    geometryData.getSectionMetadataInt(sectionId, 4), geometryData.getSectionMetadataInt(sectionId, 5),
                    geometryData.getSectionMetadataInt(sectionId, 6), geometryData.getSectionMetadataInt(sectionId, 7),
                    diagnostic.rawQuad(), diagnostic.decodedLod(), diagnostic.basePoint(), diagnostic.lodScale(), diagnostic.quadSizeAddin(), diagnostic.axis(),
                    diagnostic.worldCorner0(), diagnostic.worldCorner1(), diagnostic.worldCorner2(), diagnostic.worldCorner3());
        }
    }


    private record GpuDecodeParitySnapshot(boolean available, boolean magicValid, String reason, long frameId,
                                           long normalDrawIndex, int normalSectionId, long normalPassQuadStart, long normalLocalQuadIndex, long normalAbsoluteQuadIndex,
                                           int activeDecodeMode, int activeSectionId, long activePassQuadStart, long activeLocalQuadIndex, long activeAbsoluteQuadIndex, boolean activeBypassedIndirectLookup,
                                           long commandBufferFrameSlot,
                                           long rawQuad, int lodLevel, int axis,
                                           int activeSectionMetaA0, int activeSectionMetaA1, int activeSectionMetaA2, int activeSectionMetaA3,
                                           int activeSectionMetaB0, int activeSectionMetaB1, int activeSectionMetaB2, int activeSectionMetaB3,
                                           String basePoint, String lodScaleString, String quadSizeAddin,
                                           String worldCorner0, String worldCorner1, String worldCorner2, String worldCorner3) {
        static GpuDecodeParitySnapshot unavailable(String reason) {
            return new GpuDecodeParitySnapshot(false, false, reason, -1L,
                    -1L, -1, -1L, -1L, -1L,
                    -1, -1, -1L, -1L, -1L, false,
                    -1L, 0L, -1, -1,
                    0, 0, 0, 0, 0, 0, 0, 0,
                    "unavailable", "unavailable", "unavailable",
                    "unavailable", "unavailable", "unavailable", "unavailable");
        }

        static GpuDecodeParitySnapshot fromWords(int[] words) {
            if (words == null || words.length < REAL_LOD_GPU_DECODE_PARITY_WORDS) return unavailable("readback_word_count_invalid");
            if (words[0] != REAL_LOD_GPU_DECODE_PARITY_MAGIC) return unavailable("magic_mismatch:0x" + Integer.toHexString(words[0]));
            long raw = (Integer.toUnsignedLong(words[15]) << 32) | Integer.toUnsignedLong(words[14]);
            return new GpuDecodeParitySnapshot(true, true, "none", Integer.toUnsignedLong(words[1]),
                    Integer.toUnsignedLong(words[2]), words[3], Integer.toUnsignedLong(words[4]), Integer.toUnsignedLong(words[5]), Integer.toUnsignedLong(words[6]),
                    words[7], words[8], Integer.toUnsignedLong(words[9]), Integer.toUnsignedLong(words[10]), Integer.toUnsignedLong(words[11]), words[12] != 0,
                    Integer.toUnsignedLong(words[13]),
                    raw, words[16], words[17],
                    words[42], words[43], words[44], words[45], words[46], words[47], words[48], words[49],
                    formatFloat(Float.intBitsToFloat(words[18])) + "," + formatFloat(Float.intBitsToFloat(words[19])) + "," + formatFloat(Float.intBitsToFloat(words[20])),
                    formatFloat(Float.intBitsToFloat(words[21])),
                    formatFloat(Float.intBitsToFloat(words[22])) + "," + formatFloat(Float.intBitsToFloat(words[23])),
                    formatGpuWorldCorner(words, 26),
                    formatGpuWorldCorner(words, 30),
                    formatGpuWorldCorner(words, 34),
                    formatGpuWorldCorner(words, 38));
        }

        String rawQuadString() {
            return "0x" + Long.toUnsignedString(this.rawQuad, 16) + "/" + Long.toUnsignedString(this.rawQuad);
        }

        String activeDecodeModeName() {
            return switch (this.activeDecodeMode) {
                case 0 -> "real_decoded_quad";
                case 1 -> "forced_cpu_selected_quad";
                case 2 -> "force_cpu_section_and_quad";
                default -> "unknown:" + Integer.toUnsignedString(this.activeDecodeMode);
            };
        }

        String activeLocalQuadIndexString() {
            return this.activeLocalQuadIndex == 0xffffffffL ? "invalid" : Long.toUnsignedString(this.activeLocalQuadIndex);
        }

        boolean activeSectionMetaMatches(CpuDecodeParitySnapshot cpu) {
            return cpu != null
                    && this.activeSectionMetaA0 == cpu.selectedSectionMetaA0()
                    && this.activeSectionMetaA1 == cpu.selectedSectionMetaA1()
                    && this.activeSectionMetaA2 == cpu.selectedSectionMetaA2()
                    && this.activeSectionMetaA3 == cpu.selectedSectionMetaA3()
                    && this.activeSectionMetaB0 == cpu.selectedSectionMetaB0()
                    && this.activeSectionMetaB1 == cpu.selectedSectionMetaB1()
                    && this.activeSectionMetaB2 == cpu.selectedSectionMetaB2()
                    && this.activeSectionMetaB3 == cpu.selectedSectionMetaB3();
        }

        private static String formatGpuWorldCorner(int[] words, int offset) {
            return formatFloat(Float.intBitsToFloat(words[offset]))
                    + "," + formatFloat(Float.intBitsToFloat(words[offset + 1]))
                    + "," + formatFloat(Float.intBitsToFloat(words[offset + 2]))
                    + "," + formatFloat(Float.intBitsToFloat(words[offset + 3]));
        }
    }


    private record GeometryQuadDiagnostics(long quadIndex, long byteOffset, long requiredBytes, long bufferSizeBytes, long usedBytes, boolean inUsedRange, boolean readbackScheduled, boolean readbackCopyRecorded, boolean readbackCompleted, String rejectReason,
                                           long frameId, long currentFrameId, int rendererFrameSlot, long commandBufferAddress,
                                           DrawPass drawPass, long sourceBufferId, long currentSourceBufferId, long geometrySyncGeneration, long currentGeometrySyncGeneration,
                                           boolean staleCleared, String staleClearReason, boolean scheduledAfterStaleClear) {
        String logFields() {
            return ", geometryDiagnosticQuadIndex=" + quadIndex
                    + ", geometryDiagnosticByteOffset=" + byteOffset
                    + ", geometryDiagnosticRequiredBytes=" + requiredBytes
                    + ", geometryDiagnosticBufferSizeBytes=" + bufferSizeBytes
                    + ", geometryDiagnosticUsedBytes=" + usedBytes
                    + ", geometryDiagnosticInUsedRange=" + inUsedRange
                    + ", geometryDiagnosticReadbackScheduled=" + readbackScheduled
                    + ", geometryDiagnosticReadbackCopyRecorded=" + readbackCopyRecorded
                    + ", geometryDiagnosticReadbackCompleted=" + readbackCompleted
                    + ", geometryDiagnosticRejectReason=" + rejectReason
                    + ", geometryDiagnosticFrameId=" + frameId
                    + ", geometryDiagnosticCurrentFrameId=" + currentFrameId
                    + ", geometryDiagnosticRendererFrameSlot=" + rendererFrameSlot
                    + ", geometryDiagnosticCommandBufferAddress=0x" + Long.toHexString(commandBufferAddress)
                    + ", geometryDiagnosticDrawPass=" + diagnosticReadbackPassName(drawPass)
                    + ", geometryDiagnosticSourceBufferId=" + sourceBufferId
                    + ", geometryDiagnosticCurrentSourceBufferId=" + currentSourceBufferId
                    + ", geometryDiagnosticGeometrySyncGeneration=" + geometrySyncGeneration
                    + ", geometryDiagnosticCurrentGeometrySyncGeneration=" + currentGeometrySyncGeneration
                    + ", geometryDiagnosticStaleCleared=" + staleCleared
                    + ", geometryDiagnosticStaleClearReason=" + staleClearReason
                    + ", geometryDiagnosticScheduledAfterStaleClear=" + scheduledAfterStaleClear
                    + ", drawShaderQuadIndexFormula=passQuadStart+(uint(gl_VertexIndex)/6u)";
        }
    }


    private record QuadSample(boolean available, long raw, boolean empty, int face, int posX, int posY, int posZ, int sizeX, int sizeY, int stateId) {
        static QuadSample unavailable() { return new QuadSample(false, 0L, true, -1, 0, 0, 0, 0, 0, -1); }
        static QuadSample of(long raw) {
            return new QuadSample(true, raw, raw == 0L, eu32(raw, 3, 0), eu32(raw, 5, 21), eu32(raw, 5, 16), eu32(raw, 5, 11), eu32(raw, 4, 3) + 1, eu32(raw, 4, 7) + 1, eu32(raw, 16, 26));
        }
        String rawString() { return this.available ? "0x" + Long.toUnsignedString(this.raw, 16) + "/" + Long.toUnsignedString(this.raw) : "unavailable"; }
    }


    private static int eu32(long data, int amountBits, int shift) {
        return (int) ((data >>> shift) & ((1L << amountBits) - 1L));
    }


    private void recordJavaKnownControlledSmokeCommandToMainBuffer(VkCommandBuffer commandBuffer, ControlledRenderListSmoke controlledSmoke, int frameId) {
        if (!CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER || !controlledRenderListDiagnosticEnabled() || !controlledSmoke.safe()) return;

        if (CONTROLLED_SMOKE_MAIN_BUFFER_SKIP_KNOWN_COMMAND_UPLOAD) {
            this.controlledSmokeKnownCommandUploadSkipped = true;
            this.controlledSmokeKnownCommandUploadSkipReason = "main_buffer_skip_by_env";
            this.controlledSmokeKnownCommandUploadRecordedThisFrame = false;
            this.controlledSmokeKnownCommandUploadMethodThisFrame = "none";
            this.controlledSmokeIndirectCommandMode = "main_draw_command_buffer_known_command";
            this.javaKnownControlledSmokeCommandWrittenThisFrame = false;
            this.javaKnownControlledSmokeCommandTargetBufferId = this.drawCommandBuffer != null ? this.drawCommandBuffer.getId() : 0L;
            this.javaKnownControlledSmokeCommandTargetMatchesDrawBuffer = "skipped_by_env";
            this.controlledSmokeKnownCommandWriteMethod = "skipped_by_env";
            this.commandBufferSubmitRisk = "main_buffer_known_command_upload_skipped";
            this.controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
            this.controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
            this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame = false;
            this.javaKnownControlledSmokeCommandActualOrder = "not_recorded_skipped_by_env";
            VulkanBerylDebugLog.rateLimited("controlled-smoke-main-buffer-known-command-upload-skipped-by-env",
                "controlled smoke main buffer known command upload skipped by env: env=VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_MAIN_BUFFER_SKIP_KNOWN_COMMAND_UPLOAD"
                + ", controlledSmokeMainBufferKnownCommandUploadSkippedByEnv=true"
                + ", controlledSmokeKnownCommandWriteMethod=skipped_by_env"
                + ", commandBufferSubmitRisk=main_buffer_known_command_upload_skipped"
                + ", javaKnownControlledSmokeCommandWritten=false"
                + ", controlledSmokeIndirectCommandMode=main_draw_command_buffer_known_command"
                + ", javaKnownControlledSmokeCommandTargetMatchesDrawBuffer=skipped_by_env"
                + ", drawSubmitReason=main_buffer_known_command_upload_skipped"
                + ", indirectDrawRecorded=" + this.anyVkCmdDrawIndirectRecordedThisFrame
                + ", screenspaceSmokeIndirectSubmitted=pending"
                + ", screenspaceSmokeIndirectSubmitReason=waiting_for_valid_command", 60);
            return;
        }

        // Check if drawCommandBuffer has required usage bits
        boolean hasTransferDst = (this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0;
        boolean hasIndirect = (this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0;

        if (!hasTransferDst || !hasIndirect) {
            this.commandBufferSubmitRisk = "blocked_missing_usage_bits";
            this.controlledSmokeIndirectCommandMode = "main_draw_command_buffer_known_command";
            this.javaKnownControlledSmokeCommandWrittenThisFrame = false;
            this.javaKnownControlledSmokeCommandTargetBufferId = this.drawCommandBuffer != null ? this.drawCommandBuffer.getId() : 0L;
            this.javaKnownControlledSmokeCommandTargetMatchesDrawBuffer = "false";
            this.controlledSmokeKnownCommandWriteMethod = "vkCmdUpdateBuffer";
            this.controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
            this.controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
            this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame = false;
            this.javaKnownControlledSmokeCommandActualOrder = "not_recorded_missing_usage_bits";
            VulkanBerylDebugLog.warnRateLimited("controlled-smoke-main-buffer-missing-usage-bits",
                "controlled smoke diagnostic mode blocked: drawCommandBuffer missing required usage bits"
                + ", drawCommandBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId())
                + ", hasTransferDst=" + hasTransferDst
                + ", hasIndirect=" + hasIndirect
                + ", drawCommandBufferUsageFlags=" + this.drawCommandBufferUsageFlags
                + ", commandBufferSubmitRisk=" + this.commandBufferSubmitRisk);
            return;
        }

        long expectedVertexCount = controlledSmoke.quadCount() * 6L;
        long expectedFirstVertex = 0L;

        if (expectedVertexCount <= 0L || expectedVertexCount > 0xffffffffL || expectedFirstVertex > 0xffffffffL) {
            this.commandBufferSubmitRisk = "blocked_invalid_command_params";
            this.javaKnownControlledSmokeCommandWrittenThisFrame = false;
            this.controlledSmokeIndirectCommandMode = "main_draw_command_buffer_known_command";
            this.controlledSmokeKnownCommandWriteMethod = "vkCmdUpdateBuffer";
            VulkanBerylDebugLog.rateLimited("cmdgen-controlled-smoke-main-buffer-invalid-params",
                "cmdgen controlled-smoke Java-known command to main buffer skipped: invalid params"
                + ", expectedVertexCount=" + expectedVertexCount
                + ", expectedFirstVertex=" + expectedFirstVertex, 30);
            return;
        }

        // Update generation tracking
        long targetBufferId = this.drawCommandBuffer != null ? this.drawCommandBuffer.getId() : 0L;
        long previousGeneration = this.controlledSmokeCommandGeneration;
        updateControlledSmokeCommandGeneration(controlledSmoke.sectionId(), expectedVertexCount, 1, expectedFirstVertex, 0, targetBufferId);

        // Write command to drawCommandBuffer at offset 0
        // VkDrawIndirectCommand is 16 bytes: vertexCount, instanceCount, firstVertex, firstInstance (all uint32)
        final int MAIN_BUFFER_KNOWN_COMMAND_PAYLOAD_BYTES = 16;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer command = stack.malloc(MAIN_BUFFER_KNOWN_COMMAND_PAYLOAD_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            command.putInt(0, (int) expectedVertexCount);
            command.putInt(4, 1);
            command.putInt(8, (int) expectedFirstVertex);
            command.putInt(12, 0);
            VulkanBerylDebugLog.rateLimited("java-known-controlled-smoke-main-buffer-payload-diag",
                "mainBufferKnownCommandPayloadBytes=" + MAIN_BUFFER_KNOWN_COMMAND_PAYLOAD_BYTES
                + ", mainBufferKnownCommandPayloadCapacity=" + command.capacity()
                + ", mainBufferKnownCommandWriteOffsets=0,4,8,12"
                + ", mainBufferKnownCommandPayloadOrder=LITTLE_ENDIAN", 120);
            VK10.vkCmdUpdateBuffer(commandBuffer, this.drawCommandBuffer.getId(), 0L, command);
        }

        this.controlledSmokeKnownCommandUploadQueued = true;
        this.controlledSmokeKnownCommandUploadFlushed = true;
        this.controlledSmokeKnownCommandUploadRecordedThisFrame = true;
        this.controlledSmokeKnownCommandUploadMethodThisFrame = "vkCmdUpdateBuffer_to_main_draw_buffer";

        // Add barrier for indirect read
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(this.drawCommandBuffer.getId())
                    .offset(0L)
                    .size(DRAW_COMMAND_STRIDE_BYTES);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0,
                    null,
                    barrier,
                    null
            );
            this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame = true;
        }

        this.commandBufferSubmitRisk = "transfer_write_to_main_draw_buffer_before_indirect_draw";
        this.javaKnownControlledSmokeCommandWrittenThisFrame = true;
        this.javaKnownControlledSmokeCommandSectionId = controlledSmoke.sectionId();
        this.javaKnownControlledSmokeCommandVertexCount = expectedVertexCount;
        this.javaKnownControlledSmokeCommandInstanceCount = 1;
        this.javaKnownControlledSmokeCommandFirstVertex = expectedFirstVertex;
        this.javaKnownControlledSmokeCommandFirstInstance = 0;
        this.javaKnownControlledSmokeCommandTargetBufferId = targetBufferId;
        this.javaKnownControlledSmokeCommandTargetMatchesDrawBuffer = "true";
        this.javaKnownControlledSmokeCommandActualOrder = "main_draw_buffer_update_before_indirect_draw";
        this.controlledSmokeIndirectCommandMode = "main_draw_command_buffer_known_command";
        this.controlledSmokeKnownCommandWriteMethod = "vkCmdUpdateBuffer";
        this.controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
        this.controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
        this.controlledSmokeKnownCommandBufferHostVisible = false;
        this.controlledSmokeKnownCommandBufferHostCoherent = "false";
        this.controlledSmokeKnownCommandBufferFlushed = "not_needed";

        VulkanBerylDebugLog.rateLimited("cmdgen-controlled-smoke-java-known-command-main-buffer",
            "cmdgen controlled-smoke Java-known indirect command to main draw buffer: javaKnownControlledSmokeCommandWritten=" + this.javaKnownControlledSmokeCommandWrittenThisFrame
            + ", controlledSmokeIndirectCommandMode=" + this.controlledSmokeIndirectCommandMode
            + ", commandBufferSubmitRisk=" + this.commandBufferSubmitRisk
            + ", javaKnownControlledSmokeCommandTargetBufferId=" + this.javaKnownControlledSmokeCommandTargetBufferId
            + ", javaKnownControlledSmokeCommandTargetMatchesDrawBuffer=" + this.javaKnownControlledSmokeCommandTargetMatchesDrawBuffer
            + ", drawCommandsBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId())
            + ", controlledSmokeKnownCommandWriteMethod=" + this.controlledSmokeKnownCommandWriteMethod
            + ", controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
            + ", controlledSmokeCommandExpectedVertexCount=" + expectedVertexCount
            + ", controlledSmokeCommandExpectedFirstVertex=" + expectedFirstVertex, 30);
    }


    private void recordScreenspaceSmokeIndirectKnownCommand(int frameId) {
        if (!DRAW_SCREENSPACE_SMOKE_INDIRECT) return;
        ensureControlledSmokeKnownCommandBuffer();
        long targetBufferId = this.controlledSmokeKnownCommandBuffer == null ? 0L : this.controlledSmokeKnownCommandBuffer.getId();
        long previousGeneration = this.controlledSmokeCommandGeneration;
        updateControlledSmokeCommandGeneration(-2, SCREENSPACE_SMOKE_VERTEX_COUNT, SCREENSPACE_SMOKE_INSTANCE_COUNT, SCREENSPACE_SMOKE_FIRST_VERTEX, SCREENSPACE_SMOKE_FIRST_INSTANCE, targetBufferId);
        boolean tupleChanged = this.controlledSmokeCommandGeneration != previousGeneration
                || this.controlledSmokeCommandUploadGeneration != this.controlledSmokeCommandGeneration
                || this.controlledSmokeCommandUploadGeneration < 0L;
        if (tupleChanged) {
            uploadControlledSmokeKnownCommand(SCREENSPACE_SMOKE_VERTEX_COUNT, SCREENSPACE_SMOKE_INSTANCE_COUNT, SCREENSPACE_SMOKE_FIRST_VERTEX, SCREENSPACE_SMOKE_FIRST_INSTANCE);
            this.controlledSmokeCommandUploadGeneration = this.controlledSmokeCommandGeneration;
            this.controlledSmokeCommandUploadFrameId = frameId;
            this.controlledSmokeCommandUploadExpectedVertexCount = SCREENSPACE_SMOKE_VERTEX_COUNT;
            this.controlledSmokeCommandUploadExpectedInstanceCount = SCREENSPACE_SMOKE_INSTANCE_COUNT;
            this.controlledSmokeCommandUploadExpectedFirstVertex = SCREENSPACE_SMOKE_FIRST_VERTEX;
            this.controlledSmokeCommandUploadExpectedFirstInstance = SCREENSPACE_SMOKE_FIRST_INSTANCE;
            this.controlledSmokeKnownCommandUploadSkipped = false;
            this.controlledSmokeKnownCommandUploadSkipReason = "none";
            this.controlledSmokeKnownCommandUploadTupleChanged = true;
        } else {
            this.controlledSmokeKnownCommandUploadSkipped = true;
            this.controlledSmokeKnownCommandUploadSkipReason = "unchanged_expected_command";
            this.controlledSmokeKnownCommandUploadTupleChanged = false;
        }
        this.javaKnownControlledSmokeCommandWrittenThisFrame = targetBufferId != 0L;
        this.javaKnownControlledSmokeCommandSectionId = -2;
        this.javaKnownControlledSmokeCommandVertexCount = SCREENSPACE_SMOKE_VERTEX_COUNT;
        this.javaKnownControlledSmokeCommandInstanceCount = SCREENSPACE_SMOKE_INSTANCE_COUNT;
        this.javaKnownControlledSmokeCommandFirstVertex = SCREENSPACE_SMOKE_FIRST_VERTEX;
        this.javaKnownControlledSmokeCommandFirstInstance = SCREENSPACE_SMOKE_FIRST_INSTANCE;
        this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame = false;
        this.javaKnownControlledSmokeCommandTargetBufferId = targetBufferId;
        this.javaKnownControlledSmokeCommandTargetMatchesDrawBuffer = "false";
        this.javaKnownControlledSmokeCommandActualOrder = "dedicated_known_buffer_cpu_upload_before_draw";
        this.controlledSmokeIndirectCommandMode = "screenspace_smoke_dedicated_known_buffer";
        this.controlledSmokeKnownCommandWriteMethod = "staging_copy";
        this.controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
        this.controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
        this.controlledSmokeKnownCommandBufferHostVisible = this.controlledSmokeKnownCommandBuffer != null && this.controlledSmokeKnownCommandBuffer.getDataPtr() != 0L;
        this.controlledSmokeKnownCommandBufferHostCoherent = this.controlledSmokeKnownCommandBufferHostVisible ? "unknown" : "false";
        this.controlledSmokeKnownCommandBufferFlushed = this.controlledSmokeKnownCommandBufferHostVisible ? "unknown" : "not_needed";
        VulkanBerylDebugLog.rateLimited("screenspace-smoke-indirect-known-command", "screenspace smoke indirect known command recorded: drawPath=vkCmdDrawIndirect"
                + ", commandBuffer=dedicated_known_buffer"
                + ", submittedDrawCount=1"
                + ", vertexCount=" + SCREENSPACE_SMOKE_VERTEX_COUNT
                + ", instanceCount=" + SCREENSPACE_SMOKE_INSTANCE_COUNT
                + ", firstVertex=" + SCREENSPACE_SMOKE_FIRST_VERTEX
                + ", firstInstance=" + SCREENSPACE_SMOKE_FIRST_INSTANCE
                + ", shaderBypassesGeometry=true"
                + ", shaderBypassesRenderList=true"
                + ", shaderBypassesMetadata=true"
                + ", targetBufferId=" + targetBufferId
                + ", tupleChanged=" + tupleChanged, 1);
    }


    private void recordJavaKnownControlledSmokeCommand(VkCommandBuffer commandBuffer, ControlledRenderListSmoke controlledSmoke, int frameId) {
        if (CONTROLLED_SMOKE_USE_MAIN_DRAW_COMMAND_BUFFER_FOR_KNOWN_COMMAND) {
            // Diagnostic mode: write Java-known command to main drawCommandBuffer instead of dedicated buffer
            recordJavaKnownControlledSmokeCommandToMainBuffer(commandBuffer, controlledSmoke, frameId);
            return;
        }
        if (!CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER || !controlledRenderListDiagnosticEnabled() || !controlledSmoke.safe()) return;
        if (DISABLE_CONTROLLED_SMOKE_KNOWN_COMMAND_UPLOAD) {
            this.controlledSmokeKnownCommandUploadSkipped = true;
            this.controlledSmokeKnownCommandUploadSkipReason = "diagnostic_disabled_by_env";
            this.controlledSmokeKnownCommandUploadTupleChanged = false;
            this.controlledSmokeKnownCommandUploadRecordedThisFrame = false;
            this.controlledSmokeKnownCommandUploadMethodThisFrame = "none";
            VulkanBerylDebugLog.rateLimited("controlled-smoke-known-command-upload-disabled-env", "controlled smoke known command upload disabled by env: env=VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_DISABLE_KNOWN_COMMAND_UPLOAD"
                    + ", controlledSmokeKnownCommandUploadRecorded=false"
                    + ", controlledSmokeKnownCommandUploadMethod=none"
                    + ", drawSubmitReason=diagnostic_known_command_upload_disabled", 60);
            return;
        }
        long expectedVertexCount = controlledSmoke.quadCount() * 6L;
        long expectedFirstVertex = 0L;
        if (expectedVertexCount <= 0L || expectedVertexCount > 0xffffffffL || expectedFirstVertex > 0xffffffffL) {
            VulkanBerylDebugLog.rateLimited("cmdgen-controlled-smoke-java-command-skipped", "cmdgen controlled-smoke Java-known command skipped: javaKnownControlledSmokeCommandWritten=false"
                    + ", javaKnownControlledSmokeCommandSectionId=" + controlledSmoke.sectionId()
                    + ", javaKnownControlledSmokeCommandVertexCount=" + expectedVertexCount
                    + ", javaKnownControlledSmokeCommandInstanceCount=1"
                    + ", javaKnownControlledSmokeCommandFirstVertex=" + expectedFirstVertex
                    + ", javaKnownControlledSmokeCommandFirstInstance=0"
                    + ", controlledSmokeIndirectCommandMode=dedicated_known_buffer"
                    + ", controlledSmokeKnownCommandWriteMethod=staging_copy"
                    + ", controlledSmokeKnownCommandWriteInsideRenderPass=unknown"
                    + ", controlledSmokeKnownCommandWriteInsideDynamicRendering=unknown"
                    + ", javaKnownControlledSmokeCommandBarrierRecorded=false"
                    + ", cmdgenCommandWriteSkippedReason=expected_command_out_of_uint_range"
                    + ", cmdgenSelectedShader=" + activeCmdgenShaderName()
                    + ", cmdgenControlledSmokeSectionId=" + controlledSmoke.sectionId()
                    + ", cmdgenControlledSmokeSectionQuadCount=" + controlledSmoke.quadCount()
                    + ", cmdgenControlledSmokeExpectedVertexCount=" + expectedVertexCount
                    + ", cmdgenControlledSmokeExpectedFirstVertex=" + expectedFirstVertex, 60);
            return;
        }
        ensureControlledSmokeKnownCommandBuffer();
        long targetBufferId = this.controlledSmokeKnownCommandBuffer == null ? 0L : this.controlledSmokeKnownCommandBuffer.getId();
        long previousGeneration = this.controlledSmokeCommandGeneration;
        updateControlledSmokeCommandGeneration(controlledSmoke.sectionId(), expectedVertexCount, 1, expectedFirstVertex, 0, targetBufferId);
        boolean tupleChanged = this.controlledSmokeCommandGeneration != previousGeneration
                || this.controlledSmokeCommandUploadGeneration != this.controlledSmokeCommandGeneration
                || this.controlledSmokeCommandUploadGeneration < 0L;
        if (tupleChanged) {
            uploadControlledSmokeKnownCommand((int) expectedVertexCount, 1, (int) expectedFirstVertex, 0);
            this.controlledSmokeCommandUploadGeneration = this.controlledSmokeCommandGeneration;
            this.controlledSmokeCommandUploadFrameId = frameId;
            this.controlledSmokeCommandUploadExpectedVertexCount = expectedVertexCount;
            this.controlledSmokeCommandUploadExpectedInstanceCount = 1;
            this.controlledSmokeCommandUploadExpectedFirstVertex = expectedFirstVertex;
            this.controlledSmokeCommandUploadExpectedFirstInstance = 0;
            this.controlledSmokeKnownCommandUploadSkipped = false;
            this.controlledSmokeKnownCommandUploadSkipReason = "none";
            this.controlledSmokeKnownCommandUploadTupleChanged = true;
        } else {
            this.controlledSmokeKnownCommandUploadSkipped = true;
            this.controlledSmokeKnownCommandUploadSkipReason = "unchanged_expected_command";
            this.controlledSmokeKnownCommandUploadTupleChanged = false;
        }
        this.javaKnownControlledSmokeCommandWrittenThisFrame = targetBufferId != 0L;
        this.javaKnownControlledSmokeCommandSectionId = controlledSmoke.sectionId();
        this.javaKnownControlledSmokeCommandVertexCount = expectedVertexCount;
        this.javaKnownControlledSmokeCommandInstanceCount = 1;
        this.javaKnownControlledSmokeCommandFirstVertex = expectedFirstVertex;
        this.javaKnownControlledSmokeCommandFirstInstance = 0;
        this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame = false;
        this.javaKnownControlledSmokeCommandTargetBufferId = targetBufferId;
        this.javaKnownControlledSmokeCommandActualOrder = "dedicated_known_buffer_cpu_upload_before_readback_before_draw";
        this.controlledSmokeIndirectCommandMode = "dedicated_known_buffer";
        this.controlledSmokeKnownCommandWriteMethod = "staging_copy";
        this.controlledSmokeKnownCommandWriteInsideRenderPass = "unknown";
        this.controlledSmokeKnownCommandWriteInsideDynamicRendering = "unknown";
        this.controlledSmokeKnownCommandBufferHostVisible = this.controlledSmokeKnownCommandBuffer != null && this.controlledSmokeKnownCommandBuffer.getDataPtr() != 0L;
        this.controlledSmokeKnownCommandBufferHostCoherent = this.controlledSmokeKnownCommandBufferHostVisible ? "unknown" : "false";
        this.controlledSmokeKnownCommandBufferFlushed = this.controlledSmokeKnownCommandBufferHostVisible ? "unknown" : "not_needed";
        VulkanBerylDebugLog.rateLimited("cmdgen-controlled-smoke-java-known-command", "cmdgen controlled-smoke Java-known indirect command recorded: javaKnownControlledSmokeCommandWritten=" + this.javaKnownControlledSmokeCommandWrittenThisFrame
                + ", controlledSmokeIndirectCommandMode=" + this.controlledSmokeIndirectCommandMode
                + ", controlledSmokeKnownCommandWriteInsideRenderPass=" + this.controlledSmokeKnownCommandWriteInsideRenderPass
                + ", controlledSmokeKnownCommandWriteInsideDynamicRendering=" + this.controlledSmokeKnownCommandWriteInsideDynamicRendering
                + ", controlledSmokeKnownCommandWriteMethod=" + this.controlledSmokeKnownCommandWriteMethod
                + ", controlledSmokeKnownCommandBufferId=" + targetBufferId
                + ", controlledSmokeKnownCommandBufferHostVisible=" + this.controlledSmokeKnownCommandBufferHostVisible
                + ", controlledSmokeKnownCommandBufferHostCoherent=" + this.controlledSmokeKnownCommandBufferHostCoherent
                + ", controlledSmokeKnownCommandBufferFlushed=" + this.controlledSmokeKnownCommandBufferFlushed
                + ", controlledSmokeKnownCommandBufferBytes=" + DRAW_COMMAND_STRIDE_BYTES
                + ", controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
                + ", controlledSmokeCommandExpectedSectionId=" + this.controlledSmokeCommandExpectedSectionId
                + ", controlledSmokeCommandExpectedVertexCount=" + this.controlledSmokeCommandExpectedVertexCount
                + ", controlledSmokeCommandExpectedInstanceCount=" + this.controlledSmokeCommandExpectedInstanceCount
                + ", controlledSmokeCommandExpectedFirstVertex=" + this.controlledSmokeCommandExpectedFirstVertex
                + ", controlledSmokeCommandExpectedFirstInstance=" + this.controlledSmokeCommandExpectedFirstInstance
                + ", controlledSmokeCommandUploadGeneration=" + this.controlledSmokeCommandUploadGeneration
                + ", controlledSmokeKnownCommandVertexCount=" + this.javaKnownControlledSmokeCommandVertexCount
                + ", controlledSmokeKnownCommandInstanceCount=" + this.javaKnownControlledSmokeCommandInstanceCount
                + ", controlledSmokeKnownCommandFirstVertex=" + this.javaKnownControlledSmokeCommandFirstVertex
                + ", controlledSmokeKnownCommandFirstInstance=" + this.javaKnownControlledSmokeCommandFirstInstance
                + ", controlledSmokeDrawUsesDedicatedCommandBuffer=true"
                + ", controlledSmokeDrawCommandBufferId=" + targetBufferId
                + ", controlledSmokeReadbackBufferId=" + targetBufferId
                + ", controlledSmokeReadbackMatchesDrawBuffer=true"
                + ", javaKnownControlledSmokeCommandSectionId=" + this.javaKnownControlledSmokeCommandSectionId
                + ", javaKnownControlledSmokeCommandVertexCount=" + this.javaKnownControlledSmokeCommandVertexCount
                + ", javaKnownControlledSmokeCommandInstanceCount=" + this.javaKnownControlledSmokeCommandInstanceCount
                + ", javaKnownControlledSmokeCommandFirstVertex=" + this.javaKnownControlledSmokeCommandFirstVertex
                + ", javaKnownControlledSmokeCommandFirstInstance=" + this.javaKnownControlledSmokeCommandFirstInstance
                + ", javaKnownControlledSmokeCommandWriteOrder=dedicated_known_buffer_cpu_upload_before_readback_before_draw"
                + ", javaKnownControlledSmokeCommandBarrierRecorded=" + this.javaKnownControlledSmokeCommandBarrierRecordedThisFrame
                + ", javaKnownControlledSmokeCommandTargetBufferId=" + this.javaKnownControlledSmokeCommandTargetBufferId
                + ", controlledSmokeCommandReadbackSourceBufferId=" + targetBufferId
                + ", drawCommandsBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId())
                + ", javaKnownControlledSmokeCommandTargetMatchesReadback=true"
                + ", javaKnownControlledSmokeCommandActualOrder=" + this.javaKnownControlledSmokeCommandActualOrder
                + ", drawCommandBufferUsageFlags=" + this.drawCommandBufferUsageFlags
                + ", drawCommandBufferHasTransferDst=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0)
                + ", drawCommandBufferHasTransferSrc=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0)
                + ", drawCommandBufferHasIndirect=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0)
                + ", drawCommandBufferHasStorage=" + ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0)
                + ", cmdgenSelectedShader=" + activeCmdgenShaderName()
                + ", cmdgenCommandWritePathEnabled=true"
                + ", cmdgenCommandWriteBinding=" + CMDGEN_DRAW_COMMAND_BINDING
                + ", cmdgenCommandWriteOffsetBytes=0"
                + ", cmdgenCommand0BeforeDispatchVertexCount=0"
                + ", cmdgenCommand0AfterDispatchVertexCount=" + expectedVertexCount
                + ", cmdgenCommand0AfterDispatchInstanceCount=1"
                + ", cmdgenCommand0AfterDispatchFirstVertex=" + expectedFirstVertex
                + ", cmdgenCommand0AfterDispatchFirstInstance=0"
                + ", cmdgenControlledSmokeSectionId=" + controlledSmoke.sectionId()
                + ", cmdgenControlledSmokeSectionQuadCount=" + controlledSmoke.quadCount()
                + ", cmdgenControlledSmokeExpectedVertexCount=" + expectedVertexCount
                + ", cmdgenControlledSmokeExpectedFirstVertex=" + expectedFirstVertex
                + ", cmdgenCommandWriteSkippedReason=dedicated_known_buffer_overrides_shader_command", 60);
    }


    private void ensureControlledSmokeKnownCommandBuffer() {
        if (this.controlledSmokeKnownCommandBuffer != null) {
            if (this.controlledSmokeKnownCommandBuffer.getBufferSize() >= DRAW_COMMAND_STRIDE_BYTES && this.controlledSmokeKnownCommandBuffer.getId() != 0L) return;
            this.controlledSmokeKnownCommandBuffer.scheduleFree();
            this.controlledSmokeKnownCommandBuffer = null;
        }
        this.controlledSmokeKnownCommandBufferUsageFlags = VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        this.controlledSmokeKnownCommandBuffer = new Buffer("voxy_vulkanberyl_controlled_smoke_known_draw_command", this.controlledSmokeKnownCommandBufferUsageFlags, MemoryTypes.GPU_MEM);
        this.controlledSmokeKnownCommandBuffer.createBuffer(DRAW_COMMAND_STRIDE_BYTES);
    }


    private void uploadControlledSmokeKnownCommand(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (this.controlledSmokeKnownCommandBuffer == null) throw new IllegalStateException("controlled smoke known command buffer missing");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = stack.ints(vertexCount, instanceCount, firstVertex, firstInstance);
            VulkanBerylGeometryUploader uploader = VulkanBerylGeometryUploader.get();
            uploader.upload(this.controlledSmokeKnownCommandBuffer, 0L, MemoryUtil.memAddress(command), DRAW_COMMAND_STRIDE_BYTES);
            this.controlledSmokeKnownCommandUploadQueued = true;
            uploader.flush();
            this.controlledSmokeKnownCommandUploadFlushed = true;
            this.controlledSmokeKnownCommandUploadRecordedThisFrame = true;
            this.controlledSmokeKnownCommandUploadMethodThisFrame = "geometry_uploader";
        }
    }


    private Buffer controlledSmokeDrawCommandBuffer(ControlledRenderListSmoke controlledSmoke) {
        if (controlledSmoke.safe() && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && controlledRenderListDiagnosticEnabled() && CONTROLLED_SMOKE_USE_MAIN_DRAW_COMMAND_BUFFER_FOR_KNOWN_COMMAND) {
            return this.drawCommandBuffer;
        }
        if (controlledSmoke.safe() && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && controlledRenderListDiagnosticEnabled() && this.controlledSmokeKnownCommandBuffer != null) {
            return this.controlledSmokeKnownCommandBuffer;
        }
        return this.drawCommandBuffer;
    }


    private JavaDrawCountDiagnostic recordJavaDrawCountForNoDrawCountCmdgen(VkCommandBuffer commandBuffer, ControlledRenderListSmoke controlledSmoke, int visibleCount) {
        if (!CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) return new JavaDrawCountDiagnostic(visibleCount, "shader_drawcount_write");
        int drawCommandCapacity = safeDrawCommandCapacity();
        boolean safeForJavaDrawCount = visibleCount > 0 && drawCommandCapacity > 0 && this.drawCountBuffer != null && this.drawCountBuffer.getId() != 0L && this.drawCountBuffer.getBufferSize() >= Integer.BYTES;
        int javaDrawCount;
        String drawCountSource;
        if (controlledSmoke.enabled()) {
            javaDrawCount = controlledSmoke.safe() && visibleCount == 1 && drawCommandCapacity >= 1 ? 1 : 0;
            drawCountSource = javaDrawCount == 1 ? "java_controlled_smoke" : "java_conservative_zero";
        } else if (safeForJavaDrawCount) {
            javaDrawCount = Math.min(visibleCount, drawCommandCapacity);
            drawCountSource = "java_real_visible_count";
        } else {
            javaDrawCount = 0;
            drawCountSource = "java_conservative_zero";
        }
        if (this.drawCountBuffer != null && this.drawCountBuffer.getId() != 0L && this.drawCountBuffer.getBufferSize() >= Integer.BYTES) {
            VK10.vkCmdFillBuffer(commandBuffer, this.drawCountBuffer.getId(), 0L, Integer.BYTES, javaDrawCount);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT | VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.drawCountBuffer.getId())
                        .offset(0L)
                        .size(Integer.BYTES);
                VK10.vkCmdPipelineBarrier(
                        commandBuffer,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                        0,
                        null,
                        barrier,
                        null
                );
            }
        }
        VulkanBerylDebugLog.rateLimited("cmdgen-no-drawcount-write-java-drawcount", "cmdgen no-drawCount-write diagnostic active: gpuDrawCountStoreDisabled=true"
                + ", drawCountSource=" + drawCountSource
                + ", javaDrawCount=" + javaDrawCount
                + ", visibleCount=" + visibleCount
                + ", commandWritesEnabled=true"
                + ", shaderDrawCountStores=0"
                + ", shaderSelectionEnv=" + activeCmdgenShaderSelectionEnvVar()
                + ", renderListSmokeOneEntry=" + RENDERLIST_SMOKE_ONE_ENTRY
                + ", realQuadReadClipspaceProbe=" + REAL_QUAD_READ_CLIPSPACE_PROBE
                + ", realLodSingleQuadWorldProbe=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                + ", controlledSmokeSafe=" + controlledSmoke.safe()
                + ", drawCommandCapacity=" + drawCommandCapacity
                + ", indirectDrawEnabled=" + ENABLE_INDIRECT_DRAW
                + ", drawCountBufferId=" + (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId())
                + ", drawCountDescriptorBufferId=" + (cmdgenDrawCountDescriptorBuffer() == null ? 0L : cmdgenDrawCountDescriptorBuffer().getId()), 60);
        return new JavaDrawCountDiagnostic(javaDrawCount, drawCountSource);
    }


    private void logDrawSubmitHandoffDiagnostic(int renderListVisibleCountForDraw, JavaDrawCountDiagnostic javaDrawCount, boolean cmdgenDispatchRecorded, int cmdgenDispatchGroupCount, boolean indirectAllowed, boolean indirectDrawRecorded, int submittedDrawCount, String drawSubmitReason, boolean noDrawCountFullCmdgen) {
        logScreenspaceSmokeSubmittedDrawCountGuard(submittedDrawCount, "draw_handoff");
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        VulkanBerylDebugLog.rateLimited("cmdgen-renderlist-draw-handoff", "cmdgen render-list draw handoff: renderListVisibleCountForDraw=" + renderListVisibleCountForDraw
                + ", javaDrawCount=" + javaDrawCount.drawCount()
                + ", drawCountSource=" + javaDrawCount.source()
                + ", shaderDrawCountAvailable=" + (!CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && sample.sampledDrawCount() > 0)
                + ", enableIndirectDrawEnv=" + ENABLE_INDIRECT_DRAW
                + ", cmdgenDispatchRecorded=" + cmdgenDispatchRecorded
                + ", cmdgenDispatchGroupCount=" + cmdgenDispatchGroupCount
                + ", drawCommandsBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId())
                + ", drawCountBufferId=" + (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId())
                + ", indirectAllowed=" + indirectAllowed
                + ", indirectAllowedBeforeRecord=" + indirectAllowed
                + ", indirectDrawRecorded=" + indirectDrawRecorded
                + ", indirectRecordAttempted=" + indirectDrawRecorded
                + ", indirectRecordSkipReason=" + (indirectDrawRecorded ? "none" : (drawSubmitReason == null ? "not_recorded" : drawSubmitReason))
                + ", submittedDrawCount=" + submittedDrawCount
                + ", drawSubmitReason=" + drawSubmitReason
                + ", controlledSmokeCommandValidationState=" + this.controlledSmokeCommandValidationState
                + ", controlledSmokeCommandCanSubmit=" + this.controlledSmokeCommandCanSubmit
                + ", controlledSmokeKnownCommandUploadSkipped=" + this.controlledSmokeKnownCommandUploadSkipped
                + ", controlledSmokeKnownCommandUploadSkipReason=" + this.controlledSmokeKnownCommandUploadSkipReason
                + ", controlledSmokeKnownCommandUploadTupleChanged=" + this.controlledSmokeKnownCommandUploadTupleChanged
                + ", controlledSmokeKnownCommandUploadCompletionStrategy=" + this.controlledSmokeKnownCommandUploadCompletionStrategy
                + ", noDrawCountFullCmdgen=" + noDrawCountFullCmdgen
                + ", realQuadReadClipspaceProbe=" + REAL_QUAD_READ_CLIPSPACE_PROBE
                + ", realQuadReadClipspaceProbeDefineActive=" + this.sectionDrawVertexSourceContainsRealQuadReadClipspaceProbeDefine
                + ", cmdgenSampleScheduled=" + this.cmdgenSampleScheduled
                + ", cmdgenSamplePending=" + this.debugSamplePending
                + ", cmdgenSampleCompleted=" + (sample.sampledCommandCount() > 0)
                + ", cmdgenSampleValid=" + isCmdgenSampleValid()
                + ", cmdgenSampleScheduleReason=" + this.cmdgenSampleScheduleReason
                + ", activeDrawPass=" + this.activeDrawPass
                + ", completedSampleDrawPass=" + this.completedDebugSampleDrawPass
                + ", pendingSampleDrawPass=" + this.pendingDebugSampleDrawPass
                + ", sampleAcceptedForActivePass=" + sampleAcceptedForActivePass()
                + ", sampleRejectedReason=" + sampleRejectedReasonForActivePass()
                + ", opaquePendingSampleFrameId=" + pendingDebugSampleFrameIdForPass(DrawPass.OPAQUE)
                + ", translucentPendingSampleFrameId=" + pendingDebugSampleFrameIdForPass(DrawPass.TRANSLUCENT)
                + ", opaqueCompletedSampleFrameId=" + completedDebugSampleFrameIdForPass(DrawPass.OPAQUE)
                + ", translucentCompletedSampleFrameId=" + completedDebugSampleFrameIdForPass(DrawPass.TRANSLUCENT)
                + ", opaquePassQuadCount=" + passDebugSampleQuadCount(DrawPass.OPAQUE)
                + ", translucentPassQuadCount=" + passDebugSampleQuadCount(DrawPass.TRANSLUCENT)
                + ", cmdgenSampleGpuCompletionKnown=" + this.pendingDebugSampleGpuCompletionKnown
                + ", cmdgenOpaqueDecodeContract=meta.b.x_hi16_plus_b.yzw_packed_faces"
                + ", firstInstanceConvention=render_list_draw_index"
                + ", cmdgenTranslucentFlagActive=" + ((this.lastCmdGenConfigFlags & CMDGEN_FLAG_TRANSLUCENT_PASS) != 0)
                + ", cmdgenSampleRejectReason=" + cmdgenSampleDiagnosticReason()
                + ", sampledCommand0.vertexCount=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstVertexCount()) : -1L)
                + ", sampledCommand0.instanceCount=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstInstanceCount()) : -1L)
                + ", sampledCommand0.firstVertex=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstFirstVertex()) : -1L)
                + ", sampledCommand0.firstInstance=" + (sample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(sample.firstFirstInstance()) : -1L)
                + ", sampledDrawCount=" + (sample.sampledDrawCount() < 0 ? sample.sampledDrawCount() : Integer.toUnsignedLong(sample.sampledDrawCount()))
                + ", safeVisibleCount=" + (sample.safeVisibleCount() < 0 ? sample.safeVisibleCount() : Integer.toUnsignedLong(sample.safeVisibleCount()))
                + ", validCommandCount=" + (sample.validCommandCount() < 0 ? sample.validCommandCount() : Integer.toUnsignedLong(sample.validCommandCount()))
                + ", zeroCommandCount=" + (sample.zeroCommandCount() < 0 ? sample.zeroCommandCount() : Integer.toUnsignedLong(sample.zeroCommandCount()))
                + ", invalidMetadataCount=" + (sample.invalidMetadataCount() < 0 ? sample.invalidMetadataCount() : Integer.toUnsignedLong(sample.invalidMetadataCount()))
                + ", zeroOpaqueCount=" + (sample.zeroOpaqueCount() < 0 ? sample.zeroOpaqueCount() : Integer.toUnsignedLong(sample.zeroOpaqueCount()))
                + ", passQuadCountZero=" + (sample.passQuadCountZeroCount() < 0 ? sample.passQuadCountZeroCount() : Integer.toUnsignedLong(sample.passQuadCountZeroCount()))
                + ", passQuadRangeOutOfBounds=" + (sample.passQuadRangeOutOfBoundsCount() < 0 ? sample.passQuadRangeOutOfBoundsCount() : Integer.toUnsignedLong(sample.passQuadRangeOutOfBoundsCount()))
                + ", invalidMetadata=" + (sample.invalidMetadataDetailedCount() < 0 ? sample.invalidMetadataDetailedCount() : Integer.toUnsignedLong(sample.invalidMetadataDetailedCount()))
                + ", commandCapacityExceeded=" + (sample.commandCapacityExceededCount() < 0 ? sample.commandCapacityExceededCount() : Integer.toUnsignedLong(sample.commandCapacityExceededCount()))
                + ", indirectDrawGateReason=" + (indirectDrawRecorded ? "ready" : (drawSubmitReason == null ? "unknown" : drawSubmitReason)), 30);
        logControlledSmokeCommandRecordingDiagnostics(renderListVisibleCountForDraw, indirectDrawRecorded, drawSubmitReason);
    }


    private void logTestStatus(int traversalStageLimit, String stageMeaning,
                               int rawRenderListVisibleCount, int renderListVisibleCountForDraw,
                               boolean cmdgenProbeSelected, String probeEnvName,
                               String cmdgenSelectedShader, String cmdgenIsolationMode,
                               boolean cmdgenDispatchCallRecorded, boolean cmdgenPostDispatchBarrierRecorded,
                               boolean cmdgenDispatchRecorded, int cmdgenDispatchGroupCount,
                               boolean cmdgenSkipped, String cmdgenSkipReason,
                               boolean indirectAllowed, boolean indirectDrawRecorded,
                               int submittedDrawCount, String drawSubmitReason) {
        VulkanBerylDebugLog.rateLimitedRaw("test-status:section-draw", "[Voxy][VulkanBeryl][TEST_STATUS]"
                + " traversalStageLimit=" + traversalStageLimit
                + ", stageMeaning=" + stageMeaning
                + ", rawRenderListVisibleCount=" + rawRenderListVisibleCount
                + ", renderListVisibleCountForDraw=" + renderListVisibleCountForDraw
                + ", cmdgenProbeSelected=" + cmdgenProbeSelected
                + ", probeEnvName=" + (probeEnvName == null ? "none" : probeEnvName)
                + ", cmdgenSelectedShader=" + (cmdgenSelectedShader == null ? "none" : cmdgenSelectedShader)
                + ", cmdgenIsolationMode=" + (cmdgenIsolationMode == null ? "not_selected" : cmdgenIsolationMode)
                + ", enableIndirectDrawEnv=" + ENABLE_INDIRECT_DRAW
                + ", cmdgenDispatchCallRecorded=" + cmdgenDispatchCallRecorded
                + ", cmdgenPostDispatchBarrierRecorded=" + cmdgenPostDispatchBarrierRecorded
                + ", cmdgenDispatchRecorded=" + cmdgenDispatchRecorded
                + ", cmdgenDispatchGroupCount=" + cmdgenDispatchGroupCount
                + ", cmdgenSkipped=" + cmdgenSkipped
                + ", cmdgenSkipReason=" + (cmdgenSkipReason == null ? "not_applicable" : cmdgenSkipReason)
                + ", indirectAllowed=" + indirectAllowed
                + ", indirectAllowedBeforeRecord=" + indirectAllowed
                + ", indirectDrawRecorded=" + indirectDrawRecorded
                + ", indirectRecordAttempted=" + indirectDrawRecorded
                + ", indirectRecordSkipReason=" + (indirectDrawRecorded ? "none" : (drawSubmitReason == null ? "not_recorded" : drawSubmitReason))
                + ", submittedDrawCount=" + submittedDrawCount
                + ", drawSubmitReason=" + (drawSubmitReason == null ? "not_applicable" : drawSubmitReason), 20, TEST_STATUS_INTERVAL_NANOS);
    }


    private void logProbeSelectionLine(boolean cmdgenProbeSelected, String probeEnvName,
                                        String cmdgenSelectedShader, String cmdgenIsolationMode,
                                        String probeSelectionReason) {
        VulkanBerylDebugLog.once("cmdgen-probe-selection-" + probeSelectionReason,
                "cmdgen probe selection: cmdgenProbeSelected=" + cmdgenProbeSelected
                + ", probeEnvName=" + (probeEnvName == null ? "none" : probeEnvName)
                + ", cmdgenSelectedShader=" + (cmdgenSelectedShader == null ? "none" : cmdgenSelectedShader)
                + ", cmdgenIsolationMode=" + (cmdgenIsolationMode == null ? "not_selected" : cmdgenIsolationMode)
                + ", cmdgenProbeSelectionReason=" + probeSelectionReason);
    }


    private void logControlledSmokeCommandRecordingDiagnostics(int renderListVisibleCountForDraw, boolean indirectDrawRecorded, String drawSubmitReason) {
        if (!controlledRenderListDiagnosticEnabled()) return;
        boolean controlledSmokeIndirectDrawRecorded = indirectDrawRecorded
                && this.anyVkCmdDrawIndirectRecordedThisFrame
                && this.controlledSmokeCommandCanSubmit
                && "controlled_smoke_indirect_draw".equals(this.drawRecordedReasonThisFrame);
        String submitRisk = computeCommandBufferSubmitRisk();
        String snapshot = "renderListVisibleCountForDraw=" + renderListVisibleCountForDraw
                + ", controlledSmokeCommandCanSubmit=" + this.controlledSmokeCommandCanSubmit
                + ", controlledSmokeIndirectDrawRecorded=" + controlledSmokeIndirectDrawRecorded
                + ", controlledSmokeReadbackCopyRecorded=" + this.controlledSmokeReadbackCopyRecordedThisFrame
                + ", controlledSmokeReadbackBarrierRecorded=" + this.controlledSmokeReadbackBarrierRecordedThisFrame
                + ", controlledSmokeKnownCommandUploadRecorded=" + this.controlledSmokeKnownCommandUploadRecordedThisFrame
                + ", controlledSmokeKnownCommandUploadMethod=" + this.controlledSmokeKnownCommandUploadMethodThisFrame
                + ", cmdgenDispatchRecorded=" + this.cmdgenDispatchRecordedThisFrame
                + ", drawCountClearRecorded=" + this.drawCountClearCommandRecordedThisFrame
                + ", anyVkCmdDrawIndirectRecorded=" + this.anyVkCmdDrawIndirectRecordedThisFrame
                + ", anyVkCmdDrawRecorded=" + this.anyVkCmdDrawRecordedThisFrame
                + ", drawRecordedReason=" + this.drawRecordedReasonThisFrame
                + ", commandBufferSubmitRisk=" + submitRisk
                + ", drawSubmitReason=" + drawSubmitReason
                + ", controlledSmokeReadbackCopyDisabledByEnv=" + DISABLE_CONTROLLED_SMOKE_READBACK_COPY
                + ", controlledSmokeKnownCommandUploadDisabledByEnv=" + DISABLE_CONTROLLED_SMOKE_KNOWN_COMMAND_UPLOAD;
        VulkanBerylDebugLog.stateLimited("controlled-smoke-command-recording", "controlled-smoke command recording: " + snapshot, snapshot);
    }


    private String computeCommandBufferSubmitRisk() {
        if (this.anyVkCmdDrawIndirectRecordedThisFrame || this.anyVkCmdDrawRecordedThisFrame) return "stale_draw";
        if (this.controlledSmokeReadbackCopyRecordedThisFrame || this.controlledSmokeReadbackBarrierRecordedThisFrame) return "readback_copy";
        if (this.controlledSmokeKnownCommandUploadRecordedThisFrame) return "upload_copy";
        if (this.cmdgenDispatchRecordedThisFrame) return "cmdgen_dispatch";
        if (this.drawCountClearCommandRecordedThisFrame) return "unknown";
        return "none";
    }


    private NoDrawCountDiagnosticIndirectGate evaluateNoDrawCountDiagnosticIndirectGate(int javaDrawCount, boolean cmdgenDispatchSubmitted) {
        if (!CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) {
            return new NoDrawCountDiagnosticIndirectGate(false, "not_no_drawcount_diagnostic");
        }
        if (!ENABLE_INDIRECT_DRAW) {
            return new NoDrawCountDiagnosticIndirectGate(false, "indirect_draw_disabled");
        }
        if (javaDrawCount <= 0) {
            return new NoDrawCountDiagnosticIndirectGate(false, "java_drawcount_zero");
        }
        if (!cmdgenDispatchSubmitted) {
            return new NoDrawCountDiagnosticIndirectGate(false, "cmdgen_dispatch_not_submitted");
        }
        if (this.graphicsPipeline == null || !this.resourcesBound || this.commandGenPipeline == null) {
            return new NoDrawCountDiagnosticIndirectGate(false, "pipeline_or_descriptors_not_ready");
        }
        if (this.drawCommandBuffer == null || this.drawCommandBuffer.getId() == 0L) {
            return new NoDrawCountDiagnosticIndirectGate(false, "draw_command_buffer_not_ready");
        }
        if (this.drawCountBuffer == null || this.drawCountBuffer.getId() == 0L || this.drawCountBuffer.getBufferSize() < Integer.BYTES) {
            return new NoDrawCountDiagnosticIndirectGate(false, "drawcount_buffer_not_ready");
        }
        return new NoDrawCountDiagnosticIndirectGate(true, "ready");
    }


    private void logNoDrawCountDiagnosticIndirectGate(NoDrawCountDiagnosticIndirectGate gate, boolean indirectDrawAllowedThisFrame) {
        if (!CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) return;
        VulkanBerylDebugLog.rateLimited("cmdgen-no-drawcount-write-indirect-gate", "cmdgen no-drawCount-write indirect gate: indirectAllowedByNoDrawCountDiagnostic=" + gate.allowed()
                + ", indirectDrawAllowedThisFrame=" + indirectDrawAllowedThisFrame
                + ", indirectBlocker=" + (indirectDrawAllowedThisFrame ? "ready" : gate.blocker())
                + ", indirectDrawEnvEnabled=" + ENABLE_INDIRECT_DRAW, 60);
    }



    private IndirectDrawGate evaluatePostCmdgenIndirectDrawGate(boolean cmdgenSampleValid,
                                                               boolean indirectSafetyAllowed,
                                                               String frameSafetyReason,
                                                               boolean cmdgenDispatchSubmitted,
                                                               boolean cmdgenPostDispatchBarrierRecorded,
                                                               JavaDrawCountDiagnostic javaDrawCount,
                                                               int submittedDrawCount) {
        if (!ENABLE_INDIRECT_DRAW && !ENABLE_LODS) {
            return new IndirectDrawGate(false, "indirect_draw_disabled");
        }
        if (!cmdgenDispatchSubmitted) {
            return new IndirectDrawGate(false, "cmdgen_dispatch_not_submitted");
        }
        if (!cmdgenPostDispatchBarrierRecorded) {
            return new IndirectDrawGate(false, "cmdgen_post_dispatch_barrier_not_recorded");
        }
        if (!indirectSafetyAllowed) {
            return new IndirectDrawGate(false, frameSafetyReason);
        }
        if (this.drawCommandBuffer == null || this.drawCommandBuffer.getId() == 0L || this.drawCommandCapacity <= 0) {
            return new IndirectDrawGate(false, "draw_command_buffer_not_ready");
        }
        if (this.drawCountBuffer == null || this.drawCountBuffer.getId() == 0L || this.drawCountBuffer.getBufferSize() < Integer.BYTES) {
            return new IndirectDrawGate(false, "drawcount_buffer_not_ready");
        }
        if (javaDrawCount != null && CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER && javaDrawCount.drawCount() <= 0) {
            return new IndirectDrawGate(false, "java_drawcount_zero");
        }
        if (submittedDrawCount == 0) {
            return new IndirectDrawGate(false, "submitted_draw_count_zero");
        }
        return new IndirectDrawGate(true, "ready");
    }


    private boolean completedCmdgenSampleUsableForProduction(int visibleCount) {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (sample.validCommandCount() < 0 || sample.sampledDrawCount() < 0 || sample.safeVisibleCount() < 0) {
            return false;
        }
        int expectedSafeVisibleCount = Math.min(Math.max(0, visibleCount), this.drawCommandCapacity);
        if (sample.safeVisibleCount() != expectedSafeVisibleCount) {
            return false;
        }
        long activeDrawCommandBufferId = this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId();
        return activeDrawCommandBufferId != 0L
                && this.completedDebugSampleSourceBufferId == activeDrawCommandBufferId
                && this.completedDebugSampleDrawPass == this.activeDrawPass;
    }


    private boolean pendingCmdgenSampleMatchesProduction(int visibleCount) {
        if (!this.debugSamplePending) {
            return false;
        }
        int expectedSafeVisibleCount = Math.min(Math.max(0, visibleCount), this.drawCommandCapacity);
        long activeDrawCommandBufferId = this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId();
        return this.pendingDebugSampleVisibleCount == expectedSafeVisibleCount
                && activeDrawCommandBufferId != 0L
                && this.pendingDebugSampleSourceBufferId == activeDrawCommandBufferId
                && this.pendingDebugSampleDrawPass == this.activeDrawPass;
    }


    private String zeroValidCommandSkipReason(int visibleCount, boolean cmdgenSampleValid) {
        if (!CMDGEN_DEBUG_READBACK && !this.controlledSmokeCommandReadbackScheduled) {
            return null;
        }
        if (!completedCmdgenSampleUsableForProduction(visibleCount)) {
            return null;
        }
        if (!cmdgenSampleValid) {
            return null;
        }
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (sample.validCommandCount() == 0 || sample.sampledDrawCount() == 0) {
            return "skipped_zero_valid_commands";
        }
        return null;
    }


    private void logCmdgenZeroCommandReasonSplit(int visibleCount) {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        VulkanBerylDebugLog.rateLimited("cmdgen-zero-command-reason-split", "cmdgen zero-command reason split: visibleCount=" + visibleCount
                + ", sampledDrawCount=" + (sample.sampledDrawCount() < 0 ? sample.sampledDrawCount() : Integer.toUnsignedLong(sample.sampledDrawCount()))
                + ", safeVisibleCount=" + (sample.safeVisibleCount() < 0 ? sample.safeVisibleCount() : Integer.toUnsignedLong(sample.safeVisibleCount()))
                + ", validCommandCount=" + (sample.validCommandCount() < 0 ? sample.validCommandCount() : Integer.toUnsignedLong(sample.validCommandCount()))
                + ", zeroCommandCount=" + (sample.zeroCommandCount() < 0 ? sample.zeroCommandCount() : Integer.toUnsignedLong(sample.zeroCommandCount()))
                + ", passQuadCountZero=" + (sample.passQuadCountZeroCount() < 0 ? sample.passQuadCountZeroCount() : Integer.toUnsignedLong(sample.passQuadCountZeroCount()))
                + ", passQuadRangeOutOfBounds=" + (sample.passQuadRangeOutOfBoundsCount() < 0 ? sample.passQuadRangeOutOfBoundsCount() : Integer.toUnsignedLong(sample.passQuadRangeOutOfBoundsCount()))
                + ", invalidMetadata=" + (sample.invalidMetadataDetailedCount() < 0 ? sample.invalidMetadataDetailedCount() : Integer.toUnsignedLong(sample.invalidMetadataDetailedCount()))
                + ", commandCapacityExceeded=" + (sample.commandCapacityExceededCount() < 0 ? sample.commandCapacityExceededCount() : Integer.toUnsignedLong(sample.commandCapacityExceededCount()))
                + ", zeroOpaqueCount=" + (sample.zeroOpaqueCount() < 0 ? sample.zeroOpaqueCount() : Integer.toUnsignedLong(sample.zeroOpaqueCount()))
                + ", drawCommandCapacity=" + this.drawCommandCapacity
                + ", geometryCapacityQuads=" + this.lastCmdGenConfigGeometryCapacityQuads, 30);
    }


    private String productionDrawSubmitReason(boolean cmdgenSampleValid, int visibleCount) {
        if (cmdgenSampleValid || completedCmdgenSampleUsableForProduction(visibleCount)) {
            return "submitted_real_commands";
        }
        if (this.debugSamplePending) {
            return "submitted_debug_sample_pending";
        }
        return "submitted_real_commands";
    }


    private int safeDrawCommandCapacity() {
        if (this.drawCommandBuffer == null || this.drawCommandBuffer.getId() == 0L) return 0;
        long capacity = this.drawCommandBuffer.getBufferSize() / DRAW_COMMAND_STRIDE_BYTES;
        return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, capacity);
    }



    private OpaqueDrawSubmission dispatchMetadataBindingProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ComputePipeline pipeline, Buffer metadataProbeBuffer, int metadataBinding, String stage) {
        if (metadataProbeBuffer == this.cmdGenTinyMetadataProbeBuffer) {
            uploadTinyMetadataProbeBuffer(commandBuffer);
        }
        logMetadataBufferState(geometryData, stage, metadataProbeBuffer, metadataBinding);
        if (metadataBinding == CMDGEN_RENDER_LIST_BINDING) {
            return dispatchMinimalSsboReadProbe(commandBuffer, visibleCount, pipeline, metadataProbeBuffer, metadataBinding, CMDGEN_RAW_METADATA_UVEC4_BINDING0_SHADER_NAME, stage, false);
        }
        bindFullLayoutProbeDescriptors(pipeline, geometryData, renderList);
        bindPipelineStorageBinding(pipeline, metadataBinding, metadataProbeBuffer, metadataProbeBuffer == this.cmdGenTinyMetadataProbeBuffer ? "cmdGenTinyMetadataProbeBuffer" : "geometryData.metadataBuffer");
        logPipelineBindingState(pipeline, metadataBinding, metadataProbeBuffer, stage, metadataProbeBuffer == this.cmdGenTinyMetadataProbeBuffer ? "cmdGenTinyMetadataProbeBuffer" : "geometryData.metadataBuffer");
        logCmdGenConfigBufferState("before_dispatch:" + stage);
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen metadata binding probe dispatch submitted: stage=" + stage
                + ", metadataBinding=" + metadataBinding
                + ", metadataBufferId=" + metadataProbeBuffer.getId()
                + ", descriptorRangeBytes=" + metadataProbeBuffer.getBufferSize());
        return completeProbeDispatch(visibleCount, stage);
    }


    private OpaqueDrawSubmission dispatchBinding2ProbeBufferNoConfigProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ComputePipeline pipeline, String stage) {
        if (pipeline == null) throw new IllegalStateException("cmdgen full-layout binding2 probe-buffer no-config probe pipeline missing: stage=" + stage);
        bindFullLayoutProbeDescriptors(pipeline, geometryData, renderList);
        logPipelineBindingState(pipeline, CMDGEN_BINDING2_PROBE_BINDING, this.cmdGenBinding2ProbeBuffer, stage, "cmdGenBinding2ProbeBuffer");
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen binding2 probe-buffer no-config probe dispatch submitted: stage=" + stage
                + ", binding2BufferId=" + this.cmdGenBinding2ProbeBuffer.getId()
                + ", binding2DescriptorRangeBytes=" + this.cmdGenBinding2ProbeBuffer.getBufferSize());
        return completeProbeDispatch(visibleCount, stage);
    }


    private OpaqueDrawSubmission dispatchBinding2ProbeBufferConstProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ComputePipeline pipeline, String stage) {
        if (pipeline == null) throw new IllegalStateException("cmdgen full-layout binding2 probe-buffer const probe pipeline missing: stage=" + stage);
        if (!this.cmdGenConfigUploaded) {
            throw new IllegalStateException("cmdGenConfigBuffer full-layout binding2 probe-buffer const probe requested before config upload: stage=" + stage);
        }
        bindFullLayoutProbeDescriptors(pipeline, geometryData, renderList);
        logPipelineBindingState(pipeline, CMDGEN_BINDING2_PROBE_BINDING, this.cmdGenBinding2ProbeBuffer, stage, "cmdGenBinding2ProbeBuffer");
        logCmdGenConfigBufferState("before_dispatch:" + stage);
        barrierTransferToCompute(commandBuffer);
        logCmdgenProbeTransferBarrierAfterUploads(stage, false, true, true);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen binding2 probe-buffer const probe dispatch submitted: stage=" + stage
                + ", binding2BufferId=" + this.cmdGenBinding2ProbeBuffer.getId()
                + ", binding2DescriptorRangeBytes=" + this.cmdGenBinding2ProbeBuffer.getBufferSize());
        return completeProbeDispatch(visibleCount, stage);
    }


    private OpaqueDrawSubmission dispatchBinding1AndBinding2NoConfigProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ComputePipeline pipeline, Buffer binding2ProbeBuffer, String stage) {
        if (pipeline == null) throw new IllegalStateException("cmdgen full-layout binding1/binding2 no-config probe pipeline missing: stage=" + stage);
        uploadTinyMetadataProbeBuffer(commandBuffer);
        bindFullLayoutProbeDescriptors(pipeline, geometryData, renderList);
        bindPipelineStorageBinding(pipeline, CMDGEN_METADATA_BINDING, this.cmdGenTinyMetadataProbeBuffer, "cmdGenTinyMetadataProbeBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_BINDING2_PROBE_BINDING, binding2ProbeBuffer, binding2ProbeBuffer == this.cmdGenTinyMetadataProbeBuffer ? "cmdGenTinyMetadataProbeBuffer" : "cmdGenBinding2ProbeBuffer");
        logMetadataBufferState(geometryData, stage, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING);
        logPipelineBindingState(pipeline, CMDGEN_METADATA_BINDING, this.cmdGenTinyMetadataProbeBuffer, stage, "cmdGenTinyMetadataProbeBuffer");
        logPipelineBindingState(pipeline, CMDGEN_BINDING2_PROBE_BINDING, binding2ProbeBuffer, stage, binding2ProbeBuffer == this.cmdGenTinyMetadataProbeBuffer ? "cmdGenTinyMetadataProbeBuffer" : "cmdGenBinding2ProbeBuffer");
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen binding1/binding2 no-config probe dispatch submitted: stage=" + stage
                + ", binding1BufferId=" + this.cmdGenTinyMetadataProbeBuffer.getId()
                + ", binding1DescriptorRangeBytes=" + this.cmdGenTinyMetadataProbeBuffer.getBufferSize()
                + ", binding2BufferId=" + binding2ProbeBuffer.getId()
                + ", binding2DescriptorRangeBytes=" + binding2ProbeBuffer.getBufferSize());
        return completeProbeDispatch(visibleCount, stage);
    }


    private OpaqueDrawSubmission dispatchBinding1AndBinding2ConstProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ComputePipeline pipeline, String stage) {
        if (pipeline == null) throw new IllegalStateException("cmdgen full-layout binding1/binding2 const probe pipeline missing: stage=" + stage);
        if (!this.cmdGenConfigUploaded) {
            throw new IllegalStateException("cmdGenConfigBuffer full-layout binding1/binding2 const probe requested before config upload: stage=" + stage);
        }
        uploadTinyMetadataProbeBuffer(commandBuffer);
        bindFullLayoutProbeDescriptors(pipeline, geometryData, renderList);
        bindPipelineStorageBinding(pipeline, CMDGEN_METADATA_BINDING, this.cmdGenTinyMetadataProbeBuffer, "cmdGenTinyMetadataProbeBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_BINDING2_PROBE_BINDING, this.cmdGenBinding2ProbeBuffer, "cmdGenBinding2ProbeBuffer");
        logMetadataBufferState(geometryData, stage, this.cmdGenTinyMetadataProbeBuffer, CMDGEN_METADATA_BINDING);
        logPipelineBindingState(pipeline, CMDGEN_METADATA_BINDING, this.cmdGenTinyMetadataProbeBuffer, stage, "cmdGenTinyMetadataProbeBuffer");
        logPipelineBindingState(pipeline, CMDGEN_BINDING2_PROBE_BINDING, this.cmdGenBinding2ProbeBuffer, stage, "cmdGenBinding2ProbeBuffer");
        logCmdGenConfigBufferState("before_dispatch:" + stage);
        barrierTransferToCompute(commandBuffer);
        logCmdgenProbeTransferBarrierAfterUploads(stage, true, true, true);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen binding1/binding2 const probe dispatch submitted: stage=" + stage
                + ", binding1BufferId=" + this.cmdGenTinyMetadataProbeBuffer.getId()
                + ", binding1DescriptorRangeBytes=" + this.cmdGenTinyMetadataProbeBuffer.getBufferSize()
                + ", binding2BufferId=" + this.cmdGenBinding2ProbeBuffer.getId()
                + ", binding2DescriptorRangeBytes=" + this.cmdGenBinding2ProbeBuffer.getBufferSize());
        return completeProbeDispatch(visibleCount, stage);
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
        logPipelineBindingState(pipeline, descriptorBinding, buffer, stage, "singleSsboProbeBuffer");
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen SSBO read probe dispatch submitted: stage=" + stage
                + ", shader=" + shaderName
                + ", descriptorBinding=" + descriptorBinding
                + ", descriptorRangeBytes=" + bufferSize
                + ", uploadedTinyWord=" + uploadTinyWord);
        return completeProbeDispatch(visibleCount, stage);
    }


    private OpaqueDrawSubmission dispatchFullLayoutProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ComputePipeline pipeline, String stage) {
        if (pipeline == null) throw new IllegalStateException("cmdgen full-layout probe pipeline missing: stage=" + stage);
        if (!this.cmdGenConfigUploaded) {
            throw new IllegalStateException("cmdGenConfigBuffer full-layout probe requested before config upload: stage=" + stage);
        }
        bindFullLayoutProbeDescriptors(pipeline, geometryData, renderList);
        logCmdGenConfigBufferState("before_dispatch:" + stage);
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getId());
        pipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-" + stage + "-dispatch-submitted", "cmdgen full-layout probe dispatch submitted: stage=" + stage);
        return completeProbeDispatch(visibleCount, stage);
    }


    private OpaqueDrawSubmission dispatchDenseLayoutNoopProbe(VkCommandBuffer commandBuffer, int visibleCount, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        if (this.commandGenDenseLayoutNoopProbePipeline == null) throw new IllegalStateException("cmdgen dense-layout noop probe pipeline missing");
        bindDenseLayoutNoopProbeDescriptors(this.commandGenDenseLayoutNoopProbePipeline, geometryData, renderList);
        barrierTransferToCompute(commandBuffer);
        VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.commandGenDenseLayoutNoopProbePipeline.getId());
        this.commandGenDenseLayoutNoopProbePipeline.bindDescriptorSets(commandBuffer, 0);
        VK10.vkCmdDispatch(commandBuffer, 1, 1, 1);
        VulkanBerylDebugLog.once("cmdgen-dense-layout-noop-probe-dispatch-submitted", "cmdgen dense-layout noop probe dispatch: cmdgenSelectedShader=" + CMDGEN_DENSE_LAYOUT_NOOP_SHADER_NAME + ", cmdgenIsolationMode=dense_layout_noop, cmdgenDispatchRecorded=true, cmdgenDispatchGroupCount=1, cmdgenDescriptorBindingValidation=ok");
        return completeProbeDispatch(visibleCount, "dense_layout_noop");
    }


    private void bindDenseLayoutNoopProbeDescriptors(ComputePipeline pipeline, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        ensureCmdGenUnusedBinding2Buffer();
        bindPipelineStorageBinding(pipeline, CMDGEN_RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_UNUSED_BINDING2_BINDING, this.cmdGenUnusedBinding2Buffer, "cmdGenUnusedBinding2Buffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_DRAW_COMMAND_BINDING, this.drawCommandBuffer, "drawCommandBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_DRAW_COUNT_BINDING, cmdgenDrawCountDescriptorBuffer(), drawCountDescriptorLabel());
        bindPipelineStorageBinding(pipeline, CMDGEN_CONFIG_BINDING, this.cmdGenConfigBuffer, "cmdGenConfigBuffer");
    }


    private void bindFullLayoutProbeDescriptors(ComputePipeline pipeline, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        ensureCmdgenBinding2ProbeBuffer();
        bindPipelineStorageBinding(pipeline, CMDGEN_RENDER_LIST_BINDING, renderList.getBuffer(), "renderList.buffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_METADATA_BINDING, geometryData.getMetadataBuffer(), "geometryData.metadataBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_BINDING2_PROBE_BINDING, this.cmdGenBinding2ProbeBuffer, "cmdGenBinding2ProbeBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_DRAW_COMMAND_BINDING, this.drawCommandBuffer, "drawCommandBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_DRAW_COUNT_BINDING, this.drawCountBuffer, "drawCountBuffer");
        bindPipelineStorageBinding(pipeline, CMDGEN_CONFIG_BINDING, this.cmdGenConfigBuffer, "cmdGenConfigBuffer");
    }


    private void bindPipelineStorageBinding(ComputePipeline pipeline, int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, bufferSize);
        }
        if (binding == CMDGEN_CONFIG_BINDING && buffer == this.cmdGenConfigBuffer && bufferSize != CMDGEN_CONFIG_SIZE_BYTES) {
            throw new IllegalStateException("cmdGenConfigBuffer descriptor range mismatch: binding=" + binding + ", bufferBytes=" + bufferSize + ", expectedRangeBytes=" + CMDGEN_CONFIG_SIZE_BYTES);
        }
        UBO ubo = pipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) throw new IllegalStateException("Section cmdgen full-layout probe descriptor missing: name=" + label + ", binding=" + binding);
        ubo.getBufferSlice().set(buffer, 0L, (int) bufferSize);
    }


    private void logPipelineBindingState(ComputePipeline pipeline, int binding, Buffer expectedBuffer, String stage, String label) {
        UBO ubo = pipeline.getUBO(candidate -> candidate.binding == binding);
        Buffer actualBuffer = ubo == null ? null : ubo.getBufferSlice().getBuffer();
        long expectedBufferId = expectedBuffer == null ? 0L : expectedBuffer.getId();
        long actualBufferId = actualBuffer == null ? 0L : actualBuffer.getId();
        long expectedSize = expectedBuffer == null ? -1L : expectedBuffer.getBufferSize();
        long actualSize = actualBuffer == null ? -1L : actualBuffer.getBufferSize();
        VulkanBerylDebugLog.once("cmdgen-pipeline-binding-state:" + stage + ":" + binding, "cmdgen pipeline binding state: stage=" + stage
                + ", binding=" + binding
                + ", label=" + label
                + ", expectedBufferId=" + expectedBufferId
                + ", actualBufferId=" + actualBufferId
                + ", expectedCapacityBytes=" + expectedSize
                + ", actualCapacityBytes=" + actualSize
                + ", bufferMatches=" + (expectedBufferId == actualBufferId)
                + ", descriptorRangeValid=" + (expectedSize > 0L && expectedSize <= VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES));
    }


    private OpaqueDrawSubmission stopCmdgenIsolation(int visibleCount, String reason) {
        VulkanBerylDebugLog.once(reason, "cmdgen isolation stop: " + reason);
        int tsl = VulkanBerylCmdgenDiagnostics.TRAVERSAL_STAGE_LIMIT;
        String tsm = VulkanBerylCmdgenDiagnostics.traversalStageMeaning(tsl);
        String penv = VulkanBerylCmdgenDiagnostics.selectedProbeEnvVarName();
        String pshader = VulkanBerylCmdgenDiagnostics.selectedProbeShaderName();
        String pisolation = VulkanBerylCmdgenDiagnostics.selectedProbeIsolationModeName();
        boolean probeSel = VulkanBerylCmdgenDiagnostics.isProbeSelected();
        VulkanBerylDebugLog.rateLimitedRaw("test-status:cmdgen-stop", "[Voxy][VulkanBeryl][TEST_STATUS]"
                + " traversalStageLimit=" + tsl
                + ", stageMeaning=" + tsm
                + ", rawRenderListVisibleCount=" + this.rawVisibleCountForTestStatus
                + ", renderListVisibleCountForDraw=" + visibleCount
                + ", cmdgenProbeSelected=" + probeSel
                + ", probeEnvName=" + (penv == null ? "none" : penv)
                + ", cmdgenSelectedShader=" + (pshader == null ? "none" : pshader)
                + ", cmdgenIsolationMode=" + (pisolation == null ? "not_selected" : pisolation)
                + ", cmdgenDispatchCallRecorded=" + (probeSel || reason.contains("dispatch_blocked") ? false : false)
                + ", cmdgenPostDispatchBarrierRecorded=false"
                + ", cmdgenDispatchRecorded=false"
                + ", cmdgenDispatchGroupCount=0"
                + ", cmdgenSkipped=true"
                + ", cmdgenSkipReason=" + reason
                + ", indirectAllowed=false"
                + ", indirectDrawRecorded=false"
                + ", submittedDrawCount=0"
                + ", drawSubmitReason=" + reason, 20, TEST_STATUS_INTERVAL_NANOS);
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(false, reason);
        return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0, this.lastCompletedDebugSample.sampledCommandCount(), this.lastCompletedDebugSample.invalidSampledCommandCount(), this.lastCompletedDebugSample.sampledQuadCount(), this.debugSamplePending, reason);
    }


    /**
     * Complete a cmdgen probe dispatch that actually recorded vkCmdDispatch.
     * Emits TEST_STATUS with cmdgenDispatchRecorded=true, cmdgenSkipped=false.
     * Sets cmdgenDispatchRecordedThisFrame=true and reports valid cmdgen sample.
     */
    private OpaqueDrawSubmission completeProbeDispatch(int visibleCount, String stage) {
        this.cmdgenDispatchRecordedThisFrame = true;
        int tsl = VulkanBerylCmdgenDiagnostics.TRAVERSAL_STAGE_LIMIT;
        String tsm = VulkanBerylCmdgenDiagnostics.traversalStageMeaning(tsl);
        String penv = VulkanBerylCmdgenDiagnostics.selectedProbeEnvVarName();
        String pshader = VulkanBerylCmdgenDiagnostics.selectedProbeShaderName();
        String pisolation = VulkanBerylCmdgenDiagnostics.selectedProbeIsolationModeName();
        boolean probeSel = VulkanBerylCmdgenDiagnostics.isProbeSelected();
        VulkanBerylDebugLog.rateLimitedRaw("test-status:cmdgen-probe-complete", "[Voxy][VulkanBeryl][TEST_STATUS]"
                + " traversalStageLimit=" + tsl
                + ", stageMeaning=" + tsm
                + ", rawRenderListVisibleCount=" + this.rawVisibleCountForTestStatus
                + ", renderListVisibleCountForDraw=" + visibleCount
                + ", cmdgenProbeSelected=" + probeSel
                + ", probeEnvName=" + (penv == null ? "none" : penv)
                + ", cmdgenSelectedShader=" + (pshader == null ? "none" : pshader)
                + ", cmdgenIsolationMode=" + (pisolation == null ? "not_selected" : pisolation)
                + ", cmdgenDispatchCallRecorded=true"
                + ", cmdgenPostDispatchBarrierRecorded=false"
                + ", cmdgenDispatchRecorded=true"
                + ", cmdgenDispatchGroupCount=1"
                + ", cmdgenSkipped=false"
                + ", cmdgenSkipReason=none"
                + ", indirectAllowed=false"
                + ", indirectDrawRecorded=false"
                + ", submittedDrawCount=0"
                + ", drawSubmitReason=cmdgen_probe_dispatched:" + stage, 20, TEST_STATUS_INTERVAL_NANOS);
        VulkanBerylLodBringupDiagnostics.updateCmdgenSample(true, "cmdgen_probe_dispatched:" + stage);
        return new OpaqueDrawSubmission(visibleCount, "indirect_generated_per_section", -1L, 0,
                this.lastCompletedDebugSample.sampledCommandCount(),
                this.lastCompletedDebugSample.invalidSampledCommandCount(),
                this.lastCompletedDebugSample.sampledQuadCount(),
                this.debugSamplePending,
                "cmdgen_probe_dispatched:" + stage);
    }


    private static CmdgenIsolationStage selectedCmdgenIsolationStage() {
        CmdgenIsolationStage selected = null;
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE, CmdgenIsolationStage.READ_BINDING0_ONLY_NO_OUTPUT_WRITE);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_BINDING0_ONLY, CmdgenIsolationStage.READ_BINDING0_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_BINDING1_ONLY, CmdgenIsolationStage.READ_BINDING1_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_CONFIG_ONLY, CmdgenIsolationStage.READ_CONFIG_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY, CmdgenIsolationStage.READ_RENDERLIST_HEADER_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY, CmdgenIsolationStage.READ_RENDERLIST_COUNT_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ENTRY0_QUAD_START_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_ONLY, CmdgenIsolationStage.READ_RENDERLIST_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_METADATA_ONLY, CmdgenIsolationStage.READ_METADATA_ONLY);
        selected = selectCmdgenIsolationStage(selected, CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE, CmdgenIsolationStage.READ_RENDERLIST_METADATA_NO_WRITE);
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
        READ_BINDING0_ONLY_NO_OUTPUT_WRITE("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE", CMDGEN_FLAG_READ_BINDING0_ONLY_NO_OUTPUT_WRITE, Integer.BYTES),
        READ_BINDING0_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY", CMDGEN_FLAG_READ_BINDING0_ONLY, Integer.BYTES),
        READ_BINDING1_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING1_ONLY", CMDGEN_FLAG_READ_BINDING1_ONLY, 0),
        READ_CONFIG_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_CONFIG_ONLY", CMDGEN_FLAG_READ_CONFIG_ONLY, 0),
        READ_RENDERLIST_HEADER_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY", CMDGEN_FLAG_READ_RENDERLIST_HEADER_ONLY, Integer.BYTES),
        READ_RENDERLIST_COUNT_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY", CMDGEN_FLAG_READ_RENDERLIST_COUNT_ONLY, Integer.BYTES),
        READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY, 2 * Integer.BYTES),
        READ_RENDERLIST_ENTRY0_QUAD_START_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY, 3 * Integer.BYTES),
        READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY, 4 * Integer.BYTES),
        READ_RENDERLIST_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ONLY", CMDGEN_FLAG_READ_RENDERLIST_ONLY, 2 * Integer.BYTES),
        READ_METADATA_ONLY("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_METADATA_ONLY", CMDGEN_FLAG_READ_METADATA_ONLY, 0),
        READ_RENDERLIST_METADATA_NO_WRITE("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE", CMDGEN_FLAG_READ_RENDERLIST_METADATA_NO_WRITE, 2 * Integer.BYTES),
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



    private void logRenderListVisibilityDiagnostics(VulkanBerylViewportRenderList renderList,
                                                    VulkanBerylSectionGeometryData geometryData,
                                                    ControlledRenderListSmoke controlledSmoke,
                                                    ControlledRenderListSmoke cpuSelectionSmoke,
                                                    int rawVisibleCount,
                                                    int visibleCount,
                                                    boolean noDrawCountFullCmdgen,
                                                    String stage) {
        boolean cpuSelectionFound = cpuSelectionSmoke.safe();
        int metadataSectionCapacity = geometryData.getMaxSectionCount();
        int drawCommandCapacity = safeDrawCommandCapacity();
        int drawCountCapacityWords = this.drawCountBuffer == null ? 0 : (int) (this.drawCountBuffer.getBufferSize() / Integer.BYTES);
        int cpuSelectionSectionId = cpuSelectionSmoke.sectionId();
        boolean firstEntryWithinMetadataCapacity = cpuSelectionFound && cpuSelectionSectionId >= 0 && cpuSelectionSectionId < metadataSectionCapacity;
        boolean visibleCountWithinDrawCommandCapacity = visibleCount > 0 && visibleCount <= drawCommandCapacity;
        String message = "Render-list visibility diagnostics: stage=" + stage
                + " rawRenderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + " rawVisibleCountUsed=" + rawVisibleCount
                + " clampedVisibleCount=" + visibleCount
                + " maxEntryCount=" + renderList.getMaxEntryCount()
                + " metadataSectionCapacity=" + metadataSectionCapacity
                + " drawCommandCapacity=" + drawCommandCapacity
                + " drawCountCapacityWords=" + drawCountCapacityWords
                + " controlledSmokeEnabled=" + controlledSmoke.enabled()
                + " controlledSmokeSafe=" + controlledSmoke.safe()
                + " cpuVisibilitySelectionFound=" + cpuSelectionFound
                + " cpuVisibilitySelectionReason=" + cpuSelectionSmoke.reason()
                + " cpuSelectionSectionId=" + cpuSelectionSectionId
                + " firstEntryWithinMetadataCapacity=" + firstEntryWithinMetadataCapacity
                + " visibleCountWithinDrawCommandCapacity=" + visibleCountWithinDrawCommandCapacity
                + " sectionCount=" + Math.min(geometryData.getSectionCount(), metadataSectionCapacity)
                + " geometrySyncGeneration=" + geometryData.getGeometrySyncGeneration()
                + " usedGeometryBytes=" + geometryData.getUsedGeometryBytes()
                + " noDrawCountFullCmdgen=" + noDrawCountFullCmdgen;
        String stateSnapshot = "rawRenderListVisibleCount=" + rawVisibleCount
                + ";renderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + ";clampedVisibleCount=" + visibleCount
                + ";maxEntryCount=" + renderList.getMaxEntryCount()
                + ";metadataSectionCapacity=" + metadataSectionCapacity
                + ";drawCommandCapacity=" + drawCommandCapacity
                + ";drawCountCapacityWords=" + drawCountCapacityWords
                + ";cpuSelectionSectionId=" + cpuSelectionSectionId
                + ";firstEntryWithinMetadataCapacity=" + firstEntryWithinMetadataCapacity
                + ";visibleCountWithinDrawCommandCapacity=" + visibleCountWithinDrawCommandCapacity
                + ";noDrawCountFullCmdgen=" + noDrawCountFullCmdgen;
        VulkanBerylDebugLog.stateLimited("render-list-visibility-diagnostics", message, stateSnapshot);
    }


    private void logVisibleCountZeroReason(VulkanBerylViewportRenderList renderList,
                                           int rawVisibleCount,
                                           int visibleCount,
                                           VulkanBerylRenderBackendRuntime.FrameSafetyState frameSafety,
                                           ControlledRenderListSmoke controlledSmoke,
                                           ControlledRenderListSmoke cpuSelectionSmoke,
                                           boolean noDrawCountFullCmdgen) {
        String reasonDetail = rawVisibleCount < 0
                ? "render_list_readback_not_valid_yet"
                : (rawVisibleCount == 0 ? "render_list_readback_valid_but_zero_or_controlled_smoke_missing" : "visible_count_clamped_to_zero");
        String message = "visible_count_zero_or_negative: rawRenderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + " rawVisibleCountUsed=" + rawVisibleCount
                + " clampedVisibleCount=" + visibleCount
                + " maxEntryCount=" + renderList.getMaxEntryCount()
                + " frameSafetyAllowCmdGen=" + frameSafety.allowCmdGen()
                + " frameSafetyAllowIndirectDraw=" + frameSafety.allowIndirectDraw()
                + " frameSafetyReason=" + frameSafety.reason()
                + " reasonDetail=" + reasonDetail
                + " controlledSmokeEnabled=" + controlledSmoke.enabled()
                + " controlledSmokeSafe=" + controlledSmoke.safe()
                + " cpuVisibilitySelectionFound=" + cpuSelectionSmoke.safe()
                + " cpuVisibilitySelectionReason=" + cpuSelectionSmoke.reason()
                + " noDrawCountFullCmdgen=" + noDrawCountFullCmdgen;
        String stateSnapshot = "rawRenderListVisibleCount=" + rawVisibleCount
                + ";renderListLastVisibleCount=" + renderList.getLastVisibleCount()
                + ";clampedVisibleCount=" + visibleCount
                + ";maxEntryCount=" + renderList.getMaxEntryCount()
                + ";frameSafetyReason=" + frameSafety.reason()
                + ";reasonDetail=" + reasonDetail
                + ";noDrawCountFullCmdgen=" + noDrawCountFullCmdgen;
        VulkanBerylDebugLog.stateLimited("visible-count-zero-diagnostic", message, stateSnapshot);
    }


    private ControlledRenderListSmoke recordControlledRenderListSmoke(VkCommandBuffer commandBuffer, VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        ControlledRenderListSmoke smoke = findControlledRenderListSmokeSection(viewport, geometryData, renderList);
        if (!smoke.safe()) {
            renderList.setLastVisibleCount(0, viewport.frameId);
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
        renderList.setLastVisibleCount(1, viewport.frameId);
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


    private static boolean cpuRenderListSelectionDiagnosticEnabled() {
        return controlledRenderListDiagnosticEnabled()
                || REAL_LOD_VISIBILITY_DIAGNOSTIC
                || REAL_QUAD_READ_CLIPSPACE_PROBE
                || REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                || VulkanBerylDebugLog.TRACE_LOGS
                || VulkanBerylDebugLog.VERBOSE_LOGS;
    }


    private ControlledRenderListSmoke timedFindControlledRenderListSmokeSection(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        long startNanos = System.nanoTime();
        try {
            return findControlledRenderListSmokeSection(viewport, geometryData, renderList);
        } finally {
            addDiagnosticCpuNanos(System.nanoTime() - startNanos);
        }
    }


    private void addDiagnosticCpuNanos(long nanos) {
        if (nanos > 0L) {
            this.diagnosticCpuNanosThisPass += nanos;
        }
    }


    private void publishDiagnosticCpuTiming() {
        lastDiagnosticCpuMs = this.diagnosticCpuNanosThisPass / 1_000_000.0;
    }


    private ControlledRenderListSmoke findControlledRenderListSmokeSection(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
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
        boolean clipVisibleSearchEnabled = REAL_LOD_SINGLE_QUAD_WORLD_PROBE || (ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS && REAL_LOD_VISIBILITY_DIAGNOSTIC);
        long currentGeometrySyncGen = geometryData.getGeometrySyncGeneration();
        long now = System.nanoTime();
        if (clipVisibleSearchEnabled
                && this.cachedExpensiveDiagnosticSmoke != null
                && this.cachedExpensiveDiagnosticGeometrySyncGeneration == currentGeometrySyncGen
                && (now - this.cachedExpensiveDiagnosticTimestampNanos) < EXPENSIVE_DIAGNOSTIC_CACHE_NANOS) {
            return this.cachedExpensiveDiagnosticSmoke;
        }

        boolean sawOpaqueQuads = false;
        String firstOutOfBounds = null;
        int candidateCount = 0;
        int selectedSectionId = -1;
        int selectedQuadStart = 0;
        long selectedQuadCount = 0L;
        double selectedDistance = Double.POSITIVE_INFINITY;
        VisibilityCandidateSelection visibilitySelection = VisibilityCandidateSelection.notRun();
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
            candidateCount++;
            double distance = distanceSectionToBase(geometryData, sectionId, viewport);
            if (selectedSectionId < 0 || distance < selectedDistance) {
                selectedSectionId = sectionId;
                selectedQuadStart = (int) opaqueQuadStart;
                selectedQuadCount = opaqueQuadCount;
                selectedDistance = distance;
            }
        }
        if (selectedSectionId >= 0) {
            if (REAL_LOD_VISIBILITY_DIAGNOSTIC && !ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS) {
                VulkanBerylDebugLog.once("expensive-world-draw-diagnostic-disabled",
                        "expensive world-draw diagnostic disabled: env=" + EXPENSIVE_WORLD_DRAW_DIAGNOSTICS_ENV
                                + "=false, clip_visible_non_empty_section scan skipped");
            }
            if (clipVisibleSearchEnabled) {
                VulkanBerylDebugLog.once("expensive-world-draw-diagnostic-enabled",
                        "expensive world-draw diagnostic enabled: env=" + EXPENSIVE_WORLD_DRAW_DIAGNOSTICS_ENV
                                + "=" + ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS
                                + ", singleQuadWorldProbe=" + REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                                + ", maxCandidateTests=" + EXPENSIVE_DIAGNOSTIC_MAX_CANDIDATE_TESTS
                                + ", cacheSeconds=" + (EXPENSIVE_DIAGNOSTIC_CACHE_NANOS / 1_000_000_000L));
                if (this.cachedExpensiveDiagnosticSmoke != null
                        && this.cachedExpensiveDiagnosticGeometrySyncGeneration == currentGeometrySyncGen
                        && (now - this.cachedExpensiveDiagnosticTimestampNanos) < EXPENSIVE_DIAGNOSTIC_CACHE_NANOS) {
                    logCachedExpensiveDiagnosticSelection("reused", this.cachedExpensiveDiagnosticSmoke);
                    return this.cachedExpensiveDiagnosticSmoke;
                }
                if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE
                        && this.lastExpensiveDiagnosticScanNanos != 0L
                        && (now - this.lastExpensiveDiagnosticScanNanos) < EXPENSIVE_DIAGNOSTIC_CACHE_NANOS) {
                    VulkanBerylDebugLog.once("expensive-world-draw-diagnostic-rescan-throttled",
                            "expensive world-draw diagnostic rescan throttled after geometry change; using fallback_nearest_non_empty until cache interval elapses");
                    return ControlledRenderListSmoke.safe(selectedSectionId, selectedQuadStart, selectedQuadCount,
                            fallbackSelectionStrategy(candidateCount, selectedSectionId),
                            candidateCount, selectedDistance, visibilitySelection);
                }
                this.lastExpensiveDiagnosticScanNanos = now;
                visibilitySelection = findClipVisibleCandidate(viewport, geometryData, sectionCount, usedGeometryBytes);
                ControlledRenderListSmoke result;
                if (visibilitySelection.found()) {
                    result = ControlledRenderListSmoke.safe(visibilitySelection.sectionId(), visibilitySelection.sectionQuadStart(), visibilitySelection.sectionQuadCount(),
                            "clip_visible_candidate", candidateCount, visibilitySelection.distanceToBase(), visibilitySelection);
                } else {
                    VulkanBerylDebugLog.rateLimited("single-quad-world-probe-no-clip-visible-candidate",
                            "single-quad world probe found no clip-visible candidate: maxCandidateTests=" + EXPENSIVE_DIAGNOSTIC_MAX_CANDIDATE_TESTS
                                    + ", visibilityCandidateCount=" + visibilitySelection.candidateCount()
                                    + ", visibilityCandidateTestedCount=" + visibilitySelection.testedCount()
                                    + ", visibilityCandidateClipVisibleCount=" + visibilitySelection.clipVisibleCount()
                                    + ", rejectReason=" + visibilitySelection.rejectReason()
                                    + ", fallbackUsedOnlyAfterNoClipVisibleCandidate=" + (!visibilitySelection.found() && visibilitySelection.testedCount() > 0)
                                    + ", fallback=fallback_nearest_non_empty", 30);
                    result = ControlledRenderListSmoke.safe(selectedSectionId, selectedQuadStart, selectedQuadCount,
                            fallbackSelectionStrategy(candidateCount, selectedSectionId),
                            candidateCount, selectedDistance, visibilitySelection);
                }
                this.cachedExpensiveDiagnosticSmoke = result;
                this.cachedExpensiveDiagnosticGeometrySyncGeneration = currentGeometrySyncGen;
                this.cachedExpensiveDiagnosticTimestampNanos = now;
                logCachedExpensiveDiagnosticSelection("selected", result);
                return result;
            }
            return ControlledRenderListSmoke.safe(selectedSectionId, selectedQuadStart, selectedQuadCount,
                    fallbackSelectionStrategy(candidateCount, selectedSectionId),
                    candidateCount, selectedDistance, visibilitySelection);
        }
        if (!sawOpaqueQuads) {
            return ControlledRenderListSmoke.failed("opaque_quad_count_zero");
        }
        return ControlledRenderListSmoke.failed(firstOutOfBounds == null ? "quad_bounds_invalid" : firstOutOfBounds, candidateCount, selectedDistance);
    }


    private VisibilityCandidateSelection findClipVisibleCandidate(VulkanBerylViewport viewport, VulkanBerylSectionGeometryData geometryData, int sectionCount, long usedGeometryBytes) {
        int candidateCount = 0;
        int testedCount = 0;
        int clipVisibleCount = 0;
        int selectedSectionId = -1;
        long selectedQuadIndex = -1L;
        int selectedSectionQuadStart = 0;
        long selectedSectionQuadCount = 0L;
        double selectedDistance = Double.POSITIVE_INFINITY;
        boolean selectedClipLooksVisible = false;
        String rejectReason = "no_clip_visible_candidate";
        boolean capReached = false;
        for (int sectionId = 0; sectionId < sectionCount && !capReached; sectionId++) {
            if (!geometryData.hasNonZeroSectionMetadata(sectionId)) continue;
            int quadStart = geometryData.getSectionMetadataInt(sectionId, 3);
            long translucentQuadCount = extractTranslucentQuadCount(geometryData, sectionId);
            long opaqueQuadCount = extractOpaqueQuadCount(geometryData, sectionId);
            if (opaqueQuadCount <= 0L) continue;
            long opaqueQuadStart = Integer.toUnsignedLong(quadStart) + translucentQuadCount;
            long requiredBytes = Math.addExact(Math.multiplyExact(opaqueQuadStart, 8L), Math.multiplyExact(opaqueQuadCount, 8L));
            if (requiredBytes > usedGeometryBytes || requiredBytes > geometryData.getGeometryCapacityBytes()) {
                if ("no_clip_visible_candidate".equals(rejectReason)) rejectReason = "quad_bounds_invalid";
                continue;
            }
            candidateCount++;
            double distance = distanceSectionToBase(geometryData, sectionId, viewport);
            long candidateQuadLimit = opaqueQuadCount;
            for (long quadOffset = 0L; quadOffset < candidateQuadLimit; quadOffset++) {
                if (testedCount >= EXPENSIVE_DIAGNOSTIC_MAX_CANDIDATE_TESTS) {
                    if ("no_clip_visible_candidate".equals(rejectReason)) rejectReason = "candidate_cap_reached";
                    capReached = true;
                    break;
                }
                testedCount++;
                long quadIndex = opaqueQuadStart + quadOffset;
                QuadSample quad = sampleQuadFromJavaDiagnostics(geometryData, quadIndex);
                if (!quad.available()) {
                    if ("no_clip_visible_candidate".equals(rejectReason)) rejectReason = "geometry_quad_unavailable_to_java_diagnostics";
                    continue;
                }
                if (quad.empty()) {
                    if ("no_clip_visible_candidate".equals(rejectReason)) rejectReason = "geometry_quad_decode_empty_quad";
                    continue;
                }
                ClipDiagnostics clip = clipDiagnostics(viewport, geometryData.getSectionMetadataInt(sectionId, 0), geometryData.getSectionMetadataInt(sectionId, 1), quad);
                ClipDiagnostics relativeClip = clipDiagnosticsForMode(viewport, geometryData.getSectionMetadataInt(sectionId, 0), geometryData.getSectionMetadataInt(sectionId, 1), quad, true);
                boolean clipVisible = clip.looksVisible() && relativeClip.looksVisible() && !clip.behindCamera();
                if (!clipVisible) {
                    if (clip.behindCamera()) rejectReason = "clip_depth_cull_behind_camera";
                    else if ("no_clip_visible_candidate".equals(rejectReason) || "geometry_quad_unavailable_to_java_diagnostics".equals(rejectReason) || "geometry_quad_decode_empty_quad".equals(rejectReason)) rejectReason = "clip_depth_cull_offscreen_clip";
                    continue;
                }
                clipVisibleCount++;
                if (selectedSectionId < 0 || distance < selectedDistance) {
                    selectedSectionId = sectionId;
                    selectedQuadIndex = quadIndex;
                    selectedSectionQuadStart = (int) quadIndex;
                    selectedSectionQuadCount = Math.max(1L, opaqueQuadStart + opaqueQuadCount - quadIndex);
                    selectedDistance = distance;
                    selectedClipLooksVisible = true;
                    rejectReason = "none";
                }
            }
        }
        return new VisibilityCandidateSelection(candidateCount, testedCount, clipVisibleCount, selectedSectionId, selectedQuadIndex, selectedSectionQuadStart, selectedSectionQuadCount, selectedClipLooksVisible, rejectReason, selectedDistance);
    }


    private static String fallbackSelectionStrategy(int candidateCount, int selectedSectionId) {
        if (REAL_LOD_SINGLE_QUAD_WORLD_PROBE) return "fallback_nearest_non_empty";
        return candidateCount == 1 && selectedSectionId == 0 ? "first_section" : "fallback_nearest_non_empty";
    }


    private static void logCachedExpensiveDiagnosticSelection(String action, ControlledRenderListSmoke smoke) {
        if (smoke == null || !smoke.safe()) return;
        VulkanBerylDebugLog.once("expensive-world-draw-diagnostic-cache-" + action,
                "expensive world-draw diagnostic cached section: action=" + action
                        + ", strategy=" + smoke.selectionStrategy()
                        + ", sectionId=" + smoke.sectionId()
                        + ", quadStart=" + smoke.quadStart()
                        + ", quadCount=" + smoke.quadCount()
                        + ", visibilityCandidateCount=" + smoke.visibilityCandidateCount()
                        + ", visibilityCandidateTestedCount=" + smoke.visibilityCandidateTestedCount()
                        + ", visibilityCandidateClipVisibleCount=" + smoke.visibilityCandidateClipVisibleCount()
                        + ", selectedVisibilityCandidateQuadIndex=" + smoke.selectedVisibilityCandidateQuadIndex()
                        + ", rejectReason=" + smoke.selectedVisibilityCandidateRejectReason()
                        + ", fallbackUsedOnlyAfterNoClipVisibleCandidate=" + smoke.fallbackUsedOnlyAfterNoClipVisibleCandidate());
    }


    private void logControlledSmokeDiagnosticIfChanged(VulkanBerylSectionGeometryData geometryData, ControlledRenderListSmoke smoke) {
        int sectionCount = Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount());
        int firstNonZeroMetadataSection = geometryData.findFirstNonZeroSectionMetadata();
        String diagnostic = "sectionCount=" + sectionCount
                + " usedGeometryBytes=" + geometryData.getUsedGeometryBytes()
                + " metadataCapacityBytes=" + geometryData.getMetadataCapacityBytes()
                + " firstNonzeroMetadataSection=" + firstNonZeroMetadataSection
                + " blocker=" + smoke.reason()
                + " controlledSmokeSelectionStrategy=" + smoke.selectionStrategy()
                + " controlledSmokeCandidateCount=" + smoke.candidateCount()
                + " controlledSmokeSelectedDistanceToBase=" + formatDouble(smoke.distanceToBase())
                + " visibilityCandidateCount=" + smoke.visibilityCandidateCount()
                + " visibilityCandidateTestedCount=" + smoke.visibilityCandidateTestedCount()
                + " visibilityCandidateClipVisibleCount=" + smoke.visibilityCandidateClipVisibleCount()
                + " selectedVisibilityCandidateSectionId=" + smoke.selectedVisibilityCandidateSectionId()
                + " selectedVisibilityCandidateQuadIndex=" + smoke.selectedVisibilityCandidateQuadIndex()
                + " selectedVisibilityCandidateClipLooksVisible=" + smoke.selectedVisibilityCandidateClipLooksVisible()
                + " selectedVisibilityCandidateRejectReason=" + smoke.selectedVisibilityCandidateRejectReason()
                + " fallbackUsedOnlyAfterNoClipVisibleCandidate=" + smoke.fallbackUsedOnlyAfterNoClipVisibleCandidate();
        if (diagnostic.equals(this.lastControlledSmokeDiagnostic)) {
            return;
        }
        this.lastControlledSmokeDiagnostic = diagnostic;
        if (smoke.safe()) {
            VulkanBerylDebugLog.always("Controlled render-list smoke ready: " + diagnostic
                    + " sectionId=" + smoke.sectionId()
                    + " quadStart=" + smoke.quadStart()
                    + " quadCount=" + smoke.quadCount()
                    + " controlledSmokeSelectionStrategy=" + smoke.selectionStrategy()
                    + " controlledSmokeCandidateCount=" + smoke.candidateCount()
                    + " controlledSmokeSelectedDistanceToBase=" + formatDouble(smoke.distanceToBase())
                    + " visibilityCandidateCount=" + smoke.visibilityCandidateCount()
                    + " visibilityCandidateTestedCount=" + smoke.visibilityCandidateTestedCount()
                    + " visibilityCandidateClipVisibleCount=" + smoke.visibilityCandidateClipVisibleCount()
                    + " selectedVisibilityCandidateSectionId=" + smoke.selectedVisibilityCandidateSectionId()
                    + " selectedVisibilityCandidateQuadIndex=" + smoke.selectedVisibilityCandidateQuadIndex()
                    + " selectedVisibilityCandidateClipLooksVisible=" + smoke.selectedVisibilityCandidateClipLooksVisible()
                    + " selectedVisibilityCandidateRejectReason=" + smoke.selectedVisibilityCandidateRejectReason()
                    + " fallbackUsedOnlyAfterNoClipVisibleCandidate=" + smoke.fallbackUsedOnlyAfterNoClipVisibleCandidate());
        } else {
            VulkanBerylDebugLog.always("Controlled render-list smoke blocked: " + diagnostic);
        }
    }


    private void logCpuMirrorMetadataDiagnostic(VulkanBerylSectionGeometryData geometryData, String stage) {
        if (geometryData == null) {
            VulkanBerylDebugLog.rateLimited("cpu-mirror-metadata-diagnostic:" + stage, "CPU mirror metadata diagnostic: stage=" + stage + ", geometryData=null", 60);
            return;
        }
        int sectionCount = Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount());
        int nonZeroMetadataSections = 0;
        long totalOpaqueQuadCount = 0L;
        long totalTranslucentQuadCount = 0L;
        int firstNonZeroSection = -1;
        for (int sectionId = 0; sectionId < sectionCount; sectionId++) {
            if (!geometryData.hasNonZeroSectionMetadata(sectionId)) {
                continue;
            }
            if (firstNonZeroSection < 0) {
                firstNonZeroSection = sectionId;
            }
            nonZeroMetadataSections++;
            totalOpaqueQuadCount += extractOpaqueQuadCount(geometryData, sectionId);
            totalTranslucentQuadCount += extractTranslucentQuadCount(geometryData, sectionId);
        }
        VulkanBerylDebugLog.rateLimited("cpu-mirror-metadata-diagnostic:" + stage, "CPU mirror metadata diagnostic: stage=" + stage
                + " sectionCount=" + sectionCount
                + " nonZeroMetadataSections=" + nonZeroMetadataSections
                + " totalOpaqueQuadCount=" + totalOpaqueQuadCount
                + " totalTranslucentQuadCount=" + totalTranslucentQuadCount
                + " firstNonZeroSection=" + firstNonZeroSection
                + " mirrorWriteCount=" + geometryData.getSectionMetadataMirrorWriteCount()
                + " usedGeometryBytes=" + geometryData.getUsedGeometryBytes()
                + " maxSectionCount=" + geometryData.getMaxSectionCount(), 60);
    }

    private static double distanceSectionToBase(VulkanBerylSectionGeometryData geometryData, int sectionId, VulkanBerylViewport viewport) {
        int rawPosA = geometryData.getSectionMetadataInt(sectionId, 0);
        int rawPosB = geometryData.getSectionMetadataInt(sectionId, 1);
        int[] decoded = decodeLodPosition(rawPosA, rawPosB);
        int lod = rawPosA >>> 28;
        long sx = ((long) decoded[0]) << lod;
        long sy = ((long) decoded[1]) << lod;
        long sz = ((long) decoded[2]) << lod;
        long bx = viewport == null ? 0L : viewport.section.x;
        long by = viewport == null ? 0L : viewport.section.y;
        long bz = viewport == null ? 0L : viewport.section.z;
        double dx = sx - bx;
        double dy = sy - by;
        double dz = sz - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }


    private static int[] decodeLodPosition(int rawPosA, int rawPosB) {
        int y = (rawPosA << 4) >> 24;
        int x = (rawPosB << 4) >> 8;
        int z = (rawPosA & ((1 << 20) - 1)) << 4;
        z |= rawPosB >>> 28;
        z = (z << 8) >> 8;
        return new int[]{x, y, z};
    }


    private static String formatDouble(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0.0 ? "Infinity" : "-Infinity";
        return String.format(java.util.Locale.ROOT, "%.3f", value);
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


    private int buildIndirectCommandsFromCpuMirror(
            VkCommandBuffer commandBuffer,
            Buffer indirectBuffer,
            VulkanBerylSectionGeometryData geometryData,
            VulkanBerylViewportRenderList renderList,
            int maxCommands) {
        if (!ENABLE_LODS || !CMDGEN_DEBUG_READBACK) return 0;
        if (geometryData == null || renderList == null || indirectBuffer == null) return 0;
        if (geometryData.getSectionMetadataMirrorWriteCount() <= 0L) return 0;

        int sectionCount = Math.min(geometryData.getSectionCount(), geometryData.getMaxSectionCount());
        int commandCount = 0;
        for (int sectionId = 0; sectionId < sectionCount && commandCount < maxCommands; sectionId++) {
            if (!geometryData.hasNonZeroSectionMetadata(sectionId)) continue;
            long opaqueQuadCount = extractOpaqueQuadCount(geometryData, sectionId);
            if (opaqueQuadCount <= 0L) continue;
            commandCount++;
        }
        if (commandCount == 0) return 0;

        int renderListWords = 1 + commandCount;
        int renderListBytes = renderListWords * Integer.BYTES;
        int drawCommandBytes = commandCount * DRAW_COMMAND_STRIDE_BYTES;
        if (renderListBytes > 65536 || drawCommandBytes > 65536) {
            VulkanBerylDebugLog.rateLimited("cpu-mirror-draw-commands-vkCmdUpdateBuffer-overflow",
                    "CPU mirror DrawCommands vkCmdUpdateBuffer overflow risk: renderListBytes=" + renderListBytes
                            + " drawCommandBytes=" + drawCommandBytes
                            + " commandCount=" + commandCount, 60);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer renderListData = stack.malloc(renderListBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            renderListData.putInt(0, commandCount);
            ByteBuffer drawCommands = stack.malloc(drawCommandBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int cmdIndex = 0;
            for (int sectionId = 0; sectionId < sectionCount && cmdIndex < commandCount; sectionId++) {
                if (!geometryData.hasNonZeroSectionMetadata(sectionId)) continue;
                long opaqueQuadCount = extractOpaqueQuadCount(geometryData, sectionId);
                if (opaqueQuadCount <= 0L) continue;
                renderListData.putInt(4 + cmdIndex * Integer.BYTES, sectionId);
                int cmdOffset = cmdIndex * DRAW_COMMAND_STRIDE_BYTES;
                long vertexCount = opaqueQuadCount * 6L;
                drawCommands.putInt(cmdOffset, (int) Math.min(vertexCount, 0xFFFFFFFFL));
                drawCommands.putInt(cmdOffset + 4, 1);
                drawCommands.putInt(cmdOffset + 8, 0);
                drawCommands.putInt(cmdOffset + 12, cmdIndex);
                cmdIndex++;
            }
            VK10.vkCmdUpdateBuffer(commandBuffer, renderList.getBuffer().getId(), 0L, renderListData);
            VK10.vkCmdUpdateBuffer(commandBuffer, indirectBuffer.getId(), 0L, drawCommands);
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0, barrier, null, null);
        }
        VulkanBerylDebugLog.rateLimited("cpu-mirror-draw-commands-built",
                "CPU mirror DrawCommands built: commandCount=" + commandCount
                        + " sectionCount=" + sectionCount
                        + " maxCommands=" + maxCommands
                        + " renderListBytes=" + renderListBytes
                        + " drawCommandBytes=" + drawCommandBytes
                        + " metadataWriteCount=" + geometryData.getSectionMetadataMirrorWriteCount(), 60);
        return commandCount;
    }


    private record VisibilityCandidateSelection(int candidateCount, int testedCount, int clipVisibleCount, int sectionId, long quadIndex, int sectionQuadStart, long sectionQuadCount, boolean clipLooksVisible, String rejectReason, double distanceToBase) {
        static VisibilityCandidateSelection notRun() { return new VisibilityCandidateSelection(0, 0, 0, -1, -1L, 0, 0L, false, (ENABLE_EXPENSIVE_WORLD_DRAW_DIAGNOSTICS && REAL_LOD_VISIBILITY_DIAGNOSTIC) ? "not_found" : "not_run", Double.NaN); }
        boolean found() { return this.sectionId >= 0 && this.quadIndex >= 0L && this.clipLooksVisible; }
    }


    private record ControlledRenderListSmoke(boolean enabled, boolean safe, int sectionId, int quadStart, long quadCount, String reason, String selectionStrategy, int candidateCount, double distanceToBase, VisibilityCandidateSelection visibilitySelection) {
        static ControlledRenderListSmoke disabled() { return new ControlledRenderListSmoke(false, false, -1, 0, 0L, "disabled", "fallback", 0, Double.NaN, VisibilityCandidateSelection.notRun()); }
        static ControlledRenderListSmoke failed(String reason) { return failed(reason, 0, Double.NaN); }
        static ControlledRenderListSmoke failed(String reason, int candidateCount, double distanceToBase) { return new ControlledRenderListSmoke(true, false, -1, 0, 0L, reason, "fallback", candidateCount, distanceToBase, VisibilityCandidateSelection.notRun()); }
        static ControlledRenderListSmoke safe(int sectionId, int quadStart, long quadCount, String selectionStrategy, int candidateCount, double distanceToBase, VisibilityCandidateSelection visibilitySelection) {
            long diagnosticQuadCount = REAL_LOD_SINGLE_QUAD_WORLD_PROBE ? Math.min(quadCount, 1L) : quadCount;
            return new ControlledRenderListSmoke(true, true, sectionId, quadStart, diagnosticQuadCount, "controlled_render_list_ready", selectionStrategy, candidateCount, distanceToBase, visibilitySelection);
        }
        int visibilityCandidateCount() { return this.visibilitySelection.candidateCount(); }
        int visibilityCandidateTestedCount() { return this.visibilitySelection.testedCount(); }
        int visibilityCandidateClipVisibleCount() { return this.visibilitySelection.clipVisibleCount(); }
        int selectedVisibilityCandidateSectionId() { return this.visibilitySelection.sectionId(); }
        long selectedVisibilityCandidateQuadIndex() { return this.visibilitySelection.quadIndex(); }
        boolean selectedVisibilityCandidateClipLooksVisible() { return this.visibilitySelection.clipLooksVisible(); }
        String selectedVisibilityCandidateRejectReason() { return this.visibilitySelection.rejectReason(); }
        boolean fallbackUsedOnlyAfterNoClipVisibleCandidate() {
            return "fallback_nearest_non_empty".equals(this.selectionStrategy)
                    && this.visibilitySelection.testedCount() > 0
                    && this.visibilitySelection.clipVisibleCount() == 0
                    && !this.visibilitySelection.found();
        }
    }


    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.graphicsPipeline != null) {
            this.graphicsPipeline.cleanUp();
            this.graphicsPipeline = null;
        }
        if (this.translucentGraphicsPipeline != null) {
            this.translucentGraphicsPipeline.cleanUp();
            this.translucentGraphicsPipeline = null;
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
        if (this.commandGenMinimalConfigBinding0ReadProbePipeline != null) {
            this.commandGenMinimalConfigBinding0ReadProbePipeline.cleanUp();
            this.commandGenMinimalConfigBinding0ReadProbePipeline = null;
        }
        if (this.commandGenHardcodedBinding0ReadPipeline != null) {
            this.commandGenHardcodedBinding0ReadPipeline.cleanUp();
            this.commandGenHardcodedBinding0ReadPipeline = null;
        }
        if (this.commandGenFullLayoutNoopProbePipeline != null) {
            this.commandGenFullLayoutNoopProbePipeline.cleanUp();
            this.commandGenFullLayoutNoopProbePipeline = null;
        }
        if (this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline != null) {
            this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline.cleanUp();
            this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline = null;
        }
        if (this.commandGenFullLayoutConfigBinding0ReadProbePipeline != null) {
            this.commandGenFullLayoutConfigBinding0ReadProbePipeline.cleanUp();
            this.commandGenFullLayoutConfigBinding0ReadProbePipeline = null;
        }
        if (this.commandGenNoImportProbePipeline != null) {
            this.commandGenNoImportProbePipeline.cleanUp();
            this.commandGenNoImportProbePipeline = null;
        }
        if (this.commandGenNoImportReadMetadata0OnlyProbePipeline != null) {
            this.commandGenNoImportReadMetadata0OnlyProbePipeline.cleanUp();
            this.commandGenNoImportReadMetadata0OnlyProbePipeline = null;
        }
        if (this.commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline != null) {
            this.commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline.cleanUp();
            this.commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline = null;
        }
        if (this.commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline != null) {
            this.commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline.cleanUp();
            this.commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline = null;
        }
        if (this.commandGenNoImportAtomicDrawcountOnlyProbePipeline != null) {
            this.commandGenNoImportAtomicDrawcountOnlyProbePipeline.cleanUp();
            this.commandGenNoImportAtomicDrawcountOnlyProbePipeline = null;
        }
        if (this.commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline != null) {
            this.commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline.cleanUp();
            this.commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline = null;
        }
        if (this.commandGenReadRenderlistMetadataNoWritePipeline != null) {
            this.commandGenReadRenderlistMetadataNoWritePipeline.cleanUp();
            this.commandGenReadRenderlistMetadataNoWritePipeline = null;
        }
        if (this.drawCommandBuffer != null) {
            this.drawCommandBuffer.scheduleFree();
            this.drawCommandBuffer = null;
        }
        if (this.controlledSmokeKnownCommandBuffer != null) {
            this.controlledSmokeKnownCommandBuffer.scheduleFree();
            this.controlledSmokeKnownCommandBuffer = null;
        }
        if (this.drawCountBuffer != null && this.drawCountBuffer != this.cmdgenDrawCountScratchBuffer) {
            this.drawCountBuffer.scheduleFree();
            this.drawCountBuffer = null;
        }
        if (this.sceneUniformBuffer != null) {
            this.sceneUniformBuffer.scheduleFree();
            this.sceneUniformBuffer = null;
        }
        if (this.cmdgenDrawCountScratchBuffer != null) {
            boolean drawCountAliasesScratch = this.drawCountBuffer == this.cmdgenDrawCountScratchBuffer;
            this.cmdgenDrawCountScratchBuffer.scheduleFree();
            this.cmdgenDrawCountScratchBuffer = null;
            if (drawCountAliasesScratch) {
                this.drawCountBuffer = null;
            }
        }
        if (this.cmdGenConfigBuffer != null) {
            this.cmdGenConfigBuffer.scheduleFree();
            this.cmdGenConfigBuffer = null;
        }
        if (this.cmdGenUnusedBinding2Buffer != null) {
            this.cmdGenUnusedBinding2Buffer.scheduleFree();
            this.cmdGenUnusedBinding2Buffer = null;
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
        if (this.drawCountDebugReadbackBuffer != null) {
            this.drawCountDebugReadbackBuffer.scheduleFree();
            this.drawCountDebugReadbackBuffer = null;
        }
        if (this.geometryQuadDebugReadbackBuffer != null) {
            this.geometryQuadDebugReadbackBuffer.scheduleFree();
            this.geometryQuadDebugReadbackBuffer = null;
        }
        if (this.realLodGpuDecodeParityBuffer != null) {
            this.realLodGpuDecodeParityBuffer.scheduleFree();
            this.realLodGpuDecodeParityBuffer = null;
        }
        if (this.realLodGpuDecodeParityReadbackBuffer != null) {
            this.realLodGpuDecodeParityReadbackBuffer.scheduleFree();
            this.realLodGpuDecodeParityReadbackBuffer = null;
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
        List<UBO> descriptors = new java.util.ArrayList<>(8);
        int vertexStage = VK10.VK_SHADER_STAGE_VERTEX_BIT;
        descriptors.add(new ManualUBO(SCENE_UNIFORM_BINDING, vertexStage, SCENE_UNIFORM_SIZE_BYTES / Integer.BYTES));
        descriptors.add(new ManualStorageBuffer(1, vertexStage, 1));
        descriptors.add(new ManualStorageBuffer(2, vertexStage, 1));
        descriptors.add(new ManualStorageBuffer(3, vertexStage, 1));
        descriptors.add(new ManualStorageBuffer(GEOMETRY_BINDING, vertexStage, 1));
        descriptors.add(new ManualStorageBuffer(METADATA_BINDING, vertexStage, 1));
        descriptors.add(new ManualStorageBuffer(RENDER_LIST_BINDING, vertexStage, 1));
        descriptors.add(new ManualStorageBuffer(REAL_LOD_GPU_DECODE_PARITY_BINDING, vertexStage, REAL_LOD_GPU_DECODE_PARITY_WORDS));
        return descriptors;
    }


    private static List<ImageDescriptor> createManualDrawImageDescriptors() {
        return List.of(
                new ImageDescriptor(7, "sampler2D", "blockModelAtlas", VTextureSelector.getTextureIdx("Sampler0")),
                new ImageDescriptor(8, "sampler2D", "depthTex", VTextureSelector.getTextureIdx("Sampler7"))
        );
    }


    private void bindDrawTextureDescriptors() {
        VTextureSelector.bindShaderTextures(this.graphicsPipeline);
        bindWhiteTextureFallback(0, "blockModelAtlas");
        bindWhiteTextureFallback(7, "depthTex");
    }


    private static void bindWhiteTextureFallback(int textureIndex, String label) {
        if (VTextureSelector.getImage(textureIndex) != null) return;
        VTextureSelector.bindTexture(textureIndex, VTextureSelector.getWhiteTexture());
        VulkanBerylDebugLog.warnRateLimited("section-draw-texture-fallback:" + label, "Section draw texture descriptor fallback: label=" + label
                + ", textureIndex=" + textureIndex
                + ", fallback=VTextureSelector.whiteTexture"
                + ", depthSamplingEnabled=" + DRAW_ENABLE_DEPTH_TEX);
    }


    private void ensureCommandBuffers(int maxEntryCount) {
        if (maxEntryCount <= 0) throw new IllegalArgumentException("maxEntryCount must be positive");
        ensureCmdGenConfigBuffer();
        if (this.drawCommandBuffer != null && this.drawCommandCapacity == maxEntryCount) return;
        if (this.drawCommandBuffer != null) this.drawCommandBuffer.scheduleFree();
        long commandBytes = Math.multiplyExact((long) maxEntryCount, DRAW_COMMAND_STRIDE_BYTES);
        this.drawCommandBufferUsageFlags = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        this.drawCommandBuffer = new Buffer("voxy_vulkanberyl_opaque_draw_commands", this.drawCommandBufferUsageFlags, MemoryTypes.GPU_MEM);
        this.drawCommandBuffer.createBuffer(commandBytes);
        this.drawCommandBufferAllocationGeneration++;
        this.lastCmdgenBinding3ReboundGeneration = -1L;
        Buffer oldDrawCountBuffer = this.drawCountBuffer;
        long oldDrawCountBufferId = oldDrawCountBuffer == null ? 0L : oldDrawCountBuffer.getId();
        if (oldDrawCountBuffer != null && oldDrawCountBuffer != this.cmdgenDrawCountScratchBuffer) oldDrawCountBuffer.scheduleFree();
        allocateRealDrawCountBuffer(oldDrawCountBufferId);
        this.debugSamplePending = false;
        this.cmdgenSampleScheduled = false;
        this.cmdgenSampleScheduleReason = "buffer_reallocated";
        this.lastCompletedDebugSample = DrawCommandDebugSample.empty();
        this.lastCompletedOpaqueDebugSample = DrawCommandDebugSample.empty();
        this.lastCompletedTranslucentDebugSample = DrawCommandDebugSample.empty();
        this.pendingDebugSampleSourceBufferId = 0L;
        this.pendingDebugSampleDrawPass = DrawPass.OPAQUE;
        this.completedDebugSampleSourceBufferId = 0L;
        this.completedDebugSampleFrameId = -1L;
        this.completedDebugSampleDrawPass = DrawPass.OPAQUE;
        this.completedOpaqueDebugSampleSourceBufferId = 0L;
        this.completedTranslucentDebugSampleSourceBufferId = 0L;
        this.completedOpaqueDebugSampleFrameId = -1L;
        this.completedTranslucentDebugSampleFrameId = -1L;
        this.completedOpaqueDebugSampleSnapshot = CmdgenCommandSnapshot.unavailable();
        this.completedTranslucentDebugSampleSnapshot = CmdgenCommandSnapshot.unavailable();
        this.lastObservedCompletedDebugSampleDrawPass = null;
        this.lastObservedCompletedDebugSampleRejectReason = "none";
        if (this.drawCommandDebugReadbackBuffer != null) this.drawCommandDebugReadbackBuffer.scheduleFree();
        this.drawCommandDebugReadbackBufferUsageFlags = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.drawCommandDebugReadbackBuffer = new Buffer("voxy_vulkanberyl_opaque_draw_commands_readback", this.drawCommandDebugReadbackBufferUsageFlags, MemoryTypes.HOST_MEM);
        this.drawCommandDebugReadbackBuffer.createBuffer((long) DRAW_COMMAND_DEBUG_SAMPLE_LIMIT * DRAW_COMMAND_STRIDE_BYTES);
        if (this.drawCountDebugReadbackBuffer != null) this.drawCountDebugReadbackBuffer.scheduleFree();
        this.drawCountDebugReadbackBufferUsageFlags = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.drawCountDebugReadbackBuffer = new Buffer("voxy_vulkanberyl_opaque_draw_count_readback", this.drawCountDebugReadbackBufferUsageFlags, MemoryTypes.HOST_MEM);
        this.drawCountDebugReadbackBuffer.createBuffer(CMDGEN_DRAWCOUNT_DIAGNOSTIC_BYTES);
        if (this.geometryQuadDebugReadbackBuffer != null) this.geometryQuadDebugReadbackBuffer.scheduleFree();
        this.geometryQuadDebugReadbackBufferUsageFlags = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.geometryQuadDebugReadbackBuffer = new Buffer("voxy_vulkanberyl_geometry_quad_readback", this.geometryQuadDebugReadbackBufferUsageFlags, MemoryTypes.HOST_MEM);
        this.geometryQuadDebugReadbackBuffer.createBuffer(Long.BYTES);
        this.geometryDiagnosticReadbackScheduled = false;
        this.geometryDiagnosticReadbackCompleted = false;
        this.geometryDiagnosticReadbackCopyRecorded = false;
        this.geometryDiagnosticPendingQuadIndex = -1L;
        this.geometryDiagnosticPendingByteOffset = -1L;
        this.geometryDiagnosticPendingFrameId = -1L;
        this.geometryDiagnosticPendingRendererFrameSlot = -1;
        this.geometryDiagnosticPendingCommandBufferAddress = 0L;
        this.geometryDiagnosticPendingSourceBufferId = 0L;
        this.geometryDiagnosticPendingGeometrySyncGeneration = 0L;
        this.geometryDiagnosticCompletedQuadIndex = -1L;
        this.geometryDiagnosticCompletedByteOffset = -1L;
        this.geometryDiagnosticCompletedFrameId = -1L;
        this.geometryDiagnosticCompletedRendererFrameSlot = -1;
        this.geometryDiagnosticCompletedCommandBufferAddress = 0L;
        this.geometryDiagnosticCompletedSourceBufferId = 0L;
        this.geometryDiagnosticCompletedGeometrySyncGeneration = 0L;
        this.geometryDiagnosticRejectReason = "buffer_reallocated";
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


    private void ensureCmdGenUnusedBinding2Buffer() {
        if (this.cmdGenUnusedBinding2Buffer != null) {
            if (this.cmdGenUnusedBinding2Buffer.getBufferSize() >= CMDGEN_UNUSED_BINDING2_SIZE_BYTES) return;
            this.cmdGenUnusedBinding2Buffer.scheduleFree();
        }
        this.cmdGenUnusedBinding2Buffer = new Buffer("voxy_vulkanberyl_cmdgen_unused_binding2",
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                MemoryTypes.GPU_MEM);
        this.cmdGenUnusedBinding2Buffer.createBuffer(CMDGEN_UNUSED_BINDING2_SIZE_BYTES);
        VulkanBerylDebugLog.once("cmdgen-unused-binding2-created", "cmdgen unused binding-2 padding buffer created for dense normal ManualUBO layout; shaderAccess=false: bufferId="
                + this.cmdGenUnusedBinding2Buffer.getId()
                + ", capacityBytes=" + this.cmdGenUnusedBinding2Buffer.getBufferSize()
                + ", usage=STORAGE|TRANSFER_DST");
    }



    private static boolean isExplicitCmdgenShaderSelectionDiagnosticActive() {
        return activeCmdgenShaderSelectionEnvVar() != null;
    }


    private static boolean bindDrawCountToScratchActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && (CMDGEN_BIND_DRAWCOUNT_TO_SCRATCH_BUFFER || usePassingScratchBindingAsRealDrawCountDescriptorActive());
    }


    private static boolean largeDrawCountBufferActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER;
    }


    private static boolean drawCountWithIndirectUsageActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE;
    }


    private static boolean useScratchAllocationForRealDrawCountActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT;
    }


    private static boolean usePassingScratchBindingAsRealDrawCountDescriptorActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive();
    }


    private static boolean skipDrawCountClearBeforeDispatchActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH;
    }


    private static boolean disableAnyDrawCountConsumerPathActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH;
    }


    private static boolean drawCountFullDescriptorRangeActive() {
        return isExplicitCmdgenShaderSelectionDiagnosticActive() && CMDGEN_DRAWCOUNT_DESCRIPTOR_RANGE_FULL_BUFFER;
    }


    private void allocateRealDrawCountBuffer(long oldDrawCountBufferId) {
        if (usePassingScratchBindingAsRealDrawCountDescriptorActive()) {
            ensureCmdgenDrawCountScratchBuffer();
            this.drawCountBuffer = this.cmdgenDrawCountScratchBuffer;
            this.drawCountBufferUsageFlags = this.cmdgenDrawCountScratchBufferUsageFlags;
            this.drawCountAllocationGeneration++;
            this.lastDrawCountAllocationUsedScratchPath = true;
            this.lastOldRealDrawCountBufferStillExists = oldDrawCountBufferId != 0L;
            logPassingScratchAsRealAllocation(oldDrawCountBufferId);
            return;
        }
        boolean scratchStyle = useScratchAllocationForRealDrawCountActive();
        long drawCountBytes = scratchStyle || largeDrawCountBufferActive() ? CMDGEN_DIAGNOSTIC_DRAWCOUNT_CAPACITY_BYTES : CMDGEN_DRAWCOUNT_DIAGNOSTIC_BYTES;
        this.drawCountBufferUsageFlags = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        this.drawCountBuffer = new Buffer(scratchStyle ? "voxy_vulkanberyl_cmdgen_drawcount_scratch_real" : "voxy_vulkanberyl_opaque_draw_count", this.drawCountBufferUsageFlags, MemoryTypes.GPU_MEM);
        this.drawCountBuffer.createBuffer(drawCountBytes);
        this.drawCountAllocationGeneration++;
        this.lastDrawCountAllocationUsedScratchPath = scratchStyle;
        this.lastOldRealDrawCountBufferStillExists = oldDrawCountBufferId != 0L;
        logDiagnosticDrawCountAllocation(drawCountBytes, oldDrawCountBufferId, scratchStyle);
    }


    private void logDiagnosticDrawCountAllocation(long drawCountBytes, long oldDrawCountBufferId, boolean scratchStyle) {
        if (!isExplicitCmdgenShaderSelectionDiagnosticActive()) {
            logInactiveDrawCountDiagnosticEnvVars();
            return;
        }
        if (CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER || CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE || CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT) {
            long newId = this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId();
            boolean recreated = this.lastDrawCountAllocationBufferId != 0L && this.lastDrawCountAllocationBufferId != newId;
            this.lastDrawCountAllocationBufferId = newId;
            VulkanBerylDebugLog.once("cmdgen-diagnostic-drawcount-allocation:" + this.drawCountAllocationGeneration, "cmdgen diagnostic drawCount allocation: shaderSelectionEnv=" + activeCmdgenShaderSelectionEnvVar()
                    + ", allocationPath=" + (scratchStyle ? "scratch_style_real_drawCountBuffer" : "default_real_drawCountBuffer")
                    + ", oldPathWouldUse=" + (largeDrawCountBufferActive() ? "large_default_wrapper" : "four_byte_default_wrapper")
                    + ", requestedDefaultCapacityBytes=" + Integer.BYTES
                    + ", actualCapacityBytes=" + drawCountBytes
                    + ", largeDrawCountBufferActive=" + largeDrawCountBufferActive()
                    + ", scratchAllocationForRealDrawCountActive=" + scratchStyle
                    + ", indirectUsageActive=" + ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0)
                    + ", bufferId=" + newId
                    + ", handle=" + newId
                    + ", oldRealDrawCountBufferId=" + oldDrawCountBufferId
                    + ", oldRealDrawCountBufferStillExists=" + (oldDrawCountBufferId != 0L)
                    + ", recreatedFromPrevious=" + recreated
                    + ", usageFlags=" + this.drawCountBufferUsageFlags
                    + ", usage=" + bufferUsageString(this.drawCountBufferUsageFlags));
        }
    }


    private static void logInactiveDrawCountDiagnosticEnvVars() {
        if (CMDGEN_BIND_DRAWCOUNT_TO_SCRATCH_BUFFER || CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER || CMDGEN_DRAWCOUNT_DESCRIPTOR_RANGE_FULL_BUFFER || CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE || CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT || CMDGEN_USE_PASSING_SCRATCH_BINDING_AS_REAL_DRAWCOUNT_DESCRIPTOR || CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH || CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH) {
            VulkanBerylDebugLog.once("cmdgen-drawcount-diagnostic-env-inactive", "cmdgen drawCount buffer diagnostic env var ignored because no explicit cmdgen shader-selection env var is active: scratch="
                    + CMDGEN_BIND_DRAWCOUNT_TO_SCRATCH_BUFFER
                    + ", largeBuffer=" + CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER
                    + ", fullDescriptorRange=" + CMDGEN_DRAWCOUNT_DESCRIPTOR_RANGE_FULL_BUFFER
                    + ", indirectUsage=" + CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE
                    + ", scratchAllocationForRealDrawCount=" + CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT
                    + ", passingScratchBindingAsRealDrawCountDescriptor=" + CMDGEN_USE_PASSING_SCRATCH_BINDING_AS_REAL_DRAWCOUNT_DESCRIPTOR
                    + ", skipDrawCountClearBeforeDispatch=" + CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH
                    + ", disableAnyDrawCountConsumerPath=" + CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH);
        }
    }


    private Buffer cmdgenDrawCountDescriptorBuffer() {
        if (usePassingScratchBindingAsRealDrawCountDescriptorActive()) {
            ensurePassingScratchDrawCountIsCurrent();
            return this.drawCountBuffer;
        }
        if (bindDrawCountToScratchActive()) {
            ensureCmdgenDrawCountScratchBuffer();
            return this.cmdgenDrawCountScratchBuffer;
        }
        return this.drawCountBuffer;
    }


    private void ensurePassingScratchDrawCountIsCurrent() {
        ensureCmdgenDrawCountScratchBuffer();
        if (this.drawCountBuffer == this.cmdgenDrawCountScratchBuffer) {
            return;
        }
        Buffer oldDrawCountBuffer = this.drawCountBuffer;
        long oldDrawCountBufferId = oldDrawCountBuffer == null ? 0L : oldDrawCountBuffer.getId();
        if (oldDrawCountBuffer != null) {
            oldDrawCountBuffer.scheduleFree();
        }
        this.drawCountBuffer = this.cmdgenDrawCountScratchBuffer;
        this.drawCountBufferUsageFlags = this.cmdgenDrawCountScratchBufferUsageFlags;
        this.drawCountAllocationGeneration++;
        this.lastDrawCountAllocationUsedScratchPath = true;
        this.lastOldRealDrawCountBufferStillExists = oldDrawCountBufferId != 0L;
        logPassingScratchAsRealAllocation(oldDrawCountBufferId);
    }


    private String drawCountDescriptorLabel() {
        return bindDrawCountToScratchActive() ? "cmdgenDrawCountScratchBuffer" : "drawCountBuffer";
    }


    private void logPassingScratchAsRealAllocation(long oldDrawCountBufferId) {
        VulkanBerylDebugLog.once("cmdgen-passing-scratch-as-real-drawcount-allocation:" + this.drawCountAllocationGeneration, "cmdgen passing scratch binding is current drawCountBuffer: shaderSelectionEnv=" + activeCmdgenShaderSelectionEnvVar()
                + ", allocationPath=passing_scratch_binding_as_current_drawCountBuffer"
                + ", realDrawCountObjectCreated=false"
                + ", bufferId=" + (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId())
                + ", capacityBytes=" + (this.drawCountBuffer == null ? -1L : this.drawCountBuffer.getBufferSize())
                + ", usageFlags=" + this.drawCountBufferUsageFlags
                + ", usage=" + bufferUsageString(this.drawCountBufferUsageFlags)
                + ", oldRealDrawCountBufferId=" + oldDrawCountBufferId
                + ", oldRealDrawCountBufferStillExists=" + (oldDrawCountBufferId != 0L));
    }


    private void logPassingScratchAsRealComparison() {
        if (!usePassingScratchBindingAsRealDrawCountDescriptorActive()) return;
        Buffer descriptorBuffer = cmdgenDrawCountDescriptorBuffer();
        long descriptorId = descriptorBuffer == null ? 0L : descriptorBuffer.getId();
        long currentId = this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId();
        VulkanBerylDebugLog.once("cmdgen-passing-scratch-as-real-comparison", "cmdgen passing scratch/current drawCount comparison: passingScratchPath=true"
                + ", realDrawCountObjectCreated=false"
                + ", descriptorBinding4BufferId=" + descriptorId
                + ", currentDrawCountBufferId=" + currentId
                + ", descriptorAndCurrentDrawCountSame=" + (descriptorBuffer == this.drawCountBuffer && descriptorId == currentId)
                + ", usedRealClearPath=" + this.drawCountUsedRealClearPathThisFrame
                + ", usedScratchClearPath=" + this.drawCountUsedScratchClearPathThisFrame);
    }


    private void logDrawCountBarrierDiagnostic(String stage, boolean recorded, int srcStageMask, int dstStageMask, int srcAccessMask, int dstAccessMask) {
        if (!isExplicitCmdgenShaderSelectionDiagnosticActive()) return;
        if (!(CMDGEN_BIND_DRAWCOUNT_TO_SCRATCH_BUFFER || CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT || CMDGEN_USE_PASSING_SCRATCH_BINDING_AS_REAL_DRAWCOUNT_DESCRIPTOR || CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH || CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH)) return;
        Buffer descriptorBuffer = cmdgenDrawCountDescriptorBuffer();
        VulkanBerylDebugLog.once("cmdgen-drawcount-barrier-diagnostics:" + stage, "cmdgen drawCount barrier diagnostics: stage=" + stage
                + ", barrierRecorded=" + recorded
                + ", clearCommandRecorded=" + this.drawCountClearCommandRecordedThisFrame
                + ", skippedByEnv=" + skipDrawCountClearBeforeDispatchActive()
                + ", drawCountClearedInitialisedThisFrame=" + this.drawCountClearedThisFrame
                + ", descriptorBinding4BufferId=" + (descriptorBuffer == null ? 0L : descriptorBuffer.getId())
                + ", currentDrawCountBufferId=" + (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId())
                + ", descriptorAndCurrentDrawCountSame=" + (descriptorBuffer == this.drawCountBuffer)
                + ", srcStageMask=" + srcStageMask
                + ", srcStage=" + pipelineStageMaskString(srcStageMask)
                + ", srcAccessMask=" + srcAccessMask
                + ", srcAccess=" + accessMaskString(srcAccessMask)
                + ", dstStageMask=" + dstStageMask
                + ", dstStage=" + pipelineStageMaskString(dstStageMask)
                + ", dstAccessMask=" + dstAccessMask
                + ", dstAccess=" + accessMaskString(dstAccessMask));
    }


    private void ensureCmdgenDrawCountScratchBuffer() {
        if (this.cmdgenDrawCountScratchBuffer != null && this.cmdgenDrawCountScratchBuffer.getBufferSize() >= CMDGEN_DIAGNOSTIC_DRAWCOUNT_CAPACITY_BYTES) return;
        if (this.cmdgenDrawCountScratchBuffer != null) this.cmdgenDrawCountScratchBuffer.scheduleFree();
        this.cmdgenDrawCountScratchBufferUsageFlags = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        this.cmdgenDrawCountScratchBuffer = new Buffer("voxy_vulkanberyl_cmdgen_drawcount_scratch", this.cmdgenDrawCountScratchBufferUsageFlags, MemoryTypes.GPU_MEM);
        this.cmdgenDrawCountScratchBuffer.createBuffer(CMDGEN_DIAGNOSTIC_DRAWCOUNT_CAPACITY_BYTES);
        VulkanBerylDebugLog.once("cmdgen-drawcount-scratch-created", "cmdgen diagnostic drawCount scratch buffer created for binding4: bufferId="
                + this.cmdgenDrawCountScratchBuffer.getId()
                + ", handle=" + this.cmdgenDrawCountScratchBuffer.getId()
                + ", capacityBytes=" + this.cmdgenDrawCountScratchBuffer.getBufferSize()
                + ", usageFlags=" + this.cmdgenDrawCountScratchBufferUsageFlags
                + ", usage=" + bufferUsageString(this.cmdgenDrawCountScratchBufferUsageFlags)
                + ", binding4ReboundToScratch=true");
    }


    private void logCmdgenDrawCountDescriptorOverride(Buffer descriptorBuffer, String stage) {
        if (!isExplicitCmdgenShaderSelectionDiagnosticActive()) return;
        if (descriptorBuffer == null) return;
        boolean scratch = descriptorBuffer == this.cmdgenDrawCountScratchBuffer;
        if (scratch || CMDGEN_DRAWCOUNT_DESCRIPTOR_RANGE_FULL_BUFFER || CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER || CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE) {
            VulkanBerylDebugLog.once("cmdgen-drawcount-descriptor-override:" + stage, "cmdgen drawCount descriptor binding4 diagnostics: stage=" + stage
                    + ", binding=" + CMDGEN_DRAW_COUNT_BINDING
                    + ", reboundToScratch=" + scratch
                    + ", descriptorBufferId=" + descriptorBuffer.getId()
                    + ", handle=" + descriptorBuffer.getId()
                    + ", descriptorCapacityBytes=" + descriptorBuffer.getBufferSize()
                    + ", descriptorRangeBytes=" + descriptorBuffer.getBufferSize()
                    + ", realDrawCountBufferId=" + (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId())
                    + ", realDrawCountCapacityBytes=" + (this.drawCountBuffer == null ? -1L : this.drawCountBuffer.getBufferSize())
                    + ", realDrawCountUsage=" + bufferUsageString(this.drawCountBufferUsageFlags)
                    + ", scratchUsage=" + (scratch ? bufferUsageString(this.cmdgenDrawCountScratchBufferUsageFlags) : "<not-bound>")
                    + ", fullDescriptorRangeActive=" + drawCountFullDescriptorRangeActive()
                    + ", existingAbstractionOnlyFourBytes=" + (!scratch && descriptorBuffer.getBufferSize() == Integer.BYTES));
        }
    }


    private static long alignedFillBytes(long bufferBytes) {
        return Math.max(4L, bufferBytes & ~3L);
    }


    private void clearDrawCommandState(VkCommandBuffer commandBuffer) {
        logDrawCountBarrierDiagnostic("before_clear", false, 0, 0, 0, 0);
        if (skipDrawCountClearBeforeDispatchActive()) {
            this.drawCountClearedThisFrame = false;
            this.drawCountClearCommandRecordedThisFrame = false;
            VulkanBerylDebugLog.once("cmdgen-drawcount-clear-skipped-before-dispatch", "cmdgen drawCount clear/initialise before dispatch skipped: env=VOXY_VULKAN_BERYL_CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH, shaderSelectionEnv=" + activeCmdgenShaderSelectionEnvVar());
            logDrawCountBarrierDiagnostic("after_clear", false, 0, 0, 0, 0);
        } else if (usePassingScratchBindingAsRealDrawCountDescriptorActive()) {
            Buffer drawCountDescriptorBuffer = cmdgenDrawCountDescriptorBuffer();
            long clearBytes = alignedFillBytes(drawCountDescriptorBuffer.getBufferSize());
            VK10.vkCmdFillBuffer(commandBuffer, drawCountDescriptorBuffer.getId(), 0L, clearBytes, 0);
            this.drawCountClearedThisFrame = true;
            this.drawCountClearCommandRecordedThisFrame = true;
            this.drawCountUsedScratchClearPathThisFrame = true;
            VulkanBerylDebugLog.once("cmdgen-drawcount-passing-scratch-current-cleared", "cmdgen current drawCount uses passing scratch-binding clear path before dispatch: bufferId="
                    + drawCountDescriptorBuffer.getId()
                    + ", capacityBytes=" + drawCountDescriptorBuffer.getBufferSize()
                    + ", clearBytes=" + clearBytes);
            logDrawCountBarrierDiagnostic("after_clear", false, 0, 0, 0, 0);
        } else {
            long drawCountClearBytes = useScratchAllocationForRealDrawCountActive() ? alignedFillBytes(this.drawCountBuffer.getBufferSize()) : CMDGEN_DRAWCOUNT_DIAGNOSTIC_BYTES;
            VK10.vkCmdFillBuffer(commandBuffer, this.drawCountBuffer.getId(), 0L, drawCountClearBytes, 0);
            this.drawCountClearCommandRecordedThisFrame = true;
            this.drawCountUsedRealClearPathThisFrame = true;
            if (useScratchAllocationForRealDrawCountActive()) {
                VulkanBerylDebugLog.once("cmdgen-drawcount-scratch-real-cleared", "cmdgen real drawCount scratch-allocation buffer cleared before dispatch: bufferId="
                        + this.drawCountBuffer.getId()
                        + ", capacityBytes=" + this.drawCountBuffer.getBufferSize()
                        + ", clearBytes=" + drawCountClearBytes
                        + ", usage=" + bufferUsageString(this.drawCountBufferUsageFlags));
            }
            Buffer drawCountDescriptorBuffer = cmdgenDrawCountDescriptorBuffer();
            if (drawCountDescriptorBuffer != this.drawCountBuffer) {
                long scratchClearBytes = alignedFillBytes(drawCountDescriptorBuffer.getBufferSize());
                VK10.vkCmdFillBuffer(commandBuffer, drawCountDescriptorBuffer.getId(), 0L, scratchClearBytes, 0);
                this.drawCountUsedScratchClearPathThisFrame = true;
                VulkanBerylDebugLog.once("cmdgen-drawcount-scratch-cleared", "cmdgen drawCount diagnostic scratch buffer cleared before dispatch: bufferId="
                        + drawCountDescriptorBuffer.getId()
                        + ", capacityBytes=" + drawCountDescriptorBuffer.getBufferSize()
                        + ", clearBytes=" + scratchClearBytes);
            }
            this.drawCountClearedThisFrame = true;
            logDrawCountBarrierDiagnostic("after_clear", false, 0, 0, 0, 0);
        }
        VulkanBerylDebugLog.once("cmdgen-drawcount-clear-recorded", "cmdgen drawCount clear command recorded=" + this.drawCountClearCommandRecordedThisFrame
                + ", skippedByEnv=" + skipDrawCountClearBeforeDispatchActive()
                + ", usedRealClearPath=" + this.drawCountUsedRealClearPathThisFrame
                + ", usedScratchClearPath=" + this.drawCountUsedScratchClearPathThisFrame);
        long clearBytes = Math.min(this.drawCommandBuffer.getBufferSize(), (long) DRAW_COMMAND_DEBUG_SAMPLE_LIMIT * DRAW_COMMAND_STRIDE_BYTES);
        if (clearBytes > 0L) {
            VK10.vkCmdFillBuffer(commandBuffer, this.drawCommandBuffer.getId(), 0L, clearBytes, 0);
        }
        if (this.cmdGenBinding2ProbeBuffer != null) {
            uploadCmdgenBinding2ProbeBuffer(commandBuffer);
        }
    }



    private void ensureCmdgenTinyMetadataProbeBuffer() {
        if (this.cmdGenTinyMetadataProbeBuffer != null) return;
        this.cmdGenTinyMetadataProbeBuffer = new Buffer("voxy_vulkanberyl_cmdgen_tiny_metadata_probe",
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                MemoryTypes.GPU_MEM);
        this.cmdGenTinyMetadataProbeBuffer.createBuffer(VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE);
        VulkanBerylDebugLog.once("cmdgen-tiny-metadata-probe-created", "cmdgen tiny metadata probe buffer created: bufferId="
                + this.cmdGenTinyMetadataProbeBuffer.getId()
                + ", capacityBytes=" + this.cmdGenTinyMetadataProbeBuffer.getBufferSize()
                + ", usage=STORAGE|TRANSFER_DST"
                + ", sectionMetaStrideBytes=" + VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE);
    }


    private void uploadTinyMetadataProbeBuffer(VkCommandBuffer commandBuffer) {
        ensureCmdgenTinyMetadataProbeBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var words = stack.ints(0x13572468, 0x24681357, 0xabcdef01, 0x10fedcba, 0, 0, 0, 0);
            VK10.vkCmdUpdateBuffer(commandBuffer, this.cmdGenTinyMetadataProbeBuffer.getId(), 0L, words);
        }
    }


    private void ensureCmdgenBinding2ProbeBuffer() {
        if (this.cmdGenBinding2ProbeBuffer != null) {
            if (this.cmdGenBinding2ProbeBuffer.getBufferSize() >= CMDGEN_BINDING2_PROBE_SIZE_BYTES) return;
            this.cmdGenBinding2ProbeBuffer.scheduleFree();
        }
        this.cmdGenBinding2ProbeBuffer = new Buffer("voxy_vulkanberyl_cmdgen_binding2_probe",
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                MemoryTypes.GPU_MEM);
        this.cmdGenBinding2ProbeBuffer.createBuffer(CMDGEN_BINDING2_PROBE_SIZE_BYTES);
        VulkanBerylDebugLog.once("cmdgen-binding2-probe-created", "cmdgen binding-2 probe buffer created: bufferId="
                + this.cmdGenBinding2ProbeBuffer.getId()
                + ", capacityBytes=" + this.cmdGenBinding2ProbeBuffer.getBufferSize()
                + ", usage=STORAGE|TRANSFER_DST"
                + ", sectionMetaStrideBytes=" + VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE);
    }


    private void uploadCmdgenBinding2ProbeBuffer(VkCommandBuffer commandBuffer) {
        ensureCmdgenBinding2ProbeBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var words = stack.ints(0, 0, 0, 0, 0, 0, 0, 0);
            VK10.vkCmdUpdateBuffer(commandBuffer, this.cmdGenBinding2ProbeBuffer.getId(), 0L, words);
        }
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
                || CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE
                || CMDGEN_SHADER_READ_BINDING1_ONLY
                || CMDGEN_RENDERLIST_ALT_BUFFER_PROBE
                || controlledRenderListDiagnosticEnabled()
                || VulkanBerylDebugLog.TRACE_LOGS
                || VulkanBerylDebugLog.VERBOSE_LOGS;
    }


    private String validateCmdgenDispatchInputs(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, int visibleCount, ControlledRenderListSmoke controlledSmoke, boolean skipControlledSectionValidation, CmdgenIsolationStage isolationStage) {
        if (this.commandGenPipeline == null) return "cmdgen_pipeline_missing";
        if (this.drawCommandBuffer == null) return "draw_command_buffer_missing";
        if (this.drawCountBuffer == null) return "draw_count_buffer_missing";
        if (this.cmdGenConfigBuffer == null) return "cmdgen_config_buffer_missing";
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
        if (!geometryData.isMetadataStorageBufferCapable()) {
            return "metadata_usage_invalid: usage=" + geometryData.getMetadataUsageString() + " missing=STORAGE_BUFFER";
        }
        if (this.drawCommandBuffer.getBufferSize() < Math.multiplyExact((long) visibleCount, DRAW_COMMAND_STRIDE_BYTES)) {
            return "draw_command_capacity_exceeded: visibleCount=" + visibleCount + " bufferBytes=" + this.drawCommandBuffer.getBufferSize();
        }
        if (this.drawCountBuffer.getBufferSize() < CMDGEN_DRAWCOUNT_DIAGNOSTIC_BYTES) {
            return "draw_count_capacity_too_small: bufferBytes=" + this.drawCountBuffer.getBufferSize() + " required=" + CMDGEN_DRAWCOUNT_DIAGNOSTIC_BYTES;
        }
        if (this.cmdGenConfigBuffer.getBufferSize() < CMDGEN_CONFIG_SIZE_BYTES) {
            return "cmdgen_config_capacity_too_small: bufferBytes=" + this.cmdGenConfigBuffer.getBufferSize() + " required=" + CMDGEN_CONFIG_SIZE_BYTES;
        }
        if (skipControlledSectionValidation || (isolationStage != null && !isolationStage.requiresControlledSection())) {
            return null;
        }
        if (!controlledSmoke.safe()) {
            return "controlled_smoke_section_unavailable:" + controlledSmoke.reason();
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
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, transferToCompute, null, null);
        }
    }


    private void barrierTransferToComputeForCmdgenNonDrawCountTransfers(VkCommandBuffer commandBuffer, Buffer additionalBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int barrierCount = 0;
            if (this.cmdGenConfigBuffer != null) barrierCount++;
            if (this.drawCommandBuffer != null) barrierCount++;
            if (additionalBuffer != null) barrierCount++;
            if (barrierCount == 0) return;
            VkBufferMemoryBarrier.Buffer transferToCompute = VkBufferMemoryBarrier.calloc(barrierCount, stack);
            int barrierIndex = 0;
            barrierIndex = appendTransferToComputeBufferBarrier(transferToCompute, barrierIndex, this.cmdGenConfigBuffer);
            barrierIndex = appendTransferToComputeBufferBarrier(transferToCompute, barrierIndex, this.drawCommandBuffer);
            appendTransferToComputeBufferBarrier(transferToCompute, barrierIndex, additionalBuffer);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, transferToCompute, null);
        }
    }


    private int appendTransferToComputeBufferBarrier(VkBufferMemoryBarrier.Buffer barriers, int index, Buffer buffer) {
        if (buffer == null) return index;
        barriers.get(index)
                .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.getId())
                .offset(0L)
                .size(buffer.getBufferSize());
        return index + 1;
    }


    private void logCmdgenProbeTransferBarrierAfterUploads(String stage, boolean readsTinyMetadataProbe, boolean readsBinding2Probe, boolean readsConfig) {
        VulkanBerylDebugLog.once("cmdgen-transfer-barrier-after-uploads:" + stage, "cmdgen probe transfer barrier placed after uploads: stage=" + stage
                + ", srcStage=TRANSFER"
                + ", srcAccess=TRANSFER_WRITE"
                + ", dstStage=COMPUTE_SHADER"
                + ", dstAccess=SHADER_READ|SHADER_WRITE"
                + ", afterCmdGenConfigUpload=" + readsConfig
                + ", afterCmdGenBinding2ProbeUpload=" + readsBinding2Probe
                + ", afterCmdGenTinyMetadataProbeUpload=" + readsTinyMetadataProbe
                + ", beforeDispatch=true");
    }


    private void validateDrawCommandBuffer(int visibleCount) {
        if (this.drawCommandBuffer == null) throw new IllegalStateException("drawCommandBuffer must not be null");
        if (this.drawCommandBuffer.getId() == 0L) throw new IllegalStateException("drawCommandBuffer has invalid Vulkan buffer id");
        if ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) == 0) throw new IllegalStateException("drawCommandBuffer missing VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT");
        if ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0) throw new IllegalStateException("drawCommandBuffer missing VK_BUFFER_USAGE_TRANSFER_SRC_BIT");
        long requiredBytes = Math.multiplyExact((long) visibleCount, DRAW_COMMAND_STRIDE_BYTES);
        if (this.drawCommandBuffer.getBufferSize() < requiredBytes) throw new IllegalStateException("drawCommandBuffer is too small for visible draws");
    }


    private void logDebugReadbackIsolationDiagnostics(String mode, boolean scheduleCalled, boolean readbackVulkanCommandRecorded, boolean debugSamplePendingChanged) {
        VulkanBerylDebugLog.once("cmdgen-debug-readback-isolation", "cmdgen debug readback isolation: mode=" + mode
                + ", scheduleCalled=" + scheduleCalled
                + ", readbackVulkanCommandRecorded=" + readbackVulkanCommandRecorded
                + ", debugSamplePendingChanged=" + debugSamplePendingChanged);
    }


    private String geometryDiagnosticStaleReason(long selectedQuadIndex, long selectedByteOffset, long currentSourceBufferId, long currentGeometrySyncGeneration, long currentFrameId, int currentRendererFrameSlot) {
        if (this.geometryDiagnosticReadbackScheduled && !this.geometryDiagnosticReadbackCompleted) {
            String pendingReason = geometryDiagnosticStateStaleReason(
                    this.geometryDiagnosticPendingQuadIndex,
                    this.geometryDiagnosticPendingByteOffset,
                    this.geometryDiagnosticPendingSourceBufferId,
                    this.geometryDiagnosticPendingGeometrySyncGeneration,
                    this.geometryDiagnosticPendingDrawPass,
                    selectedQuadIndex,
                    selectedByteOffset,
                    currentSourceBufferId,
                    currentGeometrySyncGeneration);
            if (!"none".equals(pendingReason)) return pendingReason;
            if ("renderer_slot_not_advanced".equals(this.geometryDiagnosticRejectReason)
                    && this.geometryDiagnosticPendingFrameId >= 0L
                    && currentFrameId >= 0L
                    && currentFrameId - this.geometryDiagnosticPendingFrameId >= 3L
                    && currentRendererFrameSlot >= 0
                    && currentRendererFrameSlot == this.geometryDiagnosticPendingRendererFrameSlot) {
                return "renderer_slot_not_advanced";
            }
            return "none";
        }
        if (this.geometryDiagnosticReadbackCompleted) {
            return geometryDiagnosticStateStaleReason(
                    this.geometryDiagnosticCompletedQuadIndex,
                    this.geometryDiagnosticCompletedByteOffset,
                    this.geometryDiagnosticCompletedSourceBufferId,
                    this.geometryDiagnosticCompletedGeometrySyncGeneration,
                    this.geometryDiagnosticCompletedDrawPass,
                    selectedQuadIndex,
                    selectedByteOffset,
                    currentSourceBufferId,
                    currentGeometrySyncGeneration);
        }
        return "none";
    }


    private String geometryDiagnosticStateStaleReason(long quadIndex, long byteOffset, long sourceBufferId, long geometrySyncGeneration, DrawPass drawPass,
                                                      long selectedQuadIndex, long selectedByteOffset, long currentSourceBufferId, long currentGeometrySyncGeneration) {
        if (geometrySyncGeneration != 0L
                && currentGeometrySyncGeneration != 0L
                && geometrySyncGeneration != currentGeometrySyncGeneration) {
            return "geometry_sync_generation_changed";
        }
        if (sourceBufferId != 0L
                && currentSourceBufferId != 0L
                && sourceBufferId != currentSourceBufferId) {
            return "source_buffer_changed";
        }
        if (drawPass != preferredDiagnosticReadbackPass()) {
            return "command_buffer_slot_mismatch";
        }
        if (quadIndex != selectedQuadIndex || byteOffset != selectedByteOffset) {
            return "selected_quad_changed";
        }
        return "none";
    }


    private void clearGeometryDiagnosticReadbackState(String staleReason) {
        this.geometryDiagnosticStaleCleared = true;
        this.geometryDiagnosticStaleClearReason = staleReason;
        this.geometryDiagnosticScheduledAfterStaleClear = false;
        this.geometryDiagnosticReadbackScheduled = false;
        this.geometryDiagnosticReadbackCompleted = false;
        this.geometryDiagnosticReadbackCopyRecorded = false;
        this.geometryDiagnosticPendingQuadIndex = -1L;
        this.geometryDiagnosticPendingByteOffset = -1L;
        this.geometryDiagnosticPendingFrameId = -1L;
        this.geometryDiagnosticPendingRendererFrameSlot = -1;
        this.geometryDiagnosticPendingCommandBufferAddress = 0L;
        this.geometryDiagnosticPendingSourceBufferId = 0L;
        this.geometryDiagnosticPendingGeometrySyncGeneration = 0L;
        this.geometryDiagnosticPendingDrawPass = DrawPass.OPAQUE;
        this.geometryDiagnosticCompletedQuadIndex = -1L;
        this.geometryDiagnosticCompletedByteOffset = -1L;
        this.geometryDiagnosticCompletedFrameId = -1L;
        this.geometryDiagnosticCompletedRendererFrameSlot = -1;
        this.geometryDiagnosticCompletedCommandBufferAddress = 0L;
        this.geometryDiagnosticCompletedSourceBufferId = 0L;
        this.geometryDiagnosticCompletedGeometrySyncGeneration = 0L;
        this.geometryDiagnosticCompletedDrawPass = DrawPass.OPAQUE;
        this.geometryDiagnosticCompletedRaw = 0L;
        this.geometryDiagnosticRejectReason = staleReason;
    }


    private ScheduledDebugReadback scheduleDebugCommandReadback(VkCommandBuffer commandBuffer, int requestedSampledCommandCount, int visibleCount, VulkanBerylSectionGeometryData geometryData, long geometryDiagnosticQuadIndex, long currentFrameId) {
        long geometryDiagnosticByteOffset = geometryDiagnosticQuadIndex < 0L ? -1L : Math.multiplyExact(geometryDiagnosticQuadIndex, Long.BYTES);
        long geometryDiagnosticRequiredBytes = geometryDiagnosticByteOffset < 0L ? -1L : Math.addExact(geometryDiagnosticByteOffset, Long.BYTES);
        this.geometryDiagnosticStaleCleared = false;
        this.geometryDiagnosticStaleClearReason = "none";
        this.geometryDiagnosticScheduledAfterStaleClear = false;
        String staleReason = geometryDiagnosticStaleReason(
                geometryDiagnosticQuadIndex,
                geometryDiagnosticByteOffset,
                geometryData.getGeometryBuffer().getId(),
                geometryData.getGeometrySyncGeneration(),
                currentFrameId,
                safeRendererFrameSlot());
        if (!"none".equals(staleReason)) {
            clearGeometryDiagnosticReadbackState(staleReason);
        }
        boolean geometryReadbackPending = this.geometryDiagnosticReadbackScheduled && !this.geometryDiagnosticReadbackCompleted;
        if (!geometryReadbackPending) {
            this.geometryDiagnosticReadbackScheduled = false;
            this.geometryDiagnosticReadbackCopyRecorded = false;
            if (!this.geometryDiagnosticStaleCleared) {
                this.geometryDiagnosticRejectReason = "not_scheduled";
            }
        }
        boolean debugSamplePendingBefore = this.debugSamplePending;
        if (CMDGEN_DEBUG_READBACK_SCHEDULE_ENTER_ONLY) {
            long intendedCommandCopyBytes = Math.max(0L, (long) requestedSampledCommandCount * DRAW_COMMAND_STRIDE_BYTES);
            VulkanBerylDebugLog.once("cmdgen-debug-readback-schedule-enter-only", "cmdgen debug readback schedule enter only: mode=" + debugReadbackCopyModeName()
                    + ", requestedSampleCount=" + requestedSampledCommandCount
                    + ", visibleCount=" + visibleCount
                    + ", geometryBufferBytes=" + geometryData.getGeometryBuffer().getBufferSize()
                    + ", intendedDrawCommandCopyBytes=" + intendedCommandCopyBytes
                    + ", intendedDrawCountCopyBytes=" + Integer.BYTES);
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, false, this.debugSamplePending != debugSamplePendingBefore);
            return new ScheduledDebugReadback(false, "enter_only", intendedCommandCopyBytes, Integer.BYTES);
        }
        if (requestedSampledCommandCount <= 0) {
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, false, this.debugSamplePending != debugSamplePendingBefore);
            return new ScheduledDebugReadback(false, "requested_sample_count_zero", 0L, 0L);
        }
        if (this.drawCommandDebugReadbackBuffer == null || this.drawCountDebugReadbackBuffer == null) {
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, false, this.debugSamplePending != debugSamplePendingBefore);
            return new ScheduledDebugReadback(false, "readback_buffers_missing", 0L, 0L);
        }
        Buffer readbackDrawCommandSourceBuffer = this.javaKnownControlledSmokeCommandWrittenThisFrame && this.controlledSmokeKnownCommandBuffer != null ? this.controlledSmokeKnownCommandBuffer : this.drawCommandBuffer;
        int readbackDrawCommandSourceUsageFlags = readbackDrawCommandSourceBuffer != null && readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer ? this.controlledSmokeKnownCommandBufferUsageFlags : this.drawCommandBufferUsageFlags;
        if (readbackDrawCommandSourceBuffer == null || this.drawCountBuffer == null) {
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, false, this.debugSamplePending != debugSamplePendingBefore);
            return new ScheduledDebugReadback(false, "source_buffers_missing", 0L, 0L);
        }
        boolean isControlledSmokeReadback = this.controlledSmokeKnownCommandBuffer != null
                && readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer;
        if (isControlledSmokeReadback && DISABLE_CONTROLLED_SMOKE_READBACK_COPY) {
            this.controlledSmokeReadbackCopyRecordedThisFrame = false;
            this.controlledSmokeReadbackBarrierRecordedThisFrame = false;
            VulkanBerylDebugLog.rateLimited("controlled-smoke-readback-copy-disabled-env", "controlled smoke readback copy disabled by env: env=VOXY_VULKAN_BERYL_CONTROLLED_SMOKE_DISABLE_READBACK_COPY"
                    + ", controlledSmokeReadbackCopyRecorded=false"
                    + ", controlledSmokeReadbackBarrierRecorded=false"
                    + ", drawSubmitReason=diagnostic_readback_copy_disabled", 60);
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, false, this.debugSamplePending != debugSamplePendingBefore);
            return new ScheduledDebugReadback(false, "controlled_smoke_readback_copy_disabled_by_env", 0L, 0L);
        }
        if ((readbackDrawCommandSourceUsageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalStateException("cmdgen debug readback source draw command buffer missing VK_BUFFER_USAGE_TRANSFER_SRC_BIT");
        }
        if ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalStateException("cmdgen debug readback source drawCountBuffer missing VK_BUFFER_USAGE_TRANSFER_SRC_BIT");
        }
        if ((this.drawCommandDebugReadbackBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) == 0) {
            throw new IllegalStateException("cmdgen debug readback destination drawCommandDebugReadbackBuffer missing VK_BUFFER_USAGE_TRANSFER_DST_BIT");
        }
        if ((this.drawCountDebugReadbackBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) == 0) {
            throw new IllegalStateException("cmdgen debug readback destination drawCountDebugReadbackBuffer missing VK_BUFFER_USAGE_TRANSFER_DST_BIT");
        }
        if (this.drawCommandDebugReadbackBuffer.getDataPtr() == 0L || this.drawCountDebugReadbackBuffer.getDataPtr() == 0L) {
            throw new IllegalStateException("cmdgen debug readback destination buffers must be host-readable/mapped");
        }
        if (!geometryReadbackPending && (this.geometryQuadDebugReadbackBuffer == null || this.geometryQuadDebugReadbackBuffer.getDataPtr() == 0L)) {
            this.geometryDiagnosticRejectReason = "readback_staging_unavailable";
        }

        long drawCommandSourceBytes = Math.min(readbackDrawCommandSourceBuffer.getBufferSize(), readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer ? DRAW_COMMAND_STRIDE_BYTES : (long) this.drawCommandCapacity * DRAW_COMMAND_STRIDE_BYTES);
        long drawCommandDestinationBytes = this.drawCommandDebugReadbackBuffer.getBufferSize();
        int capacitySampleCount = (int) Math.min(Integer.MAX_VALUE, drawCommandSourceBytes / DRAW_COMMAND_STRIDE_BYTES);
        int destinationSampleCount = (int) Math.min(Integer.MAX_VALUE, drawCommandDestinationBytes / DRAW_COMMAND_STRIDE_BYTES);
        int sampledCommandCount = Math.max(0, Math.min(Math.min(requestedSampledCommandCount, capacitySampleCount), destinationSampleCount));
        long commandCopyBytes = (long) sampledCommandCount * DRAW_COMMAND_STRIDE_BYTES;
        boolean copyDrawCommands = commandCopyBytes > 0L;
        boolean copyDrawCount = this.drawCountBuffer.getBufferSize() >= Integer.BYTES && this.drawCountDebugReadbackBuffer.getBufferSize() >= Integer.BYTES;
        if (CMDGEN_DEBUG_READBACK_NO_COPY || CMDGEN_DEBUG_READBACK_NO_BARRIERS_NO_COPY) {
            copyDrawCommands = false;
            copyDrawCount = false;
        } else if (CMDGEN_DEBUG_READBACK_DRAW_COUNT_ONLY) {
            copyDrawCommands = false;
        } else if (CMDGEN_DEBUG_READBACK_DRAW_COMMANDS_ONLY) {
            copyDrawCount = false;
        }
        long countCopyBytes = copyDrawCount ? Math.min((long) CMDGEN_DRAWCOUNT_DIAGNOSTIC_BYTES, Math.min(this.drawCountBuffer.getBufferSize(), this.drawCountDebugReadbackBuffer.getBufferSize())) : 0L;
        boolean copyGeometryQuad = !geometryReadbackPending
                && this.geometryQuadDebugReadbackBuffer != null
                && this.geometryQuadDebugReadbackBuffer.getDataPtr() != 0L
                && geometryDiagnosticByteOffset >= 0L
                && geometryDiagnosticRequiredBytes <= geometryData.getUsedGeometryBytes()
                && geometryDiagnosticRequiredBytes <= geometryData.getGeometryBuffer().getBufferSize()
                && (geometryData.getGeometryUsageFlags() & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0;
        this.geometryDiagnosticRejectReason = copyGeometryQuad ? "scheduled"
                : geometryReadbackPending ? this.geometryDiagnosticRejectReason
                : geometryDiagnosticQuadIndex < 0L ? "quad_index_negative"
                : geometryDiagnosticRequiredBytes > geometryData.getUsedGeometryBytes() || geometryDiagnosticRequiredBytes > geometryData.getGeometryBuffer().getBufferSize() ? "quad_index_out_of_used_range"
                : (geometryData.getGeometryUsageFlags() & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0 ? "geometry_buffer_missing_transfer_src_usage"
                : "readback_staging_unavailable";
        VulkanBerylDebugLog.once("geometry-quad-debug-readback-copy-mode", "geometry quad debug readback copy mode: quadIndex=" + geometryDiagnosticQuadIndex
                + ", byteOffset=" + geometryDiagnosticByteOffset
                + ", requiredBytes=" + geometryDiagnosticRequiredBytes
                + ", usedGeometryBytes=" + geometryData.getUsedGeometryBytes()
                + ", geometryBufferSizeBytes=" + geometryData.getGeometryBuffer().getBufferSize()
                + ", sourceBufferId=" + geometryData.getGeometryBuffer().getId()
                + ", sourceUsage=" + bufferUsageString(geometryData.getGeometryUsageFlags())
                + ", destinationBufferId=" + (this.geometryQuadDebugReadbackBuffer == null ? 0L : this.geometryQuadDebugReadbackBuffer.getId())
                + ", destinationCapacityBytes=" + (this.geometryQuadDebugReadbackBuffer == null ? 0L : this.geometryQuadDebugReadbackBuffer.getBufferSize())
                + ", copyEnabled=" + copyGeometryQuad
                + ", geometryDiagnosticStaleCleared=" + this.geometryDiagnosticStaleCleared
                + ", geometryDiagnosticStaleClearReason=" + this.geometryDiagnosticStaleClearReason
                + ", geometryDiagnosticScheduledAfterStaleClear=" + (this.geometryDiagnosticStaleCleared && copyGeometryQuad)
                + ", geometryDiagnosticFrameId=" + (this.geometryDiagnosticReadbackCompleted ? this.geometryDiagnosticCompletedFrameId : this.geometryDiagnosticPendingFrameId)
                + ", geometryDiagnosticCurrentFrameId=" + currentFrameId
                + ", geometryDiagnosticGeometrySyncGeneration=" + (this.geometryDiagnosticReadbackCompleted ? this.geometryDiagnosticCompletedGeometrySyncGeneration : this.geometryDiagnosticPendingGeometrySyncGeneration)
                + ", geometryDiagnosticCurrentGeometrySyncGeneration=" + geometryData.getGeometrySyncGeneration()
                + ", rejectReason=" + this.geometryDiagnosticRejectReason);
        VulkanBerylDebugLog.once("cmdgen-debug-readback-copy-mode", "cmdgen debug readback copy mode: mode=" + debugReadbackCopyModeName()
                + ", copyDrawCommands=" + copyDrawCommands
                + ", copyDrawCount=" + copyDrawCount
                + ", requestedSampleCount=" + requestedSampledCommandCount
                + ", finalSampleCount=" + sampledCommandCount);
        if (!copyDrawCommands && !copyDrawCount) {
            if (copyGeometryQuad) {
                this.geometryDiagnosticReadbackScheduled = true;
                this.geometryDiagnosticReadbackCompleted = false;
                this.geometryDiagnosticPendingQuadIndex = geometryDiagnosticQuadIndex;
                this.geometryDiagnosticPendingByteOffset = geometryDiagnosticByteOffset;
                this.geometryDiagnosticPendingFrameId = -1L;
                this.geometryDiagnosticPendingRendererFrameSlot = safeRendererFrameSlot();
                this.geometryDiagnosticPendingCommandBufferAddress = commandBuffer.address();
                this.geometryDiagnosticPendingSourceBufferId = geometryData.getGeometryBuffer().getId();
                this.geometryDiagnosticPendingGeometrySyncGeneration = geometryData.getGeometrySyncGeneration();
                this.geometryDiagnosticPendingDrawPass = this.activeDrawPass;
                this.geometryDiagnosticReadbackCopyRecorded = false;
                this.geometryDiagnosticRejectReason = "readback_staging_unavailable";
            }
            VulkanBerylDebugLog.once("cmdgen-debug-readback-no-copy", "cmdgen debug readback no-copy mode: would read back drawCommandBuffer copyBytes="
                    + commandCopyBytes + ", drawCountBuffer copyBytes=" + (this.drawCountBuffer.getBufferSize() >= Integer.BYTES && this.drawCountDebugReadbackBuffer.getBufferSize() >= Integer.BYTES ? Integer.BYTES : 0)
                    + "; skipping debug readback barriers, vkCmdCopyBuffer, and leaving debugSamplePending=false");
            logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, false, this.debugSamplePending != debugSamplePendingBefore);
            return new ScheduledDebugReadback(false, "copy_disabled", commandCopyBytes, countCopyBytes);
        }

        VulkanBerylDebugLog.once("cmdgen-debug-readback-copy-draw-commands", "cmdgen debug readback copy: source=" + (readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer ? "controlledSmokeKnownCommandBuffer" : "drawCommandBuffer")
                + ", sourceBufferId=" + readbackDrawCommandSourceBuffer.getId()
                + ", sourceCapacityBytes=" + readbackDrawCommandSourceBuffer.getBufferSize()
                + ", sourceUsage=" + bufferUsageString(readbackDrawCommandSourceUsageFlags)
                + ", drawCommandCapacity=" + (readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer ? 1 : this.drawCommandCapacity)
                + ", destinationBufferId=" + this.drawCommandDebugReadbackBuffer.getId()
                + ", destinationCapacityBytes=" + drawCommandDestinationBytes
                + ", copyEnabled=" + copyDrawCommands
                + ", copyBytes=" + (copyDrawCommands ? commandCopyBytes : 0L)
                + ", requestedSampleCount=" + requestedSampledCommandCount
                + ", finalSampleCount=" + sampledCommandCount
                + ", controlledSmokeDrawUsesDedicatedCommandBuffer=" + (readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer)
                + ", controlledSmokeDrawCommandBufferId=" + (this.controlledSmokeKnownCommandBuffer == null ? (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId()) : this.controlledSmokeKnownCommandBuffer.getId())
                + ", controlledSmokeReadbackBufferId=" + readbackDrawCommandSourceBuffer.getId()
                + ", controlledSmokeReadbackMatchesDrawBuffer=" + (readbackDrawCommandSourceBuffer == this.controlledSmokeKnownCommandBuffer || this.controlledSmokeKnownCommandBuffer == null)
                + ", barrier=" + (this.javaKnownControlledSmokeCommandWrittenThisFrame ? "TRANSFER/TRANSFER_WRITE->TRANSFER/TRANSFER_READ" : "COMPUTE_SHADER/SHADER_WRITE->TRANSFER/TRANSFER_READ") + " before vkCmdCopyBuffer");
        VulkanBerylDebugLog.once("cmdgen-debug-readback-copy-draw-count", "cmdgen debug readback copy: source=drawCountBuffer"
                + ", sourceBufferId=" + this.drawCountBuffer.getId()
                + ", sourceCapacityBytes=" + this.drawCountBuffer.getBufferSize()
                + ", sourceUsage=" + bufferUsageString(this.drawCountBufferUsageFlags)
                + ", destinationBufferId=" + this.drawCountDebugReadbackBuffer.getId()
                + ", destinationCapacityBytes=" + this.drawCountDebugReadbackBuffer.getBufferSize()
                + ", copyEnabled=" + copyDrawCount
                + ", copyBytes=" + countCopyBytes
                + ", barrier=" + (this.javaKnownControlledSmokeCommandWrittenThisFrame ? "COMPUTE_SHADER|TRANSFER/SHADER_WRITE|TRANSFER_WRITE->TRANSFER/TRANSFER_READ" : "COMPUTE_SHADER/SHADER_WRITE->TRANSFER/TRANSFER_READ") + " before vkCmdCopyBuffer");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int readbackSrcStageMask = this.javaKnownControlledSmokeCommandWrittenThisFrame
                    ? VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                    : VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            int readbackSrcAccessMask = this.javaKnownControlledSmokeCommandWrittenThisFrame
                    ? VK10.VK_ACCESS_TRANSFER_WRITE_BIT
                    : VK10.VK_ACCESS_SHADER_WRITE_BIT;
            VkMemoryBarrier.Buffer shaderToTransfer = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(readbackSrcAccessMask)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    readbackSrcStageMask,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, shaderToTransfer, null, null);
            if (isControlledSmokeReadback) {
                this.controlledSmokeReadbackBarrierRecordedThisFrame = true;
            }

            if (copyDrawCommands) {
                VkBufferCopy.Buffer commandCopyRegion = VkBufferCopy.calloc(1, stack);
                commandCopyRegion.srcOffset(0L).dstOffset(0L).size(commandCopyBytes);
                VK10.vkCmdCopyBuffer(commandBuffer, readbackDrawCommandSourceBuffer.getId(), this.drawCommandDebugReadbackBuffer.getId(), commandCopyRegion);
                if (isControlledSmokeReadback) {
                    this.controlledSmokeReadbackCopyRecordedThisFrame = true;
                }
            }
            if (countCopyBytes >= Integer.BYTES) {
                VkBufferCopy.Buffer countCopyRegion = VkBufferCopy.calloc(1, stack);
                countCopyRegion.srcOffset(0L).dstOffset(0L).size(countCopyBytes);
                VK10.vkCmdCopyBuffer(commandBuffer, this.drawCountBuffer.getId(), this.drawCountDebugReadbackBuffer.getId(), countCopyRegion);
                if (isControlledSmokeReadback) {
                    this.controlledSmokeReadbackCopyRecordedThisFrame = true;
                }
            }
            if (copyGeometryQuad) {
                VkMemoryBarrier.Buffer geometryUploadToTransferRead = VkMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
                VK10.vkCmdPipelineBarrier(commandBuffer,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, geometryUploadToTransferRead, null, null);
                VkBufferCopy.Buffer geometryCopyRegion = VkBufferCopy.calloc(1, stack);
                geometryCopyRegion.srcOffset(geometryDiagnosticByteOffset).dstOffset(0L).size(Long.BYTES);
                VK10.vkCmdCopyBuffer(commandBuffer, geometryData.getGeometryBuffer().getId(), this.geometryQuadDebugReadbackBuffer.getId(), geometryCopyRegion);
                this.geometryDiagnosticReadbackScheduled = true;
                this.geometryDiagnosticReadbackCompleted = false;
                this.geometryDiagnosticPendingQuadIndex = geometryDiagnosticQuadIndex;
                this.geometryDiagnosticPendingByteOffset = geometryDiagnosticByteOffset;
                this.geometryDiagnosticPendingFrameId = -1L;
                this.geometryDiagnosticPendingRendererFrameSlot = safeRendererFrameSlot();
                this.geometryDiagnosticPendingCommandBufferAddress = commandBuffer.address();
                this.geometryDiagnosticPendingSourceBufferId = geometryData.getGeometryBuffer().getId();
                this.geometryDiagnosticPendingGeometrySyncGeneration = geometryData.getGeometrySyncGeneration();
                this.geometryDiagnosticPendingDrawPass = this.activeDrawPass;
                this.geometryDiagnosticReadbackCopyRecorded = true;
                this.geometryDiagnosticRejectReason = "pending";
                if (this.geometryDiagnosticStaleCleared) {
                    this.geometryDiagnosticScheduledAfterStaleClear = true;
                }
            }

            VkMemoryBarrier.Buffer transferToHost = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, transferToHost, null, null);
            if (isControlledSmokeReadback) {
                this.controlledSmokeReadbackBarrierRecordedThisFrame = true;
            }
        }
        this.pendingDebugSampleCommandCount = copyDrawCommands ? sampledCommandCount : 0;
        this.pendingDebugSampleVisibleCount = visibleCount;
        this.pendingDebugSampleGeometryBufferBytes = geometryData.getGeometryBuffer().getBufferSize();
        this.debugSamplePending = true;
        if (this.geometryDiagnosticReadbackScheduled && !geometryReadbackPending) {
            this.geometryDiagnosticPendingFrameId = -1L;
        }
        logDebugReadbackIsolationDiagnostics(debugReadbackCopyModeName(), true, true, this.debugSamplePending != debugSamplePendingBefore);
        return new ScheduledDebugReadback(copyDrawCommands, copyDrawCommands ? "scheduled" : "command_copy_disabled", copyDrawCommands ? commandCopyBytes : 0L, countCopyBytes, false, false, "none", copyDrawCommands ? safeRendererFrameSlot() : -1, copyDrawCommands ? commandBuffer.address() : 0L, copyDrawCommands ? this.controlledSmokeCommandGeneration : -1L, copyDrawCommands ? readbackDrawCommandSourceBuffer.getId() : 0L);
    }


    private static String bufferUsageString(int usageFlags) {
        List<String> usages = new ArrayList<>();
        if ((usageFlags & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0) usages.add("STORAGE");
        if ((usageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0) usages.add("INDIRECT");
        if ((usageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0) usages.add("TRANSFER_DST");
        if ((usageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0) usages.add("TRANSFER_SRC");
        return usages.isEmpty() ? "0" : String.join("|", usages);
    }


    private static String pipelineStageMaskString(int stageMask) {
        if (stageMask == 0) return "none";
        List<String> stages = new ArrayList<>();
        if ((stageMask & VK10.VK_PIPELINE_STAGE_TRANSFER_BIT) != 0) stages.add("TRANSFER");
        if ((stageMask & VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT) != 0) stages.add("COMPUTE_SHADER");
        if ((stageMask & VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT) != 0) stages.add("DRAW_INDIRECT");
        if ((stageMask & VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT) != 0) stages.add("VERTEX_SHADER");
        return stages.isEmpty() ? Integer.toString(stageMask) : String.join("|", stages);
    }


    private static String accessMaskString(int accessMask) {
        if (accessMask == 0) return "none";
        List<String> accesses = new ArrayList<>();
        if ((accessMask & VK10.VK_ACCESS_TRANSFER_WRITE_BIT) != 0) accesses.add("TRANSFER_WRITE");
        if ((accessMask & VK10.VK_ACCESS_SHADER_READ_BIT) != 0) accesses.add("SHADER_READ");
        if ((accessMask & VK10.VK_ACCESS_SHADER_WRITE_BIT) != 0) accesses.add("SHADER_WRITE");
        if ((accessMask & VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT) != 0) accesses.add("INDIRECT_COMMAND_READ");
        return accesses.isEmpty() ? Integer.toString(accessMask) : String.join("|", accesses);
    }


    private void recordCmdgenSampleSchedule(ScheduledDebugReadback scheduledDebugReadback, long frameId, CmdgenCommandSnapshot snapshot) {
        this.cmdgenSampleScheduled = true;
        this.cmdgenSampleScheduleReason = scheduledDebugReadback.reason();
        this.pendingDebugSampleFrameId = frameId;
        this.pendingDebugSampleRendererFrameSlot = scheduledDebugReadback.rendererFrameSlot();
        this.pendingDebugSampleRecordedCommandBufferAddress = scheduledDebugReadback.commandBufferAddress();
        this.pendingDebugSampleSourceBufferId = scheduledDebugReadback.sourceBufferId();
        this.pendingDebugSampleDrawPass = this.activeDrawPass;
        this.pendingDebugSampleSnapshot = snapshot;
        this.pendingDebugSampleCompletionStrategy = "unknown";
        this.pendingDebugSampleGpuCompletionKnown = false;
        if (this.geometryDiagnosticReadbackScheduled && !this.geometryDiagnosticReadbackCompleted && this.geometryDiagnosticPendingFrameId < 0L) {
            this.geometryDiagnosticPendingFrameId = frameId;
            this.geometryDiagnosticPendingRendererFrameSlot = scheduledDebugReadback.rendererFrameSlot();
            this.geometryDiagnosticPendingCommandBufferAddress = scheduledDebugReadback.commandBufferAddress();
            this.geometryDiagnosticPendingDrawPass = this.activeDrawPass;
        }
    }


    private void recordCompletedDebugSampleForPass(DrawPass drawPass, DrawCommandDebugSample sample) {
        this.lastObservedCompletedDebugSampleDrawPass = drawPass;
        this.lastObservedCompletedDebugSampleRejectReason = drawPass == this.activeDrawPass ? "none" : "draw_pass_mismatch";
        if (drawPass == DrawPass.TRANSLUCENT) {
            this.lastCompletedTranslucentDebugSample = sample;
            this.completedTranslucentDebugSampleSourceBufferId = this.pendingDebugSampleSourceBufferId;
            this.completedTranslucentDebugSampleFrameId = this.pendingDebugSampleFrameId;
            this.completedTranslucentDebugSampleSnapshot = this.pendingDebugSampleSnapshot;
        } else {
            this.lastCompletedOpaqueDebugSample = sample;
            this.completedOpaqueDebugSampleSourceBufferId = this.pendingDebugSampleSourceBufferId;
            this.completedOpaqueDebugSampleFrameId = this.pendingDebugSampleFrameId;
            this.completedOpaqueDebugSampleSnapshot = this.pendingDebugSampleSnapshot;
        }
        refreshActiveCompletedDebugSample();
    }


    private DrawPass preferredDiagnosticReadbackPass() {
        return (REAL_LOD_SINGLE_QUAD_WORLD_PROBE || REAL_QUAD_READ_CLIPSPACE_PROBE) ? DrawPass.OPAQUE : this.activeDrawPass;
    }


    private boolean diagnosticReadbackAllowedForActivePass() {
        return this.activeDrawPass == preferredDiagnosticReadbackPass();
    }


    private static String diagnosticReadbackPassName(DrawPass drawPass) {
        return drawPass == DrawPass.TRANSLUCENT ? "translucent" : "opaque";
    }


    private long pendingDebugSampleFrameIdForPass(DrawPass drawPass) {
        return this.debugSamplePending && this.pendingDebugSampleDrawPass == drawPass ? this.pendingDebugSampleFrameId : -1L;
    }


    private long completedDebugSampleFrameIdForPass(DrawPass drawPass) {
        return drawPass == DrawPass.TRANSLUCENT ? this.completedTranslucentDebugSampleFrameId : this.completedOpaqueDebugSampleFrameId;
    }


    private void refreshActiveCompletedDebugSample() {
        if (this.activeDrawPass == DrawPass.TRANSLUCENT) {
            this.lastCompletedDebugSample = this.lastCompletedTranslucentDebugSample;
            this.completedDebugSampleSourceBufferId = this.completedTranslucentDebugSampleSourceBufferId;
            this.completedDebugSampleFrameId = this.completedTranslucentDebugSampleFrameId;
            this.completedDebugSampleDrawPass = DrawPass.TRANSLUCENT;
            this.completedDebugSampleSnapshot = this.completedTranslucentDebugSampleSnapshot;
        } else {
            this.lastCompletedDebugSample = this.lastCompletedOpaqueDebugSample;
            this.completedDebugSampleSourceBufferId = this.completedOpaqueDebugSampleSourceBufferId;
            this.completedDebugSampleFrameId = this.completedOpaqueDebugSampleFrameId;
            this.completedDebugSampleDrawPass = DrawPass.OPAQUE;
            this.completedDebugSampleSnapshot = this.completedOpaqueDebugSampleSnapshot;
        }
    }


    private boolean sampleAcceptedForActivePass() {
        return this.lastCompletedDebugSample.sampledCommandCount() > 0
                && this.completedDebugSampleDrawPass == this.activeDrawPass
                && isCmdgenSampleValid();
    }


    private String sampleRejectedReasonForActivePass() {
        if (!diagnosticReadbackAllowedForActivePass()) {
            return "diagnostic_prefers_" + diagnosticReadbackPassName(preferredDiagnosticReadbackPass());
        }
        if (this.debugSamplePending && this.pendingDebugSampleDrawPass != this.activeDrawPass) {
            return "pending_" + diagnosticReadbackPassName(this.pendingDebugSampleDrawPass) + "_gpu_completion";
        }
        String gateReason = cmdgenSampleGateReason();
        return "ready".equals(gateReason) ? "none" : gateReason;
    }


    private long passDebugSampleQuadCount(DrawPass drawPass) {
        DrawCommandDebugSample sample = drawPass == DrawPass.TRANSLUCENT ? this.lastCompletedTranslucentDebugSample : this.lastCompletedOpaqueDebugSample;
        return sample.sampledCommandCount() > 0 ? sample.sampledQuadCount() : -1L;
    }


    private void consumePendingDebugCommandSampleIfReady(int currentFrameId) {
        if (!this.debugSamplePending) {
            markPendingGeometryReadback(currentFrameId, safeRendererFrameSlot());
            return;
        }
        if (!this.controlledSmokeCommandReadbackScheduled) {
            long ageFrames = this.pendingDebugSampleFrameId < 0L ? -1L : Math.max(0L, currentFrameId - this.pendingDebugSampleFrameId);
            int currentRendererFrameSlot = safeRendererFrameSlot();
            boolean rendererSlotComplete = this.pendingDebugSampleRendererFrameSlot >= 0
                    && currentRendererFrameSlot == this.pendingDebugSampleRendererFrameSlot
                    && ageFrames > 0;
            boolean runtimeNoCommandBufferComplete = Renderer.getInstance() != null && Renderer.getCommandBuffer() == null;
            boolean frameAgeFallbackComplete = this.pendingDebugSampleFrameId >= 0L && ageFrames >= 3L;
            boolean gpuCompletionKnown = rendererSlotComplete || runtimeNoCommandBufferComplete || frameAgeFallbackComplete;
            this.pendingDebugSampleCompletionStrategy = rendererSlotComplete ? "renderer_slot" : (runtimeNoCommandBufferComplete ? "copied_from_runtime" : (frameAgeFallbackComplete ? "frame_age_fallback" : "unknown"));
            this.pendingDebugSampleGpuCompletionKnown = gpuCompletionKnown;
            VulkanBerylDebugLog.stateLimited("cmdgen-sample-readback-completion", "cmdgen sample readback completion: cmdgenSampleFrameId=" + this.pendingDebugSampleFrameId
                    + ", currentFrameId=" + currentFrameId
                    + ", cmdgenSampleAgeFrames=" + ageFrames
                    + ", cmdgenSampleRendererFrameSlot=" + this.pendingDebugSampleRendererFrameSlot
                    + ", currentRendererFrameSlot=" + currentRendererFrameSlot
                    + ", cmdgenSampleRecordedCommandBufferAddress=0x" + Long.toHexString(this.pendingDebugSampleRecordedCommandBufferAddress)
                    + ", currentCommandBufferAddress=0x" + Long.toHexString(safeCurrentCommandBufferAddress())
                    + ", cmdgenSampleDrawPass=" + this.pendingDebugSampleDrawPass
                    + ", activeDrawPass=" + this.activeDrawPass
                    + ", pendingSampleDrawPass=" + this.pendingDebugSampleDrawPass
                    + ", completedSampleDrawPass=" + this.completedDebugSampleDrawPass
                    + ", opaquePendingSampleFrameId=" + pendingDebugSampleFrameIdForPass(DrawPass.OPAQUE)
                    + ", translucentPendingSampleFrameId=" + pendingDebugSampleFrameIdForPass(DrawPass.TRANSLUCENT)
                    + ", opaqueCompletedSampleFrameId=" + completedDebugSampleFrameIdForPass(DrawPass.OPAQUE)
                    + ", translucentCompletedSampleFrameId=" + completedDebugSampleFrameIdForPass(DrawPass.TRANSLUCENT)
                    + ", sampleAcceptedForActivePass=" + sampleAcceptedForActivePass()
                    + ", sampleRejectedReason=" + sampleRejectedReasonForActivePass()
                    + ", opaquePassQuadCount=" + passDebugSampleQuadCount(DrawPass.OPAQUE)
                    + ", translucentPassQuadCount=" + passDebugSampleQuadCount(DrawPass.TRANSLUCENT)
                    + ", cmdgenSampleCompletionStrategy=" + this.pendingDebugSampleCompletionStrategy
                    + ", cmdgenSampleGpuCompletionKnown=" + this.pendingDebugSampleGpuCompletionKnown, this.pendingDebugSampleFrameId + ":" + currentFrameId + ":" + currentRendererFrameSlot + ":" + this.pendingDebugSampleCompletionStrategy + ":" + this.pendingDebugSampleGpuCompletionKnown);
            if (!gpuCompletionKnown) {
                markPendingGeometryReadback(currentFrameId, currentRendererFrameSlot);
                return;
            }
            DrawCommandDebugSample completedSample = readDebugCommandSample(this.pendingDebugSampleCommandCount, this.pendingDebugSampleVisibleCount, this.pendingDebugSampleGeometryBufferBytes);
            markPendingGeometryReadback(currentFrameId, currentRendererFrameSlot);
            recordCompletedDebugSampleForPass(this.pendingDebugSampleDrawPass, completedSample);
            this.debugSamplePending = false;
            return;
        }
        int readbackFrameId = this.controlledSmokeCommandReadbackFrameId;
        int ageFrames = readbackFrameId < 0 ? -1 : Math.max(0, currentFrameId - readbackFrameId);
        int currentRendererFrameSlot = safeRendererFrameSlot();
        long currentCommandBufferAddress = safeCurrentCommandBufferAddress();
        boolean rendererSlotComplete = this.controlledSmokeCommandReadbackRendererFrameSlot >= 0
                && currentRendererFrameSlot == this.controlledSmokeCommandReadbackRendererFrameSlot
                && ageFrames > 0;
        boolean runtimeNoCommandBufferComplete = Renderer.getInstance() != null && Renderer.getCommandBuffer() == null;
        boolean frameAgeFallbackComplete = this.controlledSmokeCommandReadbackScheduled && readbackFrameId >= 0 && ageFrames >= 3;
        boolean gpuCompletionKnown = rendererSlotComplete || runtimeNoCommandBufferComplete || frameAgeFallbackComplete;
        if (rendererSlotComplete) {
            this.controlledSmokeCommandReadbackCompletionStrategy = "renderer_slot";
        } else if (runtimeNoCommandBufferComplete) {
            this.controlledSmokeCommandReadbackCompletionStrategy = "copied_from_runtime";
        } else if (frameAgeFallbackComplete) {
            this.controlledSmokeCommandReadbackCompletionStrategy = "frame_age_fallback";
        } else {
            this.controlledSmokeCommandReadbackCompletionStrategy = "unknown";
        }
        this.controlledSmokeCommandReadbackGpuCompletionKnown = gpuCompletionKnown;
        VulkanBerylDebugLog.stateLimited("controlled-smoke-command-readback-completion", "controlled smoke command readback completion: controlledSmokeCommandReadbackFrameId=" + readbackFrameId
                + ", currentFrameId=" + currentFrameId
                + ", controlledSmokeCommandReadbackAgeFrames=" + ageFrames
                + ", controlledSmokeCommandReadbackRendererFrameSlot=" + this.controlledSmokeCommandReadbackRendererFrameSlot
                + ", currentRendererFrameSlot=" + currentRendererFrameSlot
                + ", controlledSmokeCommandReadbackRecordedCommandBufferAddress=0x" + Long.toHexString(this.controlledSmokeCommandReadbackRecordedCommandBufferAddress)
                + ", currentCommandBufferAddress=0x" + Long.toHexString(currentCommandBufferAddress)
                + ", controlledSmokeCommandReadbackCompletionStrategy=" + this.controlledSmokeCommandReadbackCompletionStrategy
                + ", controlledSmokeCommandReadbackGpuCompletionKnown=" + this.controlledSmokeCommandReadbackGpuCompletionKnown, readbackFrameId + ":" + currentFrameId + ":" + currentRendererFrameSlot + ":" + this.controlledSmokeCommandReadbackCompletionStrategy + ":" + this.controlledSmokeCommandReadbackGpuCompletionKnown);
        if (!gpuCompletionKnown) {
            markPendingGeometryReadback(currentFrameId, currentRendererFrameSlot);
            return;
        }
        DrawCommandDebugSample completedSample = readDebugCommandSample(this.pendingDebugSampleCommandCount, this.pendingDebugSampleVisibleCount, this.pendingDebugSampleGeometryBufferBytes);
        markPendingGeometryReadback(currentFrameId, currentRendererFrameSlot);
        recordCompletedDebugSampleForPass(this.pendingDebugSampleDrawPass, completedSample);
        this.debugSamplePending = false;
        this.controlledSmokeCommandReadbackCompleted = completedSample.sampledCommandCount() > 0;
        this.controlledSmokeCommandReadbackCompletedFrameId = this.controlledSmokeCommandReadbackFrameId;
        this.controlledSmokeCommandReadbackCompletedGeneration = this.controlledSmokeCommandReadbackScheduledGeneration;
        this.controlledSmokeCommandReadbackCompletedSectionId = this.controlledSmokeCommandReadbackScheduledSectionId;
        this.controlledSmokeCommandReadbackCompletedExpectedVertexCount = this.controlledSmokeCommandReadbackScheduledExpectedVertexCount;
        this.controlledSmokeCommandReadbackCompletedExpectedInstanceCount = this.controlledSmokeCommandReadbackScheduledExpectedInstanceCount;
        this.controlledSmokeCommandReadbackCompletedExpectedFirstVertex = this.controlledSmokeCommandReadbackScheduledExpectedFirstVertex;
        this.controlledSmokeCommandReadbackCompletedExpectedFirstInstance = this.controlledSmokeCommandReadbackScheduledExpectedFirstInstance;
        this.controlledSmokeCommandReadbackCompletedBufferId = this.controlledSmokeCommandReadbackScheduledBufferId;
        this.controlledSmokeKnownCommandReadbackAfterUploadGeneration = this.controlledSmokeCommandUploadGeneration;
        boolean matchesCurrentGeneration = controlledSmokeCommandReadbackMatchesCurrentGeneration();
        boolean matchesCurrentExpected = controlledSmokeCommandReadbackMatchesCurrentExpected();
        String staleReason = controlledSmokeCommandReadbackStaleReason();
        VulkanBerylDebugLog.stateLimited("controlled-smoke-command-readback-observed", "controlled smoke command readback observed: controlledSmokeCommandGeneration=" + this.controlledSmokeCommandGeneration
                + ", controlledSmokeCommandReadbackGeneration=" + this.controlledSmokeCommandReadbackCompletedGeneration
                + ", controlledSmokeCommandReadbackMatchesCurrentGeneration=" + matchesCurrentGeneration
                + ", controlledSmokeCommandReadbackMatchesCurrentExpected=" + matchesCurrentExpected
                + ", controlledSmokeCommandReadbackStale=" + (!matchesCurrentExpected)
                + ", controlledSmokeCommandReadbackStaleReason=" + staleReason
                + ", controlledSmokeCommandReadbackObservedVertexCount=" + (completedSample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(completedSample.firstVertexCount()) : -1L)
                + ", controlledSmokeCommandReadbackObservedInstanceCount=" + (completedSample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(completedSample.firstInstanceCount()) : -1L)
                + ", controlledSmokeCommandReadbackObservedFirstVertex=" + (completedSample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(completedSample.firstFirstVertex()) : -1L)
                + ", controlledSmokeCommandReadbackObservedFirstInstance=" + (completedSample.sampledCommandCount() > 0 ? Integer.toUnsignedLong(completedSample.firstFirstInstance()) : -1L)
                + ", controlledSmokeCommandUploadGeneration=" + this.controlledSmokeCommandUploadGeneration
                + ", controlledSmokeCommandUploadExpectedVertexCount=" + this.controlledSmokeCommandUploadExpectedVertexCount
                + ", controlledSmokeCommandUploadExpectedFirstVertex=" + this.controlledSmokeCommandUploadExpectedFirstVertex
                + ", controlledSmokeKnownCommandUploadQueued=" + this.controlledSmokeKnownCommandUploadQueued
                + ", controlledSmokeKnownCommandUploadFlushed=" + this.controlledSmokeKnownCommandUploadFlushed
                + ", controlledSmokeKnownCommandUploadCompletionKnown=" + controlledSmokeKnownCommandUploadCompletionKnownString(currentFrameId)
                + ", controlledSmokeKnownCommandUploadCompletionStrategy=" + this.controlledSmokeKnownCommandUploadCompletionStrategy
                + ", controlledSmokeKnownCommandReadbackAfterUploadGeneration=" + this.controlledSmokeKnownCommandReadbackAfterUploadGeneration, completedSample.toString() + ":" + this.controlledSmokeCommandReadbackCompletedGeneration + ":" + this.controlledSmokeCommandGeneration);
    }



    private static int safeRendererFrameSlot() {
        try {
            return Renderer.getCurrentFrame();
        } catch (RuntimeException | Error ignored) {
            return -1;
        }
    }


    private static long safeCurrentCommandBufferAddress() {
        try {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            return commandBuffer == null ? 0L : commandBuffer.address();
        } catch (RuntimeException | Error ignored) {
            return 0L;
        }
    }


    private void markPendingGeometryReadback(int currentFrameId, int currentRendererFrameSlot) {
        if (!this.geometryDiagnosticReadbackScheduled || this.geometryDiagnosticReadbackCompleted) return;
        if (!this.geometryDiagnosticReadbackCopyRecorded) {
            this.geometryDiagnosticRejectReason = "readback_staging_unavailable";
            return;
        }
        long ageFrames = this.geometryDiagnosticPendingFrameId < 0L || currentFrameId < 0 ? -1L : Math.max(0L, currentFrameId - this.geometryDiagnosticPendingFrameId);
        if (ageFrames == 0L) {
            this.geometryDiagnosticRejectReason = "too_young_same_frame";
            return;
        }
        if (this.geometryDiagnosticPendingRendererFrameSlot >= 0
                && currentRendererFrameSlot >= 0
                && currentRendererFrameSlot == this.geometryDiagnosticPendingRendererFrameSlot
                && ageFrames <= 0L) {
            this.geometryDiagnosticRejectReason = "renderer_slot_not_advanced";
            return;
        }
        this.geometryDiagnosticRejectReason = "pending_gpu_completion";
    }


    private void consumePendingGeometryQuadReadbackIfReady(long currentFrameId, VulkanBerylSectionGeometryData geometryData, long selectedQuadIndex, long selectedByteOffset) {
        if (!this.geometryDiagnosticReadbackScheduled || this.geometryDiagnosticReadbackCompleted) {
            if (!this.geometryDiagnosticReadbackScheduled && !this.geometryDiagnosticReadbackCompleted) {
                this.geometryDiagnosticRejectReason = "readback_not_scheduled";
            }
            return;
        }
        if (!this.geometryDiagnosticReadbackCopyRecorded) {
            this.geometryDiagnosticRejectReason = "readback_staging_unavailable";
            return;
        }
        long currentSourceBufferId = geometryData == null || geometryData.getGeometryBuffer() == null ? 0L : geometryData.getGeometryBuffer().getId();
        long currentGeometrySyncGeneration = geometryData == null ? 0L : geometryData.getGeometrySyncGeneration();
        if (this.geometryDiagnosticPendingGeometrySyncGeneration != 0L
                && currentGeometrySyncGeneration != 0L
                && this.geometryDiagnosticPendingGeometrySyncGeneration != currentGeometrySyncGeneration) {
            this.geometryDiagnosticRejectReason = "geometry_sync_generation_changed";
            return;
        }
        if (this.geometryDiagnosticPendingSourceBufferId != 0L
                && currentSourceBufferId != 0L
                && this.geometryDiagnosticPendingSourceBufferId != currentSourceBufferId) {
            this.geometryDiagnosticRejectReason = "source_buffer_changed";
            return;
        }
        if (this.geometryDiagnosticPendingDrawPass != preferredDiagnosticReadbackPass()) {
            this.geometryDiagnosticRejectReason = "command_buffer_slot_mismatch";
            return;
        }
        if (this.geometryDiagnosticPendingQuadIndex != selectedQuadIndex
                || this.geometryDiagnosticPendingByteOffset != selectedByteOffset) {
            this.geometryDiagnosticRejectReason = "selected_quad_changed";
            return;
        }
        long ageFrames = this.geometryDiagnosticPendingFrameId < 0L || currentFrameId < 0L ? -1L : Math.max(0L, currentFrameId - this.geometryDiagnosticPendingFrameId);
        int currentRendererFrameSlot = safeRendererFrameSlot();
        boolean runtimeNoCommandBufferComplete = Renderer.getInstance() != null && Renderer.getCommandBuffer() == null;
        boolean frameAgeFallbackComplete = this.geometryDiagnosticPendingFrameId >= 0L && ageFrames >= 3L;
        boolean atLeastOneFrameOld = ageFrames > 0L;
        if (ageFrames < 0L) {
            this.geometryDiagnosticRejectReason = "pending_gpu_completion";
            return;
        }
        if (ageFrames == 0L) {
            this.geometryDiagnosticRejectReason = "too_young_same_frame";
            return;
        }
        if (this.geometryDiagnosticPendingRendererFrameSlot >= 0
                && currentRendererFrameSlot >= 0
                && currentRendererFrameSlot == this.geometryDiagnosticPendingRendererFrameSlot
                && !runtimeNoCommandBufferComplete
                && !frameAgeFallbackComplete
                && !atLeastOneFrameOld) {
            this.geometryDiagnosticRejectReason = "renderer_slot_not_advanced";
            return;
        }
        long ptr = this.geometryQuadDebugReadbackBuffer == null ? 0L : this.geometryQuadDebugReadbackBuffer.getDataPtr();
        if (ptr == 0L) {
            this.geometryDiagnosticRejectReason = "readback_staging_unavailable";
            return;
        }
        this.geometryDiagnosticCompletedRaw = MemoryUtil.memGetLong(ptr);
        this.geometryDiagnosticCompletedQuadIndex = this.geometryDiagnosticPendingQuadIndex;
        this.geometryDiagnosticCompletedByteOffset = this.geometryDiagnosticPendingByteOffset;
        this.geometryDiagnosticCompletedFrameId = this.geometryDiagnosticPendingFrameId;
        this.geometryDiagnosticCompletedRendererFrameSlot = this.geometryDiagnosticPendingRendererFrameSlot;
        this.geometryDiagnosticCompletedCommandBufferAddress = this.geometryDiagnosticPendingCommandBufferAddress;
        this.geometryDiagnosticCompletedSourceBufferId = this.geometryDiagnosticPendingSourceBufferId;
        this.geometryDiagnosticCompletedGeometrySyncGeneration = this.geometryDiagnosticPendingGeometrySyncGeneration;
        this.geometryDiagnosticCompletedDrawPass = this.geometryDiagnosticPendingDrawPass;
        this.geometryDiagnosticReadbackCompleted = true;
        this.geometryDiagnosticRejectReason = "none";
        VulkanBerylDebugLog.stateLimited("geometry-quad-debug-readback-observed", "geometry quad debug readback observed: geometryDiagnosticQuadIndex=" + this.geometryDiagnosticCompletedQuadIndex
                + ", geometryDiagnosticByteOffset=" + this.geometryDiagnosticCompletedByteOffset
                + ", geometryDiagnosticCompletedFrameId=" + this.geometryDiagnosticCompletedFrameId
                + ", geometryDiagnosticCompletedRendererFrameSlot=" + this.geometryDiagnosticCompletedRendererFrameSlot
                + ", geometryDiagnosticCompletedCommandBufferAddress=0x" + Long.toHexString(this.geometryDiagnosticCompletedCommandBufferAddress)
                + ", geometryDiagnosticCompletedDrawPass=" + this.geometryDiagnosticCompletedDrawPass
                + ", geometryDiagnosticSourceBufferId=" + this.geometryDiagnosticCompletedSourceBufferId
                + ", geometryDiagnosticGeometrySyncGeneration=" + this.geometryDiagnosticCompletedGeometrySyncGeneration
                + ", worldDrawQuad0Raw=" + Long.toUnsignedString(this.geometryDiagnosticCompletedRaw)
                + ", worldDrawQuad0Empty=" + (this.geometryDiagnosticCompletedRaw == 0L),
                this.geometryDiagnosticCompletedFrameId + ":" + this.geometryDiagnosticCompletedQuadIndex + ":" + this.geometryDiagnosticCompletedRaw);
    }


    private DrawCommandDebugSample readDebugCommandSample(int sampledCommandCount, int visibleCount, long geometryBufferBytes) {
        long readbackPtr = this.drawCommandDebugReadbackBuffer == null ? 0L : this.drawCommandDebugReadbackBuffer.getDataPtr();
        long drawCountReadbackPtr = this.drawCountDebugReadbackBuffer == null ? 0L : this.drawCountDebugReadbackBuffer.getDataPtr();
        if (sampledCommandCount <= 0 || readbackPtr == 0L) return DrawCommandDebugSample.empty();
        int invalid = 0;
        long quadCount = 0L;
        int firstVertexCount = 0;
        int firstInstanceCount = 0;
        int firstFirstVertex = 0;
        int firstFirstInstance = 0;
        int sampledDrawCount = drawCountReadbackPtr == 0L ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr);
        int safeVisibleCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 2L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 4L);
        int validCommandCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 3L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 8L);
        int zeroCommandCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 4L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 12L);
        int invalidMetadataCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 5L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 16L);
        int zeroOpaqueCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 6L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 20L);
        int passQuadCountZeroCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 7L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 24L);
        int passQuadRangeOutOfBoundsCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 8L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 28L);
        int invalidMetadataDetailedCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 9L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 32L);
        int commandCapacityExceededCount = drawCountReadbackPtr == 0L || this.drawCountDebugReadbackBuffer.getBufferSize() < 10L * Integer.BYTES ? -1 : MemoryUtil.memGetInt(drawCountReadbackPtr + 36L);
        String rejectReason = "none";
        long sampledDrawCountUnsigned = sampledDrawCount < 0 ? -1L : Integer.toUnsignedLong(sampledDrawCount);
        if (drawCountReadbackPtr == 0L) {
            rejectReason = "draw_count_readback_unavailable";
        } else if (sampledDrawCountUnsigned == 0L) {
            rejectReason = "draw_count_zero";
        } else if (sampledDrawCountUnsigned > visibleCount || sampledDrawCountUnsigned > this.drawCommandCapacity) {
            rejectReason = "draw_count_out_of_range";
        } else if (validCommandCount >= 0 && validCommandCount != sampledDrawCount) {
            rejectReason = "valid_command_count_mismatch";
        }
        for (int i = 0; i < sampledCommandCount; i++) {
            long base = readbackPtr + (long) i * DRAW_COMMAND_STRIDE_BYTES;
            int vertexCount = MemoryUtil.memGetInt(base);
            int instanceCount = MemoryUtil.memGetInt(base + 4L);
            int firstVertex = MemoryUtil.memGetInt(base + 8L);
            int firstInstance = MemoryUtil.memGetInt(base + 12L);
            if (i == 0) {
                firstVertexCount = vertexCount;
                firstInstanceCount = instanceCount;
                firstFirstVertex = firstVertex;
                firstFirstInstance = firstInstance;
            }
            boolean zeroNoop = vertexCount == 0 && instanceCount == 0 && firstVertex == 0 && firstInstance == 0;
            long firstInstanceUnsigned = Integer.toUnsignedLong(firstInstance);
            boolean valid = zeroNoop || (vertexCount > 0 && vertexCount % 6 == 0 && instanceCount == 1 && firstVertex == 0 && firstInstanceUnsigned < Integer.toUnsignedLong(visibleCount));
            if (!zeroNoop && valid && geometryBufferBytes > 0L) {
                long maxVertexExclusive = (geometryBufferBytes >>> 3) * 6L;
                valid = Integer.toUnsignedLong(vertexCount) <= maxVertexExclusive;
            }
            if (!valid) {
                invalid++;
                if ("none".equals(rejectReason)) {
                    rejectReason = "command" + i + "_invalid:vertexCount=" + Integer.toUnsignedLong(vertexCount)
                            + ":instanceCount=" + Integer.toUnsignedLong(instanceCount)
                            + ":firstVertex=" + Integer.toUnsignedLong(firstVertex)
                            + ":firstInstance=" + Integer.toUnsignedLong(firstInstance);
                }
            }
            if (vertexCount > 0 && vertexCount % 6 == 0) quadCount += Integer.toUnsignedLong(vertexCount) / 6L;
        }
        return new DrawCommandDebugSample(sampledCommandCount, invalid, quadCount, firstVertexCount, firstInstanceCount, firstFirstVertex, firstFirstInstance, sampledDrawCount, safeVisibleCount, validCommandCount, zeroCommandCount, invalidMetadataCount, zeroOpaqueCount, passQuadCountZeroCount, passQuadRangeOutOfBoundsCount, invalidMetadataDetailedCount, commandCapacityExceededCount, rejectReason);
    }


    private CmdgenCommandSnapshot cmdgenCommandSnapshot(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, ControlledRenderListSmoke controlledSmoke) {
        RenderListEntry0Diagnostics entry0 = renderListEntry0Diagnostics(geometryData, controlledSmoke);
        Buffer commandBuffer = controlledSmokeDrawCommandBuffer(controlledSmoke);
        long commandBufferId = commandBuffer == null ? 0L : commandBuffer.getId();
        long commandGeneration = commandBuffer == this.controlledSmokeKnownCommandBuffer ? this.controlledSmokeCommandGeneration : this.drawCommandBufferAllocationGeneration;
        long renderListBufferId = renderList == null || renderList.getBuffer() == null ? 0L : renderList.getBuffer().getId();
        long metadataBufferId = geometryData == null || geometryData.getMetadataBuffer() == null ? 0L : geometryData.getMetadataBuffer().getId();
        if (!entry0.valid()) {
            return CmdgenCommandSnapshot.unavailable(commandBufferId, commandGeneration, renderListBufferId, metadataBufferId);
        }
        return new CmdgenCommandSnapshot(entry0.sectionId(), entry0.expectedFirstVertex(), entry0.expectedVertexCount(), 1L, 0L, commandBufferId, commandGeneration, renderListBufferId, metadataBufferId, true);
    }


    private boolean isCmdgenSampleValid() {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        return sample.sampledCommandCount() > 0
                && sample.invalidSampledCommandCount() == 0
                && sample.sampledDrawCount() > 0
                && "none".equals(sample.rejectReason())
                && this.completedDebugSampleDrawPass == this.activeDrawPass
                && completedSampleMatchesScheduledSnapshot();
    }


    private boolean completedSampleMatchesScheduledSnapshot() {
        return sampleMatchesSnapshot(this.lastCompletedDebugSample, this.completedDebugSampleSnapshot)
                && this.completedDebugSampleSourceBufferId != 0L
                && this.completedDebugSampleSourceBufferId == this.completedDebugSampleSnapshot.drawCommandBufferId();
    }


    private boolean sampleMatchesSnapshot(DrawCommandDebugSample sample, CmdgenCommandSnapshot snapshot) {
        if (sample.sampledCommandCount() <= 0 || !snapshot.available()) return false;
        long sampledFirstInstance = Integer.toUnsignedLong(sample.firstFirstInstance());
        if (sampledFirstInstance != snapshot.expectedFirstInstance()) {
            long safeVisibleCount = sample.safeVisibleCount() < 0 ? Long.MAX_VALUE : Integer.toUnsignedLong(sample.safeVisibleCount());
            return sampledFirstInstance < safeVisibleCount;
        }
        return Integer.toUnsignedLong(sample.firstVertexCount()) == snapshot.expectedVertexCount()
                && Integer.toUnsignedLong(sample.firstInstanceCount()) == snapshot.expectedInstanceCount()
                && Integer.toUnsignedLong(sample.firstFirstVertex()) == 0L
                && sampledFirstInstance == snapshot.expectedFirstInstance();
    }


    private boolean completedCmdgenSampleSnapshotMatches(CmdgenCommandSnapshot currentSnapshot) {
        return isCmdgenSampleValid() && this.completedDebugSampleSnapshot.sameSnapshot(currentSnapshot);
    }


    private boolean pendingCmdgenSampleSnapshotMatches(CmdgenCommandSnapshot currentSnapshot) {
        return this.debugSamplePending
                && this.pendingDebugSampleDrawPass == this.activeDrawPass
                && this.pendingDebugSampleSnapshot.sameSnapshot(currentSnapshot);
    }


    private static long currentCmdgenDiagnosticFrameId(VulkanBerylViewport viewport, VulkanBerylViewportRenderList renderList) {
        long renderListFrameId = renderList == null ? -1L : renderList.getLastVisibleFrameId();
        return renderListFrameId >= 0L ? renderListFrameId : Integer.toUnsignedLong(viewport.frameId & 0x7fffffff);
    }


    private boolean cmdgenSampleFrameMatches(long diagnosticFrameId) {
        return this.lastCompletedDebugSample.sampledCommandCount() > 0
                && this.completedDebugSampleFrameId >= 0L
                && diagnosticFrameId >= 0L
                && this.completedDebugSampleFrameId == diagnosticFrameId;
    }


    private boolean cmdgenSampleMatchesActiveDrawCommandBuffer(long activeDrawCommandBufferId) {
        return this.lastCompletedDebugSample.sampledCommandCount() > 0
                && this.completedDebugSampleSourceBufferId != 0L
                && activeDrawCommandBufferId != 0L
                && this.completedDebugSampleSourceBufferId == activeDrawCommandBufferId;
    }


    private boolean cmdgenSampleFromWrongBuffer() {
        if (this.completedDebugSampleSourceBufferId == 0L || this.lastCompletedDebugSample.sampledCommandCount() <= 0) return false;
        long drawBufferId = this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId();
        long controlledBufferId = this.controlledSmokeKnownCommandBuffer == null ? 0L : this.controlledSmokeKnownCommandBuffer.getId();
        return drawBufferId != 0L
                && this.completedDebugSampleSourceBufferId != drawBufferId
                && (controlledBufferId == 0L || this.completedDebugSampleSourceBufferId != controlledBufferId);
    }


    private String cmdgenSampleDiagnosticReason() {
        if (isCmdgenSampleValid()) return "sample_matches_scheduled_command";
        if (this.lastCompletedDebugSample.sampledCommandCount() > 0 && this.completedDebugSampleDrawPass != this.activeDrawPass) return "sample_rejected:draw_pass_mismatch";
        if (this.debugSamplePending) return "sample_pending";
        if ("buffer_reallocated".equals(this.cmdgenSampleScheduleReason)) return "sample_stale_snapshot_mismatch";
        if (!this.cmdgenSampleScheduled) return "sample_not_scheduled";
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (sample.sampledCommandCount() <= 0) return "sample_completion_not_propagated";
        String rejectReason = cmdgenSampleGateReason();
        return "sample_rejected:" + (rejectReason == null || rejectReason.isBlank() ? "invalid_sample" : rejectReason);
    }


    private String cmdgenSampleDiagnosticReason(CmdgenCommandSnapshot currentSnapshot, long activeDrawCommandBufferId) {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (sample.sampledCommandCount() > 0 && this.completedDebugSampleDrawPass != this.activeDrawPass) {
            return this.debugSamplePending && pendingCmdgenSampleSnapshotMatches(currentSnapshot) ? "sample_pending_for_current_snapshot" : "sample_rejected:draw_pass_mismatch";
        }
        if (sample.sampledCommandCount() > 0 && this.completedDebugSampleSourceBufferId != 0L && activeDrawCommandBufferId != 0L && this.completedDebugSampleSourceBufferId != activeDrawCommandBufferId) {
            return "sample_buffer_mismatch";
        }
        if (completedCmdgenSampleSnapshotMatches(currentSnapshot)) return "sample_matches_current_renderlist_entry0";
        if (this.debugSamplePending && pendingCmdgenSampleSnapshotMatches(currentSnapshot)) return "sample_pending_for_current_snapshot";
        if (isCmdgenSampleValid()) {
            if (!currentSnapshot.available()) return "sample_matches_scheduled_command";
            if (this.completedDebugSampleSnapshot.drawCommandBufferId() != currentSnapshot.drawCommandBufferId()) return "sample_buffer_mismatch";
            if (this.completedDebugSampleSnapshot.commandBufferGeneration() != currentSnapshot.commandBufferGeneration()) return "sample_generation_mismatch";
            if (this.completedDebugSampleSnapshot.renderListBufferId() != currentSnapshot.renderListBufferId()
                    || this.completedDebugSampleSnapshot.metadataBufferId() != currentSnapshot.metadataBufferId()) return "sample_stale_snapshot_mismatch";
            if (!this.completedDebugSampleSnapshot.sameCommandTuple(currentSnapshot)) return "sample_stale_snapshot_mismatch";
            return "sample_matches_scheduled_command";
        }
        return cmdgenSampleDiagnosticReason();
    }


    private String cmdgenSampleGateReason() {
        DrawCommandDebugSample sample = this.lastCompletedDebugSample;
        if (isCmdgenSampleValid()) return "ready";
        if (sample.sampledCommandCount() > 0 && this.completedDebugSampleDrawPass != this.activeDrawPass) return "draw_pass_mismatch";
        if (this.debugSamplePending) return "pending_gpu_completion";
        if (!this.cmdgenSampleScheduled) return "not_scheduled";
        if (sample.sampledCommandCount() <= 0) return "sample_not_completed";
        if (!"none".equals(sample.rejectReason())) return sample.rejectReason();
        if (sample.invalidSampledCommandCount() > 0) return "invalid_sampled_command";
        if (sample.sampledDrawCount() <= 0) return "draw_count_zero";
        if (this.completedDebugSampleSourceBufferId != this.completedDebugSampleSnapshot.drawCommandBufferId()) return "sample_buffer_mismatch";
        if (!sampleMatchesSnapshot(sample, this.completedDebugSampleSnapshot)) return "sample_tuple_mismatch";
        return "invalid_sample";
    }


    private record DrawCommandDebugSample(int sampledCommandCount, int invalidSampledCommandCount, long sampledQuadCount, int firstVertexCount, int firstInstanceCount, int firstFirstVertex, int firstFirstInstance, int sampledDrawCount, int safeVisibleCount, int validCommandCount, int zeroCommandCount, int invalidMetadataCount, int zeroOpaqueCount, int passQuadCountZeroCount, int passQuadRangeOutOfBoundsCount, int invalidMetadataDetailedCount, int commandCapacityExceededCount, String rejectReason) {
        static DrawCommandDebugSample empty() { return new DrawCommandDebugSample(0, 0, -1L, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, "not_sampled"); }
    }


    private record CmdgenCommandSnapshot(int scheduledRenderListEntry0SectionId, long expectedFirstVertex, long expectedVertexCount, long expectedInstanceCount, long expectedFirstInstance, long drawCommandBufferId, long commandBufferGeneration, long renderListBufferId, long metadataBufferId, boolean available) {
        static CmdgenCommandSnapshot unavailable() {
            return unavailable(0L, -1L, 0L, 0L);
        }

        static CmdgenCommandSnapshot unavailable(long drawCommandBufferId, long commandBufferGeneration, long renderListBufferId, long metadataBufferId) {
            return new CmdgenCommandSnapshot(-1, -1L, -1L, -1L, -1L, drawCommandBufferId, commandBufferGeneration, renderListBufferId, metadataBufferId, false);
        }

        boolean sameSnapshot(CmdgenCommandSnapshot other) {
            if (other == null || !this.available || !other.available) return false;
            return this.scheduledRenderListEntry0SectionId == other.scheduledRenderListEntry0SectionId
                    && this.expectedFirstVertex == other.expectedFirstVertex
                    && this.expectedVertexCount == other.expectedVertexCount
                    && this.expectedInstanceCount == other.expectedInstanceCount
                    && this.expectedFirstInstance == other.expectedFirstInstance
                    && this.drawCommandBufferId == other.drawCommandBufferId
                    && this.commandBufferGeneration == other.commandBufferGeneration
                    && this.renderListBufferId == other.renderListBufferId
                    && this.metadataBufferId == other.metadataBufferId;
        }

        boolean sameCommandTuple(CmdgenCommandSnapshot other) {
            if (other == null || !this.available || !other.available) return false;
            return this.scheduledRenderListEntry0SectionId == other.scheduledRenderListEntry0SectionId
                    && this.expectedFirstVertex == other.expectedFirstVertex
                    && this.expectedVertexCount == other.expectedVertexCount
                    && this.expectedInstanceCount == other.expectedInstanceCount
                    && this.expectedFirstInstance == other.expectedFirstInstance;
        }
    }



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


    private void ensureCommandGenFullLayoutNoopProbePipeline() {
        if (this.commandGenFullLayoutNoopProbePipeline != null) return;
        this.commandGenFullLayoutNoopProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_NOOP_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_NOOP_SHADER_NAME, "CmdGenFullLayoutNoopProbe");
    }


    private void ensureCommandGenFullLayoutHardcodedBinding0ReadProbePipeline() {
        if (this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline != null) return;
        this.commandGenFullLayoutHardcodedBinding0ReadProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_SHADER_NAME, "CmdGenFullLayoutHardcodedBinding0ReadProbe");
    }


    private void ensureCommandGenFullLayoutConfigBinding0ReadProbePipeline() {
        if (this.commandGenFullLayoutConfigBinding0ReadProbePipeline != null) return;
        this.commandGenFullLayoutConfigBinding0ReadProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_SHADER_NAME, "CmdGenFullLayoutConfigBinding0ReadProbe");
    }


    private void ensureCommandGenNoImportProbePipeline() {
        if (this.commandGenNoImportProbePipeline != null) return;
        this.commandGenNoImportProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_SHADER_RESOURCE, CMDGEN_NO_IMPORT_SHADER_NAME, "CmdGenNoImportProbe");
    }


    private void ensureCommandGenNoImportReadMetadata0OnlyProbePipeline() {
        if (this.commandGenNoImportReadMetadata0OnlyProbePipeline != null) return;
        this.commandGenNoImportReadMetadata0OnlyProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_SHADER_RESOURCE, CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_SHADER_NAME, "CmdGenNoImportReadMetadata0OnlyProbe");
    }


    private void ensureCommandGenNoImportRawMetadataUvec4Binding1ProbePipeline() {
        if (this.commandGenNoImportRawMetadataUvec4Binding1ProbePipeline != null) return;
        this.commandGenNoImportRawMetadataUvec4Binding1ProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_SHADER_RESOURCE, CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_SHADER_NAME, "CmdGenNoImportRawMetadataUvec4Binding1Probe");
    }


    private void ensureCommandGenFullLayoutBinding1UintReadProbePipeline() {
        if (this.commandGenFullLayoutBinding1UintReadProbePipeline != null) return;
        this.commandGenFullLayoutBinding1UintReadProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_SHADER_NAME, "CmdGenFullLayoutBinding1UintReadProbe");
    }


    private void ensureCommandGenFullLayoutBinding1UintReadConstProbePipeline() {
        if (this.commandGenFullLayoutBinding1UintReadConstProbePipeline != null) return;
        this.commandGenFullLayoutBinding1UintReadConstProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_CONST_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_CONST_SHADER_NAME, "CmdGenFullLayoutBinding1UintReadConstProbe");
    }


    private void ensureCommandGenFullLayoutBinding1TinyUintReadNoConfigProbePipeline() {
        if (this.commandGenFullLayoutBinding1TinyUintReadNoConfigProbePipeline != null) return;
        this.commandGenFullLayoutBinding1TinyUintReadNoConfigProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_SHADER_NAME, "CmdGenFullLayoutBinding1TinyUintReadNoConfigProbe");
    }


    private void ensureCommandGenFullLayoutBinding1AndBinding2UintReadProbePipeline() {
        if (this.commandGenFullLayoutBinding1AndBinding2UintReadProbePipeline != null) return;
        this.commandGenFullLayoutBinding1AndBinding2UintReadProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_SHADER_NAME, "CmdGenFullLayoutBinding1AndBinding2UintReadProbe");
    }


    private void ensureCommandGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbePipeline() {
        if (this.commandGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbePipeline != null) return;
        this.commandGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_NAME, "CmdGenFullLayoutBinding2ProbeBufferUintReadNoConfigProbe");
    }


    private void ensureCommandGenFullLayoutBinding2ProbeBufferUintReadConstProbePipeline() {
        if (this.commandGenFullLayoutBinding2ProbeBufferUintReadConstProbePipeline != null) return;
        this.commandGenFullLayoutBinding2ProbeBufferUintReadConstProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_NAME, "CmdGenFullLayoutBinding2ProbeBufferUintReadConstProbe");
    }


    private void ensureCommandGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbePipeline() {
        if (this.commandGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbePipeline != null) return;
        this.commandGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_SHADER_NAME, "CmdGenFullLayoutBinding1TinyAndBinding2TinyUintReadNoConfigProbe");
    }


    private void ensureCommandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbePipeline() {
        if (this.commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbePipeline != null) return;
        this.commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_NAME, "CmdGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadNoConfigProbe");
    }


    private void ensureCommandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbePipeline() {
        if (this.commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbePipeline != null) return;
        this.commandGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_NAME, "CmdGenFullLayoutBinding1TinyAndBinding2ProbeBufferUintReadConstProbe");
    }


    private void ensureCommandGenSingleBinding1UintReadProbePipeline() {
        if (this.commandGenSingleBinding1UintReadProbePipeline != null) return;
        ensureCmdgenTinyMetadataProbeBuffer();
        this.commandGenSingleBinding1UintReadProbePipeline = createDenseSsboReadProbePipeline(CMDGEN_SINGLE_BINDING1_UINT_READ_SHADER_RESOURCE, CMDGEN_SINGLE_BINDING1_UINT_READ_SHADER_NAME, CMDGEN_METADATA_BINDING, this.cmdGenTinyMetadataProbeBuffer, "CmdGenSingleBinding1UintReadProbe");
    }


    private void ensureCommandGenBinding0UintReadProbePipeline() {
        if (this.commandGenBinding0UintReadProbePipeline != null) return;
        ensureCmdgenTinyMetadataProbeBuffer();
        this.commandGenBinding0UintReadProbePipeline = createSingleSsboReadProbePipeline(CMDGEN_BINDING0_UINT_READ_SHADER_RESOURCE, CMDGEN_BINDING0_UINT_READ_SHADER_NAME, CMDGEN_RENDER_LIST_BINDING, this.cmdGenTinyMetadataProbeBuffer, "CmdGenBinding0UintReadProbe");
    }


    private void ensureCommandGenFullLayoutBinding2UintReadProbePipeline() {
        if (this.commandGenFullLayoutBinding2UintReadProbePipeline != null) return;
        this.commandGenFullLayoutBinding2UintReadProbePipeline = createFullLayoutProbePipeline(CMDGEN_FULL_LAYOUT_BINDING2_UINT_READ_SHADER_RESOURCE, CMDGEN_FULL_LAYOUT_BINDING2_UINT_READ_SHADER_NAME, "CmdGenFullLayoutBinding2UintReadProbe");
    }


    private void ensureCommandGenRawMetadataUvec4Binding0ProbePipeline() {
        if (this.commandGenRawMetadataUvec4Binding0ProbePipeline != null) return;
        this.commandGenRawMetadataUvec4Binding0ProbePipeline = createSingleSsboReadProbePipeline(CMDGEN_RAW_METADATA_UVEC4_BINDING0_SHADER_RESOURCE, CMDGEN_RAW_METADATA_UVEC4_BINDING0_SHADER_NAME, CMDGEN_RENDER_LIST_BINDING, this.cmdGenTinyMetadataProbeBuffer != null ? this.cmdGenTinyMetadataProbeBuffer : this.cmdGenConfigBuffer, "CmdGenRawMetadataUvec4Binding0Probe");
    }


    private void ensureCommandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline() {
        if (this.commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline != null) return;
        this.commandGenNoImportComputeQuadCountsOnlyNoWriteProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_SHADER_RESOURCE, CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_SHADER_NAME, "CmdGenNoImportComputeQuadCountsOnlyNoWriteProbe");
    }


    private void ensureCommandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline() {
        if (this.commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline != null) return;
        this.commandGenNoImportWriteCommand0OnlyNoAtomicProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_SHADER_RESOURCE, CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_SHADER_NAME, "CmdGenNoImportWriteCommand0OnlyNoAtomicProbe");
    }


    private void ensureCommandGenNoImportAtomicDrawcountOnlyProbePipeline() {
        if (this.commandGenNoImportAtomicDrawcountOnlyProbePipeline != null) return;
        this.commandGenNoImportAtomicDrawcountOnlyProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_SHADER_RESOURCE, CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_SHADER_NAME, "CmdGenNoImportAtomicDrawcountOnlyProbe");
    }


    private void ensureCommandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline() {
        if (this.commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline != null) return;
        this.commandGenNoImportSingleInvocationRealCommandNoAtomicProbePipeline = createFullLayoutProbePipeline(CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_SHADER_RESOURCE, CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_SHADER_NAME, "CmdGenNoImportSingleInvocationRealCommandNoAtomicProbe");
    }


    private void ensureCommandGenDenseLayoutNoopProbePipeline() {
        if (this.commandGenDenseLayoutNoopProbePipeline != null) return;
        ComputePipeline.Builder builder = new ComputePipeline.Builder(CMDGEN_DENSE_LAYOUT_NOOP_SHADER_RESOURCE);
        builder.setUniforms(createManualCmdGenDescriptors(), List.of());
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(CMDGEN_DENSE_LAYOUT_NOOP_SHADER_RESOURCE);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen dense-layout noop shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            builder.compileShader(preprocessedShader.rootUrl(), CMDGEN_DENSE_LAYOUT_NOOP_SHADER_NAME);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section cmdgen dense-layout noop shader (compute=" + CMDGEN_DENSE_LAYOUT_NOOP_SHADER_NAME + ")", e);
        }
        try {
            this.commandGenDenseLayoutNoopProbePipeline = builder.createPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section cmdgen dense-layout noop compute pipeline", e);
        }
        if (this.commandGenDenseLayoutNoopProbePipeline == null || this.commandGenDenseLayoutNoopProbePipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create section cmdgen dense-layout noop compute pipeline");
        }
    }


    private void ensureCommandGenReadRenderlistMetadataNoWritePipeline() {
        if (this.commandGenReadRenderlistMetadataNoWritePipeline != null) return;
        this.commandGenReadRenderlistMetadataNoWritePipeline = createFullLayoutProbePipeline(CMDGEN_READ_RENDERLIST_METADATA_NO_WRITE_SHADER_RESOURCE, CMDGEN_READ_RENDERLIST_METADATA_NO_WRITE_SHADER_NAME, "CmdGenReadRenderlistMetadataNoWrite");
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


    private ComputePipeline createDenseSsboReadProbePipeline(String shaderResource, String shaderName, int descriptorBinding, Buffer descriptorBuffer, String label) {
        ensureCommandGenMinimalConfigReadProbePlaceholderBuffer();
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        List<UBO> descriptors = new java.util.ArrayList<>();
        for (int binding = 0; binding < descriptorBinding; binding++) {
            descriptors.add(createManualDescriptor(binding, computeStage, this.cmdGenMinimalConfigReadProbePlaceholderBuffer, label + "Placeholder" + binding));
        }
        descriptors.add(createManualDescriptor(descriptorBinding, computeStage, descriptorBuffer, label));
        ComputePipeline pipeline = createSsboReadProbePipeline(shaderResource, shaderName, descriptors, label, "manual_dense_placeholders_0_to_" + descriptorBinding);
        for (int binding = 0; binding < descriptorBinding; binding++) {
            final int targetBinding = binding;
            UBO ubo = pipeline.getUBO(candidate -> candidate.binding == targetBinding);
            if (ubo == null) throw new IllegalStateException("cmdgen dense SSBO read probe placeholder descriptor missing: binding=" + binding);
            ubo.getBufferSlice().set(this.cmdGenMinimalConfigReadProbePlaceholderBuffer, 0L, Integer.BYTES);
        }
        return pipeline;
    }


    private ComputePipeline createSingleSsboReadProbePipeline(String shaderResource, String shaderName, int descriptorBinding, Buffer descriptorBuffer, String label) {
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        return createSsboReadProbePipeline(shaderResource, shaderName, List.of(createManualDescriptor(descriptorBinding, computeStage, descriptorBuffer, label)), label, "manual_one_binding");
    }


    private ComputePipeline createSsboReadProbePipeline(String shaderResource, String shaderName, List<UBO> descriptors, String label, String descriptorMode) {
        ComputePipeline.Builder builder = new ComputePipeline.Builder(shaderResource);
        builder.setUniforms(descriptors, List.of());
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
                + ", descriptorMode=" + descriptorMode);
        return pipeline;
    }


    private ComputePipeline createFullLayoutProbePipeline(String shaderResource, String shaderName, String label) {
        ensureCmdgenBinding2ProbeBuffer();
        ComputePipeline.Builder builder = new ComputePipeline.Builder(shaderResource);
        builder.setUniforms(createManualCmdGenProbeDescriptors(), List.of());
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(shaderResource);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen full-layout probe shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            builder.compileShader(preprocessedShader.rootUrl(), shaderName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile section cmdgen full-layout probe shader (compute=" + shaderName + ")", e);
        }
        ComputePipeline pipeline;
        try {
            pipeline = builder.createPipeline();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create section cmdgen full-layout probe compute pipeline (compute=" + shaderName + ")", e);
        }
        if (pipeline == null || pipeline.getId() == 0L) {
            throw new IllegalStateException("Failed to create section cmdgen full-layout probe compute pipeline: " + shaderName);
        }
        VulkanBerylDebugLog.once("cmdgen-full-layout-probe-pipeline-created:" + label, "cmdgen full-layout probe pipeline created: label=" + label
                + ", shader=" + shaderName
                + ", descriptorMode=manual_dense_0_to_5");
        return pipeline;
    }




    private static boolean drawCountStoreSuppressedForDiagnosticSafetyActive() {
        return CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER
                && !ENABLE_INDIRECT_DRAW
                && disableAnyDrawCountConsumerPathActive();
    }


    private static String safeActiveCmdgenShaderResource() {
        if (drawCountStoreSuppressedForDiagnosticSafetyActive()) {
            return CMDGEN_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER_RESOURCE;
        }
        return activeCmdgenShaderResource();
    }


    private static String safeActiveCmdgenShaderName() {
        if (drawCountStoreSuppressedForDiagnosticSafetyActive()) {
            return CMDGEN_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER_NAME;
        }
        return activeCmdgenShaderName();
    }


    private static void logDrawCountStoreSuppressedForDiagnosticSafety(String shaderResource, String shaderName) {
        if (!drawCountStoreSuppressedForDiagnosticSafetyActive()) return;
        VulkanBerylDebugLog.once("cmdgen-drawcount-store-suppressed-for-diagnostic-safety", "cmdgen drawCount diagnostic store suppressed: drawCountStoreSuppressedForDiagnosticSafety=true"
                + ", originalShader=" + CMDGEN_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER_NAME
                + ", replacementShader=cmdgen_standalone_drawcount_declared_no_write"
                + ", selectedResource=" + shaderResource
                + ", selectedShaderName=" + shaderName
                + ", indirectDrawEnabled=" + ENABLE_INDIRECT_DRAW
                + ", drawCountConsumers=disabled_by_env"
                + ", reason=drawCount store isolated as device-loss trigger");
    }


    private static long pipelineBindingBufferId(ComputePipeline pipeline, int binding) {
        if (pipeline == null) return 0L;
        UBO ubo = pipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) return 0L;
        Buffer buffer = ubo.getBufferSlice().getBuffer();
        return buffer == null ? 0L : buffer.getId();
    }


    private static long pipelineBindingRangeBytes(ComputePipeline pipeline, int binding) {
        if (pipeline == null) return 0L;
        UBO ubo = pipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) return 0L;
        Buffer buffer = ubo.getBufferSlice().getBuffer();
        return buffer == null ? 0L : buffer.getBufferSize();
    }


    private String validateCmdgenDispatchDescriptorBindings(ComputePipeline pipeline, String stage) {
        if (pipeline == null) return "pipeline_missing";
        long binding0Range = pipelineBindingRangeBytes(pipeline, CMDGEN_RENDER_LIST_BINDING);
        long binding1Range = pipelineBindingRangeBytes(pipeline, CMDGEN_METADATA_BINDING);
        long binding2Range = pipelineBindingRangeBytes(pipeline, CMDGEN_UNUSED_BINDING2_BINDING);
        long binding3Range = pipelineBindingRangeBytes(pipeline, CMDGEN_DRAW_COMMAND_BINDING);
        long binding4Range = pipelineBindingRangeBytes(pipeline, CMDGEN_DRAW_COUNT_BINDING);
        long binding5Range = pipelineBindingRangeBytes(pipeline, CMDGEN_CONFIG_BINDING);
        long binding0Id = pipelineBindingBufferId(pipeline, CMDGEN_RENDER_LIST_BINDING);
        long binding1Id = pipelineBindingBufferId(pipeline, CMDGEN_METADATA_BINDING);
        long binding2Id = pipelineBindingBufferId(pipeline, CMDGEN_UNUSED_BINDING2_BINDING);
        long binding3Id = pipelineBindingBufferId(pipeline, CMDGEN_DRAW_COMMAND_BINDING);
        long binding4Id = pipelineBindingBufferId(pipeline, CMDGEN_DRAW_COUNT_BINDING);
        long binding5Id = pipelineBindingBufferId(pipeline, CMDGEN_CONFIG_BINDING);
        if (binding0Id == 0L) return "renderlist_buffer_id_zero";
        if (binding1Id == 0L) return "metadata_buffer_id_zero";
        if (binding2Id == 0L) return "binding2_buffer_id_zero";
        if (binding3Id == 0L) return "drawcommand_buffer_id_zero";
        if (binding4Id == 0L) return "drawcount_buffer_id_zero";
        if (binding5Id == 0L) return "config_buffer_id_zero";
        if (binding0Range <= 0L) return "renderlist_range_zero";
        if (binding1Range <= 0L) return "metadata_range_zero";
        if (binding2Range <= 0L) return "binding2_range_zero";
        if (binding3Range < DRAW_COMMAND_STRIDE_BYTES) return "drawcommand_range_below_stride: rangeBytes=" + binding3Range + " minRequired=" + DRAW_COMMAND_STRIDE_BYTES;
        if (binding4Range < Integer.BYTES) return "drawcount_range_below_word: rangeBytes=" + binding4Range + " minRequired=" + Integer.BYTES;
        if (binding5Range < CMDGEN_CONFIG_SIZE_BYTES) return "config_range_below_struct: rangeBytes=" + binding5Range + " minRequired=" + CMDGEN_CONFIG_SIZE_BYTES;
        if ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) == 0) return "drawcommand_buffer_missing_storage_usage";
        if ((this.drawCommandBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) == 0) return "drawcommand_buffer_missing_indirect_usage";
        if ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) == 0) return "drawcount_buffer_missing_storage_usage";
        if ((CMDGEN_CONFIG_USAGE_FLAGS & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) == 0) return "config_buffer_missing_storage_usage";
        VulkanBerylDebugLog.trace("cmdgen-dispatch-descriptor-validation:" + stage, "cmdgen dispatch descriptor validation ok: stage=" + stage
                + ", binding0Range=" + binding0Range + ", binding1Range=" + binding1Range + ", binding2Range=" + binding2Range
                + ", binding3Range=" + binding3Range + ", binding4Range=" + binding4Range + ", binding5Range=" + binding5Range);
        return "ok";
    }


    private static String cmdgenDispatchRiskFromState(boolean cmdgenPipelineBound, boolean cmdgenDescriptorsBound, boolean cmdgenDispatchCallRecorded, boolean cmdgenDispatchSkippedByEnv, boolean cmdgenPostDispatchBarrierRecorded, boolean cmdgenSameLayoutNoopActive, String validation, String stage) {
        if (validation != null && !"ok".equals(validation) && !"pipeline_missing".equals(validation)) return "range_or_usage";
        if (CMDGEN_DISABLE_BIND_PIPELINE || !cmdgenPipelineBound) return "pipeline_bind";
        if (CMDGEN_DISABLE_BIND_DESCRIPTORS || !cmdgenDescriptorsBound) return "descriptor_bind";
        if (CMDGEN_DISABLE_DISPATCH_CALL || !cmdgenDispatchCallRecorded) return "dispatch_call";
        if (cmdgenDispatchSkippedByEnv) return "cmdgen_dispatch_skipped";
        if (CMDGEN_DISABLE_POST_DISPATCH_BARRIER || !cmdgenPostDispatchBarrierRecorded) return "post_dispatch_barrier";
        if (cmdgenSameLayoutNoopActive) return "shader_memory_access";
        // Identify new shader-selection probe paths
        if ("command_write_after_visiblecount_read_only".equals(stage)) return "visiblecount_read_command_write";
        if ("command_write_after_indirectlookup0_read_only".equals(stage)) return "indirectlookup0_read_command_write";
        if ("command_write_after_renderlist_read_no_branch".equals(stage)) return "renderlist_read_no_branch_command_write";
        if ("command_write_after_visiblecount_branch_only".equals(stage)) return "visiblecount_branch_command_write";
        if ("command_write_after_renderlist_read_no_metadata".equals(stage)) return "renderlist_read_no_metadata_command_write";
        if ("command_write_after_metadata0_read_no_renderlist".equals(stage)) return "metadata0_read_no_renderlist_command_write";
        if ("command_write_before_metadata_read".equals(stage)) return "before_metadata_read_command_write";
        if ("full".equals(stage) || "full cmdgen.comp".equals(stage) || stage == null) return "full_no_drawcount_write";
        return "unknown";
    }


    private void logCmdgenDispatchPathDiagnostic(ComputePipeline pipeline, String shaderName, String shaderResource, String stage, boolean cmdgenPipelineBound, boolean cmdgenDescriptorsBound, boolean cmdgenDispatchCallRecorded, boolean cmdgenDispatchSkippedByEnv, boolean cmdgenPostDispatchBarrierRecorded, String cmdgenPostDispatchBarrierSkippedReason, int cmdgenDispatchGroupCount, boolean cmdgenSameLayoutNoopActive, String validation, String reason) {
        boolean cmdgenDispatchRecorded = cmdgenPipelineBound && cmdgenDescriptorsBound && cmdgenDispatchCallRecorded;
        String dispatchRisk = cmdgenDispatchRiskFromState(cmdgenPipelineBound, cmdgenDescriptorsBound, cmdgenDispatchCallRecorded, cmdgenDispatchSkippedByEnv, cmdgenPostDispatchBarrierRecorded, cmdgenSameLayoutNoopActive, validation, stage);
        int cmdgenDispatchInvocationLimit = cmdgenDispatchGroupCount * 128;
        String cmdgenIsolationMode = cmdgenSameLayoutNoopActive ? "noop_same_layout" : ("full".equals(stage) || "full cmdgen.comp".equals(stage) ? "full_no_drawcount_write" : stage);
        long controlledSmokeSectionId = this.controlledSmokeCommandExpectedSectionId >= 0 ? Integer.toUnsignedLong(this.controlledSmokeCommandExpectedSectionId) : -1L;
        long controlledSmokeSectionQuadCount = this.controlledSmokeCommandExpectedVertexCount >= 0 ? this.controlledSmokeCommandExpectedVertexCount / 4L : -1L;
        VulkanBerylDebugLog.once("cmdgen-dispatch-path-diagnostic:" + stage + ":" + reason, "cmdgen dispatch path diagnostic: stage=" + stage
                + ", reason=" + reason
                + ", cmdgenSelectedShader=" + shaderName
                + ", cmdgenIsolationMode=" + cmdgenIsolationMode
                + ", cmdgenPipelineBound=" + cmdgenPipelineBound
                + ", cmdgenDescriptorsBound=" + cmdgenDescriptorsBound
                + ", enableIndirectDrawEnv=" + ENABLE_INDIRECT_DRAW
                + ", cmdgenDispatchCallRecorded=" + cmdgenDispatchCallRecorded
                + ", cmdgenPostDispatchBarrierRecorded=" + cmdgenPostDispatchBarrierRecorded
                + ", cmdgenDispatchSkippedByEnv=" + cmdgenDispatchSkippedByEnv
                + ", cmdgenPostDispatchBarrierSkippedReason=" + (cmdgenPostDispatchBarrierSkippedReason == null ? "null" : cmdgenPostDispatchBarrierSkippedReason)
                + ", cmdgenDispatchRecorded=" + cmdgenDispatchRecorded
                + ", cmdgenDispatchGroupCount=" + cmdgenDispatchGroupCount
                + ", cmdgenDispatchInvocationLimit=" + cmdgenDispatchInvocationLimit
                + ", cmdgenDescriptorBinding0BufferId=" + pipelineBindingBufferId(pipeline, CMDGEN_RENDER_LIST_BINDING)
                + ", cmdgenDescriptorBinding1BufferId=" + pipelineBindingBufferId(pipeline, CMDGEN_METADATA_BINDING)
                + ", cmdgenDescriptorBinding2BufferId=" + pipelineBindingBufferId(pipeline, CMDGEN_UNUSED_BINDING2_BINDING)
                + ", cmdgenDescriptorBinding3BufferId=" + pipelineBindingBufferId(pipeline, CMDGEN_DRAW_COMMAND_BINDING)
                + ", cmdgenDescriptorBinding4BufferId=" + pipelineBindingBufferId(pipeline, CMDGEN_DRAW_COUNT_BINDING)
                + ", cmdgenDescriptorBinding5BufferId=" + pipelineBindingBufferId(pipeline, CMDGEN_CONFIG_BINDING)
                + ", cmdgenDescriptorBinding0RangeBytes=" + pipelineBindingRangeBytes(pipeline, CMDGEN_RENDER_LIST_BINDING)
                + ", cmdgenDescriptorBinding1RangeBytes=" + pipelineBindingRangeBytes(pipeline, CMDGEN_METADATA_BINDING)
                + ", cmdgenDescriptorBinding2RangeBytes=" + pipelineBindingRangeBytes(pipeline, CMDGEN_UNUSED_BINDING2_BINDING)
                + ", cmdgenDescriptorBinding3RangeBytes=" + pipelineBindingRangeBytes(pipeline, CMDGEN_DRAW_COMMAND_BINDING)
                + ", cmdgenDescriptorBinding3ActualBufferBytes=" + (this.drawCommandBuffer == null ? -1L : this.drawCommandBuffer.getBufferSize())
                + ", cmdgenDescriptorBinding3ExpectedBytes=" + (this.drawCommandCapacity <= 0 ? -1L : drawCommandBinding3ExpectedBytes())
                + ", cmdgenDescriptorBinding3RangeMatchesExpected=" + (pipelineBindingRangeBytes(pipeline, CMDGEN_DRAW_COMMAND_BINDING) == (this.drawCommandCapacity <= 0 ? -1L : drawCommandBinding3ExpectedBytes()))
                + ", drawCommandCapacity=" + this.drawCommandCapacity
                + ", drawCommandStrideBytes=" + DRAW_COMMAND_STRIDE_BYTES
                + ", drawCommandBufferAllocationGeneration=" + this.drawCommandBufferAllocationGeneration
                + ", cmdgenBinding3DescriptorReboundGeneration=" + this.lastCmdgenBinding3ReboundGeneration
                + ", cmdgenBinding3DescriptorStaleRisk=" + (this.lastCmdgenBinding3ReboundGeneration == this.drawCommandBufferAllocationGeneration ? "none" : "descriptor_not_rebound_after_draw_command_buffer_allocation")
                + ", cmdgenDescriptorBinding4RangeBytes=" + pipelineBindingRangeBytes(pipeline, CMDGEN_DRAW_COUNT_BINDING)
                + ", cmdgenDescriptorBinding5RangeBytes=" + pipelineBindingRangeBytes(pipeline, CMDGEN_CONFIG_BINDING)
                + ", cmdgenControlledSmokeSectionId=" + controlledSmokeSectionId
                + ", cmdgenControlledSmokeSectionQuadCount=" + controlledSmokeSectionQuadCount
                + ", cmdgenControlledSmokeExpectedVertexCount=" + this.controlledSmokeCommandExpectedVertexCount
                + ", cmdgenControlledSmokeExpectedFirstVertex=" + this.controlledSmokeCommandExpectedFirstVertex
                + ", cmdgenConfigRenderListCapacity=" + this.lastCmdGenConfigRenderListCapacity
                + ", cmdgenConfigMetadataSectionCapacity=" + this.lastCmdGenConfigMetadataSectionCapacity
                + ", cmdgenConfigGeometryCapacityQuads=" + this.lastCmdGenConfigGeometryCapacityQuads
                + ", cmdgenConfigDrawCommandCapacity=" + this.lastCmdGenConfigDrawCommandCapacity
                + ", cmdgenConfigDrawCountCapacityWords=" + this.lastCmdGenConfigDrawCountCapacityWords
                + ", cmdgenConfigFlags=0x" + Integer.toHexString(this.lastCmdGenConfigFlags)
                + ", cmdgenDescriptorBindingValidation=" + (validation == null ? "null" : validation)
                + ", drawCommandBufferUsageFlags=0x" + Integer.toHexString(this.drawCommandBufferUsageFlags)
                + ", drawCountBufferUsageFlags=0x" + Integer.toHexString(this.drawCountBufferUsageFlags)
                + ", cmdgenConfigUsageFlags=0x" + Integer.toHexString(CMDGEN_CONFIG_USAGE_FLAGS)
                + ", cmdgenDispatchRisk=" + dispatchRisk);
    }


    private static void logSelectedCmdgenShaderDiagnostics(String shaderResource, String shaderName) {
        if (!CMDGEN_DUMP_SHADER_DIAGNOSTICS) return;
        VulkanBerylDebugLog.once("cmdgen-selected-shader-diagnostics", "selected cmdgen shader diagnostics: resource=" + shaderResource
                + ", shaderName=" + shaderName
                + ", mode=" + activeCmdgenShaderMode()
                + ", selectionEnv=" + (activeCmdgenShaderSelectionEnvVar() == null ? "<default>" : activeCmdgenShaderSelectionEnvVar())
                + ", compareWith=glslangValidator -V selected-cmdgen-shader.comp && spirv-val selected-cmdgen-shader.spv && spirv-dis selected-cmdgen-shader.spv");
        try {
            String shaderSource = readShaderResourceSource(shaderResource);
            logSelectedCmdgenShaderSourceAnalysis(shaderResource, shaderSource);
            Path debugDir = selectedCmdgenShaderDebugDirectory();
            Files.createDirectories(debugDir);
            Path rawDump = debugDir.resolve("selected-cmdgen-shader.comp");
            Files.writeString(rawDump, shaderSource, StandardCharsets.UTF_8);
            VulkanBerylDebugLog.once("cmdgen-selected-shader-source-dumped", "selected cmdgen shader source dumped: path=" + rawDump
                    + "; compare with: glslangValidator -V " + rawDump + " && spirv-val selected-cmdgen-shader.spv && spirv-dis selected-cmdgen-shader.spv");
        } catch (Exception e) {
            VulkanBerylDebugLog.error("failed to dump selected cmdgen shader source: resource=" + shaderResource + ", error=" + e);
        }
    }


    private static void dumpSelectedCmdgenPreprocessedShaderDiagnostics(VulkanBerylShaderImportPreprocessor.PreparedShader preprocessedShader) {
        if (!CMDGEN_DUMP_SHADER_DIAGNOSTICS) return;
        try {
            Path debugDir = selectedCmdgenShaderDebugDirectory();
            Files.createDirectories(debugDir);
            Path preprocessedDump = debugDir.resolve("selected-cmdgen-shader.preprocessed.comp");
            Files.copy(preprocessedShader.shaderPath(), preprocessedDump, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            VulkanBerylDebugLog.once("cmdgen-selected-shader-preprocessed-dumped", "selected cmdgen preprocessed shader dumped: path=" + preprocessedDump
                    + ", tempPath=" + preprocessedShader.shaderPath()
                    + "; compare with: glslangValidator -V " + preprocessedDump + " && spirv-val selected-cmdgen-shader.preprocessed.spv && spirv-dis selected-cmdgen-shader.preprocessed.spv");
        } catch (Exception e) {
            VulkanBerylDebugLog.error("selected cmdgen preprocessed shader dump unavailable: error=" + e);
        }
    }



    private static void logSelectedCmdgenShaderSourceAnalysis(String shaderResource, String shaderSource) {
        if (activeCmdgenShaderSelectionEnvVar() == null && !CMDGEN_DUMP_SHADER_DIAGNOSTICS) return;
        int drawCountBindingDeclarations = countOccurrences(shaderSource, "layout(binding = " + CMDGEN_DRAW_COUNT_BINDING + ", std430)") + countOccurrences(shaderSource, "layout(binding=" + CMDGEN_DRAW_COUNT_BINDING + ",std430)");
        int drawCountStores = countOccurrences(shaderSource, "drawCount =") + countOccurrences(shaderSource, "drawCount[0] =") + countOccurrences(shaderSource, "atomicAdd(drawCount") + countOccurrences(shaderSource, "atomicExchange(drawCount");
        int commandStores = countOccurrences(shaderSource, "commands[");
        boolean noConfigZero = CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER && !drawCountStoreSuppressedForDiagnosticSafetyActive();
        VulkanBerylDebugLog.once("cmdgen-selected-shader-source-analysis:" + shaderResource, "selected cmdgen shader source analysis: resource=" + shaderResource
                + ", mode=" + activeCmdgenShaderMode()
                + ", drawCountGlslBlockBinding=" + CMDGEN_DRAW_COUNT_BINDING
                + ", drawCountBindingDeclarationCount=" + drawCountBindingDeclarations
                + ", drawCountStoreTokenCount=" + drawCountStores
                + ", commandStoreTokenCount=" + commandStores
                + ", noConfigLiteralZeroExpectedOnlyBinding4Write=" + noConfigZero
                + ", noConfigLiteralZeroBinding4OnlyWriteConfirmedByJavaTokenCheck=" + (noConfigZero && drawCountBindingDeclarations >= 1 && drawCountStores == 1 && commandStores == 0));
    }


    private static int countOccurrences(String text, String needle) {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }


    private static Path selectedCmdgenShaderDebugDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("voxy-vulkan-debug");
    }


    private static String readShaderResourceSource(String shaderResource) {
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(shaderResource);
        String classpathPath = VulkanBerylShaderImportPreprocessor.classpathShaderAssetPath(id);
        try (InputStream in = VulkanBerylSectionDrawPipeline.class.getResourceAsStream(classpathPath)) {
            if (in == null) throw new IllegalStateException("Shader resource not found: " + shaderResource + " (" + classpathPath + ")");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading shader resource for diagnostics: " + shaderResource + " (" + classpathPath + ")", e);
        }
    }


    private void ensureCommandGenPipeline() {
        if (this.commandGenPipeline != null) return;
        URL configUrl = VulkanBerylSectionDrawPipeline.class.getResource(CMDGEN_SHADER_CONFIG);
        Objects.requireNonNull(configUrl, "Missing section cmdgen shader config: " + CMDGEN_SHADER_CONFIG);
        String cmdgenShaderResource = safeActiveCmdgenShaderResource();
        String cmdgenShaderName = safeActiveCmdgenShaderName();
        logDrawCountStoreSuppressedForDiagnosticSafety(cmdgenShaderResource, cmdgenShaderName);
        if (activeCmdgenShaderSelectionEnvVar() != null) {
            logSelectedCmdgenShaderSourceAnalysis(cmdgenShaderResource, readShaderResourceSource(cmdgenShaderResource));
        }
        logSelectedCmdgenShaderDiagnostics(cmdgenShaderResource, cmdgenShaderName);
        if (activeCmdgenShaderSelectionEnvVar() != null) {
            VulkanBerylDebugLog.once("cmdgen-standalone-binding0-config-active", "selected normal-cmdgen shader active: env=" + activeCmdgenShaderSelectionEnvVar() + ", mode=" + activeCmdgenShaderMode());
            VulkanBerylDebugLog.once("cmdgen-standalone-binding0-config-resource", "selected normal-cmdgen shader resource name: " + cmdgenShaderResource + ", shaderName=" + cmdgenShaderName);
        }
        ComputePipeline.Builder builder = new ComputePipeline.Builder(cmdgenShaderResource);
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
        VulkanBerylDebugLog.verboseOnce("section-cmdgen-descriptor-layout", "Section cmdgen descriptor layout: descriptorMode=manual_dense_with_unused_binding2, count=" + cmdgenBindings.size()
                + ", bindings=" + cmdgenBindings
                + ", minBinding=" + minBinding
                + ", maxBinding=" + maxBinding
                + ", denseFromZero=" + denseFromZero);
        if (activeCmdgenShaderSelectionEnvVar() != null) {
            VulkanBerylDebugLog.once("cmdgen-standalone-binding0-config-descriptor-path", "selected normal-cmdgen shader uses same descriptor path as normal cmdgen: descriptorMode=manual_dense_with_unused_binding2, bindings=" + cmdgenBindings);
        }
        try {
            var preprocessedShader = VulkanBerylShaderImportPreprocessor.preprocessToTemp(cmdgenShaderResource);
            if (!java.nio.file.Files.isRegularFile(preprocessedShader.shaderPath())) {
                throw new IllegalStateException("Preprocessed cmdgen shader file missing before compile: " + preprocessedShader.shaderPath());
            }
            dumpSelectedCmdgenPreprocessedShaderDiagnostics(preprocessedShader);
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
            throw new IllegalStateException("Failed to compile section cmdgen shader (compute=" + cmdgenShaderName + ", config=" + CMDGEN_SHADER_CONFIG + ")", e);
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
        VulkanBerylDebugLog.once("cmdgen-pipeline-created", "cmdgen pipeline created: shader=" + cmdgenShaderName);
    }


    private void ensureCommandGenNoopPipeline() {
        if (this.commandGenNoopPipeline != null) return;
        ComputePipeline.Builder builder = new ComputePipeline.Builder(CMDGEN_NOOP_SHADER_RESOURCE);
        if (CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE) {
            builder.setUniforms(createManualCmdGenProbeDescriptors(), List.of());
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
                "CmdGenUnusedBinding2", CMDGEN_UNUSED_BINDING2_BINDING,
                "DrawCommandsBuffer", CMDGEN_DRAW_COMMAND_BINDING,
                "DrawCountBuffer", CMDGEN_DRAW_COUNT_BINDING,
                "CmdGenConfigBuffer", CMDGEN_CONFIG_BINDING
        );
        java.util.Map<Integer, String> expectedShaderDeclarations = java.util.Map.of(
                CMDGEN_RENDER_LIST_BINDING, "RenderListBuffer",
                CMDGEN_METADATA_BINDING, "MetadataBuffer",
                CMDGEN_UNUSED_BINDING2_BINDING, "CmdGenUnusedBinding2",
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
        String cmdgenShaderResource = safeActiveCmdgenShaderResource();
        String resourcePath = cmdgenShaderResource.replace("voxy:", "/assets/voxy/");
        try (var stream = VulkanBerylSectionDrawPipeline.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing cmdgen shader resource for layout validation: " + cmdgenShaderResource);
            }
            shaderSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read cmdgen shader for layout validation: " + cmdgenShaderResource, e);
        }
        for (var entry : expectedShaderDeclarations.entrySet()) {
            String declaration = "layout(binding = " + entry.getKey() + ", std430)";
            if (!shaderSource.contains(declaration) || !shaderSource.contains("buffer " + entry.getValue())) {
                throw new IllegalStateException("Cmdgen shader reflection mismatch: expected " + entry.getValue() + " at binding=" + entry.getKey());
            }
        }
        VulkanBerylDebugLog.once("section-cmdgen-intended-binding-map", "Section cmdgen intended binding map: binding0=renderList(RenderListBuffer), binding1=metadata(MetadataBuffer), binding2=unused dense padding(CmdGenUnusedBinding2, shaderAccess=false), binding3=commands(DrawCommandsBuffer), binding4=drawCount(DrawCountBuffer), binding5=config(CmdGenConfigBuffer)");
        VulkanBerylDebugLog.verboseOnce("section-cmdgen-layout-contract", "Section cmdgen layout contract validated: renderListBinding=" + CMDGEN_RENDER_LIST_BINDING
                + ", metadataBinding=" + CMDGEN_METADATA_BINDING
                + ", unusedBinding2=" + CMDGEN_UNUSED_BINDING2_BINDING
                + ", unusedBinding2ShaderAccess=false"
                + ", drawCommandsBinding=" + CMDGEN_DRAW_COMMAND_BINDING
                + ", drawCountBinding=" + CMDGEN_DRAW_COUNT_BINDING
                + ", configBinding=" + CMDGEN_CONFIG_BINDING
                + ", bindings=" + jsonBindings);
    }


    private List<UBO> createManualCmdGenDescriptors() {
        ensureCmdGenUnusedBinding2Buffer();
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        return List.of(
                createManualDescriptor(CMDGEN_RENDER_LIST_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == RENDER_LIST_BINDING).getBufferSlice().getBuffer(), "CmdGenRenderList"),
                createManualDescriptor(CMDGEN_METADATA_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == METADATA_BINDING).getBufferSlice().getBuffer(), "CmdGenMetadata"),
                createManualDescriptor(CMDGEN_UNUSED_BINDING2_BINDING, computeStage, this.cmdGenUnusedBinding2Buffer, "CmdGenUnusedBinding2"),
                createManualDescriptor(CMDGEN_DRAW_COMMAND_BINDING, computeStage, this.drawCommandBuffer, "CmdGenDrawCommand"),
                createManualDescriptor(CMDGEN_DRAW_COUNT_BINDING, computeStage, cmdgenDrawCountDescriptorBuffer(), drawCountDescriptorLabel()),
                createManualDescriptor(CMDGEN_CONFIG_BINDING, computeStage, this.cmdGenConfigBuffer, "CmdGenConfig")
        );
    }



    private List<UBO> createManualCmdGenProbeDescriptors() {
        ensureCmdgenBinding2ProbeBuffer();
        int computeStage = ComputePipeline.Builder.getStageFromString("compute");
        return List.of(
                createManualDescriptor(CMDGEN_RENDER_LIST_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == RENDER_LIST_BINDING).getBufferSlice().getBuffer(), "CmdGenProbeRenderList"),
                createManualDescriptor(CMDGEN_METADATA_BINDING, computeStage, this.graphicsPipeline.getUBO(c -> c.binding == METADATA_BINDING).getBufferSlice().getBuffer(), "CmdGenProbeMetadata"),
                createManualDescriptor(CMDGEN_BINDING2_PROBE_BINDING, computeStage, this.cmdGenBinding2ProbeBuffer, "CmdGenProbeBinding2"),
                createManualDescriptor(CMDGEN_DRAW_COMMAND_BINDING, computeStage, this.drawCommandBuffer, "CmdGenProbeDrawCommand"),
                createManualDescriptor(CMDGEN_DRAW_COUNT_BINDING, computeStage, cmdgenDrawCountDescriptorBuffer(), drawCountDescriptorLabel()),
                createManualDescriptor(CMDGEN_CONFIG_BINDING, computeStage, this.cmdGenConfigBuffer, "CmdGenProbeConfig")
        );
    }


    private static ManualUBO createManualDescriptor(int binding, int computeStage, Buffer buffer, String label) {
        int requestedSize = descriptorSizeBytes(binding, label, buffer);
        int structSizeInts = Math.max(1, (requestedSize + Integer.BYTES - 1) / Integer.BYTES);
        VulkanBerylDebugLog.verboseOnce("manual-descriptor:" + label + ":" + binding, "Creating manual descriptor binding=" + binding + ", label=" + label + ", requestedBytes=" + requestedSize + ", descriptorKind=storageBuffer, descriptorType=" + VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC + ", descriptorClass=ManualStorageBuffer, manualStructInts=" + structSizeInts);
        return new ManualStorageBuffer(binding, computeStage, structSizeInts);
    }


    private static int descriptorSizeBytes(int binding, String label, Buffer buffer) {
        if (buffer == null) throw new IllegalStateException("Descriptor buffer is null for binding " + binding + " (" + label + ")");
        long size = buffer.getBufferSize();
        if (size <= 0L || size > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, size);
        }
        return (int) size;
    }


    private void ensureSceneUniformBuffer() {
        if (this.sceneUniformBuffer != null) return;
        this.sceneUniformBuffer = new Buffer("voxy_vulkanberyl_section_draw_scene_uniform", VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
        this.sceneUniformBuffer.createBuffer(SCENE_UNIFORM_SIZE_BYTES);
        this.sceneUniformBound = false;
    }


    private void ensureRealLodGpuDecodeParityBuffers() {
        if (this.realLodGpuDecodeParityBuffer == null) {
            this.realLodGpuDecodeParityBuffer = new Buffer("voxy_vulkanberyl_real_lod_gpu_decode_parity",
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    MemoryTypes.GPU_MEM);
            this.realLodGpuDecodeParityBuffer.createBuffer(REAL_LOD_GPU_DECODE_PARITY_BYTES);
        }
        if (this.realLodGpuDecodeParityReadbackBuffer == null) {
            this.realLodGpuDecodeParityReadbackBuffer = new Buffer("voxy_vulkanberyl_real_lod_gpu_decode_parity_readback",
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    MemoryTypes.HOST_MEM);
            this.realLodGpuDecodeParityReadbackBuffer.createBuffer(REAL_LOD_GPU_DECODE_PARITY_BYTES);
        }
    }


    private void bindUniformBinding(int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize < SCENE_UNIFORM_SIZE_BYTES || bufferSize > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, bufferSize);
        }

        UBO ubo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) {
            throw new IllegalStateException("Section draw descriptor missing: name=" + label + ", binding=" + binding + ", config=" + DRAW_SHADER_CONFIG);
        }
        this.sectionDrawBinding0DescriptorKind = descriptorKind(ubo);
        if (!"uniformBuffer".equals(this.sectionDrawBinding0DescriptorKind)) {
            throw new IllegalStateException("Section draw SceneUniform descriptor kind mismatch: binding=" + binding + ", descriptorKind=" + this.sectionDrawBinding0DescriptorKind + ", expected=uniformBuffer");
        }
        ubo.getBufferSlice().set(buffer, 0L, SCENE_UNIFORM_SIZE_BYTES);
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


    private void logSectionDrawDescriptorParityBindings(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE || this.graphicsPipeline == null) return;
        UBO geometryUbo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == GEOMETRY_BINDING);
        UBO metadataUbo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == METADATA_BINDING);
        UBO renderListUbo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == RENDER_LIST_BINDING);
        UBO parityUbo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == REAL_LOD_GPU_DECODE_PARITY_BINDING);
        Buffer geometryBuffer = geometryData == null ? null : geometryData.getGeometryBuffer();
        Buffer metadataBuffer = geometryData == null ? null : geometryData.getMetadataBuffer();
        Buffer renderListBuffer = renderList == null ? null : renderList.getBuffer();
        long geometryRange = geometryBuffer == null ? -1L : geometryBuffer.getBufferSize();
        long metadataRange = metadataBuffer == null ? -1L : metadataBuffer.getBufferSize();
        long renderListRange = renderListBuffer == null ? -1L : renderList.getBuffer().getBufferSize();
        long parityRange = this.realLodGpuDecodeParityBuffer == null ? -1L : this.realLodGpuDecodeParityBuffer.getBufferSize();
        int geomStride = 8;
        int metaStride = 32;
        int rlStride = 4;
        long geomQuadByteOffset = Math.max(0L, this.realLodProbeCpuSelectedQuadIndex) * (long) geomStride;
        long metaSectionByteOffset = (long) Math.max(0, this.realLodProbeCpuSelectedSectionId) * (long) metaStride;
        boolean geomQuadInRange = geomQuadByteOffset + geomStride <= geometryRange;
        boolean metaSectionInRange = metaSectionByteOffset + metaStride <= metadataRange;
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-descriptor-parity-bindings", "section draw real LOD descriptor parity bindings:"
                + " binding4GeometryPresent=" + (geometryUbo != null)
                + ", binding4GeometryExpectedBufferId=" + bufferId(geometryBuffer)
                + ", binding4GeometryBoundBufferId=" + boundBufferId(geometryUbo)
                + ", binding4GeometryBoundExpectedResource=" + boundExpectedBuffer(geometryUbo, geometryBuffer)
                + ", binding4GeometryDescriptorOffset=0"
                + ", binding4GeometryDescriptorRangeBytes=" + geometryRange
                + ", binding4GeometryElementStride=" + geomStride
                + ", binding4GeometrySelectedQuadByteOffset=" + geomQuadByteOffset
                + ", binding4GeometrySelectedQuadInRange=" + geomQuadInRange
                + ", binding5MetadataPresent=" + (metadataUbo != null)
                + ", binding5MetadataExpectedBufferId=" + bufferId(metadataBuffer)
                + ", binding5MetadataBoundBufferId=" + boundBufferId(metadataUbo)
                + ", binding5MetadataBoundExpectedResource=" + boundExpectedBuffer(metadataUbo, metadataBuffer)
                + ", binding5MetadataDescriptorOffset=0"
                + ", binding5MetadataDescriptorRangeBytes=" + metadataRange
                + ", binding5MetadataElementStride=" + metaStride
                + ", binding5MetadataSelectedSectionByteOffset=" + metaSectionByteOffset
                + ", binding5MetadataSelectedSectionInRange=" + metaSectionInRange
                + ", binding6RenderListPresent=" + (renderListUbo != null)
                + ", binding6RenderListExpectedBufferId=" + bufferId(renderListBuffer)
                + ", binding6RenderListBoundBufferId=" + boundBufferId(renderListUbo)
                + ", binding6RenderListBoundExpectedResource=" + boundExpectedBuffer(renderListUbo, renderListBuffer)
                + ", binding6RenderListDescriptorOffset=0"
                + ", binding6RenderListDescriptorRangeBytes=" + renderListRange
                + ", binding6RenderListElementStride=" + rlStride
                + ", binding9ParityPresent=" + (parityUbo != null)
                + ", binding9ParityExpectedBufferId=" + bufferId(this.realLodGpuDecodeParityBuffer)
                + ", binding9ParityBoundBufferId=" + boundBufferId(parityUbo)
                + ", binding9ParityBoundExpectedResource=" + boundExpectedBuffer(parityUbo, this.realLodGpuDecodeParityBuffer)
                + ", binding9ParityDescriptorOffset=0"
                + ", binding9ParityDescriptorRangeBytes=" + parityRange
                + ", binding9ParityWords=" + REAL_LOD_GPU_DECODE_PARITY_WORDS
                + ", binding9ParityBytes=" + REAL_LOD_GPU_DECODE_PARITY_BYTES, 60);
    }


    private static long bufferId(Buffer buffer) {
        return buffer == null ? 0L : buffer.getId();
    }


    private static long boundBufferId(UBO ubo) {
        if (ubo == null || ubo.getBufferSlice() == null || ubo.getBufferSlice().getBuffer() == null) return 0L;
        return ubo.getBufferSlice().getBuffer().getId();
    }


    private static boolean boundExpectedBuffer(UBO ubo, Buffer expectedBuffer) {
        return expectedBuffer != null && boundBufferId(ubo) == expectedBuffer.getId();
    }

    private void logRealLodProbeBoundBufferContentDiagnostics(VulkanBerylSectionGeometryData geometryData, CpuDecodeParitySnapshot cpuSnapshot, GpuDecodeParitySnapshot gpuSnapshot) {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE || geometryData == null) return;
        this.realLodProbeBoundMetadataReadbackAvailable = false;
        this.realLodProbeBoundGeometryReadbackAvailable = false;
        this.realLodProbeBoundMetadataMatchesCpuSnapshot = "unchecked";
        this.realLodProbeShaderMetadataMatchesBoundMetadata = "unchecked";
        this.realLodProbeBoundGeometryRawQuadMatchesCpuSnapshot = "unchecked";
        this.realLodProbeShaderRawQuadMatchesBoundGeometry = "unchecked";
        this.realLodProbeBufferContentMismatchReason = "not_computed";
        Buffer metadataBuf = geometryData.getMetadataBuffer();
        long metadataPtr = metadataBuf == null ? 0L : metadataBuf.getDataPtr();
        int sectionId = this.realLodProbeCpuSelectedSectionId;
        int sectionByteOffset = sectionId * 32;
        if (metadataPtr != 0L && sectionId >= 0 && sectionByteOffset + 32 <= metadataBuf.getBufferSize()) {
            this.realLodProbeBoundMetadataSectionMetaA0 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset);
            this.realLodProbeBoundMetadataSectionMetaA1 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 4L);
            this.realLodProbeBoundMetadataSectionMetaA2 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 8L);
            this.realLodProbeBoundMetadataSectionMetaA3 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 12L);
            this.realLodProbeBoundMetadataSectionMetaB0 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 16L);
            this.realLodProbeBoundMetadataSectionMetaB1 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 20L);
            this.realLodProbeBoundMetadataSectionMetaB2 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 24L);
            this.realLodProbeBoundMetadataSectionMetaB3 = MemoryUtil.memGetInt(metadataPtr + sectionByteOffset + 28L);
            this.realLodProbeBoundMetadataReadbackAvailable = true;
        }
        Buffer geometryBuf = geometryData.getGeometryBuffer();
        long geometryPtr = geometryBuf == null ? 0L : geometryBuf.getDataPtr();
        long quadIndex = this.realLodProbeCpuSelectedQuadIndex;
        long quadByteOffset = quadIndex * 8L;
        if (geometryPtr != 0L && quadIndex >= 0L && quadByteOffset + 8L <= geometryBuf.getBufferSize()) {
            this.realLodProbeBoundGeometryRawQuadData = MemoryUtil.memGetLong(geometryPtr + quadByteOffset);
            this.realLodProbeBoundGeometryReadbackAvailable = true;
        }
        boolean boundMetaMatchesCpu = false;
        if (this.realLodProbeBoundMetadataReadbackAvailable && cpuSnapshot != null && cpuSnapshot.available()) {
            boundMetaMatchesCpu = this.realLodProbeBoundMetadataSectionMetaA0 == cpuSnapshot.selectedSectionMetaA0()
                    && this.realLodProbeBoundMetadataSectionMetaA1 == cpuSnapshot.selectedSectionMetaA1()
                    && this.realLodProbeBoundMetadataSectionMetaA2 == cpuSnapshot.selectedSectionMetaA2()
                    && this.realLodProbeBoundMetadataSectionMetaA3 == cpuSnapshot.selectedSectionMetaA3()
                    && this.realLodProbeBoundMetadataSectionMetaB0 == cpuSnapshot.selectedSectionMetaB0()
                    && this.realLodProbeBoundMetadataSectionMetaB1 == cpuSnapshot.selectedSectionMetaB1()
                    && this.realLodProbeBoundMetadataSectionMetaB2 == cpuSnapshot.selectedSectionMetaB2()
                    && this.realLodProbeBoundMetadataSectionMetaB3 == cpuSnapshot.selectedSectionMetaB3();
        }
        this.realLodProbeBoundMetadataMatchesCpuSnapshot = this.realLodProbeBoundMetadataReadbackAvailable ? String.valueOf(boundMetaMatchesCpu) : "unavailable";
        boolean shaderMetaMatchesBound = false;
        if (this.realLodProbeBoundMetadataReadbackAvailable && gpuSnapshot != null && gpuSnapshot.available() && gpuSnapshot.magicValid()) {
            shaderMetaMatchesBound = this.realLodProbeBoundMetadataSectionMetaA0 == gpuSnapshot.activeSectionMetaA0()
                    && this.realLodProbeBoundMetadataSectionMetaA1 == gpuSnapshot.activeSectionMetaA1()
                    && this.realLodProbeBoundMetadataSectionMetaA2 == gpuSnapshot.activeSectionMetaA2()
                    && this.realLodProbeBoundMetadataSectionMetaA3 == gpuSnapshot.activeSectionMetaA3()
                    && this.realLodProbeBoundMetadataSectionMetaB0 == gpuSnapshot.activeSectionMetaB0()
                    && this.realLodProbeBoundMetadataSectionMetaB1 == gpuSnapshot.activeSectionMetaB1()
                    && this.realLodProbeBoundMetadataSectionMetaB2 == gpuSnapshot.activeSectionMetaB2()
                    && this.realLodProbeBoundMetadataSectionMetaB3 == gpuSnapshot.activeSectionMetaB3();
        }
        this.realLodProbeShaderMetadataMatchesBoundMetadata = this.realLodProbeBoundMetadataReadbackAvailable ? String.valueOf(shaderMetaMatchesBound) : "unavailable";
        boolean boundGeomMatchesCpu = false;
        if (this.realLodProbeBoundGeometryReadbackAvailable && cpuSnapshot != null && cpuSnapshot.available()) {
            boundGeomMatchesCpu = Long.toUnsignedString(this.realLodProbeBoundGeometryRawQuadData).equals(cpuSnapshot.rawQuad());
        }
        this.realLodProbeBoundGeometryRawQuadMatchesCpuSnapshot = this.realLodProbeBoundGeometryReadbackAvailable ? String.valueOf(boundGeomMatchesCpu) : "unavailable";
        boolean shaderQuadMatchesBound = false;
        if (this.realLodProbeBoundGeometryReadbackAvailable && gpuSnapshot != null && gpuSnapshot.available() && gpuSnapshot.magicValid()) {
            shaderQuadMatchesBound = Long.toUnsignedString(this.realLodProbeBoundGeometryRawQuadData).equals(Long.toUnsignedString(gpuSnapshot.rawQuad()));
        }
        this.realLodProbeShaderRawQuadMatchesBoundGeometry = this.realLodProbeBoundGeometryReadbackAvailable ? String.valueOf(shaderQuadMatchesBound) : "unavailable";
        boolean boundMetaIsZero = this.realLodProbeBoundMetadataReadbackAvailable
                && this.realLodProbeBoundMetadataSectionMetaA0 == 0
                && this.realLodProbeBoundMetadataSectionMetaA1 == 0
                && this.realLodProbeBoundMetadataSectionMetaA2 == 0
                && this.realLodProbeBoundMetadataSectionMetaA3 == 0
                && this.realLodProbeBoundMetadataSectionMetaB0 == 0
                && this.realLodProbeBoundMetadataSectionMetaB1 == 0
                && this.realLodProbeBoundMetadataSectionMetaB2 == 0
                && this.realLodProbeBoundMetadataSectionMetaB3 == 0;
        boolean boundGeomIsZero = this.realLodProbeBoundGeometryReadbackAvailable && this.realLodProbeBoundGeometryRawQuadData == 0L;
        boolean gpuAvailable = gpuSnapshot != null && gpuSnapshot.available() && gpuSnapshot.magicValid();
        if (!this.realLodProbeBoundMetadataReadbackAvailable) {
            this.realLodProbeBufferContentMismatchReason = "metadata_descriptor_range_or_offset_mismatch";
        } else if (!this.realLodProbeBoundGeometryReadbackAvailable) {
            this.realLodProbeBufferContentMismatchReason = "geometry_descriptor_range_or_offset_mismatch";
        } else if (boundMetaIsZero) {
            this.realLodProbeBufferContentMismatchReason = "metadata_bound_buffer_zero";
        } else if (boundGeomIsZero) {
            this.realLodProbeBufferContentMismatchReason = "geometry_bound_buffer_zero";
        } else if (!boundMetaMatchesCpu) {
            this.realLodProbeBufferContentMismatchReason = "metadata_bound_buffer_differs_from_cpu_snapshot";
        } else if (!boundGeomMatchesCpu) {
            this.realLodProbeBufferContentMismatchReason = "geometry_bound_buffer_differs_from_cpu_snapshot";
        } else if (gpuAvailable && !shaderMetaMatchesBound) {
            this.realLodProbeBufferContentMismatchReason = "metadata_shader_read_differs_from_bound_buffer";
        } else if (gpuAvailable && !shaderQuadMatchesBound) {
            this.realLodProbeBufferContentMismatchReason = "geometry_shader_read_differs_from_bound_buffer";
        } else {
            this.realLodProbeBufferContentMismatchReason = "none";
        }
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-bound-buffer-content", "section draw real LOD bound buffer content diagnostics:"
                + " realLodProbeBoundMetadataReadbackAvailable=" + this.realLodProbeBoundMetadataReadbackAvailable
                + ", boundMetadataSectionId=" + sectionId
                + ", boundMetadataByteOffset=" + sectionByteOffset
                + ", boundMetadataSectionMetaA0=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaA0) : "unavailable")
                + ", boundMetadataSectionMetaA1=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaA1) : "unavailable")
                + ", boundMetadataSectionMetaA2=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaA2) : "unavailable")
                + ", boundMetadataSectionMetaA3=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaA3) : "unavailable")
                + ", boundMetadataSectionMetaB0=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaB0) : "unavailable")
                + ", boundMetadataSectionMetaB1=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaB1) : "unavailable")
                + ", boundMetadataSectionMetaB2=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaB2) : "unavailable")
                + ", boundMetadataSectionMetaB3=" + (this.realLodProbeBoundMetadataReadbackAvailable ? Integer.toUnsignedString(this.realLodProbeBoundMetadataSectionMetaB3) : "unavailable")
                + ", realLodProbeBoundMetadataMatchesCpuSnapshot=" + this.realLodProbeBoundMetadataMatchesCpuSnapshot
                + ", realLodProbeShaderMetadataMatchesBoundMetadata=" + this.realLodProbeShaderMetadataMatchesBoundMetadata
                + ", realLodProbeBoundGeometryReadbackAvailable=" + this.realLodProbeBoundGeometryReadbackAvailable
                + ", boundGeometryQuadIndex=" + quadIndex
                + ", boundGeometryByteOffset=" + quadByteOffset
                + ", realLodProbeBoundGeometryRawQuadData=" + (this.realLodProbeBoundGeometryReadbackAvailable ? "0x" + Long.toHexString(this.realLodProbeBoundGeometryRawQuadData) + "/" + Long.toUnsignedString(this.realLodProbeBoundGeometryRawQuadData) : "unavailable")
                + ", realLodProbeBoundGeometryRawQuadMatchesCpuSnapshot=" + this.realLodProbeBoundGeometryRawQuadMatchesCpuSnapshot
                + ", realLodProbeShaderRawQuadMatchesBoundGeometry=" + this.realLodProbeShaderRawQuadMatchesBoundGeometry
                + ", realLodProbeBufferContentMismatchReason=" + this.realLodProbeBufferContentMismatchReason, 60);
    }


    private void bindComputeStorageBinding(int binding, Buffer buffer, String label) {
        if (buffer == null) throw new IllegalStateException(label + " must not be null");
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(binding, label, bufferSize);
        }
        UBO ubo = this.commandGenPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) throw new IllegalStateException("Section cmdgen descriptor missing: name=" + label + ", binding=" + binding + ", config=" + CMDGEN_SHADER_CONFIG);
        String descriptorKind = descriptorKind(ubo);
        if (!"storageBuffer".equals(descriptorKind)) {
            throw new IllegalStateException("Section cmdgen descriptor kind mismatch: name=" + label + ", binding=" + binding + ", descriptorKind=" + descriptorKind + ", expected=storageBuffer, config=" + CMDGEN_SHADER_CONFIG);
        }
        if (binding == CMDGEN_CONFIG_BINDING && buffer == this.cmdGenConfigBuffer && bufferSize != CMDGEN_CONFIG_SIZE_BYTES) {
            throw new IllegalStateException("cmdGenConfigBuffer descriptor range mismatch: binding=" + binding + ", bufferBytes=" + bufferSize + ", expectedRangeBytes=" + CMDGEN_CONFIG_SIZE_BYTES);
        }
        int rangeBytes = (int) bufferSize;
        if (binding == CMDGEN_DRAW_COMMAND_BINDING && buffer == this.drawCommandBuffer) {
            rangeBytes = validateDrawCommandBinding3DescriptorRange(bufferSize, label);
            this.lastCmdgenBinding3ReboundGeneration = this.drawCommandBufferAllocationGeneration;
        }
        VulkanBerylDebugLog.trace("binding-cmdgen-descriptor:" + binding + ":" + label, "Binding cmdgen descriptor: binding=" + binding + ", label=" + label + ", bufferBytes=" + bufferSize + ", finalRangeBytes=" + rangeBytes);
        ubo.getBufferSlice().set(buffer, 0L, rangeBytes);
    }



    private int validateDrawCommandBinding3DescriptorRange(long bufferSize, String label) {
        long expectedBytes = drawCommandBinding3ExpectedBytes();
        if (this.drawCommandCapacity <= 0) {
            throw new IllegalStateException("drawCommandBuffer descriptor range cannot be validated before drawCommandCapacity is initialized: binding="
                    + CMDGEN_DRAW_COMMAND_BINDING + ", label=" + label + ", bufferBytes=" + bufferSize);
        }
        if (bufferSize != expectedBytes) {
            throw new IllegalStateException("drawCommandBuffer descriptor range mismatch: binding="
                    + CMDGEN_DRAW_COMMAND_BINDING
                    + ", label=" + label
                    + ", bufferBytes=" + bufferSize
                    + ", expectedBytes=" + expectedBytes
                    + ", drawCommandCapacity=" + this.drawCommandCapacity
                    + ", drawCommandStrideBytes=" + DRAW_COMMAND_STRIDE_BYTES);
        }
        if (expectedBytes > VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES) {
            throw descriptorRangeException(CMDGEN_DRAW_COMMAND_BINDING, label, expectedBytes);
        }
        return Math.toIntExact(expectedBytes);
    }


    private long drawCommandBinding3ExpectedBytes() {
        return Math.multiplyExact((long) this.drawCommandCapacity, DRAW_COMMAND_STRIDE_BYTES);
    }


    private void updateAndBindCmdGenConfigBuffer(VkCommandBuffer commandBuffer, VulkanBerylSectionGeometryData geometryData, int renderListCapacity, int flags) {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("commandBuffer must not be null");
        }
        ensureCmdGenConfigBuffer();
        this.lastCmdGenConfigMetadataSectionCapacity = geometryData.getMaxSectionCount();
        this.lastCmdGenConfigRenderListCapacity = renderListCapacity;
        this.lastCmdGenConfigGeometryCapacityQuads = Math.toIntExact(geometryData.getGeometryCapacityBytes() / 8L);
        this.lastCmdGenConfigDrawCommandCapacity = this.drawCommandCapacity;
        Buffer drawCountDescriptorBuffer = cmdgenDrawCountDescriptorBuffer();
        this.lastCmdGenConfigDrawCountCapacityWords = (int) (drawCountDescriptorBuffer == null ? 0L : drawCountDescriptorBuffer.getBufferSize() / Integer.BYTES);
        this.lastCmdGenConfigFlags = flags;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var words = stack.ints(
                    this.lastCmdGenConfigMetadataSectionCapacity,
                    this.lastCmdGenConfigRenderListCapacity,
                    this.lastCmdGenConfigGeometryCapacityQuads,
                    this.lastCmdGenConfigDrawCommandCapacity,
                    this.lastCmdGenConfigDrawCountCapacityWords,
                    this.lastCmdGenConfigFlags
            );
            VK10.vkCmdUpdateBuffer(commandBuffer, this.cmdGenConfigBuffer.getId(), 0L, words);
        }
        this.cmdGenConfigUploaded = true;
        logCmdGenConfigBufferState("uploaded_same_command_buffer");
        bindComputeStorageBinding(CMDGEN_CONFIG_BINDING, this.cmdGenConfigBuffer, "cmdGenConfigBuffer");
    }


    private void logDrawCountAliasAndLifetimeDiagnostics(VulkanBerylSectionGeometryData geometryData, VulkanBerylViewportRenderList renderList, String stage, boolean indirectAllowed) {
        if (!isExplicitCmdgenShaderSelectionDiagnosticActive()) return;
        long renderListId = renderList == null || renderList.getBuffer() == null ? 0L : renderList.getBuffer().getId();
        long metadataId = geometryData == null || geometryData.getMetadataBuffer() == null ? 0L : geometryData.getMetadataBuffer().getId();
        long geometryId = geometryData == null || geometryData.getGeometryBuffer() == null ? 0L : geometryData.getGeometryBuffer().getId();
        long commandsId = this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId();
        long unusedBinding2Id = this.cmdGenUnusedBinding2Buffer == null ? 0L : this.cmdGenUnusedBinding2Buffer.getId();
        long configId = this.cmdGenConfigBuffer == null ? 0L : this.cmdGenConfigBuffer.getId();
        long drawCountId = this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId();
        boolean aliasesRenderList = drawCountId != 0L && drawCountId == renderListId;
        boolean aliasesMetadata = drawCountId != 0L && drawCountId == metadataId;
        boolean aliasesGeometry = drawCountId != 0L && drawCountId == geometryId;
        boolean aliasesCommands = drawCountId != 0L && drawCountId == commandsId;
        boolean aliasesUnusedBinding2 = drawCountId != 0L && drawCountId == unusedBinding2Id;
        boolean aliasesConfig = drawCountId != 0L && drawCountId == configId;
        boolean aliasesAny = aliasesRenderList || aliasesMetadata || aliasesGeometry || aliasesCommands || aliasesUnusedBinding2 || aliasesConfig;
        String reuseState;
        if (this.lastDrawCountRenderFrameBufferId == 0L) {
            reuseState = "first_observed";
        } else if (this.lastDrawCountRenderFrameBufferId == drawCountId) {
            reuseState = "reused";
        } else {
            reuseState = "recreated_since_previous_frame";
        }
        this.lastDrawCountRenderFrameBufferId = drawCountId;
        VulkanBerylDebugLog.rateLimited("cmdgen-drawcount-alias-lifetime", "cmdgen drawCount alias/lifetime diagnostics: stage=" + stage
                + ", renderListBufferId=" + renderListId
                + ", metadataBufferId=" + metadataId
                + ", geometryBufferId=" + geometryId
                + ", commandsBufferId=" + commandsId
                + ", unusedBinding2BufferId=" + unusedBinding2Id
                + ", configBufferId=" + configId
                + ", drawCountBufferId=" + drawCountId
                + ", drawCountEqualsAnyOtherCmdgenOrRenderBuffer=" + aliasesAny
                + ", equalsRenderList=" + aliasesRenderList
                + ", equalsMetadata=" + aliasesMetadata
                + ", equalsGeometry=" + aliasesGeometry
                + ", equalsCommands=" + aliasesCommands
                + ", equalsUnusedBinding2=" + aliasesUnusedBinding2
                + ", equalsConfig=" + aliasesConfig
                + ", allocationGeneration=" + this.drawCountAllocationGeneration
                + ", frameReuseState=" + reuseState
                + ", realDrawCountUsesScratchAllocationPath=" + this.lastDrawCountAllocationUsedScratchPath
                + ", oldRealDrawCountBufferStillExists=" + this.lastOldRealDrawCountBufferStillExists
                + ", indirectDrawAllowedThisFrame=" + indirectAllowed
                + ", indirectDrawEnvEnabled=" + ENABLE_INDIRECT_DRAW
                + ", indirectDisabledOldDrawCountReferenceCheck=" + (!ENABLE_INDIRECT_DRAW ? "graphics_indirect_submit_disabled;drawCountConsumers=" + (disableAnyDrawCountConsumerPathActive() ? "disabled_by_env" : "debug_readback_or_descriptor_state_may_still_reference_current_drawCount") : "indirect_enabled"), 1);
    }


    private void logDrawCountConsumerPathDisabled(int visibleCount, boolean indirectAllowed, boolean debugReadbackRequested) {
        VulkanBerylDebugLog.once("cmdgen-disable-any-drawcount-consumer-path", "cmdgen drawCount consumer paths disabled after cmdgen dispatch: env=VOXY_VULKAN_BERYL_CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH"
                + ", shaderSelectionEnv=" + activeCmdgenShaderSelectionEnvVar()
                + ", keptCmdgenDispatchEnabled=true"
                + ", skippedDebugReadbackConsumePending=true"
                + ", skippedDebugReadbackCopyDrawCommands=true"
                + ", skippedDebugReadbackCopyDrawCount=true"
                + ", skippedLodSampleConsumption=true"
                + ", skippedGraphicsPipelineBind=true"
                + ", skippedGraphicsDescriptorBind=true"
                + ", skippedIndirectDrawSubmit=true"
                + ", skippedDrawCountBindingReadOrSubmit=true"
                + ", visibleCount=" + visibleCount
                + ", indirectAllowedBeforeSkip=" + indirectAllowed
                + ", debugReadbackRequestedBeforeSkip=" + debugReadbackRequested
                + ", drawCountBufferId=" + (this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId())
                + ", drawCommandBufferId=" + (this.drawCommandBuffer == null ? 0L : this.drawCommandBuffer.getId()));
    }


    private void logDrawCountBufferDiagnostics(String stage, boolean descriptorRangeValidExpected) {
        Buffer descriptorBuffer = cmdgenDrawCountDescriptorBuffer();
        long capacityBytes = this.drawCountBuffer == null ? -1L : this.drawCountBuffer.getBufferSize();
        long bufferId = this.drawCountBuffer == null ? 0L : this.drawCountBuffer.getId();
        long descriptorCapacityBytes = descriptorBuffer == null ? -1L : descriptorBuffer.getBufferSize();
        long descriptorBufferId = descriptorBuffer == null ? 0L : descriptorBuffer.getId();
        long descriptorRangeBytes = descriptorCapacityBytes;
        long requiredRangeBytes = Integer.BYTES;
        boolean descriptorRangeValid = descriptorRangeValidExpected && descriptorCapacityBytes >= requiredRangeBytes && descriptorCapacityBytes <= VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES;
        int javaSideCapacityWords = descriptorCapacityBytes <= 0L ? 0 : (int) (descriptorCapacityBytes / Integer.BYTES);
        boolean capacityMatchesConfig = javaSideCapacityWords == this.lastCmdGenConfigDrawCountCapacityWords;
        VulkanBerylDebugLog.once("cmdgen-drawcount-buffer-state:" + stage, "drawCountBuffer state: stage=" + stage
                + ", bufferId=" + bufferId
                + ", handle=" + bufferId
                + ", capacityBytes=" + capacityBytes
                + ", usageFlags=" + this.drawCountBufferUsageFlags
                + ", usage=" + bufferUsageString(this.drawCountBufferUsageFlags)
                + ", descriptorBinding=" + CMDGEN_DRAW_COUNT_BINDING
                + ", descriptorBufferId=" + descriptorBufferId
                + ", descriptorHandle=" + descriptorBufferId
                + ", descriptorLabel=" + drawCountDescriptorLabel()
                + ", descriptorRangeBytes=" + descriptorRangeBytes
                + ", requiredRangeBytes=" + requiredRangeBytes
                + ", descriptorRangeValid=" + descriptorRangeValid
                + ", descriptorRangeFullBufferRequested=" + drawCountFullDescriptorRangeActive()
                + ", existingRealDrawCountAbstractionOnlyFourBytes=" + (capacityBytes == Integer.BYTES)
                + ", hasStorageUsage=" + ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0)
                + ", hasTransferDstUsage=" + ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_DST_BIT) != 0)
                + ", hasTransferSrcUsage=" + ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0)
                + ", hasIndirectUsage=" + ((this.drawCountBufferUsageFlags & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0)
                + ", descriptorReboundThisFrame=" + this.cmdgenDescriptorsReboundThisFrame
                + ", drawCountClearedInitialisedThisFrame=" + this.drawCountClearedThisFrame
                + ", javaSideDrawCountCapacityWords=" + javaSideCapacityWords
                + ", configDrawCountCapacityWords=" + this.lastCmdGenConfigDrawCountCapacityWords
                + ", javaSideDrawCountCapacityMatchesDescriptorCapacity=" + capacityMatchesConfig);
    }


    private static boolean isExplicitCmdgenDiagnosticEnvActive() {
        return activeCmdgenShaderSelectionEnvVar() != null
                || !activeCmdgenProbeEnvVars().isEmpty()
                || CMDGEN_DUMP_SHADER_DIAGNOSTICS
                || CMDGEN_DEBUG_READBACK
                || CMDGEN_DISPATCH_NOOP
                || CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE
                || CMDGEN_CREATE_ONLY
                || CMDGEN_UPLOAD_CONFIG_ONLY
                || CMDGEN_CLEAR_OUTPUTS_ONLY
                || CMDGEN_BIND_FULL_ONLY;
    }


    private static String explicitCmdgenDiagnosticEnvSummary() {
        String shaderEnv = activeCmdgenShaderSelectionEnvVar();
        if (shaderEnv != null) return shaderEnv;
        List<String> probeEnvs = activeCmdgenProbeEnvVars();
        if (!probeEnvs.isEmpty()) return probeEnvs.toString();
        if (CMDGEN_DUMP_SHADER_DIAGNOSTICS) return "VOXY_VULKAN_BERYL_CMDGEN_DUMP_SHADER_DIAGNOSTICS";
        if (CMDGEN_DEBUG_READBACK) return "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK";
        return "cmdgen_diagnostic_mode";
    }


    private static void logCmdgenWaitIdleAfterDispatchState(String stage) {
        if (!CMDGEN_WAIT_IDLE_AFTER_DISPATCH) return;
        if (!isExplicitCmdgenDiagnosticEnvActive()) return;
        VulkanBerylDebugLog.once("cmdgen-wait-idle-after-dispatch", "cmdgen wait-idle-after-dispatch requested after dispatch record: stage=" + stage
                + ", result=recorded_command_buffer_not_yet_submitted"
                + ", note=VulkanMod owns the render command-buffer submit; VK_ERROR_DEVICE_LOST may still surface at the following submit");
    }


    private void logMetadataBufferState(VulkanBerylSectionGeometryData geometryData, String stage, Buffer boundMetadataBuffer, int binding) {
        long boundSize = boundMetadataBuffer == null ? -1L : boundMetadataBuffer.getBufferSize();
        VulkanBerylDebugLog.once("cmdgen-metadata-buffer-state:" + stage, "metadata buffer state: stage=" + stage
                + ", binding=" + binding
                + ", boundBufferId=" + (boundMetadataBuffer == null ? 0L : boundMetadataBuffer.getId())
                + ", boundCapacityBytes=" + boundSize
                + ", boundUsage=" + (boundMetadataBuffer == geometryData.getMetadataBuffer() ? geometryData.getMetadataUsageString() : "STORAGE|TRANSFER_DST")
                + ", realMetadataBufferId=" + geometryData.getMetadataBuffer().getId()
                + ", realMetadataCapacityBytes=" + geometryData.getMetadataCapacityBytes()
                + ", realMetadataUsage=" + geometryData.getMetadataUsageString()
                + ", storageBufferCapable=" + geometryData.isMetadataStorageBufferCapable()
                + ", descriptorRangeValid=" + (boundSize >= VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE && boundSize <= VulkanBerylSectionGeometryData.MAX_VULKANMOD_BERYL_DESCRIPTOR_RANGE_BYTES)
                + ", requiredRangeBytes=" + VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE
                + ", sectionMetaStrideBytes=" + VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE
                + ", maxSectionCount=" + geometryData.getMaxSectionCount()
                + ", sectionCount=" + geometryData.getSectionCount()
                + ", mirrorWriteCount=" + geometryData.getSectionMetadataMirrorWriteCount());
    }


    private void logCmdGenConfigBufferState(String stage) {
        String logKey = "cmdgen-config-buffer-state:" + stage;
        if (this.cmdGenConfigBuffer == null) {
            VulkanBerylDebugLog.once(logKey, "cmdGenConfigBuffer state: stage=" + stage + ", buffer=null");
            return;
        }
        VulkanBerylDebugLog.once(logKey, "cmdGenConfigBuffer state: stage=" + stage
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


    private void bindSceneUniform(VkCommandBuffer commandBuffer, VulkanBerylViewport viewport) {
        if (commandBuffer == null) throw new IllegalStateException("Section draw command buffer is unavailable for SceneUniform upload");
        UBO ubo = this.graphicsPipeline.getUBO(candidate -> candidate.binding == SCENE_UNIFORM_BINDING);
        if (ubo == null) throw new IllegalStateException("Section draw descriptor missing: name=SceneUniform, binding=0, config=" + DRAW_SHADER_CONFIG);
        Buffer uniformBuffer = ubo.getBufferSlice().getBuffer();
        if (uniformBuffer == null) throw new IllegalStateException("Section draw SceneUniform buffer is not bound");
        if (uniformBuffer.getBufferSize() < SCENE_UNIFORM_SIZE_BYTES) {
            throw new IllegalStateException("Section draw SceneUniform buffer is too small: bufferSizeBytes=" + uniformBuffer.getBufferSize() + ", requiredBytes=" + SCENE_UNIFORM_SIZE_BYTES);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var uniformData = stack.calloc(SCENE_UNIFORM_SIZE_BYTES);
            long ptr = MemoryUtil.memAddress(uniformData);
            var mat = new org.joml.Matrix4f(viewport.MVP);
            mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
            mat.getToAddress(ptr);
            ptr += 4L * 4L * 4L;
            MemoryUtil.memPutInt(ptr, viewport.section.x);
            MemoryUtil.memPutInt(ptr + 4L, viewport.section.y);
            MemoryUtil.memPutInt(ptr + 8L, viewport.section.z);
            ptr += 12L;
            MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff);
            ptr += 4L;
            MemoryUtil.memPutFloat(ptr, viewport.innerTranslation.x);
            MemoryUtil.memPutFloat(ptr + 4L, viewport.innerTranslation.y);
            MemoryUtil.memPutFloat(ptr + 8L, viewport.innerTranslation.z);
            ptr += 12L;
            MemoryUtil.memPutFloat(ptr, 0.0F);
            ptr += 4L;
            MemoryUtil.memPutInt(ptr, this.realLodProbeSceneUniformForcedQuadIndex);
            MemoryUtil.memPutInt(ptr + 4L, this.realLodProbeCpuSelectedSectionId);
            MemoryUtil.memPutInt(ptr + 8L, (int) this.realLodProbeSelectedSectionPassQuadStart);
            int realLodProbeFlags = (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SELECTED_QUAD ? 1 : 0)
                    | (this.realLodProbeReplayCpuClipAvailable ? 2 : 0)
                    | (this.realLodProbeReplayCpuWorldAvailable ? 4 : 0)
                    | (REAL_LOD_SINGLE_QUAD_WORLD_PROBE_FORCE_CPU_SECTION_AND_QUAD ? 8 : 0);
            MemoryUtil.memPutInt(ptr + 12L, realLodProbeFlags);
            ptr += 16L;
            for (int i = 0; i < this.realLodProbeReplayCpuClip.length; i++) {
                MemoryUtil.memPutFloat(ptr + (long) i * Float.BYTES, this.realLodProbeReplayCpuClip[i]);
            }
            ptr += (long) this.realLodProbeReplayCpuClip.length * Float.BYTES;
            for (int i = 0; i < this.realLodProbeReplayCpuWorld.length; i++) {
                MemoryUtil.memPutFloat(ptr + (long) i * Float.BYTES, this.realLodProbeReplayCpuWorld[i]);
            }
            VK10.vkCmdUpdateBuffer(commandBuffer, uniformBuffer.getId(), ubo.getBufferSlice().getOffset(), uniformData);
            VkMemoryBarrier.Buffer transferToVertex = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_UNIFORM_READ_BIT);
            VK10.vkCmdPipelineBarrier(commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0, transferToVertex, null, null);
        }
        this.sectionDrawBinding0DescriptorKind = descriptorKind(ubo);
        this.sceneUniformBound = true;
        logRealLodProbeReplayCpuClipUniformUpload();
        logRealLodProbeReplayCpuWorldUniformUpload();
    }


    private void logRealLodProbeReplayCpuClipUniformUpload() {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_CLIP) return;
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-replay-cpu-clip-upload", "section draw real LOD replay CPU clip uniform upload:"
                + " realLodProbeGpuVertexMode=" + realLodProbeGpuVertexMode()
                + ", realLodProbeReplayCpuClipAvailable=" + this.realLodProbeReplayCpuClipAvailable
                + ", realLodProbeReplayCpuClip0=" + this.realLodProbeReplayCpuClip0
                + ", realLodProbeReplayCpuClip1=" + this.realLodProbeReplayCpuClip1
                + ", realLodProbeReplayCpuClip2=" + this.realLodProbeReplayCpuClip2
                + ", realLodProbeReplayCpuClip3=" + this.realLodProbeReplayCpuClip3
                + ", realLodProbeReplayCpuClipSource=cpu_final_clip"
                + ", realLodProbeReplayCpuClipUnavailableReason=" + this.realLodProbeReplayCpuClipUnavailableReason
                + ", realLodProbeReplayCpuClipUniformUploadPath=SceneUniform.binding0.vkCmdUpdateBuffer"
                + ", realLodProbeReplayCpuClipUniformLayout=std140 SceneUniform offsets: realLodProbeData@" + SCENE_UNIFORM_REAL_LOD_PROBE_DATA_OFFSET_BYTES + " uvec4 flags.w bit1=replay_available, replayCpuClip0..3@" + SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_CLIP_OFFSET_BYTES + "/128/144/160 vec4, sizeBytes=" + SCENE_UNIFORM_SIZE_BYTES, 60);
    }


    private void logRealLodProbeReplayCpuWorldUniformUpload() {
        if (!REAL_LOD_SINGLE_QUAD_WORLD_PROBE_REPLAY_CPU_WORLD) return;
        VulkanBerylDebugLog.rateLimited("section-draw-real-lod-replay-cpu-world-upload", "section draw real LOD replay CPU world uniform upload:"
                + " realLodProbeGpuVertexMode=" + realLodProbeGpuVertexMode()
                + ", realLodProbeReplayCpuWorldAvailable=" + this.realLodProbeReplayCpuWorldAvailable
                + ", realLodProbeReplayCpuWorld0=" + this.realLodProbeReplayCpuWorld0
                + ", realLodProbeReplayCpuWorld1=" + this.realLodProbeReplayCpuWorld1
                + ", realLodProbeReplayCpuWorld2=" + this.realLodProbeReplayCpuWorld2
                + ", realLodProbeReplayCpuWorld3=" + this.realLodProbeReplayCpuWorld3
                + ", realLodProbeReplayCpuWorldCoordinateSpace=" + this.realLodProbeReplayCpuWorldCoordinateSpace
                + ", realLodProbeReplayCpuWorldUnavailableReason=" + this.realLodProbeReplayCpuWorldUnavailableReason
                + ", realLodProbeReplayCpuWorldUsesSameMvp=true"
                + ", realLodProbeReplayCpuWorldUsesSameSubmittedDrawCount=true"
                + ", realLodProbeReplayCpuWorldUsesSamePipeline=true"
                + ", realLodProbeReplayCpuWorldUsesSameDescriptorSets=true"
                + ", realLodProbeReplayCpuWorldUniformUploadPath=SceneUniform.binding0.vkCmdUpdateBuffer"
                + ", realLodProbeReplayCpuWorldUniformLayout=" + realLodProbeUniformLayoutSummary(), 60);
    }


    private static String realLodProbeUniformLayoutSummary() {
        return "std140 SceneUniform offsets: realLodProbeData@" + SCENE_UNIFORM_REAL_LOD_PROBE_DATA_OFFSET_BYTES
                + " uvec4 flags.w bit1=replay_cpu_clip_available bit2=replay_cpu_world_available bit3=force_cpu_section_and_quad"
                + ", replayCpuClip0..3@" + SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_CLIP_OFFSET_BYTES + "/128/144/160 vec4"
                + ", replayCpuWorld0..3@" + SCENE_UNIFORM_REAL_LOD_REPLAY_CPU_WORLD_OFFSET_BYTES + "/192/208/224 vec4"
                + ", sizeBytes=" + SCENE_UNIFORM_SIZE_BYTES;
    }


    private void logSectionDrawBindingState(String drawSubmitReason) {
        UBO sceneUbo = this.graphicsPipeline == null ? null : this.graphicsPipeline.getUBO(candidate -> candidate.binding == SCENE_UNIFORM_BINDING);
        Buffer sceneBuffer = sceneUbo == null ? null : sceneUbo.getBufferSlice().getBuffer();
        boolean sceneBound = sceneBuffer != null && sceneBuffer.getId() != 0L && sceneBuffer.getBufferSize() >= SCENE_UNIFORM_SIZE_BYTES;
        String descriptorKind = sceneUbo == null ? "unknown" : descriptorKind(sceneUbo);
        this.sectionDrawBinding0DescriptorKind = descriptorKind;
        boolean bindingsReady = this.resourcesBound && sceneBound && "uniformBuffer".equals(descriptorKind);
        VulkanBerylDebugLog.rateLimited("section-draw-bindings-ready:" + drawSubmitReason, "section draw bindings: sectionDrawSceneUniformRequired=true"
                + ", sectionDrawSceneUniformBound=" + sceneBound
                + ", sectionDrawSceneUniformBufferId=" + (sceneBuffer == null ? 0L : sceneBuffer.getId())
                + ", sectionDrawSceneUniformBufferSizeBytes=" + (sceneBuffer == null ? 0L : sceneBuffer.getBufferSize())
                + ", sectionDrawBinding0DescriptorKind=" + descriptorKind
                + ", sectionDrawBindingsReady=" + bindingsReady
                + ", drawSubmitReason=" + drawSubmitReason, 30);
    }


    private static String descriptorKind(UBO descriptor) {
        if (descriptor == null) return "unknown";
        int type = descriptor.getType();
        if (type == VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER || type == VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC) return "uniformBuffer";
        if (type == VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || type == VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC) return "storageBuffer";
        return "unknown";
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

}
