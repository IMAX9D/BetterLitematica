package dev.betterlitematica.fabric.mixin;
import java.util.LinkedHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.client.render.WorldRenderer$RenderableChunks")
public interface TerrainVisibilitySetAccessor {
    @Accessor("chunks") LinkedHashSet<?> betterlitematica$chunks();
}
