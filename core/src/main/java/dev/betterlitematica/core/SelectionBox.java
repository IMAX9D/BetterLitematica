package dev.betterlitematica.core;

import java.util.Objects;

/** Inclusive corners, independent of corner order. */
public record SelectionBox(String name, Vec3i first, Vec3i second) {
    public SelectionBox { Objects.requireNonNull(first); Objects.requireNonNull(second); if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Invalid selection name"); }
    public Region region() {
        Vec3i min = new Vec3i(Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z()));
        Vec3i max = new Vec3i(Math.max(first.x(), second.x()), Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
        return new Region(name, min, max.subtract(min).add(new Vec3i(1, 1, 1)));
    }
    public SelectionBox translate(Vec3i offset) { return new SelectionBox(name, first.add(offset), second.add(offset)); }
    public SelectionBox expand(Vec3i direction,int amount){
        if(Math.abs(direction.x())+Math.abs(direction.y())+Math.abs(direction.z())!=1)throw new IllegalArgumentException("Expected an axis direction");int[] a={first.x(),first.y(),first.z()},b={second.x(),second.y(),second.z()},d={direction.x(),direction.y(),direction.z()};
        for(int i=0;i<3;i++)if(d[i]!=0){int lo=Math.min(a[i],b[i]),hi=Math.max(a[i],b[i]);boolean forward=a[i]<=b[i];if(d[i]>0)hi=Math.max(lo,Math.addExact(hi,amount));else lo=Math.min(hi,Math.subtractExact(lo,amount));a[i]=forward?lo:hi;b[i]=forward?hi:lo;}
        var result=new SelectionBox(name,new Vec3i(a[0],a[1],a[2]),new Vec3i(b[0],b[1],b[2]));result.region();return result;
    }
}
