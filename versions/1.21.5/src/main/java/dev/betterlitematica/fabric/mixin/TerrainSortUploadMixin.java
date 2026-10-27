package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.BufferAllocator;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.client.render.chunk.ChunkBuilder$BuiltChunk$SortTask")
public abstract class TerrainSortUploadMixin {
    @Shadow @Final private ChunkBuilder.BuiltChunk field_20840;
    @Shadow @Final private ChunkBuilder.ChunkData data;
    @Unique private TerrainBuildStamp betterlitematica$stamp;
    @Inject(method="<init>",at=@At("RETURN"))private void betterlitematica$capture(CallbackInfo ci){betterlitematica$stamp=((TerrainSectionOwner)field_20840).betterlitematica$stamp(false);}
    @WrapOperation(method="run",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;uploadIndices(Lnet/minecraft/client/util/BufferAllocator$CloseableBuffer;Lnet/minecraft/client/render/RenderLayer;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> betterlitematica$sort(ChunkBuilder.BuiltChunk section,BufferAllocator.CloseableBuffer buffer,RenderLayer layer,Operation<CompletableFuture<Void>> original){
        return TerrainTransfers.sorted(section,section,layer,buffer,data.transparentSortingData.indexType(),betterlitematica$stamp);
    }
}
