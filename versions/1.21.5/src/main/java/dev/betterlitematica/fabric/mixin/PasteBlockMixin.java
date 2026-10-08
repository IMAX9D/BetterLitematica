package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.PasteBlockUpdates;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.*;

/** Suppress side effects only at an owned quiet-paste position; vanilla still removes old block entities. */
@Mixin(WorldChunk.class)
abstract class PasteBlockMixin {
    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/block/BlockState;onBlockAdded(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V"))
    private void added(BlockState state,World world,BlockPos pos,BlockState previous,boolean moved,Operation<Void> original){if(!PasteBlockUpdates.quiet(world,pos))original.call(state,world,pos,previous,moved);}
    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/block/BlockState;onStateReplaced(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Z)V"))
    private void replaced(BlockState previous,ServerWorld world,BlockPos pos,boolean moved,Operation<Void> original){if(!PasteBlockUpdates.quiet(world,pos))original.call(previous,world,pos,moved);}
    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/block/entity/BlockEntity;onBlockReplaced(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V"))
    private void replacedEntity(BlockEntity entity,BlockPos pos,BlockState previous,Operation<Void> original){if(!PasteBlockUpdates.quiet(((WorldChunk)(Object)this).getWorld(),pos))original.call(entity,pos,previous);}
}
