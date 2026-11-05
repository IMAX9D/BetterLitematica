package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPlayerInteractionManager.class)
abstract class SupplyInteractionMixin {
    @Inject(method="interactBlock",at=@At("HEAD"))
    private void betterlitematica$manualBlock(net.minecraft.client.network.ClientPlayerEntity player,net.minecraft.util.Hand hand,net.minecraft.util.hit.BlockHitResult hit,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.util.ActionResult> callback){dev.betterlitematica.fabric.PrinterInteractionGuard.manualUse();BetterLitematicaClient.manualSignInteraction(hit.getBlockPos());BetterLitematicaClient.manualContainerInteraction();}
    @Inject(method="interactItem",at=@At("HEAD"))
    private void betterlitematica$manualItem(PlayerEntity player,net.minecraft.util.Hand hand,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.util.ActionResult> ci){dev.betterlitematica.fabric.PrinterInteractionGuard.manualUse();}
    @Inject(method="clickSlot",at=@At("HEAD"))
    private void betterlitematica$manual(int sync,int slot,int button,SlotActionType type,PlayerEntity player,CallbackInfo callback){BetterLitematicaClient.manualInventory();}
}
