package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.BetterLitematicaClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Composite against the finished world, after clouds and translucent framebuffer merging. */
@Mixin(LevelRenderer.class)
public abstract class ProjectionLastMixin {
    @Inject(method="renderLevel",at=@At("RETURN"))
    private void betterlitematica$finishProjection(CallbackInfo callback){BetterLitematicaClient.finishProjectionFrame();}
}
