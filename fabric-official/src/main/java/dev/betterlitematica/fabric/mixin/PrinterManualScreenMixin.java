package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import dev.betterlitematica.fabric.PrinterInteractionGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
abstract class PrinterManualScreenMixin {
    @Inject(method="setScreen",at=@At("HEAD"))
    private void betterlitematica$manualScreen(Screen screen,CallbackInfo callback){
        if(screen instanceof AbstractContainerScreen<?>)PrinterInteractionGuard.manualUse();
    }
}
