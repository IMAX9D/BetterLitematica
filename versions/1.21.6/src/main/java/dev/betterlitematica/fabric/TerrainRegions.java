package dev.betterlitematica.fabric;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.betterlitematica.core.RangeAllocator;
import net.minecraft.client.render.chunk.Buffers;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;

/** Shared native terrain pages through the game's GPU API. Absolute 32-bit indices permit
 * suballocations on backends whose indexed draw API has no base-vertex argument. No GL casts. */
public final class TerrainRegions {
    public static final TerrainRegions INSTANCE=new TerrainRegions();
    public record Origin(int x,int y,int z){}
    public static Origin origin(int x,int y,int z){return new Origin(Math.floorDiv(x,128)*128,Math.floorDiv(y,64)*64,Math.floorDiv(z,128)*128);}
    public record Draw(GpuBuffer vertices,GpuBuffer indices,Origin origin,int firstIndex,int count,boolean ordered,Object uniformKey){}
    private final IdentityHashMap<Object,Draw> draws=new IdentityHashMap<>();
    public Origin origin(Buffers buffers){var slice=slices.get(buffers);return slice==null?null:slice.vertices.page.origin;}
    public Draw drawInfo(Object draw){return draws.get(draw);}
    public <T> T register(T draw,Buffers buffers,Object uniformKey){var slice=slices.get(buffers);if(slice!=null)draws.put(draw,new Draw(slice.vertices.page.buffer,slice.indices.page.buffer,slice.vertices.page.origin,slice.indices.first,slice.indices.count,slice.ordered,uniformKey));return draw;}
    private static final int STRIDE=32,CHUNK=256<<10;
    private final ArrayList<Page> vertexPages=new ArrayList<>(),indexPages=new ArrayList<>();
    private final IdentityHashMap<Buffers,Slice> slices=new IdentityHashMap<>();
    private final ArrayDeque<Retirement> retired=new ArrayDeque<>();
    private ArrayList<Slice> pending=new ArrayList<>();
    private ByteBuffer staging;
    public long uploadedBytes,drawnSections;private long reservedBytes;

    private final class Page {
        final boolean index;final Origin origin;final RangeAllocator allocator;final GpuBuffer buffer;int references;boolean closed;
        Page(boolean index,int capacity,Origin origin){
            this.index=index;this.origin=origin;allocator=new RangeAllocator(capacity);
            buffer=create(index,capacity*(index?4:STRIDE));reservedBytes+=capacity*(long)(index?4:STRIDE);
        }
        void close(){if(closed)return;closed=true;buffer.close();reservedBytes-=allocator.capacity()*(long)(index?4:STRIDE);}
    }
    private final class Range {
        final Page page;final int first,count;int references=1;
        Range(Page page,int first,int count){this.page=page;this.first=first;this.count=count;page.references++;}
        Range retain(){references++;return this;}
        void release(){
            if(--references!=0||page.closed)return;page.allocator.release(first,count);
            if(--page.references==0){page.close();(page.index?indexPages:vertexPages).remove(page);}
        }
    }
    static final class Slice {
        final Range vertices,indices;final boolean ordered;boolean freed;
        Slice(Range vertices,Range indices,boolean ordered){this.vertices=vertices;this.indices=indices;this.ordered=ordered;}
        void free(){if(freed)return;freed=true;vertices.release();indices.release();}
    }
    private record Retirement(GpuFence fence,ArrayList<Slice> slices){}

