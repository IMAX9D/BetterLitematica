package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Gui.class)
public abstract class WheelPlayerListMixin {
    // Share only this HUD query; keep vanilla visibility rules and avoid sticky global key state.
    @WrapOperation(method="extractTabList",at=@At(value="INVOKE",target="Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean betterlitematica$sharedHold(KeyMapping binding,Operation<Boolean> original){
        return original.call(binding)||(binding==Minecraft.getInstance().options.keyPlayerList&&BetterLitematicaClient.wheelSharesPlayerListHold());
    }
}
