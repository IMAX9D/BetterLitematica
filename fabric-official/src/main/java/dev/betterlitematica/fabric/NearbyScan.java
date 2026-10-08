package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/** Exact eye-to-unit-box frontier. Planning rows and sampled cells have separate caller budgets. */
final class NearbyScan {
    private final double radius;
    private Vec3 eye;
    private Sweep regular,frontier,pending;
    NearbyScan(Vec3 eye,double radius){this.eye=eye;this.radius=radius;regular=new Sweep(null,eye);}
    void begin(Vec3 next){
        if(!next.equals(eye)){
            var previous=eye;eye=next;trim();
            if(frontier==null)frontier=new Sweep(previous,next);
            else pending=new Sweep(pending==null?previous:pending.from,next);
            if(!overlaps(regular.to,next))regular=new Sweep(null,next);
            if(frontier!=null&&!overlaps(frontier.to,next)){frontier=pending;pending=null;}
        }
        if(!regular.hasNext())regular=new Sweep(null,next);
        trim();
    }
    private boolean overlaps(Vec3 a,Vec3 b){double width=radius*2+2;return Math.abs(a.x-b.x)<=width&&Math.abs(a.y-b.y)<=width&&Math.abs(a.z-b.z)<=width;}
    private void trim(){if(frontier!=null&&!frontier.hasNext()){frontier=pending;pending=null;}if(frontier!=null&&!frontier.hasNext())frontier=null;}
    boolean hasFrontier(){trim();return frontier!=null;}
    Vec3i frontier(int[] rows){trim();return frontier==null?null:frontier.next(rows);}
    Vec3i regular(int[] rows){return regular.next(rows);}
    int pendingFrontiers(){trim();return (frontier==null?0:1)+(pending==null?0:1);}
    private static double axis(Vec3 p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
    private static Vec3i point(int axis,int v,int a,int b){return axis==0?new Vec3i(v,a,b):axis==1?new Vec3i(b,v,a):new Vec3i(a,b,v);}
    private static int centered(int i){return i==0?0:(i%2==1?(i+1)/2:-i/2);}
    private static double distance(double eye,int cell){return eye<cell?cell-eye:eye>cell+1?eye-cell-1:0;}
    private final class Sweep {
        final Vec3 from,to;final int axis,first,second,edge;final boolean forward;
        int row,lo,hi,lo2,hi2,a,b;boolean active,other;
        Sweep(Vec3 from,Vec3 to){
            this.from=from;this.to=to;double dx=from==null?1:to.x-from.x,dy=from==null?0:to.y-from.y,dz=from==null?0:to.z-from.z;
            axis=Math.abs(dx)>=Math.abs(dy)&&Math.abs(dx)>=Math.abs(dz)?0:Math.abs(dy)>=Math.abs(dz)?1:2;first=(axis+1)%3;second=(axis+2)%3;
            edge=2*(int)Math.ceil(radius)+3;forward=from==null||axis(to,axis)>=axis(from,axis);
        }
        boolean hasNext(){return active||other||row<edge*edge;}
        Vec3i next(int[] rows){
            while(hasNext()){
                if(!active&&other){lo=lo2;hi=hi2;active=true;other=false;}
                if(!active){
                    if(rows[0]<=0)return null;rows[0]--;
                    int n=row++;a=(int)Math.floor(axis(to,first))+centered(n%edge);b=(int)Math.floor(axis(to,second))+centered(n/edge);
                    double extent=extent(to);if(extent<0)continue;
                    int min=(int)Math.ceil(axis(to,axis)-extent-1),max=(int)Math.floor(axis(to,axis)+extent);
                    double previous=from==null?-1:extent(from);
                    if(previous<0){lo=min;hi=max;active=true;}
                    else {
                        int oldMin=(int)Math.ceil(axis(from,axis)-previous-1),oldMax=(int)Math.floor(axis(from,axis)+previous);
                        int leftMax=Math.min(max,oldMin-1),rightMin=Math.max(min,oldMax+1);boolean left=min<=leftMax,right=rightMin<=max;
                        if(forward&&right){lo=rightMin;hi=max;active=true;if(left){lo2=min;hi2=leftMax;other=true;}}
                        else if(left){lo=min;hi=leftMax;active=true;if(right){lo2=rightMin;hi2=max;other=true;}}
                        else if(right){lo=rightMin;hi=max;active=true;}
                    }
                    if(!active)continue;
                }
                int value=forward?hi--:lo++;if(lo>hi)active=false;return point(axis,value,a,b);
            }
            return null;
        }
        private double extent(Vec3 origin){double x=distance(axis(origin,first),a),y=distance(axis(origin,second),b),remaining=radius*radius-x*x-y*y;return remaining<0?-1:Math.sqrt(remaining);}
    }
}
