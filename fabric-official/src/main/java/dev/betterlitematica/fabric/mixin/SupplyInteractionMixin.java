package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MultiPlayerGameMode.class)
abstract class SupplyInteractionMixin {
    @Inject(method="useItemOn",at=@At("HEAD"))
    private void betterlitematica$manualBlock(net.minecraft.client.player.LocalPlayer player,net.minecraft.world.InteractionHand hand,net.minecraft.world.phys.BlockHitResult hit,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.InteractionResult> callback){BetterLitematicaClient.manualSignInteraction(hit.getBlockPos());BetterLitematicaClient.manualContainerInteraction();}
    @Inject(method="handleContainerInput",at=@At("HEAD"))
    private void betterlitematica$manual(int sync,int slot,int button,ContainerInput type,Player player,CallbackInfo callback){BetterLitematicaClient.manualInventory();}
}
