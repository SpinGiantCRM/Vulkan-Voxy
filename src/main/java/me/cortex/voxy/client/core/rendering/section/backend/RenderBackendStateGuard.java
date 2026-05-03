package me.cortex.voxy.client.core.rendering.section.backend;

@FunctionalInterface
public interface RenderBackendStateGuard extends AutoCloseable {
    RenderBackendStateGuard NO_OP = () -> { };

    @Override
    void close();
}
