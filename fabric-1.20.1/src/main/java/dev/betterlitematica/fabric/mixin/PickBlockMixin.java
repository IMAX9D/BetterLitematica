package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class PickBlockMixin {
    @Inject(method="doItemPick",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$pick(CallbackInfo callback){if(BetterLitematicaClient.wheelBlocksWorldInput()){callback.cancel();return;}BetterLitematicaClient.manualPrinterInteraction();if(BetterLitematicaClient.pickProjection())callback.cancel();}
}
