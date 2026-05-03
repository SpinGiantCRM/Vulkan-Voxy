package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.client.core.rendering.ChunkBoundsRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;

public final class VulkanBerylChunkBoundsRenderer implements ChunkBoundsRenderer {
    private boolean freed;

    @Override
    public void addSection(long pos) {
    }

    @Override
    public void removeSection(long pos) {
    }

    @Override
    public void render(Viewport<?> viewport) {
        throw new UnsupportedOperationException("VULKANMOD_BERYL chunk bounds/depth pre-pass rendering is not implemented yet");
    }

    @Override
    public void reset() {
    }

    @Override
    public void free() {
        this.freed = true;
    }
}
