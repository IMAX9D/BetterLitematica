package dev.betterlitematica.core;
import java.util.Objects;
/** Bounds include expected air. Empty sections do not erase the region's domain. */
public record Region(String name, Vec3i min, Vec3i size,Vec3i anchor) {
    public Region(String name,Vec3i min,Vec3i size){this(name,min,size,min);}
    public Region {
        Objects.requireNonNull(name); Objects.requireNonNull(min); Objects.requireNonNull(size);Objects.requireNonNull(anchor);
        if (size.x()<=0 || size.y()<=0 || size.z()<=0) throw new IllegalArgumentException("Empty/negative normalized region");
        if (size.x()>1_048_576 || size.y()>1_048_576 || size.z()>1_048_576) throw new IllegalArgumentException("Region axis exceeds limit");
        Math.addExact(min.x(),size.x()); Math.addExact(min.y(),size.y()); Math.addExact(min.z(),size.z());
        if(anchor.x()!=min.x()&&anchor.x()!=min.x()+size.x()-1||anchor.y()!=min.y()&&anchor.y()!=min.y()+size.y()-1||anchor.z()!=min.z()&&anchor.z()!=min.z()+size.z()-1)throw new IllegalArgumentException("Source anchor must be a region corner");
    }
    public long volume() { return Math.multiplyExact(Math.multiplyExact((long)size.x(),size.y()),size.z()); }
    public boolean contains(Vec3i p) {
        return (long)p.x()-min.x()>=0 && (long)p.x()-min.x()<size.x()
            && (long)p.y()-min.y()>=0 && (long)p.y()-min.y()<size.y()
            && (long)p.z()-min.z()>=0 && (long)p.z()-min.z()<size.z();
    }
    public Vec3i sectionOrigin(SectionKey k) { return min.add(new Vec3i(Math.multiplyExact(k.x(),16),Math.multiplyExact(k.y(),16),Math.multiplyExact(k.z(),16))); }
    public boolean intersects(Region b) {
        return min.x() < (long)b.min.x()+b.size.x() && b.min.x() < (long)min.x()+size.x()
            && min.y() < (long)b.min.y()+b.size.y() && b.min.y() < (long)min.y()+size.y()
            && min.z() < (long)b.min.z()+b.size.z() && b.min.z() < (long)min.z()+size.z();
    }
}
