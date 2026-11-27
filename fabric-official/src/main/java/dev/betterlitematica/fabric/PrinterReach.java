package dev.betterlitematica.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class PrinterReach {
    private PrinterReach(){}

    static double distanceSquared(Vec3 eye,BlockPos block){
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

    static double candidateRadius(double reach){return reach+1.5*Math.sqrt(3);}

    static boolean intersectsBuildHeight(Vec3 eye,double reach,dev.betterlitematica.core.PrinterRange.Shape shape,int bottom,int top){

        if(shape==dev.betterlitematica.core.PrinterRange.Shape.SPHERE)return eye.y+reach>=bottom&&eye.y-reach<=top;
        long center=Math.round(eye.y),radius=(long)Math.floor(reach);
        return center+radius>=bottom&&center-radius<top;
    }
}
