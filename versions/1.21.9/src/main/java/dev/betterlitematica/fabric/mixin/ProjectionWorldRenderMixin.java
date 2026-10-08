package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.betterlitematica.fabric.ProjectionWorldHooks;
import net.minecraft.client.render.*;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(WorldRenderer.class)
abstract class ProjectionWorldRenderMixin {
    @Inject(method="render",at=@At("HEAD"))
    private void betterlitematica$begin(CallbackInfo callback,@Local(argsOnly=true) Camera camera,@Local(argsOnly=true) RenderTickCounter ticks,@Local(argsOnly=true,ordinal=0) Matrix4f position,@Local(argsOnly=true,ordinal=1) Matrix4f projection){ProjectionWorldHooks.begin(camera,ticks,position,projection);}
    @Inject(method="setupFrustum",at=@At("RETURN"))
    private void betterlitematica$frustum(CallbackInfoReturnable<Frustum> callback){ProjectionWorldHooks.frustum(callback.getReturnValue());}
    @Inject(method="method_62214",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/WorldRenderer;pushEntityRenders(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/state/WorldRenderState;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V"))
    private void betterlitematica$beforeEntities(CallbackInfo callback){ProjectionWorldHooks.beforeEntities();}
    @Inject(method="method_62214",at=@At(value="INVOKE_STRING",target="Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V",args="ldc=translucent"))
    private void betterlitematica$afterEntities(CallbackInfo callback){ProjectionWorldHooks.afterEntities();}
    @Inject(method="render",at=@At(value="INVOKE",target="Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;",remap=false))
    private void betterlitematica$finish(CallbackInfo callback){ProjectionWorldHooks.finishMain();}
    @Inject(method="render",at=@At("RETURN"))
    private void betterlitematica$end(CallbackInfo callback){ProjectionWorldHooks.end();}
}
