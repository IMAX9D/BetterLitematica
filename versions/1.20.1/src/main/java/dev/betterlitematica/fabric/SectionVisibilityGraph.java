package dev.betterlitematica.fabric;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Accelerate the vanilla graph's far-section checks without changing its edges or ray samples. */
public final class SectionVisibilityGraph {
    private SectionVisibilityGraph() {}
    private static final AtomicLong GEOMETRY_REVISION=new AtomicLong();
    private static final AtomicInteger MOVING_STORAGES=new AtomicInteger();
    private static final ThreadLocal<Walk> WALK=ThreadLocal.withInitial(Walk::new);

    public static void beginStorageMove(){MOVING_STORAGES.incrementAndGet();GEOMETRY_REVISION.incrementAndGet();}
    public static void endStorageMove(){GEOMETRY_REVISION.incrementAndGet();MOVING_STORAGES.decrementAndGet();}
    public static void invalidateGeometry(){GEOMETRY_REVISION.incrementAndGet();}
    public static long geometryStamp(){
        if(MOVING_STORAGES.get()!=0)return Long.MIN_VALUE;
        long stamp=GEOMETRY_REVISION.get();
        return MOVING_STORAGES.get()==0?stamp:Long.MIN_VALUE;
    }
    public static void beginWalk(){WALK.get().paths.begin();}
    public static void endWalk(){WALK.get().paths.end();}

    public static boolean visible(BuiltChunkStorage chunks,WorldRenderer.ChunkInfoList infos,ClientWorld world,
                                  double remaining,Vec3d camera,Vec3d start,Vec3d step){
        Walk walk=WALK.get();walk.chunks=chunks;walk.infos=infos;
        long stamp=geometryStamp();
        try{
            int bottom=world.getBottomY(),top=world.getTopY();
            walk.paths.context(chunks,infos,world,stamp);
            boolean visible=walk.paths.visible(remaining,camera,start,step,bottom,top,walk,stamp!=Long.MIN_VALUE);
            // Camera relocation can overlap a background traversal. Never return a memoized answer
            // across that change; sample the current storage again using vanilla's exact rule.
            if(stamp!=geometryStamp()){
                walk.paths.invalidate();
                return sample(remaining,camera.x,camera.y,camera.z,start.x,start.y,start.z,
                        step.x,step.y,step.z,bottom,top,walk);
            }
            return visible;
        }finally{walk.chunks=null;walk.infos=null;}
    }

    private static final class Walk implements Lookup {
        private final BlockPos.Mutable pos=new BlockPos.Mutable();
        private final PositivePaths paths=new PositivePaths();
        private BuiltChunkStorage chunks;private WorldRenderer.ChunkInfoList infos;
        public boolean visited(int x,int y,int z){
            var chunk=chunks.getRenderedChunk(pos.set(x,y,z));
            return chunk!=null&&infos.getInfo(chunk)!=null;
        }
    }

    /** Membership only grows in one vanilla traversal. Only successes may be memoized. */
    static final class PositivePaths {
        private final Long2ObjectOpenHashMap<Vec3d> success=new Long2ObjectOpenHashMap<>(256);
        private boolean active;private double cameraX,cameraY,cameraZ;private int bottom,top;
        private Object storage,infos,world;private long revision;
        void invalidate(){success.clear();cameraX=Double.NaN;}
        void begin(){invalidate();active=true;storage=infos=world=null;}
        void end(){active=false;invalidate();storage=infos=world=null;}
        void context(Object storage,Object infos,Object world,long revision){
            if(this.storage!=storage||this.infos!=infos||this.world!=world||this.revision!=revision){
                invalidate();this.storage=storage;this.infos=infos;this.world=world;this.revision=revision;
            }
        }
        boolean visible(double remaining,Vec3d camera,Vec3d start,Vec3d step,int bottom,int top,Lookup lookup,boolean stable){
            if(camera.x!=cameraX||camera.y!=cameraY||camera.z!=cameraZ||bottom!=this.bottom||top!=this.top){
                invalidate();cameraX=camera.x;cameraY=camera.y;cameraZ=camera.z;this.bottom=bottom;this.top=top;
            }
            int x=(int)start.x,y=(int)start.y,z=(int)start.z;
            double dx=camera.x-start.x,dy=camera.y-start.y,dz=camera.z-start.z;
            boolean cacheable=active&&stable&&Double.isFinite(remaining)&&Double.isFinite(cameraX)
                    &&remaining==dx*dx+dy*dy+dz*dz
                    &&Double.isFinite(cameraY)&&Double.isFinite(cameraZ)&&start.x==x&&start.y==y&&start.z==z
                    &&((x|y|z)&15)==0&&x>=-33554432&&x<33554432&&z>=-33554432&&z<33554432
                    &&y>=-8388608&&y<8388608;
            long key=cacheable?ChunkSectionPos.asLong(x>>4,y>>4,z>>4):0;
            Vec3d cached=cacheable?success.get(key):null;
            // Compare the actual step, not a hash or an assumed normalization. A modified step
            // must not inherit a success from a different ray starting at the same corner.
            if(cached!=null&&cached.x==step.x&&cached.y==step.y&&cached.z==step.z)return true;
            boolean visible=sample(remaining,camera.x,camera.y,camera.z,start.x,start.y,start.z,
                    step.x,step.y,step.z,bottom,top,lookup);
            if(visible&&cacheable&&success.size()<65536)success.put(key,step);
            return visible;
        }
    }

    @FunctionalInterface public interface Lookup { boolean visited(int x,int y,int z); }

    /** Same arithmetic order, floor conversion, world bounds and 60-block stopping rule as vanilla. */
    public static boolean sample(double remaining,double cx,double cy,double cz,double x,double y,double z,
                                 double sx,double sy,double sz,int bottom,int top,Lookup lookup){
        while(remaining>3600.0){
            x+=sx;y+=sy;z+=sz;
            if(y>top||y<bottom)return true;
            if(!lookup.visited(MathHelper.floor(x),MathHelper.floor(y),MathHelper.floor(z)))return false;
            double dx=cx-x,dy=cy-y,dz=cz-z;
            remaining=dx*dx+dy*dy+dz*dz;
        }
        return true;
    }
}
