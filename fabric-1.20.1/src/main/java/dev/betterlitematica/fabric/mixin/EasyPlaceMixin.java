package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MinecraftClient.class)
abstract class EasyPlaceMixin {
    @Inject(method="doItemUse",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$use(CallbackInfo callback){if(BetterLitematicaClient.wheelBlocksWorldInput()||BetterLitematicaClient.editUse()){callback.cancel();return;}BetterLitematicaClient.manualPrinterInteraction();if(BetterLitematicaClient.useProjection())callback.cancel();}
    @Inject(method="doAttack",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$attack(CallbackInfoReturnable<Boolean> callback){if(BetterLitematicaClient.wheelBlocksWorldInput()||BetterLitematicaClient.editAttack()){callback.setReturnValue(false);return;}BetterLitematicaClient.manualPrinterInteraction();}
    @Inject(method="handleBlockBreaking",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$breaking(boolean breaking,CallbackInfo callback){if(breaking&&BetterLitematicaClient.wheelBlocksWorldInput()||BetterLitematicaClient.editActive()||BetterLitematicaClient.preservePrinterBreaking())callback.cancel();}
    @Inject(method="openPauseMenu",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$editMenu(boolean pauseOnly,CallbackInfo callback){if(BetterLitematicaClient.editEscape())callback.cancel();}
}
