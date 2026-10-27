package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.betterlitematica.fabric.TerrainBuffer;
import net.minecraft.client.gl.VertexBuffer;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** Suppress only this mod's lazy terrain handles; other buffers retain vanilla allocation. */
@Mixin(VertexBuffer.class)
public abstract class TerrainBufferAllocationMixin {
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;glGenBuffers(Ljava/util/function/Consumer;)V"),require=2)
    private void betterlitematica$lazyBuffers(Consumer<Integer> sink,Operation<Void> original){if((Object)this instanceof TerrainBuffer)sink.accept(0);else original.call(sink);}
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;glGenVertexArrays(Ljava/util/function/Consumer;)V"))
    private void betterlitematica$lazyArrays(Consumer<Integer> sink,Operation<Void> original){if((Object)this instanceof TerrainBuffer)sink.accept(0);else original.call(sink);}
}
