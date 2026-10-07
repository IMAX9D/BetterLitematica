package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="fi.dy.masa.litematica.event.InputHandler",remap=false)
abstract class LegacyInputMixin {
    @Inject(method={"onKeyInput","onMouseClick","onMouseScroll"},at=@At("HEAD"),cancellable=true,require=0)
    private void betterlitematica$input(CallbackInfoReturnable<Boolean> ci){ci.setReturnValue(false);}
}
