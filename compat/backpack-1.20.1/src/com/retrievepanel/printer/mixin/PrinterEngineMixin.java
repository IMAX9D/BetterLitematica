package com.retrievepanel.printer.mixin;
import dev.betterlitematica.fabric.BackpackPrinterBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.betterlitematica.fabric.PrinterEngine",remap=false)
public abstract class PrinterEngineMixin {
    @Inject(method="tick",at=@At(value="INVOKE",target="Ldev/betterlitematica/fabric/PrinterActions;supplyTick(I)Z"))
    private void retrieve$preflight(CallbackInfo ci){BackpackPrinterBridge.beforeSupplyTick(this);}
}
