package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class ToolScrollMixin {
    @Inject(method="onButton",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$button(long window,net.minecraft.client.input.MouseButtonInfo info,int action,CallbackInfo callback){int button=info.button();if(BetterLitematicaClient.wheelMouse(window,button,action)){callback.cancel();return;}if(BetterLitematicaClient.toolInput(window,-button-1,action)){callback.cancel();return;}BetterLitematicaClient.inputEvent(window,-button-1,action);}
    @Inject(method="onScroll",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$scroll(long window,double horizontal,double vertical,CallbackInfo callback){if(BetterLitematicaClient.scrollTool(vertical))callback.cancel();}
}
