package dev.betterlitematica.fabric;
import java.nio.ByteBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
/** Adapts 1.18's single packed view without an extra mesh allocation. */
public final class TerrainBuiltBuffer {
    public record Parameters(VertexFormat format,VertexFormat.DrawMode mode,int vertexCount,int indexCount,VertexFormat.IntType indexType,boolean indexOnly,boolean sequentialIndex,int getBufferSize){}
    private final Parameters parameters;
    private final ByteBuffer vertices,indices;
    public TerrainBuiltBuffer(BufferBuilder builder){
        var data=builder.popData();var p=data.getFirst();var bytes=data.getSecond();
        int vertexBytes=p.isCameraOffset()?0:p.getLimit(),indexBytes=p.isTextured()?0:p.getVertexCount()*p.getElementFormat().size;
        vertices=bytes.duplicate().position(0).limit(vertexBytes).slice();
        indices=bytes.duplicate().position(vertexBytes).limit(vertexBytes+indexBytes).slice();
        parameters=new Parameters(p.getVertexFormat(),p.getMode(),p.getCount(),p.getVertexCount(),p.getElementFormat(),p.isCameraOffset(),p.isTextured(),vertexBytes+indexBytes);
    }
    public Parameters getParameters(){return parameters;}
    public ByteBuffer getVertexBuffer(){return vertices.duplicate();}
    public ByteBuffer getIndexBuffer(){return indices.duplicate();}
    public void release(){}
}
