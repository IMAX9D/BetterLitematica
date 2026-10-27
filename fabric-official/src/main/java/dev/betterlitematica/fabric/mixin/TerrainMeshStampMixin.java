package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.*;
@Mixin(CompiledSectionMesh.class)
public abstract class TerrainMeshStampMixin implements TerrainMeshStamp {
    @Unique private TerrainBuildStamp betterlitematica$stamp;
    public void betterlitematica$stamp(TerrainBuildStamp stamp){betterlitematica$stamp=stamp;}
    public boolean betterlitematica$current(){return betterlitematica$stamp==null||betterlitematica$stamp.current();}
}
