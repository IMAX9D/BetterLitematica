package dev.betterlitematica.fabric.mixin;

import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="net.minecraft.client.render.WorldRenderer$ChunkInfo")
public interface TerrainChunkInfoAccessor extends dev.betterlitematica.fabric.TerrainChunkHandle {
    @Accessor("chunk") ChunkBuilder.BuiltChunk betterlitematica$chunk();
}
