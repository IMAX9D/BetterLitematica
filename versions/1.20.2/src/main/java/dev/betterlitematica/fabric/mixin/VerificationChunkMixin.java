package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ClientChunkManager.class)
abstract class VerificationChunkMixin {
    @Inject(method="loadChunkFromPacket",at=@At("RETURN"))
    private void betterlitematica$loaded(CallbackInfoReturnable<WorldChunk> callback){var chunk=callback.getReturnValue();if(chunk!=null)BetterLitematicaClient.projectionChunkChanged(chunk.getWorld(),chunk.getPos().x,chunk.getPos().z);}
    @Inject(method="unload",at=@At("RETURN"))
    private void betterlitematica$unloaded(net.minecraft.util.math.ChunkPos pos,CallbackInfo callback){BetterLitematicaClient.projectionChunkChanged(((ClientChunkManager)(Object)this).getWorld(),pos.x,pos.z);}
}
