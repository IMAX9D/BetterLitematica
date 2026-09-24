package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderLayer.MultiPhase.class)
public interface RenderLayerAccessor {@Accessor("phases") RenderLayer.MultiPhaseParameters betterlitematica$phases();}
