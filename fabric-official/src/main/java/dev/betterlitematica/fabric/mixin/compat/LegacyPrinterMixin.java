package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler",remap=false,priority=2000)
abstract class LegacyPrinterMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,require=0)
    private void betterlitematica$printer(CallbackInfo ci){ci.cancel();}
}
