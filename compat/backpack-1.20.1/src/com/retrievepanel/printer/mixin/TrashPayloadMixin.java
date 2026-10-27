package com.retrievepanel.printer.mixin;

import com.retrievepanel.client.PrinterTrash;
import net.minecraft.class_634;
import net.minecraft.class_2658;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric cancels vanilla custom-payload handling after dispatching its receiver.
 * Observe inside that dispatch, before its decoder advances/releases the buffer.
 */
@Mixin(targets="net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon",remap=false)
public abstract class TrashPayloadMixin {
    @Shadow @Final private class_634 handler;
    @Inject(method="handle(Lnet/minecraft/class_2658;)Z",at=@At("HEAD"))
    private void retrieve$trash(class_2658 packet,CallbackInfoReturnable<Boolean> cir){PrinterTrash.payload(handler,packet);}
}
