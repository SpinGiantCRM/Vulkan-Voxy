package me.cortex.voxy.client.core.rendering.section.backend;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface SectionRenderBackendRuntime {
    void lateStageCompile(AbstractRenderPipeline pipeline);

    void doPrimaryWork(Viewport<?> viewport, int depthBuffer, BooleanSupplier frexStillHasWork);

    default void addDebug(List<String> debug) {
    }

    void free();
}
