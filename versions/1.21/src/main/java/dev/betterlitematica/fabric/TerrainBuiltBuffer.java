package dev.betterlitematica.fabric;
import java.nio.ByteBuffer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
/** Keeps vertex data and separately produced sorting data in the same stamped upload protocol. */
public final class TerrainBuiltBuffer {
    public record Parameters(VertexFormat format,VertexFormat.DrawMode mode,int vertexCount,int indexCount,VertexFormat.IndexType indexType,boolean indexOnly,boolean sequentialIndex,int getBufferSize){}
    private final Parameters parameters;private final ByteBuffer vertices,indices;private final Runnable release;
    public TerrainBuiltBuffer(BuiltBuffer data){
        var p=data.getDrawParameters();vertices=data.getBuffer();indices=data.getSortedBuffer();release=data::close;
        parameters=new Parameters(p.format(),p.mode(),p.vertexCount(),p.indexCount(),p.indexType(),false,indices==null,vertices.remaining()+(indices==null?0:indices.remaining()));
    }
    public TerrainBuiltBuffer(BufferAllocator.CloseableBuffer data,BuiltBuffer.SortState sort){
        vertices=null;indices=data.getBuffer();release=data::close;
        parameters=new Parameters(VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL,VertexFormat.DrawMode.QUADS,sort.centroids().length*4,sort.centroids().length*6,sort.indexType(),true,false,indices.remaining());
    }
    public Parameters getParameters(){return parameters;}
    public ByteBuffer getVertexBuffer(){return vertices==null?null:vertices.duplicate();}
    public ByteBuffer getIndexBuffer(){return indices==null?null:indices.duplicate();}
    public void release(){release.run();}
}
