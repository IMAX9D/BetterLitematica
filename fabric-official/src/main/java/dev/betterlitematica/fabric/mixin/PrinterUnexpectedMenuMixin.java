package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.PrinterInteractionGuard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ClientPacketListener.class,priority=1100)
abstract class PrinterUnexpectedMenuMixin {
    @Inject(method="handleOpenScreen",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$unexpected(ClientboundOpenScreenPacket packet,CallbackInfo ci){if(PrinterInteractionGuard.opened(packet))ci.cancel();}
    @Inject(method="handleBlockChangedAck",at=@At("TAIL"))
    private void betterlitematica$ack(net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket packet,CallbackInfo ci){PrinterInteractionGuard.acknowledged(packet.sequence());}
}
