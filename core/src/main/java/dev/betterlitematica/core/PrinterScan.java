package dev.betterlitematica.core;

import java.util.*;

/** Persistent full sweep plus at most two lazy movement frontiers. No world objects or position cube. */
public final class PrinterScan {
    private final double radius;private final PrinterRange.Shape shape;private final String order;private final boolean x,y,z;
    private Vec3i center,regularCenter;private PrinterRange regular;private Fringe frontier,pending;
    private long tick=Long.MIN_VALUE,completedRounds;private int turn;
    public PrinterScan(Vec3i center,double radius,PrinterRange.Shape shape,String order,boolean x,boolean y,boolean z){
        this.center=Objects.requireNonNull(center);this.radius=radius;this.shape=shape;this.order=order;this.x=x;this.y=y;this.z=z;
        regularCenter=center;regular=new PrinterRange(center,radius,shape,order,x,y,z);
    }
    public double radius(){return radius;}
    public long completedRounds(){return completedRounds;}
    public int pendingFrontiers(){trim();return (frontier==null?0:1)+(pending==null?0:1);}
    public void beginTick(Vec3i next,long nextTick){
        Objects.requireNonNull(next);
        if(!next.equals(center)){
            Vec3i previous=center;center=next;trim();
            if(frontier==null)frontier=new Fringe(previous,next);
            else pending=new Fringe(pending==null?previous:pending.from,next);
            // Discard only a frontier/sweep whose entire volume has left the current reach.
            if(frontier!=null&&!overlaps(frontier.to,center)){frontier=pending;pending=null;}
            if(!overlaps(regularCenter,center)){regularCenter=center;regular=new PrinterRange(center,radius,shape,order,x,y,z);}
        }
        if(nextTick!=tick&&!regular.hasNext()){regularCenter=center;regular=new PrinterRange(center,radius,shape,order,x,y,z);}
        tick=nextTick;trim();
    }
    private boolean overlaps(Vec3i a,Vec3i b){double width=2*radius;return Math.abs((long)a.x()-b.x())<=width&&Math.abs((long)a.y()-b.y())<=width&&Math.abs((long)a.z()-b.z())<=width;}
    private void trim(){if(frontier!=null&&!frontier.hasNext()){frontier=pending;pending=null;}if(frontier!=null&&!frontier.hasNext())frontier=null;}
    public boolean hasNext(){trim();return regular.hasNext()||frontier!=null;}
    /** A null result is a bounded skipped cell/row. The caller charges every call to its work budget. */
    public Vec3i next(){
        trim();if(!hasNext())throw new NoSuchElementException();Vec3i result;
        if(frontier!=null&&(!regular.hasNext()||(++turn&3)!=0))result=frontier.next();
        else {result=regular.next();if(!regular.hasNext())completedRounds++;}
        return result!=null&&PrinterRange.contains(result,center,radius,shape)?result:null;
    }
    private static int axis(Vec3i value,int axis){return axis==0?value.x():axis==1?value.y():value.z();}
    private static Vec3i position(int axis,int value,int firstValue,int secondValue){return switch(axis){case 0->new Vec3i(value,firstValue,secondValue);case 1->new Vec3i(secondValue,value,firstValue);case 2->new Vec3i(firstValue,secondValue,value);default->throw new IllegalArgumentException();};}
    private static int centered(int index){return index==0?0:(index%2==1?(index+1)/2:-index/2);}
    private final class Fringe {
        final Vec3i from,to;final int axis,first,second,edge;final boolean forward;
        int row,rows,low,high,otherLow,otherHigh,firstValue,secondValue;boolean range,other;
        Fringe(Vec3i from,Vec3i to){
            this.from=from;this.to=to;long dx=(long)to.x()-from.x(),dy=(long)to.y()-from.y(),dz=(long)to.z()-from.z();
            axis=Math.abs(dx)>=Math.abs(dy)&&Math.abs(dx)>=Math.abs(dz)?0:Math.abs(dy)>=Math.abs(dz)?1:2;
            first=(axis+1)%3;second=(axis+2)%3;forward=axis(to,axis)>axis(from,axis);edge=2*(int)Math.floor(radius)+1;rows=from.equals(to)?0:edge*edge;
        }
        boolean hasNext(){return range||other||row<rows;}
        Vec3i next(){
            if(!range&&other){low=otherLow;high=otherHigh;range=true;other=false;}
            if(!range){
                int index=row++;firstValue=axis(to,first)+centered(index%edge);secondValue=axis(to,second)+centered(index/edge);
                int extent=extent(to);if(extent<0)return null;int min=axis(to,axis)-extent,max=axis(to,axis)+extent;
                int oldExtent=extent(from);
                if(oldExtent<0){low=min;high=max;range=true;}
                else {
                    int oldMin=axis(from,axis)-oldExtent,oldMax=axis(from,axis)+oldExtent;
                    int leftMax=Math.min(max,oldMin-1),rightMin=Math.max(min,oldMax+1);
                    boolean left=min<=leftMax,right=rightMin<=max;
                    if(forward&&right){low=rightMin;high=max;range=true;if(left){otherLow=min;otherHigh=leftMax;other=true;}}
                    else if(left){low=min;high=leftMax;range=true;if(right){otherLow=rightMin;otherHigh=max;other=true;}}
                    else if(right){low=rightMin;high=max;range=true;}
                }
                if(!range)return null;
            }
            int value=forward?high--:low++;if(low>high)range=false;
            return position(axis,value,firstValue,secondValue);
        }
        private int extent(Vec3i origin){
            long a=(long)firstValue-axis(origin,first),b=(long)secondValue-axis(origin,second);
            if(Math.abs(a)>radius||Math.abs(b)>radius)return -1;
            return switch(shape){case CUBE->(int)Math.floor(radius);case OCTAHEDRON->(int)Math.floor(radius-Math.abs(a)-Math.abs(b));case SPHERE->{double remaining=radius*radius-(double)a*a-(double)b*b;yield remaining<0?-1:(int)Math.floor(Math.sqrt(remaining));}};
        }
    }
}
