package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import net.beryl.render.RenderingPipeline;
import net.beryl.render.RenderingStage;
import net.beryl.render.ShaderMainPass;
import net.beryl.render.ShaderRenderPipeline;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

/**
 * Compile-time bridge that pins Voxy against render-facing VulkanMod + Beryl API types.
 */
public final class VulkanBerylApiAccess {
    private VulkanBerylApiAccess() {
    }

    public static Class<?>[] berylRenderIntegrationTypes() {
        return new Class<?>[]{
                RenderingPipeline.class,
                ShaderRenderPipeline.class,
                ShaderMainPass.class,
                RenderingStage.class
        };
    }

    public static Class<?>[] vulkanModRenderIntegrationTypes() {
        return new Class<?>[]{
                WorldRenderer.class,
                DrawBuffers.class,
                RenderPass.class,
                Buffer.class,
                Vulkan.class
        };
    }
}
