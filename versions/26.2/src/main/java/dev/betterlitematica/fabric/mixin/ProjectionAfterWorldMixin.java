package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ProjectionShaderPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
abstract class ProjectionAfterWorldMixin {
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",shift=At.Shift.AFTER))
    private void betterlitematica$afterWorld(CallbackInfo callback){ProjectionShaderPass.flush(net.minecraft.client.Minecraft.getInstance().level);}
}
