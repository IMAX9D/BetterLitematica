package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.PasteBlockUpdates;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Wrap the existing callback chain, including Carpet's fillUpdates Redirect.
 * Outside this exact paste position the original operation is always delegated unchanged. */
@Mixin(WorldChunk.class)
abstract class PasteBlockMixin {
    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/block/BlockState;onBlockAdded(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V"))
    private void betterlitematica$pasteAdded(BlockState state,World world,BlockPos pos,BlockState previous,boolean moved,Operation<Void> original){
        if(!PasteBlockUpdates.quiet(world,pos))original.call(state,world,pos,previous,moved);
    }

    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/block/BlockState;onStateReplaced(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V"))
    private void betterlitematica$pasteReplaced(BlockState previous,World world,BlockPos pos,BlockState state,boolean moved,Operation<Void> original){
        if(!PasteBlockUpdates.quiet(world,pos)){original.call(previous,world,pos,state,moved);return;}
        // Preserve block-entity lifetime without starting neighbor physics or scattering inventories.
        if(previous.hasBlockEntity()&&previous.getBlock()!=state.getBlock())world.removeBlockEntity(pos);
    }
}
