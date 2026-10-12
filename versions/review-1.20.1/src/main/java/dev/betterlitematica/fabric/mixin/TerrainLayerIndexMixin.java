package dev.betterlitematica.fabric.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.render.WorldRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's visible list includes empty air sections. Share their rejection across terrain layers for one draw. */
@Mixin(WorldRenderer.class)
public abstract class TerrainLayerIndexMixin {
    @Shadow @Final private ObjectArrayList<?> chunkInfos;
    @Unique private final ObjectArrayList<Object> betterlitematica$terrain=new ObjectArrayList<>();
    @Unique private final ObjectArrayList<Object> betterlitematica$blockEntities=new ObjectArrayList<>();
    @Unique private final ObjectArrayList<Object> betterlitematica$dirty=new ObjectArrayList<>();
    @Unique private boolean betterlitematica$indexed;

    @Inject(method="render",at=@At("HEAD"))
    private void betterlitematica$beginTerrain(CallbackInfo ci){
        betterlitematica$indexed=false;betterlitematica$terrain.clear();betterlitematica$blockEntities.clear();betterlitematica$dirty.clear();
    }

    @Redirect(method="updateChunks",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$chunksToRebuild(WorldRenderer renderer){
        // Vanilla allocates a ChunkSectionPos before checking needsRebuild. Keep its original rebuild/light/order
        // rules, but don't send unchanged sections through that allocation-heavy loop. Re-evaluate every call.
        betterlitematica$dirty.clear();Object[] values=chunkInfos.elements();
        for(int i=0,size=chunkInfos.size();i<size;i++){
            Object info=values[i];
            if(((TerrainChunkInfoAccessor)info).betterlitematica$chunk().needsRebuild())betterlitematica$dirty.add(info);
        }
        return betterlitematica$dirty;
    }

    @Inject(method="updateChunks",at=@At("RETURN"))
    private void betterlitematica$endChunkUpdates(CallbackInfo ci){betterlitematica$dirty.clear();}

    @Redirect(method="renderLayer",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$drawableTerrain(WorldRenderer renderer){
        if(!betterlitematica$indexed){
            // BuiltChunk data is published after render-thread uploads. No cross-frame cache: edits, uploads,
            // frustum changes and world changes are all seen on the next draw. Preserve the original order,
            // including its reversal for translucent rendering. Entity/occlusion lists remain untouched.
            Object[] values=chunkInfos.elements();
            for(int i=0,size=chunkInfos.size();i<size;i++){
                Object info=values[i];
                var data=((TerrainChunkInfoAccessor)info).betterlitematica$chunk().getData();
                if(!data.isEmpty())betterlitematica$terrain.add(info);
                // A block-entity-only section must survive even if it has no terrain vertices.
                if(!data.getBlockEntities().isEmpty())betterlitematica$blockEntities.add(info);
            }
            betterlitematica$indexed=true;
        }
        return betterlitematica$terrain;
    }

    @Redirect(method="render",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$blockEntityChunks(WorldRenderer renderer){
        betterlitematica$drawableTerrain(renderer);return betterlitematica$blockEntities;
    }

    @Inject(method="render",at=@At("RETURN"))
    private void betterlitematica$endTerrain(CallbackInfo ci){betterlitematica$terrain.clear();betterlitematica$blockEntities.clear();betterlitematica$dirty.clear();betterlitematica$indexed=false;}
}
