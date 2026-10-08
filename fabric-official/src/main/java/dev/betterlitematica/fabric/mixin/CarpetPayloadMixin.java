package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Observe the already registered Carpet payload without cancelling its receiver. */
@Mixin(value=ClientCommonPacketListenerImpl.class,priority=1100)
abstract class CarpetPayloadMixin {
    @Inject(method="handleCustomPayload",at=@At("HEAD"))
    private void betterlitematica$payload(ClientboundCustomPayloadPacket packet,CallbackInfo ci){if((Object)this instanceof ClientPacketListener connection)dev.betterlitematica.fabric.AccuratePlacement.payload(connection,packet);}
}
