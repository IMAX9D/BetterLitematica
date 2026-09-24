package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderLayer.MultiPhaseParameters.class)
public interface RenderPhasesAccessor {@Accessor("texture") RenderPhase.TextureBase betterlitematica$texture();}
