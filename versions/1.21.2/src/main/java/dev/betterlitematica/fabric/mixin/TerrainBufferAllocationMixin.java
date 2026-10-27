package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.betterlitematica.fabric.TerrainBuffer;
import net.minecraft.client.gl.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only our fully overridden terrain handle omits the native per-section GPU wrapper. */
@Mixin(VertexBuffer.class)
public abstract class TerrainBufferAllocationMixin {
    @WrapOperation(method="<init>",at=@At(value="NEW",target="(Lnet/minecraft/client/gl/GlBufferTarget;Lnet/minecraft/client/gl/GlUsage;I)Lnet/minecraft/client/gl/GpuBuffer;"))
    private GpuBuffer betterlitematica$lazyBuffer(GlBufferTarget target,GlUsage usage,int bytes,Operation<GpuBuffer> original){
        return (Object)this instanceof TerrainBuffer?null:original.call(target,usage,bytes);
    }
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int betterlitematica$lazyArray(Operation<Integer> original){return (Object)this instanceof TerrainBuffer?0:original.call();}
}
