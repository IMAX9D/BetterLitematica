package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {@Accessor("renderSetup") RenderSetup betterlitematica$phases();}
