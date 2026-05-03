package me.cortex.voxy.client.core.rendering.section.backend;

public interface RenderFrameContext extends AutoCloseable {
    int sourceWidth();
    int sourceHeight();

    @Override
    void close();
}
