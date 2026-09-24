package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.*;

/** Live bounded observation, independent of full verification and printer work queues. */
final class NearbyProjectionHighlights {
    static final int MISSING_COLOR=0xffff8f29,WRONG_COLOR=0xffff3338;
    enum Kind { MISSING,WRONG,EXTRA }
    record Mark(long position,BlockState expected,Kind kind,int seen){}
    static final double OBSERVATION_RADIUS=8;
    private static final int CELLS_PER_TICK=512,MAX_PENDING=4096;
    private final LinkedHashMap<Long,HighlightCuboids.Box> cellBoxes=new LinkedHashMap<>();
    private List<HighlightCuboids.Box> boxes=List.of();private boolean boxesDirty;
    private final LinkedHashMap<Long,Mark> marks=new LinkedHashMap<>();
    private final LinkedHashSet<Long> events=new LinkedHashSet<>(),chunks=new LinkedHashSet<>();
    private final LinkedHashMap<Long,Integer> unknown=new LinkedHashMap<>();
    private Object world;private List<?> sources=List.of();private LayerRange layer=LayerRange.ALL;
    private NearbyScan scan;private ChunkScan chunkScan;private Vec3d eye=Vec3d.ZERO;private double reach;private int tick,turn;
    void clear(){cellBoxes.clear();boxes=List.of();boxesDirty=false;marks.clear();events.clear();chunks.clear();unknown.clear();chunkScan=null;world=null;sources=List.of();scan=null;eye=Vec3d.ZERO;reach=0;}
    void tick(MinecraftClient client,ProjectionController controller){
        if(client.world==null||client.player==null||client.interactionManager==null||!controller.projectionRenderingEnabled()||!controller.options().printer.highlights){clear();return;}
        var sources=controller.printerSources();if(sources.isEmpty()){clear();return;}
        advance(client.world,sources,controller.layerRange(),client.player.getEyePos(),controller.options().printer.highlightRange,controller::printerSampler,p->client.world.isChunkLoaded(p)?client.world.getBlockState(p):null,System::nanoTime);
    }
    void advance(Object nextWorld,List<?> nextSources,LayerRange nextLayer,Vec3d nextEye,double nextReach,Supplier<Function<Vec3i,ProjectionController.PrinterSample>> source,Function<BlockPos,BlockState> actual,LongSupplier clock){
        if(!Double.isFinite(nextReach)||nextReach<0||nextReach>32)throw new IllegalArgumentException("Highlight range must be 0–32");
        tick++;
        if(world!=nextWorld||!sources.equals(nextSources)||!layer.equals(nextLayer)||reach!=nextReach){clear();world=nextWorld;sources=List.copyOf(nextSources);layer=nextLayer;reach=nextReach;}
        boolean moved=!eye.equals(nextEye);eye=nextEye;
        if(moved){
            for(var iterator=marks.values().iterator();iterator.hasNext();){var mark=iterator.next();if(inRange(BlockPos.fromLong(mark.position())))continue;iterator.remove();dropBox(mark.position());}
            unknown.keySet().removeIf(key->!inRange(BlockPos.fromLong(key)));events.removeIf(key->!inRange(BlockPos.fromLong(key)));
        }
        if(nextWorld==null||nextSources.isEmpty()||reach<=0)return;
        if(scan==null)scan=new NearbyScan(eye,reach);scan.begin(eye);
        var sample=source.get();
        // Sampling allowance starts after snapshot preparation; it is not a whole-tick hard limit.
        long deadline=clock.getAsLong()+750_000L;int visited=0,retries=0;int[] rows={512};
        while(visited<CELLS_PER_TICK){
            long now=clock.getAsLong();if(visited>0&&now>=deadline)break;
            int phase=turn++&15;Vec3i at=null;
            if((phase&7)==0)at=scan.regular(rows);
            else if((phase&7)==1&&retries<64){at=retry();if(at!=null)retries++;}
            else if(phase==3)at=chunk();
            else if((phase&1)==0)at=event();
            else at=scan.frontier(rows);
            if(at==null)at=event();if(at==null)at=scan.frontier(rows);if(at==null)at=chunk();if(at==null)at=scan.regular(rows);
            if(at==null)break;visited++;
            var pos=new BlockPos(at.x(),at.y(),at.z());long key=pos.asLong();
            if(!inRange(pos)){remove(key);unknown.remove(key);continue;}
            var wanted=sample.apply(at);
            if(wanted==null||wanted.inside()&&wanted.state()==null){retryLater(key);remove(key);continue;}
            if(!wanted.inside()){unknown.remove(key);remove(key);continue;}
            var state=actual.apply(pos);
            if(state==null){retryLater(key);remove(key);continue;}
            unknown.remove(key);Kind kind=classify(wanted,state);
            if(kind==null){remove(key);continue;}
            put(new Mark(key,wanted.state(),kind,tick));
        }
    }
    private void retryLater(long key){if(unknown.size()<MAX_PENDING||unknown.containsKey(key))unknown.putIfAbsent(key,tick+2);}
    private Vec3i retry(){if(unknown.isEmpty())return null;var iterator=unknown.entrySet().iterator();var first=iterator.next();if(first.getValue()>tick)return null;long key=first.getKey();iterator.remove();return vector(BlockPos.fromLong(key));}
    private Vec3i event(){if(events.isEmpty())return null;var iterator=events.iterator();long key=iterator.next();iterator.remove();return vector(BlockPos.fromLong(key));}
    private Vec3i chunk(){
        if(chunkScan!=null&&!chunkScan.hasNext())chunkScan=null;
        if(chunkScan==null&&!chunks.isEmpty()){
            long nearest=0;double distance=Double.POSITIVE_INFINITY;
            for(long key:chunks){int x=(int)(key>>32),z=(int)key;double d=PrinterReach.distanceSquared(eye,new BlockPos(x*16+8,(int)Math.floor(eye.y),z*16+8));if(d<distance){nearest=key;distance=d;}}
            chunks.remove(nearest);chunkScan=new ChunkScan((int)(nearest>>32),(int)nearest);
        }
        return chunkScan!=null&&chunkScan.hasNext()?chunkScan.next():null;
    }
    static Kind classify(ProjectionController.PrinterSample wanted,BlockState actual){
        if(wanted==null||!wanted.inside()||wanted.state()==null||actual==null||actual==wanted.state())return null;
        if(wanted.state().isAir())return actual.isAir()?null:Kind.EXTRA;
        return actual.isAir()?Kind.MISSING:Kind.WRONG;
    }
    private boolean inRange(BlockPos pos){return layer.contains(vector(pos))&&PrinterReach.distanceSquared(eye,pos)<=reach*reach;}
    void changed(BlockPos pos,BlockState state){
        if(world==null)return;long key=pos.asLong();if(inRange(pos)&&events.size()<MAX_PENDING)events.add(key);
        var old=marks.get(key);if(old==null)return;
        Kind kind=classify(new ProjectionController.PrinterSample(true,old.expected()),state);
        if(kind==null||!inRange(pos))remove(key);else put(new Mark(old.position(),old.expected(),kind,tick));
    }
    void chunkChanged(int x,int z){chunkChanged(x,z,false);}
    void chunkChanged(int x,int z,boolean loaded){
        if(world==null||Math.abs((eye.x/16)-x)>reach/16+2||Math.abs((eye.z/16)-z)>reach/16+2)return;
        for(var iterator=marks.values().iterator();iterator.hasNext();){long key=iterator.next().position();var pos=BlockPos.fromLong(key);if((pos.getX()>>4)==x&&(pos.getZ()>>4)==z){if(loaded){if(events.size()<MAX_PENDING)events.add(key);}else{iterator.remove();dropBox(key);}}}
        if(chunks.size()<64)chunks.add(((long)x<<32)|(z&0xffffffffL));
    }
    private static Vec3i vector(BlockPos p){return new Vec3i(p.getX(),p.getY(),p.getZ());}
    private void dropBox(long key){if(cellBoxes.remove(key)!=null)boxesDirty=true;}
    private void remove(long key){if(marks.remove(key)!=null)dropBox(key);}
    private void put(Mark mark){
        var old=marks.get(mark.position());if(old!=null&&old.kind()==mark.kind()&&old.expected()==mark.expected())return;
        marks.put(mark.position(),mark);
        if(old==null||old.kind()!=mark.kind()){var at=vector(BlockPos.fromLong(mark.position()));cellBoxes.put(mark.position(),new HighlightCuboids.Box(at,at,mark.kind().ordinal()));boxesDirty=true;}
    }
    /** Stable unit geometry. Unchanged cells keep their box even when neighboring cells enter or leave. */
    List<HighlightCuboids.Box> boxes(){
        if(boxesDirty){boxes=List.copyOf(cellBoxes.values());boxesDirty=false;}return boxes;
    }
    Collection<Mark> marks(){return Collections.unmodifiableCollection(marks.values());}
    int unknownCount(){return unknown.size();}
    int eventCount(){return events.size();}
    static float alpha(long nanos){return .48f+.24f*(float)Math.sin((nanos%1_800_000_000L)*(2*Math.PI/1_800_000_000L));}
    private final class ChunkScan {
        final int x,y,z,nx,ny,nz;int cursor;
        ChunkScan(int cx,int cz){x=Math.max(cx*16,(int)Math.ceil(eye.x-reach-1));z=Math.max(cz*16,(int)Math.ceil(eye.z-reach-1));y=(int)Math.ceil(eye.y-reach-1);nx=Math.max(0,Math.min(cx*16+15,(int)Math.floor(eye.x+reach))-x+1);nz=Math.max(0,Math.min(cz*16+15,(int)Math.floor(eye.z+reach))-z+1);ny=(int)Math.floor(eye.y+reach)-y+1;}
        boolean hasNext(){return cursor<nx*ny*nz;}
        Vec3i next(){int at=cursor++;return new Vec3i(x+at%nx,y+at/(nx*nz),z+(at/nx)%nz);}
    }
}
