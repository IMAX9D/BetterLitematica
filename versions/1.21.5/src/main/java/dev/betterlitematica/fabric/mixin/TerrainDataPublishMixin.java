package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.TerrainDataUpdates;

import dev.betterlitematica.fabric.TerrainBuildStamp;
import dev.betterlitematica.fabric.TerrainSectionOwner;
import dev.betterlitematica.fabric.TerrainUploadOwner;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.chunk.ChunkBuilder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.client.render.chunk.ChunkBuilder$BuiltChunk$RebuildTask")
public abstract class TerrainDataPublishMixin {
    @Shadow @Final private ChunkBuilder.BuiltChunk field_20839;
    @Unique private TerrainBuildStamp betterlitematica$stamp;
    @Inject(method="<init>",at=@At("RETURN"))
    private void betterlitematica$capture(CallbackInfo ci){betterlitematica$stamp=((TerrainSectionOwner)field_20839).betterlitematica$stamp(true);}
    @WrapOperation(method="method_22788",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;uploadLayer(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/BuiltBuffer;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> betterlitematica$upload(ChunkBuilder.BuiltChunk section,net.minecraft.client.render.RenderLayer layer,net.minecraft.client.render.BuiltBuffer data,Operation<CompletableFuture<Void>> original){
        return dev.betterlitematica.fabric.TerrainTransfers.full(section,section,layer,data,betterlitematica$stamp);
    }
    @WrapOperation(method={"run","method_23619"},at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;setData(Lnet/minecraft/client/render/chunk/ChunkBuilder$ChunkData;)V"))
    private void betterlitematica$publishCategory(ChunkBuilder.BuiltChunk section,ChunkBuilder.ChunkData value,Operation<Void> original){
        if(betterlitematica$stamp.current()){
            original.call(section,value);
            ((TerrainSectionOwner)section).betterlitematica$published(betterlitematica$stamp.generation());
        }
    }
}
