package dev.betterlitematica.core;

import java.util.*;

/** Coalesces live-world notifications. Overflow requests a lazy full refresh instead of losing changes. */
public final class WorldSectionChanges {
    private final int capacity;
    private final LinkedHashSet<Vec3i> sections=new LinkedHashSet<>();
    private boolean all;
    public WorldSectionChanges(int capacity){if(capacity<1||capacity>65536)throw new IllegalArgumentException("World change capacity");this.capacity=capacity;}
    public void block(Vec3i position){section(new Vec3i(position.x()>>4,position.y()>>4,position.z()>>4));}
    private void section(Vec3i key){
        if(all||sections.contains(key))return;
        if(sections.size()>=capacity){sections.clear();all=true;return;}
        sections.add(key);
    }
    public void chunk(int x,int z,int bottom,int topExclusive){
        if(all||topExclusive<=bottom)return;
        int first=bottom>>4,last=(topExclusive-1)>>4;
        if((long)last-first+1>capacity){sections.clear();all=true;return;}
        for(int y=first;y<=last&&!all;y++)section(new Vec3i(x,y,z));
    }
    public boolean takeAll(){if(!all)return false;all=false;return true;}
    public List<PlacementBounds> drain(int limit){
        if(limit<0)throw new IllegalArgumentException("Negative drain");
        var result=new ArrayList<PlacementBounds>(Math.min(limit,sections.size()));
        for(var iterator=sections.iterator();iterator.hasNext()&&result.size()<limit;){var key=iterator.next();iterator.remove();
            var min=new Vec3i(key.x()<<4,key.y()<<4,key.z()<<4);result.add(new PlacementBounds(min,min.add(new Vec3i(15,15,15))));
        }
        return List.copyOf(result);
    }
    public int pending(){return sections.size();}
    public void clear(){sections.clear();all=false;}
}
