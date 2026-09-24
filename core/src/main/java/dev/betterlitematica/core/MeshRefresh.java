package dev.betterlitematica.core;

import java.util.*;

/** Single-owner mesh revisions. Completion never consumes an update received during a build. */
public final class MeshRefresh<K> {
    private final int limit;
    private final Map<K,Long> changed=new HashMap<>();
    private long sequence,global;
    public MeshRefresh(int limit){if(limit<1||limit>32768)throw new IllegalArgumentException("Mesh refresh budget");this.limit=limit;}
    public long revision(K key){return Math.max(global,changed.getOrDefault(key,0L));}
    public boolean stale(K key,long builtRevision){return builtRevision<revision(key);}
    public void changed(K key){
        Objects.requireNonNull(key);
        if(!changed.containsKey(key)&&changed.size()>=limit){allChanged();return;}
        changed.put(key,++sequence);
    }
    public void allChanged(){global=++sequence;changed.clear();}
    public void completed(K key,long builtRevision){var latest=changed.get(key);if(latest!=null&&latest<=builtRevision)changed.remove(key);}
    public int pending(){return changed.size();}
    public void clear(){sequence=global=0;changed.clear();}
    public static PlacementBounds padded(PlacementBounds bounds){return new PlacementBounds(offset(bounds.min(),-1),offset(bounds.max(),1));}
    private static Vec3i offset(Vec3i value,int delta){return new Vec3i(offset(value.x(),delta),offset(value.y(),delta),offset(value.z(),delta));}
    private static int offset(int value,int delta){return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,(long)value+delta));}
    /** Inclusive source section coordinates affected by an already padded world-space box. */
    public static PlacementBounds sourceSections(PlacementLayout.Part part,PlacementBounds world){
        var a=part.local(world.min()).subtract(part.region().min());var b=part.local(world.max()).subtract(part.region().min());var size=part.region().size();
        var min=new Vec3i(Math.max(0,Math.min(a.x(),b.x())>>4),Math.max(0,Math.min(a.y(),b.y())>>4),Math.max(0,Math.min(a.z(),b.z())>>4));
        var max=new Vec3i(Math.min((size.x()-1)>>4,Math.max(a.x(),b.x())>>4),Math.min((size.y()-1)>>4,Math.max(a.y(),b.y())>>4),Math.min((size.z()-1)>>4,Math.max(a.z(),b.z())>>4));
        return min.x()>max.x()||min.y()>max.y()||min.z()>max.z()?null:new PlacementBounds(min,max);
    }
}
