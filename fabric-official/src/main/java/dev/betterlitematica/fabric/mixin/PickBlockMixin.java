package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class PickBlockMixin {
    @Inject(method="pickBlockOrEntity",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$pick(CallbackInfo callback){if(BetterLitematicaClient.wheelBlocksWorldInput()){callback.cancel();return;}BetterLitematicaClient.manualPrinterInteraction();if(BetterLitematicaClient.pickProjection())callback.cancel();}
}
