package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.PrinterInteractionGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
abstract class PrinterManualUseMixin {
    @Inject(method="startUseItem",at=@At("HEAD"))
    private void betterlitematica$manualUse(CallbackInfo ci){PrinterInteractionGuard.manualUse();}
}
