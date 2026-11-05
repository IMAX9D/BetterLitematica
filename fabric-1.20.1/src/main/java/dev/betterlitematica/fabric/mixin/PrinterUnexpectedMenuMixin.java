package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.PrinterInteractionGuard;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ClientPlayNetworkHandler.class,priority=1100)
abstract class PrinterUnexpectedMenuMixin {
    @Inject(method="onOpenScreen",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$unexpected(OpenScreenS2CPacket packet,CallbackInfo ci){if(PrinterInteractionGuard.opened(packet))ci.cancel();}
    @Inject(method="onPlayerActionResponse",at=@At("TAIL"))
    private void betterlitematica$ack(net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket packet,CallbackInfo ci){PrinterInteractionGuard.acknowledged(packet.sequence());}
}