    private Range allocate(boolean index,int count,Origin origin){
        if(count<=0)throw new IllegalArgumentException("地形缓冲区为空");
        var pages=index?indexPages:vertexPages;
        for(var page:pages){if(!Objects.equals(page.origin,origin))continue;int first=page.allocator.allocate(count);if(first>=0)return new Range(page,first,count);}
        int capacity=Math.max(index?24576:16384,Math.max(count,Math.min(index?1572864:262144,Integer.highestOneBit(count-1)<<1)));
        var page=new Page(index,capacity,origin);pages.add(page);return new Range(page,page.allocator.allocate(count),count);
    }
    private static GpuBuffer create(boolean index,int bytes){
        return RenderSystem.getDevice().createBuffer(()->"BetterLitematica native terrain page",(index?GpuBuffer.USAGE_INDEX:GpuBuffer.USAGE_VERTEX)|GpuBuffer.USAGE_COPY_DST,bytes);
    }
    private static void write(GpuBuffer buffer,int offset,ByteBuffer bytes){RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(offset,bytes.remaining()),bytes);}
    private static GpuFence fence(){return RenderSystem.getDevice().createCommandEncoder().createFence();}

    final class Transfer {
        final TerrainBuiltBuffer mesh;final Slice destination;final Buffers previous;final boolean indexOnly;
        private final ByteBuffer vertices,sourceIndices;private int indexCursor;private boolean finished;
        Transfer(TerrainBuiltBuffer mesh,Buffers previous){
            this.mesh=mesh;this.previous=previous;indexOnly=mesh.indexOnly;
            Slice old=previous==null?null:slices.get(previous);
            if(indexOnly&&(old==null||old.freed))throw new IllegalStateException("排序的地形已被替换");
            Range verticesRange=indexOnly?old.vertices.retain():allocate(false,mesh.vertexCount,mesh.origin);
            Range indexRange=null;
            try{
                int count=indexOnly?previous.getIndexCount():mesh.indexCount;
                if(indexOnly&&(mesh.indices==null||mesh.indices.remaining()!=count*mesh.indexBytes))throw new IllegalArgumentException("地形排序数据长度不一致");
                indexRange=allocate(true,count,null);destination=new Slice(verticesRange,indexRange,indexOnly?old.ordered:mesh.ordered);
                vertices=mesh.vertices==null?null:mesh.vertices.duplicate();sourceIndices=mesh.indices==null?null:mesh.indices.duplicate().order(ByteOrder.nativeOrder());
            }catch(RuntimeException|Error failure){verticesRange.release();if(indexRange!=null)indexRange.release();throw failure;}
        }
        boolean done(){return (vertices==null||!vertices.hasRemaining())&&indexCursor==destination.indices.count;}
        int step(){
            if(vertices!=null&&vertices.hasRemaining()){
                int size=Math.min(CHUNK,vertices.remaining()),limit=vertices.limit(),offset=destination.vertices.first*STRIDE+mesh.vertices.remaining()-vertices.remaining();
                try{vertices.limit(vertices.position()+size);write(destination.vertices.page.buffer,offset,vertices);vertices.position(vertices.limit());}
                finally{vertices.limit(limit);}uploadedBytes+=size;return size;
            }
            int count=Math.min(CHUNK/4,destination.indices.count-indexCursor);if(count==0)return 0;
            if(staging==null)staging=MemoryUtil.memAlloc(CHUNK).order(ByteOrder.nativeOrder());staging.clear();
            for(int i=0;i<count;i++){
                int local;
                if(sourceIndices==null){int n=indexCursor+i;int quad=n/6*4;local=quad+switch(n%6){case 0->0;case 1->1;case 2,3->2;case 4->3;default->0;};}
                else local=switch(mesh.indexBytes){case 1->Byte.toUnsignedInt(sourceIndices.get());case 2->Short.toUnsignedInt(sourceIndices.getShort());case 4->sourceIndices.getInt();default->throw new IllegalArgumentException("地形索引格式不受支持");};
                if(local<0||local>=destination.vertices.count)throw new IllegalArgumentException("地形索引越界");
                staging.putInt(destination.vertices.first+local);
            }
            staging.flip();write(destination.indices.page.buffer,(destination.indices.first+indexCursor)*4,staging);indexCursor+=count;uploadedBytes+=count*4L;return count*4;
        }
        Buffers commit(){
            if(finished||!done())throw new IllegalStateException("地形尚未上传完成");finished=true;
            Buffers result=indexOnly?previous:new Buffers(destination.vertices.page.buffer,destination.indices.page.buffer,destination.indices.count,VertexFormat.IndexType.INT);
            if(indexOnly){result.setIndexBuffer(destination.indices.page.buffer);result.setIndexType(VertexFormat.IndexType.INT);}
            ((TerrainPooledBuffer)(Object)result).betterlitematica$pooled(true);
            var old=slices.put(result,destination);if(old!=null)pending.add(old);return result;
        }
        void abort(){if(!finished){finished=true;destination.free();}}
    }
    Transfer begin(TerrainBuiltBuffer mesh,Buffers previous){RenderSystem.assertOnRenderThread();return new Transfer(mesh,previous);}
    public int firstIndex(Buffers buffers){var slice=slices.get(buffers);if(slice==null)return 0;drawnSections++;return slice.indices.first;}
    public boolean owns(Buffers buffers){return slices.containsKey(buffers);}
    public void release(Buffers buffers){var slice=slices.remove(buffers);if(slice!=null)pending.add(slice);}
    public void beginFrame(){
        draws.clear();
        while(!retired.isEmpty()&&retired.peekFirst().fence.awaitCompletion(0)){
            var entry=retired.removeFirst();entry.fence.close();entry.slices.forEach(Slice::free);
        }
    }
    public void endFrame(){if(!pending.isEmpty()){retired.addLast(new Retirement(fence(),pending));pending=new ArrayList<>();}}
    public void clear(){
        RenderSystem.assertOnRenderThread();for(var batch:retired)batch.fence.close();retired.clear();pending.clear();slices.clear();
        vertexPages.forEach(Page::close);indexPages.forEach(Page::close);vertexPages.clear();indexPages.clear();
        MemoryUtil.memFree(staging);staging=null;draws.clear();TerrainMultiDraw.clear();TerrainUploadQueue.visibleHoles=false;
    }
    public int pages(){return vertexPages.size()+indexPages.size();}
    public long reservedBytes(){return reservedBytes;}
}
