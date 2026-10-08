package dev.betterlitematica.fabric;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/** Only states obtainable by a normal use action are accepted. */
final class EasyPlacementRules {
    static Vec3 hitPoint(BlockPos pos,Direction side,double height){
        return new Vec3(pos.getX()+0.5+side.getStepX()*0.5,
            pos.getY()+(side==Direction.UP?1:side==Direction.DOWN?0:height),
            pos.getZ()+0.5+side.getStepZ()*0.5);
    }
    /** Closest point on the same legal face, for ordinary blocks without hit-height semantics. */
    static Vec3 nearestHitPoint(BlockPos pos,Direction side,Vec3 eye){
        double x=clamp(eye.x,pos.getX()),y=clamp(eye.y,pos.getY()),z=clamp(eye.z,pos.getZ());
        return switch(side){
            case EAST->new Vec3(pos.getX()+1,y,z);
            case WEST->new Vec3(pos.getX(),y,z);
            case UP->new Vec3(x,pos.getY()+1,z);
            case DOWN->new Vec3(x,pos.getY(),z);
            case SOUTH->new Vec3(x,y,pos.getZ()+1);
            case NORTH->new Vec3(x,y,pos.getZ());
        };
    }
    private static double clamp(double eye,int cell){return Math.max(cell+0.001,Math.min(cell+0.999,eye));}
    static boolean matches(BlockState predicted,BlockState wanted){
        if(predicted==null||predicted.getBlock()!=wanted.getBlock())return false;
        if(predicted.equals(wanted))return true;
        if(wanted.hasProperty(BlockStateProperties.SLAB_TYPE)&&wanted.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.DOUBLE){
            return predicted.setValue(BlockStateProperties.SLAB_TYPE,SlabType.DOUBLE).equals(wanted);
        }
        return false;
    }
}
