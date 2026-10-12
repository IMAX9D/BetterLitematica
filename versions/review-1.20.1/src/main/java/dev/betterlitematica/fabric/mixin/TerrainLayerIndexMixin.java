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
    @Unique private boolean betterlitematica$indexed;

    @Inject(method="render",at=@At("HEAD"))
    private void betterlitematica$beginTerrain(CallbackInfo ci){
        betterlitematica$indexed=false;betterlitematica$terrain.clear();
    }

    @Redirect(method="renderLayer",at=@At(value="FIELD",target="Lnet/minecraft/client/render/WorldRenderer;chunkInfos:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",opcode=Opcodes.GETFIELD),require=0)
    private ObjectArrayList<?> betterlitematica$drawableTerrain(WorldRenderer renderer){
        if(!betterlitematica$indexed){
            // BuiltChunk data is published after render-thread uploads. No cross-frame cache: edits, uploads,
            // frustum changes and world changes are all seen on the next draw. Preserve the original order,
            // including its reversal for translucent rendering. Entity/occlusion lists remain untouched.
            Object[] values=chunkInfos.elements();
            for(int i=0,size=chunkInfos.size();i<size;i++){
                Object info=values[i];
                if(!((TerrainChunkInfoAccessor)info).betterlitematica$chunk().getData().isEmpty())betterlitematica$terrain.add(info);
            }
            betterlitematica$indexed=true;
        }
        return betterlitematica$terrain;
    }

    @Inject(method="render",at=@At("RETURN"))
    private void betterlitematica$endTerrain(CallbackInfo ci){betterlitematica$terrain.clear();betterlitematica$indexed=false;}
}
