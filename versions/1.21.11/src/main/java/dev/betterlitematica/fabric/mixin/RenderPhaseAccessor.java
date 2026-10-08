package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderLayer.class)
public interface RenderPhaseAccessor {@Accessor("name") String betterlitematica$name();}
