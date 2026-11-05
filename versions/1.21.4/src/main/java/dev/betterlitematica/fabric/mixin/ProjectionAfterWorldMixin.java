package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ProjectionShaderPass;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
abstract class ProjectionAfterWorldMixin {
    @Inject(method="renderWorld",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",shift=At.Shift.AFTER))
    private void betterlitematica$afterWorld(CallbackInfo callback){ProjectionShaderPass.flush(net.minecraft.client.MinecraftClient.getInstance().world);}
}
