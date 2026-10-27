package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.TerrainChunkHandle;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import dev.betterlitematica.fabric.TerrainDataUpdates;
import dev.betterlitematica.fabric.TerrainRenderLists;
import dev.betterlitematica.fabric.TerrainUploadQueue;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import java.util.Arrays;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Categorize the visibility traversal's compact output while preserving its per-layer order. */
@Mixin(WorldRenderer.class)
public abstract class TerrainLayerIndexMixin implements TerrainRenderLists {
    @Shadow @Final private ObjectArrayList<?> chunkInfos;
    @Unique private final ObjectArrayList<Object> betterlitematica$terrain=new ObjectArrayList<>();
    @Unique private final ObjectArrayList<Object> betterlitematica$blockEntities=new ObjectArrayList<>();
    @Unique private final ObjectArrayList<Object> betterlitematica$dirty=new ObjectArrayList<>();
    @Unique private boolean betterlitematica$indexed;
    @Unique private Object[] betterlitematica$source=new Object[0];
    @Unique private ChunkBuilder.ChunkData[] betterlitematica$data=new ChunkBuilder.ChunkData[0];
    @Unique private int betterlitematica$sourceSize;
    @Unique private long betterlitematica$frame,betterlitematica$checkedFrame=-1;
    @Unique private long betterlitematica$dataRevision=-1;
    @Override public void betterlitematica$invalidateLists(){betterlitematica$indexed=false;}

    @Inject(method="render",at=@At("HEAD"))
    private void betterlitematica$beginTerrain(CallbackInfo ci){
        // Never retain a category decision across frames, including third-party in-place data edits.
        betterlitematica$indexed=false;
        betterlitematica$frame++;
    }

    @Redirect(method="updateChunks",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$chunksToRebuild(WorldRenderer renderer){
        // Vanilla allocates a ChunkSectionPos before checking needsRebuild. Keep its original rebuild/light/order
        // rules, but don't send unchanged sections through that allocation-heavy loop.
        betterlitematica$dirty.clear();Object[] values=chunkInfos.elements();
        for(int i=0,size=chunkInfos.size();i<size;i++){
            Object info=values[i];
            if(((TerrainChunkHandle)info).betterlitematica$chunk().needsRebuild())betterlitematica$dirty.add(info);
        }
        return betterlitematica$dirty;
    }

    @Redirect(method="renderLayer",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$drawableTerrain(WorldRenderer renderer){
        long revision=TerrainDataUpdates.revision();
        // Exact identity/data checks once per frame, not once per layer plus the entity pass.
        // TerrainTraversal replaces the list through setupTerrain, which invalidates us explicitly.
        if(revision!=betterlitematica$dataRevision||chunkInfos.size()!=betterlitematica$sourceSize
                ||betterlitematica$checkedFrame!=betterlitematica$frame&&!betterlitematica$sameSource())betterlitematica$indexed=false;
        if(!betterlitematica$indexed){
            betterlitematica$terrain.clear();betterlitematica$blockEntities.clear();
            // Preserve the original order, including its reversal for translucent rendering. Record the
            // revision from BEFORE this scan: a concurrent publication must invalidate the next use.
            // Actual layer/entity rendering still reads the current data from every retained section.
            Object[] values=chunkInfos.elements();int size=chunkInfos.size();
            boolean holes=false;
            if(betterlitematica$source.length<size){
                int capacity=Math.max(size,Math.max(256,betterlitematica$source.length+betterlitematica$source.length/2));
                betterlitematica$source=new Object[capacity];betterlitematica$data=new ChunkBuilder.ChunkData[capacity];
            }else if(size<betterlitematica$sourceSize){
                Arrays.fill(betterlitematica$source,size,betterlitematica$sourceSize,null);
                Arrays.fill(betterlitematica$data,size,betterlitematica$sourceSize,null);
            }
            betterlitematica$sourceSize=size;
            for(int i=0;i<size;i++){
                Object info=values[i];
                var data=((TerrainChunkHandle)info).betterlitematica$chunk().getData();
                holes|=data==ChunkBuilder.ChunkData.EMPTY;
                betterlitematica$source[i]=info;betterlitematica$data[i]=data;
                if(!data.isEmpty())betterlitematica$terrain.add(info);
                // A block-entity-only section must survive even if it has no terrain vertices.
                if(!data.getBlockEntities().isEmpty())betterlitematica$blockEntities.add(info);
            }
            betterlitematica$indexed=true;
            betterlitematica$dataRevision=revision;
            TerrainUploadQueue.visibleHoles=holes;
        }
        betterlitematica$checkedFrame=betterlitematica$frame;
        return betterlitematica$terrain;
    }

    @Unique private boolean betterlitematica$sameSource(){
        if(!betterlitematica$indexed||chunkInfos.size()!=betterlitematica$sourceSize)return false;
        Object[] values=chunkInfos.elements();
        for(int i=0;i<betterlitematica$sourceSize;i++){
            if(values[i]!=betterlitematica$source[i]
                    ||((TerrainChunkHandle)values[i]).betterlitematica$chunk().getData()!=betterlitematica$data[i])return false;
        }
        return true;
    }

    @Redirect(method="render",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$blockEntityChunks(WorldRenderer renderer){
        betterlitematica$drawableTerrain(renderer);return betterlitematica$blockEntities;
    }

    @Inject(method={"setWorld","reload()V"},at=@At("HEAD"))
    private void betterlitematica$clearTerrain(CallbackInfo ci){
        betterlitematica$terrain.clear();betterlitematica$blockEntities.clear();betterlitematica$dirty.clear();
        Arrays.fill(betterlitematica$source,null);Arrays.fill(betterlitematica$data,null);
        betterlitematica$sourceSize=0;betterlitematica$indexed=false;betterlitematica$dataRevision=-1;
    }
}
