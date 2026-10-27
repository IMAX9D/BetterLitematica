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
    private RenderPass.RenderObject betterlitematica$range(int slot,GpuBuffer vertices,GpuBuffer indices,VertexFormat.IndexType type,int first,int count,BiConsumer<Object,RenderPass.UniformUploader> uniforms,Operation<RenderPass.RenderObject> original,@Local Buffers buffers,@Local net.minecraft.client.render.chunk.ChunkBuilder.BuiltChunk section){
        return TerrainRegions.INSTANCE.register(original.call(slot,vertices,indices,type,first+TerrainRegions.INSTANCE.firstIndex(buffers),count,uniforms),buffers,section.method_76298(net.minecraft.util.Util.getMeasuringTimeMs())>=1?null:section);
    }
    @WrapOperation(method="renderBlockLayers",at=@At(value="NEW",target="(Lorg/joml/Matrix4fc;IIIFII)Lnet/minecraft/client/gl/DynamicUniforms$ChunkSectionsValue;"))
    private net.minecraft.client.gl.DynamicUniforms.ChunkSectionsValue betterlitematica$origin(org.joml.Matrix4fc view,int x,int y,int z,float visibility,int atlasWidth,int atlasHeight,Operation<net.minecraft.client.gl.DynamicUniforms.ChunkSectionsValue> original,@Local Buffers buffers){
        var origin=TerrainRegions.INSTANCE.origin(buffers);return original.call(view,origin==null?x:origin.x(),origin==null?y:origin.y(),origin==null?z:origin.z(),visibility,atlasWidth,atlasHeight);
    }
}
