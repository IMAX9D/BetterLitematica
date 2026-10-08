package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.AccuratePlacement;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe before Fabric releases its retained bytes; keep every other handler intact. */
@Mixin(value=ClientCommonNetworkHandler.class,priority=1100)
abstract class CarpetPayloadMixin {
    @Inject(method="onCustomPayload(Lnet/minecraft/network/packet/s2c/common/CustomPayloadS2CPacket;)V",at=@At("HEAD"))
    private void betterlitematica$carpet(CustomPayloadS2CPacket packet,CallbackInfo callback){
        if((Object)this instanceof ClientPlayNetworkHandler play)AccuratePlacement.payload(play,packet);
    }
}
