package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

public interface NodeCleanupSink {
    void updateIds(IntOpenHashSet ids);
}
