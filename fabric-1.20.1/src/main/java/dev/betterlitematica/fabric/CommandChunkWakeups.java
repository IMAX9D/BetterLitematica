package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;
import java.util.function.*;

/** Chunk events are only discovery hints; unfinished source progress remains in DeferredSections. */
final class CommandChunkWakeups {
    private static final int MAX_HINTS=4096;
    private final PlacementLayout layout;private final LayerRange layer;private final int bottom,top;
    private final LinkedHashSet<Long> hints=new LinkedHashSet<>();
    private final int centerX,centerZ,radius;private int ring,edge;
    private List<PlacementLayout.Part> parts=List.of();private int partIndex;private PlacementBounds chunk;
    private int part,x,y,z,minX,minY,minZ,maxX,maxY,maxZ;private boolean range;

    CommandChunkWakeups(PlacementLayout layout,LayerRange layer,int bottom,int top,int centerX,int centerZ,int radius){
        this.layout=layout;this.layer=layer;this.bottom=bottom;this.top=top;this.centerX=centerX;this.centerZ=centerZ;this.radius=Math.max(0,Math.min(64,radius));
    }
    void loaded(int x,int z){
        long key=key(x,z);if(hints.contains(key))return;
        if(hints.size()==MAX_HINTS){var it=hints.iterator();it.next();it.remove();}
        hints.add(key);
    }
    void drain(DeferredSections sections,BiPredicate<Integer,Integer> loaded,ToIntFunction<SectionKey> admission,int budget,long deadline){
        while(budget-->0&&System.nanoTime()<deadline){
            if(range){
                if(!sections.prioritize(new SectionKey(part,x,y,z),admission))return;
                if(++y>maxY){y=minY;if(++x>maxX){x=minX;if(++z>maxZ)range=false;}}
                continue;
            }
            if(partIndex<parts.size()){range(parts.get(partIndex++));continue;}
            Long next=nextChunk();if(next==null)return;int cx=(int)(next>>32),cz=(int)(long)next;
            if(!loaded.test(cx,cz))continue;
            chunk=new PlacementBounds(new Vec3i(cx*16,bottom,cz*16),new Vec3i(cx*16+15,top-1,cz*16+15));
            parts=layout.overlapping(chunk);partIndex=0;
        }
    }
    private void range(PlacementLayout.Part candidate){
        var b=PlacementBounds.clipped(candidate.region(),candidate.transform(),layer);
        if(b==null||!PlacementLayout.intersects(b,chunk))return;
        var lo=new Vec3i(Math.max(b.min().x(),chunk.min().x()),Math.max(b.min().y(),chunk.min().y()),Math.max(b.min().z(),chunk.min().z()));
        var hi=new Vec3i(Math.min(b.max().x(),chunk.max().x()),Math.min(b.max().y(),chunk.max().y()),Math.min(b.max().z(),chunk.max().z()));
        var a=candidate.transform().inverse(lo).subtract(candidate.region().min());var c=candidate.transform().inverse(hi).subtract(candidate.region().min());
        minX=Math.min(a.x(),c.x())>>4;maxX=Math.max(a.x(),c.x())>>4;minY=Math.min(a.y(),c.y())>>4;maxY=Math.max(a.y(),c.y())>>4;minZ=Math.min(a.z(),c.z())>>4;maxZ=Math.max(a.z(),c.z())>>4;
        part=candidate.index();x=minX;y=minY;z=minZ;range=true;
    }
    private Long nextChunk(){
        if(!hints.isEmpty()){var it=hints.iterator();long result=it.next();it.remove();return result;}
        if(ring>radius)return null;if(ring==0){ring=1;return key(centerX,centerZ);}
        int side=edge/(2*ring),offset=edge%(2*ring),dx=0,dz=0;
        switch(side){case 0->{dx=-ring+offset;dz=-ring;}case 1->{dx=ring;dz=-ring+offset;}case 2->{dx=ring-offset;dz=ring;}case 3->{dx=-ring;dz=ring-offset;}default->throw new IllegalStateException();}
        long result=key(centerX+dx,centerZ+dz);if(++edge==8*ring){edge=0;ring++;}return result;
    }
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
