package me.cortex.voxy.client.core.rendering;

public interface ChunkBoundsRenderer {
    void addSection(long pos);
    void removeSection(long pos);
    void render(Viewport<?> viewport);
    void reset();
    void free();
}
