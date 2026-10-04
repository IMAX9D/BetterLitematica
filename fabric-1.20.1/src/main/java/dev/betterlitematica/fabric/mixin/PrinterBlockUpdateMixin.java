package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPlayNetworkHandler.class)
abstract class PrinterBlockUpdateMixin {
    @Inject(method="onCustomPayload",at=@At("HEAD"))
    private void betterlitematica$capability(CustomPayloadS2CPacket packet,CallbackInfo callback){dev.betterlitematica.fabric.AccuratePlacement.payload((ClientPlayNetworkHandler)(Object)this,packet);}
    @Inject(method="onInventory",at=@At("TAIL"))
    private void betterlitematica$inventory(InventoryS2CPacket packet,CallbackInfo callback){dev.betterlitematica.fabric.InventoryReceipts.inventory(packet);BetterLitematicaClient.supplyInventory(packet.getSyncId());BetterLitematicaClient.containerInventory(packet);}
    @Inject(method="onScreenHandlerSlotUpdate",at=@At("TAIL"))
    private void betterlitematica$slot(ScreenHandlerSlotUpdateS2CPacket packet,CallbackInfo callback){dev.betterlitematica.fabric.InventoryReceipts.slot(packet);BetterLitematicaClient.containerSlot(packet);}
    @Inject(method="onOpenScreen",at=@At("TAIL"))
    private void betterlitematica$open(OpenScreenS2CPacket packet,CallbackInfo callback){BetterLitematicaClient.supplyOpened(packet.getSyncId(),packet.getScreenHandlerType());}
    @Inject(method="onOpenScreen",at=@At("HEAD"))
    private void betterlitematica$opening(OpenScreenS2CPacket packet,CallbackInfo callback){BetterLitematicaClient.containerOpening(packet.getScreenHandlerType());}
    @Inject(method="onBlockUpdate",at=@At("TAIL"))
    private void betterlitematica$block(BlockUpdateS2CPacket packet,CallbackInfo callback){BetterLitematicaClient.confirmedProjectionBlock(packet.getPos(),packet.getState());}
    @Inject(method="onChunkDeltaUpdate",at=@At("TAIL"))
    private void betterlitematica$section(ChunkDeltaUpdateS2CPacket packet,CallbackInfo callback){packet.visitUpdates(BetterLitematicaClient::confirmedProjectionBlock);}
}
