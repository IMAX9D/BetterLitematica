package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
abstract class PrinterSignMixin {
    @Inject(method="openTextEdit",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$writePrintedSign(SignBlockEntity sign,boolean front,CallbackInfo callback){
        if(BetterLitematicaClient.printerSignOpened(sign,front))callback.cancel();
    }
}
