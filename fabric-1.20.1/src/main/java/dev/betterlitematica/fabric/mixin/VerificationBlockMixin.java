package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
abstract class VerificationBlockMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void betterlitematica$changed(BlockPos pos,BlockState state,boolean moved,CallbackInfoReturnable<BlockState> callback){if(callback.getReturnValue()!=null){var changedWorld=((WorldChunk)(Object)this).getWorld();dev.betterlitematica.fabric.ProjectionReceiptTracker.observed(changedWorld,pos);BetterLitematicaClient.projectionBlockChanged(changedWorld,pos);}}
}
