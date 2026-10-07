package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Pseudo
@Mixin(targets="fi.dy.masa.litematica.util.WorldUtils",remap=false)
abstract class LegacyEasyPlaceMixin {
    @Inject(method="easyPlaceOnUseTick",at=@At("HEAD"),cancellable=true,require=0)
    private static void betterlitematica$hold(CallbackInfo ci){ci.cancel();}
    @Inject(method="handleEasyPlace",at=@At("HEAD"),cancellable=true,require=0)
    private static void betterlitematica$place(CallbackInfoReturnable<Boolean> ci){ci.setReturnValue(false);}
}
