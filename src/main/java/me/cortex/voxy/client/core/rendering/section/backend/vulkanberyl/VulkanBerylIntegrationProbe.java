package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import net.beryl.BerylMod;
import net.vulkanmod.Initializer;

/**
 * Compile-time probe ensuring Voxy can resolve VulkanMod and Beryl classes.
 */
public final class VulkanBerylIntegrationProbe {
    private VulkanBerylIntegrationProbe() {
    }

    public static Class<?>[] requiredModEntryPoints() {
        return new Class<?>[]{Initializer.class, BerylMod.class};
    }
}
