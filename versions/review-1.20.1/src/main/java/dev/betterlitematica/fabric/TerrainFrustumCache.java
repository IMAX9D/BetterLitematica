package dev.betterlitematica.fabric;

import dev.betterlitematica.fabric.mixin.TerrainFrustumAccessor;
import net.minecraft.client.render.Frustum;
import org.joml.Matrix4f;

/** A conservative superset: expanding boxes by M covers any camera translation of at most M on each axis. */
public final class TerrainFrustumCache {
    public static final double MARGIN=4.0,ROUNDING_MARGIN=.125;
    private Object graph;private int count,sectionX,sectionY,sectionZ;
    private final Matrix4f matrix=new Matrix4f();private double x,y,z;
    public void clear(){graph=null;}
    public boolean reuse(Frustum view,Object current,int size,int sx,int sy,int sz){
        if(graph==null||graph!=current||count!=size||sectionX!=sx||sectionY!=sy||sectionZ!=sz||view.getClass()!=Frustum.class)return false;
        var f=(TerrainFrustumAccessor)view;
        return matrix.equals(f.betterlitematica$matrix())&&Math.abs(f.betterlitematica$x()-x)<=MARGIN
                &&Math.abs(f.betterlitematica$y()-y)<=MARGIN&&Math.abs(f.betterlitematica$z()-z)<=MARGIN;
    }
    public void remember(Frustum view,Object current,int size,int sx,int sy,int sz){
        var f=(TerrainFrustumAccessor)view;graph=current;count=size;sectionX=sx;sectionY=sy;sectionZ=sz;
        matrix.set(f.betterlitematica$matrix());x=f.betterlitematica$x();y=f.betterlitematica$y();z=f.betterlitematica$z();
    }
}
