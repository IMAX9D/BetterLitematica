package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.TerrainWorkers;
import net.minecraft.client.render.chunk.BlockBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(BlockBufferBuilderPool.class)
public abstract class TerrainBuilderPoolMixin {
    @ModifyVariable(method="allocate",at=@At("HEAD"),argsOnly=true)
    private static int betterlitematica$bounded(int requested){return Math.min(requested,TerrainWorkers.parallelism()+1);}
}
