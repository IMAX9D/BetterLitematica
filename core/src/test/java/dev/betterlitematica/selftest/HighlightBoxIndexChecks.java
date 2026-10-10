package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.*;

/** The spatial index must return exactly what a stable full sort plus the same filters returned before it. */
public final class HighlightBoxIndexChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private HighlightBoxIndexChecks(){}

    /** A convex test volume: an axis-aligned "view" box, classified the way a frustum test would be. */
    private record View(double x0,double y0,double z0,double x1,double y1,double z1) implements HighlightBoxIndex.BoundsTest {
        public int test(double a0,double b0,double c0,double a1,double b1,double c1){
            if(a1<x0||a0>x1||b1<y0||b0>y1||c1<z0||c0>z1)return HighlightBoxIndex.OUTSIDE;
            return a0>=x0&&a1<=x1&&b0>=y0&&b1<=y1&&c0>=z0&&c1<=z1?HighlightBoxIndex.INSIDE:HighlightBoxIndex.INTERSECTS;
        }
        boolean visible(HighlightCuboids.Box b){return test(b.min().x(),b.min().y(),b.min().z(),b.max().x()+1.0,b.max().y()+1.0,b.max().z()+1.0)!=HighlightBoxIndex.OUTSIDE;}
    }
    private static double distance(HighlightCuboids.Box b,double px,double py,double pz){return HighlightBoxIndex.distanceSquared(px,py,pz,b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z());}

    public static int run(){
        checks=0;
        check(HighlightBoxIndex.EMPTY.size()==0&&HighlightBoxIndex.EMPTY.nearest(0,0,0,1e9,8,(a,b,c,d,e,f)->HighlightBoxIndex.INSIDE,new int[8])==0,"Empty index selects nothing");
        var random=new Random(20261009);
        for(int round=0;round<400;round++){
            int n=random.nextInt(round<40?40:3000);int spread=8+random.nextInt(200);var boxes=new ArrayList<HighlightCuboids.Box>(n);
            for(int i=0;i<n;i++){
                // Integer grids create many equal distances, which is where tie order matters most.
                int x=random.nextInt(spread)-spread/2,y=random.nextInt(48)-64,z=random.nextInt(spread)-spread/2;
                int w=random.nextInt(4)==0?random.nextInt(40):0,h=random.nextInt(6)==0?random.nextInt(9):0,d=random.nextInt(4)==0?random.nextInt(40):0;
                boxes.add(new HighlightCuboids.Box(new Vec3i(x,y,z),new Vec3i(x+w,y+h,z+d),random.nextInt(5)));
            }
            var index=HighlightBoxIndex.build(boxes);check(index.size()==n,"Index keeps every box");
            for(int query=0;query<6;query++){
                double px=random.nextDouble()*spread-spread/2.0,py=random.nextDouble()*48-64,pz=random.nextDouble()*spread-spread/2.0;
                double range=random.nextInt(5)==0?1e12:Math.pow(4+random.nextInt(120),2);
                double vx=px-random.nextInt(60),vz=pz-random.nextInt(60);
                var view=random.nextInt(5)==0?new View(-1e9,-1e9,-1e9,1e9,1e9,1e9):new View(vx,py-random.nextInt(30),vz,vx+20+random.nextInt(120),py+random.nextInt(30),vz+20+random.nextInt(120));
                // Reference: what the overlay did before, a stable sort of every box followed by the filters.
                var order=new ArrayList<Integer>();for(int i=0;i<n;i++)order.add(i);
                final double fx=px,fy=py,fz=pz;order.sort(Comparator.comparingDouble(i->distance(boxes.get(i),fx,fy,fz)));
                var expected=new ArrayList<Integer>();for(int i:order){var b=boxes.get(i);if(distance(b,px,py,pz)<=range&&view.visible(b))expected.add(i);}
                int limit=1+random.nextInt(random.nextBoolean()?8:600);
                int[] out=new int[limit];int count=index.nearest(px,py,pz,range,limit,view,out);
                var actual=new ArrayList<Integer>();for(int i=0;i<count;i++)actual.add(index.sourceIndex(out[i]));
                check(actual.equals(expected.subList(0,Math.min(limit,expected.size()))),"Nearest selection equals the stable full sort prefix (round "+round+")");
                var visited=new TreeSet<Integer>();index.forEach(px,py,pz,range,view,(slot,distanceSquared)->{
                    check(distanceSquared==distance(index.box(slot),fx,fy,fz),"Reported distance is exact");visited.add(index.sourceIndex(slot));});
                check(visited.equals(new TreeSet<>(expected)),"Unlimited traversal visits exactly the in-range visible boxes");
            }
            for(int slot=0;slot<n;slot++){var b=index.box(slot);check(b==boxes.get(index.sourceIndex(slot))&&index.minX(slot)==b.min().x()&&index.maxZ(slot)==b.max().z(),"Flat coordinates mirror the source box");}
        }
        // The early exit must not skip a nearer box hidden in a bucket whose lower bound ties the current worst.
        var tie=List.of(new HighlightCuboids.Box(new Vec3i(16,0,0),new Vec3i(16,0,0),0),new HighlightCuboids.Box(new Vec3i(-1,0,0),new Vec3i(-1,0,0),0));
        int[] one=new int[1];HighlightBoxIndex.build(tie).nearest(7.5,0.5,0.5,1e9,1,(a,b,c,d,e,f)->HighlightBoxIndex.INSIDE,one);
        check(HighlightBoxIndex.build(tie).box(0)!=null&&HighlightBoxIndex.build(tie).nearest(7.5,0.5,0.5,1e9,1,(a,b,c,d,e,f)->HighlightBoxIndex.INSIDE,one)==1,"Single nearest is found across buckets");
        return checks;
    }
}
