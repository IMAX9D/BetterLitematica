package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.betterlitematica.core.RangeAllocator;
import dev.betterlitematica.runtime.RenderResources;
import java.nio.ByteBuffer;
import java.util.*;
import org.joml.Matrix4f;

/** Shared projection vertex pages through RenderPearl, valid on OpenGL and Vulkan. */
final class ProjectionPages {
    static final int STRIDE=24,ARENA_XZ=256,ARENA_Y=128;
    private static final int MIN_PAGE=4096,MAX_PAGE=262144;
    record Origin(int x,int y,int z){}
    static Origin origin(int x,int y,int z){return new Origin(Math.floorDiv(x,ARENA_XZ)*ARENA_XZ,Math.floorDiv(y,ARENA_Y)*ARENA_Y,Math.floorDiv(z,ARENA_XZ)*ARENA_XZ);}
    private final class Arena {
        final Origin origin;final ArrayList<Page> pages=new ArrayList<>();long live,frame=-1;
        Arena(Origin origin){this.origin=origin;}
    }
    private final class Page {
        final Arena arena;final RangeAllocator ranges;final RenderResources.SharedAllocation allocation;final ProjectionGpuMesh mesh;
        final Commands normal=new Commands(),tinted=new Commands();int slices;
        Page(Arena arena,int capacity,RenderResources.SharedAllocation allocation){
            this.arena=arena;this.allocation=allocation;ranges=new RangeAllocator(capacity);
            try{var buffer=RenderSystem.getDevice().createBuffer(()->"BetterLitematica projection page",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,(long)capacity*STRIDE);
                mesh=new ProjectionGpuMesh(buffer,capacity/4*6,PrimitiveTopology.QUADS);
            }catch(RuntimeException|Error failure){allocation.close();throw failure;}
            reservedBytes+=(long)capacity*STRIDE;pageCount++;
        }
        void delete(){mesh.close();allocation.close();reservedBytes-=(long)ranges.capacity()*STRIDE;pageCount--;}
    }
    final class Slice {
        final Page page;final int first,vertices;boolean freed;
        Slice(Page page,int first,int vertices){this.page=page;this.first=first;this.vertices=vertices;}
        void free(){
            if(freed)return;freed=true;
            // Reuse waits for all previously submitted draws, including deferred extraction commands.
            RenderSystem.queueFencedTask(()->{
                page.ranges.release(first,vertices);page.slices--;page.arena.live-=vertices;
                if(page.slices==0){page.delete();page.arena.pages.remove(page);if(page.arena.pages.isEmpty())arenas.remove(page.arena.origin);}
            });
        }
    }
    private static final class Commands {
        int size;int[] values=new int[32],prepared;
        void add(int firstQuad,int count){if(size+2>values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=firstQuad*6;values[size++]=count*6;prepared=null;}
        void clear(){size=0;prepared=null;}
        int[] ranges(){if(prepared==null)prepared=Arrays.copyOf(values,size);return prepared;}
    }
    private final Map<Origin,Arena> arenas=new HashMap<>();private final ArrayList<Arena> order=new ArrayList<>();
    private final RenderResources resources;private long frame,reservedBytes;private int pageCount;
    ProjectionPages(RenderResources resources){this.resources=resources;}
    Slice allocate(Origin origin,int vertices,RenderResources.Group owner,int demand){
        if(vertices<1||vertices>16384||(vertices&3)!=0)throw new IllegalArgumentException("投影顶点数量超出预算");
        owner.checkReference(vertices*STRIDE);var arena=arenas.computeIfAbsent(origin,Arena::new);
        for(var page:arena.pages){int at=page.ranges.allocate(vertices);if(at>=0)return slice(page,at,vertices,owner);}
        int wanted=(int)Math.max(MIN_PAGE,Math.max(vertices,Math.min(MAX_PAGE,Long.highestOneBit(Math.max(1,arena.live+vertices))*2)));
        long available=resources.availableBytes(owner.replacement())/STRIDE;int capacity=(int)Math.max(vertices,Math.min(wanted,available&~3L));
        var allocation=resources.reserveShared(capacity*STRIDE,1,owner,demand);
        if(allocation==null){if(arena.pages.isEmpty())arenas.remove(origin);return null;}
        var page=new Page(arena,capacity,allocation);arena.pages.add(page);return slice(page,page.ranges.allocate(vertices),vertices,owner);
    }
    private Slice slice(Page page,int first,int vertices,RenderResources.Group owner){page.slices++;page.arena.live+=vertices;var value=new Slice(page,first,vertices);owner.reference(vertices*STRIDE,value::free);return value;}
    void upload(Slice slice,ByteBuffer vertices){
        if(slice.freed||vertices.remaining()!=slice.vertices*STRIDE)throw new IllegalArgumentException("投影顶点数据不完整");
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice.page.mesh.vertices.slice((long)slice.first*STRIDE,vertices.remaining()),vertices);
    }
    void beginFrame(){for(var arena:order)for(var page:arena.pages){page.normal.clear();page.tinted.clear();}order.clear();frame++;}
    boolean queued(){return !order.isEmpty();}
    void add(Slice slice,int firstQuad,int count,boolean tinted){
        if(count<1||slice.freed)return;var page=slice.page;var arena=page.arena;
        if(arena.frame!=frame){arena.frame=frame;order.add(arena);}
        (tinted?page.tinted:page.normal).add(slice.first/4+firstQuad,count);
    }
    int draw(Matrix4f view,Matrix4f projection,double x,double y,double z,float opacity){
        int calls=0;
        for(boolean tinted:new boolean[]{false,true})for(var arena:order){
            var matrix=new Matrix4f(view).translate((float)(arena.origin.x-x),(float)(arena.origin.y-y),(float)(arena.origin.z-z));
            for(var page:arena.pages){var commands=tinted?page.tinted:page.normal;if(commands.size==0)continue;
                ProjectionDraw.page(page.mesh,matrix,projection,opacity,tinted,commands.ranges());calls++;
            }
        }
        return calls;
    }
    int pages(){return pageCount;}long reservedBytes(){return reservedBytes;}
}
