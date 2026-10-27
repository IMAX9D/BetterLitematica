package com.retrievepanel.printer.mixin;

import com.retrievepanel.client.PrinterRefillSession;
import net.minecraft.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=class_634.class,remap=false)
public abstract class PrinterReceiptsMixin {
    @Inject(method="method_11153",at=@At("TAIL"))
    private void retrieve$inventory(class_2649 packet,CallbackInfo ci){PrinterRefillSession.inventory(packet);}
    @Inject(method="method_11109",at=@At("TAIL"))
    private void retrieve$slot(class_2653 packet,CallbackInfo ci){PrinterRefillSession.slot(packet);}
}
