package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

public class MDICSectionGeometrySyncBackend implements SectionGeometrySyncBackend {
    private final Shader scatterWrite = Shader.make()
            .define("INPUT_BUFFER_BINDING", 0)
            .define("OUTPUT_BUFFER1_BINDING", 1)
            .define("OUTPUT_BUFFER2_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
            .compile();

    private final Shader multiMemcpy = Shader.make()
            .define("INPUT_HEADER_BUFFER_BINDING", 0)
            .define("INPUT_DATA_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
            .compile();

    @Override
    public int getMaxSectionCount(IGeometryData geometryData) {
        return ((MDICSectionGeometryData) geometryData).getMaxSectionCount();
    }

    @Override
    public long getGeometryCapacityBytes(IGeometryData geometryData) {
        return ((MDICSectionGeometryData) geometryData).getGeometryCapacityBytes();
    }

    @Override
    public void applyGeometrySync(IGeometryData geometryData, AsyncNodeManager.SyncResults results) {
        var store = (MDICSectionGeometryData) geometryData;
        store.setSectionCount(results.geometrySectionCount);

        var upload = results.geometryUpload;
        if (!upload.dataUploadPoints.isEmpty()) {
            store.ensureAccessable(upload.maxElementAccess);
            TimingStatistics.A.start();

            int copies = upload.dataUploadPoints.size();
            int upCopies = UploadStream.alignUpAlloc(copies * 16);
            int scratchSize = (int) upload.arena.getSize() * 8;
            int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
            long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
            UnsafeUtil.memcpy(upload.scratchHeaderBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
            UnsafeUtil.memcpy(upload.scratchDataBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
            UploadStream.INSTANCE.commit();

            this.multiMemcpy.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, upCopies);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, UploadStream.INSTANCE.getRawBufferId(), ptr + upCopies, upScratchSize);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, store.getGeometryBuffer().id);

            if (copies > 500) {
                Logger.warn("Large amount of copies, lag will probably happen: " + copies);
            }

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(copies, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            TimingStatistics.A.stop();
        }
    }

    @Override
    public void applyScatterWrites(IGeometryData geometryData, AsyncNodeManager.SyncResults results, NodeMetadataStore nodeMetadataStore) {
        if (!results.scatterWriteLocationMap.isEmpty()) {
            int count = results.scatterWriteLocationMap.size();
            int chunks = (count + 3) / 4;
            int streamSize = chunks * 80;
            long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);
            MemoryUtil.memCopy(results.scatterWriteBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
            UploadStream.INSTANCE.commit();

            this.scatterWrite.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
            var nodeBuffer = ((MDICNodeMetadataStore) nodeMetadataStore).getNodeBuffer();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((MDICSectionGeometryData) geometryData).getMetadataBuffer().id);
            glUniform1ui(0, count);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((count + 127) / 128, 1, 1);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        }
    }

    @Override
    public void free() {
        this.scatterWrite.free();
        this.multiMemcpy.free();
    }
}
