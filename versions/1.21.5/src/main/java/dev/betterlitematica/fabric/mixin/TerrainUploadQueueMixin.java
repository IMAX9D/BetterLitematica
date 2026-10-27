package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.TerrainUploadOwner;
import dev.betterlitematica.fabric.TerrainUploadQueue;
import dev.betterlitematica.fabric.TerrainWorkers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.render.chunk.ChunkBuilder;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ChunkBuilder.class)
public abstract class TerrainUploadQueueMixin implements TerrainUploadOwner {
    @Unique private final TerrainUploadQueue betterlitematica$uploads=new TerrainUploadQueue();
    @Unique private ExecutorService betterlitematica$workers;
    // This point is after Object.<init>, so the handler may safely store our executor on this instance.
    @ModifyVariable(method="<init>",at=@At(value="INVOKE",target="Lcom/google/common/collect/Queues;newConcurrentLinkedQueue()Ljava/util/concurrent/ConcurrentLinkedQueue;",ordinal=0),argsOnly=true)
    private net.minecraft.util.thread.NameableExecutor betterlitematica$dedicatedWorkers(net.minecraft.util.thread.NameableExecutor original){
        betterlitematica$workers=TerrainWorkers.create();return new net.minecraft.util.thread.NameableExecutor(betterlitematica$workers);
    }
    @Override public TerrainUploadQueue betterlitematica$uploads(){return betterlitematica$uploads;}
    @Inject(method="upload",at=@At("HEAD"))
    private void betterlitematica$upload(CallbackInfo ci){betterlitematica$uploads.drain();}
    @Inject(method="getChunksToUpload",at=@At("RETURN"),cancellable=true)
    private void betterlitematica$uploadCount(CallbackInfoReturnable<Integer> cir){cir.setReturnValue(cir.getReturnValue()+betterlitematica$uploads.size());}
    @Inject(method="isEmpty",at=@At("RETURN"),cancellable=true)
    private void betterlitematica$isEmpty(CallbackInfoReturnable<Boolean> cir){cir.setReturnValue(cir.getReturnValue()&&betterlitematica$uploads.size()==0);}
    @Inject(method="stop",at=@At("HEAD"))
    private void betterlitematica$stop(CallbackInfo ci){betterlitematica$uploads.stop();}
    @Inject(method="stop",at=@At("RETURN"))
    private void betterlitematica$stopWorkers(CallbackInfo ci){
        // Running jobs finish and release their buffers into the closed upload queue. Never interrupt
        // a model renderer midway through native writes; the pool's threads cannot keep the JVM alive.
        if(betterlitematica$workers!=null)betterlitematica$workers.shutdown();
    }
}
