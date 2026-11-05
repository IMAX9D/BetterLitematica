package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BackgroundPrinting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class BackgroundPrintingMixin {
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screen/Screen;shouldPause()Z"))
    private boolean betterlitematica$backgroundWorld(Screen screen){return !BackgroundPrinting.active()&&screen.shouldPause();}
    @Inject(method="openPauseMenu",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$backgroundFocus(boolean pauseOnly,CallbackInfo ci){
        if(BackgroundPrinting.active()&&!MinecraftClient.getInstance().isWindowFocused())ci.cancel();
    }
}
