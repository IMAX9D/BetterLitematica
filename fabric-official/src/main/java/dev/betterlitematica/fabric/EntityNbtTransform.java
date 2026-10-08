package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

final class EntityNbtTransform {
    private EntityNbtTransform(){}
    static void relative(CompoundTag tag,Vec3i base){
        var pos=NbtAccess.list(tag,"Pos",Tag.TAG_DOUBLE);if(pos.size()==3)position(tag,pos.getDoubleOr(0,0d)-base.x(),pos.getDoubleOr(1,0d)-base.y(),pos.getDoubleOr(2,0d)-base.z());
        if(anchor(tag)){tag.putInt("TileX",tag.getIntOr("TileX",0)-base.x());tag.putInt("TileY",tag.getIntOr("TileY",0)-base.y());tag.putInt("TileZ",tag.getIntOr("TileZ",0)-base.z());}
        for(var child:NbtAccess.list(tag,"Passengers",Tag.TAG_COMPOUND))relative((CompoundTag)child,base);
    }
    static void placed(CompoundTag tag,Vec3i regionOrigin,PlacementTransform transform){
        tag.remove("UUID");var pos=NbtAccess.list(tag,"Pos",Tag.TAG_DOUBLE);
        if(pos.size()==3){double x=pos.getDoubleOr(0,0d)+regionOrigin.x(),y=pos.getDoubleOr(1,0d)+regionOrigin.y(),z=pos.getDoubleOr(2,0d)+regionOrigin.z();
            if(transform.mirrorX())x=1-x;if(transform.mirrorZ())z=1-z;for(int i=0;i<transform.quarterTurns();i++){double old=x;x=1-z;z=old;}
            position(tag,x+transform.origin().x(),y+transform.origin().y(),z+transform.origin().z());
        }
        if(anchor(tag)){
            var anchor=transform.apply(regionOrigin.add(new Vec3i(tag.getIntOr("TileX",0),tag.getIntOr("TileY",0),tag.getIntOr("TileZ",0))));
            tag.putInt("TileX",anchor.x());tag.putInt("TileY",anchor.y());tag.putInt("TileZ",anchor.z());
            if(NbtAccess.contains(tag,"Facing",99))tag.putByte("Facing",(byte)direction(Direction.from3DDataValue(tag.getByteOr("Facing",(byte)0)),transform).get3DDataValue());
            if(NbtAccess.contains(tag,"facing",99))tag.putByte("facing",(byte)direction(Direction.from2DDataValue(tag.getByteOr("facing",(byte)0)),transform).get2DDataValue());
        }
        var rotation=NbtAccess.list(tag,"Rotation",Tag.TAG_FLOAT);if(rotation.size()==2){float yaw=rotation.getFloatOr(0,0f);if(transform.mirrorX())yaw=-yaw;if(transform.mirrorZ())yaw=180-yaw;yaw+=90*transform.quarterTurns();var next=new ListTag();next.add(FloatTag.valueOf(yaw));next.add(FloatTag.valueOf(rotation.getFloatOr(1,0f)));tag.put("Rotation",next);}
        for(var child:NbtAccess.list(tag,"Passengers",Tag.TAG_COMPOUND))placed((CompoundTag)child,regionOrigin,transform);
    }
    private static boolean anchor(CompoundTag tag){return NbtAccess.contains(tag,"TileX",99)&&NbtAccess.contains(tag,"TileY",99)&&NbtAccess.contains(tag,"TileZ",99);}
    private static Direction direction(Direction direction,PlacementTransform transform){
        Vec3i v=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ()).apply(new Vec3i(direction.getStepX(),direction.getStepY(),direction.getStepZ()));
        return Direction.getApproximateNearest(v.x(),v.y(),v.z());
    }
    private static void position(CompoundTag tag,double x,double y,double z){var next=new ListTag();next.add(DoubleTag.valueOf(x));next.add(DoubleTag.valueOf(y));next.add(DoubleTag.valueOf(z));tag.put("Pos",next);}
}
