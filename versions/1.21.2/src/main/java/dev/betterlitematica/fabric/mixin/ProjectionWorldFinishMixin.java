package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Composite after clouds and particles, while the camera matrix is still installed. */
@Mixin(WorldRenderer.class)
abstract class ProjectionWorldFinishMixin {
    @Inject(method="method_62214",at=@At(value="INVOKE_STRING",target="Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V",args="ldc=translucent"))
    private void entitiesFlushed(CallbackInfo ci){BetterLitematicaClient.captureEntityDepth();}
    @Inject(method="render",at=@At(value="INVOKE",target="Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;",shift=At.Shift.BEFORE,remap=false))
    private void completedScene(CallbackInfo ci){BetterLitematicaClient.finishProjectionFrame();}
}
