package dev.betterlitematica.fabric.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import dev.betterlitematica.fabric.TerrainDataUpdates;
import dev.betterlitematica.fabric.TerrainDirtyUpdates;
import dev.betterlitematica.fabric.TerrainRenderLists;
import net.minecraft.client.render.WorldRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Share rejection of empty air sections until visibility or published renderability changes. */
@Mixin(WorldRenderer.class)
public abstract class TerrainLayerIndexMixin implements TerrainRenderLists {
    @Shadow @Final private ObjectArrayList<?> chunkInfos;
    @Unique private final ObjectArrayList<Object> betterlitematica$terrain=new ObjectArrayList<>();
    @Unique private final ObjectArrayList<Object> betterlitematica$blockEntities=new ObjectArrayList<>();
    @Unique private final ObjectArrayList<Object> betterlitematica$dirty=new ObjectArrayList<>();
    @Unique private boolean betterlitematica$indexed;
    @Unique private boolean betterlitematica$dirtyIndexed;
    @Unique private long betterlitematica$dirtyRevision=-1,betterlitematica$dirtyValidateAt;
    @Unique private long betterlitematica$dataRevision=-1,betterlitematica$validateAt;
    @Override public void betterlitematica$invalidateLists(){betterlitematica$indexed=false;betterlitematica$dirtyIndexed=false;}

    @Inject(method="render",at=@At("HEAD"))
    private void betterlitematica$beginTerrain(CallbackInfo ci){
        long revision=TerrainDataUpdates.revision(),now=System.nanoTime();
        // Vanilla publication invalidates immediately. Periodic validation also sees third-party direct writes.
        if(revision!=betterlitematica$dataRevision||now>=betterlitematica$validateAt)betterlitematica$indexed=false;
    }

    @Redirect(method="updateChunks",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$chunksToRebuild(WorldRenderer renderer){
        // Vanilla allocates a ChunkSectionPos before checking needsRebuild. Keep its original rebuild/light/order
        // rules, but don't send unchanged sections through that allocation-heavy loop.
        long revision=TerrainDirtyUpdates.revision(),now=System.nanoTime();
        if(!betterlitematica$dirtyIndexed||revision!=betterlitematica$dirtyRevision||now>=betterlitematica$dirtyValidateAt){
            betterlitematica$dirty.clear();Object[] values=chunkInfos.elements();
            for(int i=0,size=chunkInfos.size();i<size;i++){
                Object info=values[i];
                if(((TerrainChunkInfoAccessor)info).betterlitematica$chunk().needsRebuild())betterlitematica$dirty.add(info);
            }
            betterlitematica$dirtyIndexed=true;betterlitematica$dirtyRevision=revision;betterlitematica$dirtyValidateAt=now+50_000_000L;
        }else{
            // Completions only remove candidates; retain source order and detect new dirty sections above.
            betterlitematica$dirty.removeIf(info->!((TerrainChunkInfoAccessor)info).betterlitematica$chunk().needsRebuild());
        }
        return betterlitematica$dirty;
    }

    @Redirect(method="renderLayer",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$drawableTerrain(WorldRenderer renderer){
        long revision=TerrainDataUpdates.revision();
        if(revision!=betterlitematica$dataRevision)betterlitematica$indexed=false;
        if(!betterlitematica$indexed){
            betterlitematica$terrain.clear();betterlitematica$blockEntities.clear();
            // Preserve the original order, including its reversal for translucent rendering. Record the
            // revision from BEFORE this scan: a concurrent publication must invalidate the next use.
            // Actual layer/entity rendering still reads the current data from every retained section.
            Object[] values=chunkInfos.elements();
            for(int i=0,size=chunkInfos.size();i<size;i++){
                Object info=values[i];
                var data=((TerrainChunkInfoAccessor)info).betterlitematica$chunk().getData();
                if(!data.isEmpty())betterlitematica$terrain.add(info);
                // A block-entity-only section must survive even if it has no terrain vertices.
                if(!data.getBlockEntities().isEmpty())betterlitematica$blockEntities.add(info);
            }
            betterlitematica$indexed=true;
            betterlitematica$dataRevision=revision;betterlitematica$validateAt=System.nanoTime()+50_000_000L;
        }
        return betterlitematica$terrain;
    }

    @Redirect(method="render",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$blockEntityChunks(WorldRenderer renderer){
        betterlitematica$drawableTerrain(renderer);return betterlitematica$blockEntities;
    }

    @Inject(method="setWorld",at=@At("HEAD"))
    private void betterlitematica$clearTerrain(CallbackInfo ci){betterlitematica$terrain.clear();betterlitematica$blockEntities.clear();betterlitematica$dirty.clear();betterlitematica$indexed=false;betterlitematica$dirtyIndexed=false;}
}
