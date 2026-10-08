package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class VerificationBlockMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void betterlitematica$changed(BlockPos pos,BlockState state,int flags,CallbackInfoReturnable<BlockState> callback){if(callback.getReturnValue()!=null)BetterLitematicaClient.projectionBlockChanged(((LevelChunk)(Object)this).getLevel(),pos);}
}
