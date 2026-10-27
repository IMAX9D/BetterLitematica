package dev.betterlitematica.fabric;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.util.BufferAllocator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.concurrent.CompletableFuture;
public final class TerrainTransfers {
    private TerrainTransfers(){}
    public static CompletableFuture<Void> full(ChunkBuilder.BuiltChunk section,Object target,Object layer,BuiltBuffer mesh,TerrainBuildStamp stamp){
        var store=(TerrainMeshStore)target;
        if(mesh.getDrawParameters().indexCount()==0){mesh.close();return CompletableFuture.completedFuture(null);}
        return ((TerrainUploadSource)section).betterlitematica$uploads().offer(new TerrainBuiltBuffer(mesh,stamp,layer==net.minecraft.client.render.RenderLayer.getTranslucent()||layer==net.minecraft.client.render.RenderLayer.getTripwire()),stamp,()->store.betterlitematica$get(layer),buffer->store.betterlitematica$put(layer,buffer));
    }
    public static CompletableFuture<Void> sorted(ChunkBuilder.BuiltChunk section,Object target,Object layer,BufferAllocator.CloseableBuffer mesh,VertexFormat.IndexType type,TerrainBuildStamp stamp){
        var store=(TerrainMeshStore)target;
        return ((TerrainUploadSource)section).betterlitematica$uploads().offer(new TerrainBuiltBuffer(mesh,type),stamp,()->store.betterlitematica$get(layer),buffer->store.betterlitematica$put(layer,buffer));
    }
}
