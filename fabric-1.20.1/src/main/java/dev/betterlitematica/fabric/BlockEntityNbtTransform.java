package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.Direction;
import java.util.*;

/** Type-specific directional payloads; unrelated user NBT is never guessed from field names. */
final class BlockEntityNbtTransform {
    private BlockEntityNbtTransform(){}
    static void placed(NbtCompound tag,PlacementTransform transform){
        if(!tag.getString("id").equals("minecraft:piston"))return;
        if(tag.contains("facing",NbtElement.NUMBER_TYPE)){var direction=Direction.byId(tag.getInt("facing"));var linear=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ());var next=linear.apply(new Vec3i(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ()));tag.putInt("facing",Direction.fromVector(next.x(),next.y(),next.z()).getId());}
        if(tag.contains("blockState",NbtElement.COMPOUND_TYPE)){var original=tag.getCompound("blockState");var properties=new TreeMap<String,String>();var values=original.getCompound("Properties");for(String name:values.getKeys())properties.put(name,values.getString(name));var resolver=new StateResolver1201(List.of(new BlockStateSpec(original.getString("Name"),properties)),transform);if(resolver.unresolved(0))throw new IllegalArgumentException("活塞内部方块状态不存在");tag.put("blockState",NbtHelper.fromBlockState(resolver.resolve(0)));}
    }
}
