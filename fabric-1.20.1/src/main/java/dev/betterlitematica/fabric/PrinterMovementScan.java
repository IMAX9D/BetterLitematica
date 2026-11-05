package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.NoSuchElementException;

/** Persistent ordered construction plus bounded, source-aware section rechecks. */
final class PrinterMovementScan {
    private final PrinterScan sweep;
    private final double radius;
    private final PrinterRange.Shape shape;
    private final String order;
    private final boolean[] reverse;
    private java.util.function.Predicate<PlacementBounds> relevant=box->true;
    private Vec3i center,anchor;
    private long movedAt=Long.MIN_VALUE;
    private long nextRecheck;
    private Overlap repair;
    private int turn;
    PrinterMovementScan(Vec3i center,double radius,PrinterRange.Shape shape,String order,boolean x,boolean y,boolean z){
        this.center=this.anchor=center;this.radius=radius;this.shape=shape;this.order=order;reverse=new boolean[]{x,y,z};
        sweep=new PrinterScan(center,radius,shape,order,x,y,z);
    }
    double radius(){return radius;}
    void domain(java.util.function.Predicate<PlacementBounds> relevant){this.relevant=java.util.Objects.requireNonNull(relevant);}
    long completedRounds(){return sweep.completedRounds();}
    void beginTick(Vec3i next,long tick){
        if(!next.equals(center)){center=next;movedAt=tick;}
        sweep.beginTick(next,tick);
        // Small eye-position jitter must not repeatedly rescan almost the entire sphere.
        if(movedAt!=Long.MIN_VALUE&&tick-movedAt>=10&&distance(anchor,center)>=Math.max(2,radius*.1)){
            repair=new Overlap(anchor,center);anchor=center;movedAt=Long.MIN_VALUE;nextRecheck=tick+200;
        }else if((repair==null||!repair.hasNext())&&tick>=nextRecheck){
            repair=new Overlap(center,center);nextRecheck=tick+200;
        }
    }
    private static long distance(Vec3i a,Vec3i b){return Math.max(Math.abs((long)a.x()-b.x()),Math.max(Math.abs((long)a.y()-b.y()),Math.abs((long)a.z()-b.z())));}
    boolean hasNext(){return sweep.hasNext()||repair!=null&&repair.hasNext();}
    Vec3i next(){
        if(!hasNext())throw new NoSuchElementException();
        if(repair==null||!repair.hasNext()||sweep.hasNext()&&((++turn&1)!=0))return sweep.next();
        Vec3i result=repair.next();
        return result!=null&&PrinterRange.contains(result,center,radius,shape)?result:null;
    }
    private static int centered(int n){return n==0?0:(n%2==1?(n+1)/2:-n/2);}
    private final class Overlap {
        final Vec3i from,to;
        final int[] min=new int[3],max=new int[3],sectionCenter=new int[3],edge=new int[3],cellMin=new int[3],cellWidth=new int[3];
        final long volume;
        long cursor;int cellCursor,cellVolume;
        Overlap(Vec3i from,Vec3i to){
            this.from=from;this.to=to;int r=(int)Math.floor(radius);long size=1;
            int[] a={from.x(),from.y(),from.z()},b={to.x(),to.y(),to.z()};
            for(int i=0;i<3;i++){
                min[i]=Math.max(a[i],b[i])-r;max[i]=Math.min(a[i],b[i])+r;sectionCenter[i]=b[i]>>4;
                int reach=Math.max(Math.abs(sectionCenter[i]-(min[i]>>4)),Math.abs((max[i]>>4)-sectionCenter[i]));
                edge[i]=reach*2+1;size*=edge[i];if(min[i]>max[i])size=0;
            }
            volume=size;
        }
        boolean hasNext(){return cellCursor<cellVolume||cursor<volume;}
        Vec3i next(){
            if(cellCursor==cellVolume){
                long n=cursor++;int[] lo=new int[3],hi=new int[3];
                for(int i=0;i<3;i++){int axis=order.charAt(i)-'X';int offset=centered((int)(n%edge[axis]));n/=edge[axis];int start=(sectionCenter[axis]+(reverse[axis]?-offset:offset))<<4;lo[axis]=Math.max(min[axis],start);hi[axis]=Math.min(max[axis],start+15);}
                if(lo[0]>hi[0]||lo[1]>hi[1]||lo[2]>hi[2])return null;
                var box=new PlacementBounds(new Vec3i(lo[0],lo[1],lo[2]),new Vec3i(hi[0],hi[1],hi[2]));
                if(!relevant.test(box))return null;
                cellCursor=0;cellVolume=1;for(int axis=0;axis<3;axis++){cellMin[axis]=lo[axis];cellWidth[axis]=hi[axis]-lo[axis]+1;cellVolume*=cellWidth[axis];}
            }
            int n=cellCursor++,x=0,y=0,z=0;
            for(int i=0;i<3;i++){int axis=order.charAt(i)-'X';int offset=n%cellWidth[axis];n/=cellWidth[axis];int value=cellMin[axis]+(reverse[axis]?cellWidth[axis]-1-offset:offset);switch(axis){case 0->x=value;case 1->y=value;case 2->z=value;default->throw new IllegalStateException();}}
            var pos=new Vec3i(x,y,z);
            return PrinterRange.contains(pos,from,radius,shape)&&PrinterRange.contains(pos,to,radius,shape)?pos:null;
        }
    }
}
