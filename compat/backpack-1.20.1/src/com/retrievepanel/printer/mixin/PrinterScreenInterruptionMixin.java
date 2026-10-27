package com.retrievepanel.printer.mixin;
import com.retrievepanel.client.PrinterRefillSession;
import net.minecraft.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=class_310.class,remap=false)
public abstract class PrinterScreenInterruptionMixin {
    @Inject(method="method_1507",at=@At("HEAD"))
    private void retrieve$userScreen(class_437 screen,CallbackInfo ci){PrinterRefillSession.userScreen(screen);}
}
