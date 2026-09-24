package dev.betterlitematica.fabric;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** A block is in reach when some point on its surface is in reach. */
final class PrinterReach {
    private PrinterReach(){}

    static double distanceSquared(Vec3d eye,BlockPos block){
        double dx=axis(eye.x,block.getX());
        double dy=axis(eye.y,block.getY());
        double dz=axis(eye.z,block.getZ());
        return dx*dx+dy*dy+dz*dz;
    }

    private static double axis(double eye,int cell){
        if(eye<cell)return cell-eye;
        if(eye>cell+1)return eye-cell-1;
        return 0;
    }

    /** Covers every reachable cell corner despite rounding the eye to an integer scan center. */
    static double candidateRadius(double reach){return reach+1.5*Math.sqrt(3);}
}
