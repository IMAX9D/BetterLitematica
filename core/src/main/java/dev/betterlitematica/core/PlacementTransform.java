package dev.betterlitematica.core;
/** Mirror local X/Z, then clockwise quarter-turns in the X/Z plane, then translate. */
public record PlacementTransform(Vec3i origin, int quarterTurns, boolean mirrorX, boolean mirrorZ) {
    public PlacementTransform { if (origin == null) throw new NullPointerException("origin"); quarterTurns = Math.floorMod(quarterTurns,4); }
    public Vec3i apply(Vec3i p) {
        int x = mirrorX ? Math.negateExact(p.x()) : p.x();
        int z = mirrorZ ? Math.negateExact(p.z()) : p.z();
        for(int i=0;i<quarterTurns;i++) { int old=x; x=Math.negateExact(z); z=old; }
        return new Vec3i(x,p.y(),z).add(origin);
    }
    public Vec3i inverse(Vec3i p) {
        p = p.subtract(origin); int x=p.x(),z=p.z();
        for(int i=0;i<quarterTurns;i++) { int old=x; x=z; z=Math.negateExact(old); }
        return new Vec3i(mirrorX ? Math.negateExact(x):x,p.y(),mirrorZ ? Math.negateExact(z):z);
    }
    /** this(before(p)), including mirrors. A canonical dihedral representation avoids angle-only composition. */
    public PlacementTransform compose(PlacementTransform before){
        Vec3i origin=apply(before.origin()),x=apply(before.apply(new Vec3i(1,0,0))).subtract(origin),z=apply(before.apply(new Vec3i(0,0,1))).subtract(origin);
        for(int turn=0;turn<4;turn++)for(boolean mirror:new boolean[]{false,true}){var candidate=new PlacementTransform(Vec3i.ZERO,turn,mirror,false);if(candidate.apply(new Vec3i(1,0,0)).equals(x)&&candidate.apply(new Vec3i(0,0,1)).equals(z))return new PlacementTransform(origin,turn,mirror,false);}
        throw new IllegalStateException("Non-orthogonal placement");
    }
}
