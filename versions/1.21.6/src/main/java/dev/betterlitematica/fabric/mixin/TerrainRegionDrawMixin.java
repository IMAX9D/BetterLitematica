package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.betterlitematica.fabric.TerrainRegions;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.Buffers;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Keep native shaders, fog, ordering and drawMultipleIndexed; replace only buffer ranges. */
@Mixin(WorldRenderer.class)
public abstract class TerrainRegionDrawMixin {
    @Inject(method="render",at=@At("HEAD"))private void betterlitematica$begin(CallbackInfo ci){TerrainRegions.INSTANCE.beginFrame();}
    @Inject(method="render",at=@At("RETURN"))private void betterlitematica$end(CallbackInfo ci){TerrainRegions.INSTANCE.endFrame();}
    @Inject(method={"setWorld","reload()V","close"},at=@At("HEAD"))private void betterlitematica$clear(CallbackInfo ci){TerrainRegions.INSTANCE.clear();}
    @WrapOperation(method="renderBlockLayers",at=@At(value="NEW",target="(ILcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;IILjava/util/function/BiConsumer;)Lcom/mojang/blaze3d/systems/RenderPass$RenderObject;"))
    private RenderPass.RenderObject betterlitematica$range(int slot,GpuBuffer vertices,GpuBuffer indices,VertexFormat.IndexType type,int first,int count,BiConsumer<Object,RenderPass.UniformUploader> uniforms,Operation<RenderPass.RenderObject> original,@Local Buffers buffers){
        return TerrainRegions.INSTANCE.register(original.call(slot,vertices,indices,type,first+TerrainRegions.INSTANCE.firstIndex(buffers),count,uniforms),buffers,null);
    }
    @WrapOperation(method="renderBlockLayers",at=@At(value="NEW",target="(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;F)Lnet/minecraft/client/gl/DynamicUniforms$UniformValue;"))
    private net.minecraft.client.gl.DynamicUniforms.UniformValue betterlitematica$origin(org.joml.Matrix4fc view,org.joml.Vector4fc color,org.joml.Vector3fc offset,org.joml.Matrix4fc texture,float width,Operation<net.minecraft.client.gl.DynamicUniforms.UniformValue> original,@Local Buffers buffers,@Local net.minecraft.client.render.chunk.ChunkBuilder.BuiltChunk section){
        var origin=TerrainRegions.INSTANCE.origin(buffers);var p=section.getOrigin();
        return original.call(view,color,origin==null?offset:new org.joml.Vector3f(offset).add(origin.x()-p.getX(),origin.y()-p.getY(),origin.z()-p.getZ()),texture,width);
    }
}
