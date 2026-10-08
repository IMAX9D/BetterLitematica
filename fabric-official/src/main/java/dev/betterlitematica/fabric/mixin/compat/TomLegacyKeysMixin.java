package dev.betterlitematica.fabric.mixin.compat;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets="com.tom.storagemod.StorageModClient",remap=false)
abstract class TomLegacyKeysMixin {
    @WrapOperation(method="onInitializeClient",at=@At(value="INVOKE",target="Lnet/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper;registerKeyBinding(Lnet/minecraft/class_304;)Lnet/minecraft/class_304;",remap=false),require=0)
    private KeyMapping betterlitematica$printerKeys(KeyMapping key,Operation<KeyMapping> original){
        return key.getName().startsWith("key.toms_storage.printer_")?key:original.call(key);
    }
}
