package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ProjectionReceiptTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
abstract class ProjectionReceiptMixin {
    @Inject(method="handleBlockUpdate",at=@At("HEAD"))
    private void betterlitematica$beginBlockVisibility(ClientboundBlockUpdatePacket packet,CallbackInfo callback){ProjectionReceiptTracker.begin();}
    @Inject(method="handleChunkBlocksUpdate",at=@At("HEAD"))
    private void betterlitematica$beginSectionVisibility(ClientboundSectionBlocksUpdatePacket packet,CallbackInfo callback){ProjectionReceiptTracker.begin();}
    @Inject(method="handleBlockUpdate",at=@At("TAIL"))
    private void betterlitematica$blockVisibility(ClientboundBlockUpdatePacket packet,CallbackInfo callback){try{ProjectionReceiptTracker.refresh(packet.getPos());}finally{ProjectionReceiptTracker.end();}}
    @Inject(method="handleChunkBlocksUpdate",at=@At("TAIL"))
    private void betterlitematica$sectionVisibility(ClientboundSectionBlocksUpdatePacket packet,CallbackInfo callback){try{packet.runUpdates((pos,state)->ProjectionReceiptTracker.refresh(pos));}finally{ProjectionReceiptTracker.end();}}
}
