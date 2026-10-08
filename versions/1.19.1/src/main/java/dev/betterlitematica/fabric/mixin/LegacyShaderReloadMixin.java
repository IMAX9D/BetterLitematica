package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ProjectionShaders;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
@Mixin(GameRenderer.class)
public abstract class LegacyShaderReloadMixin {
 @Inject(method="loadShaders",at=@At("HEAD")) private void beforeReload(ResourceManager resources,CallbackInfo ci){ProjectionShaders.close();}
 @Inject(method="loadShaders",at=@At("TAIL")) private void afterReload(ResourceManager resources,CallbackInfo ci)throws IOException{ProjectionShaders.reload(resources);}
 @Inject(method="close",at=@At("HEAD")) private void closePrograms(CallbackInfo ci){ProjectionShaders.close();}
}
