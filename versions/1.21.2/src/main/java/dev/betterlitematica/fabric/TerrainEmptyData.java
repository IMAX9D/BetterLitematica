package dev.betterlitematica.fabric;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.Direction;
import java.util.List;

final class TerrainEmptyData extends ChunkBuilder.ChunkData {
    static final TerrainEmptyData INSTANCE=new TerrainEmptyData();
    private TerrainEmptyData(){occlusionGraph.fill(true);}
    @Override public boolean hasNonEmptyLayers(){return false;}
    @Override public boolean isEmpty(RenderLayer layer){return true;}
    @Override public List<BlockEntity> getBlockEntities(){return List.of();}
    @Override public boolean isVisibleThrough(Direction from,Direction to){return true;}
    static ChunkBuilder.ChunkData canonical(ChunkBuilder.ChunkData data,long connections){

        return data.getClass()==ChunkBuilder.ChunkData.class&&connections==(1L<<36)-1
                &&!data.hasNonEmptyLayers()&&data.getBlockEntities().isEmpty()?INSTANCE:data;
    }
}
