package dev.betterlitematica.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterlitematica.core.RangeAllocator;
import dev.betterlitematica.fabric.TerrainChunkHandle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Independently implemented terrain transport, reusing BetterLitematica's region/page design.
 * Vanilla model, fluid, lighting and resource-pack shaders are the compatibility boundary.
 * Pages hold many sections. Opaque layers group by page; translucent layers only merge adjacent
 * runs, keeping the exact section and per-quad order chosen by the game's sorter.
 */
public final class TerrainRegions {
    public static final TerrainRegions INSTANCE=new TerrainRegions();
    static final int STRIDE=32;
    private static final int SIZE_XZ=128,SIZE_Y=64,MIN_VERTICES=4096,MAX_PAGE_VERTICES=262144;
    public record Origin(int x,int y,int z){}
    public static Origin origin(int x,int y,int z){return new Origin(Math.floorDiv(x,SIZE_XZ)*SIZE_XZ,Math.floorDiv(y,SIZE_Y)*SIZE_Y,Math.floorDiv(z,SIZE_XZ)*SIZE_XZ);}

    private final class Arena {
        final Origin origin;
        final ArrayList<Page> vertices=new ArrayList<>();
        final ArrayList<IndexPage> indices=new ArrayList<>();
        Arena(Origin origin){this.origin=origin;}
        void prune(){if(vertices.isEmpty()&&indices.isEmpty())arenas.remove(origin,this);}
    }
    final class Page {
        final Arena arena;final Origin origin;final RangeAllocator ranges;
        final Commands commands=new Commands();
        int vao,vbo,references;long pass;boolean closed;
        Page(Arena arena,int capacity){
            this.arena=arena;origin=arena.origin;ranges=new RangeAllocator(capacity);
            try{
                BufferRenderer.unbindAll();
                vao=GlStateManager._glGenVertexArrays();vbo=GlStateManager._glGenBuffers();
                GlStateManager._glBindVertexArray(vao);GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
                GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER,(long)capacity*STRIDE,GL15.GL_STATIC_DRAW);
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL.startDrawing();
            }catch(RuntimeException|Error failure){
                if(vao!=0)GlStateManager._glDeleteVertexArrays(vao);if(vbo!=0)GlStateManager._glDeleteBuffers(vbo);throw failure;
            }finally{unbind();}
            reservedBytes+=(long)capacity*STRIDE;pageCount++;
        }
        void close(){
            if(closed)return;closed=true;commands.close();
            GlStateManager._glDeleteVertexArrays(vao);GlStateManager._glDeleteBuffers(vbo);
            reservedBytes-=(long)ranges.capacity()*STRIDE;pageCount--;
        }
    }
    private final class IndexPage {
        final Arena arena;final VertexFormat.IntType type;final RangeAllocator ranges;
        int buffer,references;boolean closed;
        IndexPage(Arena arena,VertexFormat.IntType type,int capacity){
            this.arena=arena;this.type=type;ranges=new RangeAllocator(capacity);
            buffer=GlStateManager._glGenBuffers();
            try{
                // COPY_WRITE_BUFFER does not alter any VAO's element-buffer binding.
                GL15.glBindBuffer(GL32.GL_COPY_WRITE_BUFFER,buffer);
                GL15.glBufferData(GL32.GL_COPY_WRITE_BUFFER,(long)capacity*type.size,GL15.GL_DYNAMIC_DRAW);
            }catch(RuntimeException|Error failure){GlStateManager._glDeleteBuffers(buffer);throw failure;}
            finally{GL15.glBindBuffer(GL32.GL_COPY_WRITE_BUFFER,0);}
            reservedBytes+=(long)capacity*type.size;indexPageCount++;
        }
        void close(){if(closed)return;closed=true;GlStateManager._glDeleteBuffers(buffer);reservedBytes-=(long)ranges.capacity()*type.size;indexPageCount--;}
    }
    private final class IndexSlot {
        final IndexPage page;final int first,count;boolean released;
        IndexSlot(IndexPage page,int first,int count){this.page=page;this.first=first;this.count=count;page.references++;}
        void free(){
            if(released||page.closed)return;released=true;page.ranges.release(first,count);
            if(--page.references==0){page.close();page.arena.indices.remove(page);page.arena.prune();}
        }
    }
    final class Slice {
        final Page page;final int first,vertices,indices;IndexSlot sorted;boolean retired,released;
        Slice(Page page,int first,int vertices,int indices){this.page=page;this.first=first;this.vertices=vertices;this.indices=indices;page.references++;}
        void free(){
            if(released||page.closed)return;released=true;
            if(sorted!=null)sorted.free();page.ranges.release(first,vertices);
            if(--page.references==0){page.close();page.arena.vertices.remove(page);page.arena.prune();}
        }
    }
    /** A partial upload owns fresh ranges until commit; neither geometry nor sorted indices become
     * visible halfway through an upload. Byte offsets are long; allocator units remain vertices/indices. */
    final class Transfer {
        final Slice destination;final boolean indexOnly;final IndexSlot indices;
        private final ByteBuffer vertices,indexBytes;
        private final int vertexLength,indexLength;
        private boolean finished;
        Transfer(TerrainBuiltBuffer data,Origin origin,Slice existing){
            var parameters=data.getParameters();indexOnly=parameters.indexOnly();
            destination=indexOnly?existing:allocate(origin,parameters.vertexCount(),parameters.indexCount());
            IndexSlot allocated=null;
            try{
                if(!parameters.sequentialIndex())allocated=allocateIndices(destination.page.arena,parameters.indexType(),parameters.indexCount());
                indices=allocated;
                vertices=indexOnly?null:data.getVertexBuffer().slice();
                indexBytes=indices==null?null:data.getIndexBuffer().slice();
                vertexLength=vertices==null?0:vertices.remaining();indexLength=indexBytes==null?0:indexBytes.remaining();
            }catch(RuntimeException|Error failure){if(allocated!=null)allocated.free();if(!indexOnly)destination.free();throw failure;}
        }
        boolean done(){return (vertices==null||!vertices.hasRemaining())&&(indexBytes==null||!indexBytes.hasRemaining());}
        int step(){
            if(done())return 0;
            ByteBuffer source=vertices!=null&&vertices.hasRemaining()?vertices:indexBytes;
            boolean vertex=source==vertices;
            int end=source.limit(),length=Math.min(256<<10,source.remaining()),next=source.position()+length;
            long offset=vertex?(long)destination.first*STRIDE+vertexLength-source.remaining()
                    :(long)indices.first*indices.page.type.size+indexLength-source.remaining();
            try{
                source.limit(next);GL15.glBindBuffer(GL32.GL_COPY_WRITE_BUFFER,vertex?destination.page.vbo:indices.page.buffer);
                GL15.glBufferSubData(GL32.GL_COPY_WRITE_BUFFER,offset,source);source.position(next);
                return length;
            }finally{source.limit(end);GL15.glBindBuffer(GL32.GL_COPY_WRITE_BUFFER,0);}
        }
        Slice commit(){
            if(finished||!done())throw new IllegalStateException("地形数据尚未传输完成");
            finished=true;
            if(indexOnly&&destination.sorted!=null)pendingRetirement.add(destination.sorted);
            destination.sorted=indices;return destination;
        }
        void abort(){
            if(finished)return;finished=true;
            if(indices!=null)indices.free();if(!indexOnly)destination.free();
        }
    }
    private static final class Commands implements AutoCloseable {
        private IntBuffer counts,bases;private PointerBuffer offsets;private int size,maximumIndices,sequentialIndices;private boolean aligned=true;
        void clear(){size=0;maximumIndices=sequentialIndices=0;aligned=true;}
        void add(int count,int first,long offset){
            ensure(size+1);counts.put(size,count);bases.put(size,first);offsets.put(size,offset);size++;maximumIndices=Math.max(maximumIndices,count);
            aligned&=(first&3)==0;sequentialIndices=Math.max(sequentialIndices,first/4*6+count);
        }
        private void ensure(int required){
            if(counts!=null&&counts.capacity()>=required)return;
            int capacity=Math.max(16,Integer.highestOneBit(required-1)<<1);
            IntBuffer nextCounts=null,nextBases=null;PointerBuffer nextOffsets=null;
            try{
                nextCounts=MemoryUtil.memAllocInt(capacity);nextBases=MemoryUtil.memAllocInt(capacity);nextOffsets=MemoryUtil.memAllocPointer(capacity);
                for(int i=0;i<size;i++){nextCounts.put(i,counts.get(i));nextBases.put(i,bases.get(i));nextOffsets.put(i,offsets.get(i));}
            }catch(RuntimeException|Error failure){MemoryUtil.memFree(nextCounts);MemoryUtil.memFree(nextBases);MemoryUtil.memFree(nextOffsets);throw failure;}
            close();counts=nextCounts;bases=nextBases;offsets=nextOffsets;
        }
        int draw(int type,boolean sequential){
            if(size==0)return 0;
            counts.position(0).limit(size);bases.position(0).limit(size);offsets.position(0).limit(size);
            int calls;
            if(sequential&&aligned){
                int bytes=type==GL11.GL_UNSIGNED_SHORT?2:4;
                for(int i=0;i<size;i++)offsets.put(i,(long)(bases.get(i)/4)*6*bytes);
                // Absolute sequential indices avoid base-vertex multi-draw lighting differences
                // observed with Minecraft's integer lightmap attribute. Keep one call per page.
                GL14.glMultiDrawElements(GL11.GL_TRIANGLES,counts,type,offsets);calls=1;
            }else{
                // Sorted indices are section-local. Preserve order and exact vanilla attribute
                // addressing while still sharing the region binding and uniform update.
                for(int i=0;i<size;i++)GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES,counts.get(i),type,offsets.get(i),bases.get(i));
                calls=size;
            }
            counts.limit(counts.capacity());bases.limit(bases.capacity());offsets.limit(offsets.capacity());
            return calls;
        }
        @Override public void close(){MemoryUtil.memFree(counts);MemoryUtil.memFree(bases);MemoryUtil.memFree(offsets);counts=null;bases=null;offsets=null;}
    }
    private record Retirement(long fence,ArrayList<Object> allocations){}
    private final Map<Origin,Arena> arenas=new HashMap<>();
    private final ArrayList<Page> active=new ArrayList<>();
    private final Commands ordered=new Commands();
    private final ArrayDeque<Retirement> retirement=new ArrayDeque<>();
    private ArrayList<Object> pendingRetirement=new ArrayList<>();
    private long pass,reservedBytes;private int pageCount,indexPageCount;
    public long drawCalls,drawnSections;

    private static int capacity(int required,int minimum,int maximum){
        if(required<1)throw new IllegalArgumentException("地形缓冲区为空");
        if(required>maximum)return required;
        return Math.max(minimum,Math.min(maximum,Integer.highestOneBit(required-1)<<1));
    }
    private Slice allocate(Origin origin,int vertices,int indices){
        var arena=arenas.computeIfAbsent(origin,Arena::new);
        for(var page:arena.vertices){int first=page.ranges.allocate(vertices);if(first>=0)return new Slice(page,first,vertices,indices);}
        int wanted=capacity(vertices,MIN_VERTICES,MAX_PAGE_VERTICES);
        if(!arena.vertices.isEmpty())wanted=Math.max(wanted,Math.min(MAX_PAGE_VERTICES,arena.vertices.get(arena.vertices.size()-1).ranges.capacity()*2));
        var page=new Page(arena,wanted);arena.vertices.add(page);
        return new Slice(page,page.ranges.allocate(vertices),vertices,indices);
    }
    private IndexSlot allocateIndices(Arena arena,VertexFormat.IntType type,int count){
        for(var page:arena.indices)if(page.type==type){int first=page.ranges.allocate(count);if(first>=0)return new IndexSlot(page,first,count);}
        var page=new IndexPage(arena,type,capacity(count,MIN_VERTICES*3,MAX_PAGE_VERTICES*3));arena.indices.add(page);
        return new IndexSlot(page,page.ranges.allocate(count),count);
    }
    Transfer begin(TerrainBuiltBuffer data,Origin origin,Slice existing){return new Transfer(data,origin,existing);}
    void retire(Slice slice){if(!slice.retired&&!slice.page.closed){slice.retired=true;pendingRetirement.add(slice);}}

    /** Recycle only after the GPU has consumed earlier draws. No client wait with a nonzero timeout. */
    public void beginFrame(){
        while(!retirement.isEmpty()){
            var oldest=retirement.peekFirst();int status=GL32.glClientWaitSync(oldest.fence,0,0);
            if(status!=GL32.GL_ALREADY_SIGNALED&&status!=GL32.GL_CONDITION_SATISFIED)break;
            retirement.removeFirst();GL32.glDeleteSync(oldest.fence);
            for(Object allocation:oldest.allocations){if(allocation instanceof Slice slice)slice.free();else ((IndexSlot)allocation).free();}
        }
    }
    public void endFrame(){
        if(pendingRetirement.isEmpty())return;
        retirement.addLast(new Retirement(GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0),pendingRetirement));
        pendingRetirement=new ArrayList<>();
    }
    public void clear(){
        RenderSystem.assertOnRenderThread();
        for(var batch:retirement)GL32.glDeleteSync(batch.fence);retirement.clear();pendingRetirement.clear();
        for(var arena:arenas.values()){for(var page:arena.vertices)page.close();for(var page:arena.indices)page.close();}
        arenas.clear();active.clear();ordered.close();ordered.clear();TerrainUploadQueue.visibleHoles=false;
    }

    public void draw(ObjectArrayList<?> visible,RenderLayer layer,Shader shader,double x,double y,double z){
        RenderSystem.assertOnRenderThread();
        try{
            if(layer==RenderLayer.getTranslucent())drawOrdered(visible,layer,shader,x,y,z);
            else drawOpaque(visible,layer,shader,x,y,z);
        }finally{unbind();}
    }
    private static Slice slice(Object info,RenderLayer layer){
        var section=((TerrainChunkHandle)info).betterlitematica$chunk();
        if(section.getData().isEmpty(layer))return null;
        var buffer=section.getBuffer(layer);
        if(!(buffer instanceof TerrainBuffer nativeBuffer))throw new IllegalStateException("地形缓冲区未完成接管");
        var slice=nativeBuffer.slice;
        return slice==null||slice.retired||slice.page.closed?null:slice;
    }
    private void drawOpaque(ObjectArrayList<?> visible,RenderLayer layer,Shader shader,double x,double y,double z){
        active.clear();pass++;Object[] values=visible.elements();int sections=0;
        for(int i=0,size=visible.size();i<size;i++){
            var slice=slice(values[i],layer);if(slice==null)continue;
            // Opaque layers should be sequential. Preserve correctness if a mod supplies sorted data.
            if(slice.sorted!=null){drawOrdered(visible,layer,shader,x,y,z);return;}
            var page=slice.page;
            if(page.pass!=pass){page.pass=pass;page.commands.clear();active.add(page);}
            page.commands.add(slice.indices,slice.first,0);sections++;
        }
        drawnSections+=sections;
        for(var page:active)drawBatch(page,null,page.commands,shader,x,y,z);
    }
    private void drawOrdered(ObjectArrayList<?> visible,RenderLayer layer,Shader shader,double x,double y,double z){
        Page current=null;IndexPage index=null;ordered.clear();Object[] values=visible.elements();
        boolean reverse=layer==RenderLayer.getTranslucent();
        for(int step=0,size=visible.size();step<size;step++){
            int i=reverse?size-1-step:step;var slice=slice(values[i],layer);if(slice==null)continue;
            var nextIndex=slice.sorted==null?null:slice.sorted.page;
            if(slice.page!=current||index!=nextIndex){
                if(current!=null)drawBatch(current,index,ordered,shader,x,y,z);
                ordered.clear();current=slice.page;index=nextIndex;
            }
            ordered.add(slice.indices,slice.first,slice.sorted==null?0:(long)slice.sorted.first*index.type.size);drawnSections++;
        }
        if(current!=null)drawBatch(current,index,ordered,shader,x,y,z);
    }
    private void drawBatch(Page page,IndexPage indices,Commands commands,Shader shader,double x,double y,double z){
        var origin=page.origin;
        shader.chunkOffset.set((float)(origin.x-x),(float)(origin.y-y),(float)(origin.z-z));shader.chunkOffset.upload();
        BufferRenderer.unbindAll();GlStateManager._glBindVertexArray(page.vao);
        if(indices==null){
            var sequential=RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS,commands.aligned?commands.sequentialIndices:commands.maximumIndices);
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,sequential.getId());
            drawCalls+=commands.draw(sequential.getElementFormat().type,true);
        }else{GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices.buffer);drawCalls+=commands.draw(indices.type.type,false);}
    }
    void drawOne(Slice slice){
        if(slice.page.closed||slice.retired)return;
        BufferRenderer.unbindAll();GlStateManager._glBindVertexArray(slice.page.vao);
        if(slice.sorted==null){
            var indices=RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS,slice.indices);GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices.getId());
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES,slice.indices,indices.getElementFormat().type,0L,slice.first);
        }else{
            var slot=slice.sorted;GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,slot.page.buffer);
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES,slice.indices,slot.page.type.type,(long)slot.first*slot.page.type.size,slice.first);
        }
    }
    private static void unbind(){GlStateManager._glBindVertexArray(0);GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,0);BufferRenderer.unbindAll();}
    public long reservedBytes(){return reservedBytes;}
    public int pages(){return pageCount+indexPageCount;}
}
