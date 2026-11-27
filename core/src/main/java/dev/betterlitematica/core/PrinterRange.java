package dev.betterlitematica.core;

public final class PrinterRange {
    public enum Shape { SPHERE,OCTAHEDRON,CUBE }
    private final Vec3i center;private final double radius;private final Shape shape;
    private final int[] axes=new int[3],start=new int[3],end=new int[3],step=new int[3],offset=new int[3];
    private final long volume;private long cursor;
    public PrinterRange(Vec3i center,double radius,Shape shape,String order,boolean x,boolean y,boolean z){
        this(center,radius,shape,order,x,y,z,null);
    }
    public PrinterRange(Vec3i center,double radius,Shape shape,String order,boolean x,boolean y,boolean z,PlacementBounds bounds){
        if(!Double.isFinite(radius)||radius<0||radius>256||!java.util.Set.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX").contains(order))throw new IllegalArgumentException("Work range");
        this.center=center;this.radius=radius;this.shape=java.util.Objects.requireNonNull(shape);
        int r=(int)Math.ceil(radius);
        long cells=1;
        step[0]=x?-1:1;step[1]=y?-1:1;step[2]=z?-1:1;
        for(int i=0;i<3;i++){
            axes[i]=order.charAt(i)-'X';
            int lo=-r,hi=r;
            if(bounds!=null){
                long c=coordinate(center,i);
                lo=(int)Math.min(r+1,Math.max(-r,coordinate(bounds.min(),i)-c));
                hi=(int)Math.max(-r-1,Math.min(r,coordinate(bounds.max(),i)-c));
            }
            cells*=Math.max(0,hi-lo+1);
            start[i]=offset[i]=step[i]>0?lo:hi;
            end[i]=step[i]>0?hi:lo;
        }
        volume=cells;
    }
    private static int coordinate(Vec3i p,int axis){return axis==0?p.x():axis==1?p.y():p.z();}
    public boolean hasNext(){return cursor<volume;}
    public long visited(){return cursor;}
    public long volume(){return volume;}
    public static boolean contains(Vec3i position,Vec3i center,double radius,Shape shape){
        long x=(long)position.x()-center.x(),y=(long)position.y()-center.y(),z=(long)position.z()-center.z();
        if(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>radius)return false;
        return switch(shape){case CUBE->true;case SPHERE->x*x+y*y+z*z<=radius*radius;case OCTAHEDRON->Math.abs(x)+Math.abs(y)+Math.abs(z)<=radius;};
    }
    public Vec3i next(){
        if(!hasNext())throw new java.util.NoSuchElementException();
        int x=offset[0],y=offset[1],z=offset[2];
        cursor++;
        for(int axis:axes){
            if(offset[axis]!=end[axis]){offset[axis]+=step[axis];break;}
            offset[axis]=start[axis];
        }
        boolean inside=switch(shape){case CUBE->Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))<=radius;case SPHERE->(long)x*x+(long)y*y+(long)z*z<=radius*radius;case OCTAHEDRON->Math.abs(x)+Math.abs(y)+Math.abs(z)<=radius;};
        return inside?new Vec3i(Math.addExact(center.x(),x),Math.addExact(center.y(),y),Math.addExact(center.z(),z)):null;
    }
}
