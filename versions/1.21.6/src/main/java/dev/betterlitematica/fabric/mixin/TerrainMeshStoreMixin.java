package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.TerrainMeshStore;
import net.minecraft.client.render.chunk.*;
import net.minecraft.client.render.BlockRenderLayer;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
@Mixin(ChunkRenderData.class)
public abstract class TerrainMeshStoreMixin implements TerrainMeshStore {
    @Shadow @Final private Map<BlockRenderLayer,Buffers> buffersByLayer;
    public Buffers betterlitematica$get(Object layer){return buffersByLayer.get(layer);}
    public void betterlitematica$put(Object layer,Buffers value){var old=buffersByLayer.put((BlockRenderLayer)layer,value);if(old!=null&&old!=value)old.close();}
}
