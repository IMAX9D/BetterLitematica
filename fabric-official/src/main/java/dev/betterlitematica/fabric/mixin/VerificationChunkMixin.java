package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ClientChunkCache.class)
abstract class VerificationChunkMixin {
    @Inject(method="replaceWithPacketData",at=@At("RETURN"))
    private void betterlitematica$loaded(CallbackInfoReturnable<LevelChunk> callback){var chunk=callback.getReturnValue();if(chunk!=null)BetterLitematicaClient.projectionChunkChanged(chunk.getLevel(),chunk.getPos().x(),chunk.getPos().z());}
    @Inject(method="drop",at=@At("RETURN"))
    private void betterlitematica$unloaded(net.minecraft.world.level.ChunkPos pos,CallbackInfo callback){BetterLitematicaClient.projectionChunkChanged(((ClientChunkCache)(Object)this).getLevel(),pos.x(),pos.z());}
}
