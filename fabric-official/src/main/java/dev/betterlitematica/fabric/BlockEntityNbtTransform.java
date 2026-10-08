package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import java.util.*;

/** Type-specific directional payloads; unrelated user NBT is never guessed from field names. */
final class BlockEntityNbtTransform {
    static final class UnknownSourceState extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        UnknownSourceState(){super("活塞内部方块状态不存在");}
    }
    private BlockEntityNbtTransform(){}
    static void placed(CompoundTag tag,PlacementTransform transform){
        if(!tag.getStringOr("id","").equals("minecraft:piston"))return;
        if(NbtAccess.contains(tag,"facing",99)){var direction=Direction.from3DDataValue(tag.getIntOr("facing",0));var linear=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ());var next=linear.apply(new Vec3i(direction.getStepX(),direction.getStepY(),direction.getStepZ()));tag.putInt("facing",Direction.getApproximateNearest(next.x(),next.y(),next.z()).get3DDataValue());}
        if(NbtAccess.contains(tag,"blockState",Tag.TAG_COMPOUND)){var original=tag.getCompoundOrEmpty("blockState");var properties=new TreeMap<String,String>();var values=original.getCompoundOrEmpty("Properties");for(String name:values.keySet())properties.put(name,values.getStringOr(name,""));var resolver=new StateResolver1201(List.of(new BlockStateSpec(original.getStringOr("Name",""),properties)),transform);if(resolver.unresolvedState(0))throw new UnknownSourceState();tag.put("blockState",NbtUtils.writeBlockState(resolver.resolve(0)));}
    }
}
