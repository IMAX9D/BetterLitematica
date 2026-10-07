package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
abstract class PrinterSignMixin {
    @Inject(method="openEditSignScreen",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$writePrintedSign(SignBlockEntity sign,boolean front,CallbackInfo callback){
        if(BetterLitematicaClient.printerSignOpened(sign,front))callback.cancel();
    }
}
