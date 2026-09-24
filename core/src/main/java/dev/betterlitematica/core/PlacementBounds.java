package dev.betterlitematica.core;

/** Axis-aligned world bounds after an orthogonal placement transform and world-space layer clipping. */
public record PlacementBounds(Vec3i min,Vec3i max) {
    public static PlacementBounds clipped(Region region,PlacementTransform transform,LayerRange layer){
        Vec3i a=transform.apply(region.min()),b=transform.apply(region.min().add(new Vec3i(region.size().x()-1,region.size().y()-1,region.size().z()-1)));
        int[] lo={Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z())},hi={Math.max(a.x(),b.x()),Math.max(a.y(),b.y()),Math.max(a.z(),b.z())};
        int axis=layer.axis().ordinal();lo[axis]=Math.max(lo[axis],layer.min());hi[axis]=Math.min(hi[axis],layer.max());
        if(lo[axis]>hi[axis])return null;
        return new PlacementBounds(new Vec3i(lo[0],lo[1],lo[2]),new Vec3i(hi[0],hi[1],hi[2]));
    }
}
