package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets={"fi.dy.masa.litematica.event.InputHandler","me.aleksilassila.litematica.printer.config.InputHandler"},remap=false)
abstract class LegacyHotkeysMixin {
    @Inject(method={"addKeysToMap","addHotkeys"},at=@At("HEAD"),cancellable=true,require=0)
    private void betterlitematica$controls(CallbackInfo ci){ci.cancel();}
}
