package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.betterlitematica.fabric.TerrainFrustumScope;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Frustum.class)
public abstract class TerrainFrustumExpandMixin {
    @WrapOperation(method="cubeInFrustum(DDDDDD)I",at=@At(value="INVOKE",target="Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I",remap=false))
    private int betterlitematica$conservative(FrustumIntersection view,float x1,float y1,float z1,float x2,float y2,float z2,Operation<Integer> original){
        float m=TerrainFrustumScope.margin();return original.call(view,x1-m,y1-m,z1-m,x2+m,y2+m,z2+m);
    }
}
