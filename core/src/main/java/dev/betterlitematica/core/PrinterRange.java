package dev.betterlitematica.core;

/** Lazy ordered cursor: the largest range does not allocate a cube of positions. */
public final class PrinterRange {
    public enum Shape { SPHERE,OCTAHEDRON,CUBE }
    private final Vec3i center;private final double radius;private final Shape shape;private final String order;private final boolean[] reverse;
    private final int r,edge;private final long volume;private long cursor;
    public PrinterRange(Vec3i center,double radius,Shape shape,String order,boolean x,boolean y,boolean z){
        if(!Double.isFinite(radius)||radius<0||radius>256||!java.util.Set.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX").contains(order))throw new IllegalArgumentException("Work range");
        this.center=center;this.radius=radius;this.shape=java.util.Objects.requireNonNull(shape);this.order=order;reverse=new boolean[]{x,y,z};r=(int)Math.ceil(radius);edge=2*r+1;volume=(long)edge*edge*edge;
    }
    public boolean hasNext(){return cursor<volume;}
    public long visited(){return cursor;}
    public long volume(){return volume;}
    public static boolean contains(Vec3i position,Vec3i center,double radius,Shape shape){
        long x=(long)position.x()-center.x(),y=(long)position.y()-center.y(),z=(long)position.z()-center.z();
        if(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>radius)return false;
        return switch(shape){case CUBE->true;case SPHERE->x*x+y*y+z*z<=radius*radius;case OCTAHEDRON->Math.abs(x)+Math.abs(y)+Math.abs(z)<=radius;};
    }
    public Vec3i next(){
        if(!hasNext())throw new java.util.NoSuchElementException();long n=cursor++;int x=0,y=0,z=0;
        for(int i=0;i<3;i++){int value=(int)(n%edge)-r;n/=edge;char axis=order.charAt(i);int index=axis-'X';if(reverse[index])value=-value;switch(axis){case 'X'->x=value;case 'Y'->y=value;case 'Z'->z=value;default->throw new IllegalStateException();}}
        boolean inside=switch(shape){case CUBE->Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))<=radius;case SPHERE->(long)x*x+(long)y*y+(long)z*z<=radius*radius;case OCTAHEDRON->Math.abs(x)+Math.abs(y)+Math.abs(z)<=radius;};
        return inside?center.add(new Vec3i(x,y,z)):null;
    }
}
