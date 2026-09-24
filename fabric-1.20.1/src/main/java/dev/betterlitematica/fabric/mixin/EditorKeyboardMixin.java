package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.EditorInput;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
abstract class EditorKeyboardMixin {
    @Inject(method="onKey",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$physical(long window,int key,int scan,int action,int modifiers,CallbackInfo callback){EditorInput.key(window,scan,action);if(BetterLitematicaClient.wheelKey(window,key,scan,action)){callback.cancel();return;}if(key>=0)BetterLitematicaClient.inputEvent(window,key,action);}
}
