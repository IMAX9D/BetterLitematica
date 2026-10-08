package dev.betterlitematica.fabric;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
/** Temporary action input keeps movement, jump and sprint from the physical input snapshot. */
final class PlayerInputBridge {
    static void shift(LocalPlayer player,boolean shift){var input=player.input.keyPresses;player.input.keyPresses=withShift(input,shift);}
    static Input withShift(Input value,boolean shift){return new Input(value.forward(),value.backward(),value.left(),value.right(),value.jump(),shift,value.sprint());}
    static void sendShift(LocalPlayer player,boolean shift){player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerInputPacket(withShift(player.input.keyPresses,shift)));}
}
