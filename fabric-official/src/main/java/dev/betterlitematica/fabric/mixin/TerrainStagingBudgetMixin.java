package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import dev.betterlitematica.fabric.TerrainNativeBudget;
import it.unimi.dsi.fastutil.objects.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Partition the native pending batch; callbacks run only for resident entries. Restore deferred
 * entries in finally, and retain their staging bytes until the last deferred entry is consumed. */
@Mixin(UberGpuBuffer.class)
public abstract class TerrainStagingBudgetMixin {
    @Shadow @Final private Object2ObjectOpenHashMap<Object,UberGpuBuffer.StagedAllocationEntry<Object>> stagedAllocations;
    @Shadow @Final private ObjectOpenHashSet<Object> skippedStagedAllocations;
    @Unique private final ArrayDeque<Object> betterlitematica$order=new ArrayDeque<>();
    @Unique private final Set<Object> betterlitematica$known=Collections.newSetFromMap(new IdentityHashMap<>());
    @Unique private final Object2ObjectOpenHashMap<Object,UberGpuBuffer.StagedAllocationEntry<Object>> betterlitematica$deferred=new Object2ObjectOpenHashMap<>();
    @Unique private final ObjectOpenHashSet<Object> betterlitematica$skipped=new ObjectOpenHashSet<>();

    @WrapMethod(method="uploadStagedAllocations")
    private boolean betterlitematica$budget(GpuDevice device,CommandEncoder encoder,Operation<Boolean> original){
        if(!TerrainNativeBudget.active)return original.call(device,encoder);
        for(var key:stagedAllocations.keySet())if(betterlitematica$known.add(key))betterlitematica$order.addLast(key);
        betterlitematica$deferred.putAll(stagedAllocations);stagedAllocations.clear();
        long bytes=0,limit=TerrainNativeBudget.allowance();
        while(!betterlitematica$order.isEmpty()&&TerrainNativeBudget.available()&&(bytes==0||bytes<limit)){
            Object key=betterlitematica$order.removeFirst();betterlitematica$known.remove(key);
            var entry=betterlitematica$deferred.remove(key);if(entry==null)continue;
            stagedAllocations.put(key,entry);if(!skippedStagedAllocations.contains(key))bytes+=entry.size;
        }
        betterlitematica$skipped.addAll(skippedStagedAllocations);betterlitematica$skipped.retainAll(betterlitematica$deferred.keySet());
        try{return original.call(device,encoder);}
        finally{
            stagedAllocations.putAll(betterlitematica$deferred);betterlitematica$deferred.clear();
            skippedStagedAllocations.addAll(betterlitematica$skipped);betterlitematica$skipped.clear();TerrainNativeBudget.consume(bytes);
        }
    }
    @WrapOperation(method="uploadStagedAllocations",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/UberGpuBuffer$UberGpuBufferStagingBuffer;clearFrame(Lcom/mojang/blaze3d/systems/CommandEncoder;)V"))
    private void betterlitematica$keepStaging(UberGpuBuffer.UberGpuBufferStagingBuffer staging,CommandEncoder encoder,Operation<Void> original){
        if(betterlitematica$deferred.isEmpty())original.call(staging,encoder);
    }
    @WrapOperation(method="uploadStagedAllocations",at=@At(value="FIELD",target="Lcom/mojang/blaze3d/vertex/UberGpuBuffer;stagingBufferUsedSize:I",opcode=Opcodes.PUTFIELD))
    private void betterlitematica$keepUsed(UberGpuBuffer<?> buffer,int value,Operation<Void> original){if(betterlitematica$deferred.isEmpty())original.call(buffer,value);}
    @WrapMethod(method="close")
    private void betterlitematica$close(Operation<Void> original){try{original.call();}finally{betterlitematica$order.clear();betterlitematica$known.clear();betterlitematica$deferred.clear();betterlitematica$skipped.clear();}}
}
