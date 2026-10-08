package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(InGameHud.class)
public abstract class WheelPlayerListMixin {
    // Share only this HUD query; keep vanilla visibility rules and avoid sticky global key state.
    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean betterlitematica$sharedHold(KeyBinding binding,Operation<Boolean> original){
        return original.call(binding)||(binding==MinecraftClient.getInstance().options.keyPlayerList&&BetterLitematicaClient.wheelSharesPlayerListHold());
    }
}
