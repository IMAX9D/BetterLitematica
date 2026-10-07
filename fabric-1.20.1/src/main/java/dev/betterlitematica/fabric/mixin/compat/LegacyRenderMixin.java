package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets={"fi.dy.masa.litematica.render.LitematicaRenderer","fi.dy.masa.litematica.event.RenderHandler"},remap=false)
abstract class LegacyRenderMixin {
    @Inject(method={"renderSchematicOverlay","piecewisePrepareAndUpdate","piecewiseRenderSolid","piecewiseRenderCutoutMipped","piecewiseRenderCutout","piecewiseRenderTranslucent","piecewiseRenderOverlay","piecewiseRenderEntities","onRenderWorldLast","onRenderGameOverlayPost"},at=@At("HEAD"),cancellable=true,require=0)
    private void betterlitematica$render(CallbackInfo ci){ci.cancel();}
}
