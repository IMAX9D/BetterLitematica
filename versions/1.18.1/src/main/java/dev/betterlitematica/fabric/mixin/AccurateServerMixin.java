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
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
@Mixin(ServerPlayNetworkHandler.class)
public abstract class AccurateServerMixin {
    @Shadow public ServerPlayerEntity player;
    // This protocol checks block-center reach directly; only placement decoding needs a wrapper.
    @WrapOperation(method="onPlayerInteractBlock",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerPlayerInteractionManager;interactBlock(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;"))
    private ActionResult betterlitematica$use(ServerPlayerInteractionManager manager,ServerPlayerEntity player,World world,ItemStack stack,Hand hand,BlockHitResult hit,Operation<ActionResult> original){return AccuratePlacement.serverUse(player,hand,hit,()->original.call(manager,player,world,stack,hand,hit));}
}
