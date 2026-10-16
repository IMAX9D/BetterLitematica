package dev.betterlitematica.fabric;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.Direction;
import java.util.List;

/** Built, completely open air. Distinct from Minecraft's not-yet-built EMPTY sentinel. */
final class TerrainEmptyData extends ChunkBuilder.ChunkData {
    static final TerrainEmptyData INSTANCE=new TerrainEmptyData();
    private TerrainEmptyData(){occlusionGraph.fill(true);}
    @Override public boolean isEmpty(){return true;}
    @Override public boolean isEmpty(RenderLayer layer){return true;}
    @Override public List<BlockEntity> getBlockEntities(){return List.of();}
    @Override public boolean isVisibleThrough(Direction from,Direction to){return true;}
    static ChunkBuilder.ChunkData canonical(ChunkBuilder.ChunkData data,long connections){
        // Custom data classes, invisible opaque sections and block-entity-only sections retain their
        // own data. Only immutable vanilla output with no geometry/entities and fully open faces shares.
        return data.getClass()==ChunkBuilder.ChunkData.class&&connections==(1L<<36)-1
                &&data.isEmpty()&&data.getBlockEntities().isEmpty()?INSTANCE:data;
    }
}
