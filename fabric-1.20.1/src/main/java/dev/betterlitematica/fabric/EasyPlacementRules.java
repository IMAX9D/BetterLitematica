package dev.betterlitematica.fabric;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;

/** Only states obtainable by a normal use action are accepted. */
final class EasyPlacementRules {
    static Vec3d hitPoint(BlockPos pos,Direction side,double height){
        return new Vec3d(pos.getX()+0.5+side.getOffsetX()*0.5,
            pos.getY()+(side==Direction.UP?1:side==Direction.DOWN?0:height),
            pos.getZ()+0.5+side.getOffsetZ()*0.5);
    }
    /** Closest point on the same legal face, for ordinary blocks without hit-height semantics. */
    static Vec3d nearestHitPoint(BlockPos pos,Direction side,Vec3d eye){
        double x=clamp(eye.x,pos.getX()),y=clamp(eye.y,pos.getY()),z=clamp(eye.z,pos.getZ());
        return switch(side){
            case EAST->new Vec3d(pos.getX()+1,y,z);
            case WEST->new Vec3d(pos.getX(),y,z);
            case UP->new Vec3d(x,pos.getY()+1,z);
            case DOWN->new Vec3d(x,pos.getY(),z);
            case SOUTH->new Vec3d(x,y,pos.getZ()+1);
            case NORTH->new Vec3d(x,y,pos.getZ());
        };
    }
    private static double clamp(double eye,int cell){return Math.max(cell+0.001,Math.min(cell+0.999,eye));}
    static boolean matches(BlockState predicted,BlockState wanted){
        if(predicted==null||predicted.getBlock()!=wanted.getBlock())return false;
        if(predicted.equals(wanted))return true;
        if(wanted.contains(Properties.SLAB_TYPE)&&wanted.get(Properties.SLAB_TYPE)==SlabType.DOUBLE){
            return predicted.with(Properties.SLAB_TYPE,SlabType.DOUBLE).equals(wanted);
        }
        return false;
    }
}
