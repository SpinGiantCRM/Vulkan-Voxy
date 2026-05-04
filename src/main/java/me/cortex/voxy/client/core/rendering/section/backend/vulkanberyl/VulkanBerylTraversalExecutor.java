package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.resources.ResourceLocation;
import net.beryl.render.ComputePipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.lwjgl.vulkan.VK10;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VulkanBerylTraversalExecutor {
    public static final String TRAVERSAL_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/hierarchical/traversal.comp";
    private static final String TRAVERSAL_SHADER_NAME = "vulkanberyl/hierarchical/traversal";
    private static final String TRAVERSAL_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/hierarchical/traversal.json";

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

    public String getTraversalShaderResource() {
        return TRAVERSAL_SHADER_RESOURCE;
    }

    public ResourceLocation getTraversalShaderResourceLocation() {
        return ResourceLocation.parse(TRAVERSAL_SHADER_RESOURCE);
    }

    public void ensureTraversalPipeline() {
        if (this.freed) throw new IllegalStateException("traversal executor is freed");
        requireLiveResources();
        if (this.traversalPipeline != null) {
            return;
        }

        URL shaderRootUrl = VulkanBerylTraversalExecutor.class.getResource("/assets/voxy/shaders");
        if (shaderRootUrl == null) throw new IllegalStateException("Unable to locate /assets/voxy/shaders for traversal compute pipeline");
        URL configUrl = VulkanBerylTraversalExecutor.class.getResource(TRAVERSAL_SHADER_CONFIG);
        if (configUrl == null) throw new IllegalStateException("Missing traversal compute shader config: " + TRAVERSAL_SHADER_CONFIG);

        ComputePipeline.Builder builder = new ComputePipeline.Builder(TRAVERSAL_SHADER_RESOURCE);
        JsonObject config;
        try (InputStreamReader reader = new InputStreamReader(configUrl.openStream(), StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load traversal compute shader config: " + TRAVERSAL_SHADER_CONFIG, e);
        }

        builder.parseBindings(config);
        builder.compileShader(shaderRootUrl.toExternalForm(), TRAVERSAL_SHADER_NAME);
        ComputePipeline pipeline = builder.createPipeline();
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

        bindStorageBinding(SCENE_UNIFORM_BINDING, this.traversalResources.getUniformBuffer(), "traversalResources.uniformBuffer");
        bindStorageBinding(REQUEST_QUEUE_BINDING, this.traversalResources.getRequestBuffer(), "traversalResources.requestBuffer");
        bindStorageBinding(RENDER_QUEUE_BINDING, this.renderList.getBuffer(), "renderList.buffer");
        bindStorageBinding(NODE_DATA_BINDING, this.nodeMetadataStore.getNodeBuffer(), "nodeMetadataStore.nodeBuffer");
        bindStorageBinding(NODE_QUEUE_INDEX_BINDING, this.traversalResources.getQueueIndexBuffer(), "traversalResources.queueIndexBuffer");
        bindStorageBinding(NODE_QUEUE_META_BINDING, this.traversalResources.getQueueMetaBuffer(), "traversalResources.queueMetaBuffer");
        bindStorageBinding(NODE_QUEUE_SOURCE_BINDING, this.traversalResources.getScratchQueueA(), "traversalResources.scratchQueueA");
        bindStorageBinding(NODE_QUEUE_SINK_BINDING, this.traversalResources.getScratchQueueB(), "traversalResources.scratchQueueB");
        bindStorageBinding(RENDER_TRACKER_BINDING, this.traversalResources.getRenderTrackerBuffer(), "traversalResources.renderTrackerBuffer");
        this.descriptorsBound = true;
    }

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
        requireMethod(VK10.class, "vkCmdDispatch", missing, org.lwjgl.vulkan.VkCommandBuffer.class, int.class, int.class, int.class);

        if (!missing.isEmpty()) {
            throw new UnsupportedOperationException("Vulkan/Beryl traversal compute dispatch integration missing required API: " + String.join(", ", missing));
        }
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

    private void bindStorageBinding(int binding, Buffer buffer, String label) {
        requireBuffer(label, buffer);
        UBO ubo = this.traversalPipeline.getUBO(candidate -> candidate.binding == binding);
        if (ubo == null) {
            throw new IllegalStateException("Traversal descriptor binding " + binding + " is missing from traversal.json");
        }
        long bufferSize = buffer.getBufferSize();
        if (bufferSize <= 0L || bufferSize > Integer.MAX_VALUE) {
            throw new IllegalStateException(label + " has invalid descriptor size: " + bufferSize);
        }
        ubo.getBufferSlice().set(buffer, 0L, (int) bufferSize);
    }
}
