package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
abstract class PrinterBlockUpdateMixin {
    @Inject(method="handleSetPlayerInventory",at=@At("TAIL"))
    private void betterlitematica$playerInventory(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet,CallbackInfo callback){dev.betterlitematica.fabric.InventoryReceipts.playerInventory(packet);BetterLitematicaClient.containerPlayerInventory(packet);}
    @Inject(method="handleSetCursorItem",at=@At("TAIL"))
    private void betterlitematica$cursor(net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket packet,CallbackInfo callback){BetterLitematicaClient.containerCursor(packet);}
    @Inject(method="handleContainerContent",at=@At("TAIL"))
    private void betterlitematica$inventory(ClientboundContainerSetContentPacket packet,CallbackInfo callback){dev.betterlitematica.fabric.InventoryReceipts.inventory(packet);BetterLitematicaClient.supplyInventory(packet.containerId());BetterLitematicaClient.containerInventory(packet);}
    @Inject(method="handleContainerSetSlot",at=@At("TAIL"))
    private void betterlitematica$slot(ClientboundContainerSetSlotPacket packet,CallbackInfo callback){dev.betterlitematica.fabric.InventoryReceipts.slot(packet);BetterLitematicaClient.containerSlot(packet);}
    @Inject(method="handleOpenScreen",at=@At("TAIL"))
    private void betterlitematica$open(ClientboundOpenScreenPacket packet,CallbackInfo callback){BetterLitematicaClient.supplyOpened(packet.getContainerId(),packet.getType());}
    @Inject(method="handleOpenScreen",at=@At("HEAD"))
    private void betterlitematica$opening(ClientboundOpenScreenPacket packet,CallbackInfo callback){BetterLitematicaClient.containerOpening(packet.getType());}
    @Inject(method="handleBlockUpdate",at=@At("TAIL"))
    private void betterlitematica$block(ClientboundBlockUpdatePacket packet,CallbackInfo callback){BetterLitematicaClient.confirmedProjectionBlock(packet.getPos(),packet.getBlockState());}
    @Inject(method="handleChunkBlocksUpdate",at=@At("TAIL"))
    private void betterlitematica$section(ClientboundSectionBlocksUpdatePacket packet,CallbackInfo callback){packet.runUpdates(BetterLitematicaClient::confirmedProjectionBlock);}
}
