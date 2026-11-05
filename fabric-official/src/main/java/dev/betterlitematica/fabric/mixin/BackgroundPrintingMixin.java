package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BackgroundPrinting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class BackgroundPrintingMixin {
    @Redirect(method="runTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;isPauseScreen()Z"))
    private boolean betterlitematica$backgroundWorld(Screen screen){return !BackgroundPrinting.active()&&screen.isPauseScreen();}
    @Inject(method="pauseGame",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$backgroundFocus(boolean pauseOnly,CallbackInfo ci){
        if(BackgroundPrinting.active()&&!Minecraft.getInstance().isWindowActive())ci.cancel();
    }
}
