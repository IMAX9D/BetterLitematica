package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import dev.betterlitematica.fabric.InventoryReceipts;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** New direct inventory messages acknowledge owned operations only after vanilla has applied them. */
@Mixin(ClientPlayNetworkHandler.class)
abstract class InventoryDirectUpdateMixin {
    @Inject(method="onSetPlayerInventory",at=@At("TAIL"))
    private void playerInventory(SetPlayerInventoryS2CPacket packet,CallbackInfo ci){InventoryReceipts.player(packet.slot());BetterLitematicaClient.containerPlayer(packet.slot(),packet.contents());}
    @Inject(method="onSetCursorItem",at=@At("TAIL"))
    private void cursor(SetCursorItemS2CPacket packet,CallbackInfo ci){BetterLitematicaClient.containerCursor(packet.contents());}
}
