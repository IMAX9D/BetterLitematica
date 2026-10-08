package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.Direction;

final class EntityNbtTransform {
    private EntityNbtTransform(){}
    static void relative(NbtCompound tag,Vec3i base){
        var pos=NbtAccess.list(tag,"Pos",NbtElement.DOUBLE_TYPE);if(pos.size()==3)position(tag,pos.getDouble(0,0d)-base.x(),pos.getDouble(1,0d)-base.y(),pos.getDouble(2,0d)-base.z());
        if(anchor(tag)){tag.putInt("TileX",tag.getInt("TileX",0)-base.x());tag.putInt("TileY",tag.getInt("TileY",0)-base.y());tag.putInt("TileZ",tag.getInt("TileZ",0)-base.z());}
        for(var child:NbtAccess.list(tag,"Passengers",NbtElement.COMPOUND_TYPE))relative((NbtCompound)child,base);
    }
    static void placed(NbtCompound tag,Vec3i regionOrigin,PlacementTransform transform){
        tag.remove("UUID");var pos=NbtAccess.list(tag,"Pos",NbtElement.DOUBLE_TYPE);
        if(pos.size()==3){double x=pos.getDouble(0,0d)+regionOrigin.x(),y=pos.getDouble(1,0d)+regionOrigin.y(),z=pos.getDouble(2,0d)+regionOrigin.z();
            if(transform.mirrorX())x=1-x;if(transform.mirrorZ())z=1-z;for(int i=0;i<transform.quarterTurns();i++){double old=x;x=1-z;z=old;}
            position(tag,x+transform.origin().x(),y+transform.origin().y(),z+transform.origin().z());
        }
        if(anchor(tag)){
            var anchor=transform.apply(regionOrigin.add(new Vec3i(tag.getInt("TileX",0),tag.getInt("TileY",0),tag.getInt("TileZ",0))));
            tag.putInt("TileX",anchor.x());tag.putInt("TileY",anchor.y());tag.putInt("TileZ",anchor.z());
            if(NbtAccess.contains(tag,"Facing",NbtAccess.NUMERIC))tag.putByte("Facing",(byte)direction(Direction.byIndex(tag.getByte("Facing",(byte)0)),transform).getIndex());
            if(NbtAccess.contains(tag,"facing",NbtAccess.NUMERIC))tag.putByte("facing",(byte)direction(Direction.fromHorizontalQuarterTurns(tag.getByte("facing",(byte)0)),transform).getHorizontalQuarterTurns());
        }
        var rotation=NbtAccess.list(tag,"Rotation",NbtElement.FLOAT_TYPE);if(rotation.size()==2){float yaw=rotation.getFloat(0,0f);if(transform.mirrorX())yaw=-yaw;if(transform.mirrorZ())yaw=180-yaw;yaw+=90*transform.quarterTurns();var next=new NbtList();next.add(NbtFloat.of(yaw));next.add(NbtFloat.of(rotation.getFloat(1,0f)));tag.put("Rotation",next);}
        for(var child:NbtAccess.list(tag,"Passengers",NbtElement.COMPOUND_TYPE))placed((NbtCompound)child,regionOrigin,transform);
    }
    private static boolean anchor(NbtCompound tag){return NbtAccess.contains(tag,"TileX",NbtAccess.NUMERIC)&&NbtAccess.contains(tag,"TileY",NbtAccess.NUMERIC)&&NbtAccess.contains(tag,"TileZ",NbtAccess.NUMERIC);}
    private static Direction direction(Direction direction,PlacementTransform transform){
        Vec3i v=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ()).apply(new Vec3i(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ()));
        return Direction.fromVector(v.x(),v.y(),v.z(),direction);
    }
    private static void position(NbtCompound tag,double x,double y,double z){var next=new NbtList();next.add(NbtDouble.of(x));next.add(NbtDouble.of(y));next.add(NbtDouble.of(z));tag.put("Pos",next);}
}
