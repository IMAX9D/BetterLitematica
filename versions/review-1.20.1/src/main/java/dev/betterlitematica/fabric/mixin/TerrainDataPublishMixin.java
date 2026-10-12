package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.TerrainDataUpdates;
import java.util.concurrent.atomic.AtomicReference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(targets="net.minecraft.client.render.chunk.ChunkBuilder$BuiltChunk$RebuildTask")
public abstract class TerrainDataPublishMixin {
    @WrapOperation(method="method_23619",at=@At(value="INVOKE",target="Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V"))
    private void betterlitematica$publishCategory(AtomicReference<?> reference,Object value,Operation<Void> original){TerrainDataUpdates.publish(reference,value,original);}
}
