package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.TerrainView;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Frustum.class)
public interface TerrainFrustumAccessor extends TerrainView {
    @Accessor("intersection") org.joml.FrustumIntersection betterlitematica$intersection();
    @Accessor("matrix") org.joml.Matrix4f betterlitematica$matrix();
    @Accessor("camX") double betterlitematica$x(); @Accessor("camY") double betterlitematica$y(); @Accessor("camZ") double betterlitematica$z();
}
