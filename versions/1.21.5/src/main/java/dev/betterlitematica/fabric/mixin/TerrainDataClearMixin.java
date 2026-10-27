package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.TerrainDataUpdates;
import dev.betterlitematica.fabric.TerrainExpandedBounds;

import dev.betterlitematica.fabric.TerrainRebuildUpdates;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.Box;
import java.util.concurrent.atomic.AtomicReference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;
@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class TerrainDataClearMixin implements TerrainExpandedBounds {
    @Unique private Box betterlitematica$boundsSource,betterlitematica$expandedBounds;
    @Override public Box betterlitematica$expandedBounds(Box original){
        if(betterlitematica$boundsSource!=original){
            betterlitematica$boundsSource=original;
            betterlitematica$expandedBounds=original.expand(4.125);
        }
        return betterlitematica$expandedBounds;
    }
    @WrapOperation(method={"clear","setData"},at=@At(value="INVOKE",target="Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V"))
    private void betterlitematica$clearCategory(AtomicReference<?> reference,Object value,Operation<Void> original){TerrainDataUpdates.publish(reference,value,original);}
    @WrapOperation(method="scheduleRebuild(Z)V",at=@At(value="FIELD",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;needsRebuild:Z",opcode=Opcodes.PUTFIELD))
    private void betterlitematica$newDirty(ChunkBuilder.BuiltChunk chunk,boolean value,Operation<Void> original){
        boolean added=value&&!chunk.needsRebuild();original.call(chunk,value);
        if(added)TerrainRebuildUpdates.becameDirty();
    }
    @Inject(method="clear",at=@At("RETURN"))
    private void betterlitematica$clearedDirty(CallbackInfo ci){TerrainRebuildUpdates.becameDirty();}
}
