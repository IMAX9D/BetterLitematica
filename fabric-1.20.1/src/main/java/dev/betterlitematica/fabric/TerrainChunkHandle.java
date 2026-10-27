package dev.betterlitematica.fabric;

import net.minecraft.client.render.chunk.ChunkBuilder;

/** Shared section lookup for old ChunkInfo wrappers and newer direct section lists. */
public interface TerrainChunkHandle {
    ChunkBuilder.BuiltChunk betterlitematica$chunk();
}
