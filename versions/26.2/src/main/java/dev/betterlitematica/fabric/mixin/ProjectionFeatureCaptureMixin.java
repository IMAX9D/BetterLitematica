package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ProjectionModels;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Redirect only our detached capture scope; all normal entity/BER drawing remains untouched. */
@Mixin(RenderTypeFeatureRenderer.class)
abstract class ProjectionFeatureCaptureMixin {
 @Inject(method="getVertexBuilder",at=@At("HEAD"),cancellable=true)
 private void betterlitematica$capture(RenderType type,CallbackInfoReturnable<VertexConsumer> result){var capture=ProjectionModels.capture(type);if(capture!=null)result.setReturnValue(capture);}
}
