package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.TerrainBuffer;
import dev.betterlitematica.fabric.TerrainBuildStamp;
import dev.betterlitematica.fabric.TerrainSectionOwner;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.ChunkRendererRegionBuilder;
import net.minecraft.util.math.BlockPos;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class TerrainSectionBufferMixin implements TerrainSectionOwner {
    @Shadow @Final private Map<RenderLayer,VertexBuffer> buffers;
    @Shadow @Final private ChunkBuilder field_20833;
    @Shadow public abstract BlockPos getOrigin();
    @Shadow public abstract void scheduleRebuild(ChunkBuilder builder,ChunkRendererRegionBuilder regions);
    @Unique private volatile long betterlitematica$generation;
    @Unique private volatile long betterlitematica$published=Long.MIN_VALUE;

    @Override public long betterlitematica$generation(){return betterlitematica$generation;}
    @Override public TerrainBuildStamp betterlitematica$stamp(boolean newBuild){
        if(newBuild)betterlitematica$generation++;
        var pos=getOrigin();return new TerrainBuildStamp(this,newBuild?betterlitematica$generation:betterlitematica$published,pos.getX(),pos.getY(),pos.getZ());
    }
    @Override public void betterlitematica$published(long generation){betterlitematica$published=generation;}
    @Inject(method="method_22779",at=@At("HEAD"),cancellable=true)
    private static void betterlitematica$createBuffer(RenderLayer layer,CallbackInfoReturnable<VertexBuffer> cir){cir.setReturnValue(new TerrainBuffer());}
    @Inject(method="clear",at=@At("HEAD"))
    private void betterlitematica$discardMeshes(CallbackInfo ci){
        betterlitematica$generation++;
        betterlitematica$published=Long.MIN_VALUE;
        if(buffers!=null)for(var buffer:buffers.values())if(buffer instanceof TerrainBuffer terrain)terrain.clearMesh();
    }
    @Inject(method="rebuild",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$buildOffThread(ChunkRendererRegionBuilder regions,CallbackInfo ci){
        // Keep vanilla's immutable region snapshot/model adapters and priority queue. Even urgent
        // block updates run on its bounded workers instead of meshing on the render thread.
        scheduleRebuild(field_20833,regions);ci.cancel();
    }
}
