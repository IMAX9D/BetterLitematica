package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.PrinterInteractionGuard;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
abstract class PrinterOutgoingMixin {
    @Inject(method="sendPacket(Lnet/minecraft/network/packet/Packet;)V",at=@At("HEAD"))
    private void betterlitematica$recordInteraction(Packet<?> packet,CallbackInfo callback){
        PrinterInteractionGuard.sent(packet);
    }
}
