package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.AccuratePlacement;
import net.minecraft.server.network.*;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.*;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(ServerPlayNetworkHandler.class)
public abstract class AccurateServerMixin {
    @Shadow public ServerPlayerEntity player;
    @Redirect(method="onPlayerInteractBlock",at=@At(value="INVOKE",target="Lnet/minecraft/util/math/Vec3d;subtract(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d betterlitematica$physical(Vec3d hit,Vec3d center,PlayerInteractBlockC2SPacket packet){return AccuratePlacement.physical(player,packet.getHand(),packet.getBlockHitResult(),hit).subtract(center);}
    @Redirect(method="onPlayerInteractBlock",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerPlayerInteractionManager;interactBlock(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;"))
    private ActionResult betterlitematica$use(ServerPlayerInteractionManager manager,ServerPlayerEntity player,World world,ItemStack stack,Hand hand,BlockHitResult hit){return AccuratePlacement.serverUse(player,hand,hit,()->manager.interactBlock(player,world,stack,hand,hit));}
}
