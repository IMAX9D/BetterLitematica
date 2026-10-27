package dev.betterlitematica.fabric;


import dev.betterlitematica.fabric.TerrainChunkHandle;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Box;
import org.joml.Matrix4f;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;

/** A conservative superset: expanding boxes by M covers any camera translation of at most M on each axis. */
public final class TerrainFrustumCache {
    public static final double MARGIN=4.0,ROUNDING_MARGIN=.125;
    private Object graph;private int count,sectionX,sectionY,sectionZ;
    private Object[] entries=new Object[0];private Box[] bounds=new Box[0];
    private boolean committed;
    private final Matrix4f matrix=new Matrix4f();private double x,y,z;
    public void clear(){
        graph=null;committed=false;
        Arrays.fill(entries,0,count,null);Arrays.fill(bounds,0,count,null);count=0;
    }
    private static boolean supported(Frustum view){
        if(view==null||view.getClass()!=Frustum.class||!(view instanceof TerrainView f))return false;
        return f.betterlitematica$matrix().isFinite()&&Double.isFinite(f.betterlitematica$x())
                &&Double.isFinite(f.betterlitematica$y())&&Double.isFinite(f.betterlitematica$z());
    }
    private static Box bounds(Object info){
        return info instanceof TerrainChunkHandle access&&access.betterlitematica$chunk()!=null
                ?access.betterlitematica$chunk().boundingBox:null;
    }
    private boolean matches(Frustum view,LinkedHashSet<?> current,int sx,int sy,int sz){
        if(graph==null||graph!=current||count!=current.size()||sectionX!=sx||sectionY!=sy||sectionZ!=sz||!supported(view))return false;
        var f=(TerrainView)view;
        if(!matrix.equals(f.betterlitematica$matrix())||Math.abs(f.betterlitematica$x()-x)>MARGIN
                ||Math.abs(f.betterlitematica$y()-y)>MARGIN||Math.abs(f.betterlitematica$z()-z)>MARGIN)return false;
        // Size and identity of the set are insufficient: a section can move, or members can be
        // replaced/reordered without changing either. Compare exact identities, never a hash.
        int i=0;
        try{
            for(Object info:current){
                if(i>=count||entries[i]!=info||bounds[i]!=bounds(info))return false;
                i++;
            }
        }catch(ConcurrentModificationException changed){return false;}
        return i==count;
    }
    public boolean reuse(Frustum view,LinkedHashSet<?> current,int sx,int sy,int sz){
        return committed&&matches(view,current,sx,sy,sz);
    }
    /** Snapshot BEFORE vanilla traverses the set; only commit after a successful unchanged traversal. */
    public boolean capture(Frustum view,LinkedHashSet<?> current,int sx,int sy,int sz){
        clear();
        if(current==null||current.size()>1_048_576||!supported(view))return false;
        if(entries.length<current.size()){
            int capacity=Math.min(1_048_576,Math.max(current.size(),Math.max(256,entries.length+entries.length/2)));
            entries=new Object[capacity];bounds=new Box[capacity];
        }
        try{
            for(Object info:current){
                Box box=bounds(info);
                if(box==null||count==entries.length){clear();return false;}
                entries[count]=info;bounds[count++]=box;
            }
        }catch(ConcurrentModificationException changed){clear();return false;}
        var f=(TerrainView)view;graph=current;sectionX=sx;sectionY=sy;sectionZ=sz;
        matrix.set(f.betterlitematica$matrix());x=f.betterlitematica$x();y=f.betterlitematica$y();z=f.betterlitematica$z();
        return true;
    }
    public void commit(Frustum view,LinkedHashSet<?> current,int sx,int sy,int sz){
        // The margin applies only AFTER a completed traversal. Moving the view during capture
        // would shift the expansion's center and could invalidate the conservative-superset proof.
        if(supported(view)&&view instanceof TerrainView f
                &&f.betterlitematica$x()==x&&f.betterlitematica$y()==y&&f.betterlitematica$z()==z
                &&matches(view,current,sx,sy,sz))committed=true;else clear();
    }
}
