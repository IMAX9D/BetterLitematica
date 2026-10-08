package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.EditorInput;
import net.minecraft.client.KeyboardHandler;
import dev.betterlitematica.fabric.BetterLitematicaClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class EditorKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$physical(long window,int action,net.minecraft.client.input.KeyEvent event,CallbackInfo callback){action=dev.betterlitematica.fabric.NativeInput.action(action);int key=dev.betterlitematica.fabric.NativeInput.legacyKey(event.key()),scan=event.key();EditorInput.key(window,scan,action);if(BetterLitematicaClient.wheelKey(window,key,scan,action)){callback.cancel();return;}if(key>=0&&BetterLitematicaClient.toolInput(window,key,action)){callback.cancel();return;}if(key>=0)BetterLitematicaClient.inputEvent(window,key,action);}
}
