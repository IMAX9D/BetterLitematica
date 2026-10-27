package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.betterlitematica.fabric.TerrainRegions;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder.Buffers;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Keep native shaders, fog, ordering and drawMultipleIndexed; replace only buffer ranges. */
@Mixin(WorldRenderer.class)
public abstract class TerrainRegionDrawMixin {
    @Inject(method="render",at=@At("HEAD"))private void betterlitematica$begin(CallbackInfo ci){TerrainRegions.INSTANCE.beginFrame();}
    @Inject(method="render",at=@At("RETURN"))private void betterlitematica$end(CallbackInfo ci){TerrainRegions.INSTANCE.endFrame();}
    @Inject(method={"setWorld","reload()V","close"},at=@At("HEAD"))private void betterlitematica$clear(CallbackInfo ci){TerrainRegions.INSTANCE.clear();}
    @WrapOperation(method="renderLayer",at=@At(value="NEW",target="(ILcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;IILjava/util/function/Consumer;)Lcom/mojang/blaze3d/systems/RenderPass$RenderObject;"))
    private RenderPass.RenderObject betterlitematica$range(int slot,GpuBuffer vertices,GpuBuffer indices,VertexFormat.IndexType type,int first,int count,Consumer<RenderPass.UniformUploader> uniforms,Operation<RenderPass.RenderObject> original,@Local Buffers buffers,@Local(argsOnly=true,ordinal=0) double cameraX,@Local(argsOnly=true,ordinal=1) double cameraY,@Local(argsOnly=true,ordinal=2) double cameraZ){
        var origin=TerrainRegions.INSTANCE.origin(buffers);
        Consumer<RenderPass.UniformUploader> adjusted=origin==null?uniforms:uploader->{if(uniforms!=null)uniforms.accept(uploader);uploader.upload("ModelOffset",(float)(origin.x()-cameraX),(float)(origin.y()-cameraY),(float)(origin.z()-cameraZ));};
        var object=original.call(slot,vertices,indices,type,first+TerrainRegions.INSTANCE.firstIndex(buffers),count,adjusted);
        return TerrainRegions.INSTANCE.register(object,buffers,null);
    }
}
