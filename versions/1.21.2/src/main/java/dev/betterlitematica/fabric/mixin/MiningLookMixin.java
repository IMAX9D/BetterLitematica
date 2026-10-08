package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Hold only an owned, short pre-placement server rotation; the camera is untouched. */
@Mixin(PlayerMoveC2SPacket.class)
abstract class MiningLookMixin {
    @ModifyVariable(method="<init>(DDDFFZZZZ)V",at=@At("HEAD"),ordinal=0,argsOnly=true)
    private static float betterlitematica$miningYaw(float yaw){return BetterLitematicaClient.miningYaw(yaw);}
    @ModifyVariable(method="<init>(DDDFFZZZZ)V",at=@At("HEAD"),ordinal=1,argsOnly=true)
    private static float betterlitematica$miningPitch(float pitch){return BetterLitematicaClient.miningPitch(pitch);}
}
