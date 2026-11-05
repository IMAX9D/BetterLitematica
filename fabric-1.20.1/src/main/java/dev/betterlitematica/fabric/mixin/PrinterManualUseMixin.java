package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.PrinterInteractionGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftClient.class)
abstract class PrinterManualUseMixin {
    @Inject(method="doItemUse",at=@At("HEAD"))
    private void betterlitematica$manualUse(CallbackInfo ci){PrinterInteractionGuard.manualUse();}
    @Inject(method="setScreen",at=@At("HEAD"))
    private void betterlitematica$manualScreen(Screen screen,CallbackInfo ci){if(screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>)PrinterInteractionGuard.manualUse();}
}
