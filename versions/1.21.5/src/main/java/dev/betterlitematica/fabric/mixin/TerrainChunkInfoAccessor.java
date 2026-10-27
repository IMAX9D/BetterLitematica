package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
/** Visible lists directly store section handles on this API generation. */
@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class TerrainChunkInfoAccessor implements dev.betterlitematica.fabric.TerrainChunkHandle {
    public ChunkBuilder.BuiltChunk betterlitematica$chunk(){return (ChunkBuilder.BuiltChunk)(Object)this;}
}
