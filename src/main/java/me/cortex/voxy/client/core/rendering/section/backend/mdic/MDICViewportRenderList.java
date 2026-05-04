package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.ViewportRenderList;

public record MDICViewportRenderList(GlBuffer glBuffer) implements ViewportRenderList {
    public static MDICViewportRenderList require(ViewportRenderList renderList) {
        if (renderList instanceof MDICViewportRenderList mdicRenderList) {
            return mdicRenderList;
        }
        throw new IllegalStateException("Viewport render list is not MDIC/OpenGL");
    }

    @Override
    public long size() {
        return this.glBuffer.size();
    }
}
