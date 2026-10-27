package com.retrievepanel.printer.mixin;
import dev.betterlitematica.fabric.BackpackPrinterBridge;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leave IPN config untouched. Rebase its monitors through its own normal screen-return path. */
@Mixin(targets="org.anti_ad.mc.ipnext.event.AutoRefillHandler",remap=false)
public abstract class IpnAutoRefillMixin {
    @Shadow private static boolean screenOpening;
    @Inject(method="onTickInGame",at=@At("HEAD"),cancellable=true)
    private void retrieve$inventoryOwner(CallbackInfo ci){
        if(BackpackPrinterBridge.suppressIpnAutoRefill()){
            screenOpening=true; // IPN init() runs once on its next permitted in-game tick.
            ci.cancel();
        }
    }
}
