package dev.betterlitematica.core;
public record Vec3i(int x, int y, int z) {
    public static final Vec3i ZERO = new Vec3i(0,0,0);
    public Vec3i add(Vec3i b) { return new Vec3i(Math.addExact(x,b.x),Math.addExact(y,b.y),Math.addExact(z,b.z)); }
    public Vec3i subtract(Vec3i b) { return new Vec3i(Math.subtractExact(x,b.x),Math.subtractExact(y,b.y),Math.subtractExact(z,b.z)); }
}
