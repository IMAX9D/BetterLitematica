package com.retrievepanel.printer.mixin;
import dev.betterlitematica.fabric.BackpackPrinterBridge;
import net.minecraft.class_1792;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="dev.betterlitematica.fabric.PrinterActions",remap=false)
public abstract class PrinterMaterialMixin {
    @Inject(method="equipInternal",at=@At("HEAD"),cancellable=true)
    private void retrieve$used(class_1792 item,int tick,CallbackInfoReturnable<Object> ci){
        BackpackPrinterBridge.used(item,tick);
        if(item==net.minecraft.class_1802.field_8162&&BackpackPrinterBridge.emptyHand(this))ci.setReturnValue(BackpackPrinterBridge.waitOutcome());
    }
}
