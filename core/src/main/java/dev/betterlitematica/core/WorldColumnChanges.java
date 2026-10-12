package dev.betterlitematica.core;

import java.util.*;

/** Chunk packets invalidate columns, not a separate queue entry for every vertical section. */
public final class WorldColumnChanges {
    private final int capacity;
    private final LinkedHashMap<Long,PlacementBounds> columns=new LinkedHashMap<>();
    private boolean all;
    public WorldColumnChanges(int capacity){if(capacity<1||capacity>65536)throw new IllegalArgumentException("World change capacity");this.capacity=capacity;}
    public void chunk(int x,int z,int bottom,int topExclusive){
        if(all||topExclusive<=bottom)return;
        long key=((long)x<<32)|(z&0xffffffffL);
        var previous=columns.get(key);
        if(previous==null&&columns.size()>=capacity){columns.clear();all=true;return;}
        int low=previous==null?bottom:Math.min(bottom,previous.min().y());
        int high=previous==null?topExclusive-1:Math.max(topExclusive-1,previous.max().y());
        columns.put(key,new PlacementBounds(new Vec3i(x<<4,low,z<<4),new Vec3i((x<<4)+15,high,(z<<4)+15)));
    }
    public boolean takeAll(){if(!all)return false;all=false;return true;}
    public List<PlacementBounds> drain(int limit){
        if(limit<0)throw new IllegalArgumentException("Negative drain");
        var result=new ArrayList<PlacementBounds>(Math.min(limit,columns.size()));
        for(var iterator=columns.values().iterator();iterator.hasNext()&&result.size()<limit;){result.add(iterator.next());iterator.remove();}
        return List.copyOf(result);
    }
    public int pending(){return columns.size();}
    public void clear(){columns.clear();all=false;}
}
