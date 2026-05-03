package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;

public final class MDICViewportDepthResources {
    public final HiZBuffer hiZBuffer;
    public final DepthFramebuffer depthBoundingBuffer = new DepthFramebuffer();

    public MDICViewportDepthResources(RenderProperties properties) {
        this.hiZBuffer = new HiZBuffer(properties);
    }

    public void update(int width, int height, RenderProperties properties) {
        if (this.depthBoundingBuffer.resize(width, height)) {
            this.depthBoundingBuffer.clear(properties.inverseClearDepth());
        }
    }

    public void free() {
        this.hiZBuffer.free();
        this.depthBoundingBuffer.free();
    }
}
