package dev.betterlitematica.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterlitematica.core.RangeAllocator;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * Block geometry of every section in a 256x128x256 arena shares a few large vertex buffers, so a frame issues one
 * multi-draw per page instead of one draw per section part. Vertex positions are relative to the arena origin, so
 * one model-view matrix serves the whole arena. Render thread only.
 */
final class ProjectionPages {
    static final int STRIDE=24,ARENA_XZ=256,ARENA_Y=128;
    /** Vertices; a page grows with its arena, from 96 KiB to 6 MiB. Parts never exceed 16384 vertices. */
    private static final int MIN_PAGE=4096,MAX_PAGE=262144,MAX_PART_INDICES=16384/4*6;
    record Origin(int x,int y,int z){}
    static Origin origin(int x,int y,int z){return new Origin(Math.floorDiv(x,ARENA_XZ)*ARENA_XZ,Math.floorDiv(y,ARENA_Y)*ARENA_Y,Math.floorDiv(z,ARENA_XZ)*ARENA_XZ);}

    private final class Arena {
        final Origin origin;final ArrayList<Page> pages=new ArrayList<>();long live,frame=-1;boolean tinted;
        Arena(Origin origin){this.origin=origin;}
    }
    private final class Page {
        final Arena arena;final RangeAllocator ranges;final int vao,vbo;int slices;
        final Commands normal=new Commands(),tinted=new Commands();
        Page(Arena arena,int capacity){
            this.arena=arena;ranges=new RangeAllocator(capacity);
            vao=GlStateManager._glGenVertexArrays();vbo=GlStateManager._glGenBuffers();
            BufferRenderer.resetCurrentVertexBuffer();GlStateManager._glBindVertexArray(vao);
            try{
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER,(long)capacity*STRIDE,GL15.GL_STATIC_DRAW);
                // The same attribute layout MC's own buffers use for this format; the element buffer binding is VAO state.
                VertexFormats.POSITION_TEXTURE_COLOR.setupState();
                RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS).bindAndGrow(MAX_PART_INDICES);
            }finally{GlStateManager._glBindVertexArray(0);GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,0);}
            reservedBytes+=(long)capacity*STRIDE;pageCount++;
        }
        void delete(){GlStateManager._glDeleteVertexArrays(vao);GlStateManager._glDeleteBuffers(vbo);reservedBytes-=(long)ranges.capacity()*STRIDE;pageCount--;}
    }
    /** One section part inside a page; {@link #free()} returns its range, and an empty page deletes its buffers. */
    final class Slice {
        private final Page page;final int first,vertices;private boolean freed;
        private Slice(Page page,int first,int vertices){this.page=page;this.first=first;this.vertices=vertices;}
        void free(){
            if(freed)return;freed=true;page.ranges.release(first,vertices);page.slices--;page.arena.live-=vertices;
            if(page.slices==0){page.delete();page.arena.pages.remove(page);if(page.arena.pages.isEmpty())arenas.remove(page.arena.origin);}
        }
    }
    /** Draws collected for one page in one pass; near sections are added first. */
    private static final class Commands {
        int size;int[] firstQuad=new int[16],quads=new int[16],base=new int[16];
        void add(int first,int count,int vertex){
            if(size==quads.length){firstQuad=Arrays.copyOf(firstQuad,size*2);quads=Arrays.copyOf(quads,size*2);base=Arrays.copyOf(base,size*2);}
            firstQuad[size]=first;quads[size]=count;base[size]=vertex;size++;
        }
    }
    private final Map<Origin,Arena> arenas=new HashMap<>();
    private final ArrayList<Arena> order=new ArrayList<>();
    private long frame,reservedBytes;private int pageCount;
    private IntBuffer counts=BufferUtils.createIntBuffer(256),bases=BufferUtils.createIntBuffer(256);private PointerBuffer offsets=BufferUtils.createPointerBuffer(256);

    Slice allocate(Origin origin,int vertices){
        if(vertices<1||vertices>16384)throw new IllegalArgumentException("Part outside page budget");
        var arena=arenas.computeIfAbsent(origin,Arena::new);
        for(var page:arena.pages){int at=page.ranges.allocate(vertices);if(at>=0)return slice(page,at,vertices);}
        // Sparse arenas keep small pages; dense ones double towards the largest page.
        long wanted=Math.max(vertices,Math.min(MAX_PAGE,Long.highestOneBit(Math.max(1,arena.live+vertices))*2));
        var page=new Page(arena,(int)Math.max(MIN_PAGE,wanted));arena.pages.add(page);
        return slice(page,page.ranges.allocate(vertices),vertices);
    }
    private Slice slice(Page page,int first,int vertices){page.slices++;page.arena.live+=vertices;return new Slice(page,first,vertices);}
    void upload(Slice slice,ByteBuffer vertices){
        if(slice.freed||vertices.remaining()!=slice.vertices*STRIDE)throw new IllegalArgumentException("Vertex data does not match its slice");
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,slice.page.vbo);
        try{GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,(long)slice.first*STRIDE,vertices);}finally{GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,0);}
    }

    void beginFrame(){frame++;order.clear();}
    boolean queued(){return !order.isEmpty();}
    /** Queue quads [firstQuad, firstQuad+quads) of a slice; tinted quads draw in a second pass. */
    void add(Slice slice,int firstQuad,int quads,boolean tinted){
        if(quads<1)return;var page=slice.page;var arena=page.arena;
        if(arena.frame!=frame){arena.frame=frame;arena.tinted=false;order.add(arena);}
        (tinted?page.tinted:page.normal).add(firstQuad,quads,slice.first);arena.tinted|=tinted;
    }
    /** Issues the queued draws with the bound shader; returns the number of multi-draw calls. */
    int draw(ShaderProgram shader,Matrix4f view,double cameraX,double cameraY,double cameraZ,Matrix4f scratch){
        if(order.isEmpty())return 0;int calls=0;
        var indices=RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);var tint=shader.getUniform("SurfaceTint");boolean anyTinted=false;
        try{
            for(var arena:order){calls+=drawArena(shader,arena,false,view,cameraX,cameraY,cameraZ,scratch,indices);anyTinted|=arena.tinted;}
            if(anyTinted){
                if(tint!=null){tint.set(1);tint.upload();}
                for(var arena:order)if(arena.tinted)calls+=drawArena(shader,arena,true,view,cameraX,cameraY,cameraZ,scratch,indices);
                if(tint!=null){tint.set(0);tint.upload();}
            }
        }finally{
            for(var arena:order)for(var page:arena.pages){page.normal.size=0;page.tinted.size=0;}
            order.clear();GlStateManager._glBindVertexArray(0);BufferRenderer.resetCurrentVertexBuffer();
        }
        return calls;
    }
    private int drawArena(ShaderProgram shader,Arena arena,boolean tinted,Matrix4f view,double cameraX,double cameraY,double cameraZ,Matrix4f scratch,RenderSystem.ShapeIndexBuffer indices){
        int calls=0;boolean placed=false;
        for(var page:arena.pages){
            var commands=tinted?page.tinted:page.normal;if(commands.size==0)continue;
            if(!placed){
                scratch.set(view).translate((float)(arena.origin.x()-cameraX),(float)(arena.origin.y()-cameraY),(float)(arena.origin.z()-cameraZ));
                GlUniform model=shader.modelViewMat;if(model!=null){model.set(scratch);model.upload();}placed=true;
            }
            BufferRenderer.resetCurrentVertexBuffer();GlStateManager._glBindVertexArray(page.vao);
            indices.bindAndGrow(MAX_PART_INDICES);var type=indices.getIndexType();
            ensure(commands.size);counts.clear();offsets.clear();bases.clear();
            for(int i=0;i<commands.size;i++){counts.put(commands.quads[i]*6);offsets.put((long)commands.firstQuad[i]*6L*type.size);bases.put(commands.base[i]);}
            counts.flip();offsets.flip();bases.flip();
            GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES,counts,type.glType,offsets,bases);calls++;
        }
        return calls;
    }
    private void ensure(int size){
        if(counts.capacity()>=size)return;int next=Integer.highestOneBit(size-1)<<1;
        counts=BufferUtils.createIntBuffer(next);bases=BufferUtils.createIntBuffer(next);offsets=BufferUtils.createPointerBuffer(next);
    }
    int pages(){return pageCount;}
    long reservedBytes(){return reservedBytes;}
}
