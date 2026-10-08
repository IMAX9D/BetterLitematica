package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.Direction;

final class EntityNbtTransform {
    private EntityNbtTransform(){}
    static void relative(NbtCompound tag,Vec3i base){
        var pos=tag.getList("Pos",NbtElement.DOUBLE_TYPE);if(pos.size()==3)position(tag,pos.getDouble(0)-base.x(),pos.getDouble(1)-base.y(),pos.getDouble(2)-base.z());
        if(anchor(tag)){tag.putInt("TileX",tag.getInt("TileX")-base.x());tag.putInt("TileY",tag.getInt("TileY")-base.y());tag.putInt("TileZ",tag.getInt("TileZ")-base.z());}
        for(var child:tag.getList("Passengers",NbtElement.COMPOUND_TYPE))relative((NbtCompound)child,base);
    }
    static void placed(NbtCompound tag,Vec3i regionOrigin,PlacementTransform transform){
        tag.remove("UUID");var pos=tag.getList("Pos",NbtElement.DOUBLE_TYPE);
        if(pos.size()==3){double x=pos.getDouble(0)+regionOrigin.x(),y=pos.getDouble(1)+regionOrigin.y(),z=pos.getDouble(2)+regionOrigin.z();
            if(transform.mirrorX())x=1-x;if(transform.mirrorZ())z=1-z;for(int i=0;i<transform.quarterTurns();i++){double old=x;x=1-z;z=old;}
            position(tag,x+transform.origin().x(),y+transform.origin().y(),z+transform.origin().z());
        }
        if(anchor(tag)){
            var anchor=transform.apply(regionOrigin.add(new Vec3i(tag.getInt("TileX"),tag.getInt("TileY"),tag.getInt("TileZ"))));
            tag.putInt("TileX",anchor.x());tag.putInt("TileY",anchor.y());tag.putInt("TileZ",anchor.z());
            if(tag.contains("Facing",NbtElement.NUMBER_TYPE))tag.putByte("Facing",(byte)direction(Direction.byId(tag.getByte("Facing")),transform).getId());
            if(tag.contains("facing",NbtElement.NUMBER_TYPE))tag.putByte("facing",(byte)direction(Direction.fromHorizontalQuarterTurns(tag.getByte("facing")),transform).getHorizontalQuarterTurns());
        }
        var rotation=tag.getList("Rotation",NbtElement.FLOAT_TYPE);if(rotation.size()==2){float yaw=rotation.getFloat(0);if(transform.mirrorX())yaw=-yaw;if(transform.mirrorZ())yaw=180-yaw;yaw+=90*transform.quarterTurns();var next=new NbtList();next.add(NbtFloat.of(yaw));next.add(NbtFloat.of(rotation.getFloat(1)));tag.put("Rotation",next);}
        for(var child:tag.getList("Passengers",NbtElement.COMPOUND_TYPE))placed((NbtCompound)child,regionOrigin,transform);
    }
    private static boolean anchor(NbtCompound tag){return tag.contains("TileX",NbtElement.NUMBER_TYPE)&&tag.contains("TileY",NbtElement.NUMBER_TYPE)&&tag.contains("TileZ",NbtElement.NUMBER_TYPE);}
    private static Direction direction(Direction direction,PlacementTransform transform){
        Vec3i v=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ()).apply(new Vec3i(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ()));
        return Direction.fromVector(v.x(),v.y(),v.z(),direction);
    }
    private static void position(NbtCompound tag,double x,double y,double z){var next=new NbtList();next.add(NbtDouble.of(x));next.add(NbtDouble.of(y));next.add(NbtDouble.of(z));tag.put("Pos",next);}
}
