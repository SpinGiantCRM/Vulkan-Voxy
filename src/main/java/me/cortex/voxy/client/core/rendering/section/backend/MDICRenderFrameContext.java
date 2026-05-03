package me.cortex.voxy.client.core.rendering.section.backend;

public record MDICRenderFrameContext(
        int sourceFrameBuffer,
        int sourceWidth,
        int sourceHeight,
        int previousViewportX,
        int previousViewportY,
        Runnable closeAction
) implements RenderFrameContext {
    @Override
    public void close() {
        this.closeAction.run();
    }
}
