package me.cortex.voxy.client.core.rendering.section.backend;

public interface RenderFrameContext extends AutoCloseable {
    int sourceFrameBuffer();
    int sourceWidth();
    int sourceHeight();

    @Override
    void close();
}
