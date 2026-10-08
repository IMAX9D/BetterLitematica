package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.PasteBlockUpdates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Wrap the existing callback chain, including Carpet's fillUpdates Redirect.
 * Outside this exact paste position the original operation is always delegated unchanged. */
@Mixin(LevelChunk.class)
abstract class PasteBlockMixin {
    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private void betterlitematica$pasteAdded(BlockState state,Level world,BlockPos pos,BlockState previous,boolean moved,Operation<Void> original){
        if(!PasteBlockUpdates.quiet(world,pos))original.call(state,world,pos,previous,moved);
    }

    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;affectNeighborsAfterRemoval(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Z)V"))
    private void betterlitematica$pasteReplaced(BlockState previous,net.minecraft.server.level.ServerLevel world,BlockPos pos,boolean moved,Operation<Void> original){
        if(!PasteBlockUpdates.quiet(world,pos))original.call(previous,world,pos,moved);
    }
    @WrapOperation(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/BlockEntity;preRemoveSideEffects(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void betterlitematica$pasteInventory(net.minecraft.world.level.block.entity.BlockEntity entity,BlockPos pos,BlockState previous,Operation<Void> original){
        // Vanilla removes the old entity immediately after this callback; only drop/physics side effects are suppressed.
        if(!PasteBlockUpdates.quiet(entity.getLevel(),pos))original.call(entity,pos,previous);
    }
}
