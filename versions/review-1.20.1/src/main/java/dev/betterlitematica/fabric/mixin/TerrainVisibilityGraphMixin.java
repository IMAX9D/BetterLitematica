package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.betterlitematica.fabric.SectionVisibilityGraph;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Vanilla-only graph walk. OptionalMixins leaves replacement renderers, including external Sodium, in control. */
@Mixin(WorldRenderer.class)
public abstract class TerrainVisibilityGraphMixin {
    @ModifyVariable(method="collectRenderableChunks",at=@At("STORE"),index=11)
    private boolean betterlitematica$graphInsteadOfRay(boolean far){return false;}

    @ModifyExpressionValue(method="collectRenderableChunks",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/WorldRenderer;getAdjacentChunk(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;Lnet/minecraft/util/math/Direction;)Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;"))
    private ChunkBuilder.BuiltChunk betterlitematica$outward(ChunkBuilder.BuiltChunk result,
            @Local(index=7) BlockPos cameraSection,@Local(index=10) ChunkBuilder.BuiltChunk source,
            @Local(index=15) Direction side,@Local(argsOnly=true) boolean culling) {
        if(result==null||!culling)return result;
        BlockPos at=source.getOrigin();
        return SectionVisibilityGraph.outward(at.getX(),at.getY(),at.getZ(),cameraSection.getX(),cameraSection.getY(),cameraSection.getZ(),side)?result:null;
    }

    @WrapOperation(method="collectRenderableChunks",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$ChunkData;isVisibleThrough(Lnet/minecraft/util/math/Direction;Lnet/minecraft/util/math/Direction;)Z"))
    private boolean betterlitematica$angle(ChunkBuilder.ChunkData data,Direction from,Direction to,Operation<Boolean> original,
            @Local(index=10) ChunkBuilder.BuiltChunk source,@Local(argsOnly=true) Vec3d camera) {
        BlockPos at=source.getOrigin();
        return SectionVisibilityGraph.through(from,to,camera.x-at.getX()-8,camera.y-at.getY()-8,camera.z-at.getZ()-8)
                &&original.call(data,from,to);
    }
}
