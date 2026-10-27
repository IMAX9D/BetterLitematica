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
    @WrapOperation(method="run",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;uploadLayer(Ljava/util/Map;Lnet/minecraft/client/render/chunk/ChunkRenderData;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> betterlitematica$upload(ChunkBuilder.BuiltChunk section,java.util.Map<net.minecraft.client.render.BlockRenderLayer,net.minecraft.client.render.BuiltBuffer> layers,net.minecraft.client.render.chunk.ChunkRenderData target,Operation<CompletableFuture<Void>> original){
        var uploads=new java.util.ArrayList<CompletableFuture<Void>>(layers.size());
        layers.forEach((layer,data)->uploads.add(dev.betterlitematica.fabric.TerrainTransfers.full(section,target,layer,data,betterlitematica$stamp)));
        return CompletableFuture.allOf(uploads.toArray(CompletableFuture[]::new));
    }
    @WrapOperation(method={"run","method_72053"},at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;setCurrentRenderData(Lnet/minecraft/client/render/chunk/AbstractChunkRenderData;)V"))
    private void betterlitematica$publishCategory(ChunkBuilder.BuiltChunk section,net.minecraft.client.render.chunk.AbstractChunkRenderData value,Operation<Void> original){
        if(betterlitematica$stamp.current()){
            original.call(section,value);
            ((TerrainSectionOwner)section).betterlitematica$published(betterlitematica$stamp.generation());
        }
    }
}
