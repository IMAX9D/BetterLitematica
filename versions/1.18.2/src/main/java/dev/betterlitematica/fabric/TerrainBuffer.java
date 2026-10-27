package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import java.nio.ByteOrder;

/** A section/layer handle, with no GL objects until its first nonempty mesh arrives. */
public final class TerrainBuffer extends VertexBuffer {
    private volatile boolean closed;
    TerrainRegions.Slice slice;
    private long meshGeneration=Long.MIN_VALUE;
    private int relativeX,relativeY,relativeZ;

    public TerrainBuffer(){super();}

    public void prepare(TerrainBuiltBuffer data,TerrainBuildStamp stamp){
        var parameters=data.getParameters();
        if(parameters.format()!=VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL||parameters.mode()!=VertexFormat.DrawMode.QUADS)
            throw new IllegalArgumentException("地形顶点格式不受支持");
        if(parameters.indexOnly())return;
        var origin=TerrainRegions.origin(stamp.x(),stamp.y(),stamp.z());
        var vertices=data.getVertexBuffer().order(ByteOrder.nativeOrder());
        int dx=stamp.x()-origin.x(),dy=stamp.y()-origin.y(),dz=stamp.z()-origin.z();
        int base=vertices.position(),count=parameters.vertexCount();
        if((long)count*TerrainRegions.STRIDE!=vertices.remaining())throw new IllegalArgumentException("地形顶点长度不一致");
        for(int i=0,p=base;i<count;i++,p+=TerrainRegions.STRIDE){
            vertices.putFloat(p,vertices.getFloat(p)+dx);
            vertices.putFloat(p+4,vertices.getFloat(p+4)+dy);
            vertices.putFloat(p+8,vertices.getFloat(p+8)+dz);
        }
        TerrainUploadQueue.preparedBytes.add(vertices.remaining());
    }

    public void accept(TerrainBuiltBuffer data,TerrainBuildStamp stamp){
        RenderSystem.assertOnRenderThread();
        var transfer=beginUpload(data,stamp);if(transfer==null)return;
        try{while(!transfer.done())transfer.step();finishUpload(transfer,stamp);}catch(RuntimeException|Error failure){transfer.abort();throw failure;}
    }
    TerrainRegions.Transfer beginUpload(TerrainBuiltBuffer data,TerrainBuildStamp stamp){
        var parameters=data.getParameters();
        if(parameters.indexCount()==0||parameters.vertexCount()==0){if(!parameters.indexOnly())clearMesh();return null;}
        // An old sorting job must not reorder a replacement mesh, even at the same coordinates.
        if(parameters.indexOnly()&&(slice==null||slice.retired||slice.page.closed||meshGeneration!=stamp.generation()
                ||parameters.indexCount()!=slice.indices||parameters.vertexCount()!=slice.vertices))return null;
        return TerrainRegions.INSTANCE.begin(data,TerrainRegions.origin(stamp.x(),stamp.y(),stamp.z()),slice);
    }
    void finishUpload(TerrainRegions.Transfer transfer,TerrainBuildStamp stamp){
        var next=transfer.commit();
        if(transfer.indexOnly)return;
        var previous=slice;slice=next;meshGeneration=stamp.generation();
        if(next!=null){relativeX=stamp.x()-next.page.origin.x();relativeY=stamp.y()-next.page.origin.y();relativeZ=stamp.z()-next.page.origin.z();}
        if(previous!=null)TerrainRegions.INSTANCE.retire(previous);
    }

    public void clearMesh(){
        RenderSystem.assertOnRenderThread();
        if(slice!=null){TerrainRegions.INSTANCE.retire(slice);slice=null;}
        meshGeneration=Long.MIN_VALUE;
    }

    @Override public void upload(BufferBuilder builder){
        // All native uploads must carry a creation-time stamp. Silently accepting an unstamped
        // upload could draw old geometry at a new position after the section handle is recycled.
        builder.popData();throw new IllegalStateException("地形上传缺少版本标识");
    }
    @Override public void bind(){/* Binding is batched by region, not by section. */}
    @Override public void drawElements(){
        // Preserve the vanilla single-draw path for callers outside our batched world pass.
        if(slice==null||closed)return;
        var shader=RenderSystem.getShader();
        if(shader==null)return;
        var offset=shader.chunkOffset;
        if(offset==null){TerrainRegions.INSTANCE.drawOne(slice);return;}
        var values=offset.getFloatData();float x=values.get(0),y=values.get(1),z=values.get(2);
        offset.set(x-relativeX,y-relativeY,z-relativeZ);offset.upload();
        try{TerrainRegions.INSTANCE.drawOne(slice);}finally{offset.set(x,y,z);offset.upload();}
    }
    public VertexFormat getVertexFormat(){return VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL;}
    public boolean isClosed(){return closed;}
    @Override public void close(){if(!closed){closed=true;clearMesh();}}
}
