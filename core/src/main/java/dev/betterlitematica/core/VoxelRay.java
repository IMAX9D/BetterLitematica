package dev.betterlitematica.core;

import java.util.*;

/** Grid DDA, shared by picking and placement. Work is bounded by maxCells. */
public final class VoxelRay {
    public record Hit(Vec3i position, double distance, Vec3i face) {}
    private VoxelRay() {}
    public static List<Hit> trace(double ox,double oy,double oz,double dx,double dy,double dz,double reach,int maxCells){
        if(!Double.isFinite(ox+oy+oz+dx+dy+dz+reach)||reach<0||reach>256||maxCells<1||maxCells>1024)throw new IllegalArgumentException("Invalid ray");
        double norm=Math.sqrt(dx*dx+dy*dy+dz*dz);if(norm==0)return List.of();dx/=norm;dy/=norm;dz/=norm;
        int x=(int)Math.floor(ox),y=(int)Math.floor(oy),z=(int)Math.floor(oz);int sx=Double.compare(dx,0),sy=Double.compare(dy,0),sz=Double.compare(dz,0);
        double tx=boundary(ox,x,dx),ty=boundary(oy,y,dy),tz=boundary(oz,z,dz),ax=dx==0?Double.POSITIVE_INFINITY:Math.abs(1/dx),ay=dy==0?Double.POSITIVE_INFINITY:Math.abs(1/dy),az=dz==0?Double.POSITIVE_INFINITY:Math.abs(1/dz);
        List<Hit> result=new ArrayList<>();double distance=0;Vec3i face=Vec3i.ZERO;
        while(result.size()<maxCells&&distance<=reach){result.add(new Hit(new Vec3i(x,y,z),distance,face));
            if(tx<=ty&&tx<=tz){x=Math.addExact(x,sx);distance=tx;tx+=ax;face=new Vec3i(-sx,0,0);}
            else if(ty<=tz){y=Math.addExact(y,sy);distance=ty;ty+=ay;face=new Vec3i(0,-sy,0);}
            else{z=Math.addExact(z,sz);distance=tz;tz+=az;face=new Vec3i(0,0,-sz);}
        }return List.copyOf(result);
    }
    private static double boundary(double origin,int cell,double direction){return direction>0?(cell+1.0-origin)/direction:direction<0?(origin-cell)/-direction:Double.POSITIVE_INFINITY;}
}
