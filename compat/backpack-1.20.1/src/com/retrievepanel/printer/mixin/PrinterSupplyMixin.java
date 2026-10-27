package com.retrievepanel.printer.mixin;

import com.retrievepanel.client.PrinterRefillSession;
import com.retrievepanel.printer.*;
import dev.betterlitematica.fabric.BackpackPrinterBridge;
import net.minecraft.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="dev.betterlitematica.fabric.PrinterSupply",remap=false)
public abstract class PrinterSupplyMixin {
    @Shadow private class_1792 wanted;
    @Inject(method="request",at=@At("HEAD"),cancellable=true)
    private void retrieve$request(class_1792 item,@Coerce Object source,int tick,CallbackInfoReturnable<Boolean> cir){
        if(!PrinterRefillConfig.current.enabled||wanted!=null)return;
        var client=class_310.method_1551();
        if(client.field_1724==null||client.field_1724.method_7337()||item==class_1802.field_8162)return;
        cir.setReturnValue(BackpackPrinterBridge.request(this,item));
    }
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void retrieve$tick(int tick,CallbackInfoReturnable<Boolean> cir){if(PrinterRefillSession.activeFor(this)){BackpackPrinterBridge.tick(this);cir.setReturnValue(true);}}
    @Inject(method="active",at=@At("HEAD"),cancellable=true)
    private void retrieve$active(CallbackInfoReturnable<Boolean> cir){if(PrinterRefillSession.activeFor(this))cir.setReturnValue(true);}
    @Inject(method="opened",at=@At("HEAD"),cancellable=true)
    private void retrieve$opened(int sync,class_3917<?> type,CallbackInfo ci){if(PrinterRefillSession.activeFor(this))ci.cancel();}
    @Inject(method="reset",at=@At("HEAD"))
    private void retrieve$reset(boolean close,CallbackInfo ci){PrinterRefillSession.cancel(this,close);}
}
