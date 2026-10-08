package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.AccuratePlacement;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.*;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AccurateServerMixin {
    @Shadow public ServerPlayer player;
    // Tweakeroo redirects the same subtraction. Delegate through its existing
    // operation after correcting only our own encoded local placement position.
    @WrapOperation(method="handleUseItemOn",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;subtract(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 betterlitematica$physical(Vec3 hit,Vec3 center,Operation<Vec3> original,ServerboundUseItemOnPacket packet){return original.call(AccuratePlacement.physical(player,packet.getHand(),packet.getHitResult(),hit),center);}
    @WrapOperation(method="handleUseItemOn",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult betterlitematica$use(ServerPlayerGameMode manager,ServerPlayer player,Level world,ItemStack stack,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original){return AccuratePlacement.serverUse(player,hand,hit,()->original.call(manager,player,world,stack,hand,hit));}
}
