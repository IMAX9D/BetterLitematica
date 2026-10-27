package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.betterlitematica.fabric.TerrainMultiDraw;
import net.minecraft.client.gl.*;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Restrict batching to commands tagged by the terrain adapter. All other passes stay vanilla. */
@Mixin(GlResourceManager.class)
public abstract class TerrainBatchDrawMixin {
    @WrapMethod(method="drawObjectsWithRenderPass")
    private void betterlitematica$batch(RenderPassImpl pass,Collection<RenderPass.RenderObject> objects,GpuBuffer indices,VertexFormat.IndexType type,Operation<Void> original){
        if(!TerrainMultiDraw.draw(objects,group->original.call(pass,group,indices,type)))original.call(pass,objects,indices,type);
    }
    @WrapOperation(method="drawObjectWithRenderPass",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V"))
    private void betterlitematica$elements(int mode,int count,int type,long offset,Operation<Void> original){
        if(!TerrainMultiDraw.elements(mode,type))original.call(mode,count,type,offset);
    }
}
