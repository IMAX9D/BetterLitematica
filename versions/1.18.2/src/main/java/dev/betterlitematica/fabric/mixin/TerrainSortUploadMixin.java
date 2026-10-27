package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.chunk.ChunkBuilder;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.client.render.chunk.ChunkBuilder$BuiltChunk$SortTask")
public abstract class TerrainSortUploadMixin {
    @Shadow @Final private ChunkBuilder.BuiltChunk field_20840;
    @Unique private TerrainBuildStamp betterlitematica$stamp;
    @Inject(method="<init>",at=@At("RETURN"))
    private void betterlitematica$capture(CallbackInfo ci){betterlitematica$stamp=((TerrainSectionOwner)field_20840).betterlitematica$stamp(false);}
    @WrapOperation(method="run",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder;scheduleUpload(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/gl/VertexBuffer;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> betterlitematica$queue(ChunkBuilder builder,BufferBuilder data,VertexBuffer buffer,Operation<CompletableFuture<Void>> original){
        return buffer instanceof TerrainBuffer terrain
            ?((TerrainUploadOwner)builder).betterlitematica$uploads().offer(terrain,new dev.betterlitematica.fabric.TerrainBuiltBuffer(data),betterlitematica$stamp):original.call(builder,data,buffer);
    }
}
