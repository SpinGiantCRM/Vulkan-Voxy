package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;

public class MDICNodeMetadataStore implements NodeMetadataStore {
    private final GlBuffer nodeBuffer;

    public MDICNodeMetadataStore(GlBuffer nodeBuffer) {
        this.nodeBuffer = nodeBuffer;
    }

    GlBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }
}
