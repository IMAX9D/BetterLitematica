package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ProjectionReceiptTracker;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPlayNetworkHandler.class)
abstract class ProjectionReceiptMixin {
    @Inject(method="onBlockUpdate",at=@At("HEAD"))
    private void betterlitematica$beginBlockVisibility(BlockUpdateS2CPacket packet,CallbackInfo callback){ProjectionReceiptTracker.begin();}
    @Inject(method="onChunkDeltaUpdate",at=@At("HEAD"))
    private void betterlitematica$beginSectionVisibility(ChunkDeltaUpdateS2CPacket packet,CallbackInfo callback){ProjectionReceiptTracker.begin();}
    @Inject(method="onBlockUpdate",at=@At("TAIL"))
    private void betterlitematica$blockVisibility(BlockUpdateS2CPacket packet,CallbackInfo callback){try{ProjectionReceiptTracker.refresh(packet.getPos());}finally{ProjectionReceiptTracker.end();}}
    @Inject(method="onChunkDeltaUpdate",at=@At("TAIL"))
    private void betterlitematica$sectionVisibility(ChunkDeltaUpdateS2CPacket packet,CallbackInfo callback){try{packet.visitUpdates((pos,state)->ProjectionReceiptTracker.refresh(pos));}finally{ProjectionReceiptTracker.end();}}
}
