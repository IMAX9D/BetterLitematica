package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.Direction;
import java.util.*;

/** Type-specific directional payloads; unrelated user NBT is never guessed from field names. */
final class BlockEntityNbtTransform {
    static final class UnknownSourceState extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        UnknownSourceState(){super("活塞内部方块状态不存在");}
    }
    private BlockEntityNbtTransform(){}
    static void placed(NbtCompound tag,PlacementTransform transform){
        if(!tag.getString("id","").equals("minecraft:piston"))return;
        if(NbtAccess.contains(tag,"facing",NbtAccess.NUMERIC)){var direction=Direction.byIndex(tag.getInt("facing",0));var linear=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ());var next=linear.apply(new Vec3i(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ()));tag.putInt("facing",Direction.fromVector(next.x(),next.y(),next.z(),direction).getIndex());}
        if(NbtAccess.contains(tag,"blockState",NbtElement.COMPOUND_TYPE)){var original=tag.getCompoundOrEmpty("blockState");var properties=new TreeMap<String,String>();var values=original.getCompoundOrEmpty("Properties");for(String name:values.getKeys())properties.put(name,values.getString(name,""));var resolver=new StateResolver1201(List.of(new BlockStateSpec(original.getString("Name",""),properties)),transform);if(resolver.unresolvedState(0))throw new UnknownSourceState();tag.put("blockState",NbtHelper.fromBlockState(resolver.resolve(0)));}
    }
}
