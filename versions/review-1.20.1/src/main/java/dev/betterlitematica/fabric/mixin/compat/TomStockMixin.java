package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.nbt.NbtCompound;
@Pseudo
@Mixin(targets="com.tom.storagemod.gui.StorageTerminalMenu",remap=false)
abstract class TomStockMixin {
    @Inject(method="receiveClientNBTPacket",at=@At("TAIL"),require=0)
    private void betterlitematica$stock(NbtCompound packet,CallbackInfo ci){
        if(packet.contains("d"))dev.betterlitematica.fabric.RemoteSupply.observed(this);
    }
}
