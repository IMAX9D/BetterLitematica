package dev.betterlitematica.fabric;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;

/** Preserve every unrelated physical input while temporarily evaluating a sneaking placement. */
final class InputState {
    private InputState(){}
    static void sneaking(ClientPlayerEntity player,boolean sneak){
        var old=player.input.playerInput;
        player.input.playerInput=new PlayerInput(old.forward(),old.backward(),old.left(),old.right(),old.jump(),sneak,old.sprint());
    }
}
