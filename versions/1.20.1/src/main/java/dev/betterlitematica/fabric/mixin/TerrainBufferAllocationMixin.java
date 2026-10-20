package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.TerrainBuffer;
import net.minecraft.client.gl.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only our explicit subclass is lazy; clouds, entities, projections and other mods are untouched. */
@Mixin(VertexBuffer.class)
public abstract class TerrainBufferAllocationMixin {
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"),require=2)
    private int betterlitematica$lazyBuffers(Operation<Integer> original){return (Object)this instanceof TerrainBuffer?0:original.call();}
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int betterlitematica$lazyArray(Operation<Integer> original){return (Object)this instanceof TerrainBuffer?0:original.call();}
}
