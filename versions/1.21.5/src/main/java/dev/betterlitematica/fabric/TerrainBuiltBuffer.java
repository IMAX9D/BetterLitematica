package dev.betterlitematica.fabric;
import java.nio.ByteBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.util.BufferAllocator;
/** Worker-owned native mesh, released only after its stamped upload completes. */
public final class TerrainBuiltBuffer {
    final ByteBuffer vertices,indices;final int vertexCount,indexCount,indexBytes;final boolean indexOnly;private final Runnable release;final TerrainRegions.Origin origin;final boolean ordered;
    public TerrainBuiltBuffer(BuiltBuffer data,TerrainBuildStamp stamp,boolean ordered){
        var p=data.getDrawParameters();if(p.format().getVertexSize()!=32||p.mode()!=VertexFormat.DrawMode.QUADS)throw new IllegalArgumentException("地形顶点格式不受支持");
        origin=TerrainRegions.origin(stamp.x(),stamp.y(),stamp.z());this.ordered=ordered;
        vertices=data.getBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());indices=data.getSortedBuffer()==null?null:data.getSortedBuffer().duplicate();
        vertexCount=p.vertexCount();indexCount=p.indexCount();indexBytes=p.indexType().size;indexOnly=false;release=data::close;
        // Sorting centres were captured before this transfer; shift only the worker-owned vertices.
        int dx=stamp.x()-origin.x(),dy=stamp.y()-origin.y(),dz=stamp.z()-origin.z();
        for(int i=0,pointer=vertices.position();i<vertexCount;i++,pointer+=32){
            vertices.putFloat(pointer,vertices.getFloat(pointer)+dx);vertices.putFloat(pointer+4,vertices.getFloat(pointer+4)+dy);vertices.putFloat(pointer+8,vertices.getFloat(pointer+8)+dz);
        }
    }
    public TerrainBuiltBuffer(BufferAllocator.CloseableBuffer data,VertexFormat.IndexType type){
        origin=null;ordered=true;vertices=null;indices=data.getBuffer().duplicate();vertexCount=indexCount=0;indexBytes=type.size;indexOnly=true;release=data::close;
    }
    public void release(){release.run();}
}
