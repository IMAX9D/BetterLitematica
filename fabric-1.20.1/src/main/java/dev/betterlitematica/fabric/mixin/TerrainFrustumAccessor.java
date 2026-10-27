package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Frustum.class)
public interface TerrainFrustumAccessor extends dev.betterlitematica.fabric.TerrainView {
    @Accessor("frustumIntersection") org.joml.FrustumIntersection betterlitematica$intersection();
    @Accessor("positionProjectionMatrix") Matrix4f betterlitematica$matrix();
    @Accessor("x") double betterlitematica$x();
    @Accessor("y") double betterlitematica$y();
    @Accessor("z") double betterlitematica$z();
}
