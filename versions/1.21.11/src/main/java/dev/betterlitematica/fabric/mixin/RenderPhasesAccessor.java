package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
@Mixin(RenderSetup.class)
public interface RenderPhasesAccessor {@Accessor("textures") Map<String,RenderSetup.TextureSpec> betterlitematica$texture();}
