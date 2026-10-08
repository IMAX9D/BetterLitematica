package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Storage, terminal packets and standalone schematic stocking remain owned by Tom. */
@Pseudo
@Mixin(targets={"com.tom.storagemod.util.ClientPrinterStorageBridge","com.tom.storagemod.util.ClientPrinterAutoNavigator","com.tom.storagemod.util.ClientPrinterLayeredController","com.tom.storagemod.util.ClientPrinterNativeIntegration","com.tom.storagemod.util.ClientPrinterUpcomingDemandPlanner"},remap=false,priority=2000)
abstract class TomLegacyPrinterMixin {
    @Inject(method={"tick","install","toggle","toggleBackpackApi"},at=@At("HEAD"),cancellable=true,require=0)
    private static void betterlitematica$legacyPrinter(CallbackInfo ci){ci.cancel();}
}
