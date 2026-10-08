package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderSetup.TextureSpec.class)
public interface TexturePhaseAccessor {@Accessor("location") Identifier betterlitematica$id();}
