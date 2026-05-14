#define SENTINAL_OUT_OF_BOUNDS uint(-1)


#ifdef VOXY_VULKAN_BERYL_QUEUE_INDEX_SSBO
layout(binding = NODE_QUEUE_INDEX_BINDING, std430) restrict readonly buffer NodeQueueIndex {
    uint queueIdx;
};
#else
layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;
#endif

layout(binding = NODE_QUEUE_META_BINDING, std430) restrict buffer NodeQueueMeta {
    uvec4 nodeQueueMetadata[MAX_ITERATIONS];
};

layout(binding = NODE_QUEUE_SOURCE_BINDING, std430) restrict readonly buffer NodeQueueSource {
    uint[] nodeQueueSource;
};

layout(binding = NODE_QUEUE_SINK_BINDING, std430) restrict writeonly buffer NodeQueueSink {
    uint[] nodeQueueSink;
};

uint getCurrentNode() {
    if (queueIdx >= MAX_ITERATIONS) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    if (gl_GlobalInvocationID.x >= MAX_QUEUE_SIZE) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    uint queueCount = nodeQueueMetadata[queueIdx].w;
    if (queueCount > MAX_QUEUE_SIZE || queueCount <= gl_GlobalInvocationID.x) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    return nodeQueueSource[gl_GlobalInvocationID.x];
}


//TODO: limit the size/writing out of bounds
uint nodePushIndex = SENTINAL_OUT_OF_BOUNDS;
uint nodePushRemaining = 0u;
void pushNodesInit(uint nodeCount) {
    if (queueIdx >= (MAX_ITERATIONS - 1u)) {
        nodePushIndex = SENTINAL_OUT_OF_BOUNDS;
        nodePushRemaining = 0u;
        return;
    }

    uint index = atomicAdd(nodeQueueMetadata[queueIdx + 1u].w, nodeCount);
    if (index >= MAX_QUEUE_SIZE) {
        nodePushIndex = SENTINAL_OUT_OF_BOUNDS;
        nodePushRemaining = 0u;
        return;
    }

    uint writable = min(nodeCount, MAX_QUEUE_SIZE - index);
    nodePushIndex = index;
    nodePushRemaining = writable;

    uint inc = ((index + writable + LOCAL_SIZE - 1u) >> LOCAL_SIZE_BITS) - (index >> LOCAL_SIZE_BITS);
    if (inc != 0u) {
        atomicAdd(nodeQueueMetadata[queueIdx + 1u].x, inc);
    }
}

void pushNode(uint nodeId) {
    if (nodePushRemaining == 0u || nodePushIndex == SENTINAL_OUT_OF_BOUNDS) {
        return;
    }
    nodeQueueSink[nodePushIndex++] = nodeId;
    nodePushRemaining--;
}

#define SIMPLE_QUEUE(type, name, bindingIndex) \
layout(binding = bindingIndex, std430) restrict buffer name##Struct { \
    type name##Index; \
    type name[]; \
};
