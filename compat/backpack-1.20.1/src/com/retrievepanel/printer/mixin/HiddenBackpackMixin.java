package com.retrievepanel.printer.mixin;

import com.retrievepanel.client.PrinterRefillSession;
import net.minecraft.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.fabricmc.fabric.impl.screenhandler.client.ClientNetworking",remap=false)
public abstract class HiddenBackpackMixin {
    @Inject(method="openScreen",at=@At("HEAD"),cancellable=true)
    private void retrieve$background(class_2960 id,int sync,class_2561 title,class_2540 buf,CallbackInfo ci){
        if(PrinterRefillSession.capture(id,sync,buf)){buf.release();ci.cancel();}
    }
}
