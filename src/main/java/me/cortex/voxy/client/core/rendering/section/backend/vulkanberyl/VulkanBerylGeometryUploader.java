package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

final class VulkanBerylGeometryUploader {
    private static final VulkanBerylGeometryUploader INSTANCE = new VulkanBerylGeometryUploader();

    private VulkanBerylGeometryUploader() {
    }

    public static VulkanBerylGeometryUploader get() {
        return INSTANCE;
    }

    public void upload(Buffer destinationBuffer, long destinationOffsetBytes, long sourceAddress, long copySizeBytes) {
        if (destinationBuffer == null) {
            throw new IllegalArgumentException("destinationBuffer must not be null");
        }
        if (destinationOffsetBytes < 0L) {
            throw new IllegalArgumentException("destinationOffsetBytes must be non-negative");
        }
        if (sourceAddress == 0L) {
            throw new IllegalArgumentException("sourceAddress must be non-zero");
        }
        if (copySizeBytes <= 0L) {
            throw new IllegalArgumentException("copySizeBytes must be positive");
        }
        if (copySizeBytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("copySizeBytes exceeds VulkanMod ByteBuffer upload limit: " + copySizeBytes);
        }

        ByteBuffer uploadView = MemoryUtil.memByteBuffer(sourceAddress, (int) copySizeBytes);
        UploadManager.INSTANCE.recordUpload(destinationBuffer, destinationOffsetBytes, copySizeBytes, uploadView);
    }

    public void flush() {
        UploadManager.INSTANCE.syncUploads();
    }
}
