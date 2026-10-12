package dev.betterlitematica.fabric;

import net.minecraft.util.math.Direction;

/**
 * Direction/angle traversal rules adapted from Sodium 0.5.13's OcclusionCuller, by JellySquid and contributors.
 * Upstream: https://github.com/CaffeineMC/sodium/blob/mc1.20.1-0.5.13/src/main/java/me/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller.java
 * PolyForm Shield 1.0.0; see assets/betterlitematica/third-party/sodium-LICENSE.md.
 * Only traversal decisions are adapted: vanilla retains chunk data, scheduling, drawing and resource ownership.
 */
public final class SectionVisibilityGraph {
    private SectionVisibilityGraph() {}

    /** Only expand away from the camera's section. On its coordinate plane both directions remain legal. */
    public static boolean outward(int x,int y,int z,int cameraX,int cameraY,int cameraZ,Direction side) {
        return switch(side) {
            case WEST -> x<=cameraX;
            case EAST -> x>=cameraX;
            case DOWN -> y<=cameraY;
            case UP -> y>=cameraY;
            case NORTH -> z<=cameraZ;
            case SOUTH -> z>=cameraZ;
        };
    }

    /** Opposite faces connect only when their axis is dominant relative to the camera; ties remain visible. */
    public static boolean through(Direction from,Direction to,double dx,double dy,double dz) {
        if(from.getAxis()!=to.getAxis()||from==to)return true;
        double x=Math.abs(dx),y=Math.abs(dy),z=Math.abs(dz);
        return switch(from.getAxis()) {
            case X -> x>=y&&x>=z;
            case Y -> y>=x&&y>=z;
            case Z -> z>=x&&z>=y;
        };
    }
}
